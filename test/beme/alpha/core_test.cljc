(ns beme.alpha.core-test
  "Tests for the beme.alpha.core public API."
  (:require [clojure.test :refer [deftest is testing]]
            [beme.alpha.core :as core]))

;; ---------------------------------------------------------------------------
;; Text-to-form track
;; ---------------------------------------------------------------------------

(deftest beme->forms-test
  (testing "single form"
    (is (= '[(+ 1 2)] (core/beme->forms "+(1 2)"))))
  (testing "multiple forms"
    (is (= '[(def x 42) (println x)]
           (core/beme->forms "def(x 42)\nprintln(x)"))))
  (testing "empty string"
    (is (= [] (core/beme->forms "")))))

(deftest forms->beme-test
  (testing "single form"
    (is (= "+(1 2)" (core/forms->beme ['(+ 1 2)]))))
  (testing "multiple forms separated by blank line"
    (is (= "def(x 42)\n\nprintln(x)"
           (core/forms->beme ['(def x 42) '(println x)])))))

;; ---------------------------------------------------------------------------
;; Form-to-text track
;; ---------------------------------------------------------------------------

(deftest forms->clj-test
  (testing "single form"
    (is (= "(+ 1 2)" (core/forms->clj ['(+ 1 2)]))))
  (testing "multiple forms"
    (is (= "(def x 42)\n\n(println x)"
           (core/forms->clj ['(def x 42) '(println x)])))))

#?(:clj
(deftest clj->forms-test
  (testing "single form"
    (is (= '[(defn f [x] (+ x 1))]
           (core/clj->forms "(defn f [x] (+ x 1))"))))
  (testing "multiple forms"
    (is (= '[(def x 42) (println x)]
           (core/clj->forms "(def x 42)\n(println x)"))))))

;; ---------------------------------------------------------------------------
;; Text-to-text track
;; ---------------------------------------------------------------------------

(deftest beme->clj-test
  (testing "returns Clojure source string"
    (is (= "(+ 1 2)" (core/beme->clj "+(1 2)"))))
  (testing "multiple forms joined by blank lines"
    (is (= "(def x 42)\n\n(println x)"
           (core/beme->clj "def(x 42)\nprintln(x)")))))

#?(:clj
(deftest clj->beme-test
  (testing "converts Clojure source to beme"
    (is (= "defn(f [x] +(x 1))"
           (core/clj->beme "(defn f [x] (+ x 1))"))))
  (testing "multiple forms"
    (is (= "def(x 42)\n\nprintln(x)"
           (core/clj->beme "(def x 42)\n(println x)"))))))

;; ---------------------------------------------------------------------------
;; Composition identities
;; ---------------------------------------------------------------------------

(deftest composition-identities
  (testing "beme->clj = forms->clj . beme->forms"
    (let [src "def(x 42)\nprintln(x)"]
      (is (= (core/beme->clj src)
             (core/forms->clj (core/beme->forms src))))))
  #?(:clj
  (testing "clj->beme = forms->beme . clj->forms"
    (let [src "(def x 42)\n(println x)"]
      (is (= (core/clj->beme src)
             (core/forms->beme (core/clj->forms src))))))))

;; ---------------------------------------------------------------------------
;; Options
;; ---------------------------------------------------------------------------

#?(:clj
(deftest beme->forms-with-resolve-keyword
  (testing ":resolve-keyword option resolves :: keywords at read time"
    (let [forms (core/beme->forms "::foo"
                  {:resolve-keyword #(clojure.core/read-string %)})]
      (is (= [:user/foo] forms))))))

;; ---------------------------------------------------------------------------
;; Deprecated aliases (backward compatibility)
;; ---------------------------------------------------------------------------

(deftest read-beme-string-test
  (testing "deprecated alias works"
    (is (= '[(+ 1 2)] (core/read-beme-string "+(1 2)")))))

(deftest print-beme-string-test
  (testing "deprecated alias works"
    (is (= "+(1 2)" (core/print-beme-string ['(+ 1 2)])))))

#?(:clj
(deftest clj-string->beme-test
  (testing "deprecated alias works"
    (is (= "defn(f [x] +(x 1))"
           (core/clj-string->beme "(defn f [x] (+ x 1))"))))))

#?(:clj
(deftest clj->forms-empty
  (testing "empty string produces empty vector"
    (is (= [] (core/clj->forms ""))))))

#?(:clj
(deftest clj->forms-reader-conditional
  (testing "reader conditional parses as single form"
    (let [forms (core/clj->forms "#?(:clj 1 :cljs 2)")]
      (is (= 1 (count forms)))
      (is (reader-conditional? (first forms)))))))

#?(:clj
(deftest clj->forms-eof-sentinel-no-collision
  (testing ":beme.alpha.core/eof as top-level form does not truncate output"
    (is (= [:beme.alpha.core/eof '(def y 2)]
           (core/clj->forms ":beme.alpha.core/eof (def y 2)"))))
  (testing ":beme.alpha.core/eof inside a form is preserved"
    (is (= '[(def x :beme.alpha.core/eof)]
           (core/clj->forms "(def x :beme.alpha.core/eof)"))))))

#?(:clj
(deftest beme->clj-with-opts
  (testing "basic beme->clj conversion"
    (is (= "(+ 1 2)" (core/beme->clj "+(1 2)"))))))

;; ---------------------------------------------------------------------------
;; Error propagation
;; ---------------------------------------------------------------------------

(deftest beme->forms-error-has-location
  (testing "parse error through beme->forms carries :line and :col in ex-data"
    (try
      (core/beme->forms "(bare parens)")
      (is false "should have thrown")
      (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
        (let [data (ex-data e)]
          (is (integer? (:line data)))
          (is (integer? (:col data))))))))

(deftest beme->clj-error-has-location
  (testing "parse error through beme->clj carries :line and :col in ex-data"
    (try
      (core/beme->clj "(bare parens)")
      (is false "should have thrown")
      (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
        (let [data (ex-data e)]
          (is (integer? (:line data)))
          (is (integer? (:col data))))))))

(deftest run-pipeline-error-has-location
  (testing "parse error through run-pipeline carries :line and :col in ex-data"
    (try
      (core/run-pipeline "(bare parens)")
      (is false "should have thrown")
      (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
        (let [data (ex-data e)]
          (is (integer? (:line data)))
          (is (integer? (:col data))))))))

(deftest beme->forms-error-is-ex-info
  (testing "parse error is ExceptionInfo with descriptive message"
    (try
      (core/beme->forms "(bare parens)")
      (is false "should have thrown")
      (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
        (is (string? (ex-message e)))
        (is (re-find #"(?i)paren" (ex-message e)))))))

;; ---------------------------------------------------------------------------
;; run-pipeline
;; ---------------------------------------------------------------------------

(deftest run-pipeline-test
  (testing "returns context with all intermediate state"
    (let [ctx (core/run-pipeline "foo(1 2)")]
      (is (string? (:source ctx)))
      (is (vector? (:raw-tokens ctx)))
      (is (vector? (:tokens ctx)))
      (is (vector? (:forms ctx)))
      (is (= "foo(1 2)" (:source ctx)))))
  (testing "forms match beme->forms"
    (let [src "def(x 42)\nprintln(x)"
          ctx (core/run-pipeline src)]
      (is (= (core/beme->forms src) (:forms ctx))))))

(deftest run-pipeline-nil-source-error
  (testing "nil source throws instead of silently returning empty"
    (is (thrown? #?(:clj Exception :cljs :default) (core/run-pipeline nil)))))

(deftest run-pipeline-empty-source
  (testing "empty string produces empty vectors, not nil"
    (let [ctx (core/run-pipeline "")]
      (is (= [] (:forms ctx)))
      (is (= [] (:tokens ctx)))
      (is (= [] (:raw-tokens ctx)))
      (is (vector? (:forms ctx)))
      (is (vector? (:tokens ctx)))
      (is (vector? (:raw-tokens ctx)))))
  (testing "whitespace-only source produces empty vectors"
    (let [ctx (core/run-pipeline "   \n  ")]
      (is (= [] (:forms ctx)))
      (is (= [] (:tokens ctx)))
      (is (= [] (:raw-tokens ctx))))))

(deftest run-pipeline-whitespace-test
  (testing "raw-tokens carry :ws from pipeline"
    (let [ctx (core/run-pipeline "  foo(x)")]
      (is (= "  " (:ws (first (:raw-tokens ctx)))))))
  (testing "whitespace between forms preserved"
    (let [ctx (core/run-pipeline "foo\n\nbar")]
      (is (= "\n\n" (:ws (second (:raw-tokens ctx))))))))

#?(:clj
(deftest run-pipeline-jvm-test
  (testing "raw-tokens are pre-grouper"
    (let [ctx (core/run-pipeline "#?(:clj 1)")]
      (is (> (count (:raw-tokens ctx)) (count (:tokens ctx))))))
  (testing "opts pass through"
    (let [ctx (core/run-pipeline "::foo"
                {:resolve-keyword #(clojure.core/read-string %)})]
      (is (= [:user/foo] (:forms ctx)))))))

;; ---------------------------------------------------------------------------
;; pprint-beme
;; ---------------------------------------------------------------------------

(deftest pprint-beme-test
  (testing "pretty-prints a single form in a vector"
    (is (= "42" (core/pprint-beme [42]))))
  (testing "pretty-prints multiple forms separated by blank lines"
    (is (= "def(x 42)\n\nprintln(x)"
           (core/pprint-beme ['(def x 42) '(println x)]))))
  (testing "works with list input"
    (is (= "def(x 42)\n\nprintln(x)"
           (core/pprint-beme (list '(def x 42) '(println x)))))))

(deftest pprint-beme-width-option
  (testing "respects :width option"
    (let [narrow (core/pprint-beme ['(+ 1 2 3 4 5)] {:width 10})
          wide (core/pprint-beme ['(+ 1 2 3 4 5)] {:width 200})]
      (is (re-find #"begin" narrow))
      (is (not (re-find #"begin" wide))))))
