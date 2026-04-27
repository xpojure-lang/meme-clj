(ns m1clj-lang.reader.dispatch-test
  "Parser tests for reader macros and dispatch forms:
   @deref, ^meta, 'quote, #'var, #_discard, #(), regex, char,
   tagged literals, reader conditionals, namespaced maps."
  (:require [clojure.test :refer [deftest is testing]]
            [m1clj-lang.api :as lang]
            [meme.tools.clj.forms :as forms]
            [m1clj-lang.formatter.flat :as fmt-flat]))

;; ---------------------------------------------------------------------------
;; Prefix reader macros: @, ^, ', #'
;; ---------------------------------------------------------------------------

(deftest parse-deref
  (is (= '[(clojure.core/deref state)]
         (lang/m1clj->forms "@state"))))

(deftest parse-deref-of-call
  (let [result (first (lang/m1clj->forms "@atom-val()"))]
    (is (= 'clojure.core/deref (first result)))))

(deftest parse-metadata-keyword
  (let [result (first (lang/m1clj->forms "^:dynamic x"))]
    (is (= 'x result))
    (is (true? (:dynamic (meta result))))))

(deftest parse-metadata-map
  (let [result (first (lang/m1clj->forms "^{:doc \"hello\"} x"))]
    (is (= 'x result))
    (is (= "hello" (:doc (meta result))))))

(deftest parse-metadata-type
  (let [result (first (lang/m1clj->forms "^String x"))]
    (is (= 'x result))
    (is (= 'String (:tag (meta result))))))

(deftest parse-chained-metadata
  (testing "two keyword metadata merge"
    (let [result (first (lang/m1clj->forms "^:private ^:dynamic x"))]
      (is (= 'x result))
      (is (true? (:private (meta result))))
      (is (true? (:dynamic (meta result))))))
  (testing "keyword + type tag merge"
    (let [result (first (lang/m1clj->forms "^:private ^String x"))]
      (is (= 'x result))
      (is (true? (:private (meta result))))
      (is (= 'String (:tag (meta result))))))
  (testing "three chained metadata"
    (let [result (first (lang/m1clj->forms "^:private ^:dynamic ^String x"))]
      (is (= 'x result))
      (is (true? (:private (meta result))))
      (is (true? (:dynamic (meta result))))
      (is (= 'String (:tag (meta result)))))))

(deftest parse-var-quote
  (let [result (first (lang/m1clj->forms "#'foo"))]
    (is (= 'var (first result)))
    (is (= 'foo (second result)))))

(deftest parse-discard
  (is (= '[(bar)]
         (lang/m1clj->forms "#_foo bar()"))))

(deftest parse-quote
  (let [result (first (lang/m1clj->forms "'foo"))]
    (is (= 'quote (first result)))
    (is (= 'foo (second result)))))

(deftest parse-quote-call
  (let [result (first (lang/m1clj->forms "'f(1 2 3)"))]
    (is (= 'quote (first result)))))

;; ---------------------------------------------------------------------------
;; Regex, char literals
;; ---------------------------------------------------------------------------

(deftest parse-regex
  (let [result (first (lang/m1clj->forms "#\"pattern\""))]
    (is (instance? #?(:clj java.util.regex.Pattern :cljs js/RegExp) result))
    (is (= "pattern" #?(:clj (.pattern ^java.util.regex.Pattern result)
                        :cljs (.-source result))))))

(deftest parse-char-literal
  (is (= \a (first (lang/m1clj->forms "\\a"))))
  (is (= \newline (first (lang/m1clj->forms "\\newline"))))
  (is (= \space (first (lang/m1clj->forms "\\space")))))

;; ---------------------------------------------------------------------------
;; #() anonymous function shorthand
;; ---------------------------------------------------------------------------

(deftest parse-hash-paren-fn
  (let [form (first (lang/m1clj->forms "#(inc(%))"))]
    (is (= '(fn [%1] (inc %1)) form))))

(deftest parse-anon-fn-variants
  (testing "#(inc(%)) — single param, meme call syntax inside"
    (is (= '[(fn [%1] (inc %1))]
           (lang/m1clj->forms "#(inc(%))"))))
  (testing "#(+(%1 %2)) — two params"
    (is (= '[(fn [%1 %2] (+ %1 %2))]
           (lang/m1clj->forms "#(+(%1 %2))"))))
  (testing "#(%) — bare % is identity (returns arg, does not call it)"
    (is (= '[(fn [%1] %1)]
           (lang/m1clj->forms "#(%)"))))
  (testing "#(%()) — call arg as function (parens = call)"
    (is (= '[(fn [%1] (%1))]
           (lang/m1clj->forms "#(%(  ))"))))
  (testing "#() inside a call"
    (is (= '[(map (fn [%1] (inc %1)) [1 2 3])]
           (lang/m1clj->forms "map(#(inc(%)) [1 2 3])"))))
  (testing "#() with keyword call inside"
    (is (= '[(fn [%1] (:name %1))]
           (lang/m1clj->forms "#(:name(%))"))))
  (testing "#(apply(str %&)) — rest param"
    (is (= '[(fn [& %&] (apply str %&))]
           (lang/m1clj->forms "#(apply(str %&))"))))
  (testing "#(+(%1 %&)) — numbered + rest"
    (is (= '[(fn [%1 & %&] (+ %1 %&))]
           (lang/m1clj->forms "#(+(%1 %&))"))))
  (testing "#() with three numbered params"
    (is (= '[(fn [%1 %2 %3] (+ %1 %2 %3))]
           (lang/m1clj->forms "#(+(%1 %2 %3))"))))
  (testing "#() with two-digit param %10"
    (let [form (first (lang/m1clj->forms "#(+(%1 %10))"))]
      (is (= 'fn (first form)))
      (is (= 10 (count (second form))))
      (is (= '%10 (nth (second form) 9)))))
  (testing "#() with %08 — not octal, decimal 8"
    (let [form (first (lang/m1clj->forms "#(+(%1 %08))"))]
      (is (= 'fn (first form)))
      (is (= 8 (count (second form))))
      (is (= '%8 (nth (second form) 7)))))
  (testing "#() zero params"
    (is (= '[(fn [] (rand))]
           (lang/m1clj->forms "#(rand())"))))
  ;; NOTE: experimental pipeline wraps multiple #() body forms in (do ...)
  (testing "#() with multiple forms wraps in do"
    (is (= '[(fn [] (do (a) (b)))]
           (lang/m1clj->forms "#(a() b())"))))
  (testing "#( unterminated gives unclosed error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"[Uu]nclosed"
                          (lang/m1clj->forms "#(inc"))))
  (testing "#() with only discarded body throws"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"requires a body"
                          (lang/m1clj->forms "#(#_x)")))))

;; ---------------------------------------------------------------------------
;; Tagged literals, namespaced maps, reader conditionals
;; ---------------------------------------------------------------------------

(deftest parse-tagged-literal
  ;; Tag with no registered data-reader: falls back to TaggedLiteral on both
  ;; platforms. (Tags in default-data-readers like #uuid / #inst resolve at
  ;; read time — see parse-tagged-literal-default-reader-resolves.)
  (let [result (first (lang/m1clj->forms "#unknown-tag \"foo\""))]
    (is (tagged-literal? result))
    (is (= 'unknown-tag (:tag result)))
    (is (= "foo" (:form result)))))

(deftest parse-tagged-literal-default-reader-resolves
  ;; Default data-readers for #uuid / #inst run at read time, matching
  ;; clojure.core/read-string. CLJS recognises the same two tags.
  (testing "#uuid"
    (let [result (first (lang/m1clj->forms
                          "#uuid \"550e8400-e29b-41d4-a716-446655440000\""))]
      (is (instance? #?(:clj java.util.UUID :cljs cljs.core/UUID) result))))
  (testing "#inst"
    (let [result (first (lang/m1clj->forms
                          "#inst \"2024-01-15T10:30:00.000-00:00\""))]
      (is (instance? #?(:clj java.util.Date :cljs js/Date) result)))))

#?(:clj
   (deftest parse-namespaced-map
     (testing "namespace is applied to unqualified keys"
       (let [result (first (lang/m1clj->forms "#:user{:name \"x\" :age 1}"))]
         (is (= "x" (:user/name result)))
         (is (= 1 (:user/age result)))))
     (testing "already-qualified keys are preserved"
       (let [result (first (lang/m1clj->forms "#:user{:name \"x\" :other/id 2}"))]
         (is (= "x" (:user/name result)))
         (is (= 2 (:other/id result)))))
     (testing "nested values inside namespaced map are not affected"
       (let [result (first (lang/m1clj->forms "#:user{:name \"x\" :address {:city \"Kyiv\"}}"))]
         (is (= "x" (:user/name result)))
         (is (= {:city "Kyiv"} (:user/address result)))))))

;; ---------------------------------------------------------------------------
;; C7: Bare auto-resolve namespaced map #::{}
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest parse-bare-auto-resolve-namespaced-map
     (testing "#::{:a 1} — bare auto-resolve accepted, keys stay unqualified"
       (let [result (first (lang/m1clj->forms "#::{:a 1}"))]
         (is (map? result))
         (is (= 1 (:a result)) "keys should stay unqualified (defer to eval)")))
     (testing "#::{:a 1} roundtrips through source-driven canon (AST path)"
       (is (= "#::{:a 1}" (lang/format-m1clj "#::{:a 1}" nil))))))

;; ---------------------------------------------------------------------------
;; Reader conditionals — m1clj->forms always returns CljReaderConditional
;; records. step-evaluate-reader-conditionals materializes them for eval
;; paths (see stages-test and run-test for that coverage).
;; ---------------------------------------------------------------------------

(deftest parse-reader-conditional
  (testing "preserves as a ReaderConditional record with all branches"
    (let [rc (first (lang/m1clj->forms "#?(:clj 1 :cljs 2)"))]
      (is (forms/clj-reader-conditional? rc))
      (is (= '(:clj 1 :cljs 2) (forms/rc-form rc)))
      (is (false? (forms/rc-splicing? rc)))))
  (testing "preserves :default branch"
    (let [rc (first (lang/m1clj->forms "#?(:clj 1 :default 0)"))]
      (is (forms/clj-reader-conditional? rc))
      (is (= '(:clj 1 :default 0) (forms/rc-form rc))))))

(deftest parse-reader-conditional-splicing
  (testing "splicing variant preserves as a record with splicing=true"
    (let [rc (first (lang/m1clj->forms "#?@(:clj [1] :cljs [2])"))]
      (is (forms/clj-reader-conditional? rc))
      (is (true? (forms/rc-splicing? rc)))
      (is (= '(:clj [1] :cljs [2]) (forms/rc-form rc))))))

(deftest parse-reader-conditional-meme-syntax
  (testing "inner forms use meme call syntax"
    (let [rc (first (lang/m1clj->forms "#?(:clj inc(1) :cljs dec(2))"))]
      (is (= '(:clj (inc 1) :cljs (dec 2)) (forms/rc-form rc))))))

(deftest parse-reader-conditional-nested
  (testing "nested #? both preserved as records"
    (let [rc (first (lang/m1clj->forms "#?(:clj #?(:clj 1 :cljs 2) :cljs 3)"))]
      (is (forms/clj-reader-conditional? rc))
      (let [inner (second (forms/rc-form rc))]
        (is (forms/clj-reader-conditional? inner))
        (is (= '(:clj 1 :cljs 2) (forms/rc-form inner)))))))

;; ---------------------------------------------------------------------------
;; maybe-call on opaque forms — opaque results can be call heads
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest maybe-call-on-namespaced-map
     (testing "#:ns{} followed by ( is a call"
       (let [form (first (lang/m1clj->forms "#:user{:name \"x\"}(:user/name)"))]
         (is (seq? form))
         (is (map? (first form)))
         (is (= :user/name (second form)))))))

#?(:clj
   (deftest maybe-call-on-reader-conditional
     (testing "#?(...) followed by ( is a call"
       (let [form (first (lang/m1clj->forms "#?(:clj inc :cljs identity)(42)"))]
         (is (seq? form))
         (is (= 42 (second form)))))))

;; ---------------------------------------------------------------------------
;; CLJS-specific: opaque form tokenization and reader paths
;; ---------------------------------------------------------------------------

#?(:cljs
   (deftest cljs-form-parsing
     (testing "reader conditionals preserved as records on CLJS"
       ;; Post-5.0: #?/#?@ always return CljReaderConditional records from
       ;; m1clj->forms. Platform materialization lives in
       ;; step-evaluate-reader-conditionals, which tooling paths don't run.
       (let [forms (lang/m1clj->forms "#?(:clj x :cljs y)")]
         (is (= 1 (count forms)))
         (is (forms/clj-reader-conditional? (first forms)))))
     (testing "namespaced maps parse on CLJS"
       (let [forms (lang/m1clj->forms "#:user{:name \"x\"}")]
         (is (= [{:user/name "x"}] forms))))))

#?(:cljs
   (deftest cljs-reader-paths
     (testing "discard sentinel filters correctly on CLJS"
       (is (= [] (lang/m1clj->forms "#_foo"))))
     (testing "regex parsing works through CLJS path"
       (let [forms (lang/m1clj->forms "#\"abc\"")]
         (is (= 1 (count forms)))
         (is (instance? js/RegExp (first forms)))))
     (testing "string parsing works through CLJS reader"
       (is (= ["hello"] (lang/m1clj->forms "\"hello\""))))
     (testing "number parsing works through CLJS reader"
       (is (= [42] (lang/m1clj->forms "42")))
       (is (= [3.14] (lang/m1clj->forms "3.14"))))))
