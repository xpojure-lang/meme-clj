(ns meme.test-util
  "Shared test utilities for meme-clj test suite.
   Extracts helpers that were duplicated across dogfood, vendor roundtrip,
   and benchmark tests."
  (:require [m1clj-lang.api :as lang]
            [m1clj-lang.formatter.flat :as fmt-flat]
            [meme.tools.clj.parser.api :as clj-parser]
            [meme.tools.clj.expander :as expander]
            [meme.tools.clj.run :as clj-run]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Clojure reader — read all forms from a .clj/.cljc file
;; ---------------------------------------------------------------------------

(defn read-clj-forms
  "Read all Clojure forms from a .clj/.cljc file using Clojure's reader.
   Returns vector of {:form f} or {:read-error msg}."
  [path]
  (binding [*read-eval* false]
    (let [rdr (java.io.PushbackReader. (io/reader path))]
      (loop [forms []]
        (let [result (try {:form (read {:read-cond :preserve :eof ::eof} rdr)}
                          (catch Exception e {:read-error (.getMessage e)}))]
          (cond
            (:read-error result) (conj forms result)
            (= (:form result) ::eof) forms
            :else (recur (conj forms result))))))))

;; ---------------------------------------------------------------------------
;; Form naming — extract a readable name for error messages
;; ---------------------------------------------------------------------------

(defn form-name
  "Extract a readable name for a form (for failure messages). Always
   returns a non-empty string so failures are actionable: top-level
   def-likes get their declared name, other seqs show the head, and
   non-seq forms (bare symbols, literals, reader-conditional records)
   render as a truncated `pr-str`."
  [form]
  (cond
    (seq? form)
    (let [head (first form)]
      (cond
        (#{'defn 'defn- 'def 'defmacro 'defmulti 'defmethod
           'defprotocol 'defrecord 'deftype} head)
        (str head " " (second form))
        (= 'ns head) (str "ns " (second form))
        :else (str head "...")))
    :else
    (let [s (pr-str form)]
      (if (> (count s) 60) (str (subs s 0 57) "...") s))))

;; ---------------------------------------------------------------------------
;; Per-form roundtrip
;; ---------------------------------------------------------------------------

(defn try-roundtrip-form
  "Try to roundtrip a single form. Returns {:ok form} or {:error msg}.
   m1clj->forms preserves ReaderConditional records by default."
  [form]
  (try
    (let [meme-text (fmt-flat/format-form form)
          forms2 (lang/m1clj->forms meme-text)]
      {:ok (if (= 1 (count forms2)) (first forms2) forms2)})
    (catch Exception e
      {:error (.getMessage e)})))

(defn roundtrip-file-forms
  "Roundtrip every form in a file individually.
   Returns {:path p :total n :succeeded [...] :failed [...] :read-errors [...]}.
   The :path key is the stringified path (nil if not provided)."
  ([path] (roundtrip-file-forms path nil))
  ([path opts]
   (let [read-results (read-clj-forms path)
         read-errors (filterv :read-error read-results)
         forms (mapv :form (filterv :form read-results))
         results (mapv (fn [form]
                         (merge (try-roundtrip-form form)
                                {:name (form-name form)}))
                       forms)]
     (cond-> {:total (+ (count results) (count read-errors))
              :succeeded (filterv :ok results)
              :failed (filterv :error results)
              :read-errors read-errors}
       (:include-path opts) (assoc :path (str path))))))

;; ---------------------------------------------------------------------------
;; Native-parser cross-check — the native parser is compared against
;; clojure.core/read-string on every vendor file. Two-tier gate:
;;
;;   1. CRASH: if the native parser throws on input read-string accepts,
;;      that is a regression — fail loud.
;;
;;   2. PARITY: post-expansion (syntax-quote + auto-resolve resolved on
;;      both sides), the form vectors should be `=` modulo a small set of
;;      cosmetic normalisations. Real-world syntactic divergences (a
;;      mis-tokenised number, a dropped form, a wrong dispatch case)
;;      surface here.
;;
;; Cosmetic normalisations applied (see `normalize-form`):
;;   • `fn*`               → `fn`            (#() expands via fn* in read-
;;                                            string, fn in native)
;;   • `pN__M#`            → `<arg-N>`       (#() arg names — read-string)
;;   • `%N`                → `<arg-N>`       (#() arg names — native)
;;   • `prefix__N__auto__` → `prefix__<g>__auto__`  (auto-gensyms in `~)
;;   • #regex Pattern      → `"#regex \"...\""` string  (Pattern equality
;;                                                      checks identity)
;;
;; Files where read-string itself errors (auto-resolve keywords without
;; ns context, record literals needing *read-eval*) are reported but
;; not counted against the gate.
;; ---------------------------------------------------------------------------

(def ^:private gensym-arg-rs-pattern
  ;; read-string emits #() args as p<N>__<gensym-id>(#).
  ;; Inside syntax-quote, the trailing # triggers auto-gensym, layering
  ;; another gensym suffix and __auto__ on top: p<N>__<id>__<id2>__auto__.
  ;; Both shapes collapse to <arg-N>.
  #"p(\d+)__\d+(?:__\d+)?(?:#|__auto__)?")

(def ^:private gensym-arg-native-pattern
  ;; Native lowering emits #() args as %<N>.
  #"%(\d+)")

(def ^:private auto-gensym-pattern
  ;; Auto-gensym inside `: prefix__<gensym-id>__auto__.
  ;; Native passes through `(gensym)` which prepends G__, so the prefix
  ;; may carry a trailing __G that read-string never emits — strip it
  ;; into the same placeholder.
  #"(.+?)(?:__G)?__\d+__auto__")

(defn- normalize-symbol [sym]
  (let [n (name sym)]
    (cond
      ;; #() lowers via fn* in read-string and via fn in the native AST tier.
      ;; Inside syntax-quote, native's `fn` further resolves to clojure.core/fn
      ;; (sq-special-forms only excludes `fn*`); read-string never emits the
      ;; resolved form because its #() expansion is already fn* (a special).
      ;; Collapse all three to a single canonical token.
      (or (= sym 'fn*)
          (= sym 'clojure.core/fn))
      'fn

      (re-matches gensym-arg-rs-pattern n)
      (let [[_ idx] (re-matches gensym-arg-rs-pattern n)]
        (symbol (str "<arg-" idx ">")))

      (re-matches gensym-arg-native-pattern n)
      (let [[_ idx] (re-matches gensym-arg-native-pattern n)]
        (symbol (str "<arg-" idx ">")))

      (re-matches auto-gensym-pattern n)
      (let [[_ base] (re-matches auto-gensym-pattern n)]
        (symbol (str base "__<g>__auto__")))

      :else sym)))

(defn normalize-form
  "Recursively normalise away surface differences between read-string
  and the native parser. See namespace comment above for the rules.

  Public so the fuzz cross-check property target (`fuzz/meme/fuzz/roundtrip.clj`)
  can reuse the same normalisation rules as `vendor_cross_check_test`."
  [form]
  (cond
    (symbol? form) (normalize-symbol form)

    ;; java.util.regex.Pattern equality is identity; collapse to a
    ;; pattern-string token instead.
    (instance? java.util.regex.Pattern form)
    (str "#regex \"" (.pattern ^java.util.regex.Pattern form) "\"")

    (instance? clojure.lang.ReaderConditional form)
    (clojure.lang.ReaderConditional/create
      (normalize-form (.form ^clojure.lang.ReaderConditional form))
      (.splicing ^clojure.lang.ReaderConditional form))

    ;; Records other than ReaderConditional: leave intact (the type tag
    ;; is the semantic identity).
    (record? form) form

    (map? form)
    (into (empty form)
          (map (fn [[k v]] [(normalize-form k) (normalize-form v)]))
          form)

    (set? form) (into (empty form) (map normalize-form) form)
    (vector? form) (mapv normalize-form form)
    (seq? form) (apply list (map normalize-form form))
    :else form))

(defn cross-check-resolve-keyword
  "Mimic clojure.core/read-string's auto-resolve-keyword behaviour with
  *ns* defaulting to user. Plain ::foo → :user/foo. ::alias/foo keeps
  the alias as-is (we don't know read-string's ns aliases here).

  Public — the fuzz cross-check property target consumes this."
  [raw]
  (let [body (subs raw 2)
        slash (str/index-of body "/")]
    (if slash
      (keyword (subs body 0 slash) (subs body (inc slash)))
      (keyword "user" body))))

(defn cross-check-file
  "Compare a file's parse output via clojure.core/read-string and the
   native parser. Returns a status map with one of:

     :ok                       — both parsers produced equal forms (post-
                                  normalisation)
     :native-crash             — native threw on input read-string accepted
     :diverged                 — both parsed cleanly but normalised forms
                                 differ (semantic divergence — bug or
                                 missing feature)
     :expander-error           — expander threw while expanding native
                                 output (known expander limitation)
     :read-string-incomplete   — read-string couldn't read every form
                                 (auto-resolve keyword without ns context,
                                 record literal needing *read-eval*).
                                 Not counted against the parity gate.

   Status maps for divergent / crashed cases include `:first-divergence`
   or `:native-error` for diagnostics."
  [path]
  (let [read-results (read-clj-forms path)
        had-rs-error? (boolean (some :read-error read-results))]
    (cond
      had-rs-error?
      {:status :read-string-incomplete
       :read-error (first (keep :read-error read-results))}

      :else
      (let [native-raw (try
                        (clj-parser/clj->forms
                          (slurp path)
                          {:resolve-keyword cross-check-resolve-keyword})
                        (catch Exception e {:error (.getMessage e)}))
            crashed? (and (map? native-raw) (:error native-raw))]
        (cond
          crashed?
          {:status :native-crash :native-error (:error native-raw)}

          :else
          (let [expanded (try
                           (binding [*ns* (the-ns 'user)]
                             (expander/expand-forms
                               native-raw
                               {:resolve-symbol clj-run/default-resolve-symbol}))
                           (catch Exception e {:error (.getMessage e)}))]
            (if (and (map? expanded) (:error expanded))
              {:status :expander-error :expander-error (:error expanded)}
              (let [rs (mapv normalize-form (mapv :form read-results))
                    native (mapv normalize-form expanded)]
                (if (and (= (count rs) (count native))
                         (every? identity (map = rs native)))
                  {:status :ok}
                  {:status :diverged
                   :rs-count (count rs)
                   :native-count (count native)
                   :first-divergence
                   (first (keep-indexed
                            (fn [i [a b]]
                              (when (not= a b) {:index i :rs a :native b}))
                            (map vector rs native)))})))))))))

;; ---------------------------------------------------------------------------
;; File discovery
;; ---------------------------------------------------------------------------

(defn find-clj-files
  "Find all .clj and .cljc files under a directory, sorted."
  [dir]
  (->> (file-seq (io/file dir))
       (filter #(.isFile %))
       (filter #(let [name (.getName %)]
                  (or (str/ends-with? name ".clj")
                      (str/ends-with? name ".cljc"))))
       (sort-by str)))
