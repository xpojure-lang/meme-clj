(ns beme.alpha.core
  "beme public API: read and print beme syntax.

   Three tracks:
     text-to-form:  beme->forms, forms->beme (all platforms)
     form-to-text:  forms->clj (all platforms), clj->forms (JVM only)
     text-to-text:  beme->clj (all platforms), clj->beme (JVM only)

   Pipeline:
     beme.alpha.pipeline/run — full ctx->ctx pipeline with intermediate state"
  (:require [clojure.string :as str]
            [beme.alpha.emit.printer :as printer]
            [beme.alpha.emit.pprint :as pprint]
            [beme.alpha.pipeline :as pipeline]))

;; ---------------------------------------------------------------------------
;; Text-to-form track
;; ---------------------------------------------------------------------------

(defn beme->forms
  "Read beme source string, return a vector of Clojure forms.
   opts map:
     :resolve-keyword — fn that resolves auto-resolve keyword strings (\"::foo\")
                        to keywords at read time. Required on CLJS (errors
                        without it, since cljs.reader cannot resolve :: correctly).
   Note: returns only parsed forms. Use run-pipeline when you need
   access to intermediate pipeline state (raw tokens, grouped tokens)."
  ([s] (:forms (pipeline/run s)))
  ([s opts] (:forms (pipeline/run s opts))))

(defn forms->beme
  "Print Clojure forms as beme source string (single-line per form)."
  [forms]
  (printer/print-beme-string forms))

(defn pprint-beme
  "Pretty-print Clojure forms as beme source string (multi-line, indented).
   opts: {:width 80}"
  ([forms] (pprint/pprint-forms forms))
  ([forms opts] (pprint/pprint-forms forms opts)))

;; ---------------------------------------------------------------------------
;; Form-to-text track
;; ---------------------------------------------------------------------------

(defn forms->clj
  "Print Clojure forms as Clojure source string."
  [forms]
  (str/join "\n\n" (map pr-str forms)))

#?(:clj
(def ^:private eof-sentinel (Object.)))

#?(:clj
(defn clj->forms
  "Read Clojure source string, return a vector of forms.
   JVM/Babashka only — Clojure's reader is needed for full form support."
  [clj-src]
  (let [rdr (java.io.PushbackReader. (java.io.StringReader. clj-src))]
    (loop [forms []]
      (let [form (try
                   (read {:read-cond :preserve :eof eof-sentinel} rdr)
                   (catch Exception e
                     (throw (ex-info (str "Clojure read error: " (ex-message e)) {} e))))]
        (if (identical? form eof-sentinel)
          forms
          (recur (conj forms form))))))))

;; ---------------------------------------------------------------------------
;; Text-to-text track (compositions)
;; ---------------------------------------------------------------------------

(defn beme->clj
  "Convert beme source string to Clojure source string.
   opts map: same as beme->forms (e.g. :resolve-keyword)."
  ([beme-src] (forms->clj (beme->forms beme-src)))
  ([beme-src opts] (forms->clj (beme->forms beme-src opts))))

#?(:clj
(defn clj->beme
  "Convert Clojure source string to beme source string.
   JVM/Babashka only."
  [clj-src]
  (forms->beme (clj->forms clj-src))))

;; ---------------------------------------------------------------------------
;; Pipeline access
;; ---------------------------------------------------------------------------

(defn run-pipeline
  "Run the full pipeline: source → scan → group → parse.
   Returns a context map with :source, :opts, :raw-tokens, :tokens, :forms.
   Useful for tooling that needs intermediate pipeline state."
  ([source] (pipeline/run source))
  ([source opts] (pipeline/run source opts)))

