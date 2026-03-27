(ns beme.alpha.test-runner
  "Run .beme tests: eval-based and fixture-based."
  (:require [beme.alpha.parse.reader :as reader]
            [beme.alpha.runtime.run :as run]
            [beme.alpha.errors :as errors]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

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
;; Eval-based tests (test/examples/tests/*.beme — self-asserting)
;; ---------------------------------------------------------------------------

(defn run-eval-tests
  "Run all .beme files in dir via eval. Each file asserts its own correctness."
  [dir]
  (let [file-obj (io/file dir)
        files (sort (or (.listFiles file-obj) []))
        beme-files (filter #(.endsWith (.getName %) ".beme") files)]
    (if (empty? beme-files)
      (do (println (str "  ERROR: no .beme files found in " dir))
          (println)
          (println "  eval: 0/0 passed, 1 FAILED")
          1)
      (let [results (doall
                      (for [f beme-files]
                        (do (print (str "  " (.getName f) " ... "))
                            (flush)
                            (try
                              (run/run-file (str f))
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
        (println)
        (println (str "  eval: " passed "/" total " passed"
                      (when (pos? failed) (str ", " failed " FAILED"))))
        failed))))

;; ---------------------------------------------------------------------------
;; Fixture-based tests (test/examples/fixtures/*.beme + *.edn)
;; ---------------------------------------------------------------------------

(defn run-fixture-tests
  "Run fixture tests: parse .beme, compare to .edn expected forms."
  [dir]
  (let [file-obj (io/file dir)
        files (sort (or (.listFiles file-obj) []))
        beme-files (filter #(.endsWith (.getName %) ".beme") files)]
    (if (empty? beme-files)
      (do (println (str "  ERROR: no .beme files found in " dir))
          (println)
          (println "  fixtures: 0/0 passed, 1 FAILED")
          1)
      (let [results (doall
                      (for [f beme-files]
                        (let [base (str/replace (.getName f) #"\.beme$" "")
                              edn-file (io/file dir (str base ".edn"))]
                          (print (str "  " (.getName f) " ... "))
                          (flush)
                          (if-not (.exists edn-file)
                            (do (println "SKIP (no .edn fixture)")
                                :skip)
                            (let [beme-src (slurp (str f))]
                              (try
                                (let [edn-src (slurp (str edn-file))
                                      actual (reader/read-beme-string beme-src)
                                      ;; Convention: .edn fixture files contain exactly one top-level
                                      ;; vector wrapping all expected forms. edn/read-string reads only
                                      ;; the first form, so multiple top-level forms would be silently
                                      ;; ignored. The vector corresponds to the parsed forms from the
                                      ;; matching .beme file.
                                      expected (edn/read-string edn-src)
                                      diff (diff-forms expected actual)]
                                  (if diff
                                    (do (println "FAIL")
                                        (println (str "    " (pr-str diff)))
                                        :fail)
                                    (do (println "OK")
                                        :ok)))
                                (catch Throwable e
                                  (println "ERROR")
                                  (println (str "    " (errors/format-error e beme-src)))
                                  :fail)))))))
            total (count (filter #(not= :skip %) results))
            failed (count (filter #(= :fail %) results))
            passed (- total failed)]
        (println)
        (println (str "  fixtures: " passed "/" total " passed"
                      (when (pos? failed) (str ", " failed " FAILED"))))
        failed))))

;; ---------------------------------------------------------------------------
;; Combined runner
;; ---------------------------------------------------------------------------

(defn run-all-beme-tests
  "Run eval tests and fixture tests. Returns total failure count."
  []
  (println "Eval tests (test/examples/tests):")
  (let [eval-fails (run-eval-tests "test/examples/tests")]
    (println)
    (println "Fixture tests (test/examples/fixtures):")
    (let [fixture-fails (run-fixture-tests "test/examples/fixtures")
          total-fails (+ eval-fails fixture-fails)]
      (println)
      total-fails)))
