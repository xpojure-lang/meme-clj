(ns meme-lang.api
  "Meme lang composition: lossless pipeline with Pratt parser.

   Pipeline: scanner → trivia-attacher → pratt-parser → cst-reader
   The Pratt parser produces a lossless CST; the CST reader lowers it
   to Clojure forms."
  (:require [meme-lang.stages :as stages]
            [meme.tools.clj.forms :as forms]
            [meme-lang.form-shape :as form-shape]
            [meme-lang.formatter.flat :as fmt-flat]
            [meme-lang.formatter.canon :as fmt-canon]
            [meme.tools.clj.expander :as expander]
            #?(:clj [meme-lang.run :as run])
            #?(:clj [meme-lang.repl :as repl])
            #?(:clj [meme.registry :as registry])))

;; ---------------------------------------------------------------------------
;; Lang API — delegates to composable stages
;; ---------------------------------------------------------------------------

(defn meme->forms
  "Read meme source string. Returns a vector of Clojure forms.
   step-parse → step-read

   Reader conditionals (`#?`, `#?@`) are always returned as
   `MemeReaderConditional` records. To evaluate them for the current
   platform, compose `meme-lang.stages/step-evaluate-reader-conditionals`
   after reading, or use `run-string` / `run-file` (which do so
   automatically). The `:read-cond` option is no longer accepted —
   passing it throws `:meme-lang/deprecated-opt`.

   opts keys:
     :resolve-keyword  — fn to resolve auto-resolve keywords (::kw)
     :resolve-symbol   — fn to resolve symbols in syntax-quote expansion
     :grammar          — custom Pratt grammar spec (advanced)"
  ([s] (meme->forms s nil))
  ([s opts]
   {:pre [(string? s)]}
   (:forms (stages/run s opts))))

(defn forms->meme
  "Print Clojure forms as meme source string (single-line per form).
   Takes a SEQUENCE of forms (vector or seq), not a single form."
  [forms]
  {:pre [(sequential? forms)]}
  (fmt-flat/format-forms forms))

(defn format-meme-forms
  "Format Clojure forms as canonical meme source string (multi-line, indented).

   opts keys:
     :width  — target line width (int, default 80)"
  ([forms] (format-meme-forms forms nil))
  ([forms opts]
   {:pre [(sequential? forms)]}
   (fmt-canon/format-forms forms opts)))

(defn forms->clj
  "Print Clojure forms as Clojure source string with reader sugar."
  [forms]
  (fmt-flat/format-clj (expander/expand-forms forms)))

(defn meme->clj
  "Convert meme source to Clojure source string (lossless by default).

   Reader conditionals are preserved as `#?(...)` in the output rather than
   being evaluated at the current platform — faithful for `.cljc` conversion.
   For the eval-time value, use `run-string` instead.

   opts: same as `meme->forms` (`:resolve-keyword`, `:resolve-symbol`)."
  ([meme-src] (meme->clj meme-src nil))
  ([meme-src opts]
   {:pre [(string? meme-src)]}
   (forms->clj (meme->forms meme-src opts))))

#?(:clj
   (do
     (def ^:private eof-sentinel (Object.))
     (defn- check-depth
       "Walk form and throw if nesting exceeds max-parse-depth.
       Uses `>=` to match `meme.tools.clj.cst-reader/read-node`: both entry points
       reject at exactly `max-parse-depth` levels of nesting."
       [form depth]
       (when (>= depth forms/max-parse-depth)
         (throw (ex-info "Clojure source exceeds maximum nesting depth"
                         {:depth depth})))
       (when (coll? form)
         (run! #(check-depth % (inc depth)) form)))

     (defn clj->forms
       "Read Clojure source string, return a vector of forms.
       JVM/Babashka only — Clojure's reader is needed for full form support."
       [clj-src]
       {:pre [(string? clj-src)]}
       (binding [*read-eval* false]
         (let [rdr (java.io.PushbackReader. (java.io.StringReader. clj-src))
               result (loop [forms []]
                        (let [form (try
                                     (read {:read-cond :preserve :eof eof-sentinel} rdr)
                                     (catch StackOverflowError _
                                       (throw (ex-info "Clojure source exceeds maximum nesting depth"
                                                       {:source (subs clj-src 0 (min 200 (count clj-src)))})))
                                     (catch Exception e
                                       (throw (ex-info (str "Clojure read error: " (ex-message e)) {:source clj-src} e))))]
                          (if (identical? form eof-sentinel)
                            forms
                            (recur (conj forms form)))))]
           (run! #(check-depth % 0) result)
           result)))))
#?(:clj
   (defn clj->meme
     "Convert Clojure source to meme. JVM only."
     [clj-src]
     {:pre [(string? clj-src)]}
     (forms->meme (clj->forms clj-src))))

;; ---------------------------------------------------------------------------
;; Lang commands (for CLI dispatch)
;; ---------------------------------------------------------------------------

(defn format-meme
  "Format meme source text. Reads source, formats via canonical formatter."
  [source opts]
  (let [forms (meme->forms source opts)]
    (if (empty? forms)
      source
      (case (:style opts)
        "flat" (fmt-flat/format-forms forms)
        "clj"  (fmt-flat/format-clj forms)
        (fmt-canon/format-forms forms opts)))))

(defn ^:no-doc to-clj
  "CLI-dispatch adapter: meme source → Clojure text. Library callers should
   use `meme->clj` directly — it has the same lossless behavior."
  ([source] (meme->clj source))
  ([source opts] (meme->clj source opts)))

#?(:clj
   (defn ^:no-doc to-meme
     "CLI-dispatch adapter: Clojure source → meme. JVM only.
      Library callers should use `clj->meme` directly."
     ([source] (clj->meme source))
     ([source _opts] (to-meme source))))

(def lang-map
  "Command map for the meme lang.
   :form-shape is the lang-owned semantic vocabulary — tools (formatter,
   future LSP/lint) consume it to know how this lang decomposes its
   special forms."
  {:extension ".meme"
   :extensions [".memec" ".memej" ".memejs"]
   :format     format-meme
   :to-clj     to-clj
   :form-shape form-shape/registry
   #?@(:clj [:to-meme to-meme
              :run     (fn [source opts] (run/run-string source opts))
              :repl    (fn [opts] (repl/start opts))])})

;; Self-register as a built-in when this ns is loaded on JVM/Babashka.
;; The registry imports no langs; langs register themselves — this keeps
;; the registry pure infrastructure and avoids the old circular dep.
#?(:clj (registry/register-builtin! :meme lang-map))

;; Install meme's string handler for :run — a string value is a prelude
;; .meme path that runs before user source. Keeps this meme convention
;; inside the meme namespace rather than hardcoded in the registry.
#?(:clj
   (registry/register-string-handler! :run
     (fn [prelude-path]
       (fn [source opts]
         (run/run-string (slurp prelude-path) (dissoc opts :prelude :lang))
         (run/run-string source opts)))))
