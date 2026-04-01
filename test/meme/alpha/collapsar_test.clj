(ns meme.alpha.collapsar-test
  "Tests for the collapsar engine and meme↔clj collapsar pipeline."
  (:require [clojure.test :refer [deftest is testing]]
            [meme.alpha.collapsar :as c]
            [meme.alpha.collapsar.meme :as cm]))

;; ---------------------------------------------------------------------------
;; collapsar.meme: meme→clj
;; ---------------------------------------------------------------------------

(deftest collapsar-meme->clj-basic
  (testing "simple call"
    (is (= "(f x y)" (cm/meme->clj "f(x y)"))))
  (testing "nested calls"
    (is (= "(+ 1 (* 2 3))" (cm/meme->clj "+(1 *(2 3))"))))
  (testing "defn"
    (is (= "(defn foo [x] (+ x 1))" (cm/meme->clj "defn(foo [x] +(x 1))")))))

(deftest collapsar-meme->clj-data-literals
  (testing "vector"
    (is (= "[1 2 3]" (cm/meme->clj "[1 2 3]"))))
  (testing "map"
    (is (= "{:a 1}" (cm/meme->clj "{:a 1}"))))
  (testing "set"
    (is (= "#{1}" (cm/meme->clj "#{1}")))))

(deftest collapsar-meme->clj-empty-input
  (testing "empty string"
    (is (= "" (cm/meme->clj "")))))

(deftest collapsar-meme->clj-discard
  (testing "#_ inside vector"
    (is (= "[1 2]" (cm/meme->clj "[1 2 #_ 3]"))))
  (testing "#_ at top level"
    (is (= "c" (cm/meme->clj "#_ #_ a b c")))))

;; ---------------------------------------------------------------------------
;; collapsar.meme: clj→meme (JVM only)
;; ---------------------------------------------------------------------------

(deftest collapsar-clj->meme-basic
  (testing "simple S-expression"
    (is (= "f(x y)" (cm/clj->meme "(f x y)"))))
  (testing "defn"
    (is (= "defn(foo [x] +(x 1))" (cm/clj->meme "(defn foo [x] (+ x 1))")))))

;; ---------------------------------------------------------------------------
;; collapsar.meme: traced execution
;; ---------------------------------------------------------------------------

(deftest collapsar-traced-returns-result
  (testing "traced meme->clj returns same result"
    (let [result (cm/meme->clj-traced "f(x)")]
      (is (map? result))
      (is (string? (:result result))))))

;; ---------------------------------------------------------------------------
;; collapsar.meme: pipeline inspection
;; ---------------------------------------------------------------------------

(deftest collapsar-inspect
  (testing "inspect returns both pipeline descriptions"
    (let [info (cm/inspect)]
      (is (map? info))
      (is (contains? info :meme->clj))
      (is (contains? info :clj->meme)))))

;; ---------------------------------------------------------------------------
;; collapsar engine: core functions
;; ---------------------------------------------------------------------------

(deftest collapsar-pattern-var
  (testing "?x is a pattern var"
    (is (c/pattern-var? '?x)))
  (testing "plain symbol is not"
    (is (not (c/pattern-var? 'x)))))

(deftest collapsar-splice-var
  (testing "??xs is a splice var"
    (is (c/splice-var? '??xs)))
  (testing "?x is not a splice var"
    (is (not (c/splice-var? '?x)))))

(deftest collapsar-wildcard
  (testing "_ is a wildcard"
    (is (c/wildcard? '_)))
  (testing "other symbols are not"
    (is (not (c/wildcard? 'x)))))

(deftest collapsar-rule-construction
  (testing "rule creates a rule map"
    (let [r (c/rule '(?f ?x) '(call ?f ?x))]
      (is (map? r))
      (is (contains? r :pattern))
      (is (contains? r :replacement)))))

(deftest collapsar-match-and-substitute
  (testing "match-pattern binds variables"
    (let [bindings (c/match-pattern '(?f ?x ?y) '(add 1 2))]
      (is (= 'add (get bindings 'f)))
      (is (= 1 (get bindings 'x)))
      (is (= 2 (get bindings 'y)))))
  (testing "substitute fills in bindings"
    (is (= '(call add 1 2)
           (c/substitute '(call ?f ?x ?y) {'f 'add 'x 1 'y 2})))))

(deftest collapsar-rewrite
  (testing "bottom-up rewrite to fixpoint"
    (let [rules [(c/rule '(double ?x) '(+ ?x ?x))]
          result (c/rewrite rules '(double 3))]
      (is (= '(+ 3 3) result)))))

;; ---------------------------------------------------------------------------
;; collapsar engine: pipeline validation
;; ---------------------------------------------------------------------------

(deftest collapsar-pipeline-validation
  (testing "valid pipeline accepted"
    (let [p1 (c/make-phase :p1 [(c/rule '(a ?x) '(b ?x))])
          p2 (c/make-phase :p2 [(c/rule '(b ?x) '(c ?x))])]
      (is (some? (c/make-pipeline :test [p1 p2])))))
  (testing "invalid pipeline rejected — phase raises earlier head"
    (let [p1 (c/make-phase :p1 [(c/rule '(a ?x) '(b ?x))])
          p2 (c/make-phase :p2 [(c/rule '(b ?x) '(a ?x))])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid pipeline"
                            (c/make-pipeline :test [p1 p2]))))))

;; ---------------------------------------------------------------------------
;; collapsar engine: iteration cap enforcement
;; ---------------------------------------------------------------------------

(deftest collapsar-iteration-cap
  (testing "non-terminating phase throws after max-iters"
    (let [rules [(c/rule '(loop ?x) '(loop (inc ?x)))]
          phase (c/make-phase :loopy rules {:max-iters 5})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"did not reach fixed point"
                            (c/run-phase phase '(loop 0)))))))

;; ---------------------------------------------------------------------------
;; collapsar engine: head-eliminating detection
;; ---------------------------------------------------------------------------

(deftest collapsar-head-eliminating
  (testing "rules that eliminate consumed heads"
    (is (c/head-eliminating? [(c/rule '(a ?x) '(b ?x))])))
  (testing "rules that re-introduce consumed heads"
    (is (not (c/head-eliminating? [(c/rule '(a ?x) '(a (inc ?x)))])))))
