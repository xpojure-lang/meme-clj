(ns meme-lang.api
  "Meme lang composition: lossless pipeline with Pratt parser.

   Pipeline: scanner → trivia-attacher → pratt-parser → cst-reader
   The Pratt parser produces a lossless CST; the CST reader lowers it
   to Clojure forms."
  (:require [meme-lang.stages :as stages]
            [meme-lang.formatter.flat :as fmt-flat]
            [meme-lang.formatter.canon :as fmt-canon]
            [meme-lang.expander :as expander]
            #?(:clj [meme-lang.run :as run])
            #?(:clj [meme-lang.repl :as repl])))

;; ---------------------------------------------------------------------------
;; Lang API — delegates to composable stages
;; ---------------------------------------------------------------------------

(defn meme->forms
  "Read meme source string. Returns a vector of Clojure forms.
   step-parse → step-read"
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
   opts: {:width 80}"
  ([forms] (format-meme-forms forms nil))
  ([forms opts]
   {:pre [(sequential? forms)]}
   (fmt-canon/format-forms forms opts)))

(defn forms->clj
  "Print Clojure forms as Clojure source string with reader sugar."
  [forms]
  (fmt-flat/format-clj (expander/expand-forms forms)))

(defn meme->clj
  "Convert meme source to Clojure source string."
  ([meme-src] (meme->clj meme-src nil))
  ([meme-src opts]
   {:pre [(string? meme-src)]}
   (forms->clj (meme->forms meme-src opts))))

#?(:clj
   (do
     (def ^:private eof-sentinel (Object.))
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
                          (catch StackOverflowError _
                            (throw (ex-info "Clojure source exceeds maximum nesting depth"
                                            {:source (subs clj-src 0 (min 200 (count clj-src)))})))
                          (catch Exception e
                            (throw (ex-info (str "Clojure read error: " (ex-message e)) {:source clj-src} e))))]
               (if (identical? form eof-sentinel)
                 forms
                 (recur (conj forms form))))))))))
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
  (let [read-opts (assoc opts :read-cond :preserve)
        forms (meme->forms source read-opts)]
    (if (empty? forms)
      source
      (case (:style opts)
        "flat" (fmt-flat/format-forms forms)
        "clj"  (fmt-flat/format-clj forms)
        (fmt-canon/format-forms forms opts)))))

(defn to-clj
  "Convert meme source to Clojure text with reader conditionals preserved."
  ([source] (meme->clj source {:read-cond :preserve}))
  ([source _opts] (to-clj source)))

#?(:clj
   (defn to-meme
     "Convert Clojure source text to meme syntax. JVM only."
     ([source] (clj->meme source))
     ([source _opts] (to-meme source))))

(def lang-map
  "Command map for the meme lang."
  {:extension ".meme"
   :extensions [".memec" ".memej" ".memejs"]
   :format  format-meme
   :to-clj  to-clj
   #?@(:clj [:to-meme to-meme
              :run     (fn [source opts] (run/run-string source opts))
              :repl    (fn [opts] (repl/start opts))])})
