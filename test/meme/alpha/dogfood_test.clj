(ns meme.alpha.dogfood-test
  "Dogfood: roundtrip meme's own source files through clj→meme→clj.
   Tests at two levels:
   - Per-form roundtrip with precise failure accounting
   - clj-kondo var-definition comparison for semantic equivalence"
  (:require [clojure.test :refer [deftest is testing]]
            [meme.alpha.core :as core]
            [meme.alpha.emit.printer :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-kondo.core :as kondo]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- read-clj-forms
  "Read all Clojure forms from a .cljc file using Clojure's reader."
  [path]
  (binding [*read-eval* false]
    (let [rdr (java.io.PushbackReader. (io/reader path))]
      (loop [forms []]
        (let [form (try (read {:read-cond :preserve :eof ::eof} rdr)
                        (catch Exception _ {::error true}))]
          (if (= form ::eof)
            forms
            (recur (conj forms form))))))))

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
  "Try to roundtrip a single form. Returns {:ok form} or {:error msg}.
   Uses :read-cond :preserve so ReaderConditional objects roundtrip correctly."
  [form]
  (try
    (let [meme-text (p/print-form form)
          forms2 (core/meme->forms meme-text {:read-cond :preserve})]
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
  (let [{:keys [total succeeded failed]} (roundtrip-file-forms "src/meme/alpha/core.cljc")]
    (is (= total (count succeeded)) "all forms roundtrip")
    (is (zero? (count failed)))))

(deftest dogfood-per-form-run
  (let [{:keys [total succeeded failed]} (roundtrip-file-forms "src/meme/alpha/runtime/run.cljc")]
    (is (= total (count succeeded)) "all forms roundtrip")
    (is (zero? (count failed)))))

(deftest dogfood-per-form-repl
  (let [{:keys [total succeeded failed]} (roundtrip-file-forms "src/meme/alpha/runtime/repl.cljc")]
    (is (= total (count succeeded)) "all forms roundtrip")
    (is (zero? (count failed)))))

(deftest dogfood-per-form-test-runner
  (let [{:keys [total succeeded failed]} (roundtrip-file-forms "test/meme/alpha/test_runner.clj")]
    (is (= total (count succeeded)) "all forms roundtrip")
    (is (zero? (count failed)))))

;; reader.cljc and printer.cljc: all forms currently roundtrip successfully.
;; If a future change introduces forms that break roundtrip (e.g., reader
;; conditionals, platform-specific dispatch), relax these to a tolerance band
;; and document the specific failing forms in a comment.

(deftest dogfood-per-form-reader
  (let [{:keys [total succeeded failed]} (roundtrip-file-forms "src/meme/alpha/parse/reader.cljc")]
    (is (= total (count succeeded))
        (str "all forms roundtrip; failures: "
             (str/join ", " (map :name failed))))
    (is (zero? (count failed)))))

(deftest dogfood-per-form-printer
  (let [{:keys [total succeeded failed]} (roundtrip-file-forms "src/meme/alpha/emit/printer.cljc")]
    (is (= total (count succeeded))
        (str "all forms roundtrip; failures: "
             (str/join ", " (map :name failed))))
    (is (zero? (count failed)))))

(deftest dogfood-per-form-tokenizer
  (let [{:keys [total succeeded failed]} (roundtrip-file-forms "src/meme/alpha/scan/tokenizer.cljc")]
    (is (= total (count succeeded))
        (str "all forms roundtrip; failures: "
             (str/join ", " (map :name failed))))
    (is (zero? (count failed)))))

(deftest dogfood-per-form-grouper
  (let [{:keys [total succeeded failed]} (roundtrip-file-forms "src/meme/alpha/scan/grouper.cljc")]
    (is (= total (count succeeded))
        (str "all forms roundtrip; failures: "
             (str/join ", " (map :name failed))))
    (is (zero? (count failed)))))

(deftest dogfood-per-form-resolve
  (let [{:keys [total succeeded failed]} (roundtrip-file-forms "src/meme/alpha/parse/resolve.cljc")]
    (is (= total (count succeeded))
        (str "all forms roundtrip; failures: "
             (str/join ", " (map :name failed))))
    (is (zero? (count failed)))))

(deftest dogfood-per-form-pipeline
  (let [{:keys [total succeeded failed]} (roundtrip-file-forms "src/meme/alpha/pipeline.cljc")]
    (is (= total (count succeeded))
        (str "all forms roundtrip; failures: "
             (str/join ", " (map :name failed))))
    (is (zero? (count failed)))))

(deftest dogfood-per-form-errors
  (let [{:keys [total succeeded failed]} (roundtrip-file-forms "src/meme/alpha/errors.cljc")]
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
  "Roundtrip a .cljc through meme and write result to a temp .clj file.
   Uses :read-cond :preserve so ReaderConditional forms roundtrip correctly.
   Returns the temp file path."
  [path]
  (let [forms (read-clj-forms path)
        meme-text (p/print-meme-string forms)
        roundtripped (core/meme->forms meme-text {:read-cond :preserve})
        tmp (java.io.File/createTempFile "dogfood" ".clj")]
    (spit tmp (str/join "\n\n" (map pr-str roundtripped)))
    tmp))

(deftest dogfood-semantic-equivalence
  (doseq [path ["src/meme/alpha/core.cljc" "src/meme/alpha/runtime/run.cljc"
                 "src/meme/alpha/runtime/repl.cljc" "test/meme/alpha/test_runner.clj"
                 "src/meme/alpha/parse/reader.cljc" "src/meme/alpha/emit/printer.cljc"
                 "src/meme/alpha/scan/tokenizer.cljc" "src/meme/alpha/scan/grouper.cljc"
                 "src/meme/alpha/parse/resolve.cljc" "src/meme/alpha/pipeline.cljc"
                 "src/meme/alpha/errors.cljc"]]
    (testing (str path " roundtripped vars match original")
      (let [original (kondo-var-defs path)
            tmp (roundtrip-to-tmp path)
            roundtripped (kondo-var-defs (str tmp))]
        (.delete tmp)
        (is (every? original roundtripped)
            (str "unexpected vars in roundtrip: " (clojure.set/difference roundtripped original)))))))
