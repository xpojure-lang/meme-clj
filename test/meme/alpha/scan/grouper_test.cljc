(ns meme.alpha.scan.grouper-test
  "Unit tests for meme.alpha.scan.grouper: opaque region collapsing."
  (:require [clojure.test :refer [deftest is testing]]
            [meme.alpha.scan.tokenizer :as tokenizer]
            [meme.alpha.scan.grouper :as grouper]))

(defn- group [s]
  (let [raw (tokenizer/tokenize s)]
    (grouper/group-tokens raw s)))

;; ---------------------------------------------------------------------------
;; Reader conditional collapsing
;; ---------------------------------------------------------------------------

(deftest group-reader-conditional
  (testing "#?(...) collapses to single :reader-cond-raw token"
    (let [tokens (group "#?(:clj 1 :cljs 2)")]
      (is (= 1 (count tokens)))
      (is (= :reader-cond-raw (:type (first tokens))))
      (is (= "#?(:clj 1 :cljs 2)" (:value (first tokens))))))
  (testing "#?@(...) splice variant"
    (let [tokens (group "#?@(:clj [1 2] :cljs [3])")]
      (is (= 1 (count tokens)))
      (is (= :reader-cond-raw (:type (first tokens))))
      (is (= "#?@(:clj [1 2] :cljs [3])" (:value (first tokens))))))
  (testing "#?() empty"
    (let [tokens (group "#?()")]
      (is (= 1 (count tokens)))
      (is (= "#?()" (:value (first tokens)))))))

;; ---------------------------------------------------------------------------
;; Namespaced map collapsing
;; ---------------------------------------------------------------------------

(deftest group-namespaced-map
  (testing "#:ns{...} collapses to single :namespaced-map-raw token"
    (let [tokens (group "#:user{:name \"x\" :age 1}")]
      (is (= 1 (count tokens)))
      (is (= :namespaced-map-raw (:type (first tokens))))
      (is (= "#:user{:name \"x\" :age 1}" (:value (first tokens))))))
  (testing "#:ns{} empty map"
    (let [tokens (group "#:ns{}")]
      (is (= 1 (count tokens)))
      (is (= "#:ns{}" (:value (first tokens)))))))

;; ---------------------------------------------------------------------------
;; Syntax-quote collapsing
;; ---------------------------------------------------------------------------

(deftest group-syntax-quote
  (testing "`(...) collapses to single :syntax-quote-raw token"
    (let [tokens (group "`(a b c)")]
      (is (= 1 (count tokens)))
      (is (= :syntax-quote-raw (:type (first tokens))))
      (is (= "`(a b c)" (:value (first tokens))))))
  (testing "`[...] vector form"
    (let [tokens (group "`[a b]")]
      (is (= 1 (count tokens)))
      (is (= :syntax-quote-raw (:type (first tokens))))))
  (testing "`{...} map form"
    (let [tokens (group "`{:a 1}")]
      (is (= 1 (count tokens)))
      (is (= :syntax-quote-raw (:type (first tokens)))))))

;; ---------------------------------------------------------------------------
;; Nested brackets inside opaque regions
;; ---------------------------------------------------------------------------

(deftest group-nested-brackets
  (testing "#? with nested parens and brackets"
    (let [tokens (group "#?(:clj (foo [1 2]) :cljs nil)")]
      (is (= 1 (count tokens)))
      (is (= :reader-cond-raw (:type (first tokens))))))
  (testing "#:ns{} with nested vector"
    (let [tokens (group "#:user{:ids [1 2 3]}")]
      (is (= 1 (count tokens)))
      (is (= :namespaced-map-raw (:type (first tokens)))))))

;; ---------------------------------------------------------------------------
;; Non-opaque tokens pass through unchanged
;; ---------------------------------------------------------------------------

(deftest group-passthrough
  (testing "plain tokens are unchanged"
    (let [tokens (group "foo(1 2)")]
      (is (= 5 (count tokens)))
      (is (= [:symbol :open-paren :number :number :close-paren]
             (mapv :type tokens)))))
  (testing "mixed opaque and plain tokens"
    (let [tokens (group "foo(#?(:clj 1) bar)")]
      (is (= 5 (count tokens)))
      (is (= :symbol (:type (first tokens))))
      (is (= :reader-cond-raw (:type (nth tokens 2)))))))

;; ---------------------------------------------------------------------------
;; #() inside opaque regions (grouper must track bracket depth correctly)
;; ---------------------------------------------------------------------------

(deftest group-anon-fn-inside-opaque
  (testing "#() inside reader conditional"
    (let [tokens (group "#?(:clj #(inc %) :cljs identity)")]
      (is (= 1 (count tokens)))
      (is (= :reader-cond-raw (:type (first tokens))))))
  (testing "#() inside namespaced map"
    (let [tokens (group "#:user{:f #(inc %)}")]
      (is (= 1 (count tokens)))
      (is (= :namespaced-map-raw (:type (first tokens)))))))

;; ---------------------------------------------------------------------------
;; Marker tokens without following bracket pass through ungrouped
;; ---------------------------------------------------------------------------

(deftest group-reader-cond-start-without-bracket
  (testing "#? followed by non-bracket passes through as start token"
    (let [tokens (group "#?foo")]
      (is (some #(= :reader-cond-start (:type %)) tokens))
      (is (not (some #(= :reader-cond-raw (:type %)) tokens)))))
  (testing "#? at EOF passes through as start token"
    (let [raw (tokenizer/tokenize "#?")
          tokens (grouper/group-tokens raw "#?")]
      (is (= 1 (count tokens)))
      (is (= :reader-cond-start (:type (first tokens)))))))

(deftest group-namespaced-map-start-without-bracket
  (testing "#:ns followed by non-bracket passes through as start token"
    (let [tokens (group "#:ns foo")]
      (is (some #(= :namespaced-map-start (:type %)) tokens))
      (is (not (some #(= :namespaced-map-raw (:type %)) tokens))))))

(deftest group-syntax-quote-start-without-bracket
  (testing "backtick at EOF throws during tokenization (not a grouper path)"
    ;; The tokenizer itself rejects bare ` at EOF with :incomplete error,
    ;; so the grouper never sees a :syntax-quote-start without a following form.
    (is (thrown? #?(:clj Exception :cljs :default) (tokenizer/tokenize "`")))))

;; ---------------------------------------------------------------------------
;; Composite tokens carry correct :end-line/:end-col from the closing delimiter
;; ---------------------------------------------------------------------------

(deftest group-composite-end-position
  (testing "#?(...) end position spans to closing paren"
    (let [tokens (group "#?(:clj 1)")]
      (is (= 1 (count tokens)))
      (is (= 1 (:end-line (first tokens))))
      (is (= 11 (:end-col (first tokens))))))
  (testing "#:ns{...} end position spans to closing brace"
    (let [tokens (group "#:ns{:a 1}")]
      (is (= 1 (count tokens)))
      (is (= 1 (:end-line (first tokens))))
      (is (= 11 (:end-col (first tokens))))))
  (testing "multi-line #?() end position on correct line"
    (let [tokens (group "#?(:clj\n  1)")]
      (is (= 1 (count tokens)))
      (is (= 2 (:end-line (first tokens))))
      (is (= 5 (:end-col (first tokens)))))))
