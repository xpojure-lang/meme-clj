(ns meme.scan.tokenizer-test
  (:require [clojure.test :refer [deftest is testing]]
            [meme.scan.tokenizer :as tokenizer]))

;; ---------------------------------------------------------------------------
;; Tokenizer tests
;; ---------------------------------------------------------------------------

(deftest tokenize-simple-symbol
  (is (= :symbol (:type (first (tokenizer/tokenize "foo"))))))

(deftest tokenize-keyword
  (is (= {:type :keyword :value ":active"} (select-keys (first (tokenizer/tokenize ":active")) [:type :value]))))

(deftest tokenize-auto-resolve-keyword
  (is (= {:type :keyword :value "::local"} (select-keys (first (tokenizer/tokenize "::local")) [:type :value]))))

(deftest tokenize-namespaced-keyword
  (is (= {:type :keyword :value ":foo/bar"} (select-keys (first (tokenizer/tokenize ":foo/bar")) [:type :value]))))

(deftest tokenize-number
  (is (= {:type :number :value "42"} (select-keys (first (tokenizer/tokenize "42")) [:type :value]))))

(deftest tokenize-float
  (is (= {:type :number :value "3.14"} (select-keys (first (tokenizer/tokenize "3.14")) [:type :value]))))

(deftest tokenize-string
  (is (= {:type :string :value "\"hello\""} (select-keys (first (tokenizer/tokenize "\"hello\"")) [:type :value]))))

(deftest tokenize-string-with-escapes
  (is (= {:type :string :value "\"he\\\"llo\""} (select-keys (first (tokenizer/tokenize "\"he\\\"llo\"")) [:type :value]))))

(deftest tokenize-namespace-qualified
  (let [tokens (tokenizer/tokenize "str/upper-case")]
    (is (= 1 (count tokens)))
    (is (= "str/upper-case" (:value (first tokens))))))

(deftest tokenize-catch-finally-as-symbols
  (let [tokens (tokenizer/tokenize "catch finally")]
    (is (= [:symbol :symbol] (mapv :type tokens)))
    (is (= "catch" (:value (first tokens))))
    (is (= "finally" (:value (second tokens))))))

(deftest tokenize-call
  (let [tokens (tokenizer/tokenize "println(\"hello\")")]
    (is (= [:symbol :open-paren :string :close-paren] (mapv :type tokens)))))

(deftest tokenize-commas-as-whitespace
  (let [tokens (tokenizer/tokenize "assoc(m, :key, \"value\")")]
    (is (= [:symbol :open-paren :symbol :keyword :string :close-paren] (mapv :type tokens)))))

(deftest tokenize-comment
  (let [tokens (tokenizer/tokenize "; this is a comment\nfoo")]
    (is (= 1 (count tokens)))
    (is (= "foo" (:value (first tokens))))))

(deftest tokenize-deref
  (is (= :deref (:type (first (tokenizer/tokenize "@"))))))

(deftest tokenize-meta
  (is (= :meta (:type (first (tokenizer/tokenize "^"))))))

(deftest tokenize-quote
  (is (= :quote (:type (first (tokenizer/tokenize "'"))))))

(deftest tokenize-syntax-quote
  (is (= :syntax-quote (:type (first (tokenizer/tokenize "`foo")))))
  (is (= :syntax-quote (:type (first (tokenizer/tokenize "`(a b c)"))))))

(deftest tokenize-unquote
  (is (= :unquote (:type (first (tokenizer/tokenize "~x")))))
  (is (= :unquote-splicing (:type (first (tokenizer/tokenize "~@x"))))))

(deftest tokenize-var-quote
  (is (= :var-quote (:type (first (tokenizer/tokenize "#'foo"))))))

(deftest tokenize-discard
  (is (= :discard (:type (first (tokenizer/tokenize "#_foo"))))))

(deftest tokenize-set-literal
  (is (= :open-set (:type (first (tokenizer/tokenize "#{"))))))

(deftest tokenize-hash-paren-open
  (let [tokens (tokenizer/tokenize "#(inc(%))")
        first-tok (first tokens)]
    (is (= :open-anon-fn (:type first-tok)))
    (is (= "#(" (:value first-tok)))
    ;; Contents are tokenized as normal meme tokens
    (is (= :symbol (:type (second tokens))))
    (is (= "inc" (:value (second tokens))))))

(deftest tokenize-regex
  (let [tok (first (tokenizer/tokenize "#\"pattern\""))]
    (is (= :regex (:type tok)))
    (is (= "#\"pattern\"" (:value tok)))))

(deftest tokenize-tagged-literal
  (let [tok (first (tokenizer/tokenize "#inst"))]
    (is (= :tagged-literal (:type tok)))
    (is (= "#inst" (:value tok)))))

(deftest tokenize-char-literal
  (let [tok (first (tokenizer/tokenize "\\a"))]
    (is (= :char (:type tok)))
    (is (= "\\a" (:value tok)))))

(deftest tokenize-named-char-literal
  (let [tok (first (tokenizer/tokenize "\\newline"))]
    (is (= :char (:type tok)))
    (is (= "\\newline" (:value tok)))))

(deftest tokenize-reader-cond
  (testing "tokenizer emits :reader-cond-start marker"
    (let [tok (first (tokenizer/tokenize "#?(:clj 1)"))]
      (is (= :reader-cond-start (:type tok)))
      (is (= "#?" (:value tok))))))

(deftest tokenize-reader-cond-splicing
  (testing "raw tokenizer emits :reader-cond-start marker for #?@"
    (let [tok (first (tokenizer/tokenize "#?@(:clj [1])"))]
      (is (= :reader-cond-start (:type tok)))
      (is (= "#?@" (:value tok))))))

(deftest tokenize-namespaced-map
  (testing "tokenizer emits :namespaced-map-start marker"
    (let [tok (first (tokenizer/tokenize "#:ns{:a 1}"))]
      (is (= :namespaced-map-start (:type tok)))
      (is (= "#:ns" (:value tok))))))

(deftest tokenize-operators
  (testing "operators are symbols"
    (doseq [op ["+" "-" "*" "/" "=" "==" ">=" "<=" "!=" "->" "->>" "not="]]
      (is (= :symbol (:type (first (tokenizer/tokenize op)))) (str op " should be a symbol")))))

(deftest tokenize-constructor-symbol
  ;; java.util.Date.() — the . is part of the symbol
  (let [tokens (tokenizer/tokenize "java.util.Date.()")]
    (is (= "java.util.Date." (:value (first tokens))))))

(deftest tokenize-line-col-tracking
  (let [tokens (tokenizer/tokenize "foo\nbar")]
    (is (= 1 (:line (first tokens))))
    (is (= 2 (:line (second tokens))))))

(deftest tokenize-column-tracking
  (testing "column tracks character position within a line"
    (let [tokens (tokenizer/tokenize "foo bar")]
      (is (= 1 (:col (first tokens))))
      (is (= 5 (:col (second tokens))))))
  (testing "column resets after newline"
    (let [tokens (tokenizer/tokenize "abc\nde")]
      (is (= 1 (:col (first tokens))))
      (is (= 1 (:col (second tokens))))))
  (testing "multi-char token starts at first character column"
    (let [tokens (tokenizer/tokenize "  println(x)")]
      (is (= 3 (:col (first tokens))))  ; println
      (is (= 10 (:col (second tokens)))))) ; (
  (testing "keyword column tracks the colon"
    (let [tokens (tokenizer/tokenize ":key")]
      (is (= 1 (:col (first tokens)))))))

(deftest tokenize-whitespace-only
  (testing "empty string produces zero tokens"
    (is (= [] (tokenizer/tokenize ""))))
  (testing "whitespace-only produces zero tokens"
    (is (= [] (tokenizer/tokenize "   \t\n  "))))
  (testing "commas-only produces zero tokens"
    (is (= [] (tokenizer/tokenize ",,,"))))
  (testing "comment-only produces zero tokens"
    (is (= [] (tokenizer/tokenize "; just a comment")))))

(deftest tokenize-multi-line-string
  (testing "string spanning multiple lines is a single token"
    (let [tokens (tokenizer/tokenize "\"line1\nline2\"")]
      (is (= 1 (count tokens)))
      (is (= :string (:type (first tokens))))))
  (testing "string with escaped newline"
    (let [tokens (tokenizer/tokenize "\"line1\\nline2\"")]
      (is (= 1 (count tokens)))
      (is (= :string (:type (first tokens)))))))

;; ---------------------------------------------------------------------------
;; Number format edge cases — delegated to host reader, tokenizer just captures
;; ---------------------------------------------------------------------------

(deftest tokenize-hex-number
  (is (= {:type :number :value "0xFF"} (select-keys (first (tokenizer/tokenize "0xFF")) [:type :value]))))

(deftest tokenize-scientific-number
  (is (= {:type :number :value "1.5e10"} (select-keys (first (tokenizer/tokenize "1.5e10")) [:type :value]))))

(deftest tokenize-bigint
  (is (= {:type :number :value "42N"} (select-keys (first (tokenizer/tokenize "42N")) [:type :value]))))

(deftest tokenize-bigdecimal
  (is (= {:type :number :value "1.5M"} (select-keys (first (tokenizer/tokenize "1.5M")) [:type :value]))))

(deftest tokenize-radix-number
  (is (= {:type :number :value "8r77"} (select-keys (first (tokenizer/tokenize "8r77")) [:type :value]))))

;; ---------------------------------------------------------------------------
;; Unicode
;; ---------------------------------------------------------------------------

(deftest tokenize-unicode-in-strings
  (testing "Unicode characters in strings"
    (let [tokens (tokenizer/tokenize "\"Hello, \u4e16\u754c\"")]
      (is (= 1 (count tokens)))
      (is (= :string (:type (first tokens))))))
  (testing "Emoji in strings"
    (let [tokens (tokenizer/tokenize "\"test \ud83d\ude00 emoji\"")]
      (is (= 1 (count tokens)))
      (is (= :string (:type (first tokens)))))))

(deftest tokenize-unicode-symbols
  (testing "Unicode letters in symbols"
    (let [tokens (tokenizer/tokenize "\u03b1\u03b2\u03b3")]
      (is (= 1 (count tokens)))
      (is (= :symbol (:type (first tokens))))
      (is (= "\u03b1\u03b2\u03b3" (:value (first tokens))))))
  (testing "Unicode symbol in call"
    (let [tokens (tokenizer/tokenize "\u03b1(x)")]
      (is (= 4 (count tokens)))
      (is (= :symbol (:type (first tokens)))))))

(deftest tokenize-column-after-string
  (testing "keyword after string has correct column"
    (let [tokens (tokenizer/tokenize "\"hello\" :key")]
      (is (= :keyword (:type (second tokens))))
      (is (= 9 (:col (second tokens)))))))

;; ---------------------------------------------------------------------------
;; Whitespace preservation
;; ---------------------------------------------------------------------------

(deftest whitespace-attachment-basic
  (let [src "  foo(x)"
        tokens (tokenizer/attach-whitespace (tokenizer/tokenize src) src)]
    (testing "leading whitespace attached to first token"
      (is (= "  " (:ws (first tokens)))))
    (testing "no ws on adjacent tokens"
      (is (nil? (:ws (second tokens)))))))

(deftest whitespace-attachment-between-tokens
  (let [src "foo  bar"
        tokens (tokenizer/attach-whitespace (tokenizer/tokenize src) src)]
    (testing "space between tokens attached to second"
      (is (nil? (:ws (first tokens))))
      (is (= "  " (:ws (second tokens)))))))

(deftest whitespace-attachment-newlines
  (let [src "foo\n\nbar"
        tokens (tokenizer/attach-whitespace (tokenizer/tokenize src) src)]
    (testing "newlines preserved between tokens"
      (is (= "\n\n" (:ws (second tokens)))))))

(deftest whitespace-attachment-comments
  (let [src "foo ; comment\nbar"
        tokens (tokenizer/attach-whitespace (tokenizer/tokenize src) src)]
    (testing "comment preserved as whitespace"
      (is (= " ; comment\n" (:ws (second tokens)))))))

(deftest whitespace-attachment-trailing
  (let [src "foo  "
        tokens (tokenizer/attach-whitespace (tokenizer/tokenize src) src)]
    (testing "trailing whitespace in metadata"
      (is (= "  " (:trailing-ws (meta tokens)))))))

(deftest whitespace-attachment-empty
  (let [tokens (tokenizer/attach-whitespace [] "  \n  ")]
    (testing "whitespace-only source stored as trailing"
      (is (= "  \n  " (:trailing-ws (meta tokens)))))))

(deftest whitespace-attachment-commas
  (let [src "a, b, c"
        tokens (tokenizer/attach-whitespace (tokenizer/tokenize src) src)]
    (testing "commas preserved in whitespace"
      (is (= ", " (:ws (second tokens))))
      (is (= ", " (:ws (nth tokens 2)))))))
