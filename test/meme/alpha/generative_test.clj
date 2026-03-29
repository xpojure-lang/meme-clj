(ns meme.alpha.generative-test
  "Property-based generative tests for the meme reader/printer roundtrip.
   JVM-only (.clj) because test.check is not available on Babashka/CLJS.

   Tests are organized as a combinatorial matrix across three dimensions:
     Dimension 1 — Head type:  symbol, keyword, vector, set, map
     Dimension 2 — Arg type:   primitive, vector, map, set, call, kw-call, kw-nested, deref, quote, var, meta
     Dimension 3 — Arity:      0, 1, 2, 3+
   Plus recursive nesting where args themselves are headed calls."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [meme.alpha.core :as core]
            [meme.alpha.emit.printer :as p]))

;; ---------------------------------------------------------------------------
;; Leaf generators
;; ---------------------------------------------------------------------------

(def safe-symbol-chars "abcdefghijklmnopqrstuvwxyz")
(def symbol-suffix-chars "abcdefghijklmnopqrstuvwxyz0123456789-?!*")

(def reserved-symbols #{'fn 'quote 'var 'clojure.core/deref
                         'nil 'true 'false
                         'begin 'end})

(def gen-simple-symbol
  (gen/let [first-char (gen/elements (seq safe-symbol-chars))
            rest-chars (gen/vector (gen/elements (seq symbol-suffix-chars)) 0 8)]
    (let [sym (symbol (apply str first-char rest-chars))]
      (if (reserved-symbols sym) (symbol (str first-char "x")) sym))))

(def gen-keyword
  (gen/one-of
    [(gen/let [n (gen/not-empty (gen/vector (gen/elements (seq safe-symbol-chars)) 1 8))]
       (keyword (apply str n)))
     (gen/let [ns-chars (gen/not-empty (gen/vector (gen/elements (seq safe-symbol-chars)) 1 5))
               n-chars (gen/not-empty (gen/vector (gen/elements (seq safe-symbol-chars)) 1 5))]
       (keyword (apply str ns-chars) (apply str n-chars)))]))

(def gen-number
  (gen/frequency
    [[5 (gen/one-of [gen/small-integer
                     (gen/fmap long (gen/large-integer* {:min -1000000 :max 1000000}))])]
     [2 (gen/let [n gen/small-integer d (gen/choose 1 99)]
          (+ (double n) (/ d 100.0)))]
     [1 (gen/let [num (gen/such-that #(not= 0 %) gen/small-integer)
                  den (gen/such-that #(pos? %) (gen/fmap #(Math/abs (int %)) gen/small-integer) 100)]
          (/ num (max den 1)))]
     [1 (gen/fmap bigint (gen/large-integer* {:min -10000 :max 10000}))]
     [1 (gen/let [n gen/small-integer d (gen/choose 1 99)]
          (bigdec (+ n (/ d 100.0))))]]))

(def gen-string
  (gen/one-of
    [(gen/fmap #(apply str %) (gen/vector (gen/elements (seq "abcdefghijklmnopqrstuvwxyz 0123456789!?.-_")) 0 20))
     ;; Strings with escape-producing characters (quotes, backslashes, newlines)
     (gen/fmap #(apply str %) (gen/vector (gen/elements [\a \b \c \space \" \\ \newline \tab \return]) 0 10))]))

(def gen-char
  (gen/elements [\a \b \c \d \e \f \g \h \i \j \k \l \m
                 \newline \tab \space \return]))

(def gen-primitive
  (gen/one-of
    [gen-number gen-string gen-keyword gen-simple-symbol
     gen/boolean (gen/return nil) gen-char]))

;; ---------------------------------------------------------------------------
;; Collection generators (non-call)
;; ---------------------------------------------------------------------------

(defn gen-vector-of [g] (gen/vector g 0 5))
(defn gen-map-of [g] (gen/let [kvs (gen/vector (gen/tuple gen-keyword g) 0 4)]
                        (apply array-map (mapcat identity kvs))))
(defn gen-set-of [g] (gen/fmap set (gen/vector g 0 4)))

;; ---------------------------------------------------------------------------
;; Dimension 1 — Head generators
;; ---------------------------------------------------------------------------

(def head-generators
  "Each entry: [name generator]"
  [[:symbol  gen-simple-symbol]
   [:keyword gen-keyword]
   [:vector  (gen/vector gen-simple-symbol 1 3)]
   [:set     (gen/fmap set (gen/not-empty (gen/vector gen-keyword 1 3)))]
   [:map     (gen/let [k gen-keyword v gen-primitive]
               (array-map k v))]])

;; ---------------------------------------------------------------------------
;; Dimension 2 — Arg element generators
;; ---------------------------------------------------------------------------

(defn arg-generators
  "Each entry: [name generator]. Takes a base element generator for recursive nesting."
  [gen-elem]
  [[:primitive gen-primitive]
   [:symbol    gen-simple-symbol]
   [:keyword   gen-keyword]
   [:vector    (gen/vector gen-elem 0 3)]
   [:map       (gen-map-of gen-elem)]
   [:set       (gen-set-of gen-elem)]
   [:call      (gen/let [h gen-simple-symbol as (gen/vector gen-elem 0 3)]
                 (apply list h as))]
   [:kw-call   (gen/let [k gen-keyword a gen-simple-symbol] (list k a))]
   [:kw-nested (gen/let [k1 gen-keyword k2 gen-keyword s gen-simple-symbol]
                 (list k2 (list k1 s)))]
   [:deref     (gen/fmap #(list 'clojure.core/deref %) gen-simple-symbol)]
   [:quote     (gen/fmap #(list 'quote %) gen-simple-symbol)]
   [:var       (gen/fmap #(list 'var %) gen-simple-symbol)]
   [:meta      (gen/let [kw (gen/let [n (gen/not-empty (gen/vector (gen/elements (seq safe-symbol-chars)) 1 4))]
                               (keyword (apply str n)))
                         sym gen-simple-symbol]
                 (with-meta sym {kw true}))]])

;; ---------------------------------------------------------------------------
;; Dimension 3 — Arities
;; ---------------------------------------------------------------------------

(def arities [0 1 2 3])

;; ---------------------------------------------------------------------------
;; Matrix cell generator
;; ---------------------------------------------------------------------------

(defn gen-matrix-cell
  "Generate a single head(args...) form for a specific head × arg-type × arity."
  [gen-head gen-arg arity]
  (gen/let [h gen-head
            args (gen/vector gen-arg arity)]
    (apply list h args)))

;; ---------------------------------------------------------------------------
;; Flat matrix: all head × arg × arity cells, uniform sampling
;; ---------------------------------------------------------------------------

(def gen-flat-matrix
  "Uniformly sample from all head-type × arg-type × arity cells.
   5 heads × 9 arg-types × 4 arities = 180 cells."
  (gen/one-of
    (for [[_hname gen-h] head-generators
          [_aname gen-a] (arg-generators gen-primitive)
          arity arities]
      (gen-matrix-cell gen-h gen-a arity))))

;; ---------------------------------------------------------------------------
;; Nested matrix: args are themselves matrix forms (depth 1)
;; ---------------------------------------------------------------------------

(def gen-nested-matrix
  "Args are themselves headed calls from the flat matrix.
   Tests nesting of all head types inside all other head types."
  (gen/one-of
    (for [[_hname gen-h] head-generators
          arity [1 2 3]]
      (gen-matrix-cell gen-h gen-flat-matrix arity))))

;; ---------------------------------------------------------------------------
;; Full recursive form generator (for mixed/stress tests)
;; ---------------------------------------------------------------------------

(def gen-form
  "Recursive generator mixing all head types, collections, prefixes, and paths."
  (gen/recursive-gen
    (fn [inner]
      (gen/one-of
        [(gen-vector-of inner)
         (gen-map-of inner)
         (gen-set-of inner)
         ;; All head types with recursive args
         (gen/let [h gen-simple-symbol as (gen/vector inner 0 4)]
           (apply list h as))
         (gen/let [k gen-keyword as (gen/vector inner 1 3)]
           (apply list k as))
         (gen/let [v (gen/vector gen-simple-symbol 1 3) as (gen/vector inner 1 3)]
           (apply list v as))
         (gen/let [s (gen/fmap set (gen/not-empty (gen/vector gen-keyword 1 3))) a inner]
           (list s a))
         (gen/let [m (gen/let [k gen-keyword v gen-primitive] (array-map k v)) a inner]
           (list m a))
         ;; Prefix forms
         (gen/fmap #(list 'clojure.core/deref %) gen-simple-symbol)
         (gen/fmap #(list 'quote %) gen-simple-symbol)
         (gen/fmap #(list 'var %) gen-simple-symbol)
         ;; Keyword-headed nested calls
         (gen/let [k gen-keyword s gen-simple-symbol] (list k s))
         (gen/let [k1 gen-keyword k2 gen-keyword s gen-simple-symbol]
           (list k2 (list k1 s)))
         ;; Metadata
         (gen/let [kw (gen/let [n (gen/not-empty (gen/vector (gen/elements (seq safe-symbol-chars)) 1 6))]
                        (keyword (apply str n)))
                   sym gen-simple-symbol]
           (with-meta sym {kw true}))]))
    gen-primitive))

;; ---------------------------------------------------------------------------
;; Roundtrip helper
;; ---------------------------------------------------------------------------

(defn roundtrip-ok? [form]
  (try
    (let [printed (p/print-meme-string [form])
          read-back (core/meme->forms printed)]
      (= [form] read-back))
    (catch Exception e
      (println "Roundtrip failed for form:" (pr-str form))
      (println "Error:" (.getMessage e))
      false)))

;; ===========================================================================
;; Properties — Data structure roundtrip (no call syntax)
;; ===========================================================================

(defspec prop-primitive-roundtrip 500
  (prop/for-all [form gen-primitive]
    (roundtrip-ok? form)))

(defspec prop-vector-roundtrip 300
  (prop/for-all [v (gen/vector gen-primitive 0 6)]
    (roundtrip-ok? v)))

(defspec prop-map-roundtrip 300
  (prop/for-all [m (gen-map-of gen-primitive)]
    (roundtrip-ok? m)))

(defspec prop-set-roundtrip 300
  (prop/for-all [s (gen/fmap set (gen/vector gen-primitive 0 5))]
    (roundtrip-ok? s)))

(defspec prop-nested-collection-roundtrip 300
  (prop/for-all [form (gen/recursive-gen
                         (fn [inner]
                           (gen/one-of
                             [(gen/vector inner 0 4)
                              (gen-map-of inner)
                              (gen/fmap set (gen/vector inner 0 3))]))
                         gen-primitive)]
    (roundtrip-ok? form)))

;; ===========================================================================
;; Properties — Prefix and keyword-nested roundtrip
;; ===========================================================================

(defspec prop-prefix-roundtrip 300
  (prop/for-all [form (gen/one-of
                         [(gen/fmap #(list 'clojure.core/deref %) gen-simple-symbol)
                          (gen/fmap #(list 'quote %) gen-simple-symbol)
                          (gen/fmap #(list 'var %) gen-simple-symbol)])]
    (roundtrip-ok? form)))

(defspec prop-metadata-roundtrip 300
  (prop/for-all [form (gen/let [kw (gen/let [n (gen/not-empty (gen/vector (gen/elements (seq safe-symbol-chars)) 1 6))]
                                     (keyword (apply str n)))
                                sym gen-simple-symbol]
                         (with-meta sym {kw true}))]
    (let [printed (p/print-meme-string [form])
          read-back (first (core/meme->forms printed))]
      (and (= form read-back)
           (= (meta form) (dissoc (meta read-back) :ws))))))

(defspec prop-keyword-nested-roundtrip 300
  (prop/for-all [form (gen/let [k1 gen-keyword k2 gen-keyword s gen-simple-symbol]
                        (list k2 (list k1 s)))]
    (roundtrip-ok? form)))

;; ===========================================================================
;; Properties — Combinatorial matrix: head × arg × arity
;; ===========================================================================

(defspec prop-matrix-flat 500
  (prop/for-all [form gen-flat-matrix]
    (roundtrip-ok? form)))

(defspec prop-matrix-nested 500
  (prop/for-all [form gen-nested-matrix]
    (roundtrip-ok? form)))

;; ===========================================================================
;; Properties — Full recursive stress test
;; ===========================================================================

(defspec prop-mixed-form-roundtrip 500
  (prop/for-all [form gen-form]
    (roundtrip-ok? form)))

(defspec prop-print-produces-valid-meme 300
  (prop/for-all [form gen-form]
    (try
      (let [printed (p/print-meme-string [form])]
        (core/meme->forms printed)
        true)
      (catch Exception _ false))))

;; ===========================================================================
;; Property — #_ discard: discarded forms don't affect output
;; ===========================================================================

(defspec prop-discard-transparent 200
  (prop/for-all [form gen-form
                 discard-form gen-primitive]
    (try
      (let [;; Print form normally, then print with #_ discard before it
            printed (p/print-meme-string [form])
            discard-printed (str "#_" (p/print-meme-string [discard-form]) " " printed)
            read-normal (core/meme->forms printed)
            read-discard (core/meme->forms discard-printed)]
        (= read-normal read-discard))
      (catch Exception _ false))))

;; ===========================================================================
;; Property — Syntax-quote parses without error (no roundtrip — Clojure expands)
;; ===========================================================================

(def gen-syntax-quote-meme
  "Generate meme strings containing syntax-quote forms."
  (gen/let [name gen-simple-symbol
            args (gen/vector gen-simple-symbol 1 3)
            body-syms (gen/vector gen-simple-symbol 1 4)]
    (str "defmacro(" name " [" (str/join " " (map str args)) "] "
         "`(" (str/join " " (map str body-syms)) "))")))

(defspec prop-syntax-quote-parses 200
  (prop/for-all [meme-str gen-syntax-quote-meme]
    (try
      (core/meme->forms meme-str)
      true
      (catch Exception _ false))))

(def gen-syntax-quote-with-unquote
  "Generate meme strings with syntax-quote containing ~ unquotes."
  (gen/let [name gen-simple-symbol
            param gen-simple-symbol
            body-head gen-simple-symbol]
    (str "defmacro(" name " [" param "] "
         "`(" body-head " ~" param "))")))

(defspec prop-syntax-quote-with-unquote-parses 200
  (prop/for-all [meme-str gen-syntax-quote-with-unquote]
    (try
      (core/meme->forms meme-str)
      true
      (catch Exception _ false))))

;; ===========================================================================
;; Property — #() anonymous functions with % params roundtrip
;; ===========================================================================

(def gen-anon-fn-form
  "Generate (fn [%1 ...] body) forms where body uses the declared params."
  (gen/let [arity (gen/choose 1 3)
            body-head gen-simple-symbol]
    (let [params (mapv #(symbol (str "%" (inc %))) (range arity))
          ;; Build a body that uses all declared params
          body (apply list body-head params)]
      (list 'fn params body))))

(defspec prop-anon-fn-percent-roundtrip 100
  (prop/for-all [form gen-anon-fn-form]
    (try
      (let [printed (p/print-form form)
            read-back (first (core/meme->forms printed))]
        (= form read-back))
      (catch Exception e
        (println "Anon fn roundtrip failed for:" (pr-str form))
        (println "Error:" (.getMessage e))
        false))))
