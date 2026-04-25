(ns meme.tools.clj.forms
  "Clojure-surface AST records and cross-stage form-level predicates.

   Contains the deferred-evaluation wrappers (CljSyntaxQuote, CljUnquote,
   CljUnquoteSplicing, CljRaw, CljAutoKeyword, CljReaderConditional)
   that the reader produces for forms that don't round-trip through plain
   Clojure data, plus anonymous-function helpers and the shared metadata
   key vocabulary (`:meme/leading-trivia`, `:meme/sugar`,
   `:meme/insertion-order`, `:meme/namespace-prefix`, `:meme/meta-chain`,
   `:meme/bare-percent`) used by reader, stages, and printer."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Deferred auto-resolve keyword encoding
;;
;; When no :resolve-keyword fn is provided (the default on JVM), :: keywords
;; are emitted as (clojure.core/read-string "::foo") — a form that resolves
;; at eval time in the user's namespace. The printer must recognize this
;; encoding to round-trip :: keywords back to "::foo" text.
;; ---------------------------------------------------------------------------

(defrecord CljAutoKeyword [raw])

(defn deferred-auto-keyword
  "Wrap a :: keyword string as a deferred eval form.
   Returns a CljAutoKeyword record that the printer can recognize
   unambiguously and the expander converts to (clojure.core/read-string \"::foo\")
   before eval."
  [raw]
  (->CljAutoKeyword raw))

(defn deferred-auto-keyword?
  "Is form a deferred auto-resolve keyword produced by the reader?"
  [form]
  (instance? CljAutoKeyword form))

(defn deferred-auto-keyword-raw
  "Extract the raw :: keyword string from a deferred form.
   Caller must check deferred-auto-keyword? first."
  [form]
  (:raw form))

(defn deferred-auto-keyword->form
  "Convert a CljAutoKeyword to an eval-able list form.
   C65: use platform-appropriate read-string (clojure.core on JVM, cljs.reader on CLJS)."
  [^CljAutoKeyword ak]
  (list #?(:clj 'clojure.core/read-string
           :cljs 'cljs.reader/read-string)
        (:raw ak)))

;; ---------------------------------------------------------------------------
;; Portable reader conditional support
;;
;; JVM has clojure.lang.ReaderConditional (created by reader-conditional,
;; tested by reader-conditional?). CLJS has no equivalent, so we provide
;; a defrecord polyfill and portable constructor/predicate/accessors.
;; On JVM we use the native type so forms from mclj->forms and clj->forms
;; are the same type — critical for roundtrip equality.
;; ---------------------------------------------------------------------------

#?(:cljs
   (defrecord CljReaderConditional [form splicing]))

(defn make-reader-conditional
  "Construct a reader conditional. Portable: uses native type on JVM,
   CljReaderConditional on CLJS."
  [form splicing?]
  #?(:clj  (reader-conditional form splicing?)
     :cljs (->CljReaderConditional form splicing?)))

(defn clj-reader-conditional?
  "Is x a reader conditional? Portable across JVM and CLJS."
  [x]
  #?(:clj  (reader-conditional? x)
     :cljs (instance? CljReaderConditional x)))

(defn rc-form
  "Get the form list from a reader conditional."
  [rc]
  #?(:clj  (.-form ^clojure.lang.ReaderConditional rc)
     :cljs (:form rc)))

(defn rc-splicing?
  "Is this a splicing reader conditional (#?@)?"
  [rc]
  #?(:clj  (.-splicing ^clojure.lang.ReaderConditional rc)
     :cljs (:splicing rc)))

;; ---------------------------------------------------------------------------
;; Syntax-quote / unquote / unquote-splicing AST nodes
;;
;; These preserve syntax-quote structure through the pipeline instead of
;; eagerly expanding into seq/concat/list. The printer reconstructs
;; backtick/tilde syntax from these nodes. Runtime paths (run, repl)
;; expand them before eval.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Raw value wrapper — preserves source notation for types without metadata
;;
;; Numbers (0xFF, 010, 1e2), characters (\u0041, \o101), and strings
;; ("hello \u0041") are primitive types that can't carry metadata.
;; CljRaw wraps the resolved value alongside the raw source text so the
;; printer can reproduce the original notation. Runtime paths unwrap
;; before eval.
;; ---------------------------------------------------------------------------

(defrecord CljRaw [value raw])

(defn raw?
  "True if x is a CljRaw wrapper (preserves alternate notation for roundtrip)."
  [x] (instance? CljRaw x))

(defrecord CljSyntaxQuote [form])
(defrecord CljUnquote [form])
(defrecord CljUnquoteSplicing [form])

(defn syntax-quote?
  "Is x a syntax-quote AST node?"
  [x]
  (instance? CljSyntaxQuote x))

(defn unquote?
  "Is x an unquote AST node?"
  [x]
  (instance? CljUnquote x))

(defn unquote-splicing?
  "Is x an unquote-splicing AST node?"
  [x]
  (instance? CljUnquoteSplicing x))

;; ---------------------------------------------------------------------------
;; Shared metadata keys — internal keys used by the meme pipeline
;;
;; Both printer and formatter need to distinguish user-visible metadata
;; (^:private, ^:tag, ^{:doc "..."}) from internal bookkeeping keys.
;; Centralizing the set here prevents drift between modules.
;; ---------------------------------------------------------------------------

(def ^:const max-parse-depth
  "Maximum nesting depth for both the recursive-descent parser and the
   rewrite tree builder. Guards against stack overflow on deeply nested input."
  512)

(def internal-meta-keys
  "Metadata keys used internally by the meme pipeline.
   Excluded when checking for user-visible metadata.
   Both :col (meme tokenizer) and :column (Clojure reader) are included
   so stripping works regardless of which reader produced the metadata."
  #{:line :col :column :file :meme/leading-trivia :meme/sugar :meme/insertion-order :meme/namespace-prefix :meme/meta-chain :meme/bare-percent})

(def notation-meta-keys
  "Internal metadata keys that encode the user's notation choices.
   Must survive metadata stripping so the printer can reconstruct
   the original syntax (e.g. #:ns{} maps, set insertion order, quote sugar)."
  #{:meme/namespace-prefix :meme/insertion-order :meme/sugar :meme/bare-percent})

(defn strip-internal-meta
  "Remove internal meme metadata keys, returning only user-visible metadata."
  [m]
  (apply dissoc m internal-meta-keys))

;; ---------------------------------------------------------------------------
;; Set-with-source-order helpers
;;
;; Sets carry source order in :meme/insertion-order metadata so the printer
;; can reproduce the user's written order. Walkers that touch a set must
;; iterate in source order AND refresh the metadata from the walked elements,
;; otherwise the order vector and the set's contents diverge silently — the
;; printer's count-match guard then either renders stale entries (e.g. an
;; unexpanded form that the walker actually replaced) or falls back to hash
;; order. Three independent walkers had this exact bug; the helpers below
;; consolidate the pattern so future walkers can't reinvent the foot-gun.
;; ---------------------------------------------------------------------------

(defn meme-set-source-seq
  "Return the elements of set `s` in source order: the :meme/insertion-order
   vector when present and consistent with the set, otherwise the set's own
   iteration order. Use this anywhere you need to walk a set that came from
   the meme reader and care about the user's written ordering."
  [s]
  (let [order (:meme/insertion-order (meta s))]
    (if (and order (= (count order) (count s))) order s)))

(defn with-refreshed-set-order
  "Attach a fresh :meme/insertion-order to set `s` derived from the
   `walked-elements` collection (the elements just inserted). Use this when
   the set you're returning has different contents from the one you started
   with — e.g. expand-syntax-quotes replacing a CljSyntaxQuote with its
   expanded form. Distinct-dedups the order vector to match set semantics."
  [s walked-elements]
  (vary-meta s assoc :meme/insertion-order (vec (distinct walked-elements))))

(defn walk-meme-set
  "Walk a meme set element-by-element via `f` (one-in, one-out), preserving
   source order on input and refreshing :meme/insertion-order on output so
   it stays consistent with the new contents. The right helper for a walker
   that transforms each element to exactly one element. For multi-out walkers
   (e.g. #?@ splice), use meme-set-source-seq + with-refreshed-set-order
   directly with mapcat."
  [s f]
  (let [walked (mapv f (meme-set-source-seq s))]
    (-> (set walked)
        (with-meta (meta s))
        (with-refreshed-set-order walked))))

;; ---------------------------------------------------------------------------
;; Shared % parameter utilities
;;
;; Both the reader (#() → fn) and printer (fn → #()) need to identify
;; % parameter symbols. Centralizing here prevents logic drift.
;; ---------------------------------------------------------------------------

(defn percent-param-type
  "If sym is a % parameter symbol, return its type: :bare, :rest, or the integer N.
   Returns nil otherwise."
  [sym]
  (when (symbol? sym)
    (let [n (name sym)]
      (cond
        (= n "%") :bare
        (= n "%&") :rest
        (and (str/starts-with? n "%")
             (> (count n) 1)
             (<= (count n) 12)  ;; host platform limit: prevents Long/parseInt overflow
             (re-matches #"0*[1-9]\d*" (subs n 1)))
        (let [v #?(:clj (Long/parseLong (subs n 1))
                   :cljs (js/parseInt (subs n 1) 10))]
          (when (<= v 20) v))  ;; Clojure limits anon fn params to %1..%20
        :else nil))))

;; ---------------------------------------------------------------------------
;; Shared anonymous function parameter utilities
;;
;; find-percent-params, normalize-bare-percent, and build-anon-fn-params are
;; used by both the parser (#() → fn) and the rewrite engine (tree → forms).
;; Centralizing here prevents behavioral divergence between the two paths.
;; ---------------------------------------------------------------------------

(defn invalid-percent-symbol?
  "Return true if sym looks like a % param but isn't valid.
   RT3-F13: catches %-1, %foo, %1a, etc. Clojure rejects these."
  [sym]
  (when (symbol? sym)
    (let [n (name sym)]
      (and (str/starts-with? n "%")
           (> (count n) 1)
           (not= n "%&")
           (nil? (percent-param-type sym))))))

(defn walk-anon-fn-body
  "Generic walker over anonymous function body forms. Skips nested (fn ...) bodies.
   Handles AST node types (raw, syntax-quote, unquote, unquote-splicing), maps,
   sets, tagged literals, seqs, and vectors.

   `f` is called on every non-container form and returns the walked result.
   For containers, the walker recurses and rebuilds the structure.
   Use this to avoid duplicating the form-type dispatch across multiple walkers."
  [f form]
  (cond
    ;; Nested fn boundary — don't recurse
    (and (seq? form) (= 'fn (first form))) (f form)
    ;; Sequential containers
    (seq? form) (with-meta (apply list (map #(walk-anon-fn-body f %) form)) (meta form))
    (vector? form) (with-meta (mapv #(walk-anon-fn-body f %) form) (meta form))
    ;; AST nodes — before map? since defrecords satisfy map?
    (raw? form) (f form)
    (syntax-quote? form) (->CljSyntaxQuote (walk-anon-fn-body f (:form form)))
    (unquote? form) (->CljUnquote (walk-anon-fn-body f (:form form)))
    (unquote-splicing? form) (->CljUnquoteSplicing (walk-anon-fn-body f (:form form)))
    ;; Maps and sets
    (map? form) (with-meta
                  (into {} (map (fn [[k v]] [(walk-anon-fn-body f k) (walk-anon-fn-body f v)]) form))
                  (meta form))
    (set? form) (walk-meme-set form #(walk-anon-fn-body f %))
    ;; Tagged literals (JVM only)
    #?@(:clj [(tagged-literal? form)
              (tagged-literal (.-tag form) (walk-anon-fn-body f (.-form form)))])
    ;; Leaf
    :else (f form)))

(defn find-percent-params
  "Walk form collecting % param types. Skips nested (fn ...) bodies."
  [form]
  (let [result (volatile! #{})]
    (walk-anon-fn-body
     (fn [f]
       (when-let [p (percent-param-type f)] (vswap! result conj p))
       f)
     form)
    @result))

(defn find-invalid-percent-symbols
  "Walk form finding first symbol that looks like a % param but isn't valid.
   RT3-F13: %-1, %foo, etc. Returns first found or nil."
  [form]
  (let [found (volatile! nil)]
    (walk-anon-fn-body
     (fn [f]
       (when (and (nil? @found) (invalid-percent-symbol? f))
         (vreset! found f))
       f)
     form)
    @found))

(defn normalize-bare-percent
  "Replace bare % with %1 in form. Skips nested (fn ...) bodies."
  [form]
  (walk-anon-fn-body
   (fn [f]
     (if (and (symbol? f) (= "%" (name f))) (symbol "%1") f))
   form))

(defn restore-bare-percent
  "Replace %1 with % in a form tree. Used when :meme/bare-percent metadata
   indicates the user wrote bare % (normalized to %1 for eval correctness).
   Skips nested (fn ...) bodies — matches normalize-bare-percent guard."
  [form]
  (walk-anon-fn-body
   (fn [f]
     (if (and (symbol? f) (= (name f) "%1") (nil? (namespace f))) (symbol "%") f))
   form))

(defn build-anon-fn-params
  "Build [%1 %2 ...] or [%1 & %&] param vector from collected param types."
  [param-set]
  (let [has-bare? (contains? param-set :bare)
        has-rest? (contains? param-set :rest)
        nums (filter number? param-set)
        max-n (if (seq nums) (apply max nums) (if has-bare? 1 0))]
    (cond-> (mapv #(symbol (str "%" %)) (range 1 (inc max-n)))
      has-rest? (into ['& (symbol "%&")]))))
