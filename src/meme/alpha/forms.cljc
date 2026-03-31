(ns meme.alpha.forms
  "Shared form-level predicates and constructors.
   Cross-stage contracts that both the parser and printer depend on."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Deferred auto-resolve keyword encoding
;;
;; When no :resolve-keyword fn is provided (the default on JVM), :: keywords
;; are emitted as (clojure.core/read-string "::foo") — a form that resolves
;; at eval time in the user's namespace. The printer must recognize this
;; encoding to round-trip :: keywords back to "::foo" text.
;; ---------------------------------------------------------------------------

(defn deferred-auto-keyword
  "Wrap a :: keyword string as a deferred eval form.
   Returns (clojure.core/read-string \"::foo\")."
  [raw]
  (list 'clojure.core/read-string raw))

(defn deferred-auto-keyword?
  "Is form a deferred auto-resolve keyword produced by the reader?"
  [form]
  (and (seq? form)
       (= 2 (count form))
       (= 'clojure.core/read-string (first form))
       (let [s (second form)]
         (and (string? s) (str/starts-with? s "::")))))

(defn deferred-auto-keyword-raw
  "Extract the raw :: keyword string from a deferred form.
   Caller must check deferred-auto-keyword? first."
  [form]
  (second form))

;; ---------------------------------------------------------------------------
;; Portable reader conditional support
;;
;; JVM has clojure.lang.ReaderConditional (created by reader-conditional,
;; tested by reader-conditional?). CLJS has no equivalent, so we provide
;; a defrecord polyfill and portable constructor/predicate/accessors.
;; On JVM we use the native type so forms from meme->forms and clj->forms
;; are the same type — critical for roundtrip equality.
;; ---------------------------------------------------------------------------

#?(:cljs
(defrecord MemeReaderConditional [form splicing]))

(defn make-reader-conditional
  "Construct a reader conditional. Portable: uses native type on JVM,
   MemeReaderConditional on CLJS."
  [form splicing?]
  #?(:clj  (reader-conditional form splicing?)
     :cljs (->MemeReaderConditional form splicing?)))

(defn meme-reader-conditional?
  "Is x a reader conditional? Portable across JVM and CLJS."
  [x]
  #?(:clj  (reader-conditional? x)
     :cljs (instance? MemeReaderConditional x)))

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
;; MemeRaw wraps the resolved value alongside the raw source text so the
;; printer can reproduce the original notation. Runtime paths unwrap
;; before eval.
;; ---------------------------------------------------------------------------

(defrecord MemeRaw [value raw])

(defn raw? [x] (instance? MemeRaw x))
(defn raw-value [x] (:value x))
(defn raw-text [x] (:raw x))

(defrecord MemeSyntaxQuote [form])
(defrecord MemeUnquote [form])
(defrecord MemeUnquoteSplicing [form])

(defn syntax-quote?
  "Is x a syntax-quote AST node?"
  [x]
  (instance? MemeSyntaxQuote x))

(defn unquote?
  "Is x an unquote AST node?"
  [x]
  (instance? MemeUnquote x))

(defn unquote-splicing?
  "Is x an unquote-splicing AST node?"
  [x]
  (instance? MemeUnquoteSplicing x))

;; ---------------------------------------------------------------------------
;; Shared metadata keys — internal keys used by the meme pipeline
;;
;; Both printer and formatter need to distinguish user-visible metadata
;; (^:private, ^:tag, ^{:doc "..."}) from internal bookkeeping keys.
;; Centralizing the set here prevents drift between modules.
;; ---------------------------------------------------------------------------

(def internal-meta-keys
  "Metadata keys used internally by the meme pipeline.
   Excluded when checking for user-visible metadata."
  #{:line :col :column :file :ws :meme/sugar :meme/order :meme/ns :meme/meta-chain})

(defn strip-internal-meta
  "Remove internal meme metadata keys, returning only user-visible metadata."
  [m]
  (apply dissoc m internal-meta-keys))

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
             (re-matches #"\d+" (subs n 1)))
        #?(:clj (Long/parseLong (subs n 1))
           :cljs (js/parseInt (subs n 1) 10))
        :else nil))))
