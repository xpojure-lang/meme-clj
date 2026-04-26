(ns meme.tools.clj.forms-test
  "Tests for shared form-level predicates, constructors, and constants."
  (:require [clojure.test :refer [deftest is testing]]
            [meme.tools.clj.forms :as forms]))

;; ============================================================
;; CljAutoKeyword
;; ============================================================

(deftest deferred-auto-keyword-roundtrip
  (let [ak (forms/deferred-auto-keyword "::foo")]
    (is (forms/deferred-auto-keyword? ak))
    (is (= "::foo" (forms/deferred-auto-keyword-raw ak)))
    ;; C65: platform-appropriate read-string
    (is (= (list #?(:clj 'clojure.core/read-string
                    :cljs 'cljs.reader/read-string) "::foo")
           (forms/deferred-auto-keyword->form ak)))))

(deftest deferred-auto-keyword-predicate
  (is (not (forms/deferred-auto-keyword? :foo)))
  (is (not (forms/deferred-auto-keyword? "::foo")))
  (is (not (forms/deferred-auto-keyword? nil))))

;; ============================================================
;; CljRaw
;; ============================================================

(deftest raw-wrapper
  (let [r (forms/->CljRaw 42 "0x2A")]
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
  (testing "CljSyntaxQuote"
    (let [sq (forms/->CljSyntaxQuote '(+ 1 2))]
      (is (forms/syntax-quote? sq))
      (is (not (forms/unquote? sq)))
      (is (= '(+ 1 2) (:form sq)))))
  (testing "CljUnquote"
    (let [uq (forms/->CljUnquote 'x)]
      (is (forms/unquote? uq))
      (is (not (forms/syntax-quote? uq)))
      (is (= 'x (:form uq)))))
  (testing "CljUnquoteSplicing"
    (let [uqs (forms/->CljUnquoteSplicing 'xs)]
      (is (forms/unquote-splicing? uqs))
      (is (not (forms/unquote? uqs)))
      (is (= 'xs (:form uqs))))))

;; ============================================================
;; Reader conditionals (portable)
;; ============================================================

(deftest reader-conditional-portable
  (let [rc (forms/make-reader-conditional '(:clj 1 :cljs 2) false)]
    (is (forms/clj-reader-conditional? rc))
    (is (= '(:clj 1 :cljs 2) (forms/rc-form rc)))
    (is (not (forms/rc-splicing? rc)))))

(deftest reader-conditional-splicing
  (let [rc (forms/make-reader-conditional '(:clj [1] :cljs [2]) true)]
    (is (forms/clj-reader-conditional? rc))
    (is (forms/rc-splicing? rc))))

(deftest reader-conditional-predicate
  (is (not (forms/clj-reader-conditional? nil)))
  (is (not (forms/clj-reader-conditional? '(:clj 1)))))

;; ============================================================
;; strip-internal-meta
;; ============================================================

(deftest strip-internal-meta-removes-pipeline-keys
  (let [m {:line 1 :col 2 :m1clj/leading-trivia " " :m1clj/sugar true :m1clj/insertion-order [1] :m1clj/namespace-prefix "foo"
           :m1clj/meta-chain [] :user-key "val" :tag 'String}]
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

;; ============================================================
;; find-percent-params
;; ============================================================

(deftest find-percent-params-bare
  (is (= #{:bare} (forms/find-percent-params '%))))

(deftest find-percent-params-numbered
  (is (= #{1 2} (forms/find-percent-params '(+ %1 %2)))))

(deftest find-percent-params-rest
  (is (= #{:rest} (forms/find-percent-params '%&))))

(deftest find-percent-params-mixed
  (is (= #{:bare :rest} (forms/find-percent-params '(apply str % %&)))))

(deftest find-percent-params-in-vector
  (is (= #{1 2} (forms/find-percent-params '[%1 %2]))))

(deftest find-percent-params-in-map
  (is (= #{1 2} (forms/find-percent-params '{%1 %2}))))

(deftest find-percent-params-in-set
  (is (= #{1} (forms/find-percent-params '#{%1}))))

(deftest find-percent-params-nested-containers
  (is (= #{1 2 3} (forms/find-percent-params '[[%1] {%2 :a} #{%3}]))))

(deftest find-percent-params-skips-nested-fn
  (testing "nested (fn ...) body is not walked"
    (is (= #{1} (forms/find-percent-params '(+ %1 (fn [x] %2)))))))

(deftest find-percent-params-empty
  (is (= #{} (forms/find-percent-params '(+ 1 2)))))

;; ============================================================
;; normalize-bare-percent
;; ============================================================

(deftest normalize-bare-percent-simple
  (is (= '%1 (forms/normalize-bare-percent '%))))

(deftest normalize-bare-percent-in-list
  (is (= '(+ %1 1) (forms/normalize-bare-percent '(+ % 1)))))

(deftest normalize-bare-percent-rest-unchanged
  (is (= '%& (forms/normalize-bare-percent '%&))))

(deftest normalize-bare-percent-numbered-unchanged
  (is (= '%2 (forms/normalize-bare-percent '%2))))

(deftest normalize-bare-percent-nested-containers
  (is (= '[%1 {%1 :a}] (forms/normalize-bare-percent '[% {% :a}]))))

(deftest normalize-bare-percent-skips-nested-fn
  (testing "nested (fn ...) body is not walked — bare % inside fn is untouched"
    (is (= '(+ %1 (fn [x] %))
           (forms/normalize-bare-percent '(+ % (fn [x] %)))))))

;; ============================================================
;; restore-bare-percent
;; ============================================================

(deftest restore-bare-percent-simple
  (is (= '% (forms/restore-bare-percent '%1))))

(deftest restore-bare-percent-in-list
  (is (= '(+ % 1) (forms/restore-bare-percent '(+ %1 1)))))

(deftest restore-bare-percent-numbered-stays
  (testing "%2 is not converted to bare %"
    (is (= '%2 (forms/restore-bare-percent '%2)))))

(deftest restore-bare-percent-rest-stays
  (is (= '%& (forms/restore-bare-percent '%&))))

(deftest restore-bare-percent-nested-containers
  (is (= '[% {% :a}] (forms/restore-bare-percent '[%1 {%1 :a}]))))

(deftest restore-bare-percent-skips-nested-fn
  (testing "nested (fn ...) body is not walked — %1 inside fn is untouched"
    (is (= '(+ % (fn [x] %1))
           (forms/restore-bare-percent '(+ %1 (fn [x] %1)))))))

;; ============================================================
;; walk-anon-fn-body — set source-order invariant
;; ============================================================

(deftest walk-anon-fn-body-refreshes-set-insertion-order
  ;; Scar tissue: walk-anon-fn-body's set branch rebuilt sets via
  ;; (set (map walker form)) and re-attached the input meta, so when
  ;; the walker transformed any element (e.g. % → %1) the surviving
  ;; :m1clj/insertion-order vector still referenced the pre-walk element
  ;; and lost agreement with the set's actual contents. Fix routes the
  ;; rebuild through walk-meme-set, the same helper the other walkers
  ;; (walk-rc, expand-syntax-quotes) already use.
  (testing "elements transformed by the walker stay in sync with insertion-order meta"
    (let [s (with-meta #{(symbol "%") :a :b}
                       {:m1clj/insertion-order [:a (symbol "%") :b]})
          out (forms/walk-anon-fn-body
                (fn [f] (if (and (symbol? f) (= "%" (name f))) (symbol "%1") f))
                s)
          order (:m1clj/insertion-order (meta out))]
      (is (contains? out '%1))
      (is (not (contains? out '%)))
      (is (= (count order) (count out))
          ":m1clj/insertion-order must have the same count as the set")
      (is (every? #(contains? out %) order)
          "every entry in :m1clj/insertion-order must be a member of the set"))))
