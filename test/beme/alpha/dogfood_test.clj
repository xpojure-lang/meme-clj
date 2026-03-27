(ns beme.alpha.dogfood-test
  "Dogfood: roundtrip beme's own source files through clj→beme→clj.
   Tests at two levels:
   - Per-form roundtrip with precise failure accounting
   - clj-kondo var-definition comparison for semantic equivalence"
  (:require [clojure.test :refer [deftest is testing]]
            [beme.alpha.core :as core]
            [beme.alpha.emit.printer :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-kondo.core :as kondo]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- read-clj-forms
  "Read all Clojure forms from a .cljc file using Clojure's reader."
  [path]
  (let [rdr (java.io.PushbackReader. (io/reader path))]
    (loop [forms []]
      (let [form (try (read {:read-cond :preserve :eof ::eof} rdr)
                      (catch Exception _ {::error true}))]
        (if (= form ::eof)
          forms
          (recur (conj forms form)))))))

(defn- form-name
  "Extract a readable name for a form."
  [form]
  (when (seq? form)
    (let [head (first form)]
      (cond
        (#{'defn 'defn- 'def 'defmacro 'defmulti 'defmethod
           'defprotocol 'defrecord} head)
        (str head " " (second form))
        (= 'ns head) (str "ns " (second form))
        :else (str head "...")))))

(defn- try-roundtrip-form
  "Try to roundtrip a single form. Returns {:ok form} or {:error msg}."
  [form]
  (try
    (let [beme-text (p/print-form form)
          forms2 (core/beme->forms beme-text)]
      {:ok (if (= 1 (count forms2)) (first forms2) forms2)})
    (catch Exception e
      {:error (.getMessage e)})))

;; ---------------------------------------------------------------------------
;; Per-form roundtrip: precise accounting for every source file.
;; ---------------------------------------------------------------------------

(defn- roundtrip-file-forms
  "Roundtrip every form in a file individually. Returns {:total :succeeded :failed}."
  [path]
  (let [forms (read-clj-forms path)
        results (mapv (fn [form]
                        (merge (try-roundtrip-form form)
                               {:name (form-name form)}))
                      forms)]
    {:total (count results)
     :succeeded (filterv :ok results)
     :failed (filterv :error results)}))

(deftest dogfood-per-form-core
  (let [{:keys [total succeeded failed]} (roundtrip-file-forms "src/beme/alpha/core.cljc")]
    (is (= total (count succeeded)) "all forms roundtrip")
    (is (zero? (count failed)))))

(deftest dogfood-per-form-run
  (let [{:keys [total succeeded failed]} (roundtrip-file-forms "src/beme/alpha/runtime/run.cljc")]
    (is (= total (count succeeded)) "all forms roundtrip")
    (is (zero? (count failed)))))

(deftest dogfood-per-form-repl
  (let [{:keys [total succeeded failed]} (roundtrip-file-forms "src/beme/alpha/runtime/repl.cljc")]
    (is (= total (count succeeded)) "all forms roundtrip")
    (is (zero? (count failed)))))

(deftest dogfood-per-form-test-runner
  (let [{:keys [total succeeded failed]} (roundtrip-file-forms "test/beme/alpha/test_runner.clj")]
    (is (= total (count succeeded)) "all forms roundtrip")
    (is (zero? (count failed)))))

;; reader.cljc and printer.cljc: all forms currently roundtrip successfully.
;; If a future change introduces forms that break roundtrip (e.g., reader
;; conditionals, platform-specific dispatch), relax these to a tolerance band
;; and document the specific failing forms in a comment.

(deftest dogfood-per-form-reader
  (let [{:keys [total succeeded failed]} (roundtrip-file-forms "src/beme/alpha/parse/reader.cljc")]
    (is (= total (count succeeded))
        (str "all forms roundtrip; failures: "
             (str/join ", " (map :name failed))))
    (is (zero? (count failed)))))

(deftest dogfood-per-form-printer
  (let [{:keys [total succeeded failed]} (roundtrip-file-forms "src/beme/alpha/emit/printer.cljc")]
    (is (= total (count succeeded))
        (str "all forms roundtrip; failures: "
             (str/join ", " (map :name failed))))
    (is (zero? (count failed)))))

(deftest dogfood-per-form-tokenizer
  (let [{:keys [total succeeded failed]} (roundtrip-file-forms "src/beme/alpha/scan/tokenizer.cljc")]
    (is (= total (count succeeded))
        (str "all forms roundtrip; failures: "
             (str/join ", " (map :name failed))))
    (is (zero? (count failed)))))

(deftest dogfood-per-form-grouper
  (let [{:keys [total succeeded failed]} (roundtrip-file-forms "src/beme/alpha/scan/grouper.cljc")]
    (is (= total (count succeeded))
        (str "all forms roundtrip; failures: "
             (str/join ", " (map :name failed))))
    (is (zero? (count failed)))))

(deftest dogfood-per-form-resolve
  (let [{:keys [total succeeded failed]} (roundtrip-file-forms "src/beme/alpha/parse/resolve.cljc")]
    (is (= total (count succeeded))
        (str "all forms roundtrip; failures: "
             (str/join ", " (map :name failed))))
    (is (zero? (count failed)))))

(deftest dogfood-per-form-pipeline
  (let [{:keys [total succeeded failed]} (roundtrip-file-forms "src/beme/alpha/pipeline.cljc")]
    (is (= total (count succeeded))
        (str "all forms roundtrip; failures: "
             (str/join ", " (map :name failed))))
    (is (zero? (count failed)))))

(deftest dogfood-per-form-errors
  (let [{:keys [total succeeded failed]} (roundtrip-file-forms "src/beme/alpha/errors.cljc")]
    (is (= total (count succeeded))
        (str "all forms roundtrip; failures: "
             (str/join ", " (map :name failed))))
    (is (zero? (count failed)))))

;; ---------------------------------------------------------------------------
;; clj-kondo semantic equivalence: roundtripped code defines the same API.
;; ---------------------------------------------------------------------------

(defn- kondo-var-defs
  "Analyze a file with clj-kondo and extract var definition names."
  [path]
  (let [result (kondo/run! {:lint [path]
                            :config {:output {:analysis true}}})
        var-defs (get-in result [:analysis :var-definitions])]
    (set (map :name var-defs))))

(defn- roundtrip-to-tmp
  "Roundtrip a .cljc through beme and write result to a temp .clj file.
   Returns the temp file path. Throws on failure instead of returning nil."
  [path]
  (let [forms (read-clj-forms path)
        beme-text (p/print-beme-string forms)
        roundtripped (core/beme->forms beme-text)
        tmp (java.io.File/createTempFile "dogfood" ".clj")]
    (spit tmp (str/join "\n\n" (map pr-str roundtripped)))
    tmp))

(deftest dogfood-semantic-equivalence
  (doseq [path ["src/beme/alpha/core.cljc" "src/beme/alpha/runtime/run.cljc"
                 "src/beme/alpha/runtime/repl.cljc" "test/beme/alpha/test_runner.clj"
                 "src/beme/alpha/parse/reader.cljc" "src/beme/alpha/emit/printer.cljc"
                 "src/beme/alpha/scan/tokenizer.cljc" "src/beme/alpha/scan/grouper.cljc"
                 "src/beme/alpha/parse/resolve.cljc" "src/beme/alpha/pipeline.cljc"
                 "src/beme/alpha/errors.cljc"]]
    (testing (str path " defines the same vars after roundtrip")
      (let [original (kondo-var-defs path)
            tmp (roundtrip-to-tmp path)
            roundtripped (kondo-var-defs (str tmp))]
        (.delete tmp)
        (is (= original roundtripped))))))
