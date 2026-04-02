(ns meme.rewrite.tree-test
  "Tests for the rewrite-based M→S pipeline.
   Cross-tests against the existing parser."
  (:require [clojure.test :refer [deftest is testing]]
            [meme.scan.tokenizer :as tokenizer]
            [meme.rewrite :as rw]
            [meme.rewrite.tree :as tree]
            [meme.rewrite.rules :as rules]
            [meme.core :as core]))

(defn- rewrite-parse
  "Parse meme source via the rewrite pipeline:
   tokenize → build-tree → apply tree->s rules → transform structures."
  [s]
  (let [tokens (tokenizer/attach-whitespace (tokenizer/tokenize s) s)
        tagged (#'tree/tokens->tree tokens)
        rewritten (mapv #(rw/rewrite rules/tree->s-rules %) tagged)
        forms (mapv rules/transform-structures rewritten)]
    forms))

;; ============================================================
;; Tree builder
;; ============================================================

(deftest tree-builder-basic
  (testing "simple call produces m-call"
    (let [tree (#'tree/tokens->tree
                (tokenizer/attach-whitespace
                 (tokenizer/tokenize "f(x)") "f(x)"))]
      (is (= '[(m-call f x)] tree))))
  (testing "non-adjacent is not a call"
    (let [tree (#'tree/tokens->tree
                (tokenizer/attach-whitespace
                 (tokenizer/tokenize "f (x)") "f (x)"))]
      ;; f and (paren x) are separate
      (is (= 2 (count tree)))
      (is (= 'f (first tree)))))
  (testing "vector produces bracket"
    (let [tree (#'tree/tokens->tree
                (tokenizer/attach-whitespace
                 (tokenizer/tokenize "[1 2 3]") "[1 2 3]"))]
      (is (= '[(bracket 1 2 3)] tree))))
  (testing "nested call"
    (let [tree (#'tree/tokens->tree
                (tokenizer/attach-whitespace
                 (tokenizer/tokenize "f(g(x))") "f(g(x))"))]
      (is (= '[(m-call f (m-call g x))] tree)))))

;; ============================================================
;; Full rewrite pipeline
;; ============================================================

(deftest rewrite-pipeline-basic
  (testing "simple call"
    (is (= '[(f x)] (rewrite-parse "f(x)"))))
  (testing "nested call"
    (is (= '[(f (g x))] (rewrite-parse "f(g(x))"))))
  (testing "vector"
    (is (= [[1 2 3]] (rewrite-parse "[1 2 3]"))))
  (testing "def"
    (is (= '[(def x 42)] (rewrite-parse "def(x 42)"))))
  (testing "defn"
    (is (= '[(defn foo [x] (+ x 1))]
           (rewrite-parse "defn(foo [x] +(x 1))"))))
  (testing "let"
    (is (= '[(let [a 1] (* a 2))]
           (rewrite-parse "let([a 1] *(a 2))"))))
  (testing "empty list"
    (is (= '[()] (rewrite-parse "()"))))
  (testing "map"
    (is (= [{:a 1 :b 2}] (rewrite-parse "{:a 1 :b 2}"))))
  (testing "keyword"
    (is (= [:foo] (rewrite-parse ":foo"))))
  (testing "string"
    (is (= ["hello"] (rewrite-parse "\"hello\""))))
  (testing "number"
    (is (= [42] (rewrite-parse "42")))))

;; ============================================================
;; Delimiter validation in build-tree
;; ============================================================

;; Bug: build-tree skipped validation that :open-paren follows #? and
;; :open-brace follows #:ns. If tokens were malformed, position would
;; silently be off by one and wrong tokens would be parsed.
;; Fix: added explicit delimiter type checks matching the main parser.

(deftest build-tree-reader-cond-delimiter-validation
  (testing "reader conditional with correct delimiter parses normally"
    (let [tree (#'tree/tokens->tree
                (tokenizer/attach-whitespace
                 (tokenizer/tokenize "#?(:clj 1 :cljs 2)") "#?(:clj 1 :cljs 2)"))]
      (is (= 1 (count tree)))))
  (testing "namespaced map with correct delimiter parses normally"
    (let [tree (#'tree/tokens->tree
                (tokenizer/attach-whitespace
                 (tokenizer/tokenize "#:user{:a 1}") "#:user{:a 1}"))]
      (is (= 1 (count tree))))))

;; ============================================================
;; Cross-test: rewrite pipeline vs existing parser
;; ============================================================

(deftest cross-test-rewrite-vs-parser
  (doseq [[label src] [["simple call" "f(x y)"]
                        ["def" "def(x 42)"]
                        ["defn" "defn(foo [x] +(x 1))"]
                        ["let" "let([a 1 b 2] +(a b))"]
                        ["if" "if(true 1 2)"]
                        ["nested" "defn(f [x] let([a +(x 1)] *(a 2)))"]
                        ["vector" "[1 2 3]"]
                        ["map" "{:a 1 :b 2}"]
                        ["set" "#{1 2 3}"]
                        ["number" "42"]
                        ["string" "\"hello\""]
                        ["keyword" ":foo"]
                        ["empty list" "()"]
                        ["threading" "->(x inc str)"]
                        ["multiple forms" "def(a 1)\ndef(b 2)"]
                        ["quote sugar" "'foo"]
                        ["deref" "@atom"]
                        ["discard" "#_foo bar"]
                        ["meta kw" "^:private def(x 1)"]
                        ["meta type" "^String x"]
                        ["syntax-quote" "`x"]
                        ["unquote in sq" "`foo(~x)"]
                        #?@(:clj [["auto-kw" "::foo"]])
                        ["char" "\\a"]
                        ["char named" "\\newline"]
                        ["interop" ".toString(obj)"]
                        ["chained" "f(x)(y)"]
                        ["vec-head" "[x](+(x 1))"]
                        ["anon-fn" "#(inc(%))"]
                        ["var" "#'x"]
                        ["reader-cond" "#?(:clj 1 :cljs 2)"]]]
    (testing label
      (let [parser-result (core/meme->forms src)
            rewrite-result (rewrite-parse src)]
        (is (= parser-result rewrite-result)
            (str "divergence for " label
                 ": parser=" (pr-str parser-result)
                 " rewrite=" (pr-str rewrite-result)))))))
