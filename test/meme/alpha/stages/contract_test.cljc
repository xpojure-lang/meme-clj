(ns meme.alpha.stages.contract-test
  "Tests for the stage contract: specs, runtime validation, and explain."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.spec.alpha :as s]
            [meme.alpha.stages :as stages]
            [meme.alpha.stages.contract :as contract]))

;; ---------------------------------------------------------------------------
;; Token spec
;; ---------------------------------------------------------------------------

(deftest token-spec-accepts-valid
  (testing "minimal token"
    (is (s/valid? ::contract/token
                  {:type :symbol :value "foo" :line 1 :col 1 :offset 0})))
  (testing "token with all optional fields"
    (is (s/valid? ::contract/token
                  {:type :string :value "\"hello\"" :line 1 :col 1 :offset 0
                   :end-line 1 :end-col 8 :end-offset 7 :ws "  "}))))

(deftest token-spec-rejects-invalid
  (testing "missing :type"
    (is (not (s/valid? ::contract/token
                       {:value "foo" :line 1 :col 1 :offset 0}))))
  (testing "unknown :type"
    (is (not (s/valid? ::contract/token
                       {:type :bogus :value "foo" :line 1 :col 1 :offset 0}))))
  (testing "non-string :value"
    (is (not (s/valid? ::contract/token
                       {:type :symbol :value 42 :line 1 :col 1 :offset 0}))))
  (testing ":line must be pos-int (not zero)"
    (is (not (s/valid? ::contract/token
                       {:type :symbol :value "x" :line 0 :col 1 :offset 0}))))
  (testing ":offset must be nat-int (not negative)"
    (is (not (s/valid? ::contract/token
                       {:type :symbol :value "x" :line 1 :col 1 :offset -1})))))

;; ---------------------------------------------------------------------------
;; Opts spec
;; ---------------------------------------------------------------------------

(deftest opts-spec
  (testing "nil opts"
    (is (s/valid? ::contract/opts nil)))
  (testing "empty opts"
    (is (s/valid? ::contract/opts {})))
  (testing "valid :resolve-keyword"
    (is (s/valid? ::contract/opts {:resolve-keyword identity})))
  (testing "valid :read-cond"
    (is (s/valid? ::contract/opts {:read-cond :preserve})))
  (testing "invalid :read-cond value"
    (is (not (s/valid? ::contract/opts {:read-cond :evaluate})))))

;; ---------------------------------------------------------------------------
;; Opts specs — prelude is a vector of forms, not a string
;; Bug: :meme.opts/prelude was specced as string? but runtime passes a vector
;; of parsed forms. Dormant because *validate* defaults to false.
;; ---------------------------------------------------------------------------

(deftest prelude-spec-accepts-forms
  (testing "prelude as vector of forms is valid"
    (is (s/valid? :meme.opts/prelude ['(def x 1) '(def y 2)])))
  (testing "prelude as empty vector is valid"
    (is (s/valid? :meme.opts/prelude [])))
  (testing "prelude as string is not valid"
    (is (not (s/valid? :meme.opts/prelude "some code")))))

;; ---------------------------------------------------------------------------
;; Context map specs
;; ---------------------------------------------------------------------------

(deftest ctx-input-spec
  (testing "valid input"
    (is (s/valid? ::contract/ctx-input {:source "foo()"}))
    (is (s/valid? ::contract/ctx-input {:source "" :opts nil}))
    (is (s/valid? ::contract/ctx-input {:source "x" :opts {:read-cond :preserve}})))
  (testing "missing :source"
    (is (not (s/valid? ::contract/ctx-input {}))))
  (testing "non-string :source"
    (is (not (s/valid? ::contract/ctx-input {:source 42})))))

;; ---------------------------------------------------------------------------
;; Specs match actual stage output
;; ---------------------------------------------------------------------------

(deftest actual-scan-output-conforms
  (testing "scan output passes ::ctx-after-scan"
    (let [ctx (stages/step-scan {:source "foo(1 2)"})]
      (is (s/valid? ::contract/ctx-after-scan ctx)
          (s/explain-str ::contract/ctx-after-scan ctx)))))

(deftest actual-scan-output-has-tokens
  (testing "scan output includes both :raw-tokens and :tokens"
    (let [ctx (stages/step-scan {:source "foo(1 2)"})]
      (is (= (:raw-tokens ctx) (:tokens ctx))))))

(deftest actual-parse-output-conforms
  (testing "parse output passes ::ctx-after-parse"
    (let [ctx (stages/run "foo(1 2)")]
      (is (s/valid? ::contract/ctx-after-parse ctx)
          (s/explain-str ::contract/ctx-after-parse ctx)))))

(deftest actual-stages-empty-source
  (testing "empty source passes all stage specs"
    (let [ctx (stages/run "")]
      (is (s/valid? ::contract/ctx-after-parse ctx)))))

(deftest actual-stages-complex-source
  (testing "complex source with multiple forms"
    (let [ctx (stages/run "def(x 42)\nprintln(x)")]
      (is (s/valid? ::contract/ctx-after-parse ctx)))))

;; ---------------------------------------------------------------------------
;; *validate* toggle
;; ---------------------------------------------------------------------------

(deftest validate-off-by-default
  (testing "validate! is no-op when *validate* is false"
    (is (nil? (contract/validate! :scan :input {:source 42})))))

(deftest validate-on-catches-errors
  (testing "bad :source type caught when *validate* is true"
    (binding [contract/*validate* true]
      (is (thrown-with-msg?
           #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
           #"Pipeline contract violation"
           (contract/validate! :scan :input {:source 42})))))
  (testing "missing :source caught"
    (binding [contract/*validate* true]
      (is (thrown-with-msg?
           #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
           #"Pipeline contract violation"
           (contract/validate! :scan :input {}))))))

(deftest validate-ex-data-has-stage-and-phase
  (testing "ex-data includes :stage, :phase, :problems"
    (binding [contract/*validate* true]
      (try
        (contract/validate! :scan :input {:source 42})
        (is false "should have thrown")
        (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
          (let [data (ex-data e)]
            (is (= :scan (:stage data)))
            (is (= :input (:phase data)))
            (is (seq (:problems data)))))))))

(deftest validate-in-full-stages
  (testing "valid input passes with *validate* true"
    (binding [contract/*validate* true]
      (let [ctx (stages/run "+(1 2)")]
        (is (= '[(+ 1 2)] (:forms ctx)))))))

;; ---------------------------------------------------------------------------
;; explain-context
;; ---------------------------------------------------------------------------

(deftest explain-valid-returns-nil
  (is (nil? (contract/explain-context :scan :input {:source "hello"}))))

(deftest explain-invalid-returns-string
  (let [explanation (contract/explain-context :scan :input {:source 42})]
    (is (string? explanation))
    (is (pos? (count explanation)))))

;; ---------------------------------------------------------------------------
;; valid?
;; ---------------------------------------------------------------------------

(deftest valid-predicate
  (is (true? (contract/valid? :scan :input {:source "x"})))
  (is (false? (contract/valid? :scan :input {:source 42})))
  (is (true? (contract/valid? :scan :input {:source "" :opts nil}))))
