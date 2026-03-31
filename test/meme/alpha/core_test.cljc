(ns meme.alpha.core-test
  "Tests for the meme.alpha.core public API."
  (:require [clojure.test :refer [deftest is testing]]
            [meme.alpha.core :as core]
            [meme.alpha.forms :as forms]))

;; ---------------------------------------------------------------------------
;; Text-to-form track
;; ---------------------------------------------------------------------------

(deftest meme->forms-test
  (testing "single form"
    (is (= '[(+ 1 2)] (core/meme->forms "+(1 2)"))))
  (testing "multiple forms"
    (is (= '[(def x 42) (println x)]
           (core/meme->forms "def(x 42)\nprintln(x)"))))
  (testing "empty string"
    (is (= [] (core/meme->forms "")))))

(deftest forms->meme-test
  (testing "single form"
    (is (= "+(1 2)" (core/forms->meme ['(+ 1 2)]))))
  (testing "multiple forms separated by blank line"
    (is (= "def(x 42)\n\nprintln(x)"
           (core/forms->meme ['(def x 42) '(println x)])))))

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

(deftest meme->clj-test
  (testing "returns Clojure source string"
    (is (= "(+ 1 2)" (core/meme->clj "+(1 2)"))))
  (testing "multiple forms joined by blank lines"
    (is (= "(def x 42)\n\n(println x)"
           (core/meme->clj "def(x 42)\nprintln(x)")))))

#?(:clj
(deftest clj->meme-test
  (testing "converts Clojure source to meme"
    (is (= "defn(f [x] +(x 1))"
           (core/clj->meme "(defn f [x] (+ x 1))"))))
  (testing "multiple forms"
    (is (= "def(x 42)\n\nprintln(x)"
           (core/clj->meme "(def x 42)\n(println x)"))))))

;; ---------------------------------------------------------------------------
;; Composition identities
;; ---------------------------------------------------------------------------

(deftest composition-identities
  (testing "meme->clj = forms->clj . meme->forms"
    (let [src "def(x 42)\nprintln(x)"]
      (is (= (core/meme->clj src)
             (core/forms->clj (core/meme->forms src))))))
  #?(:clj
  (testing "clj->meme = forms->meme . clj->forms"
    (let [src "(def x 42)\n(println x)"]
      (is (= (core/clj->meme src)
             (core/forms->meme (core/clj->forms src))))))))

;; ---------------------------------------------------------------------------
;; Options
;; ---------------------------------------------------------------------------

#?(:clj
(deftest meme->forms-with-resolve-keyword
  (testing ":resolve-keyword option resolves :: keywords at read time"
    (let [forms (core/meme->forms "::foo"
                  {:resolve-keyword #(clojure.core/read-string %)})]
      (is (= [:user/foo] forms))))))



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
  (testing ":meme.alpha.core/eof as top-level form does not truncate output"
    (is (= [:meme.alpha.core/eof '(def y 2)]
           (core/clj->forms ":meme.alpha.core/eof (def y 2)"))))
  (testing ":meme.alpha.core/eof inside a form is preserved"
    (is (= '[(def x :meme.alpha.core/eof)]
           (core/clj->forms "(def x :meme.alpha.core/eof)"))))))

#?(:clj
(deftest meme->clj-with-opts
  (testing "basic meme->clj conversion"
    (is (= "(+ 1 2)" (core/meme->clj "+(1 2)"))))))

;; ---------------------------------------------------------------------------
;; Error propagation
;; ---------------------------------------------------------------------------

(deftest meme->forms-error-has-location
  (testing "parse error through meme->forms carries :line and :col in ex-data"
    (try
      (core/meme->forms "(bare parens)")
      (is false "should have thrown")
      (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
        (let [data (ex-data e)]
          (is (integer? (:line data)))
          (is (integer? (:col data))))))))

(deftest meme->clj-error-has-location
  (testing "parse error through meme->clj carries :line and :col in ex-data"
    (try
      (core/meme->clj "(bare parens)")
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

(deftest meme->forms-error-is-ex-info
  (testing "parse error is ExceptionInfo with descriptive message"
    (try
      (core/meme->forms "(bare parens)")
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
  (testing "forms match meme->forms"
    (let [src "def(x 42)\nprintln(x)"
          ctx (core/run-pipeline src)]
      (is (= (core/meme->forms src) (:forms ctx))))))

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
  (testing "raw-tokens and tokens are identical"
    (let [ctx (core/run-pipeline "`foo")]
      (is (= (:raw-tokens ctx) (:tokens ctx)))))
  (testing "opts pass through"
    (let [ctx (core/run-pipeline "::foo"
                {:resolve-keyword #(clojure.core/read-string %)})]
      (is (= [:user/foo] (:forms ctx)))))))

;; ---------------------------------------------------------------------------
;; AST node accessors (MemeRaw)
;; ---------------------------------------------------------------------------

(deftest meme-raw-accessors
  (testing "run-pipeline exposes MemeRaw nodes with raw-value and raw-text"
    (let [ctx (core/run-pipeline "42")
          form (first (:forms ctx))]
      (when (forms/raw? form)
        (is (= 42 (forms/raw-value form)))
        (is (= "42" (forms/raw-text form))))))
  (testing "MemeRaw constructed directly"
    (let [r (forms/->MemeRaw 3.14 "3.14")]
      (is (forms/raw? r))
      (is (= 3.14 (forms/raw-value r)))
      (is (= "3.14" (forms/raw-text r))))))

;; ---------------------------------------------------------------------------
;; format-meme (canonical formatter)
;; ---------------------------------------------------------------------------

(deftest format-meme-test
  (testing "formats a single form in a vector"
    (is (= "42" (core/format-meme [42]))))
  (testing "formats multiple forms separated by blank lines"
    (is (= "def(x 42)\n\nprintln(x)"
           (core/format-meme ['(def x 42) '(println x)]))))
  (testing "works with list input"
    (is (= "def(x 42)\n\nprintln(x)"
           (core/format-meme (list '(def x 42) '(println x)))))))

(deftest format-meme-width-option
  (testing "respects :width option"
    (let [narrow (core/format-meme ['(+ 1 2 3 4 5)] {:width 10})
          wide (core/format-meme ['(+ 1 2 3 4 5)] {:width 200})]
      (is (re-find #"\n" narrow))
      (is (not (re-find #"\n" wide))))))

(deftest format-forms-rejects-string-input
  (testing "forms->meme rejects a string"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                 (core/forms->meme "hello"))))
  (testing "format-meme rejects a string"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                 (core/format-meme "hello")))))
