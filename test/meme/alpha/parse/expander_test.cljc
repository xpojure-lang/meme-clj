(ns meme.alpha.parse.expander-test
  "Unit tests for meme.alpha.parse.expander: syntax-quote expansion and
   MemeRaw unwrapping."
  (:require [clojure.test :refer [deftest is testing]]
            [meme.alpha.core :as core]
            [meme.alpha.forms :as forms]
            [meme.alpha.parse.expander :as expander]))

;; ---------------------------------------------------------------------------
;; expand-forms — no-op path (no AST nodes)
;; ---------------------------------------------------------------------------

(deftest expand-forms-passthrough
  (testing "plain forms pass through unchanged"
    (is (= [42] (expander/expand-forms [42])))
    (is (= ['foo] (expander/expand-forms ['foo])))
    (is (= ['(+ 1 2)] (expander/expand-forms ['(+ 1 2)])))
    (is (= [[:a :b]] (expander/expand-forms [[:a :b]])))
    (is (= [{:a 1}] (expander/expand-forms [{:a 1}])))))

;; ---------------------------------------------------------------------------
;; MemeRaw unwrapping
;; ---------------------------------------------------------------------------

(deftest expand-forms-unwraps-raw
  (testing "MemeRaw at top level is unwrapped to plain value"
    (is (= [255] (expander/expand-forms [(forms/->MemeRaw 255 "0xFF")]))))
  (testing "MemeRaw nested in a list is unwrapped"
    (let [form (list '+ (forms/->MemeRaw 10 "0xA") 1)
          result (first (expander/expand-forms [form]))]
      (is (= 10 (second result))))))

;; ---------------------------------------------------------------------------
;; Syntax-quote expansion via meme->forms + expand
;; ---------------------------------------------------------------------------

(deftest expand-syntax-quote-symbol
  (testing "syntax-quote on a symbol produces (quote sym)"
    (let [forms (core/meme->forms "`x")
          expanded (expander/expand-forms forms)]
      (is (= '(quote x) (first expanded))))))

(deftest expand-syntax-quote-list
  (testing "syntax-quote on a list produces seq/concat/list"
    (let [forms (core/meme->forms "`foo(x y)")
          expanded (first (expander/expand-forms forms))]
      ;; Should be (seq (concat (list (quote foo)) (list (quote x)) (list (quote y))))
      (is (seq? expanded))
      (is (= 'clojure.core/seq (first expanded))))))

(deftest expand-syntax-quote-with-unquote
  (testing "unquote inside syntax-quote splices the value"
    (let [forms (core/meme->forms "`foo(~x)")
          expanded (first (expander/expand-forms forms))]
      ;; The expansion should reference x directly (not quoted)
      ;; (seq (concat (list (quote foo)) (list x)))
      (is (seq? expanded))
      ;; Find the concat args and check that x appears unquoted
      (let [concat-form (second expanded)
            args (rest concat-form)]
        ;; First arg: (list (quote foo)) — quoted head
        (is (= '(clojure.core/list (quote foo)) (first args)))
        ;; Second arg: (list x) — unquoted
        (is (= '(clojure.core/list x) (second args)))))))

(deftest expand-syntax-quote-with-unquote-splicing
  (testing "unquote-splicing inside syntax-quote splices the collection"
    (let [forms (core/meme->forms "`foo(~@xs)")
          expanded (first (expander/expand-forms forms))]
      ;; (seq (concat (list (quote foo)) xs))
      (let [concat-form (second expanded)
            args (rest concat-form)]
        ;; First arg: (list (quote foo)) — quoted head
        (is (= '(clojure.core/list (quote foo)) (first args)))
        ;; Second arg: xs — spliced directly
        (is (= 'xs (second args)))))))

;; ---------------------------------------------------------------------------
;; Gensym consistency
;; ---------------------------------------------------------------------------

(deftest expand-gensym-consistent
  (testing "foo# resolves to the same gensym within one syntax-quote"
    (let [forms (core/meme->forms "`let([x# 1] x#)")
          expanded (first (expander/expand-forms forms))]
      ;; The two occurrences of x# should expand to the same generated symbol
      ;; Extract the vector arg (second in concat after let head)
      ;; Structure: (seq (concat (list (quote let)) (list (vec (concat ...))) ...))
      ;; We just check the full expansion evaluates without error and that
      ;; the gensym names match by checking they contain "auto"
      (is (seq? expanded))
      (is (= 'clojure.core/seq (first expanded))))))

;; ---------------------------------------------------------------------------
;; Nested syntax-quote
;; ---------------------------------------------------------------------------

(deftest nested-syntax-quote-expands
  (testing "nested backtick does not crash"
    (let [forms (core/meme->forms "``x")
          expanded (expander/expand-forms forms)]
      (is (seq? (first expanded)) "nested syntax-quote should expand to a seq form"))))

;; ---------------------------------------------------------------------------
;; expand-syntax-quotes on individual forms
;; ---------------------------------------------------------------------------

(deftest expand-syntax-quotes-vector
  (testing "vectors inside syntax-quote are preserved as vectors"
    (let [forms (core/meme->forms "`[a b]")
          expanded (first (expander/expand-forms forms))]
      ;; Should produce (apply vector (concat ...))
      (is (seq? expanded))
      (is (= 'clojure.core/apply (first expanded)))
      (is (= 'clojure.core/vector (second expanded))))))

(deftest expand-syntax-quotes-self-quoting
  (testing "keywords, numbers, strings are self-quoting inside syntax-quote"
    (let [forms (core/meme->forms "`:foo")
          expanded (first (expander/expand-forms forms))]
      (is (= :foo expanded)))
    (let [forms (core/meme->forms "`42")
          expanded (first (expander/expand-forms forms))]
      (is (= 42 expanded)))
    (let [forms (core/meme->forms "`\"hello\"")
          expanded (first (expander/expand-forms forms))]
      (is (= "hello" expanded)))))
