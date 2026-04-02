(ns meme.rewrite.rules-test
  "Tests for S→M and M→S rewrite rules.
   Cross-tests the rule-based path against the existing printer."
  (:require [clojure.test :refer [deftest is testing]]
            [meme.rewrite :as rw]
            [meme.rewrite.rules :as rules]
            [meme.rewrite.emit :as emit]
            [meme.emit.formatter.flat :as fmt-flat]))

;; ============================================================
;; S→M rules: tag Clojure forms with m-call
;; ============================================================

(deftest s->m-basic
  (testing "simple call"
    (is (= '(m-call + 1 2)
           (rw/rewrite rules/s->m-rules '(+ 1 2)))))
  (testing "nested calls"
    (is (= '(m-call + (m-call * x y) 1)
           (rw/rewrite rules/s->m-rules '(+ (* x y) 1)))))
  (testing "vector passes through"
    (is (= [1 2 3]
           (rw/rewrite rules/s->m-rules [1 2 3]))))
  (testing "def"
    (is (= '(m-call def x 42)
           (rw/rewrite rules/s->m-rules '(def x 42)))))
  (testing "defn"
    (is (= '(m-call defn foo [x] (m-call + x 1))
           (rw/rewrite rules/s->m-rules '(defn foo [x] (+ x 1))))))
  (testing "let"
    (is (= '(m-call let [a 1 b 2] (m-call + a b))
           (rw/rewrite rules/s->m-rules '(let [a 1 b 2] (+ a b))))))
  (testing "empty list"
    (is (= () (rw/rewrite rules/s->m-rules ()))))
  (testing "symbol alone"
    (is (= 'x (rw/rewrite rules/s->m-rules 'x))))
  (testing "keyword alone"
    (is (= :foo (rw/rewrite rules/s->m-rules :foo)))))

;; ============================================================
;; M→S rules: convert m-call back to S-expressions
;; ============================================================

(deftest m->s-basic
  (testing "simple m-call"
    (is (= '(+ 1 2)
           (rw/rewrite rules/m->s-rules '(m-call + 1 2)))))
  (testing "nested m-call"
    (is (= '(defn foo [x] (+ x 1))
           (rw/rewrite rules/m->s-rules '(m-call defn foo [x] (m-call + x 1))))))
  (testing "roundtrip: S→M→S"
    (let [original '(defn greet [name] (str "Hello, " name "!"))
          tagged (rw/rewrite rules/s->m-rules original)
          recovered (rw/rewrite rules/m->s-rules tagged)]
      (is (= original recovered)))))

;; ============================================================
;; Emit: m-call tree → meme text
;; ============================================================

(deftest emit-basic
  (testing "m-call emits head(args)"
    (is (= "+(1 2)" (emit/emit '(m-call + 1 2)))))
  (testing "nested m-call"
    (is (= "defn(foo [x] +(x 1))"
           (emit/emit '(m-call defn foo [x] (m-call + x 1))))))
  (testing "empty call"
    (is (= "rand()" (emit/emit '(m-call rand)))))
  (testing "vector"
    (is (= "[1 2 3]" (emit/emit [1 2 3]))))
  (testing "map"
    (is (= "{:a 1}" (emit/emit {:a 1}))))
  (testing "symbol"
    (is (= "foo" (emit/emit 'foo))))
  (testing "string"
    (is (= "\"hello\"" (emit/emit "hello")))))

;; ============================================================
;; Cross-test: rule-based S→M+emit vs existing printer
;; ============================================================

(deftest cross-test-s->m-vs-printer
  (doseq [[label form] [["simple call" '(+ 1 2)]
                         ["def" '(def x 42)]
                         ["defn" '(defn foo [x] (+ x 1))]
                         ["let" '(let [a 1] (* a 2))]
                         ["nested" '(defn f [x] (let [a (+ x 1)] (* a 2)))]
                         ["if" '(if true 1 2)]
                         ["threading" '(-> x inc str)]
                         ["vector data" [1 2 3]]
                         ["keyword" :foo]
                         ["symbol" 'bar]
                         ["number" 42]
                         ["string" "hello"]]]
    (testing label
      (let [;; existing printer path
            printer-output (fmt-flat/format-form form)
            ;; rule-based path
            tagged (rw/rewrite rules/s->m-rules form)
            rule-output (emit/emit tagged)]
        (is (= printer-output rule-output)
            (str "divergence for " label
                 ": printer=" (pr-str printer-output)
                 " rules=" (pr-str rule-output)))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: s->m-rules must tag nil/true/false-headed lists as m-call.
;; Bug: guard (and (or (symbol? f) (keyword? f)) ...) excluded nil/true/false.
;; Fix: extended guard to accept nil?, true?, false?.
;; ---------------------------------------------------------------------------

(deftest s->m-rules-non-symbol-heads
  (testing "nil-headed list is tagged as m-call"
    (let [result (rw/rewrite rules/s->m-rules '(nil 1 2))]
      (is (= '(m-call nil 1 2) result))))
  (testing "true-headed list is tagged as m-call"
    (let [result (rw/rewrite rules/s->m-rules '(true :a))]
      (is (= '(m-call true :a) result))))
  (testing "false-headed list is tagged as m-call"
    (let [result (rw/rewrite rules/s->m-rules '(false :b))]
      (is (= '(m-call false :b) result))))
  (testing "number-headed list is NOT tagged (data, not a call)"
    (let [result (rw/rewrite rules/s->m-rules '(42 x y))]
      (is (= '(42 x y) result)
          "number heads remain plain lists — they are data, not calls")))
  (testing "m-call itself is never re-tagged"
    (let [result (rw/rewrite rules/s->m-rules '(m-call f x))]
      (is (= '(m-call f x) result)))))
