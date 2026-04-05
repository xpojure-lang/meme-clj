(ns meme.tools.parser
  "Unified scanlet-parselet Pratt parser.

   The engine reads directly from a source string. The grammar spec defines
   everything language-specific: character dispatch (scanlets), trivia
   classification, prefix parselets (nud), and postfix rules (led).

   A scanlet is (fn [engine] → CST node). It consumes characters from the
   source string using engine primitives and produces a CST node.

   Grammar spec shape:
     {:nud        {char → scanlet-fn}
      :nud-pred   [[pred scanlet-fn] ...]
      :trivia     {char → trivia-consumer-fn}
      :trivia-pred [[pred trivia-consumer-fn] ...]
      :led        [{:char c :bp n :when pred :fn scanlet-fn} ...]}

   Parselet factories (nud-atom, nud-prefix, nud-delimited, etc.) generate
   common patterns. Custom parselets are plain functions."
)

;; ---------------------------------------------------------------------------
;; CST node constructor
;; ---------------------------------------------------------------------------

(defn cst
  "Create a CST node."
  [type fields]
  (assoc fields :node type))

;; ---------------------------------------------------------------------------
;; Line/col computation
;; ---------------------------------------------------------------------------

(defn- build-line-starts
  "Precompute a vector of character offsets where each line begins.
   Line 1 starts at offset 0."
  [^String source]
  (let [len (count source)]
    (loop [i 0
           starts (transient [0])]
      (if (>= i len)
        (persistent! starts)
        (let [ch (.charAt source i)]
          (cond
            (= ch \newline)
            (recur (inc i) (conj! starts (inc i)))

            (= ch \return)
            (if (and (< (inc i) len) (= (.charAt source (inc i)) \newline))
              (recur (+ i 2) (conj! starts (+ i 2)))
              (recur (inc i) (conj! starts (inc i))))

            :else
            (recur (inc i) starts)))))))

(defn- binary-search-line
  "Find the line index (0-based) for a given offset using the line-starts vector.
   Returns the index of the largest line-start <= offset."
  [line-starts offset]
  (loop [lo 0
         hi (dec (count line-starts))]
    (if (>= lo hi)
      lo
      (let [mid (+ lo (quot (- hi lo 1) 2) 1)]
        (if (<= (nth line-starts mid) offset)
          (recur mid hi)
          (recur lo (dec mid)))))))

(defn pos-at
  "Compute {:line :col} for a character offset. Line/col are 1-indexed."
  [engine offset]
  (let [line-starts (:line-starts engine)
        line-idx (binary-search-line line-starts offset)]
    {:line (inc line-idx)
     :col (inc (- offset (nth line-starts line-idx)))}))

;; ---------------------------------------------------------------------------
;; Engine state
;; ---------------------------------------------------------------------------

(defn make-engine [source spec]
  {:source      source
   :len         (count source)
   :pos         (volatile! 0)
   :depth       (volatile! 0)
   :line-starts (build-line-starts source)
   :trivia-acc  (volatile! [])
   :spec        spec})

;; ---------------------------------------------------------------------------
;; Engine primitives (public — used by grammar scanlets)
;; ---------------------------------------------------------------------------

(defn peek-char
  "Character at cursor, or nil if at EOF."
  ([engine]
   (let [i @(:pos engine)]
     (when (< i (:len engine))
       (.charAt ^String (:source engine) i))))
  ([engine offset]
   (let [i (+ @(:pos engine) offset)]
     (when (< i (:len engine))
       (.charAt ^String (:source engine) i)))))

(defn cursor
  "Current cursor position in source."
  [engine]
  @(:pos engine))

(defn advance!
  "Move cursor forward by n characters."
  [engine n]
  (vswap! (:pos engine) + n)
  nil)

(defn set-pos!
  "Set cursor to an absolute position."
  [engine pos]
  (vreset! (:pos engine) pos)
  nil)

(defn eof?
  "Is cursor at or past end of source?"
  [engine]
  (>= @(:pos engine) (:len engine)))

(defn source-str
  "The full source string."
  [engine]
  (:source engine))

(defn source-len
  "Length of source string."
  [engine]
  (:len engine))

;; ---------------------------------------------------------------------------
;; Token production
;; ---------------------------------------------------------------------------

(defn make-token!
  "Produce a token map covering [start, cursor). Drains trivia-acc and attaches
   as :trivia/before. Token shape is identical to the legacy tokenizer output."
  [engine type start]
  (let [end @(:pos engine)
        raw (subs (:source engine) start end)
        {:keys [line col]} (pos-at engine start)
        trivia @(:trivia-acc engine)
        tok (cond-> {:type type :raw raw :offset start :line line :col col}
              (seq trivia) (assoc :trivia/before trivia))]
    (vreset! (:trivia-acc engine) [])
    tok))

(defn make-trivia-token!
  "Produce a trivia token covering [start, cursor). Does NOT drain trivia-acc.
   Used by trivia consumers — the token is pushed onto trivia-acc by skip-trivia!."
  [engine type start]
  (let [end @(:pos engine)
        raw (subs (:source engine) start end)
        {:keys [line col]} (pos-at engine start)]
    {:type type :raw raw :offset start :line line :col col}))

;; ---------------------------------------------------------------------------
;; Trivia
;; ---------------------------------------------------------------------------

(defn- trivia-pred-match
  "Check :trivia-pred fallback list for a matching trivia consumer.
   Predicates are (fn [ch] → bool)."
  [engine ch]
  (when-let [preds (get-in engine [:spec :trivia-pred])]
    (some (fn [[pred consumer]]
            (when (pred ch) consumer))
          preds)))

(defn skip-trivia!
  "Consume trivia characters, accumulating trivia tokens in trivia-acc.
   A trivia consumer may return nil to signal no match (e.g., BOM not at
   position 0). In that case, stop the trivia loop."
  [engine]
  (loop []
    (when-not (eof? engine)
      (let [ch (peek-char engine)
            consumer (or (get (:trivia (:spec engine)) ch)
                         (trivia-pred-match engine ch))]
        (when consumer
          (let [tok (consumer engine)]
            (when tok
              (vswap! (:trivia-acc engine) conj tok)
              (recur))))))))

;; ---------------------------------------------------------------------------
;; Forward declarations
;; ---------------------------------------------------------------------------

(declare parse-expr)

;; ---------------------------------------------------------------------------
;; Close-delimiter handling
;; ---------------------------------------------------------------------------

(defn expect-close!
  "If the current character matches close-char, consume it and return a close
   token of the given type. Otherwise return nil (unclosed delimiter)."
  [engine close-char close-type]
  (skip-trivia! engine)
  (when (and (not (eof? engine)) (= (peek-char engine) close-char))
    (let [start (cursor engine)]
      (advance! engine 1)
      (make-token! engine close-type start))))

(defn parse-until
  "Parse expressions until a closing character. Returns [nodes close-tok].
   close-tok is nil if EOF is reached before the close character."
  [engine close-char close-type]
  (loop [nodes []]
    (skip-trivia! engine)
    (cond
      (eof? engine)
      [nodes nil]

      (= (peek-char engine) close-char)
      (let [start (cursor engine)]
        (advance! engine 1)
        [nodes (make-token! engine close-type start)])

      :else
      (recur (conj nodes (parse-expr engine 0))))))

;; ---------------------------------------------------------------------------
;; Parselet factories — generate common nud/led functions
;; ---------------------------------------------------------------------------

(defn nud-atom
  "Factory: atom parselet (leaf node, no operands)."
  [_node-type]
  (fn [_engine tok]
    (cst :atom {:token tok})))

(defn nud-prefix
  "Factory: prefix parselet (consume 1 operand)."
  ([node-type] (nud-prefix node-type 0))
  ([node-type bp]
   (fn [engine tok]
     (let [form (parse-expr engine bp)]
       (cst node-type {:token tok :form form})))))

(defn nud-prefix-two
  "Factory: prefix parselet consuming 2 operands (e.g., metadata)."
  ([node-type first-key second-key] (nud-prefix-two node-type first-key second-key 0))
  ([node-type first-key second-key bp]
   (fn [engine tok]
     (let [a (parse-expr engine bp)
           b (parse-expr engine bp)]
       (cst node-type {:token tok first-key a second-key b})))))

(defn nud-delimited
  "Factory: delimited parselet (consume children until close char)."
  [node-type close-char close-type]
  (fn [engine open-tok]
    (let [[children close-tok] (parse-until engine close-char close-type)]
      (cst node-type {:open open-tok :children children :close close-tok}))))

(defn nud-empty-or-error
  "Factory: empty-or-error parselet (empty list or bare parens error)."
  [empty-node-type close-char close-type error-message]
  (fn [engine open-tok]
    (skip-trivia! engine)
    (if (and (not (eof? engine)) (= (peek-char engine) close-char))
      ;; Empty: ()
      (let [start (cursor engine)]
        (advance! engine 1)
        (cst empty-node-type {:open open-tok :close (make-token! engine close-type start)}))
      ;; Non-empty parens without head: error
      (let [[children close-tok] (parse-until engine close-char close-type)]
        (cst :error {:message error-message
                     :open open-tok :children children :close close-tok})))))

(defn nud-prefixed-delimited
  "Factory: prefixed-delimited parselet (prefix token + delimited body).
   extra-fn: optional (fn [prefix-tok] → extra-fields-map)."
  ([node-type open-char open-type close-char close-type]
   (nud-prefixed-delimited node-type open-char open-type close-char close-type nil))
  ([node-type open-char open-type close-char close-type extra-fn]
   (fn [engine ns-tok]
     (skip-trivia! engine)
     (let [open-tok (when (and (not (eof? engine)) (= (peek-char engine) open-char))
                      (let [start (cursor engine)]
                        (advance! engine 1)
                        (make-token! engine open-type start)))
           [children close-tok] (if open-tok
                                  (parse-until engine close-char close-type)
                                  [[] nil])]
       (cst node-type (merge {:ns ns-tok :open open-tok
                              :children children :close close-tok}
                             (when extra-fn (extra-fn ns-tok))))))))

(defn led-call
  "Factory: call parselet (head + delimited args)."
  [node-type close-char close-type]
  (fn [engine lhs open-tok]
    (let [[args close-tok] (parse-until engine close-char close-type)]
      (cst node-type {:head lhs :open open-tok :args args :close close-tok}))))

(defn led-infix
  "Factory: binary infix parselet."
  ([node-type] (led-infix node-type nil))
  ([node-type right-bp]
   (fn [engine lhs op-tok]
     (let [rhs (parse-expr engine (or right-bp 0))]
       (cst node-type {:left lhs :token op-tok :right rhs})))))

;; ---------------------------------------------------------------------------
;; Nud dispatch
;; ---------------------------------------------------------------------------

(defn- nud-pred-match
  "Check :nud-pred fallback list for a matching nud parselet.
   Predicates are (fn [ch engine] → bool) — receive both the character
   and the engine for lookahead."
  [engine ch]
  (when-let [preds (get-in engine [:spec :nud-pred])]
    (some (fn [[pred parselet]]
            (when (pred ch engine) parselet))
          preds)))

;; ---------------------------------------------------------------------------
;; Led matching
;; ---------------------------------------------------------------------------

(defn- matching-led
  "Find the first led rule matching the current character at the given min-bp."
  [engine min-bp]
  (when-not (eof? engine)
    (let [ch (peek-char engine)]
      (first (filter (fn [rule]
                       (and (= (:char rule) ch)
                            (> (:bp rule) min-bp)
                            (if-let [pred (:when rule)]
                              (pred engine)
                              true)))
                     (:led (:spec engine)))))))

;; ---------------------------------------------------------------------------
;; Core Pratt loop
;; ---------------------------------------------------------------------------

(defn parse-expr
  "Parse one expression at the given minimum binding power."
  [engine min-bp]
  (skip-trivia! engine)
  (if (eof? engine)
    (cst :error {:message "Unexpected end of input"})
    (let [max-depth (:max-depth (:spec engine))
          d (vswap! (:depth engine) inc)]
      (if (and max-depth (> d max-depth))
        (do (vswap! (:depth engine) dec)
            (throw (ex-info "Maximum nesting depth exceeded"
                            (pos-at engine @(:pos engine)))))
        (let [ch (peek-char engine)
              nud-fn (or (get (:nud (:spec engine)) ch)
                         (nud-pred-match engine ch))
              lhs (if nud-fn
                    (nud-fn engine)
                    ;; Unknown character — consume one byte as :invalid
                    (let [start (cursor engine)]
                      (advance! engine 1)
                      (cst :error {:token (make-token! engine :invalid start)
                                   :message (str "Unexpected token: :invalid")})))
              result
              (loop [lhs lhs]
                (skip-trivia! engine)
                (if-let [led (matching-led engine min-bp)]
                  ;; Led matched — consume the opening char and call the led parselet
                  (let [start (cursor engine)]
                    (advance! engine 1)
                    (let [open-tok (make-token! engine (:open-type led) start)]
                      (recur ((:fn led) engine lhs open-tok))))
                  lhs))]
          (vswap! (:depth engine) dec)
          result)))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn parse
  "Parse a source string into a vector of CST nodes using the given grammar spec."
  [source spec]
  (if (zero? (count source))
    []
    (let [engine (make-engine source spec)]
      (loop [nodes []]
        (skip-trivia! engine)
        (if (eof? engine)
          (let [trailing @(:trivia-acc engine)]
            (if (seq trailing)
              (with-meta nodes {:trivia/after trailing})
              nodes))
          (recur (conj nodes (parse-expr engine 0))))))))

