(ns meme.tools.parser-test
  "Direct unit tests for the generic Pratt engine.

   Uses a minimal synthetic calculator grammar — numbers, identifiers, parens,
   unary !, + - at bp 10 (left-assoc), * at bp 20 (left-assoc), ^ at bp 30
   (right-assoc). Exercises the engine independently of meme's grammar, so a
   future grammar refactor can't silently break precedence or error recovery."
  (:require [clojure.test :refer [deftest is testing]]
            [meme.tools.parser :as p]
            [meme.tools.lexer :as lex]))

;; ---------------------------------------------------------------------------
;; Synthetic grammar
;; ---------------------------------------------------------------------------

(def ^:private number-scanlet
  (lex/atom-scanlet :number lex/consume-number))

(def ^:private ident-scanlet
  (lex/atom-scanlet :ident lex/consume-identifier))

(def ^:private paren-scanlet
  (lex/delimited-scanlet :group :lparen \) :rparen))

(def ^:private bang-scanlet
  (lex/single-char-scanlet :bang (p/nud-prefix :not 40)))

(def ^:private ws-trivia
  (fn [engine] (lex/ws-consumer engine (fn [c] (or (= c \space) (= c \tab))))))

(def calc-grammar
  "Minimal calculator grammar used by these tests."
  {:max-depth 16
   :nud       {\( paren-scanlet
               \! bang-scanlet}
   :nud-pred  [[(fn [ch _e] (lex/digit? ch))       number-scanlet]
               [(fn [ch _e] (lex/ident-start? ch)) ident-scanlet]]
   :trivia    {\space ws-trivia
               \tab   ws-trivia
               \newline lex/newline-consumer}
   :led       [{:char \+ :bp 10 :open-type :plus  :fn (p/led-infix :binop 10)}
               {:char \- :bp 10 :open-type :minus :fn (p/led-infix :binop 10)}
               {:char \* :bp 20 :open-type :times :fn (p/led-infix :binop 20)}
               ;; right-associative: rhs parsed at min-bp = bp - 1
               {:char \^ :bp 30 :open-type :caret :fn (p/led-infix :binop 29)}]})

;; ---------------------------------------------------------------------------
;; Helpers for assertions over the CST
;; ---------------------------------------------------------------------------

(defn- raw
  "Pull :raw off an atom node's token (handy for terse assertions)."
  [node]
  (get-in node [:token :raw]))

(defn- binop
  "Extract [op-raw left-raw-or-node right-raw-or-node] from a :binop node."
  [node]
  [(get-in node [:token :raw])
   (:left node)
   (:right node)])

;; ---------------------------------------------------------------------------
;; CST node constructor
;; ---------------------------------------------------------------------------

(deftest cst-adds-node-key
  (testing "cst sets :node on the supplied fields map"
    (is (= {:node :foo :a 1 :b 2} (p/cst :foo {:a 1 :b 2}))))
  (testing "cst preserves all existing fields"
    (is (= :atom (:node (p/cst :atom {:token {:raw "x"}}))))))

;; ---------------------------------------------------------------------------
;; Engine state & primitives
;; ---------------------------------------------------------------------------

(deftest engine-state-and-primitives
  (let [e (p/make-engine "abc" calc-grammar)]
    (testing "initial state"
      (is (= 0 (p/cursor e)))
      (is (= 3 (p/source-len e)))
      (is (= "abc" (p/source-str e)))
      (is (false? (p/eof? e))))
    (testing "peek-char"
      (is (= \a (p/peek-char e)))
      (is (= \b (p/peek-char e 1)))
      (is (= \c (p/peek-char e 2)))
      (is (nil? (p/peek-char e 3))))
    (testing "advance! moves cursor"
      (p/advance! e 2)
      (is (= 2 (p/cursor e)))
      (is (= \c (p/peek-char e))))
    (testing "set-pos! jumps cursor"
      (p/set-pos! e 0)
      (is (= 0 (p/cursor e))))
    (testing "eof? at end"
      (p/set-pos! e 3)
      (is (true? (p/eof? e)))
      (is (nil? (p/peek-char e))))))

(deftest pos-at-line-col
  (testing "offset 0 is line 1 col 1"
    (let [e (p/make-engine "abc\ndef" calc-grammar)]
      (is (= {:line 1 :col 1} (p/pos-at e 0)))
      (is (= {:line 1 :col 3} (p/pos-at e 2)))))
  (testing "offset after \\n advances line"
    (let [e (p/make-engine "abc\ndef" calc-grammar)]
      (is (= {:line 2 :col 1} (p/pos-at e 4)))
      (is (= {:line 2 :col 3} (p/pos-at e 6)))))
  (testing "CRLF is one line break"
    (let [e (p/make-engine "ab\r\ncd" calc-grammar)]
      (is (= {:line 2 :col 1} (p/pos-at e 4)))))
  (testing "bare CR also advances line"
    (let [e (p/make-engine "ab\rcd" calc-grammar)]
      (is (= {:line 2 :col 1} (p/pos-at e 3))))))

;; ---------------------------------------------------------------------------
;; Token production
;; ---------------------------------------------------------------------------

(deftest make-token-drains-trivia
  (testing "token with no prior trivia has no :trivia/before"
    (let [e (p/make-engine "abc" calc-grammar)]
      (p/advance! e 3)
      (let [tok (p/make-token! e :ident 0)]
        (is (= "abc" (:raw tok)))
        (is (= :ident (:type tok)))
        (is (= 0 (:offset tok)))
        (is (= {:line 1 :col 1} (select-keys tok [:line :col])))
        (is (not (contains? tok :trivia/before))))))
  (testing "accumulated trivia is attached and drained"
    (let [e (p/make-engine "  abc" calc-grammar)]
      (p/skip-trivia! e)
      (p/advance! e 3)
      (let [tok (p/make-token! e :ident 2)]
        (is (= "abc" (:raw tok)))
        (is (seq (:trivia/before tok)))
        (is (= :whitespace (:type (first (:trivia/before tok)))))
        ;; After draining, trivia-acc is empty; next token has no trivia.
        (let [e2 (p/make-engine "x" calc-grammar)]
          (p/advance! e2 1)
          (let [tok2 (p/make-token! e2 :ident 0)]
            (is (not (contains? tok2 :trivia/before)))))))))

;; ---------------------------------------------------------------------------
;; Basic parse behavior — atoms, groups, prefix, EOF
;; ---------------------------------------------------------------------------

(deftest parse-empty-string
  (is (= [] (p/parse "" calc-grammar))))

(deftest parse-single-atom
  (let [[node] (p/parse "42" calc-grammar)]
    (is (= :atom (:node node)))
    (is (= "42" (raw node)))
    (is (= :number (get-in node [:token :type])))))

(deftest parse-multiple-top-level-forms
  (let [nodes (p/parse "1 2 3" calc-grammar)]
    (is (= 3 (count nodes)))
    (is (= ["1" "2" "3"] (map raw nodes)))))

(deftest parse-group
  (let [[g] (p/parse "(42)" calc-grammar)]
    (is (= :group (:node g)))
    (is (= :lparen (get-in g [:open :type])))
    (is (= :rparen (get-in g [:close :type])))
    (is (= 1 (count (:children g))))
    (is (= "42" (raw (first (:children g)))))))

(deftest parse-unclosed-group
  (testing "missing close yields nil :close; does not throw"
    (let [[g] (p/parse "(42" calc-grammar)]
      (is (= :group (:node g)))
      (is (nil? (:close g)))
      (is (= 1 (count (:children g)))))))

(deftest parse-prefix
  (let [[node] (p/parse "!x" calc-grammar)]
    (is (= :not (:node node)))
    (is (= "!" (get-in node [:token :raw])))
    (is (= "x" (raw (:form node))))))

;; ---------------------------------------------------------------------------
;; Pratt precedence
;; ---------------------------------------------------------------------------

(deftest precedence-times-binds-tighter-than-plus
  (testing "1 + 2 * 3 → 1 + (2 * 3)"
    (let [[top] (p/parse "1 + 2 * 3" calc-grammar)
          [op l r] (binop top)]
      (is (= "+" op))
      (is (= "1" (raw l)))
      (is (= :binop (:node r)))
      (is (= "*" (get-in r [:token :raw])))
      (is (= "2" (raw (:left r))))
      (is (= "3" (raw (:right r))))))
  (testing "1 * 2 + 3 → (1 * 2) + 3"
    (let [[top] (p/parse "1 * 2 + 3" calc-grammar)
          [op l r] (binop top)]
      (is (= "+" op))
      (is (= :binop (:node l)))
      (is (= "*" (get-in l [:token :raw])))
      (is (= "3" (raw r))))))

(deftest left-associativity-plus-minus
  (testing "1 + 2 + 3 → ((1 + 2) + 3)"
    (let [[top] (p/parse "1 + 2 + 3" calc-grammar)
          [op l r] (binop top)]
      (is (= "+" op))
      (is (= :binop (:node l)) "left side is the inner +")
      (is (= "3" (raw r)) "right side is the last atom"))))

(deftest right-associativity-caret
  (testing "2 ^ 3 ^ 4 → (2 ^ (3 ^ 4))"
    (let [[top] (p/parse "2 ^ 3 ^ 4" calc-grammar)
          [op l r] (binop top)]
      (is (= "^" op))
      (is (= "2" (raw l)) "left side is the outer atom")
      (is (= :binop (:node r)) "right side is the inner ^"))))

(deftest prefix-binds-tighter-than-infix
  (testing "!x + y parses as (!x) + y, not !(x + y)"
    (let [[top] (p/parse "!x + y" calc-grammar)
          [op l r] (binop top)]
      (is (= "+" op))
      (is (= :not (:node l)))
      (is (= "y" (raw r))))))

;; ---------------------------------------------------------------------------
;; EOF and error recovery
;; ---------------------------------------------------------------------------

(deftest led-rhs-eof-produces-error-node
  (testing "trailing operator with no rhs yields an :error node for rhs"
    (let [[top] (p/parse "1 +" calc-grammar)]
      (is (= :binop (:node top)))
      (is (= :error (:node (:right top))))
      (is (re-find #"(?i)end of input" (:message (:right top)))))))

(deftest invalid-character-yields-error-node
  (testing "a dispatch-less character produces an :error atom and parse continues"
    (let [nodes (p/parse "$" calc-grammar)]
      (is (= 1 (count nodes)))
      (is (= :error (:node (first nodes))))
      (is (re-find #"(?i)unexpected token" (:message (first nodes))))))
  (testing "1 + $ still produces a binop tree with error on rhs"
    (let [[top] (p/parse "1 + $" calc-grammar)]
      (is (= :binop (:node top)))
      (is (= :error (:node (:right top)))))))

(deftest max-depth-exceeded-throws
  (testing "more nested groups than :max-depth throws ex-info with position"
    (let [depth (+ (:max-depth calc-grammar) 4)
          input (str (apply str (repeat depth "(")) "1" (apply str (repeat depth ")")))]
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                            #"(?i)depth"
                            (p/parse input calc-grammar))))))

;; ---------------------------------------------------------------------------
;; matching-led :when predicate — conditional led matching
;; ---------------------------------------------------------------------------

(deftest when-predicate-gates-led-matching
  (testing "led rule with :when false is skipped; rule with :when true fires"
    ;; Two led rules on the same char with :when predicates — proves the
    ;; dispatcher honors :when. '+' with :when (always-false) does not match;
    ;; '+' with :when (always-true) does.
    (let [grammar (assoc calc-grammar :led
                    [{:char \+ :bp 5  :open-type :plus
                      :when (fn [_e] false)
                      :fn (p/led-infix :nope)}
                     {:char \+ :bp 10 :open-type :plus
                      :when (fn [_e] true)
                      :fn (p/led-infix :yes 10)}])
          [top] (p/parse "1 + 2" grammar)]
      (is (= :yes (:node top)) ":when true rule fires"))))

;; ---------------------------------------------------------------------------
;; Trivia preservation on tokens
;; ---------------------------------------------------------------------------

(deftest whitespace-attached-to-following-token
  (testing "spaces between atoms appear as :trivia/before on the next token"
    (let [[_ second-atom] (p/parse "1   2" calc-grammar)]
      (is (= "2" (raw second-atom)))
      (is (seq (get-in second-atom [:token :trivia/before]))))))

(deftest trailing-trivia-attached-as-metadata
  (testing "trivia after the last form rides on the top-level vector as metadata"
    (let [nodes (p/parse "1   " calc-grammar)]
      (is (= 1 (count nodes)))
      (is (seq (:trivia/after (meta nodes)))))))

;; ---------------------------------------------------------------------------
;; expect-close! and parse-until — used by delimited parselets
;; ---------------------------------------------------------------------------

(deftest expect-close-consumes-matching-char
  (let [e (p/make-engine ")" calc-grammar)
        tok (p/expect-close! e \) :rparen)]
    (is (= :rparen (:type tok)))
    (is (true? (p/eof? e)))))

(deftest expect-close-returns-nil-on-mismatch
  (let [e (p/make-engine "x" calc-grammar)]
    (is (nil? (p/expect-close! e \) :rparen)))
    (is (= 0 (p/cursor e)) "cursor unchanged on mismatch")))

;; ---------------------------------------------------------------------------
;; Predicate helpers
;; ---------------------------------------------------------------------------

(deftest next-char-predicates
  (let [e (p/make-engine "==" calc-grammar)]
    (testing "next-char-is?"
      (is (true? ((p/next-char-is? \=) e)))
      (is (false? ((p/next-char-is? \!) e))))
    (testing "next-char-is-not? is the inverse"
      (is (false? ((p/next-char-is-not? \=) e)))
      (is (true? ((p/next-char-is-not? \!) e))))))

;; ---------------------------------------------------------------------------
;; led-comparison-or-equal — peek-and-consume led factory
;; ---------------------------------------------------------------------------

(deftest led-comparison-or-equal-dispatches-on-next-char
  (let [grammar (assoc calc-grammar :led
                  [{:char \< :bp 5 :open-type :lt
                    :fn (p/led-comparison-or-equal :lt :le 5)}])]
    (testing "< without trailing = produces :lt"
      (let [[top] (p/parse "1 < 2" grammar)]
        (is (= :lt (:node top)))))
    (testing "<= produces :le"
      (let [[top] (p/parse "1 <= 2" grammar)]
        (is (= :le (:node top)))))))

;; ---------------------------------------------------------------------------
;; nud-empty-or-error — empty delimiter vs bare-parens error
;; ---------------------------------------------------------------------------

(deftest nud-empty-or-error-factory
  (let [empty-parselet (p/nud-empty-or-error :empty \) :rparen "bare parens")
        grammar (assoc calc-grammar
                  :nud (assoc (:nud calc-grammar)
                         \( (lex/single-char-scanlet :lparen empty-parselet)))]
    (testing "() is parsed as :empty"
      (let [[top] (p/parse "()" grammar)]
        (is (= :empty (:node top)))
        (is (= :lparen (get-in top [:open :type])))
        (is (= :rparen (get-in top [:close :type])))))
    (testing "(1) is parsed as :error with the supplied message"
      (let [[top] (p/parse "(1)" grammar)]
        (is (= :error (:node top)))
        (is (= "bare parens" (:message top)))))))
