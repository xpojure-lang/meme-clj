(ns meme.generative-cljs-test
  "Cross-platform property-based tests. Portable subset of generative_test.clj.
   Runs on both JVM and ClojureScript to catch CLJS-specific bugs in
   tokenizer, resolve, and formatter that JVM-only generative tests miss."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [meme-lang.api :as lang]
            [meme-lang.formatter.canon :as fmt-canon]
            [meme-lang.formatter.flat :as fmt-flat]))

;; ===========================================================================
;; Leaf generators (portable — no chars, ratios, BigDecimal, BigInt)
;; ===========================================================================

(def safe-symbol-chars "abcdefghijklmnopqrstuvwxyz")
(def symbol-suffix-chars "abcdefghijklmnopqrstuvwxyz0123456789-?!*")

(def reserved-symbols #{'fn 'quote 'var 'clojure.core/deref
                        (symbol "nil") (symbol "true") (symbol "false")})

(def reserved-meta-keywords #{:meme/ws :line :column :file})

(def gen-simple-symbol
  (gen/let [first-char (gen/elements (seq safe-symbol-chars))
            rest-chars (gen/vector (gen/elements (seq symbol-suffix-chars)) 0 8)]
    (let [sym (symbol (apply str first-char rest-chars))]
      (if (reserved-symbols sym) (symbol (str first-char "x")) sym))))

(def gen-keyword
  (gen/such-that
   #(not (reserved-meta-keywords %))
   (gen/one-of
    [(gen/let [n (gen/not-empty (gen/vector (gen/elements (seq safe-symbol-chars)) 1 8))]
       (keyword (apply str n)))
     (gen/let [ns-chars (gen/not-empty (gen/vector (gen/elements (seq safe-symbol-chars)) 1 5))
               n-chars (gen/not-empty (gen/vector (gen/elements (seq safe-symbol-chars)) 1 5))]
       (keyword (apply str ns-chars) (apply str n-chars)))])))

(def gen-number
  (gen/one-of [gen/small-integer
               (gen/fmap long (gen/large-integer* {:min -1000000 :max 1000000}))
               (gen/let [n gen/small-integer d (gen/choose 1 99)]
                 (+ (double n) (/ d 100.0)))]))

(def gen-string
  (gen/one-of
   [(gen/fmap #(apply str %) (gen/vector (gen/elements (seq "abcdefghijklmnopqrstuvwxyz 0123456789!?.-_")) 0 20))
    (gen/fmap #(apply str %) (gen/vector (gen/elements [\a \b \c \space \" \\ \newline \tab]) 0 10))]))

(def gen-primitive
  (gen/one-of
   [gen-number gen-string gen-keyword gen-simple-symbol
    gen/boolean (gen/return nil)]))

;; ===========================================================================
;; Collection generators
;; ===========================================================================

(defn gen-vector-of [g] (gen/vector g 0 5))
(defn gen-map-of [g] (gen/let [kvs (gen/vector (gen/tuple gen-keyword g) 0 4)]
                       (apply array-map (mapcat identity kvs))))
(defn gen-set-of [g] (gen/fmap set (gen/vector g 0 4)))

;; ===========================================================================
;; Matrix generators (head × arg × arity)
;; ===========================================================================

(def head-generators
  [[:symbol  gen-simple-symbol]
   [:keyword gen-keyword]
   [:vector  (gen/vector gen-simple-symbol 1 3)]])

(defn arg-generators [gen-elem]
  [[:primitive gen-primitive]
   [:symbol    gen-simple-symbol]
   [:keyword   gen-keyword]
   [:vector    (gen/vector gen-elem 0 3)]
   [:map       (gen-map-of gen-elem)]
   [:set       (gen-set-of gen-elem)]
   [:call      (gen/let [h gen-simple-symbol as (gen/vector gen-elem 0 3)]
                 (apply list h as))]
   [:kw-call   (gen/let [k gen-keyword a gen-simple-symbol] (list k a))]
   [:deref     (gen/fmap #(list 'clojure.core/deref %) gen-simple-symbol)]
   [:quote     (gen/fmap #(list 'quote %) gen-simple-symbol)]
   [:var       (gen/fmap #(list 'var %) gen-simple-symbol)]])

(def arities [0 1 2 3])

(defn gen-matrix-cell [gen-head gen-arg arity]
  (gen/let [h gen-head
            args (gen/vector gen-arg arity)]
    (apply list h args)))

(def gen-flat-matrix
  (gen/one-of
   (for [[_hname gen-h] head-generators
         [_aname gen-a] (arg-generators gen-primitive)
         arity arities]
     (gen-matrix-cell gen-h gen-a arity))))

;; ===========================================================================
;; Recursive form generator (portable)
;; ===========================================================================

(def gen-form
  (gen/recursive-gen
   (fn [inner]
     (gen/one-of
      [(gen-vector-of inner)
       (gen-map-of inner)
       (gen-set-of inner)
       (gen/let [h gen-simple-symbol as (gen/vector inner 0 4)]
         (apply list h as))
       (gen/let [k gen-keyword as (gen/vector inner 1 3)]
         (apply list k as))
       (gen/fmap #(list 'clojure.core/deref %) gen-simple-symbol)
       (gen/fmap #(list 'quote %) gen-simple-symbol)
       (gen/fmap #(list 'var %) gen-simple-symbol)
       (gen/let [k gen-keyword s gen-simple-symbol] (list k s))]))
   gen-primitive))

;; ===========================================================================
;; Roundtrip helper
;; ===========================================================================

(defn roundtrip-ok? [form]
  (try
    (let [printed (fmt-flat/format-forms [form])
          read-back (lang/meme->forms printed)]
      (= [form] read-back))
    (catch #?(:clj Exception :cljs :default) _
      false)))

;; ===========================================================================
;; Properties
;; ===========================================================================

(defspec prop-cljs-matrix-flat 100
  (prop/for-all [form gen-flat-matrix]
    (roundtrip-ok? form)))

(defspec prop-cljs-mixed-form-roundtrip 100
  (prop/for-all [form gen-form]
    (roundtrip-ok? form)))

(defspec prop-cljs-errors-have-location 100
  (prop/for-all [meme-str (gen/one-of
                            [(gen/return "(1 2 3)")
                             (gen/return "#=(+ 1 2)")
                             (gen/let [h (gen/fmap str gen-simple-symbol)]
                               (str h "([)"))])]
    (try
      (lang/meme->forms meme-str)
      false
      (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
        (let [data (ex-data e)]
          (and (integer? (:line data))
               (integer? (:col data)))))
      (catch #?(:clj Exception :cljs :default) _
        false))))

;; NOTE: The experimental tokenizer never throws for unterminated strings.
;; Only delimiter-unclosed inputs produce :incomplete.
(defspec prop-cljs-unclosed-is-incomplete 100
  (prop/for-all [meme-str (gen/one-of
                            [(gen/return "f(x")
                             (gen/return "[1 2")])]
    (try
      (lang/meme->forms meme-str)
      false
      (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
        (:incomplete (ex-data e)))
      (catch #?(:clj Exception :cljs :default) _
        false))))

(defspec prop-cljs-canon-formatter-idempotent 100
  (prop/for-all [form gen-form]
    (let [fmt1 (fmt-canon/format-forms [form] {:width 80})
          reparsed (lang/meme->forms fmt1)
          fmt2 (fmt-canon/format-forms reparsed {:width 80})]
      (= fmt1 fmt2))))
