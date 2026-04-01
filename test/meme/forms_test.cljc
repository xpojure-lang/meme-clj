(ns meme.forms-test
  "Tests for shared form-level predicates, constructors, and constants."
  (:require [clojure.test :refer [deftest is testing]]
            [meme.forms :as forms]))

;; ============================================================
;; MemeAutoKeyword
;; ============================================================

(deftest deferred-auto-keyword-roundtrip
  (let [ak (forms/deferred-auto-keyword "::foo")]
    (is (forms/deferred-auto-keyword? ak))
    (is (= "::foo" (forms/deferred-auto-keyword-raw ak)))
    (is (= '(clojure.core/read-string "::foo")
           (forms/deferred-auto-keyword->form ak)))))

(deftest deferred-auto-keyword-predicate
  (is (not (forms/deferred-auto-keyword? :foo)))
  (is (not (forms/deferred-auto-keyword? "::foo")))
  (is (not (forms/deferred-auto-keyword? nil))))

;; ============================================================
;; MemeRaw
;; ============================================================

(deftest raw-wrapper
  (let [r (forms/->MemeRaw 42 "0x2A")]
    (is (forms/raw? r))
    (is (= 42 (:value r)))
    (is (= "0x2A" (:raw r)))))

(deftest raw-predicate
  (is (not (forms/raw? 42)))
  (is (not (forms/raw? nil)))
  (is (not (forms/raw? "hello"))))

;; ============================================================
;; Syntax-quote AST nodes
;; ============================================================

(deftest syntax-quote-nodes
  (testing "MemeSyntaxQuote"
    (let [sq (forms/->MemeSyntaxQuote '(+ 1 2))]
      (is (forms/syntax-quote? sq))
      (is (not (forms/unquote? sq)))
      (is (= '(+ 1 2) (:form sq)))))
  (testing "MemeUnquote"
    (let [uq (forms/->MemeUnquote 'x)]
      (is (forms/unquote? uq))
      (is (not (forms/syntax-quote? uq)))
      (is (= 'x (:form uq)))))
  (testing "MemeUnquoteSplicing"
    (let [uqs (forms/->MemeUnquoteSplicing 'xs)]
      (is (forms/unquote-splicing? uqs))
      (is (not (forms/unquote? uqs)))
      (is (= 'xs (:form uqs))))))

;; ============================================================
;; Reader conditionals (portable)
;; ============================================================

(deftest reader-conditional-portable
  (let [rc (forms/make-reader-conditional '(:clj 1 :cljs 2) false)]
    (is (forms/meme-reader-conditional? rc))
    (is (= '(:clj 1 :cljs 2) (forms/rc-form rc)))
    (is (not (forms/rc-splicing? rc)))))

(deftest reader-conditional-splicing
  (let [rc (forms/make-reader-conditional '(:clj [1] :cljs [2]) true)]
    (is (forms/meme-reader-conditional? rc))
    (is (forms/rc-splicing? rc))))

(deftest reader-conditional-predicate
  (is (not (forms/meme-reader-conditional? nil)))
  (is (not (forms/meme-reader-conditional? '(:clj 1)))))

;; ============================================================
;; strip-internal-meta
;; ============================================================

(deftest strip-internal-meta-removes-pipeline-keys
  (let [m {:line 1 :col 2 :ws " " :meme/sugar true :meme/order [1] :meme/ns "foo"
           :meme/meta-chain [] :user-key "val" :tag 'String}]
    (is (= {:user-key "val" :tag 'String} (forms/strip-internal-meta m)))))

(deftest strip-internal-meta-preserves-empty
  (is (= {} (forms/strip-internal-meta {:line 1 :col 2}))))

;; ============================================================
;; percent-param-type
;; ============================================================

(deftest percent-param-type-classification
  (is (= :bare (forms/percent-param-type '%)))
  (is (= :rest (forms/percent-param-type '%&)))
  (is (= 1 (forms/percent-param-type '%1)))
  (is (= 2 (forms/percent-param-type '%2)))
  (is (= 10 (forms/percent-param-type '%10)))
  (is (nil? (forms/percent-param-type 'x)))
  (is (nil? (forms/percent-param-type '%abc)))
  (is (nil? (forms/percent-param-type 42))))

;; ============================================================
;; build-anon-fn-params
;; ============================================================

(deftest build-anon-fn-params-bare
  (is (= ['%1] (forms/build-anon-fn-params #{:bare}))))

(deftest build-anon-fn-params-numbered
  (is (= ['%1 '%2] (forms/build-anon-fn-params #{1 2}))))

(deftest build-anon-fn-params-rest
  (is (= ['%1 '& '%&] (forms/build-anon-fn-params #{1 :rest}))))

(deftest build-anon-fn-params-empty
  (is (= [] (forms/build-anon-fn-params #{}))))

;; ============================================================
;; notation-meta-keys
;; ============================================================

(deftest notation-meta-keys-subset-of-internal
  (testing "notation keys are a subset of internal keys"
    (is (every? forms/internal-meta-keys forms/notation-meta-keys))))
