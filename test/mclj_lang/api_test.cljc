(ns mclj-lang.api-test
  "Tests for the mclj-lang.api language API.

   Run-string and REPL input-state behavior is exercised in mclj-lang.run-test
   and mclj-lang.repl-test per the placement table — this file covers the
   text↔forms↔text tracks plus the format/* tracks of the language API."
  (:require [clojure.test :refer [deftest is testing]]
            [mclj-lang.api :as lang]))

;; ---------------------------------------------------------------------------
;; Text-to-form track
;; ---------------------------------------------------------------------------

(deftest mclj->forms-test
  (testing "single form"
    (is (= '[(+ 1 2)] (lang/mclj->forms "+(1 2)"))))
  (testing "multiple forms"
    (is (= '[(def x 42) (println x)]
           (lang/mclj->forms "def(x 42)\nprintln(x)"))))
  (testing "empty string"
    (is (= [] (lang/mclj->forms "")))))

(deftest forms->mclj-test
  (testing "single form"
    (is (= "+(1 2)" (lang/forms->mclj ['(+ 1 2)]))))
  (testing "multiple forms separated by blank line"
    (is (= "def(x 42)\n\nprintln(x)"
           (lang/forms->mclj ['(def x 42) '(println x)])))))

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

(deftest mclj->clj-test
  (testing "basic conversion"
    (is (= "(+ 1 2)" (lang/mclj->clj "+(1 2)"))))
  (testing "multiple forms"
    (is (= "(def x 42)\n\n(println x)"
           (lang/mclj->clj "def(x 42)\nprintln(x)")))))

#?(:clj
   (deftest clj->mclj-test
     (testing "basic conversion"
       (is (= "+(1 2)" (lang/clj->mclj "(+ 1 2)"))))))


;; ---------------------------------------------------------------------------
;; Format
;; ---------------------------------------------------------------------------

(deftest format-mclj-test
  (testing "basic formatting"
    (is (= "+(1 2)" (lang/format-mclj "+(1 2)" {}))))
  (testing "empty source returns source"
    (is (= "" (lang/format-mclj "" {})))))

(deftest format-mclj-forms-test
  (testing "single short form fits on one line"
    (is (= "+(1 2)" (lang/format-mclj-forms ['(+ 1 2)]))))
  (testing "multiple forms separated by blank line"
    (is (= "+(1 2)\n\n*(3 4)"
           (lang/format-mclj-forms ['(+ 1 2) '(* 3 4)]))))
  (testing "empty forms vector → empty string"
    (is (= "" (lang/format-mclj-forms []))))
  (testing "narrow width forces multi-line"
    (let [out (lang/format-mclj-forms ['(defn greet [name] (str "Hello " name))]
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
