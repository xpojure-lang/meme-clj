(ns m1clj-lang.api-test
  "Tests for the m1clj-lang.api language API.

   Run-string and REPL input-state behavior is exercised in m1clj-lang.run-test
   and m1clj-lang.repl-test per the placement table — this file covers the
   text↔forms↔text tracks plus the format/* tracks of the language API."
  (:require [clojure.test :refer [deftest is testing]]
            [m1clj-lang.api :as lang]))

;; ---------------------------------------------------------------------------
;; Text-to-form track
;; ---------------------------------------------------------------------------

(deftest m1clj->forms-test
  (testing "single form"
    (is (= '[(+ 1 2)] (lang/m1clj->forms "+(1 2)"))))
  (testing "multiple forms"
    (is (= '[(def x 42) (println x)]
           (lang/m1clj->forms "def(x 42)\nprintln(x)"))))
  (testing "empty string"
    (is (= [] (lang/m1clj->forms "")))))

(deftest forms->m1clj-test
  (testing "single form"
    (is (= "+(1 2)" (lang/forms->m1clj ['(+ 1 2)]))))
  (testing "multiple forms separated by blank line"
    (is (= "def(x 42)\n\nprintln(x)"
           (lang/forms->m1clj ['(def x 42) '(println x)])))))

;; ---------------------------------------------------------------------------
;; Form-to-text track
;; ---------------------------------------------------------------------------

(deftest forms->clj-test
  (testing "single form"
    (is (= "(+ 1 2)" (lang/forms->clj ['(+ 1 2)]))))
  (testing "multiple forms"
    (is (= "(def x 42)\n\n(println x)"
           (lang/forms->clj ['(def x 42) '(println x)])))))

#?(:clj
   (deftest clj->forms-test
     (testing "single form"
       (is (= '[(defn f [x] (+ x 1))]
              (lang/clj->forms "(defn f [x] (+ x 1))"))))
     (testing "multiple forms"
       (is (= '[(def x 42) (println x)]
              (lang/clj->forms "(def x 42)\n(println x)"))))))

;; ---------------------------------------------------------------------------
;; Text-to-text track
;; ---------------------------------------------------------------------------

(deftest m1clj->clj-test
  (testing "basic conversion"
    (is (= "(+ 1 2)" (lang/m1clj->clj "+(1 2)"))))
  (testing "multiple forms"
    (is (= "(def x 42)\n\n(println x)"
           (lang/m1clj->clj "def(x 42)\nprintln(x)")))))

#?(:clj
   (deftest clj->m1clj-test
     (testing "basic conversion"
       (is (= "+(1 2)" (lang/clj->m1clj "(+ 1 2)"))))))


;; ---------------------------------------------------------------------------
;; Format
;; ---------------------------------------------------------------------------

(deftest format-m1clj-test
  (testing "basic formatting"
    (is (= "+(1 2)" (lang/format-m1clj "+(1 2)" {}))))
  (testing "empty source returns source"
    (is (= "" (lang/format-m1clj "" {})))))

(deftest format-m1clj-forms-test
  (testing "single short form fits on one line"
    (is (= "+(1 2)" (lang/format-m1clj-forms ['(+ 1 2)]))))
  (testing "multiple forms separated by blank line"
    (is (= "+(1 2)\n\n*(3 4)"
           (lang/format-m1clj-forms ['(+ 1 2) '(* 3 4)]))))
  (testing "empty forms vector → empty string"
    (is (= "" (lang/format-m1clj-forms []))))
  (testing "narrow width forces multi-line"
    (let [out (lang/format-m1clj-forms ['(defn greet [name] (str "Hello " name))]
                                       {:width 20})]
      (is (re-find #"\n" out)))))

;; ---------------------------------------------------------------------------
;; C5: clj->forms deep nesting guard
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest clj->forms-deep-nesting-guard
     (testing "deeply nested Clojure input produces clean error, not StackOverflowError"
       (let [depth 10000
             input (str (apply str (repeat depth "(")) "x" (apply str (repeat depth ")")))]
         (is (thrown-with-msg? Exception #"nesting depth"
                               (lang/clj->forms input)))))))
