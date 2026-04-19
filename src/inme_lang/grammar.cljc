(ns inme-lang.grammar
  "Inme-lang grammar spec — infix meme.

   Inme is meme (M-expressions) plus a set of conventional binary infix
   operators. Infix parselets emit `:call` CST nodes whose head is a
   synthetic symbol atom, so every module downstream of the parser
   (cst-reader, printer, formatter, stages, run, repl) reuses meme's
   implementation unchanged — only the grammar is new.

   Operators (bp = binding power, higher = tighter):
     ==  (→ =)      bp 40
     !=  (→ not=)   bp 40
     <, <=          bp 50
     >, >=          bp 50
     +, -           bp 60
     *, /, %  (→ mod)  bp 70

   All are left-associative. Unary minus beyond meme's built-in handling
   of negative numeric literals (`-5`) is NOT supported in v0 — write
   `(- 0 x)` instead."
  (:require [meme.tools.parser :as pratt]
            [meme.tools.lexer :as lexer]
            [meme-lang.grammar :as meme-grammar]))

;; ---------------------------------------------------------------------------
;; Led parselet factories
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
  "Emit a :call CST node with the named operator as head, wrapping lhs
   and rhs. :open and :close reuse op-tok — the meme cst-reader only
   checks :close for truthiness and reads :open's trivia/before, which
   we strip on the synthetic head."
  [op-tok op-name lhs rhs]
  (pratt/cst :call {:head  (op-atom op-tok op-name)
                    :open  op-tok
                    :args  [lhs rhs]
                    :close op-tok}))

(defn- led-infix-call
  "Factory: single-char binary infix that lowers to `(op lhs rhs)`.
   Right-BP equals left-BP → left-associative."
  [op-name bp]
  (fn [engine lhs op-tok]
    (let [rhs (pratt/parse-expr engine bp)]
      (make-call op-tok op-name lhs rhs))))

(defn- led-two-char-call
  "Factory: two-character binary infix (==, !=). The dispatch loop has
   already consumed the first char; this consumes the second before
   parsing RHS."
  [op-name bp]
  (fn [engine lhs op-tok]
    (pratt/advance! engine 1)
    (let [rhs (pratt/parse-expr engine bp)]
      (make-call op-tok op-name lhs rhs))))

(defn- led-compare-or-eq
  "Factory: led parselet for < or > with an optional trailing = (<=, >=)."
  [base-name eq-name bp]
  (fn [engine lhs op-tok]
    (if (and (not (pratt/eof? engine)) (= (pratt/peek-char engine) \=))
      (do (pratt/advance! engine 1)
          (let [rhs (pratt/parse-expr engine bp)]
            (make-call op-tok eq-name lhs rhs)))
      (let [rhs (pratt/parse-expr engine bp)]
        (make-call op-tok base-name lhs rhs)))))

(def ^:private next-is-eq?
  "Predicate for `:when` clauses — returns true if the char after
   cursor is `=`. Used to gate == and != dispatch."
  (pratt/next-char-is? \=))

;; ---------------------------------------------------------------------------
;; Grouping parens in nud position
;; ---------------------------------------------------------------------------

(defn- nud-group-or-empty
  "Nud parselet for `(` in inme: `()` is the empty list, `(expr)` is
   grouping (returns expr), and `(a b)` is an error. Meme's nud for
   `(` rejects any non-empty bare parens; inme carves out single-
   expression grouping so `(1 + 2) * 3` parses intuitively."
  [engine open-tok]
  (pratt/skip-trivia! engine)
  (cond
    ;; ()
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
  "Binding power for unary-prefix reader-sugar operators (', @, `, ~).
   Sits above infix operators (max 70) and below function-call (100),
   so `'x + y` parses as `(+ (quote x) y)` rather than `(quote (+ x y))`."
  80)

(def grammar
  "Inme grammar: meme's grammar with extra :led entries for infix
   operators, `(` overridden in nud position to mean grouping, and
   reader-sugar prefix operators (', @, `, ~) bumped to bp 80 so they
   bind tighter than infix arithmetic. All other behavior (trivia,
   dispatch, dispatched `#` reader macros) is inherited from meme."
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
      (assoc :led
             (into (:led meme-grammar/grammar)
                   [{:char \+ :bp 60 :open-type :plus-op  :fn (led-infix-call "+" 60)}
                    {:char \- :bp 60 :open-type :minus-op :fn (led-infix-call "-" 60)}
                    {:char \* :bp 70 :open-type :star-op  :fn (led-infix-call "*" 70)}
                    {:char \/ :bp 70 :open-type :slash-op :fn (led-infix-call "/" 70)}
                    {:char \% :bp 70 :open-type :pct-op   :fn (led-infix-call "mod" 70)}
                    {:char \= :bp 40 :open-type :eq-eq    :when next-is-eq?
                     :fn (led-two-char-call "=" 40)}
                    {:char \! :bp 40 :open-type :bang-eq  :when next-is-eq?
                     :fn (led-two-char-call "not=" 40)}
                    {:char \< :bp 50 :open-type :lt-op    :fn (led-compare-or-eq "<" "<=" 50)}
                    {:char \> :bp 50 :open-type :gt-op    :fn (led-compare-or-eq ">" ">=" 50)}]))))
