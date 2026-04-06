(ns wlj-lang.grammar
  "Grammar spec and custom parselets for wlj (Wolfram-style syntax)."
  (:require [meme.tools.parser :as pratt]
            [meme.tools.lexer :as lexer]
            [wlj-lang.lexlets :as lex]))

;; ---------------------------------------------------------------------------
;; Predicates
;; ---------------------------------------------------------------------------

(def ^:private next-is-eq?  (pratt/next-char-is? \=))
(def ^:private next-is-gt?  (pratt/next-char-is? \>))
(def ^:private next-is-amp? (pratt/next-char-is? \&))
(def ^:private next-is-pipe? (pratt/next-char-is? \|))

(defn- is-block-comment? [engine]
  (let [pos (inc (pratt/cursor engine))]
    (and (< pos (pratt/source-len engine))
         (= (.charAt ^String (pratt/source-str engine) pos) \*))))

;; ---------------------------------------------------------------------------
;; Custom parselets
;; ---------------------------------------------------------------------------

(def ^:private lt-or-lte (pratt/led-comparison-or-equal :lt :lte 360))
(def ^:private gt-or-gte (pratt/led-comparison-or-equal :gt :gte 360))

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
    {:char \/ :bp 70  :open-type :pipe    :fn (pratt/led-infix-2char :postfix-apply 70)
     :when (pratt/next-char-is? \/)}
    {:char \/ :bp 570 :open-type :slash   :fn (pratt/led-infix :div 570)
     :when (pratt/next-char-is-not? \/)}
    {:char \+ :bp 400 :open-type :plus    :fn (pratt/led-infix :add 400)}
    {:char \- :bp 400 :open-type :minus   :fn (pratt/led-infix :sub 400)
     :when (pratt/next-char-is-not? \>)}
    ;; Comparison
    {:char \= :bp 360 :open-type :eq-eq   :fn (pratt/led-infix-2char :eq 360)   :when next-is-eq?}
    {:char \! :bp 360 :open-type :neq     :fn (pratt/led-infix-2char :neq 360)  :when next-is-eq?}
    {:char \< :bp 360 :open-type :lt      :fn lt-or-lte}
    {:char \> :bp 360 :open-type :gt      :fn gt-or-gte}
    ;; Logical
    {:char \& :bp 290 :open-type :and     :fn (pratt/led-infix-2char :and 290)  :when next-is-amp?}
    {:char \| :bp 270 :open-type :or      :fn (pratt/led-infix-2char :or 270)   :when next-is-pipe?}
    ;; Rule
    {:char \- :bp 140 :open-type :arrow   :fn rule-scanlet               :when next-is-gt?}
    ;; Assignment (= but not ==)
    {:char \= :bp 50  :open-type :assign  :fn assign-scanlet}
    ;; Compound
    {:char \; :bp 30  :open-type :semi    :fn (pratt/led-compound :compound 30)}]

   :max-depth 512})
