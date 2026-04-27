(ns meme-lang.stages-test
  "Tests for the experimental pipeline stages, including
   step-expand-syntax-quotes and expand-forms."
  (:require [clojure.test :refer [deftest is testing]]
            [meme-lang.stages :as stages]
            [meme-lang.forms :as forms]))

;; ---------------------------------------------------------------------------
;; Full pipeline: scan → trivia → parse → read
;; ---------------------------------------------------------------------------

(deftest full-pipeline-basic
  (testing "simple call"
    (let [ctx (stages/run "+(1 2)")]
      (is (= '[(+ 1 2)] (:forms ctx)))))
  (testing "empty string"
    (let [ctx (stages/run "")]
      (is (= [] (:forms ctx)))))
  (testing "multiple forms"
    (let [ctx (stages/run "def(x 42)\nx")]
      (is (= '[(def x 42) x] (:forms ctx))))))

(deftest full-pipeline-context-keys
  (testing "context contains expected keys"
    (let [ctx (stages/run "+(1 2)")]
      (is (string? (:source ctx)))
      (is (vector? (:cst ctx)))
      (is (vector? (:forms ctx))))))

;; ---------------------------------------------------------------------------
;; step-expand-syntax-quotes
;; ---------------------------------------------------------------------------

(deftest step-expand-syntax-quotes-basic
  (testing "expands syntax-quote AST nodes"
    (let [ctx (-> (stages/run "`foo")
                  stages/step-expand-syntax-quotes)
          form (first (:forms ctx))]
      ;; `foo expands to (quote foo) — no longer a MemeSyntaxQuote record
      (is (seq? form))
      (is (= 'quote (first form)))
      (is (not (forms/syntax-quote? form)))))
  #?(:clj
     (testing "unwraps MemeRaw values"
       (let [ctx (-> (stages/run "0xFF")
                     stages/step-expand-syntax-quotes)]
         (is (= 255 (first (:forms ctx))))
         (is (not (forms/raw? (first (:forms ctx))))))))
  (testing "passes through plain forms unchanged"
    (let [ctx (-> (stages/run "+(1 2)")
                  stages/step-expand-syntax-quotes)]
      (is (= '[(+ 1 2)] (:forms ctx))))))

;; ---------------------------------------------------------------------------
;; expand-forms convenience
;; ---------------------------------------------------------------------------

(deftest expand-forms-test
  #?(:clj
     (testing "expands a vector of forms"
       (let [forms (:forms (stages/run "0xFF"))
             expanded (stages/expand-syntax-quotes forms {})]
         (is (= [255] expanded)))))
  (testing "empty forms"
    (is (= [] (stages/expand-syntax-quotes [] {})))))

;; ---------------------------------------------------------------------------
;; Pipeline with expansion — full eval path
;; ---------------------------------------------------------------------------

(deftest pipeline-with-expansion
  (testing "full pipeline + expansion produces eval-ready forms"
    (let [ctx (-> (stages/run "`map")
                  stages/step-expand-syntax-quotes)
          form (first (:forms ctx))]
      ;; After expansion, `map becomes (quote map) — plain Clojure, no AST records
      (is (seq? form))
      (is (= 'quote (first form)))
      (is (not (forms/syntax-quote? form))))))

;; ---------------------------------------------------------------------------
;; Incomplete error detection
;; ---------------------------------------------------------------------------

(deftest incomplete-errors
  (testing "unclosed paren produces :incomplete error"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                 (stages/run "+(1 2")))
    (try (stages/run "+(1 2")
         (catch #?(:clj Exception :cljs :default) e
           (is (:incomplete (ex-data e))))))
  (testing "unclosed bracket produces :incomplete error"
    (try (stages/run "[1 2 3")
         (catch #?(:clj Exception :cljs :default) e
           (is (:incomplete (ex-data e))))))
  (testing "unclosed brace produces :incomplete error"
    (try (stages/run "{:a 1")
         (catch #?(:clj Exception :cljs :default) e
           (is (:incomplete (ex-data e))))))
  (testing "valid input does not produce :incomplete"
    (is (vector? (:forms (stages/run "+(1 2)"))))))

;; ---------------------------------------------------------------------------
;; Pipeline contract — miscomposed stages throw clear errors
;; ---------------------------------------------------------------------------

(deftest stage-contracts-are-exposed
  (testing "stage-contracts is public data, one entry per stage"
    (is (= #{:step-parse :step-read :step-evaluate-reader-conditionals :step-expand-syntax-quotes}
           (set (keys stages/stage-contracts))))
    (is (= #{:source} (get-in stages/stage-contracts [:step-parse :requires])))
    (is (= #{:cst}    (get-in stages/stage-contracts [:step-read :requires])))
    (is (= #{:forms}  (get-in stages/stage-contracts [:step-evaluate-reader-conditionals :requires])))
    (is (= #{:forms}  (get-in stages/stage-contracts [:step-expand-syntax-quotes :requires])))))

(deftest step-read-without-parse-throws-pipeline-error
  (testing "calling step-read without :cst in ctx fails with pipeline-error"
    (try (stages/step-read {:source "+(1 2)"})
         (is false "step-read should have thrown")
         (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
           (let [data (ex-data e)]
             (is (= :meme-lang/pipeline-error (:type data)))
             (is (= :step-read (:stage data)))
             (is (contains? (set (:missing data)) :cst))
             (is (re-find #"missing required ctx key" (ex-message e))))))))

(deftest step-expand-without-read-throws-pipeline-error
  (testing "calling step-expand-syntax-quotes without :forms fails with pipeline-error"
    (try (stages/step-expand-syntax-quotes {:source "x" :cst []})
         (is false "step-expand should have thrown")
         (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
           (let [data (ex-data e)]
             (is (= :meme-lang/pipeline-error (:type data)))
             (is (= :step-expand-syntax-quotes (:stage data)))
             (is (contains? (set (:missing data)) :forms)))))))

(deftest step-parse-without-source-throws-pipeline-error
  (testing "calling step-parse without :source fails with pipeline-error"
    (try (stages/step-parse {})
         (is false "step-parse should have thrown")
         (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
           (let [data (ex-data e)]
             (is (= :meme-lang/pipeline-error (:type data)))
             (is (= :step-parse (:stage data)))
             (is (contains? (set (:missing data)) :source)))))))

(deftest step-parse-with-non-string-source-still-type-checks
  (testing "type check on :source value runs after presence check"
    (try (stages/step-parse {:source 42})
         (is false "step-parse should have thrown")
         (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
           (let [data (ex-data e)]
             (is (= :meme-lang/pipeline-error (:type data)))
             (is (= :step-parse (:stage data)))
             (is (re-find #"must be a string" (ex-message e))))))))

;; ---------------------------------------------------------------------------
;; step-evaluate-reader-conditionals
;; ---------------------------------------------------------------------------

(defn- eval-rc
  "Helper: read src, run eval-rc step, return the resulting :forms vector.
   Opts may set :platform to override the default compile-time platform."
  ([src] (eval-rc src nil))
  ([src opts]
   (:forms (stages/step-evaluate-reader-conditionals
             (assoc (stages/run src) :opts opts)))))

(deftest eval-rc-basic
  (testing "#? with matching platform returns the branch value"
    (is (= [1] (eval-rc "#?(:clj 1 :cljs 2)" {:platform :clj})))
    (is (= [2] (eval-rc "#?(:clj 1 :cljs 2)" {:platform :cljs}))))
  (testing "#? with no matching platform removes the form"
    (is (= [] (eval-rc "#?(:cljs 1)" {:platform :clj}))))
  (testing "#? with :default fallback"
    (is (= [99] (eval-rc "#?(:cljs 1 :default 99)" {:platform :clj}))))
  (testing "named platform wins over :default"
    (is (= [1] (eval-rc "#?(:clj 1 :default 99)" {:platform :clj})))))

(deftest eval-rc-recurses-into-collections
  (testing "#? nested inside a list"
    (is (= '[(f 1)] (eval-rc "f(#?(:clj 1 :cljs 2))" {:platform :clj}))))
  (testing "#? nested inside a vector"
    (is (= [[:a 1 :b]] (eval-rc "[:a #?(:clj 1 :cljs 2) :b]" {:platform :clj}))))
  (testing "#? as a map value"
    (is (= [{:a 1}] (eval-rc "{:a #?(:clj 1 :cljs 2)}" {:platform :clj}))))
  (testing "#? as a map key"
    (is (= [{:a 1}] (eval-rc "{#?(:clj :a :cljs :b) 1}" {:platform :clj}))))
  (testing "#? inside a set"
    (is (= [#{:a 1}] (eval-rc "#{:a #?(:clj 1 :cljs 2)}" {:platform :clj})))))

(deftest eval-rc-splicing
  (testing "#?@ inside a vector splices items in"
    (is (= [[:a 1 2 :b]] (eval-rc "[:a #?@(:clj [1 2] :cljs [3]) :b]" {:platform :clj}))))
  (testing "#?@ inside a list splices"
    (is (= '[(f x y)] (eval-rc "f(#?@(:clj [x y] :cljs [z]))" {:platform :clj}))))
  (testing "#?@ inside a set splices"
    (is (= [#{:a 1 2}] (eval-rc "#{:a #?@(:clj [1 2] :cljs [3])}" {:platform :clj}))))
  (testing "#?@ at top level splices into :forms"
    (is (= [1 2] (eval-rc "#?@(:clj [1 2])" {:platform :clj}))))
  (testing "#?@ with no match contributes zero forms"
    (is (= [[:a :b]] (eval-rc "[:a #?@(:cljs [1 2]) :b]" {:platform :clj})))))

(deftest eval-rc-errors
  (testing "odd-count branch list throws"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                 (eval-rc "#?(:clj)"))))
  (testing "#?@ matched value must be sequential"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                          #"non-sequential"
                          (eval-rc "[:a #?@(:clj 42) :b]" {:platform :clj})))))

(deftest eval-rc-platform-opt
  (testing ":platform opt overrides the compile-time default"
    (is (= [:cljs-thing]
           (eval-rc "#?(:clj :jvm-thing :cljs :cljs-thing)" {:platform :cljs})))))

(deftest eval-rc-passthrough-for-non-rc
  (testing "plain atoms pass through unchanged"
    (is (= '[x] (eval-rc "x")))
    (is (= [42] (eval-rc "42")))
    (is (= [:k] (eval-rc ":k")))
    (is (= ["s"] (eval-rc "\"s\""))))
  (testing "plain collections with no RC pass through"
    (is (= '[(f x y)] (eval-rc "f(x y)")))
    (is (= [[:a :b]] (eval-rc "[:a :b]")))
    (is (= [{:a 1 :b 2}] (eval-rc "{:a 1 :b 2}")))))

(deftest eval-rc-deeply-nested
  (testing "nested #? evaluates inside-out"
    (is (= [[1]] (eval-rc "[#?(:clj #?(:clj 1 :cljs 2) :cljs 3)]" {:platform :clj}))))
  (testing "#? deep inside nested collections"
    (is (= '[(f [:a {:k 1}])]
           (eval-rc "f([:a {:k #?(:clj 1 :cljs 2)}])" {:platform :clj})))))

(deftest eval-rc-inside-syntax-quote
  (testing "#? inside ` is evaluated (matches native Clojure read-time semantics)"
    (let [[form] (eval-rc "`#?(:clj x :cljs y)" {:platform :clj})]
      ;; After eval-rc, the SQ wraps just `x (the MemeReaderConditional is gone)
      (is (forms/syntax-quote? form))
      (is (= 'x (:form form)))))
  (testing "#? inside ` with no match drops the whole syntax-quote form"
    (is (= [] (eval-rc "`#?(:cljs y)" {:platform :clj}))))
  (testing "#? inside ~ (unquote)"
    ;; `f(~#?(:clj a :cljs b)) — syntax-quote around a call whose single arg is ~#?
    (let [[outer] (eval-rc "`f(~#?(:clj a :cljs b))" {:platform :clj})
          call (:form outer)
          arg  (second call)]
      (is (forms/syntax-quote? outer))
      (is (= 'f (first call)))
      (is (forms/unquote? arg))
      (is (= 'a (:form arg))))))

(deftest eval-rc-contract-check
  (testing "missing :forms fails with pipeline-error"
    (try (stages/step-evaluate-reader-conditionals {:source "" :cst []})
         (is false "should have thrown")
         (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
           (let [data (ex-data e)]
             (is (= :meme-lang/pipeline-error (:type data)))
             (is (= :step-evaluate-reader-conditionals (:stage data)))
             (is (contains? (set (:missing data)) :forms)))))))

(deftest eval-rc-is-idempotent-on-plain-forms
  (testing "running the step twice is a no-op (idempotent on already-evaluated forms)"
    (let [after-first  (stages/step-evaluate-reader-conditionals (stages/run "#?(:clj 1 :cljs 2)"))
          after-second (stages/step-evaluate-reader-conditionals after-first)]
      (is (= (:forms after-first) (:forms after-second))))))

;; ---------------------------------------------------------------------------
;; Pipeline composition — tooling vs. eval paths
;; ---------------------------------------------------------------------------

(deftest tooling-path-preserves-reader-conditionals
  (testing "stages/run keeps reader conditionals as records"
    (let [forms (:forms (stages/run "#?(:clj 1 :cljs 2)"))]
      (is (forms/meme-reader-conditional? (first forms))))))

;; ---------------------------------------------------------------------------
;; Shebang stripping
;; ---------------------------------------------------------------------------

(deftest shebang-stripping
  (testing "shebang line is stripped"
    (let [ctx (stages/run "#!/usr/bin/env bb\n+(1 2)")]
      (is (= '[(+ 1 2)] (:forms ctx)))))
  (testing "shebang with \\r\\n line ending"
    (let [ctx (stages/run "#!/usr/bin/env bb\r\n+(1 2)")]
      (is (= '[(+ 1 2)] (:forms ctx)))))
  (testing "shebang with bare \\r line ending"
    (let [ctx (stages/run "#!/usr/bin/env bb\r+(1 2)")]
      (is (= '[(+ 1 2)] (:forms ctx)))))
  (testing "shebang-only file with no newline"
    (let [ctx (stages/run "#!/usr/bin/env bb")]
      (is (= [] (:forms ctx)))))
  (testing "no shebang — normal parsing"
    (let [ctx (stages/run "+(1 2)")]
      (is (= '[(+ 1 2)] (:forms ctx))))))
