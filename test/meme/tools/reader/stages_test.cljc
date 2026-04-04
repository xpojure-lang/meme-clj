(ns meme.tools.reader.stages-test
  "Tests for the experimental pipeline stages, including
   step-expand-syntax-quotes and expand-forms."
  (:require [clojure.test :refer [deftest is testing]]
            [meme.tools.reader.stages :as stages]
            [meme.tools.forms :as forms]))

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
      (is (vector? (:raw-tokens ctx)))
      (is (vector? (:tokens ctx)))
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
;; Shebang stripping
;; ---------------------------------------------------------------------------

(deftest shebang-stripping
  (testing "shebang line is stripped"
    (let [ctx (stages/run "#!/usr/bin/env bb\n+(1 2)")]
      (is (= '[(+ 1 2)] (:forms ctx)))))
  (testing "no shebang — normal parsing"
    (let [ctx (stages/run "+(1 2)")]
      (is (= '[(+ 1 2)] (:forms ctx))))))
