(ns meme-lang.reader.dispatch-test
  "Parser tests for reader macros and dispatch forms:
   @deref, ^meta, 'quote, #'var, #_discard, #(), regex, char,
   tagged literals, reader conditionals, namespaced maps."
  (:require [clojure.test :refer [deftest is testing]]
            [meme-lang.api :as lang]
            [meme-lang.forms :as forms]
            [meme-lang.formatter.flat :as fmt-flat]))

;; ---------------------------------------------------------------------------
;; Prefix reader macros: @, ^, ', #'
;; ---------------------------------------------------------------------------

(deftest parse-deref
  (is (= '[(clojure.core/deref state)]
         (lang/meme->forms "@state"))))

(deftest parse-deref-of-call
  (let [result (first (lang/meme->forms "@atom-val()"))]
    (is (= 'clojure.core/deref (first result)))))

(deftest parse-metadata-keyword
  (let [result (first (lang/meme->forms "^:dynamic x"))]
    (is (= 'x result))
    (is (true? (:dynamic (meta result))))))

(deftest parse-metadata-map
  (let [result (first (lang/meme->forms "^{:doc \"hello\"} x"))]
    (is (= 'x result))
    (is (= "hello" (:doc (meta result))))))

(deftest parse-metadata-type
  (let [result (first (lang/meme->forms "^String x"))]
    (is (= 'x result))
    (is (= 'String (:tag (meta result))))))

(deftest parse-chained-metadata
  (testing "two keyword metadata merge"
    (let [result (first (lang/meme->forms "^:private ^:dynamic x"))]
      (is (= 'x result))
      (is (true? (:private (meta result))))
      (is (true? (:dynamic (meta result))))))
  (testing "keyword + type tag merge"
    (let [result (first (lang/meme->forms "^:private ^String x"))]
      (is (= 'x result))
      (is (true? (:private (meta result))))
      (is (= 'String (:tag (meta result))))))
  (testing "three chained metadata"
    (let [result (first (lang/meme->forms "^:private ^:dynamic ^String x"))]
      (is (= 'x result))
      (is (true? (:private (meta result))))
      (is (true? (:dynamic (meta result))))
      (is (= 'String (:tag (meta result)))))))

(deftest parse-var-quote
  (let [result (first (lang/meme->forms "#'foo"))]
    (is (= 'var (first result)))
    (is (= 'foo (second result)))))

(deftest parse-discard
  (is (= '[(bar)]
         (lang/meme->forms "#_foo bar()"))))

(deftest parse-quote
  (let [result (first (lang/meme->forms "'foo"))]
    (is (= 'quote (first result)))
    (is (= 'foo (second result)))))

(deftest parse-quote-call
  (let [result (first (lang/meme->forms "'f(1 2 3)"))]
    (is (= 'quote (first result)))))

;; ---------------------------------------------------------------------------
;; Regex, char literals
;; ---------------------------------------------------------------------------

(deftest parse-regex
  (let [result (first (lang/meme->forms "#\"pattern\""))]
    (is (instance? #?(:clj java.util.regex.Pattern :cljs js/RegExp) result))
    (is (= "pattern" #?(:clj (.pattern ^java.util.regex.Pattern result)
                        :cljs (.-source result))))))

(deftest parse-char-literal
  (is (= \a (first (lang/meme->forms "\\a"))))
  (is (= \newline (first (lang/meme->forms "\\newline"))))
  (is (= \space (first (lang/meme->forms "\\space")))))

;; ---------------------------------------------------------------------------
;; #() anonymous function shorthand
;; ---------------------------------------------------------------------------

(deftest parse-hash-paren-fn
  (let [form (first (lang/meme->forms "#(inc(%))"))]
    (is (= '(fn [%1] (inc %1)) form))))

(deftest parse-anon-fn-variants
  (testing "#(inc(%)) — single param, meme call syntax inside"
    (is (= '[(fn [%1] (inc %1))]
           (lang/meme->forms "#(inc(%))"))))
  (testing "#(+(%1 %2)) — two params"
    (is (= '[(fn [%1 %2] (+ %1 %2))]
           (lang/meme->forms "#(+(%1 %2))"))))
  (testing "#(%) — bare % is identity (returns arg, does not call it)"
    (is (= '[(fn [%1] %1)]
           (lang/meme->forms "#(%)"))))
  (testing "#(%()) — call arg as function (parens = call)"
    (is (= '[(fn [%1] (%1))]
           (lang/meme->forms "#(%(  ))"))))
  (testing "#() inside a call"
    (is (= '[(map (fn [%1] (inc %1)) [1 2 3])]
           (lang/meme->forms "map(#(inc(%)) [1 2 3])"))))
  (testing "#() with keyword call inside"
    (is (= '[(fn [%1] (:name %1))]
           (lang/meme->forms "#(:name(%))"))))
  (testing "#(apply(str %&)) — rest param"
    (is (= '[(fn [& %&] (apply str %&))]
           (lang/meme->forms "#(apply(str %&))"))))
  (testing "#(+(%1 %&)) — numbered + rest"
    (is (= '[(fn [%1 & %&] (+ %1 %&))]
           (lang/meme->forms "#(+(%1 %&))"))))
  (testing "#() with three numbered params"
    (is (= '[(fn [%1 %2 %3] (+ %1 %2 %3))]
           (lang/meme->forms "#(+(%1 %2 %3))"))))
  (testing "#() with two-digit param %10"
    (let [form (first (lang/meme->forms "#(+(%1 %10))"))]
      (is (= 'fn (first form)))
      (is (= 10 (count (second form))))
      (is (= '%10 (nth (second form) 9)))))
  (testing "#() with %08 — not octal, decimal 8"
    (let [form (first (lang/meme->forms "#(+(%1 %08))"))]
      (is (= 'fn (first form)))
      (is (= 8 (count (second form))))
      (is (= '%8 (nth (second form) 7)))))
  (testing "#() zero params"
    (is (= '[(fn [] (rand))]
           (lang/meme->forms "#(rand())"))))
  ;; NOTE: experimental pipeline wraps multiple #() body forms in (do ...)
  (testing "#() with multiple forms wraps in do"
    (is (= '[(fn [] (do (a) (b)))]
           (lang/meme->forms "#(a() b())"))))
  (testing "#( unterminated gives unclosed error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"[Uu]nclosed"
                          (lang/meme->forms "#(inc"))))
  (testing "#() with only discarded body throws"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"requires a body"
                          (lang/meme->forms "#(#_x)")))))

;; ---------------------------------------------------------------------------
;; Tagged literals, namespaced maps, reader conditionals (JVM-only)
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest parse-tagged-literal
     (let [result (first (lang/meme->forms "#uuid \"550e8400-e29b-41d4-a716-446655440000\""))]
       (is (instance? clojure.lang.TaggedLiteral result)))))

#?(:clj
   (deftest parse-namespaced-map
     (testing "namespace is applied to unqualified keys"
       (let [result (first (lang/meme->forms "#:user{:name \"x\" :age 1}"))]
         (is (= "x" (:user/name result)))
         (is (= 1 (:user/age result)))))
     (testing "already-qualified keys are preserved"
       (let [result (first (lang/meme->forms "#:user{:name \"x\" :other/id 2}"))]
         (is (= "x" (:user/name result)))
         (is (= 2 (:other/id result)))))
     (testing "nested values inside namespaced map are not affected"
       (let [result (first (lang/meme->forms "#:user{:name \"x\" :address {:city \"Kyiv\"}}"))]
         (is (= "x" (:user/name result)))
         (is (= {:city "Kyiv"} (:user/address result)))))))

;; ---------------------------------------------------------------------------
;; C7: Bare auto-resolve namespaced map #::{}
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest parse-bare-auto-resolve-namespaced-map
     (testing "#::{:a 1} — bare auto-resolve accepted, keys stay unqualified"
       (let [result (first (lang/meme->forms "#::{:a 1}"))]
         (is (map? result))
         (is (= 1 (:a result)) "keys should stay unqualified (defer to eval)")
         (is (= "::" (:meme-lang/namespace-prefix (meta result))) "metadata signals bare auto-resolve")))
     (testing "#::{:a 1} roundtrips through printer"
       (let [forms (lang/meme->forms "#::{:a 1}")
             printed (fmt-flat/format-forms forms)
             reparsed (lang/meme->forms printed)]
         (is (= forms reparsed))))))

;; ---------------------------------------------------------------------------
;; Reader conditionals — meme->forms always returns MemeReaderConditional
;; records. step-evaluate-reader-conditionals materializes them for eval
;; paths (see stages-test and run-test for that coverage).
;; ---------------------------------------------------------------------------

(deftest parse-reader-conditional
  (testing "preserves as a ReaderConditional record with all branches"
    (let [rc (first (lang/meme->forms "#?(:clj 1 :cljs 2)"))]
      (is (forms/meme-reader-conditional? rc))
      (is (= '(:clj 1 :cljs 2) (forms/rc-form rc)))
      (is (false? (forms/rc-splicing? rc)))))
  (testing "preserves :default branch"
    (let [rc (first (lang/meme->forms "#?(:clj 1 :default 0)"))]
      (is (forms/meme-reader-conditional? rc))
      (is (= '(:clj 1 :default 0) (forms/rc-form rc))))))

(deftest parse-reader-conditional-splicing
  (testing "splicing variant preserves as a record with splicing=true"
    (let [rc (first (lang/meme->forms "#?@(:clj [1] :cljs [2])"))]
      (is (forms/meme-reader-conditional? rc))
      (is (true? (forms/rc-splicing? rc)))
      (is (= '(:clj [1] :cljs [2]) (forms/rc-form rc))))))

(deftest parse-reader-conditional-meme-syntax
  (testing "inner forms use meme call syntax"
    (let [rc (first (lang/meme->forms "#?(:clj inc(1) :cljs dec(2))"))]
      (is (= '(:clj (inc 1) :cljs (dec 2)) (forms/rc-form rc))))))

(deftest parse-reader-conditional-nested
  (testing "nested #? both preserved as records"
    (let [rc (first (lang/meme->forms "#?(:clj #?(:clj 1 :cljs 2) :cljs 3)"))]
      (is (forms/meme-reader-conditional? rc))
      (let [inner (second (forms/rc-form rc))]
        (is (forms/meme-reader-conditional? inner))
        (is (= '(:clj 1 :cljs 2) (forms/rc-form inner)))))))

;; ---------------------------------------------------------------------------
;; maybe-call on opaque forms — opaque results can be call heads
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest maybe-call-on-namespaced-map
     (testing "#:ns{} followed by ( is a call"
       (let [form (first (lang/meme->forms "#:user{:name \"x\"}(:user/name)"))]
         (is (seq? form))
         (is (map? (first form)))
         (is (= :user/name (second form)))))))

#?(:clj
   (deftest maybe-call-on-reader-conditional
     (testing "#?(...) followed by ( is a call"
       (let [form (first (lang/meme->forms "#?(:clj inc :cljs identity)(42)"))]
         (is (seq? form))
         (is (= 42 (second form)))))))

;; ---------------------------------------------------------------------------
;; CLJS-specific: opaque form tokenization and reader paths
;; ---------------------------------------------------------------------------

#?(:cljs
   (deftest cljs-form-parsing
     (testing "reader conditionals preserved as records on CLJS"
       ;; Post-5.0: #?/#?@ always return MemeReaderConditional records from
       ;; meme->forms. Platform materialization lives in
       ;; step-evaluate-reader-conditionals, which tooling paths don't run.
       (let [forms (lang/meme->forms "#?(:clj x :cljs y)")]
         (is (= 1 (count forms)))
         (is (forms/meme-reader-conditional? (first forms)))))
     (testing "namespaced maps parse on CLJS"
       (let [forms (lang/meme->forms "#:user{:name \"x\"}")]
         (is (= [{:user/name "x"}] forms))))))

#?(:cljs
   (deftest cljs-reader-paths
     (testing "discard sentinel filters correctly on CLJS"
       (is (= [] (lang/meme->forms "#_foo"))))
     (testing "regex parsing works through CLJS path"
       (let [forms (lang/meme->forms "#\"abc\"")]
         (is (= 1 (count forms)))
         (is (instance? js/RegExp (first forms)))))
     (testing "string parsing works through CLJS reader"
       (is (= ["hello"] (lang/meme->forms "\"hello\""))))
     (testing "number parsing works through CLJS reader"
       (is (= [42] (lang/meme->forms "42")))
       (is (= [3.14] (lang/meme->forms "3.14"))))))
