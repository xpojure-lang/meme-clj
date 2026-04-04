(ns meme.dogfood-test
  "Dogfood: roundtrip meme's own source files through clj→meme→clj.
   Tests at two levels:
   - Per-form roundtrip with precise failure accounting
   - clj-kondo var-definition comparison for semantic equivalence"
  (:require [clojure.test :refer [deftest is testing]]
            [meme.langs.meme :as lang]
            [meme.tools.emit.formatter.flat :as fmt-flat]
            [meme.test-util :as tu]
            [clojure.set :as set]
            [clojure.string :as str]
            [clj-kondo.core :as kondo]))

;; ---------------------------------------------------------------------------
;; Per-form roundtrip: precise accounting for every source file.
;; ---------------------------------------------------------------------------

(deftest dogfood-per-form-experimental
  ;; lang-map uses #?@(:clj [...]) inside a map literal which Clojure's reader
  ;; cannot read with :read-cond :preserve — tolerate 1 read error for that form.
  (let [{:keys [total succeeded failed read-errors]} (tu/roundtrip-file-forms "src/meme/langs/meme.cljc")]
    (is (= (- total (count read-errors)) (count succeeded)) "readable forms roundtrip")
    (is (zero? (count failed)))
    (is (<= (count read-errors) 1) "at most 1 read error (lang-map #?@ splice)")))

(deftest dogfood-per-form-run
  (let [{:keys [total succeeded failed read-errors]} (tu/roundtrip-file-forms "src/meme/tools/run.clj")]
    (is (= total (count succeeded)) "all forms roundtrip")
    (is (zero? (count failed)))
    (is (zero? (count read-errors)) "no read errors in own source")))

(deftest dogfood-per-form-repl
  (let [{:keys [total succeeded failed read-errors]} (tu/roundtrip-file-forms "src/meme/tools/repl.clj")]
    (is (= total (count succeeded)) "all forms roundtrip")
    (is (zero? (count failed)))
    (is (zero? (count read-errors)) "no read errors in own source")))

(deftest dogfood-per-form-test-runner
  (let [{:keys [total succeeded failed read-errors]} (tu/roundtrip-file-forms "test/meme/test_runner.clj")]
    (is (= total (count succeeded)) "all forms roundtrip")
    (is (zero? (count failed)))
    (is (zero? (count read-errors)) "no read errors in own source")))

;; reader.cljc and printer.cljc: all forms currently roundtrip successfully.
;; If a future change introduces forms that break roundtrip (e.g., reader
;; conditionals, platform-specific dispatch), relax these to a tolerance band
;; and document the specific failing forms in a comment.

(deftest dogfood-per-form-cst-reader
  (let [{:keys [total succeeded failed read-errors]} (tu/roundtrip-file-forms "src/meme/tools/reader/cst_reader.cljc")]
    (is (= total (count succeeded))
        (str "all forms roundtrip; failures: "
             (str/join ", " (map :name failed))))
    (is (zero? (count failed)))
    (is (zero? (count read-errors)) "no read errors in own source")))

(deftest dogfood-per-form-printer
  (let [{:keys [total succeeded failed read-errors]} (tu/roundtrip-file-forms "src/meme/tools/emit/printer.cljc")]
    (is (= total (count succeeded))
        (str "all forms roundtrip; failures: "
             (str/join ", " (map :name failed))))
    (is (zero? (count failed)))
    (is (zero? (count read-errors)) "no read errors in own source")))

(deftest dogfood-per-form-exp-tokenizer
  (let [{:keys [total succeeded failed read-errors]} (tu/roundtrip-file-forms "src/meme/tools/reader/tokenizer.cljc")]
    (is (= total (count succeeded))
        (str "all forms roundtrip; failures: "
             (str/join ", " (map :name failed))))
    (is (zero? (count failed)))
    (is (zero? (count read-errors)) "no read errors in own source")))

(deftest dogfood-per-form-resolve
  (let [{:keys [total succeeded failed read-errors]} (tu/roundtrip-file-forms "src/meme/tools/parse/resolve.cljc")]
    (is (= total (count succeeded))
        (str "all forms roundtrip; failures: "
             (str/join ", " (map :name failed))))
    (is (zero? (count failed)))
    (is (zero? (count read-errors)) "no read errors in own source")))

(deftest dogfood-per-form-exp-stages
  (let [{:keys [total succeeded failed read-errors]} (tu/roundtrip-file-forms "src/meme/tools/reader/stages.cljc")]
    (is (= total (count succeeded))
        (str "all forms roundtrip; failures: "
             (str/join ", " (map :name failed))))
    (is (zero? (count failed)))
    (is (zero? (count read-errors)) "no read errors in own source")))

(deftest dogfood-per-form-errors
  (let [{:keys [total succeeded failed read-errors]} (tu/roundtrip-file-forms "src/meme/tools/errors.cljc")]
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
  (let [read-results (tu/read-clj-forms path)
        forms (mapv :form (filterv :form read-results))
        meme-text (fmt-flat/format-forms forms)
        roundtripped (lang/meme->forms meme-text {:read-cond :preserve})
        tmp (java.io.File/createTempFile "dogfood" ".clj")]
    (spit tmp (str/join "\n\n" (map pr-str roundtripped)))
    tmp))

(deftest dogfood-semantic-equivalence
  (doseq [path ["src/meme/langs/meme.cljc" "src/meme/tools/run.clj"
                "src/meme/tools/repl.clj" "test/meme/test_runner.clj"
                "src/meme/tools/reader/cst_reader.cljc"
                "src/meme/tools/emit/printer.cljc"
                "src/meme/tools/reader/tokenizer.cljc"
                "src/meme/tools/parse/resolve.cljc"
                "src/meme/tools/reader/stages.cljc"
                "src/meme/tools/errors.cljc"
                "src/meme/registry.clj"
                "src/meme/cli.clj"]]
    (testing (str path " roundtripped vars match original")
      (let [original (kondo-var-defs path)
            tmp (roundtrip-to-tmp path)
            roundtripped (kondo-var-defs (str tmp))
            ;; lang-map contains function values that don't survive pr-str roundtrip
            expected (disj original 'lang-map)]
        (.delete tmp)
        (is (every? roundtripped expected)
            (str "vars lost in roundtrip: " (set/difference expected roundtripped)))))))

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
        forms1 (lang/meme->forms src)
        printed (fmt-flat/format-forms forms1)
        forms2 (lang/meme->forms printed)
        pairs (map vector forms1 forms2)
        succeeded (filterv (fn [[a b]] (= (normalize-for-compare a) (normalize-for-compare b))) pairs)
        failed (filterv (fn [[a b]] (not= (normalize-for-compare a) (normalize-for-compare b))) pairs)]
    {:total (count forms1)
     :succeeded (count succeeded)
     :failed failed}))


(deftest self-hosting-example-meme-files
  (doseq [path ["examples/stars.meme"
                 "examples/languages/prefix/core.meme"]]
    (testing (str path " roundtrips")
      (let [{:keys [total succeeded failed]} (roundtrip-meme-file path)]
        (is (pos? total) (str path " should have forms"))
        (is (= total succeeded)
            (str path ": " (count failed) " of " total " forms failed"))
        (is (empty? failed))))))
