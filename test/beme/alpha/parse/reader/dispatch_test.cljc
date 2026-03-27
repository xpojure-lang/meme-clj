(ns beme.alpha.parse.reader.dispatch-test
  "Parser tests for reader macros and dispatch forms:
   @deref, ^meta, 'quote, #'var, #_discard, #(), regex, char,
   tagged literals, reader conditionals, namespaced maps."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [beme.alpha.parse.reader :as r]
            [beme.alpha.scan.tokenizer :as tokenizer]
            [beme.alpha.scan.grouper :as grouper]))

(defn- tokenize [s]
  (-> (tokenizer/tokenize s) (grouper/group-tokens s)))

;; ---------------------------------------------------------------------------
;; Prefix reader macros: @, ^, ', #'
;; ---------------------------------------------------------------------------

(deftest parse-deref
  (is (= '[(clojure.core/deref state)]
         (r/read-beme-string "@state"))))

(deftest parse-deref-of-call
  (let [result (first (r/read-beme-string "@atom-val()"))]
    (is (= 'clojure.core/deref (first result)))))

(deftest parse-metadata-keyword
  (let [result (first (r/read-beme-string "^:dynamic x"))]
    (is (= 'x result))
    (is (true? (:dynamic (meta result))))))

(deftest parse-metadata-map
  (let [result (first (r/read-beme-string "^{:doc \"hello\"} x"))]
    (is (= 'x result))
    (is (= "hello" (:doc (meta result))))))

(deftest parse-metadata-type
  (let [result (first (r/read-beme-string "^String x"))]
    (is (= 'x result))
    (is (= 'String (:tag (meta result))))))

(deftest parse-var-quote
  (let [result (first (r/read-beme-string "#'foo"))]
    (is (= 'var (first result)))
    (is (= 'foo (second result)))))

(deftest parse-discard
  (is (= '[(bar)]
         (r/read-beme-string "#_foo bar()"))))

(deftest parse-quote
  (let [result (first (r/read-beme-string "'foo"))]
    (is (= 'quote (first result)))
    (is (= 'foo (second result)))))

(deftest parse-quote-list
  (let [result (first (r/read-beme-string "'(1 2 3)"))]
    (is (= 'quote (first result)))))

;; ---------------------------------------------------------------------------
;; Regex, char literals
;; ---------------------------------------------------------------------------

(deftest parse-regex
  (let [result (first (r/read-beme-string "#\"pattern\""))]
    (is (instance? #?(:clj java.util.regex.Pattern :cljs js/RegExp) result))
    (is (= "pattern" #?(:clj (.pattern ^java.util.regex.Pattern result)
                        :cljs (.-source result))))))

(deftest parse-char-literal
  (is (= \a (first (r/read-beme-string "\\a"))))
  (is (= \newline (first (r/read-beme-string "\\newline"))))
  (is (= \space (first (r/read-beme-string "\\space")))))

;; ---------------------------------------------------------------------------
;; #() anonymous function shorthand
;; ---------------------------------------------------------------------------

(deftest parse-hash-paren-fn
  (let [form (first (r/read-beme-string "#(inc(%))"))]
    (is (= '(fn [%1] (inc %1)) form))))

(deftest parse-anon-fn-variants
  (testing "#(inc(%)) — single param, beme call syntax inside"
    (is (= '[(fn [%1] (inc %1))]
           (r/read-beme-string "#(inc(%))"))))
  (testing "#(+(%1 %2)) — two params"
    (is (= '[(fn [%1 %2] (+ %1 %2))]
           (r/read-beme-string "#(+(%1 %2))"))))
  (testing "#(%) — bare % is identity (returns arg, does not call it)"
    (is (= '[(fn [%1] %1)]
           (r/read-beme-string "#(%)"))))
  (testing "#(%()) — call arg as function (parens = call)"
    (is (= '[(fn [%1] (%1))]
           (r/read-beme-string "#(%(  ))"))))
  (testing "#() inside a call"
    (is (= '[(map (fn [%1] (inc %1)) [1 2 3])]
           (r/read-beme-string "map(#(inc(%)) [1 2 3])"))))
  (testing "#() with keyword call inside"
    (is (= '[(fn [%1] (:name %1))]
           (r/read-beme-string "#(:name(%))"))))
  (testing "#(apply(str %&)) — rest param"
    (is (= '[(fn [& %&] (apply str %&))]
           (r/read-beme-string "#(apply(str %&))"))))
  (testing "#(+(%1 %&)) — numbered + rest"
    (is (= '[(fn [%1 & %&] (+ %1 %&))]
           (r/read-beme-string "#(+(%1 %&))"))))
  (testing "#() with three numbered params"
    (is (= '[(fn [%1 %2 %3] (+ %1 %2 %3))]
           (r/read-beme-string "#(+(%1 %2 %3))"))))
  (testing "#() with two-digit param %10"
    (let [form (first (r/read-beme-string "#(+(%1 %10))"))]
      (is (= 'fn (first form)))
      (is (= 10 (count (second form))))
      (is (= '%10 (nth (second form) 9)))))
  (testing "#() with %08 — not octal, decimal 8"
    (let [form (first (r/read-beme-string "#(+(%1 %08))"))]
      (is (= 'fn (first form)))
      (is (= 8 (count (second form))))
      (is (= '%8 (nth (second form) 7)))))
  (testing "#() zero params"
    (is (= '[(fn [] (rand))]
           (r/read-beme-string "#(rand())"))))
  (testing "#() with multiple forms throws clear error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"single expression"
          (r/read-beme-string "#(a() b())"))))
  (testing "#( unterminated gives unterminated error, not single-expression"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"Unterminated #\(\)"
          (r/read-beme-string "#(inc"))))
  (testing "#() with only discarded body throws"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"body was discarded"
          (r/read-beme-string "#(#_x)")))))

;; ---------------------------------------------------------------------------
;; Tagged literals, namespaced maps, reader conditionals (JVM-only)
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest parse-tagged-literal
     (let [result (first (r/read-beme-string "#uuid \"550e8400-e29b-41d4-a716-446655440000\""))]
       (is (instance? clojure.lang.TaggedLiteral result)))))

#?(:clj
   (deftest parse-namespaced-map
     (testing "namespace is applied to unqualified keys"
       (let [result (first (r/read-beme-string "#:user{:name \"x\" :age 1}"))]
         (is (= "x" (:user/name result)))
         (is (= 1 (:user/age result)))))
     (testing "already-qualified keys are preserved"
       (let [result (first (r/read-beme-string "#:user{:name \"x\" :other/id 2}"))]
         (is (= "x" (:user/name result)))
         (is (= 2 (:other/id result)))))
     (testing "nested values inside namespaced map are not affected"
       (let [result (first (r/read-beme-string "#:user{:name \"x\" :address {:city \"Kyiv\"}}"))]
         (is (= "x" (:user/name result)))
         (is (= {:city "Kyiv"} (:user/address result)))))))

#?(:clj
   (deftest parse-reader-conditional
     (let [result (first (r/read-beme-string "#?(:clj 1 :cljs 2)"))]
       (is (instance? clojure.lang.ReaderConditional result)))))

#?(:clj
   (deftest parse-reader-conditional-splicing
     (let [result (first (r/read-beme-string "#?@(:clj [1 2] :cljs [3 4])"))]
       (is (instance? clojure.lang.ReaderConditional result))
       (is (str/includes? (pr-str result) "#?@")))))

;; ---------------------------------------------------------------------------
;; maybe-call on opaque forms — opaque results can be call heads
;; ---------------------------------------------------------------------------

#?(:clj
(deftest maybe-call-on-namespaced-map
  (testing "#:ns{} followed by ( is a call"
    (let [form (first (r/read-beme-string "#:user{:name \"x\"}(:user/name)"))]
      (is (seq? form))
      (is (map? (first form)))
      (is (= :user/name (second form)))))))

#?(:clj
(deftest maybe-call-on-reader-conditional
  (testing "#?(...) followed by ( is a call"
    (let [form (first (r/read-beme-string "#?(:clj inc :cljs identity)(42)"))]
      (is (seq? form))
      (is (= 42 (second form)))))))

;; ---------------------------------------------------------------------------
;; CLJS-specific: opaque form tokenization and reader paths
;; ---------------------------------------------------------------------------

#?(:cljs
(deftest cljs-opaque-form-tokenization
  (testing "tokenizer handles reader conditionals even on CLJS"
    (let [tokens (tokenize "#?(:clj x :cljs y)")]
      (is (= 1 (count tokens)))
      (is (= :reader-cond-raw (:type (first tokens))))))
  (testing "tokenizer handles namespaced maps even on CLJS"
    (let [tokens (tokenize "#:user{:name \"x\"}")]
      (is (= 1 (count tokens)))
      (is (= :namespaced-map-raw (:type (first tokens))))))))

#?(:cljs
(deftest cljs-reader-paths
  (testing "discard sentinel filters correctly on CLJS"
    (is (= [] (r/read-beme-string "#_foo"))))
  (testing "regex parsing works through CLJS path"
    (let [forms (r/read-beme-string "#\"abc\"")]
      (is (= 1 (count forms)))
      (is (instance? js/RegExp (first forms)))))
  (testing "string parsing works through CLJS reader"
    (is (= ["hello"] (r/read-beme-string "\"hello\""))))
  (testing "number parsing works through CLJS reader"
    (is (= [42] (r/read-beme-string "42")))
    (is (= [3.14] (r/read-beme-string "3.14"))))))
