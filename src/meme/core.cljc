(ns meme.core
  "meme public API: read and print meme syntax.

   Three tracks:
     text-to-form:  meme->forms, forms->meme (all platforms)
     form-to-text:  forms->clj (all platforms), clj->forms (JVM only)
     text-to-text:  meme->clj (all platforms), clj->meme (JVM only)

   Stages:
     meme.stages/run — scan + parse stages with intermediate state (no expand/rewrite)"
  (:require [meme.emit.formatter.flat :as fmt-flat]
            [meme.emit.formatter.canon :as fmt-canon]
            [meme.parse.expander :as expander]
            [meme.stages :as stages]))

;; ---------------------------------------------------------------------------
;; Text-to-form track
;; ---------------------------------------------------------------------------

(defn meme->forms
  "Read meme source string, return a vector of parsed forms.
   The returned forms may contain meme-internal AST record types
   (MemeRaw, MemeAutoKeyword, MemeSyntaxQuote) that preserve notation.
   Use forms->meme to print them, or expand-forms to get plain Clojure values.
   opts map:
     :resolve-keyword — fn that resolves auto-resolve keyword strings (\"::foo\")
                        to keywords at read time. Required on CLJS (errors
                        without it, since cljs.reader cannot resolve :: correctly).
     :read-cond       — :preserve to return ReaderConditional objects instead of
                        evaluating. Default: evaluate for current platform.
   Note: returns only parsed forms. Use stages/run when you need
   access to intermediate state (raw tokens, tokens, or forms)."
  ([s] (meme->forms s nil))
  ([s opts]
   {:pre [(string? s)]}
   (:forms (stages/run s opts))))

(defn forms->meme
  "Print Clojure forms as meme source string (single-line per form).
   Takes a SEQUENCE of forms (vector or seq), not a single form.
   To print a single form, wrap it: (forms->meme [my-form])."
  [forms]
  {:pre [(sequential? forms)]}
  (fmt-flat/format-forms forms))

(defn format-meme
  "Format Clojure forms as canonical meme source string (multi-line, indented).
   opts: {:width 80}"
  ([forms] (format-meme forms nil))
  ([forms opts]
   {:pre [(sequential? forms)]}
   (fmt-canon/format-forms forms opts)))

;; ---------------------------------------------------------------------------
;; Form-to-text track
;; ---------------------------------------------------------------------------

(defn forms->clj
  "Print Clojure forms as Clojure source string with reader sugar.
   Expands syntax-quote AST nodes since Clojure has no backtick form."
  [forms]
  (fmt-flat/format-clj (expander/expand-forms forms)))

#?(:clj
   (def ^:private eof-sentinel (Object.)))

#?(:clj
   (defn clj->forms
     "Read Clojure source string, return a vector of forms.
     JVM/Babashka only — Clojure's reader is needed for full form support."
     [clj-src]
     {:pre [(string? clj-src)]}
     (binding [*read-eval* false]
       (let [rdr (java.io.PushbackReader. (java.io.StringReader. clj-src))]
         (loop [forms []]
           (let [form (try
                        (read {:read-cond :preserve :eof eof-sentinel} rdr)
                        (catch Exception e
                          (throw (ex-info (str "Clojure read error: " (ex-message e)) {:source clj-src} e))))]
             (if (identical? form eof-sentinel)
               forms
               (recur (conj forms form)))))))))

;; ---------------------------------------------------------------------------
;; Text-to-text track (compositions)
;; ---------------------------------------------------------------------------

(defn meme->clj
  "Convert meme source string to Clojure source string.
   opts map: same as meme->forms (e.g. :resolve-keyword)."
  ([meme-src] (meme->clj meme-src nil))
  ([meme-src opts]
   {:pre [(string? meme-src)]}
   (forms->clj (meme->forms meme-src opts))))

#?(:clj
   (defn clj->meme
     "Convert Clojure source string to meme source string.
     JVM/Babashka only."
     [clj-src]
     {:pre [(string? clj-src)]}
     (forms->meme (clj->forms clj-src))))

;; RT3-F40: expose run-stages in public API (documented in api.md but was missing)
(defn run-stages
  "Run the reader pipeline, returning the full context map with intermediate state.
   Useful for tooling that needs access to :raw-tokens, :tokens, or :forms.
   opts map: same as meme->forms."
  ([source] (stages/run source))
  ([source opts] (stages/run source opts)))
