(ns beme.core
  "beme public API: read and print beme syntax.

   Three tracks:
     text-to-form:  beme->forms, forms->beme (all platforms)
     form-to-text:  forms->clj (all platforms), clj->forms (JVM only)
     text-to-text:  beme->clj (all platforms), clj->beme (JVM only)

   Pipeline:
     beme.pipeline/run — full ctx->ctx pipeline with intermediate state"
  (:require [clojure.string :as str]
            [beme.reader :as reader]
            [beme.printer :as printer]
            [beme.pipeline :as pipeline]))

;; ---------------------------------------------------------------------------
;; Text-to-form track
;; ---------------------------------------------------------------------------

(defn beme->forms
  "Read beme source string, return a vector of Clojure forms.
   opts map:
     :resolve-keyword — fn that resolves auto-resolve keyword strings (\"::foo\")
                        to keywords at read time. Required on CLJS (errors
                        without it, since cljs.reader cannot resolve :: correctly).
   Note: does not attach whitespace metadata to tokens. Use run-pipeline
   when :ws fields are needed on intermediate token state."
  ([s] (reader/read-beme-string s))
  ([s opts] (reader/read-beme-string s opts)))

(defn forms->beme
  "Print Clojure forms as beme source string."
  [forms]
  (printer/print-beme-string forms))

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
   Returns a context map with :source, :raw-tokens, :tokens, :forms.
   Useful for tooling that needs intermediate pipeline state."
  ([source] (pipeline/run source))
  ([source opts] (pipeline/run source opts)))

;; ---------------------------------------------------------------------------
;; Deprecated aliases (backward compatibility)
;; ---------------------------------------------------------------------------

(defn ^:deprecated read-beme-string
  "Deprecated: use beme->forms."
  ([s] (beme->forms s))
  ([s opts] (beme->forms s opts)))

(defn ^:deprecated print-beme-string
  "Deprecated: use forms->beme."
  [forms]
  (forms->beme forms))

(defn ^:deprecated clj-string->beme
  "Deprecated: use clj->beme."
  [clj-src]
  #?(:clj (clj->beme clj-src)
     :cljs (throw (ex-info "clj-string->beme is JVM only" {}))))
