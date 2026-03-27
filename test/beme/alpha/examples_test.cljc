(ns beme.alpha.examples-test
  "Integration tests: complex, multi-feature beme examples.
   Unit-level parser tests live in reader_test.cljc."
  (:require [clojure.test :refer [deftest is testing]]
            [beme.alpha.core :as core]))

;; ---------------------------------------------------------------------------
;; Full integration example — everything is a call
;; ---------------------------------------------------------------------------

(def ^:private full-example
  "ns(my.accounts
  :require(
    [clojure.string :as str]))

defn(transform-accounts [accounts]
  let([
    active filter(:active accounts)
    balanced ->>(active
      map(fn([a] update(a :balance *(:balance(a) 1.05))))
      remove(fn([a] neg?(:balance(a)))))
  ]
    reduce(fn([acc {:keys [id balance]}]
      assoc(acc id {:balance balance
                    :status :processed}))
    {} balanced)))

defn(summarize [accounts]
  let([
    processed transform-accounts(accounts)
    total ->>(processed vals() map(:balance) reduce(+))
  ]
    println(str(\"Total: \" total))
    processed))")

(deftest full-readme-example-parses
  (let [forms (core/beme->forms full-example)]
    ;; Should produce 3 top-level forms: ns, defn, defn
    (is (= 3 (count forms)))

    ;; ns
    (let [ns-form (nth forms 0)]
      (is (= 'ns (first ns-form)))
      (is (= 'my.accounts (second ns-form))))

    ;; defn transform-accounts
    (let [transform (nth forms 1)]
      (is (= 'defn (first transform)))
      (is (= 'transform-accounts (second transform)))
      (is (= '[accounts] (nth transform 2)))
      (testing "body is a let form"
        (let [body (nth transform 3)]
          (is (= 'let (first body)))
          (testing "bindings vector contains filter and ->> threading"
            (let [bindings (second body)]
              (is (vector? bindings))
              (is (= 'active (first bindings)))
              ;; filter(:active accounts) → (filter :active accounts)
              (let [filter-call (second bindings)]
                (is (= 'filter (first filter-call)))
                (is (= :active (second filter-call)))))))))

    ;; defn summarize
    (let [summarize (nth forms 2)]
      (is (= 'defn (first summarize)))
      (is (= 'summarize (second summarize)))
      (is (= '[accounts] (nth summarize 2)))
      (testing "body is a let with threading"
        (let [body (nth summarize 3)]
          (is (= 'let (first body)))
          (let [bindings (second body)]
            (is (vector? bindings))
            ;; processed = transform-accounts(accounts)
            (is (= 'processed (first bindings)))
            (let [call (second bindings)]
              (is (= 'transform-accounts (first call))))))))))

