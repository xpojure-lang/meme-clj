(ns beme.alpha.emit.pprint-test
  (:require [clojure.test :refer [deftest is testing]]
            [beme.alpha.emit.pprint :as pprint]))

;; ---------------------------------------------------------------------------
;; Flat output — forms that fit within width
;; ---------------------------------------------------------------------------

(deftest flat-primitives
  (is (= "42" (pprint/pprint-form 42)))
  (is (= ":foo" (pprint/pprint-form :foo)))
  (is (= "\"hello\"" (pprint/pprint-form "hello")))
  (is (= "nil" (pprint/pprint-form nil)))
  (is (= "true" (pprint/pprint-form true))))

(deftest flat-call
  (testing "call that fits stays flat"
    (is (= "+(1 2)" (pprint/pprint-form '(+ 1 2)))))
  (testing "zero-arg call"
    (is (= "foo()" (pprint/pprint-form '(foo))))))

(deftest flat-collections
  (is (= "[1 2 3]" (pprint/pprint-form [1 2 3])))
  (is (= "{:a 1}" (pprint/pprint-form {:a 1})))
  (is (= "#{42}" (pprint/pprint-form #{42}))))

;; ---------------------------------------------------------------------------
;; Multi-line — begin/end for calls exceeding width
;; ---------------------------------------------------------------------------

(deftest multi-line-all-in-body
  (testing "let (head-line-args=0) puts everything in body"
    (let [result (pprint/pprint-form '(let [x 1] (+ x 1)) {:width 15})]
      (is (= "let begin\n  [x 1]\n  +(x 1)\nend" result)))))

(deftest multi-line-head-line-args
  (testing "defn (head-line-args=1) keeps name on first line"
    (let [result (pprint/pprint-form
                  '(defn greet [name] (str "Hello " name))
                  {:width 30})]
      (is (re-find #"^defn begin greet\n" result))
      (is (re-find #"\nend$" result)))))

(deftest multi-line-if-keeps-condition
  (testing "if (head-line-args=1) keeps condition on first line"
    (let [result (pprint/pprint-form
                  '(if (> x 0) "positive" "negative")
                  {:width 30})]
      (is (re-find #"^if begin >\(x 0\)" result)))))

(deftest multi-line-defmethod-keeps-two
  (testing "defmethod (head-line-args=2) keeps name and dispatch"
    (let [result (pprint/pprint-form
                  '(defmethod area :circle [{:keys [radius]}] (* Math/PI (* radius radius)))
                  {:width 40})]
      (is (re-find #"^defmethod begin area :circle" result)))))

(deftest head-args-fallback-when-too-wide
  (testing "head-line args that don't fit fall back to all-in-body"
    (let [result (pprint/pprint-form
                  '(defn a-very-long-function-name [x] (+ x 1))
                  {:width 25})]
      ;; name is too wide for first line, so all args go in body
      (is (re-find #"^defn begin\n" result)))))

;; ---------------------------------------------------------------------------
;; Width parameter
;; ---------------------------------------------------------------------------

(deftest width-forces-layout
  (testing "narrow width forces multi-line"
    (is (re-find #"begin" (pprint/pprint-form '(+ 1 2 3 4 5) {:width 10}))))
  (testing "wide width keeps flat"
    (is (not (re-find #"begin" (pprint/pprint-form '(+ 1 2 3 4 5) {:width 200}))))))

;; ---------------------------------------------------------------------------
;; Collections — multi-line
;; ---------------------------------------------------------------------------

(deftest multi-line-vector
  (let [result (pprint/pprint-form [1 2 3 4 5 6 7 8 9 10] {:width 15})]
    (is (re-find #"^\[" result))
    (is (re-find #"\n" result))
    (is (re-find #"\]$" result))))

(deftest multi-line-map
  (let [result (pprint/pprint-form (sorted-map :a 1 :b 2 :c 3 :d 4) {:width 15})]
    (is (re-find #"^\{" result))
    (is (re-find #"\n" result))
    (is (re-find #"\}$" result))))

(deftest multi-line-set
  (let [result (pprint/pprint-form (sorted-set 1 2 3 4 5 6 7 8 9 10) {:width 15})]
    (is (re-find #"^#\{" result))
    (is (re-find #"\n" result))
    (is (re-find #"\}$" result))))

;; ---------------------------------------------------------------------------
;; Comments from :ws metadata
;; ---------------------------------------------------------------------------

(deftest comment-before-form
  (let [form (with-meta '(foo x) {:ws "; a comment\n"})
        result (pprint/pprint-form form)]
    (is (re-find #"^; a comment\n" result))
    (is (re-find #"foo\(x\)" result))))

(deftest multiple-comment-lines
  (let [form (with-meta '(foo) {:ws "; line 1\n; line 2\n"})
        result (pprint/pprint-form form)]
    (is (re-find #"; line 1\n; line 2\n" result))))

(deftest no-ws-metadata
  (testing "form without :ws has no comment prefix"
    (is (= "+(1 2)" (pprint/pprint-form '(+ 1 2))))))

;; ---------------------------------------------------------------------------
;; pprint-forms — multiple forms
;; ---------------------------------------------------------------------------

(deftest pprint-forms-separated-by-blank-lines
  (let [result (pprint/pprint-forms ['(def x 42) '(println x)])]
    (is (= "def(x 42)\n\nprintln(x)" result))))

(deftest pprint-forms-trailing-comments
  (let [forms (with-meta ['(def x 1)] {:trailing-ws "\n; end of file\n"})
        result (pprint/pprint-forms forms)]
    (is (re-find #"; end of file" result))))

(deftest pprint-forms-single
  (is (= "42" (pprint/pprint-forms [42]))))
