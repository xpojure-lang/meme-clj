(ns meme.fuzz.roundtrip
  "Jazzer fuzz targets for meme roundtrip testing.

   Targets:
   - RoundtripTarget:        parse → print → re-parse equivalence + no raw JVM exceptions
   - FormatTarget:           parse → format → re-parse equivalence
   - IdempotentTarget:       format(format(x)) == format(x)
   - FormsToCljTarget:       parse → forms->clj → tools.reader → re-parse equivalence
                             (catches expand-forms / forms->clj divergences, e.g. bug #2)
   - NativeCljTarget:        clj-parse → forms->clj → re-parse-as-clj equivalence
                             (mirrors RoundtripTarget for the native Clojure surface)
   - CrossCheckTarget:       clojure.core/read-string vs native parser, normalised
                             (promotes the vendor cross-check ratchet to a property)
   - CljModeIdempotentTarget: format-clj(format-clj(x)) == format-clj(x)

   Build:  clojure -T:build fuzzer-jar
   Run:    bb fuzz roundtrip          (default)
           bb fuzz format
           bb fuzz idempotent
           bb fuzz forms-to-clj
           bb fuzz native-clj
           bb fuzz cross-check
           bb fuzz clj-idempotent"
  (:require [m1clj-lang.api :as api]
            [clj-lang.api :as clj-api]
            [meme.tools.clj.expander :as expander]
            [meme.tools.clj.forms :as forms]
            [meme.tools.clj.parser.api :as clj-parser]
            [meme.tools.clj.run :as clj-run]
            [meme.test-util :as tu])
  (:import [com.code_intelligence.jazzer.api FuzzedDataProvider]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- strip-internal-meta
  "Remove meme-internal metadata keys so structural equality works.
   Delegates to forms/strip-internal-meta so the key set stays in sync
   as the pipeline evolves."
  [form]
  (if (instance? clojure.lang.IObj form)
    (let [cleaned (forms/strip-internal-meta (meta form))]
      (if (empty? cleaned)
        (with-meta form nil)
        (with-meta form cleaned)))
    form))

(defn- deep-strip [form]
  (cond
    ;; Unwrap CljRaw to bare value — notation changes (e.g., bare control
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
;; Target 1: Roundtrip — parse → print → re-parse
;; Also catches unexpected JVM exceptions (subsumes the old NoCrashTarget).
;; ExceptionInfo = expected parse error (swallowed).
;; Other Exception = real bug (reported).
;; AssertionError = roundtrip mismatch (reported).
;; ---------------------------------------------------------------------------

(gen-class
  :name meme.fuzz.roundtrip.RoundtripTarget
  :prefix "rt-"
  :methods [^:static [fuzzerTestOneInput [com.code_intelligence.jazzer.api.FuzzedDataProvider] void]])

(defn rt-fuzzerTestOneInput
  [^FuzzedDataProvider data]
  (let [s (.consumeRemainingAsString data)]
    (try
      (let [forms (api/m1clj->forms s)]
        (when (seq forms)
          (let [printed (api/forms->m1clj forms)
                reparsed (api/m1clj->forms printed)]
            (when-not (forms-equal? forms reparsed)
              (throw (AssertionError.
                       (str "Roundtrip mismatch!\n"
                            "  input:    " (pr-str s) "\n"
                            "  forms:    " (pr-str forms) "\n"
                            "  printed:  " (pr-str printed) "\n"
                            "  reparsed: " (pr-str reparsed))))))))
      (catch clojure.lang.ExceptionInfo _ nil)
      (catch AssertionError e (throw e))
      (catch StackOverflowError _ nil)
      (catch Exception e
        (throw (AssertionError.
                 (str "Unexpected exception on input " (pr-str s) "\n"
                      "  type: " (.getName (class e)) "\n"
                      "  msg:  " (.getMessage e))))))))

;; ---------------------------------------------------------------------------
;; Target 2: Format roundtrip — parse → format → re-parse
;; ---------------------------------------------------------------------------

(gen-class
  :name meme.fuzz.roundtrip.FormatTarget
  :prefix "fmt-"
  :methods [^:static [fuzzerTestOneInput [com.code_intelligence.jazzer.api.FuzzedDataProvider] void]])

(defn fmt-fuzzerTestOneInput
  [^FuzzedDataProvider data]
  (let [s (.consumeRemainingAsString data)]
    (try
      (let [forms (api/m1clj->forms s)]
        (when (seq forms)
          (let [formatted (api/format-m1clj-forms forms)
                reparsed (api/m1clj->forms formatted)]
            (when-not (forms-equal? forms reparsed)
              (throw (AssertionError.
                       (str "Format roundtrip mismatch!\n"
                            "  input:     " (pr-str s) "\n"
                            "  forms:     " (pr-str forms) "\n"
                            "  formatted: " (pr-str formatted) "\n"
                            "  reparsed:  " (pr-str reparsed))))))))
      (catch clojure.lang.ExceptionInfo _ nil)
      (catch AssertionError e (throw e))
      (catch StackOverflowError _ nil)
      (catch Exception e
        (throw (AssertionError.
                 (str "Unexpected exception on input " (pr-str s) "\n"
                      "  type: " (.getName (class e)) "\n"
                      "  msg:  " (.getMessage e))))))))

;; ---------------------------------------------------------------------------
;; Target 3: Format idempotency — format(format(x)) == format(x)
;; ---------------------------------------------------------------------------

(gen-class
  :name meme.fuzz.roundtrip.IdempotentTarget
  :prefix "idem-"
  :methods [^:static [fuzzerTestOneInput [com.code_intelligence.jazzer.api.FuzzedDataProvider] void]])

(defn idem-fuzzerTestOneInput
  [^FuzzedDataProvider data]
  (let [s (.consumeRemainingAsString data)]
    (try
      (let [forms (api/m1clj->forms s)]
        (when (seq forms)
          (let [fmt1 (api/format-m1clj-forms forms)
                reparsed (api/m1clj->forms fmt1)
                fmt2 (api/format-m1clj-forms reparsed)]
            (when (not= fmt1 fmt2)
              (throw (AssertionError.
                       (str "Format not idempotent!\n"
                            "  input: " (pr-str s) "\n"
                            "  fmt1:  " (pr-str fmt1) "\n"
                            "  fmt2:  " (pr-str fmt2))))))))
      (catch clojure.lang.ExceptionInfo _ nil)
      (catch AssertionError e (throw e))
      (catch StackOverflowError _ nil)
      (catch Exception e
        (throw (AssertionError.
                 (str "Unexpected exception on input " (pr-str s) "\n"
                      "  type: " (.getName (class e)) "\n"
                      "  msg:  " (.getMessage e))))))))

;; ---------------------------------------------------------------------------
;; Target 4: forms->clj differential — parse meme → forms->clj → re-read as
;; Clojure → re-parse equivalence. Mirrors RoundtripTarget but on the
;; meme→clj boundary that bug #2 (syntax-quote inside set staying un-expanded)
;; lived in. Coverage-guided exploration of the seam between expand-forms
;; output and what Clojure's reader sees.
;; ---------------------------------------------------------------------------

(gen-class
  :name meme.fuzz.roundtrip.FormsToCljTarget
  :prefix "f2c-"
  :methods [^:static [fuzzerTestOneInput [com.code_intelligence.jazzer.api.FuzzedDataProvider] void]])

(defn- read-clj-all
  "Read every top-level form from a Clojure source string.

  Mirrors meme's tagged-literal handling: unknown tags become a
  `TaggedLiteral` (via `*default-data-reader-fn*`) instead of throwing.
  Without this, the differential trips on every `#my/tag …` input even
  though meme + clojure.core/read-string would agree under the same
  permissive reader binding."
  [src]
  (let [rdr (java.io.PushbackReader. (java.io.StringReader. src))
        eof (Object.)]
    (binding [*read-eval* false
              *default-data-reader-fn* tagged-literal]
      (loop [acc []]
        (let [form (read {:read-cond :preserve :eof eof} rdr)]
          (if (identical? form eof) acc (recur (conj acc form))))))))

(defn f2c-fuzzerTestOneInput
  [^FuzzedDataProvider data]
  (let [s (.consumeRemainingAsString data)]
    (try
      ;; Pass `:resolve-keyword` so deferred `CljAutoKeyword` records resolve at
      ;; lowering time — Clojure's reader resolves `::foo` eagerly under `*ns*
      ;; user`, so we must mirror that for the differential to make sense.
      (let [forms (api/m1clj->forms s {:resolve-keyword tu/cross-check-resolve-keyword})]
        (when (seq forms)
          (let [clj-text (api/forms->clj forms)
                ;; LHS: what forms->clj actually emitted (forms after expand).
                ;; RHS: what Clojure's reader makes of that text. Should match.
                ;; expand-forms with no :resolve-symbol mirrors what
                ;; forms->clj does internally — symbols inside syntax-quote
                ;; stay unresolved. Read-clj-all then sees the same unresolved
                ;; text and produces matching forms.
                expanded (vec (expander/expand-forms forms))
                reparsed (vec (read-clj-all clj-text))]
            (when-not (forms-equal? expanded reparsed)
              (throw (AssertionError.
                       (str "forms->clj differential mismatch!\n"
                            "  input:    " (pr-str s) "\n"
                            "  forms:    " (pr-str forms) "\n"
                            "  clj-text: " (pr-str clj-text) "\n"
                            "  expanded: " (pr-str expanded) "\n"
                            "  reparsed: " (pr-str reparsed))))))))
      (catch clojure.lang.ExceptionInfo _ nil)
      (catch AssertionError e (throw e))
      (catch StackOverflowError _ nil)
      (catch Exception e
        (throw (AssertionError.
                 (str "Unexpected exception on input " (pr-str s) "\n"
                      "  type: " (.getName (class e)) "\n"
                      "  msg:  " (.getMessage e))))))))

;; ---------------------------------------------------------------------------
;; Target 5: Native Clojure roundtrip — parse-as-clj → forms->clj → re-parse-as-clj
;; Mirrors RoundtripTarget for the native Clojure surface so the
;; meme.tools.clj.parser.* code path gets coverage-guided exercise.
;; ---------------------------------------------------------------------------

(gen-class
  :name meme.fuzz.roundtrip.NativeCljTarget
  :prefix "ncj-"
  :methods [^:static [fuzzerTestOneInput [com.code_intelligence.jazzer.api.FuzzedDataProvider] void]])

(defn ncj-fuzzerTestOneInput
  [^FuzzedDataProvider data]
  (let [s (.consumeRemainingAsString data)]
    (try
      (let [forms (clj-parser/clj->forms s)]
        (when (seq forms)
          (let [clj-text (api/forms->clj forms)
                expanded (vec (expander/expand-forms forms))
                reparsed (vec (clj-parser/clj->forms clj-text))
                reparsed-expanded (vec (expander/expand-forms reparsed))]
            (when-not (forms-equal? expanded reparsed-expanded)
              (throw (AssertionError.
                       (str "Native-clj roundtrip mismatch!\n"
                            "  input:    " (pr-str s) "\n"
                            "  forms:    " (pr-str forms) "\n"
                            "  clj-text: " (pr-str clj-text) "\n"
                            "  expanded: " (pr-str expanded) "\n"
                            "  reparsed: " (pr-str reparsed-expanded))))))))
      (catch clojure.lang.ExceptionInfo _ nil)
      (catch AssertionError e (throw e))
      (catch StackOverflowError _ nil)
      (catch Exception e
        (throw (AssertionError.
                 (str "Unexpected exception on input " (pr-str s) "\n"
                      "  type: " (.getName (class e)) "\n"
                      "  msg:  " (.getMessage e))))))))

;; ---------------------------------------------------------------------------
;; Target 6: Cross-check property — clojure.core/read-string vs native parser.
;; Promotes the vendor-cross-check ratchet (fixed input set) into a property:
;; for any input read-string accepts cleanly, the native parser must produce
;; equal forms post-expansion under `meme.test-util/normalize-form`.
;; ---------------------------------------------------------------------------

(gen-class
  :name meme.fuzz.roundtrip.CrossCheckTarget
  :prefix "xc-"
  :methods [^:static [fuzzerTestOneInput [com.code_intelligence.jazzer.api.FuzzedDataProvider] void]])

(defn- read-string-all
  "Read every top-level form from src using clojure.core/read with the
  permissive bindings that match meme's surface (`:preserve` reader-cond,
  `tagged-literal` for unknown tags, `*read-eval*` false). Returns nil
  on read errors so the property is one-sided (we only assert when both
  sides accept the input)."
  [src]
  (try
    (let [rdr (java.io.PushbackReader. (java.io.StringReader. src))
          eof (Object.)]
      (binding [*read-eval* false
                *default-data-reader-fn* tagged-literal]
        (loop [acc []]
          (let [form (read {:read-cond :preserve :eof eof} rdr)]
            (if (identical? form eof) acc (recur (conj acc form)))))))
    (catch Exception _ nil)))

(defn xc-fuzzerTestOneInput
  [^FuzzedDataProvider data]
  (let [s (.consumeRemainingAsString data)]
    (try
      (when-let [rs-forms (read-string-all s)]
        (let [native (clj-parser/clj->forms
                       s {:resolve-keyword tu/cross-check-resolve-keyword})]
          (when (seq native)
            (let [expanded (binding [*ns* (the-ns 'user)]
                             (vec (expander/expand-forms
                                    native
                                    {:resolve-symbol clj-run/default-resolve-symbol})))
                  rs-norm  (mapv tu/normalize-form rs-forms)
                  nat-norm (mapv tu/normalize-form expanded)]
              (when-not (= rs-norm nat-norm)
                (throw (AssertionError.
                         (str "Cross-check parity break!\n"
                              "  input:           " (pr-str s) "\n"
                              "  read-string:     " (pr-str rs-norm) "\n"
                              "  native expanded: " (pr-str nat-norm)))))))))
      (catch clojure.lang.ExceptionInfo _ nil)
      (catch AssertionError e (throw e))
      (catch StackOverflowError _ nil)
      (catch Exception e
        (throw (AssertionError.
                 (str "Unexpected exception on input " (pr-str s) "\n"
                      "  type: " (.getName (class e)) "\n"
                      "  msg:  " (.getMessage e))))))))

;; ---------------------------------------------------------------------------
;; Target 7: :clj-mode formatter idempotency — format-clj(format-clj(x)) == format-clj(x)
;; Same property as IdempotentTarget but for the native-Clojure surface.
;; ---------------------------------------------------------------------------

(gen-class
  :name meme.fuzz.roundtrip.CljModeIdempotentTarget
  :prefix "cidem-"
  :methods [^:static [fuzzerTestOneInput [com.code_intelligence.jazzer.api.FuzzedDataProvider] void]])

(defn cidem-fuzzerTestOneInput
  [^FuzzedDataProvider data]
  (let [s (.consumeRemainingAsString data)]
    (try
      (let [fmt1 (clj-api/format-clj s nil)]
        (when (seq fmt1)
          (let [fmt2 (clj-api/format-clj fmt1 nil)]
            (when (not= fmt1 fmt2)
              (throw (AssertionError.
                       (str "Format-clj not idempotent!\n"
                            "  input: " (pr-str s) "\n"
                            "  fmt1:  " (pr-str fmt1) "\n"
                            "  fmt2:  " (pr-str fmt2))))))))
      (catch clojure.lang.ExceptionInfo _ nil)
      (catch AssertionError e (throw e))
      (catch StackOverflowError _ nil)
      (catch Exception e
        (throw (AssertionError.
                 (str "Unexpected exception on input " (pr-str s) "\n"
                      "  type: " (.getName (class e)) "\n"
                      "  msg:  " (.getMessage e))))))))
