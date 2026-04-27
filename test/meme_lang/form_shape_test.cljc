(ns meme-lang.form-shape-test
  (:require [clojure.test :refer [deftest is testing]]
            [meme-lang.form-shape :as fs]))

;; ---------------------------------------------------------------------------
;; Unknown heads — plain calls
;; ---------------------------------------------------------------------------

(deftest unknown-head-returns-nil
  (testing "a plain function call has no form-shape; decompose returns nil"
    (is (nil? (fs/decompose fs/registry '+ [1 2 3])))
    (is (nil? (fs/decompose fs/registry 'my-user-fn [:a :b])))
    (is (nil? (fs/decompose fs/registry 'nonexistent [])))))

(deftest nil-registry-returns-nil
  (testing "nil registry — every head is shape-less (plain-call rendering)"
    (is (nil? (fs/decompose nil 'defn '[foo [x] x])))
    (is (nil? (fs/decompose nil 'let '[[x 1] x])))))

;; ---------------------------------------------------------------------------
;; defn / defn- / defmacro
;; ---------------------------------------------------------------------------

(deftest defn-single-arity
  (is (= [[:name 'foo] [:params '[x]] [:body '(+ x 1)]]
         (fs/decompose fs/registry 'defn '[foo [x] (+ x 1)]))))

(deftest defn-with-docstring
  (is (= [[:name 'foo] [:doc "adds one"] [:params '[x]] [:body '(+ x 1)]]
         (fs/decompose fs/registry 'defn '[foo "adds one" [x] (+ x 1)]))))

(deftest defn-multi-arity
  (is (= [[:name 'foo]
          [:arity '([x] (foo x 1))]
          [:arity '([x y] (+ x y))]]
         (fs/decompose fs/registry 'defn '[foo ([x] (foo x 1)) ([x y] (+ x y))]))))

(deftest defn-multi-arity-with-doc
  (is (= [[:name 'foo]
          [:doc "adds"]
          [:arity '([x] (foo x 1))]
          [:arity '([x y] (+ x y))]]
         (fs/decompose fs/registry 'defn '[foo "adds" ([x] (foo x 1)) ([x y] (+ x y))]))))

(deftest defn-dash-uses-same-shape
  (is (= (fs/decompose fs/registry 'defn '[foo [x] x])
         (fs/decompose fs/registry 'defn- '[foo [x] x]))))

(deftest defmacro-uses-same-shape-as-defn
  (is (= (fs/decompose fs/registry 'defn '[my [x] x])
         (fs/decompose fs/registry 'defmacro '[my [x] x]))))

(deftest defn-name-only
  (is (= [[:name 'foo]] (fs/decompose fs/registry 'defn '[foo]))))

(deftest defn-empty-rejected
  (is (nil? (fs/decompose fs/registry 'defn []))))

;; ---------------------------------------------------------------------------
;; defmethod
;; ---------------------------------------------------------------------------

(deftest defmethod-shape
  (is (= [[:name 'area] [:dispatch-val :circle]
          [:params '[x]] [:body '(* 3.14 x)]]
         (fs/decompose fs/registry 'defmethod '[area :circle [x] (* 3.14 x)]))))

(deftest defmethod-requires-name-and-dispatch
  (is (nil? (fs/decompose fs/registry 'defmethod [])))
  (is (nil? (fs/decompose fs/registry 'defmethod '[area]))))

;; ---------------------------------------------------------------------------
;; let / loop / for / bindings-body family
;; ---------------------------------------------------------------------------

(deftest let-shape
  (is (= [[:bindings '[x 1 y 2]] [:body '(+ x y)]]
         (fs/decompose fs/registry 'let '[[x 1 y 2] (+ x y)]))))

(deftest let-rejects-non-vector-bindings
  (is (nil? (fs/decompose fs/registry 'let '[not-a-vector body]))))

(deftest loop-for-doseq-share-shape
  (is (= (fs/decompose fs/registry 'let '[[x 1] x])
         (fs/decompose fs/registry 'loop '[[x 1] x])
         (fs/decompose fs/registry 'for '[[x 1] x])
         (fs/decompose fs/registry 'doseq '[[x 1] x]))))

(deftest if-let-when-let-share-shape
  (is (= (fs/decompose fs/registry 'let '[[x 1] x])
         (fs/decompose fs/registry 'if-let '[[x 1] x])
         (fs/decompose fs/registry 'when-let '[[x 1] x])
         (fs/decompose fs/registry 'if-some '[[x 1] x])
         (fs/decompose fs/registry 'when-some '[[x 1] x]))))

;; ---------------------------------------------------------------------------
;; if / when family
;; ---------------------------------------------------------------------------

(deftest if-shape
  (is (= [[:test '(> x 0)] [:body "pos"] [:body "neg"]]
         (fs/decompose fs/registry 'if '[(> x 0) "pos" "neg"]))))

(deftest when-shape
  (is (= [[:test 'ready?] [:body 'do-thing] [:body 'do-other]]
         (fs/decompose fs/registry 'when '[ready? do-thing do-other]))))

;; ---------------------------------------------------------------------------
;; case / cond / condp
;; ---------------------------------------------------------------------------

(deftest case-shape-with-default
  (is (= [[:expr 'x]
          [:clause [1 "one"]]
          [:clause [2 "two"]]
          [:default "other"]]
         (fs/decompose fs/registry 'case '[x 1 "one" 2 "two" "other"]))))

(deftest case-shape-no-default
  (is (= [[:expr 'x]
          [:clause [1 "one"]]
          [:clause [2 "two"]]]
         (fs/decompose fs/registry 'case '[x 1 "one" 2 "two"]))))

(deftest cond-shape
  (is (= [[:clause ['(< x 0) "neg"]]
          [:clause ['(> x 0) "pos"]]
          [:clause [:else "zero"]]]
         (fs/decompose fs/registry 'cond '[(< x 0) "neg" (> x 0) "pos" :else "zero"]))))

(deftest cond-rejects-odd-count
  (is (nil? (fs/decompose fs/registry 'cond '[a 1 b]))))

(deftest condp-shape
  (is (= [[:dispatch-fn '=] [:expr 'x]
          [:clause [1 "one"]]
          [:default "other"]]
         (fs/decompose fs/registry 'condp '[= x 1 "one" "other"]))))

;; ---------------------------------------------------------------------------
;; Threading: ->, ->>, some->, cond->
;; ---------------------------------------------------------------------------

(deftest threading-shape
  (is (= [[:expr 'x] [:body '(inc)] [:body '(dec)]]
         (fs/decompose fs/registry '-> '[x (inc) (dec)])))
  (is (= (fs/decompose fs/registry '-> '[x (inc)])
         (fs/decompose fs/registry '->> '[x (inc)])
         (fs/decompose fs/registry 'some-> '[x (inc)])
         (fs/decompose fs/registry 'cond-> '[x (inc)]))))

;; ---------------------------------------------------------------------------
;; as->
;; ---------------------------------------------------------------------------

(deftest as->-shape
  (is (= [[:expr 'x] [:as-name 'y] [:body '(inc y)]]
         (fs/decompose fs/registry 'as-> '[x y (inc y)]))))

;; ---------------------------------------------------------------------------
;; catch — dispatch on class, bind exception name
;; ---------------------------------------------------------------------------

(deftest catch-shape
  (is (= [[:dispatch-val 'Exception] [:name 'e] [:body '(log e)]]
         (fs/decompose fs/registry 'catch '[Exception e (log e)]))))

;; ---------------------------------------------------------------------------
;; def / defonce / ns / defprotocol
;; ---------------------------------------------------------------------------

(deftest def-shape
  (is (= [[:name 'x] [:body 42]] (fs/decompose fs/registry 'def '[x 42]))))

(deftest def-with-docstring
  (is (= [[:name 'x] [:doc "answer"] [:body 42]]
         (fs/decompose fs/registry 'def '[x "answer" 42]))))

(deftest ns-with-doc-and-refs
  (is (= [[:name 'my.app] [:doc "app ns"] [:body '(:require [x])]]
         (fs/decompose fs/registry 'ns '[my.app "app ns" (:require [x])]))))

;; ---------------------------------------------------------------------------
;; defrecord / deftype
;; ---------------------------------------------------------------------------

(deftest defrecord-shape
  (is (= [[:name 'Point] [:params '[x y]] [:body '(method)]]
         (fs/decompose fs/registry 'defrecord '[Point [x y] (method)]))))

;; ---------------------------------------------------------------------------
;; deftest / testing
;; ---------------------------------------------------------------------------

(deftest deftest-shape
  (is (= [[:name 'my-test] [:body '(is (= 1 1))]]
         (fs/decompose fs/registry 'deftest '[my-test (is (= 1 1))]))))

(deftest testing-shape
  (is (= [[:doc "description"] [:body '(is true)]]
         (fs/decompose fs/registry 'testing '["description" (is true)]))))

;; ---------------------------------------------------------------------------
;; User-defined forms with the same shape as a built-in should need only
;; a registry entry to inherit full layout — verify the registry can
;; resolve an arbitrary symbol via the decomposer function directly.
;; ---------------------------------------------------------------------------

(deftest decomposer-functions-are-exposed
  (testing "built-in decomposers are reachable via the registry for reuse"
    (let [defn-fn (get fs/registry 'defn)]
      (is (fn? defn-fn))
      (is (= (fs/decompose fs/registry 'defn '[foo [x] x])
             (defn-fn '[foo [x] x]))))))

;; ---------------------------------------------------------------------------
;; Structural fallback — opt-in via with-structural-fallback
;; ---------------------------------------------------------------------------

(deftest default-registry-has-no-fallback
  (testing "plain registry returns nil for unregistered heads"
    (is (nil? (fs/decompose fs/registry 'my-defn '[foo [x] (+ x 1)])))
    (is (nil? (fs/decompose fs/registry 'my-let '[[x 1] (+ x 1)])))))

(deftest fallback-infers-defn-like-shape
  (testing "with fallback enabled, name + params vector infers defn-like slots"
    (let [reg (fs/with-structural-fallback fs/registry)]
      (is (= [[:name 'foo] [:params '[x]] [:body '(+ x 1)]]
             (fs/decompose reg 'my-defn '[foo [x] (+ x 1)]))))))

(deftest fallback-infers-let-like-shape
  (testing "with fallback enabled, leading bindings vector infers let-like slots"
    (let [reg (fs/with-structural-fallback fs/registry)]
      (is (= [[:bindings '[x 1]] [:body '(+ x 1)]]
             (fs/decompose reg 'my-let '[[x 1] (+ x 1)]))))))

(deftest fallback-leaves-plain-calls-alone
  (testing "fallback only fires when the args match a recognized pattern"
    (let [reg (fs/with-structural-fallback fs/registry)]
      ;; Plain function call with no vector arg — no inference
      (is (nil? (fs/decompose reg 'my-fn '[1 2 3])))
      ;; Single non-vector arg — no inference
      (is (nil? (fs/decompose reg 'my-fn '[42]))))))

(deftest fallback-does-not-override-registered-entries
  (testing "registered heads still dispatch to their own decomposer"
    (let [reg (fs/with-structural-fallback fs/registry)]
      ;; defn is registered; use its registered decomposer, not the fallback
      (is (= (fs/decompose fs/registry 'defn '[foo [x] x])
             (fs/decompose reg 'defn '[foo [x] x]))))))

(deftest fallback-composes-with-user-extensions
  (testing "user-added entries + structural fallback both work"
    (let [reg (-> fs/registry
                  (assoc 'my-special (fn [_args] [[:name 'special]]))
                  (fs/with-structural-fallback))]
      ;; User-registered head hits the explicit decomposer
      (is (= [[:name 'special]] (fs/decompose reg 'my-special [])))
      ;; Unregistered but defn-shaped head hits the fallback
      (is (= [[:name 'foo] [:params '[x]] [:body 'x]]
             (fs/decompose reg 'my-defn '[foo [x] x]))))))
