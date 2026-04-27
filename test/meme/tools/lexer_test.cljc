(ns meme.tools.lexer-test
  "Direct unit tests for the scanlet builders and common consume functions.

   These exercise the builders in isolation without the whole meme grammar, so
   a change to the lexer helpers can't silently break the contract all langs
   rely on."
  (:require [clojure.test :refer [deftest is testing]]
            [meme.tools.parser :as p]
            [meme.tools.lexer :as lex]))

(def ^:private dummy-grammar
  "Empty grammar — scanlet tests drive the engine directly and don't parse."
  {:nud {} :led []})

;; ---------------------------------------------------------------------------
;; Character predicates
;; ---------------------------------------------------------------------------

(deftest digit-predicate
  (is (every? lex/digit? [\0 \1 \5 \9]))
  (is (not-any? lex/digit? [\a \A \_ \+ \space \newline nil])))

(deftest ident-start-predicate
  (is (every? lex/ident-start? [\a \z \A \Z \_]))
  (is (not-any? lex/ident-start? [\0 \9 \+ \- \space nil])))

(deftest ident-char-predicate
  (is (every? lex/ident-char? [\a \Z \0 \9 \_]))
  (is (not-any? lex/ident-char? [\+ \- \space \. nil])))

;; ---------------------------------------------------------------------------
;; Consume functions — pure (source, len, pos) → end-pos
;; ---------------------------------------------------------------------------

(deftest consume-number-forms
  (testing "plain integer"
    (is (= 3 (lex/consume-number "123" 3 0))))
  (testing "decimal"
    (is (= 4 (lex/consume-number "3.14" 4 0)))
    (is (= 4 (lex/consume-number "1.25" 4 0))))
  (testing "trailing dot without digit is NOT consumed as decimal"
    ;; "5." should stop at position 1, leaving the dot for the caller.
    (is (= 1 (lex/consume-number "5." 2 0))))
  (testing "scientific notation"
    (is (= 4 (lex/consume-number "1e10" 4 0)))
    (is (= 4 (lex/consume-number "1e+5" 4 0)))
    (is (= 4 (lex/consume-number "1e-5" 4 0)))
    (is (= 5 (lex/consume-number "1.5e3" 5 0))))
  (testing "stops at non-digit"
    (is (= 3 (lex/consume-number "123abc" 6 0)))))

(deftest consume-identifier-forms
  (testing "simple identifier"
    (is (= 3 (lex/consume-identifier "abc" 3 0)))
    (is (= 7 (lex/consume-identifier "my_var1" 7 0))))
  (testing "stops at non-ident char"
    (is (= 3 (lex/consume-identifier "abc+def" 7 0))))
  (testing "single char identifier"
    (is (= 1 (lex/consume-identifier "x" 1 0)))))

(deftest consume-string-forms
  (testing "simple string"
    (is (= 5 (lex/consume-string "\"abc\"" 5 0))))
  (testing "empty string"
    (is (= 2 (lex/consume-string "\"\"" 2 0))))
  (testing "escaped quote inside string"
    (is (= 6 (lex/consume-string "\"a\\\"b\"" 6 0))))
  (testing "unterminated string runs to EOF"
    (is (= 3 (lex/consume-string "\"ab" 3 0)))))

;; ---------------------------------------------------------------------------
;; Scanlet builders
;; ---------------------------------------------------------------------------

(deftest atom-scanlet-builds-atom-node
  (let [scanlet (lex/atom-scanlet :number lex/consume-number)
        e (p/make-engine "42" dummy-grammar)
        node (scanlet e)]
    (is (= :atom (:node node)))
    (is (= "42" (get-in node [:token :raw])))
    (is (= :number (get-in node [:token :type])))
    (is (= 2 (p/cursor e)) "cursor advanced past the consumed chars")))

(deftest single-char-scanlet-delegates-to-factory
  (let [captured (atom nil)
        fake-factory (fn [_engine tok]
                       (reset! captured tok)
                       (p/cst :marker {:token tok}))
        scanlet (lex/single-char-scanlet :bang fake-factory)
        e (p/make-engine "!x" dummy-grammar)
        node (scanlet e)]
    (testing "consumes one character and calls the factory with its token"
      (is (= 1 (p/cursor e)))
      (is (= "!" (:raw @captured)))
      (is (= :bang (:type @captured))))
    (testing "node returned is whatever the factory returned"
      (is (= :marker (:node node))))))

(deftest delimited-scanlet-builds-delimited-node
  (let [grammar {:nud {\[ (lex/delimited-scanlet :vec :lbrack \] :rbrack)}
                 :nud-pred [[(fn [c _] (lex/digit? c))
                             (lex/atom-scanlet :num lex/consume-number)]]
                 :trivia {\space (fn [e] (lex/ws-consumer e (fn [c] (= c \space))))}
                 :led []}
        [node] (p/parse "[1 2 3]" grammar)]
    (is (= :vec (:node node)))
    (is (= :lbrack (get-in node [:open :type])))
    (is (= :rbrack (get-in node [:close :type])))
    (is (= ["1" "2" "3"]
           (mapv #(get-in % [:token :raw]) (:children node))))))

;; ---------------------------------------------------------------------------
;; Trivia consumers
;; ---------------------------------------------------------------------------

(deftest ws-consumer-advances-cursor
  (let [e (p/make-engine "   x" dummy-grammar)
        tok (lex/ws-consumer e)]
    (is (= :whitespace (:type tok)))
    (is (= "   " (:raw tok)))
    (is (= 3 (p/cursor e)))))

(deftest ws-consumer-custom-predicate
  (let [e (p/make-engine " \tx" dummy-grammar)
        tok (lex/ws-consumer e (fn [c] (or (= c \space) (= c \tab))))]
    (is (= " \t" (:raw tok)))
    (is (= 2 (p/cursor e)))))

(deftest newline-consumer-handles-lf-cr-and-crlf
  (testing "LF"
    (let [e (p/make-engine "\nx" dummy-grammar)
          tok (lex/newline-consumer e)]
      (is (= "\n" (:raw tok)))
      (is (= 1 (p/cursor e)))))
  (testing "CRLF counts as one newline token"
    (let [e (p/make-engine "\r\nx" dummy-grammar)
          tok (lex/newline-consumer e)]
      (is (= "\r\n" (:raw tok)))
      (is (= 2 (p/cursor e)))))
  (testing "bare CR is a newline"
    (let [e (p/make-engine "\rx" dummy-grammar)
          tok (lex/newline-consumer e)]
      (is (= "\r" (:raw tok)))
      (is (= 1 (p/cursor e))))))

(deftest line-comment-consumer-runs-to-newline
  (let [e (p/make-engine "// hello\nrest" dummy-grammar)
        tok (lex/line-comment-consumer e 2)]
    (is (= :comment (:type tok)))
    (is (= "// hello" (:raw tok)))
    ;; Cursor stops AT the newline, not past it.
    (is (= \newline (p/peek-char e)))))

(deftest line-comment-consumer-runs-to-eof
  (testing "comment without trailing newline terminates cleanly"
    (let [e (p/make-engine "; comment" dummy-grammar)
          tok (lex/line-comment-consumer e 1)]
      (is (= "; comment" (:raw tok)))
      (is (true? (p/eof? e))))))

(deftest block-comment-consumer-consumes-whole-block
  (testing "simple block comment"
    (let [e (p/make-engine "(* hello *)rest" dummy-grammar)
          tok (lex/block-comment-consumer e)]
      (is (= :comment (:type tok)))
      (is (= "(* hello *)" (:raw tok)))))
  (testing "nested block comments"
    (let [e (p/make-engine "(* outer (* inner *) more *)rest" dummy-grammar)
          tok (lex/block-comment-consumer e)]
      (is (= "(* outer (* inner *) more *)" (:raw tok)))))
  (testing "unterminated block comment runs to EOF without throwing"
    (let [e (p/make-engine "(* unterminated" dummy-grammar)
          tok (lex/block-comment-consumer e)]
      (is (= "(* unterminated" (:raw tok)))
      (is (true? (p/eof? e))))))
