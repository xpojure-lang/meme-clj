(ns meme.core-test
  "Tests for the meme.core public API."
  (:require [clojure.test :refer [deftest is testing]]
            [meme.core :as core]
            [meme.forms :as forms]
            [meme.stages :as stages]))

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
     (testing ":meme.core/eof as top-level form does not truncate output"
       (is (= [:meme.core/eof '(def y 2)]
              (core/clj->forms ":meme.core/eof (def y 2)"))))
     (testing ":meme.core/eof inside a form is preserved"
       (is (= '[(def x :meme.core/eof)]
              (core/clj->forms "(def x :meme.core/eof)"))))))

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

(deftest run-stages-error-has-location
  (testing "parse error through run-stages carries :line and :col in ex-data"
    (try
      (stages/run "(bare parens)")
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
;; run-stages
;; ---------------------------------------------------------------------------

(deftest run-stages-test
  (testing "returns context with all intermediate state"
    (let [ctx (stages/run "foo(1 2)")]
      (is (string? (:source ctx)))
      (is (vector? (:raw-tokens ctx)))
      (is (vector? (:tokens ctx)))
      (is (vector? (:forms ctx)))
      (is (= "foo(1 2)" (:source ctx)))))
  (testing "forms match meme->forms"
    (let [src "def(x 42)\nprintln(x)"
          ctx (stages/run src)]
      (is (= (core/meme->forms src) (:forms ctx))))))

(deftest run-stages-nil-source-error
  (testing "nil source throws instead of silently returning empty"
    (is (thrown? #?(:clj Exception :cljs :default) (stages/run nil)))))

(deftest run-stages-empty-source
  (testing "empty string produces empty vectors, not nil"
    (let [ctx (stages/run "")]
      (is (= [] (:forms ctx)))
      (is (= [] (:tokens ctx)))
      (is (= [] (:raw-tokens ctx)))
      (is (vector? (:forms ctx)))
      (is (vector? (:tokens ctx)))
      (is (vector? (:raw-tokens ctx)))))
  (testing "whitespace-only source produces empty vectors"
    (let [ctx (stages/run "   \n  ")]
      (is (= [] (:forms ctx)))
      (is (= [] (:tokens ctx)))
      (is (= [] (:raw-tokens ctx))))))

(deftest run-stages-whitespace-test
  (testing "raw-tokens carry :ws from pipeline"
    (let [ctx (stages/run "  foo(x)")]
      (is (= "  " (:ws (first (:raw-tokens ctx)))))))
  (testing "whitespace between forms preserved"
    (let [ctx (stages/run "foo\n\nbar")]
      (is (= "\n\n" (:ws (second (:raw-tokens ctx))))))))

#?(:clj
   (deftest run-stages-jvm-test
     (testing "raw-tokens and tokens are identical"
       (let [ctx (stages/run "`foo")]
         (is (= (:raw-tokens ctx) (:tokens ctx)))))
     (testing "opts pass through"
       (let [ctx (stages/run "::foo"
                                    {:resolve-keyword #(clojure.core/read-string %)})]
         (is (= [:user/foo] (:forms ctx)))))))

;; ---------------------------------------------------------------------------
;; AST node accessors (MemeRaw)
;; ---------------------------------------------------------------------------

(deftest meme-raw-accessors
  (testing "stages/run exposes MemeRaw nodes with :value and :raw fields"
    (let [ctx (stages/run "42")
          form (first (:forms ctx))]
      (when (forms/raw? form)
        (is (= 42 (:value form)))
        (is (= "42" (:raw form))))))
  (testing "MemeRaw constructed directly"
    (let [r (forms/->MemeRaw 3.14 "3.14")]
      (is (forms/raw? r))
      (is (= 3.14 (:value r)))
      (is (= "3.14" (:raw r))))))

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

;; ---------------------------------------------------------------------------
;; :clj mode coverage for complex forms
;; ---------------------------------------------------------------------------

(deftest meme->clj-sugar-forms
  (testing "quote sugar"
    (is (= "'foo" (core/meme->clj "'foo"))))
  (testing "deref sugar"
    (is (= "@foo" (core/meme->clj "@foo"))))
  (testing "var sugar"
    (is (= "#'foo" (core/meme->clj "#'foo"))))
  (testing "anon-fn sugar"
    (is (= "#(inc %)" (core/meme->clj "#(inc(%))")))))

(deftest meme->clj-nested-forms
  (testing "nested let + arithmetic"
    (is (= "(defn f [x] (let [a (+ x 1)] (* a 2)))"
           (core/meme->clj "defn(f [x] let([a +(x 1)] *(a 2)))"))))
  (testing "threading macro"
    (is (= "(-> x inc str)" (core/meme->clj "->(x inc str)"))))
  (testing "try/catch"
    (is (= "(try (do x) (catch Exception e (handle e)))"
           (core/meme->clj "try(do(x) catch(Exception e handle(e)))")))))

(deftest meme->clj-data-literals
  (testing "vector"
    (is (= "[1 2 3]" (core/meme->clj "[1 2 3]"))))
  (testing "map"
    (is (= "{:a 1 :b 2}" (core/meme->clj "{:a 1 :b 2}"))))
  (testing "set"
    (is (= "#{1 2 3}" (core/meme->clj "#{1 2 3}"))))
  #?(:clj
     (testing "auto-resolve keyword"
       (is (= "::foo" (core/meme->clj "::foo"))))))

(deftest meme->clj-metadata
  (testing "keyword metadata"
    (is (= "^:private (def x 1)" (core/meme->clj "^:private def(x 1)"))))
  (testing "type metadata"
    (is (= "^String x" (core/meme->clj "^String x")))))

#?(:clj
   (deftest meme->clj-reader-conditionals
     (testing "reader conditional preserves through meme->clj"
       (let [result (core/meme->clj "#?(:clj 1 :cljs 2)" {:read-cond :preserve})]
         (is (= "#?(:clj 1 :cljs 2)" result))))))

#?(:clj
   (deftest meme->clj-namespaced-map
     (testing "namespaced map"
       (is (= "#:user{:a 1 :b 2}" (core/meme->clj "#:user{:a 1 :b 2}"))))))

(deftest format-forms-rejects-string-input
  (testing "forms->meme rejects a string"
    (is (thrown? #?(:clj AssertionError :cljs js/Error)
                 (core/forms->meme "hello"))))
  (testing "format-meme rejects a string"
    (is (thrown? #?(:clj AssertionError :cljs js/Error)
                 (core/format-meme "hello")))))
