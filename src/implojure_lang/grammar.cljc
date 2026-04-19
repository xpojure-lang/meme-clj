(ns implojure-lang.grammar
  "Implojure-lang grammar spec — infix meme.

   Implojure is meme (M-expressions) plus a fixed set of conventional infix
   operators. Infix parselets emit `:call` CST nodes whose head is a
   synthetic symbol atom, so every module downstream of the parser
   (cst-reader, printer, formatter, stages, run, repl) reuses meme's
   implementation unchanged — only the grammar is new.

   Operators (bp = binding power, higher = tighter):
     *, /, mod     bp 70
     +, -          bp 60
     <, <=, >, >=  bp 50
     =, not=       bp 40
     and           bp 35
     or            bp 30
     |slot|>       bp 20   — named pipeline. The slot is any tight meme
                              expression (bp 100) — a symbol (`|n|>`), a
                              map destructure (`|{:keys [a b]}|>`), or a
                              vector destructure (`|[x y]|>`). Chains
                              collapse into a single flat
                              `(let [slot₁ x, slot₂ e₁, …] final-e)`.
     not           nud prefix, parses operand at bp 35

   All binary operators are left-associative. Word operators (`and`,
   `or`, `mod`, `not=`, `not`) require a word boundary after them —
   `andrew` / `modern` / `orbital` stay symbols.

   The named-pipeline operator `|name|>` matches only when the `|name|>`
   shape is present at cursor; otherwise `|` falls through to Clojure's
   regular symbol parsing, so `|foo|` stays readable as a symbol."
  (:require [meme.tools.parser :as pratt]
            [meme.tools.lexer :as lexer]
            [meme-lang.grammar :as meme-grammar]
            [meme-lang.lexlets :as lex]))

;; ---------------------------------------------------------------------------
;; Synthetic CST node helpers
;; ---------------------------------------------------------------------------

(defn- op-atom
  "Build a synthetic :atom CST node carrying the operator symbol.
   Reuses op-tok's location; replaces :type/:raw so meme's cst-reader
   resolves the head to op-name."
  [op-tok op-name]
  (pratt/cst :atom {:token (-> op-tok
                               (assoc :type :symbol :raw op-name)
                               (dissoc :trivia/before))}))

(defn- make-call
  "Emit a :call CST node with op-name as head and the given arg vector.
   :open and :close reuse op-tok — the meme cst-reader only checks
   :close for truthiness and reads :open's trivia/before, which is
   stripped on the synthetic head."
  [op-tok op-name args]
  (pratt/cst :call {:head  (op-atom op-tok op-name)
                    :open  op-tok
                    :args  (vec args)
                    :close op-tok}))

;; ---------------------------------------------------------------------------
;; Binary infix parselets
;; ---------------------------------------------------------------------------

(defn- led-infix-call
  "Factory: binary infix that lowers to `(op lhs rhs)`. Left-associative.
   extra-chars is the number of additional characters to consume after
   the dispatch loop's single-char consume (1 for two-char ops like `or`,
   2 for `and`, 3 for `not=`, 0 for single-char ops like `+`)."
  [op-name bp extra-chars]
  (fn [engine lhs op-tok]
    (when (pos? extra-chars) (pratt/advance! engine extra-chars))
    (let [rhs (pratt/parse-expr engine bp)]
      (make-call op-tok op-name [lhs rhs]))))

(defn- led-compare-or-eq
  "Factory: led parselet for < or > with an optional trailing = (<=, >=)."
  [base-name eq-name bp]
  (fn [engine lhs op-tok]
    (if (and (not (pratt/eof? engine)) (= (pratt/peek-char engine) \=))
      (do (pratt/advance! engine 1)
          (make-call op-tok eq-name [lhs (pratt/parse-expr engine bp)]))
      (make-call op-tok base-name [lhs (pratt/parse-expr engine bp)]))))

;; ---------------------------------------------------------------------------
;; Word-boundary predicate
;; ---------------------------------------------------------------------------

(defn- word-at-cursor?
  "Returns a predicate on `engine` that's true iff the source at cursor
   starts with `word` and the char right after `word` is either EOF or
   a non-symbol-continuation char."
  [word]
  (let [word-len (count word)]
    (fn [engine]
      (let [pos (pratt/cursor engine)
            src (pratt/source-str engine)
            len (pratt/source-len engine)]
        (and (<= (+ pos word-len) len)
             (= word (subs src pos (+ pos word-len)))
             (or (>= (+ pos word-len) len)
                 (not (lex/symbol-char? (.charAt ^String src (+ pos word-len))))))))))

;; ---------------------------------------------------------------------------
;; Not-adjacent-call predicates
;;
;; Meme's convention is `name(args)` is a call, regardless of what
;; `name` is. We preserve that here so that `+(1 2)`, `<=(1 2)`,
;; `and(x y)`, and `not=(x y)` all read as calls — the operator is
;; just the head symbol, not an infix. Each infix led gates on a
;; predicate that rejects it when an open-paren follows the operator
;; with no trivia between.
;; ---------------------------------------------------------------------------

(defn- not-adjacent-call?
  "Factory: returns a :when that rejects infix when `(` appears `offset`
   chars past cursor. Use 1 for single-char ops like +, -, *, /."
  [offset]
  (fn [engine]
    (not= (pratt/peek-char engine offset) \()))

(defn- compare-led-when
  "Gate for `<` / `>` leds. At cursor the operator's first char has
   not yet been consumed. Reject when the operator would be `<(` /
   `>(` or `<=(` / `>=(` — all four are call-head forms, not infix."
  [engine]
  (case (pratt/peek-char engine 1)
    \= (not= (pratt/peek-char engine 2) \()
    \( false
    true))

(defn- word-op-when
  "Compose word-boundary matching with the not-adjacent-call guard.
   Makes `and(x)` / `or(x)` / `mod(x)` / `not=(x y)` read as calls,
   not as infix operators with bogus RHS."
  [word]
  (let [word-len (count word)
        matcher  (word-at-cursor? word)]
    (fn [engine]
      (and (matcher engine)
           (not= (pratt/peek-char engine word-len) \()))))

;; ---------------------------------------------------------------------------
;; Named-pipeline operator `|name|>`
;; ---------------------------------------------------------------------------

(defn- find-pipe-close
  "Scan from `start` in `src` looking for the `|` of a closing `|>`
   with balanced `{}`, `[]`, `()` in between. Returns the position of
   the `|` in `|>` if found, else nil."
  [^String src ^long len ^long start]
  (loop [i start depth 0]
    (cond
      (>= (inc i) len) nil
      (and (zero? depth)
           (= (.charAt src i) \|)
           (= (.charAt src (inc i)) \>)
           (> i start))
      i
      (or (= (.charAt src i) \{) (= (.charAt src i) \[) (= (.charAt src i) \())
      (recur (inc i) (inc depth))
      (or (= (.charAt src i) \}) (= (.charAt src i) \]) (= (.charAt src i) \)))
      (recur (inc i) (dec depth))
      (and (zero? depth) (= (.charAt src i) \|))
      nil
      :else (recur (inc i) depth))))

(defn- pipe-op-at-cursor?
  "Matches iff cursor is on `|` and a closing `|>` (with balanced
   brackets in between) exists ahead. `|foo|` without `|>` still
   falls through to regular symbol parsing."
  [engine]
  (boolean
    (find-pipe-close (pratt/source-str engine)
                     (pratt/source-len engine)
                     (inc (pratt/cursor engine)))))

(defn- pipe-let-call
  "Build a :call CST node for `(let [binding-pairs...] body)` tagged with
   ::pipe-chain? so subsequent led-as-pipe steps can detect and extend it
   in place instead of nesting."
  [op-tok binding-pairs body]
  (assoc (pratt/cst :call
                    {:head  (op-atom op-tok "let")
                     :open  op-tok
                     :args  [(pratt/cst :vector
                                        {:open op-tok
                                         :children (vec binding-pairs)
                                         :close op-tok})
                             body]
                     :close op-tok})
         ::pipe-chain? true))

(defn- led-as-pipe
  "Factory: led parselet for `|<slot>|>`. The slot is parsed as a tight
   meme expression (bp 100) against a bounded view of the source that
   ends at the closing `|>` — this prevents meme's symbol consumer
   (which treats `|` and `>` as symbol chars) from overshooting.

   A bare symbol (`|n|>`), a map destructure (`|{:keys [a b]}|>`), or
   a vector destructure (`|[a b]|>`) all parse correctly and feed
   straight into the emitted `let` as a binding pattern.

   Chaining lowers to one flat let:
     x |n|> e1 |n|> e2 ... |n|> eN  →  (let [n x, n e1, ..., n e(N-1)] eN)

   Detection: if lhs is a call CST previously tagged ::pipe-chain?, its
   binding vector is extended in place (the old body becomes the value
   for the new binding, the new rhs is the new body). Otherwise a fresh
   tagged let-call is emitted."
  [bp]
  (fn [engine lhs op-tok]
    (let [src       (pratt/source-str engine)
          full-len  (pratt/source-len engine)
          start-pos (pratt/cursor engine)
          close-pos (find-pipe-close src full-len start-pos)]
      (if (nil? close-pos)
        (pratt/cst :error
                   {:message "Pipe slot not terminated by `|>`"
                    :token op-tok})
        ;; Bounded parse of the slot — :len capped at close-pos. The
        ;; engine's :pos volatile is shared, so position updates here
        ;; are visible to the outer engine.
        (let [bounded  (assoc engine :len close-pos)
              slot-cst (pratt/parse-expr bounded 100)]
          (pratt/set-pos! engine (+ close-pos 2))
          (let [rhs (pratt/parse-expr engine bp)]
            (if (and (= :call (:node lhs)) (::pipe-chain? lhs))
              (let [[bindings-vec prior-body] (:args lhs)
                    extended (update bindings-vec :children conj slot-cst prior-body)]
                (assoc lhs :args [extended rhs]))
              (pipe-let-call op-tok [slot-cst lhs] rhs))))))))

;; ---------------------------------------------------------------------------
;; Unary `not` — nud position
;; ---------------------------------------------------------------------------

(def ^:private not-word? (word-at-cursor? "not"))

(defn- not-nud-pred
  "Nud-pred: fires when cursor is at `not` with a trailing word boundary."
  [ch engine]
  (and (= ch \n) (not-word? engine)))

(defn- not-nud-scanlet
  "Nud parselet for unary `not`. Consumes `not` then parses one operand
   at bp 35 (just above `and`), so `not x = y` groups as `(not (= x y))`
   and `not x and y` groups as `(and (not x) y)`."
  [engine]
  (let [start (pratt/cursor engine)]
    (pratt/advance! engine 3)
    (let [op-tok   (pratt/make-token! engine :not-op start)
          operand  (pratt/parse-expr engine 35)]
      (make-call op-tok "not" [operand]))))

;; ---------------------------------------------------------------------------
;; Grouping parens in nud position
;; ---------------------------------------------------------------------------

(defn- nud-group-or-empty
  "Nud parselet for `(` in implojure: `()` is the empty list, `(expr)` is
   grouping (returns expr), and `(a b)` is an error. Meme's nud for
   `(` rejects any non-empty bare parens; implojure carves out single-
   expression grouping so `(1 + 2) * 3` parses intuitively."
  [engine open-tok]
  (pratt/skip-trivia! engine)
  (cond
    (and (not (pratt/eof? engine)) (= (pratt/peek-char engine) \)))
    (let [start (pratt/cursor engine)]
      (pratt/advance! engine 1)
      (pratt/cst :list {:open  open-tok
                        :close (pratt/make-token! engine :close-paren start)}))

    :else
    (let [expr (pratt/parse-expr engine 0)]
      (pratt/skip-trivia! engine)
      (if (and (not (pratt/eof? engine)) (= (pratt/peek-char engine) \)))
        (let [start (pratt/cursor engine)]
          (pratt/advance! engine 1)
          (pratt/make-token! engine :close-paren start)
          expr)
        (pratt/cst :error
                   {:message "Expected `)` to close grouping expression"
                    :open open-tok})))))

;; ---------------------------------------------------------------------------
;; Grammar spec
;; ---------------------------------------------------------------------------

(def ^:private prefix-bp
  "Binding power for reader-sugar prefix operators (', @, `). Bumped
   above infix operators (max 70) and below function-call (100), so
   `'x + y` parses as `(+ (quote x) y)` rather than `(quote (+ x y))`."
  80)

(def grammar
  "Implojure grammar: meme's grammar with extra :led entries for infix
   operators, `(` overridden in nud position to mean grouping, reader-
   sugar prefix operators bumped to bp 80, and a `not` nud-pred
   prepended ahead of meme's symbol-start predicate."
  (-> meme-grammar/grammar
      (assoc-in [:nud \(] (lexer/single-char-scanlet :open-paren nud-group-or-empty))
      (assoc-in [:nud \']
                (lexer/single-char-scanlet :quote
                  (pratt/nud-prefix :quote prefix-bp)))
      (assoc-in [:nud \@]
                (lexer/single-char-scanlet :deref
                  (pratt/nud-prefix :deref prefix-bp)))
      (assoc-in [:nud \`]
                (lexer/single-char-scanlet :syntax-quote
                  (pratt/nud-prefix :syntax-quote prefix-bp)))
      (update :nud-pred
              (fn [preds]
                (into [[not-nud-pred not-nud-scanlet]] preds)))
      (assoc :led
             (into (:led meme-grammar/grammar)
                   (let [simple-gate (not-adjacent-call? 1)]
                     [{:char \+ :bp 60 :open-type :plus-op  :when simple-gate :fn (led-infix-call "+" 60 0)}
                      {:char \- :bp 60 :open-type :minus-op :when simple-gate :fn (led-infix-call "-" 60 0)}
                      {:char \* :bp 70 :open-type :star-op  :when simple-gate :fn (led-infix-call "*" 70 0)}
                      {:char \/ :bp 70 :open-type :slash-op :when simple-gate :fn (led-infix-call "/" 70 0)}
                      {:char \= :bp 40 :open-type :eq-op    :when simple-gate :fn (led-infix-call "=" 40 0)}
                      {:char \< :bp 50 :open-type :lt-op    :when compare-led-when :fn (led-compare-or-eq "<" "<=" 50)}
                      {:char \> :bp 50 :open-type :gt-op    :when compare-led-when :fn (led-compare-or-eq ">" ">=" 50)}
                      {:char \a :bp 35 :open-type :and-op
                       :when (word-op-when "and")
                       :fn   (led-infix-call "and" 35 2)}
                      {:char \o :bp 30 :open-type :or-op
                       :when (word-op-when "or")
                       :fn   (led-infix-call "or" 30 1)}
                      {:char \n :bp 40 :open-type :not=-op
                       :when (word-op-when "not=")
                       :fn   (led-infix-call "not=" 40 3)}
                      {:char \m :bp 70 :open-type :mod-op
                       :when (word-op-when "mod")
                       :fn   (led-infix-call "mod" 70 2)}
                      {:char \| :bp 20 :open-type :pipe-op
                       :when pipe-op-at-cursor?
                       :fn   (led-as-pipe 20)}])))))
