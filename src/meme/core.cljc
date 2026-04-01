(ns meme.core
  "meme public API: read and print meme syntax.

   Three tracks:
     text-to-form:  meme->forms, forms->meme (all platforms)
     form-to-text:  forms->clj (all platforms), clj->forms (JVM only)
     text-to-text:  meme->clj (all platforms), clj->meme (JVM only)

   Stages:
     meme.stages/run — full ctx->ctx stages with intermediate state"
  (:require [meme.emit.formatter.flat :as fmt-flat]
            [meme.emit.formatter.canon :as fmt-canon]
            [meme.parse.expander :as expander]
            [meme.stages :as stages]))

;; ---------------------------------------------------------------------------
;; Text-to-form track
;; ---------------------------------------------------------------------------

(defn meme->forms
  "Read meme source string, return a vector of Clojure forms.
   opts map:
     :resolve-keyword — fn that resolves auto-resolve keyword strings (\"::foo\")
                        to keywords at read time. Required on CLJS (errors
                        without it, since cljs.reader cannot resolve :: correctly).
     :read-cond       — :preserve to return ReaderConditional objects instead of
                        evaluating. Default: evaluate for current platform.
   Note: returns only parsed forms. Use run-stages when you need
   access to intermediate state (raw tokens, tokens, or forms)."
  ([s] (:forms (stages/run s)))
  ([s opts] (:forms (stages/run s opts))))

(defn forms->meme
  "Print Clojure forms as meme source string (single-line per form)."
  [forms]
  (fmt-flat/format-forms forms))

(defn format-meme
  "Format Clojure forms as canonical meme source string (multi-line, indented).
   opts: {:width 80}"
  ([forms] (fmt-canon/format-forms forms))
  ([forms opts] (fmt-canon/format-forms forms opts)))

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
     (binding [*read-eval* false]
       (let [rdr (java.io.PushbackReader. (java.io.StringReader. clj-src))]
         (loop [forms []]
           (let [form (try
                        (read {:read-cond :preserve :eof eof-sentinel} rdr)
                        (catch Exception e
                          (throw (ex-info (str "Clojure read error: " (ex-message e)) {} e))))]
             (if (identical? form eof-sentinel)
               forms
               (recur (conj forms form)))))))))

;; ---------------------------------------------------------------------------
;; Text-to-text track (compositions)
;; ---------------------------------------------------------------------------

(defn meme->clj
  "Convert meme source string to Clojure source string.
   opts map: same as meme->forms (e.g. :resolve-keyword)."
  ([meme-src] (forms->clj (meme->forms meme-src)))
  ([meme-src opts] (forms->clj (meme->forms meme-src opts))))

#?(:clj
   (defn clj->meme
     "Convert Clojure source string to meme source string.
   JVM/Babashka only."
     [clj-src]
     (forms->meme (clj->forms clj-src))))

;; ---------------------------------------------------------------------------
;; Stage access
;; ---------------------------------------------------------------------------

(defn run-stages
  "Run the full stage pipeline: source → scan → parse.
   Returns a context map with :source, :opts, :raw-tokens, :tokens, :forms.
   Useful for tooling that needs intermediate state."
  ([source] (stages/run source))
  ([source opts] (stages/run source opts)))
