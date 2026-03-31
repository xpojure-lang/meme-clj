(ns meme.alpha.dogfood-test
  "Dogfood: roundtrip meme's own source files through clj→meme→clj.
   Tests at two levels:
   - Per-form roundtrip with precise failure accounting
   - clj-kondo var-definition comparison for semantic equivalence"
  (:require [clojure.test :refer [deftest is testing]]
            [meme.alpha.core :as core]
            [meme.alpha.emit.formatter.flat :as fmt-flat]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clj-kondo.core :as kondo]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- read-clj-forms
  "Read all Clojure forms from a .cljc file using Clojure's reader.
   Returns a vector of {:form f} or {:read-error msg} maps."
  [path]
  (binding [*read-eval* false]
    (let [rdr (java.io.PushbackReader. (io/reader path))]
      (loop [results []]
        (let [result (try {:form (read {:read-cond :preserve :eof ::eof} rdr)}
                          (catch Exception e {:read-error (.getMessage e)}))]
          (if (= ::eof (:form result))
            results
            (recur (conj results result))))))))

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
    (let [meme-text (fmt-flat/format-form form)
          forms2 (core/meme->forms meme-text {:read-cond :preserve})]
      {:ok (if (= 1 (count forms2)) (first forms2) forms2)})
    (catch Exception e
      {:error (.getMessage e)})))

;; ---------------------------------------------------------------------------
;; Per-form roundtrip: precise accounting for every source file.
;; ---------------------------------------------------------------------------

(defn- roundtrip-file-forms
  "Roundtrip every form in a file individually.
   Returns {:total :succeeded :failed :read-errors}."
  [path]
  (let [read-results (read-clj-forms path)
        read-errors (filterv :read-error read-results)
        forms (mapv :form (filterv :form read-results))
        results (mapv (fn [form]
                        (merge (try-roundtrip-form form)
                               {:name (form-name form)}))
                      forms)]
    {:total (+ (count results) (count read-errors))
     :succeeded (filterv :ok results)
     :failed (filterv :error results)
     :read-errors read-errors}))

(deftest dogfood-per-form-core
  (let [{:keys [total succeeded failed read-errors]} (roundtrip-file-forms "src/meme/alpha/core.cljc")]
    (is (= total (count succeeded)) "all forms roundtrip")
    (is (zero? (count failed)))
    (is (zero? (count read-errors)) "no read errors in own source")))

(deftest dogfood-per-form-run
  (let [{:keys [total succeeded failed read-errors]} (roundtrip-file-forms "src/meme/alpha/runtime/run.cljc")]
    (is (= total (count succeeded)) "all forms roundtrip")
    (is (zero? (count failed)))
    (is (zero? (count read-errors)) "no read errors in own source")))

(deftest dogfood-per-form-repl
  (let [{:keys [total succeeded failed read-errors]} (roundtrip-file-forms "src/meme/alpha/runtime/repl.cljc")]
    (is (= total (count succeeded)) "all forms roundtrip")
    (is (zero? (count failed)))
    (is (zero? (count read-errors)) "no read errors in own source")))

(deftest dogfood-per-form-test-runner
  (let [{:keys [total succeeded failed read-errors]} (roundtrip-file-forms "test/meme/alpha/test_runner.clj")]
    (is (= total (count succeeded)) "all forms roundtrip")
    (is (zero? (count failed)))
    (is (zero? (count read-errors)) "no read errors in own source")))

;; reader.cljc and printer.cljc: all forms currently roundtrip successfully.
;; If a future change introduces forms that break roundtrip (e.g., reader
;; conditionals, platform-specific dispatch), relax these to a tolerance band
;; and document the specific failing forms in a comment.

(deftest dogfood-per-form-reader
  (let [{:keys [total succeeded failed read-errors]} (roundtrip-file-forms "src/meme/alpha/parse/reader.cljc")]
    (is (= total (count succeeded))
        (str "all forms roundtrip; failures: "
             (str/join ", " (map :name failed))))
    (is (zero? (count failed)))
    (is (zero? (count read-errors)) "no read errors in own source")))

(deftest dogfood-per-form-printer
  (let [{:keys [total succeeded failed read-errors]} (roundtrip-file-forms "src/meme/alpha/emit/printer.cljc")]
    (is (= total (count succeeded))
        (str "all forms roundtrip; failures: "
             (str/join ", " (map :name failed))))
    (is (zero? (count failed)))
    (is (zero? (count read-errors)) "no read errors in own source")))

(deftest dogfood-per-form-tokenizer
  (let [{:keys [total succeeded failed read-errors]} (roundtrip-file-forms "src/meme/alpha/scan/tokenizer.cljc")]
    (is (= total (count succeeded))
        (str "all forms roundtrip; failures: "
             (str/join ", " (map :name failed))))
    (is (zero? (count failed)))
    (is (zero? (count read-errors)) "no read errors in own source")))

(deftest dogfood-per-form-resolve
  (let [{:keys [total succeeded failed read-errors]} (roundtrip-file-forms "src/meme/alpha/parse/resolve.cljc")]
    (is (= total (count succeeded))
        (str "all forms roundtrip; failures: "
             (str/join ", " (map :name failed))))
    (is (zero? (count failed)))
    (is (zero? (count read-errors)) "no read errors in own source")))

(deftest dogfood-per-form-pipeline
  (let [{:keys [total succeeded failed read-errors]} (roundtrip-file-forms "src/meme/alpha/pipeline.cljc")]
    (is (= total (count succeeded))
        (str "all forms roundtrip; failures: "
             (str/join ", " (map :name failed))))
    (is (zero? (count failed)))
    (is (zero? (count read-errors)) "no read errors in own source")))

(deftest dogfood-per-form-errors
  (let [{:keys [total succeeded failed read-errors]} (roundtrip-file-forms "src/meme/alpha/errors.cljc")]
    (is (= total (count succeeded))
        (str "all forms roundtrip; failures: "
             (str/join ", " (map :name failed))))
    (is (zero? (count failed)))
    (is (zero? (count read-errors)) "no read errors in own source")))

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
        meme-text (fmt-flat/format-forms forms)
        roundtripped (core/meme->forms meme-text {:read-cond :preserve})
        tmp (java.io.File/createTempFile "dogfood" ".clj")]
    (spit tmp (str/join "\n\n" (map pr-str roundtripped)))
    tmp))

(deftest dogfood-semantic-equivalence
  (doseq [path ["src/meme/alpha/core.cljc" "src/meme/alpha/runtime/run.cljc"
                "src/meme/alpha/runtime/repl.cljc" "test/meme/alpha/test_runner.clj"
                "src/meme/alpha/parse/reader.cljc" "src/meme/alpha/emit/printer.cljc"
                "src/meme/alpha/scan/tokenizer.cljc"
                "src/meme/alpha/parse/resolve.cljc" "src/meme/alpha/pipeline.cljc"
                "src/meme/alpha/errors.cljc"]]
    (testing (str path " roundtripped vars match original")
      (let [original (kondo-var-defs path)
            tmp (roundtrip-to-tmp path)
            roundtripped (kondo-var-defs (str tmp))]
        (.delete tmp)
        (is (every? roundtripped original)
            (str "vars lost in roundtrip: " (set/difference original roundtripped)))))))

;; ---------------------------------------------------------------------------
;; Self-hosting: roundtrip .meme files through meme→forms→meme→forms.
;; The CLI is written in .meme — this verifies meme can read its own syntax.
;; ---------------------------------------------------------------------------

(defn- normalize-for-compare
  "Recursively strip metadata and normalize regex for structural comparison."
  [form]
  (cond
    (instance? java.util.regex.Pattern form)
    (str "#\"" (.pattern ^java.util.regex.Pattern form) "\"")
    (seq? form)  (with-meta (apply list (map normalize-for-compare form)) nil)
    (vector? form) (with-meta (mapv normalize-for-compare form) nil)
    (map? form) (with-meta (into {} (map (fn [[k v]] [(normalize-for-compare k) (normalize-for-compare v)]) form)) nil)
    (set? form) (with-meta (set (map normalize-for-compare form)) nil)
    (instance? clojure.lang.IObj form) (with-meta form nil)
    :else form))

(defn- roundtrip-meme-file
  "Roundtrip a .meme file: parse → print → re-parse. Returns {:total :succeeded :failed}.
   Compares structurally (ignoring metadata) since flat printing loses whitespace metadata."
  [path]
  (let [src (slurp path)
        forms1 (core/meme->forms src)
        printed (fmt-flat/format-forms forms1)
        forms2 (core/meme->forms printed)
        pairs (map vector forms1 forms2)
        succeeded (filterv (fn [[a b]] (= (normalize-for-compare a) (normalize-for-compare b))) pairs)
        failed (filterv (fn [[a b]] (not= (normalize-for-compare a) (normalize-for-compare b))) pairs)]
    {:total (count forms1)
     :succeeded (count succeeded)
     :failed failed}))

(deftest self-hosting-cli-meme
  (testing "cli.meme roundtrips through meme→forms→meme→forms"
    (let [{:keys [total succeeded failed]} (roundtrip-meme-file "src/meme/alpha/runtime/cli.meme")]
      (is (pos? total) "cli.meme should have forms")
      (is (= total succeeded)
          (str total " forms, " (count failed) " failed roundtrip"))
      (is (empty? failed)))))

(deftest self-hosting-example-meme-files
  (doseq [path ["examples/rewrite/simplify.meme"
                 "examples/rewrite/m-call.meme"
                 "examples/rewrite/guards.meme"]]
    (testing (str path " roundtrips")
      (let [{:keys [total succeeded failed]} (roundtrip-meme-file path)]
        (is (pos? total) (str path " should have forms"))
        (is (= total succeeded)
            (str path ": " (count failed) " of " total " forms failed"))
        (is (empty? failed))))))
