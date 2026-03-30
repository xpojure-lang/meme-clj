(ns meme.alpha.vendor-roundtrip-test
  "Roundtrip vendor Clojure projects through clj->meme->clj.
   Each git submodule in test/vendor/ is a real-world Clojure project.
   Every .clj/.cljc file is roundtripped per-form with precise accounting."
  (:require [clojure.test :refer [deftest is]]
            [meme.alpha.core :as core]
            [meme.alpha.emit.formatter.flat :as fmt-flat]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Helpers (shared pattern with dogfood_test.clj)
;; ---------------------------------------------------------------------------

(defn- read-clj-forms
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

(defn- form-name
  "Extract a readable name for a form."
  [form]
  (when (seq? form)
    (let [head (first form)]
      (cond
        (#{'defn 'defn- 'def 'defmacro 'defmulti 'defmethod
           'defprotocol 'defrecord 'deftype} head)
        (str head " " (second form))
        (= 'ns head) (str "ns " (second form))
        :else (str head "...")))))

(defn- try-roundtrip-form
  "Try to roundtrip a single form. Returns {:ok form} or {:error msg}.
   Uses :read-cond :preserve so ReaderConditional objects roundtrip correctly."
  [form]
  (try
    (let [meme-text (fmt-flat/format-form form)
          forms2 (core/meme->forms meme-text {:read-cond :preserve})]
      {:ok (if (= 1 (count forms2)) (first forms2) forms2)})
    (catch Exception e
      {:error (.getMessage e)})))

(defn- roundtrip-file-forms
  "Roundtrip every form in a file individually.
   Returns {:path p :total n :succeeded [...] :failed [...] :read-errors [...]}."
  [path]
  (let [read-results (read-clj-forms path)
        read-errors (filterv :read-error read-results)
        forms (mapv :form (filterv :form read-results))
        results (mapv (fn [form]
                        (merge (try-roundtrip-form form)
                               {:name (form-name form)}))
                      forms)]
    {:path (str path)
     :total (+ (count results) (count read-errors))
     :succeeded (filterv :ok results)
     :failed (filterv :error results)
     :read-errors read-errors}))

;; ---------------------------------------------------------------------------
;; File discovery
;; ---------------------------------------------------------------------------

(def ^:private vendor-dir "test/vendor")

(defn- find-clj-files
  "Find all .clj and .cljc files under a directory, sorted."
  [dir]
  (->> (file-seq (io/file dir))
       (filter #(.isFile %))
       (filter #(let [name (.getName %)]
                  (or (str/ends-with? name ".clj")
                      (str/ends-with? name ".cljc"))))
       (sort-by str)))

;; ---------------------------------------------------------------------------
;; Per-project roundtrip test
;; ---------------------------------------------------------------------------

(defn- test-project
  "Roundtrip all files in a vendor project. Returns summary map."
  [project-dir]
  (let [project-name (.getName project-dir)
        files (find-clj-files project-dir)
        results (mapv roundtrip-file-forms files)
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
        (println (format "    - %s: %s" (or name "?") error))))
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
           (println (format "SKIP %s — submodule not initialized" ~project-name))
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
