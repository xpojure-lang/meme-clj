(ns meme.test-runner
  "Run .meme tests: eval-based and fixture-based.
   All test sections run against every built-in lang pipeline."
  (:require [meme.tools.clj.forms :as forms]
            [meme.tools.clj.errors :as errors]
            [meme.registry :as registry]
            ;; Explicit requires trigger self-registration of each built-in
            ;; lang (post-refactor: registry imports no langs).
            [mclj-lang.api]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private edn-readers
  "Data readers for fixture .edn files."
  {'meme/auto-kw forms/deferred-auto-keyword})

(defn- deep=
  "Compare two form trees for structural equality, treating lists and seqs as equal.
   Note: metadata is ignored — Clojure's = ignores metadata on all values."
  [a b]
  (cond
    (and (sequential? a) (sequential? b))
    (and (= (count a) (count b))
         (every? true? (map deep= a b)))

    :else (= a b)))

(defn- diff-forms
  "Return first index where forms differ, or nil if equal.
   expected must be a vector (the convention for .edn fixture files)."
  [expected actual]
  (when-not (vector? expected)
    (throw (ex-info "Fixture .edn must contain a single top-level vector"
                    {:got (type expected)})))
  (if (not= (count expected) (count actual))
    {:error :count-mismatch
     :expected-count (count expected) :actual-count (count actual)
     :extra (when (> (count actual) (count expected))
              (vec (drop (count expected) actual)))
     :missing (when (> (count expected) (count actual))
                (vec (drop (count actual) expected)))}
    (first
     (keep-indexed
      (fn [i [e a]]
        (when-not (deep= e a)
          {:index i :expected e :actual a}))
      (map vector expected actual)))))

;; ---------------------------------------------------------------------------
;; Lang pipeline resolution
;; ---------------------------------------------------------------------------

(defn- resolve-lang-fns
  "Resolve the API functions for a built-in lang by requiring its namespace.
   Returns a map of {:mclj->forms fn, :mclj->clj fn, :clj->mclj fn}, or nil
   if the lang has no meme-compatible API."
  [lang-kw]
  (when (= lang-kw :mclj)
    (let [ns-sym 'mclj-lang.api]
      (require ns-sym)
      {:mclj->forms (ns-resolve (find-ns ns-sym) 'mclj->forms)
       :mclj->clj   (ns-resolve (find-ns ns-sym) 'mclj->clj)
       :clj->mclj   (ns-resolve (find-ns ns-sym) 'clj->mclj)})))

;; ---------------------------------------------------------------------------
;; Eval-based tests (test/examples/tests/*.meme — self-asserting)
;; ---------------------------------------------------------------------------

(defn- lang-extension
  "Return the primary file extension for a lang-map (e.g. \".meme\")."
  [lang-map]
  (or (:extension lang-map)
      (first (:extensions lang-map))))

(defn- run-eval-tests-for-lang
  "Run test files in dir matching the lang's extension via eval."
  [dir _lang-name lang-map]
  (let [run-fn (:run lang-map)
        ext    (lang-extension lang-map)]
    (if-not run-fn
      (do (println (str "  SKIP (no :run command)")) 0)
    (let [file-obj (io/file dir)
          files (sort (or (.listFiles file-obj) []))
          meme-files (filter #(.endsWith (.getName %) ext) files)]
      (if (empty? meme-files)
        (do (println (str "  SKIP (no " ext " files in " dir ")"))
            0)
        (let [results (doall
                       (for [f meme-files]
                         (do (print (str "  " (.getName f) " ... "))
                             (flush)
                             (try
                               (run-fn (slurp (str f)) {})
                               (println "OK")
                               :ok
                               (catch Throwable e
                                 (println "FAIL")
                                 (let [src (try (slurp (str f)) (catch Exception _ nil))]
                                   (println (str "    " (errors/format-error e src))))
                                 :fail)))))
              total (count results)
              failed (count (filter #(= :fail %) results))
              passed (- total failed)]
          (println (str "  eval: " passed "/" total " passed"
                        (when (pos? failed) (str ", " failed " FAILED"))))
          failed))))))

;; ---------------------------------------------------------------------------
;; Parse fixture tests (test/examples/fixtures-parse/*.meme + *.edn)
;; ---------------------------------------------------------------------------

(defn- run-parse-fixtures-for-lang
  "Run fixture tests using the given lang's mclj->forms."
  [dir mclj->forms-fn]
  (let [file-obj (io/file dir)
        files (sort (or (.listFiles file-obj) []))
        meme-files (filter #(.endsWith (.getName %) ".meme") files)]
    (if (empty? meme-files)
      (do (println (str "  ERROR: no .meme files found in " dir))
          1)
      (let [results (doall
                     (for [f meme-files]
                       (let [base (str/replace (.getName f) #"\.meme$" "")
                             edn-file (io/file dir (str base ".edn"))]
                         (print (str "  " (.getName f) " ... "))
                         (flush)
                         (if-not (.exists edn-file)
                           (do (println "SKIP (no .edn fixture)")
                               :skip)
                           (let [meme-src (slurp (str f))]
                             (try
                               (let [edn-src (slurp (str edn-file))
                                     actual (mclj->forms-fn meme-src)
                                     expected (edn/read-string {:readers edn-readers} edn-src)
                                     diff (diff-forms expected actual)]
                                 (if diff
                                   (do (println "FAIL")
                                       (println (str "    " (pr-str diff)))
                                       :fail)
                                   (do (println "OK")
                                       :ok)))
                               (catch Throwable e
                                 (println "ERROR")
                                 (println (str "    " (errors/format-error e meme-src)))
                                 :fail)))))))
            total (count (filter #(not= :skip %) results))
            failed (count (filter #(= :fail %) results))
            passed (- total failed)]
        (println (str "  fixtures: " passed "/" total " passed"
                      (when (pos? failed) (str ", " failed " FAILED"))))
        failed))))

;; ---------------------------------------------------------------------------
;; Emit fixture tests (test/examples/fixtures-emit/)
;; ---------------------------------------------------------------------------

(defn- run-emit-fixtures-for-lang
  "Run emit/conversion fixture tests using the given lang's conversion fns."
  [dir mclj->clj-fn clj->mclj-fn]
  (let [file-obj (io/file dir)
        files (sort (or (.listFiles file-obj) []))
        meme-files (filter #(and (.endsWith (.getName %) ".meme")
                                 (not (str/includes? (.getName %) ".clj.")))
                           files)
        clj-files (filter #(and (.endsWith (.getName %) ".clj")
                                (not (str/includes? (.getName %) ".meme.")))
                          files)
        fails (atom 0)]
    (when (and (empty? meme-files) (empty? clj-files))
      (println (str "  ERROR: no fixture files found in " dir))
      (swap! fails inc))
    ;; --- meme→clj direction ---
    (doseq [f meme-files]
      (let [base (.getName f)
            clj-file (io/file dir (str base ".clj"))
            cljs-file (io/file dir (str base ".cljs"))]
        (print (str "  " base " ... "))
        (flush)
        (cond
          (not (.exists clj-file))
          (do (println "MISSING") (println (str "    Required: " (.getName clj-file)))
              (swap! fails inc))
          (not (.exists cljs-file))
          (do (println "MISSING") (println (str "    Required: " (.getName cljs-file)))
              (swap! fails inc))
          :else
          (try
            (let [meme-src (str/trim-newline (slurp (str f)))
                  expected (str/trim-newline (slurp (str clj-file)))
                  actual (mclj->clj-fn meme-src)]
              (if (= expected actual)
                (println "OK")
                (do (println "FAIL")
                    (println (str "    Expected: " (subs expected 0 (min 80 (count expected))) "..."))
                    (println (str "    Actual:   " (subs actual 0 (min 80 (count actual))) "..."))
                    (swap! fails inc))))
            (catch Throwable e
              (println "ERROR")
              (println (str "    " (.getMessage e)))
              (swap! fails inc))))))
    ;; --- clj→meme direction ---
    (when clj->mclj-fn
      (doseq [f clj-files]
        (let [base (.getName f)
              meme-file (io/file dir (str base ".meme"))]
          (print (str "  " base " → .meme ... "))
          (flush)
          (if-not (.exists meme-file)
            (do (println "MISSING") (println (str "    Required: " (.getName meme-file)))
                (swap! fails inc))
            (try
              (let [clj-src (str/trim-newline (slurp (str f)))
                    expected (str/trim-newline (slurp (str meme-file)))
                    actual (clj->mclj-fn clj-src)]
                (if (= expected actual)
                  (println "OK")
                  (do (println "FAIL")
                      (println (str "    Expected: " (subs expected 0 (min 80 (count expected))) "..."))
                      (println (str "    Actual:   " (subs actual 0 (min 80 (count actual))) "..."))
                      (swap! fails inc))))
              (catch Throwable e
                (println "ERROR")
                (println (str "    " (.getMessage e)))
                (swap! fails inc)))))))
    (let [total (+ (count meme-files) (if clj->mclj-fn (count clj-files) 0))
          failed @fails
          passed (- total failed)]
      (println (str "  emit: " passed "/" total " passed"
                    (when (pos? failed) (str ", " failed " FAILED"))))
      failed)))

;; ---------------------------------------------------------------------------
;; Combined runner — enumerates all built-in lang pipelines
;; ---------------------------------------------------------------------------

(defn run-all-meme-tests
  "Run eval, parse fixture, and emit fixture tests for every built-in lang.
   Returns total failure count."
  []
  (let [builtins (sort-by key (registry/builtin-langs))
        total-fails (atom 0)]
    (doseq [[lang-name lang-map] builtins]
      (let [fns (resolve-lang-fns lang-name)]
        (println (str "=== " (name lang-name) " ==="))
        (println)

        ;; Eval tests
        (when (:run lang-map)
          (println (str "  Eval tests (test/examples/tests):"))
          (swap! total-fails + (run-eval-tests-for-lang "test/examples/tests" lang-name lang-map))
          (println))

        ;; Parse fixtures
        (when (:mclj->forms fns)
          (println (str "  Parse fixtures (test/examples/fixtures-parse):"))
          (swap! total-fails + (run-parse-fixtures-for-lang "test/examples/fixtures-parse" (:mclj->forms fns)))
          (println))

        ;; Emit fixtures
        (when (:mclj->clj fns)
          (println (str "  Emit fixtures (test/examples/fixtures-emit):"))
          (swap! total-fails + (run-emit-fixtures-for-lang "test/examples/fixtures-emit" (:mclj->clj fns) (:clj->mclj fns)))
          (println))))

    @total-fails))
