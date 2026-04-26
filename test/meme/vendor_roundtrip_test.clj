(ns meme.vendor-roundtrip-test
  "Roundtrip vendor Clojure projects through clj->m1clj->clj.
   Each git submodule in test/vendor/ is a real-world Clojure project.
   Every .clj/.cljc file is roundtripped per-form with precise accounting."
  (:require [clojure.test :refer [deftest is]]
            [meme.test-util :as tu]
            [clojure.java.io :as io]))

(def ^:private vendor-dir "test/vendor")

;; ---------------------------------------------------------------------------
;; Per-project roundtrip test
;; ---------------------------------------------------------------------------

(defn- test-project
  "Roundtrip all files in a vendor project. Returns summary map."
  [project-dir]
  (let [project-name (.getName project-dir)
        files (tu/find-clj-files project-dir)
        results (mapv #(tu/roundtrip-file-forms % {:include-path true}) files)
        total-files (count results)
        total-forms (reduce + (map :total results))
        total-succeeded (reduce + (map #(count (:succeeded %)) results))
        total-failed (reduce + (map #(count (:failed %)) results))
        total-read-errors (reduce + (map #(count (:read-errors %)) results))
        passed-files (count (filter #(and (zero? (count (:failed %)))
                                          (zero? (count (:read-errors %))))
                                    results))
        problem-files (filter #(or (pos? (count (:failed %)))
                                   (pos? (count (:read-errors %))))
                              results)]
    {:project project-name
     :total-files total-files
     :passed-files passed-files
     :total-forms total-forms
     :succeeded-forms total-succeeded
     :failed-forms total-failed
     :read-errors total-read-errors
     :file-details results
     :problem-file-details problem-files}))

(defn- report-project
  "Print a human-readable report for a project result."
  [{:keys [project total-files passed-files total-forms succeeded-forms
           read-errors problem-file-details]}]
  (println (format "\n=== %s === files: %d/%d  forms: %d/%d%s"
                   project passed-files total-files
                   succeeded-forms total-forms
                   (if (pos? read-errors)
                     (format "  (read-errors: %d)" read-errors)
                     "")))
  (doseq [{:keys [path failed read-errors]} problem-file-details]
    (when (seq failed)
      (println (format "  ROUNDTRIP-FAIL %s (%d forms)" path (count failed)))
      (doseq [{:keys [name error]} failed]
        (println (format "    - %s: %s" name error))))
    (when (seq read-errors)
      (println (format "  READ-ERROR %s (%d forms)" path (count read-errors)))
      (doseq [{:keys [read-error]} read-errors]
        (println (format "    - %s" read-error))))))

;; ---------------------------------------------------------------------------
;; Test definitions — one deftest per vendor project
;; ---------------------------------------------------------------------------

(defmacro ^:private defvendortest
  "Define a vendor roundtrip test for a project directory name."
  [project-name]
  (let [test-sym (symbol (str "vendor-roundtrip-" project-name))
        dir-path (str vendor-dir "/" project-name)]
    `(deftest ~test-sym
       (let [dir# (io/file ~dir-path)]
         (if-not (.isDirectory dir#)
           ;; Explicit failure (not silent skip) so CI surfaces uninitialized
           ;; submodules instead of passing trivially with zero forms tested.
           (is false
               (str ~project-name " — vendor submodule not initialized "
                    "at " ~dir-path ". "
                    "Run: git submodule update --init"))
           (let [result# (test-project dir#)]
             (report-project result#)
             (is (pos? (:total-forms result#))
                 (str ~project-name " should have forms to test"))
             ;; Read errors are not meme's fault — report but don't fail
             (when (pos? (:read-errors result#))
               (println (format "  NOTE: %d forms could not be read by Clojure's reader"
                                (:read-errors result#))))
             ;; Roundtrip failures ARE meme's fault — these must be zero
             (is (zero? (:failed-forms result#))
                 (str ~project-name " — "
                      (:failed-forms result#) " forms failed roundtrip"))))))))

(defvendortest "core.async")
(defvendortest "specter")
(defvendortest "malli")
(defvendortest "ring")
(defvendortest "clj-http")
(defvendortest "medley")
(defvendortest "hiccup")
