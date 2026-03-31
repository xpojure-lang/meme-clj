(ns meme.alpha.rewrite.emit-test
  "Tests for meme.alpha.rewrite.emit: serializes m-call tagged trees to meme text."
  (:require [clojure.test :refer [deftest is testing]]
            [meme.alpha.rewrite.emit :as emit]))

;; ---------------------------------------------------------------------------
;; emit — single form
;; ---------------------------------------------------------------------------

(deftest emit-m-call
  (testing "m-call → call syntax"
    (is (= "f(x y)" (emit/emit '(m-call f x y)))))
  (testing "nested m-call"
    (is (= "f(g(x))" (emit/emit '(m-call f (m-call g x))))))
  (testing "nullary m-call"
    (is (= "f()" (emit/emit '(m-call f))))))

(deftest emit-plain-list
  (testing "empty list"
    (is (= "()" (emit/emit '()))))
  (testing "non-m-call list passes through"
    (is (= "(a b c)" (emit/emit '(a b c))))))

(deftest emit-data-literals
  (testing "vector"
    (is (= "[1 2 3]" (emit/emit [1 2 3]))))
  (testing "empty vector"
    (is (= "[]" (emit/emit []))))
  (testing "map"
    (is (= "{:a 1}" (emit/emit {:a 1}))))
  (testing "set"
    (is (= "#{1}" (emit/emit #{1}))))
  (testing "nil"
    (is (= "nil" (emit/emit nil))))
  (testing "boolean"
    (is (= "true" (emit/emit true))))
  (testing "string"
    (is (= "\"hello\"" (emit/emit "hello"))))
  (testing "keyword"
    (is (= ":foo" (emit/emit :foo))))
  (testing "symbol"
    (is (= "bar" (emit/emit 'bar)))))

(deftest emit-numbers
  (testing "integer"
    (is (= "42" (emit/emit 42))))
  (testing "float"
    (is (= "3.14" (emit/emit 3.14))))
  #?(:clj
     (do
       (testing "ratio"
         (is (= "1/3" (emit/emit 1/3))))
       (testing "BigDecimal"
         (is (= "3.14M" (emit/emit 3.14M))))
       (testing "BigInt"
         (is (= "42N" (emit/emit 42N)))))))

(deftest emit-special-values
  (testing "##Inf"
    (is (= "##Inf" (emit/emit ##Inf))))
  (testing "##-Inf"
    (is (= "##-Inf" (emit/emit ##-Inf))))
  (testing "##NaN"
    (is (= "##NaN" (emit/emit ##NaN)))))

;; ---------------------------------------------------------------------------
;; emit-forms — multiple forms
;; ---------------------------------------------------------------------------

(deftest emit-forms-multiple
  (testing "multiple forms separated by newlines"
    (is (= "f(x)\n\ng(y)"
           (emit/emit-forms ['(m-call f x) '(m-call g y)]))))
  (testing "empty input"
    (is (= "" (emit/emit-forms [])))))
