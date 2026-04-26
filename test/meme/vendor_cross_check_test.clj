(ns meme.vendor-cross-check-test
  "Native parser parity gate.

   Two-tier check, run per .clj/.cljc file under each vendor submodule:

     1. CRASH gate — the native parser must not throw on any input that
        clojure.core/read-string accepts cleanly. Crashes are real bugs.

     2. PARITY gate — post-expansion (syntax-quote + auto-resolve resolved
        on both sides), the form vectors must be `=` modulo a small set
        of cosmetic normalisations: `fn*` → `fn`, `pN__M#` and `%N` both
        → `<arg-N>`, auto-gensym suffixes collapsed, regex patterns
        compared by `.pattern` string. See `meme.test-util/normalize-form`
        for the full list.

   Files where read-string cannot read everything (auto-resolve keywords
   without ns context, record literals needing `*read-eval*`) are
   reported but do NOT count against the parity gate. Files where
   `expand-forms` throws are tracked separately as `:expander-error` —
   known expander limitations on nested syntax-quote / unquote that
   pre-date this work; tracked, not gated.

   Per-project divergence baselines are encoded below. The gate is a
   ratchet: lowering a baseline is good (means you fixed something),
   raising it requires explicit acknowledgment of a regression."
  (:require [clojure.test :refer [deftest is]]
            [meme.test-util :as tu]
            [clojure.java.io :as io]))

(def ^:private vendor-dir "test/vendor")

;; Per-project baselines. The number is the maximum allowed
;; (:diverged + :expander-error) count. Setting a baseline to 0
;; means full parity; non-zero baselines admit known limitations.
;;
;; To tighten: drive a baseline down. The test fails on REGRESSIONS
;; (count exceeds baseline) and reminds you to lower the constant
;; when count drops below it (so future regressions are caught).
(def ^:private parity-baselines
  {"core.async"  5  ; remaining: macro-heavy go-blocks with deep ~/~@
   "specter"     1  ; one symbol-resolution edge case in macros
   "malli"       2  ; remaining macro-heavy core.cljc cases
   "ring"        0  ; full parity
   "clj-http"    1  ; one resolver edge case
   "medley"      1  ; one syntax-quote auto-gensym scope subtlety
   "hiccup"      1}) ; one resolver edge case in compiler.clj

(defn- cross-check-project
  [project-dir]
  (let [project-name (.getName project-dir)
        files (tu/find-clj-files project-dir)
        results (mapv (fn [f]
                        (assoc (tu/cross-check-file f) :path (str f)))
                      files)
        by-status (group-by :status results)]
    {:project project-name
     :total-files (count results)
     :ok-count (count (:ok by-status))
     :diverged-count (count (:diverged by-status))
     :diverged (:diverged by-status)
     :native-crash-count (count (:native-crash by-status))
     :native-crashes (:native-crash by-status)
     :expander-error-count (count (:expander-error by-status))
     :read-string-incomplete-count (count (:read-string-incomplete by-status))}))

(defn- shorten [v]
  (let [s (pr-str v)]
    (if (> (count s) 240) (str (subs s 0 237) "...") s)))

(defn- report-cross-check
  [{:keys [project total-files ok-count diverged-count native-crash-count
           expander-error-count read-string-incomplete-count
           native-crashes diverged]}
   baseline]
  (let [parity-fail (+ diverged-count expander-error-count)]
    (println (format "\n=== %s parity === %d/%d ok  diverged=%d  expander-err=%d  rs-incomplete=%d  baseline=%d%s"
                     project ok-count total-files
                     diverged-count expander-error-count read-string-incomplete-count
                     baseline
                     (if (pos? native-crash-count)
                       (format "  CRASHES=%d" native-crash-count) "")))
    (doseq [{:keys [path native-error]} (take 5 native-crashes)]
      (println (format "  CRASH %s\n    %s" path native-error)))
    (when (and (pos? diverged-count) (> parity-fail baseline))
      (doseq [{:keys [path first-divergence]} (take 3 diverged)]
        (println (format "  DIVERGE %s" path))
        (when first-divergence
          (let [{:keys [index rs native]} first-divergence]
            (println (format "    form %d:" index))
            (println (format "      rs:     %s" (shorten rs)))
            (println (format "      native: %s" (shorten native)))))))))

(defmacro ^:private defcrosscheck
  [project-name]
  (let [test-sym (symbol (str "vendor-parity-" project-name))
        dir-path (str vendor-dir "/" project-name)]
    `(deftest ~test-sym
       (let [dir# (io/file ~dir-path)]
         (when (.isDirectory dir#)
           (let [result# (cross-check-project dir#)
                 baseline# (get parity-baselines ~project-name 0)
                 parity-fail# (+ (:diverged-count result#)
                                 (:expander-error-count result#))]
             (report-cross-check result# baseline#)
             ;; Crash gate — never tolerate.
             (is (zero? (:native-crash-count result#))
                 (str ~project-name " — native parser crashed on "
                      (:native-crash-count result#) " files"))
             ;; Parity gate — must not exceed baseline.
             (is (<= parity-fail# baseline#)
                 (str ~project-name " — " parity-fail#
                      " files diverged or hit expander errors; baseline is "
                      baseline# ". A REGRESSION raised the count; either fix "
                      "the cause or update parity-baselines if intentional."))
             ;; Reminder when the count drops — keeps baselines tight.
             (when (< parity-fail# baseline#)
               (println (format "  NOTE: %s parity-fail (%d) is below baseline (%d) — lower the baseline in vendor_cross_check_test.clj."
                                ~project-name parity-fail# baseline#)))))))))

(defcrosscheck "core.async")
(defcrosscheck "specter")
(defcrosscheck "malli")
(defcrosscheck "ring")
(defcrosscheck "clj-http")
(defcrosscheck "medley")
(defcrosscheck "hiccup")
