(ns meme.fuzz.roundtrip
  "Jazzer fuzz targets for meme roundtrip testing.

   Each target is a gen-class with a static fuzzerTestOneInput method.
   Jazzer feeds mutated inputs and uses coverage guidance to explore
   parser paths.

   Build:  clojure -T:build fuzzer-jar
   Run:    java -cp target/fuzzer.jar com.code_intelligence.jazzer.Jazzer \\
             --target_class=meme.fuzz.roundtrip.RoundtripTarget"
  (:require [meme-lang.api :as api]
            [meme-lang.forms :as forms])
  (:import [com.code_intelligence.jazzer.api FuzzedDataProvider]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- strip-internal-meta
  "Remove meme-internal metadata keys so structural equality works."
  [form]
  (if (instance? clojure.lang.IObj form)
    (let [m (meta form)
          cleaned (dissoc m :line :column :file :ws :meme/sugar :meme/ns
                          :meme/meta-chain :meme/order)]
      (if (empty? cleaned)
        (with-meta form nil)
        (with-meta form cleaned)))
    form))

(defn- deep-strip [form]
  (cond
    ;; Unwrap MemeRaw to bare value — notation changes (e.g., bare control
    ;; char → \uHHHH) are acceptable as long as the semantic value is preserved.
    (forms/raw? form) (deep-strip (:value form))
    (forms/deferred-auto-keyword? form) (:raw form)
    ;; Normalize regex to pattern string — java.util.regex.Pattern doesn't
    ;; implement .equals(), so two identical patterns are never =.
    (instance? java.util.regex.Pattern form) (.pattern ^java.util.regex.Pattern form)
    ;; Normalize NaN — IEEE 754 NaN != NaN, use sentinel for comparison.
    (and (number? form) (Double/isNaN (double form))) ::NaN
    (seq? form) (strip-internal-meta (apply list (map deep-strip form)))
    (vector? form) (strip-internal-meta (mapv deep-strip form))
    (map? form) (strip-internal-meta (into {} (map (fn [[k v]] [(deep-strip k) (deep-strip v)])) form))
    (set? form) (strip-internal-meta (into #{} (map deep-strip) form))
    :else (strip-internal-meta form)))

(defn- forms-equal?
  "Compare forms ignoring internal metadata, record types
   (MemeRaw, MemeAutoKeyword), regex identity, and NaN != NaN."
  [a b]
  (= (deep-strip a) (deep-strip b)))

;; ---------------------------------------------------------------------------
;; Target 1: Roundtrip — parse → print → re-parse, assert equivalence
;; ---------------------------------------------------------------------------

(gen-class
  :name meme.fuzz.roundtrip.RoundtripTarget
  :prefix "rt-"
  :methods [^:static [fuzzerTestOneInput [com.code_intelligence.jazzer.api.FuzzedDataProvider] void]])

(defn rt-fuzzerTestOneInput
  [^FuzzedDataProvider data]
  (let [s (.consumeRemainingAsString data)]
    (try
      (let [forms (api/meme->forms s)]
        (when (seq forms)
          (let [printed (api/forms->meme forms)
                reparsed (api/meme->forms printed)]
            (when-not (forms-equal? forms reparsed)
              (throw (AssertionError.
                       (str "Roundtrip mismatch!\n"
                            "  input:    " (pr-str s) "\n"
                            "  forms:    " (pr-str forms) "\n"
                            "  printed:  " (pr-str printed) "\n"
                            "  reparsed: " (pr-str reparsed))))))))
      (catch Exception _ nil)
      (catch StackOverflowError _ nil))))

;; ---------------------------------------------------------------------------
;; Target 2: No-crash — parse arbitrary input, assert no unexpected exceptions
;; ---------------------------------------------------------------------------

(gen-class
  :name meme.fuzz.roundtrip.NoCrashTarget
  :prefix "nc-"
  :methods [^:static [fuzzerTestOneInput [com.code_intelligence.jazzer.api.FuzzedDataProvider] void]])

(defn nc-fuzzerTestOneInput
  [^FuzzedDataProvider data]
  (let [s (.consumeRemainingAsString data)]
    (try
      (api/meme->forms s)
      nil
      (catch Exception _ nil)
      (catch StackOverflowError _ nil))))

;; ---------------------------------------------------------------------------
;; Target 3: Format roundtrip — parse → format → re-parse
;; ---------------------------------------------------------------------------

(gen-class
  :name meme.fuzz.roundtrip.FormatTarget
  :prefix "fmt-"
  :methods [^:static [fuzzerTestOneInput [com.code_intelligence.jazzer.api.FuzzedDataProvider] void]])

(defn fmt-fuzzerTestOneInput
  [^FuzzedDataProvider data]
  (let [s (.consumeRemainingAsString data)]
    (try
      (let [forms (api/meme->forms s)]
        (when (seq forms)
          (let [formatted (api/format-meme-forms forms)
                reparsed (api/meme->forms formatted)]
            (when-not (forms-equal? forms reparsed)
              (throw (AssertionError.
                       (str "Format roundtrip mismatch!\n"
                            "  input:     " (pr-str s) "\n"
                            "  forms:     " (pr-str forms) "\n"
                            "  formatted: " (pr-str formatted) "\n"
                            "  reparsed:  " (pr-str reparsed))))))))
      (catch Exception _ nil)
      (catch StackOverflowError _ nil))))
