(ns inme-lang.grammar-test
  "Tests for inme-lang: infix operators, precedence, grouping, and
   interaction with meme syntax (calls, reader sugar)."
  (:require [clojure.test :refer [deftest is testing]]
            [inme-lang.api :as inme]))

(defn- parse-one [src] (first (inme/inme->forms src)))

;; ---------------------------------------------------------------------------
;; Atoms still work (everything meme parses, inme parses)
;; ---------------------------------------------------------------------------

(deftest atoms-pass-through
  (is (= 42 (parse-one "42")))
  (is (= 'foo (parse-one "foo")))
  (is (= :k (parse-one ":k")))
  (is (= "hi" (parse-one "\"hi\"")))
  (is (= [1 2 3] (parse-one "[1 2 3]")))
  (is (= {:a 1} (parse-one "{:a 1}"))))

;; ---------------------------------------------------------------------------
;; Binary infix operators
;; ---------------------------------------------------------------------------

(deftest arithmetic-operators
  (is (= '(+ 1 2) (parse-one "1 + 2")))
  (is (= '(- 5 3) (parse-one "5 - 3")))
  (is (= '(* 2 3) (parse-one "2 * 3")))
  (is (= '(/ 10 2) (parse-one "10 / 2")))
  (is (= '(mod 10 3) (parse-one "10 % 3"))))

(deftest comparison-operators
  (is (= '(< 1 2) (parse-one "1 < 2")))
  (is (= '(> 3 1) (parse-one "3 > 1")))
  (is (= '(<= 1 1) (parse-one "1 <= 1")))
  (is (= '(>= 2 2) (parse-one "2 >= 2"))))

(deftest equality-operators
  (is (= '(= 1 1) (parse-one "1 == 1")))
  (is (= '(not= 1 2) (parse-one "1 != 2"))))

;; ---------------------------------------------------------------------------
;; Precedence
;; ---------------------------------------------------------------------------

(deftest multiplicative-binds-tighter-than-additive
  (is (= '(+ 1 (* 2 3)) (parse-one "1 + 2 * 3")))
  (is (= '(+ (* 2 3) 1) (parse-one "2 * 3 + 1"))))

(deftest additive-binds-tighter-than-comparison
  (is (= '(< (+ 1 2) 5) (parse-one "1 + 2 < 5"))))

(deftest comparison-binds-tighter-than-equality
  (is (= '(= (< 1 2) true) (parse-one "1 < 2 == true"))))

(deftest left-associative
  (testing "same-precedence ops associate left to right"
    (is (= '(- (+ 1 2) 3) (parse-one "1 + 2 - 3")))
    (is (= '(/ (* 10 2) 5) (parse-one "10 * 2 / 5")))))

;; ---------------------------------------------------------------------------
;; Grouping
;; ---------------------------------------------------------------------------

(deftest parens-group-single-expression
  (is (= 42 (parse-one "(42)")))
  (is (= '(+ 1 2) (parse-one "(1 + 2)")))
  (is (= '(* (+ 1 2) 3) (parse-one "(1 + 2) * 3"))))

(deftest empty-parens-are-empty-list
  (is (= '() (parse-one "()"))))

(deftest bare-multi-expression-parens-error
  (testing "`(a b)` is not valid — grouping expects a single expression"
    (is (thrown? #?(:clj Exception :cljs :default)
                 (parse-one "(1 2)")))))

;; ---------------------------------------------------------------------------
;; Interaction with meme syntax
;; ---------------------------------------------------------------------------

(deftest infix-inside-call
  (is (= '(f (+ 1 2)) (parse-one "f(1 + 2)")))
  (is (= '(vec [(+ 1 2) (* 3 4)]) (parse-one "vec([1 + 2 3 * 4])"))))

(deftest call-inside-infix
  (is (= '(+ (f 1) (g 2)) (parse-one "f(1) + g(2)")))
  (is (= '(* (count xs) 2) (parse-one "count(xs) * 2"))))

(deftest negative-literals-still-work
  (testing "meme's nud-pred for -digit keeps parsing negative numbers"
    (is (= '(+ -5 3) (parse-one "-5 + 3")))
    (is (= '(+ 1 -5) (parse-one "1 + -5")))))

(deftest reader-sugar-works
  (is (= '(+ (quote x) (quote y)) (parse-one "'x + 'y")))
  (is (= '(+ (clojure.core/deref a) (clojure.core/deref b)) (parse-one "@a + @b"))))

(deftest vectors-and-maps-mixed-with-infix
  (is (= '(+ [1 2] [3 4]) (parse-one "[1 2] + [3 4]"))))

;; ---------------------------------------------------------------------------
;; Multi-form sources
;; ---------------------------------------------------------------------------

(deftest multiple-top-level-forms
  (is (= '[(def x 10) (* x 2)]
         (inme/inme->forms "def(x 10)\nx * 2"))))

;; ---------------------------------------------------------------------------
;; inme->clj
;; ---------------------------------------------------------------------------

(deftest to-clj-produces-clojure-syntax
  (is (= "(+ 1 2)" (inme/inme->clj "1 + 2")))
  (is (= "(+ (f 1) (g 2))" (inme/inme->clj "f(1) + g(2)"))))

;; ---------------------------------------------------------------------------
;; Registry dispatch
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest lang-registered
     (testing ":inme is registered and .inme extension resolves to it"
       (let [reg (requiring-resolve 'meme.registry/resolve-lang)
             by-ext (requiring-resolve 'meme.registry/resolve-by-extension)]
         (is (some? (reg :inme)))
         (let [[lang-kw lang-map] (by-ext ".inme")]
           (is (= :inme lang-kw))
           (is (some? (:format lang-map))))))))

;; ---------------------------------------------------------------------------
;; run-string (JVM only)
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest run-string-evaluates-infix
     (let [run-str (requiring-resolve 'inme-lang.run/run-string)]
       (is (= 3 (run-str "1 + 2")))
       (is (= 20 (run-str "def(x 10) x * 2")))
       (is (= :yes (run-str "if(1 < 2 :yes :no)"))))))
