(ns wlj-lang.grammar
  "Grammar spec and custom parselets for wlj (Wolfram-style syntax)."
  (:require [meme.tools.parser :as pratt]
            [meme.tools.lexer :as lexer]
            [wlj-lang.lexlets :as lex]))

;; ---------------------------------------------------------------------------
;; Predicates
;; ---------------------------------------------------------------------------

(defn- next-is-eq? [engine]
  (let [pos (inc (pratt/cursor engine))]
    (and (< pos (pratt/source-len engine))
         (= (.charAt ^String (pratt/source-str engine) pos) \=))))

(defn- next-is-gt? [engine]
  (let [pos (inc (pratt/cursor engine))]
    (and (< pos (pratt/source-len engine))
         (= (.charAt ^String (pratt/source-str engine) pos) \>))))

(defn- next-is-amp? [engine]
  (let [pos (inc (pratt/cursor engine))]
    (and (< pos (pratt/source-len engine))
         (= (.charAt ^String (pratt/source-str engine) pos) \&))))

(defn- next-is-pipe? [engine]
  (let [pos (inc (pratt/cursor engine))]
    (and (< pos (pratt/source-len engine))
         (= (.charAt ^String (pratt/source-str engine) pos) \|))))

(defn- is-block-comment? [engine]
  (let [pos (inc (pratt/cursor engine))]
    (and (< pos (pratt/source-len engine))
         (= (.charAt ^String (pratt/source-str engine) pos) \*))))

;; ---------------------------------------------------------------------------
;; Custom parselets
;; ---------------------------------------------------------------------------

(defn- two-char-infix [node-type bp]
  (fn [engine lhs op-tok]
    (pratt/advance! engine 1)
    (let [rhs (pratt/parse-expr engine bp)]
      (pratt/cst node-type {:left lhs :token op-tok :right rhs}))))

(defn- lt-or-lte-scanlet [engine lhs op-tok]
  (if (and (not (pratt/eof? engine)) (= (pratt/peek-char engine) \=))
    (do (pratt/advance! engine 1)
        (let [rhs (pratt/parse-expr engine 360)]
          (pratt/cst :lte {:left lhs :token op-tok :right rhs})))
    (let [rhs (pratt/parse-expr engine 360)]
      (pratt/cst :lt {:left lhs :token op-tok :right rhs}))))

(defn- gt-or-gte-scanlet [engine lhs op-tok]
  (if (and (not (pratt/eof? engine)) (= (pratt/peek-char engine) \=))
    (do (pratt/advance! engine 1)
        (let [rhs (pratt/parse-expr engine 360)]
          (pratt/cst :gte {:left lhs :token op-tok :right rhs})))
    (let [rhs (pratt/parse-expr engine 360)]
      (pratt/cst :gt {:left lhs :token op-tok :right rhs}))))

;; Call: f[x, y] — no adjacency needed
(defn- call-scanlet [engine lhs open-tok]
  ;; Check for double bracket [[
  (if (and (not (pratt/eof? engine)) (= (pratt/peek-char engine) \[))
    ;; Part: expr[[i]]
    (let [start2 (pratt/cursor engine)]
      (pratt/advance! engine 1)
      (let [_open2 (pratt/make-token! engine :open-bracket start2)
            [args close-tok] (pratt/parse-until engine \] :close-bracket)]
        ;; Expect second ]
        (pratt/skip-trivia! engine)
        (if (and (not (pratt/eof? engine)) (= (pratt/peek-char engine) \]))
          (let [start3 (pratt/cursor engine)]
            (pratt/advance! engine 1)
            (let [_close2 (pratt/make-token! engine :close-bracket start3)]
              (pratt/cst :part {:expr lhs :token open-tok :args args :close close-tok})))
          (pratt/cst :error {:message "Expected ]] for Part" :token open-tok}))))
    ;; Regular call: f[x, y]
    (let [[args close-tok] (pratt/parse-until engine \] :close-bracket)]
      (pratt/cst :call {:head lhs :open open-tok :args args :close close-tok}))))

;; Compound: a ; b ; c
(defn- compound-scanlet [engine lhs semi-tok]
  (pratt/skip-trivia! engine)
  (if (or (pratt/eof? engine) (= (pratt/peek-char engine) \)))
    ;; Trailing semicolon — return lhs with null marker
    (pratt/cst :compound {:left lhs :token semi-tok :right nil})
    (let [rhs (pratt/parse-expr engine 30)]
      (pratt/cst :compound {:left lhs :token semi-tok :right rhs}))))

;; Rule: a -> b
(defn- rule-scanlet [engine lhs op-tok]
  (pratt/advance! engine 1) ;; consume >
  (let [rhs (pratt/parse-expr engine 139)] ;; right-assoc
    (pratt/cst :rule {:left lhs :token op-tok :right rhs})))

;; Assignment: a = b (but not ==)
(defn- assign-scanlet [engine lhs op-tok]
  (let [rhs (pratt/parse-expr engine 49)] ;; right-assoc
    (pratt/cst :assign {:left lhs :token op-tok :right rhs})))

;; Association: <| ... |>
(defn- association-scanlet [engine]
  (let [start (pratt/cursor engine)]
    ;; Consume <|
    (pratt/advance! engine 2)
    (let [open-tok (pratt/make-token! engine :open-assoc start)]
      ;; Parse until |>
      (loop [entries []]
        (pratt/skip-trivia! engine)
        (cond
          (pratt/eof? engine)
          (pratt/cst :error {:message "Unterminated association <|...|>" :token open-tok})

          (and (= (pratt/peek-char engine) \|)
               (let [p (inc (pratt/cursor engine))]
                 (and (< p (pratt/source-len engine))
                      (= (.charAt ^String (pratt/source-str engine) p) \>))))
          (let [close-start (pratt/cursor engine)]
            (pratt/advance! engine 2)
            (let [close-tok (pratt/make-token! engine :close-assoc close-start)]
              (pratt/cst :association {:open open-tok :entries entries :close close-tok})))

          :else
          (let [entry (pratt/parse-expr engine 0)]
            (recur (conj entries entry))))))))

;; Comment trivia: ( followed by * → block comment, otherwise nil
(defn- paren-comment-trivia [engine]
  (when (is-block-comment? engine)
    (lex/block-comment-consumer engine)))

;; ---------------------------------------------------------------------------
;; Grammar spec
;; ---------------------------------------------------------------------------

(def grammar
  {:nud
   {\( (lexer/single-char-scanlet :open-paren
         (pratt/nud-delimited :group \) :close-paren))
    \{ (lexer/single-char-scanlet :open-brace
         (pratt/nud-delimited :list \} :close-brace))
    \- (lexer/single-char-scanlet :minus (pratt/nud-prefix :unary-minus 580))
    \! (lexer/single-char-scanlet :bang (pratt/nud-prefix :unary-not 300))
    \" (lexer/atom-scanlet :string lex/consume-string)}

   :nud-pred
   [[(fn [ch _e] (lex/digit? ch))       (lexer/atom-scanlet :number lex/consume-number)]
    [(fn [ch _e] (lex/ident-start? ch)) (lexer/atom-scanlet :identifier lex/consume-identifier)]
    [(fn [ch e] (and (= ch \<)
                     (let [p (inc (pratt/cursor e))]
                       (and (< p (pratt/source-len e))
                            (= (.charAt ^String (pratt/source-str e) p) \|)))))
     association-scanlet]]

   :trivia
   {\space   lex/ws-consumer
    \tab     lex/ws-consumer
    \,       lex/ws-consumer
    \newline lex/newline-consumer
    \return  lex/newline-consumer
    \(       paren-comment-trivia}

   :led
   [;; Call — always, no adjacency
    {:char \[ :bp 890 :open-type :open-bracket :fn call-scanlet}
    ;; Arithmetic
    {:char \^ :bp 750 :open-type :caret   :fn (pratt/led-infix :pow 749)}
    {:char \* :bp 500 :open-type :star    :fn (pratt/led-infix :mul 500)}
    {:char \/ :bp 570 :open-type :slash   :fn (pratt/led-infix :div 570)}
    {:char \+ :bp 400 :open-type :plus    :fn (pratt/led-infix :add 400)}
    {:char \- :bp 400 :open-type :minus   :fn (pratt/led-infix :sub 400)
     :when (fn [e] (not (next-is-gt? e)))}
    ;; Comparison
    {:char \= :bp 360 :open-type :eq-eq   :fn (two-char-infix :eq 360)   :when next-is-eq?}
    {:char \! :bp 360 :open-type :neq     :fn (two-char-infix :neq 360)  :when next-is-eq?}
    {:char \< :bp 360 :open-type :lt      :fn lt-or-lte-scanlet}
    {:char \> :bp 360 :open-type :gt      :fn gt-or-gte-scanlet}
    ;; Logical
    {:char \& :bp 290 :open-type :and     :fn (two-char-infix :and 290)  :when next-is-amp?}
    {:char \| :bp 270 :open-type :or      :fn (two-char-infix :or 270)   :when next-is-pipe?}
    ;; Rule
    {:char \- :bp 140 :open-type :arrow   :fn rule-scanlet               :when next-is-gt?}
    ;; Assignment (= but not ==)
    {:char \= :bp 50  :open-type :assign  :fn assign-scanlet}
    ;; Compound
    {:char \; :bp 30  :open-type :semi    :fn compound-scanlet}]

   :max-depth 512})
