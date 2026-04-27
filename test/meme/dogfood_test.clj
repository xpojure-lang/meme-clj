(ns meme.dogfood-test
  "Dogfood: roundtrip meme's own source files through clj→meme→clj.
   Tests at two levels:
   - Per-form roundtrip with precise failure accounting
   - clj-kondo var-definition comparison for semantic equivalence"
  (:require [clojure.test :refer [deftest is testing]]
            [m1clj-lang.api :as lang]
            [m1clj-lang.formatter.flat :as fmt-flat]
            [meme.test-util :as tu]
            [clojure.set :as set]
            [clojure.string :as str]
            [clj-kondo.core :as kondo]))

;; ---------------------------------------------------------------------------
;; Per-form roundtrip: precise accounting for every source file.
;; ---------------------------------------------------------------------------

(deftest dogfood-per-form-experimental
  ;; lang-map uses #?@(:clj [...]) inside a map literal which Clojure's reader
  ;; cannot read with :read-cond :preserve (always the meme default now) —
  ;; tolerate 1 read error for that form.
  (let [{:keys [total succeeded failed read-errors]} (tu/roundtrip-file-forms "m1clj-lang/src/m1clj_lang/api.cljc")]
    (is (= (- total (count read-errors)) (count succeeded)) "readable forms roundtrip")
    (is (zero? (count failed)))
    (is (<= (count read-errors) 1) "at most 1 read error (lang-map #?@ splice)")))

(deftest dogfood-per-form-run
  (let [{:keys [total succeeded failed read-errors]} (tu/roundtrip-file-forms "m1clj-lang/src/m1clj_lang/run.clj")]
    (is (= total (count succeeded)) "all forms roundtrip")
    (is (zero? (count failed)))
    (is (zero? (count read-errors)) "no read errors in own source")))

(deftest dogfood-per-form-repl
  (let [{:keys [total succeeded failed read-errors]} (tu/roundtrip-file-forms "m1clj-lang/src/m1clj_lang/repl.clj")]
    (is (= total (count succeeded)) "all forms roundtrip")
    (is (zero? (count failed)))
    (is (zero? (count read-errors)) "no read errors in own source")))

(deftest dogfood-per-form-test-runner
  (let [{:keys [total succeeded failed read-errors]} (tu/roundtrip-file-forms "cli/test/meme/test_runner.clj")]
    (is (= total (count succeeded)) "all forms roundtrip")
    (is (zero? (count failed)))
    (is (zero? (count read-errors)) "no read errors in own source")))

;; reader.cljc and printer.cljc: all forms currently roundtrip successfully.
;; If a future change introduces forms that break roundtrip (e.g., reader
;; conditionals, platform-specific dispatch), relax these to a tolerance band
;; and document the specific failing forms in a comment.

(deftest dogfood-per-form-cst-reader
  (let [{:keys [total succeeded failed read-errors]} (tu/roundtrip-file-forms "toolkit/src/meme/tools/clj/cst_reader.cljc")]
    (is (= total (count succeeded))
        (str "all forms roundtrip; failures: "
             (str/join ", " (map :name failed))))
    (is (zero? (count failed)))
    (is (zero? (count read-errors)) "no read errors in own source")))

(deftest dogfood-per-form-printer
  (let [{:keys [total succeeded failed read-errors]} (tu/roundtrip-file-forms "m1clj-lang/src/m1clj_lang/printer.cljc")]
    (is (= total (count succeeded))
        (str "all forms roundtrip; failures: "
             (str/join ", " (map :name failed))))
    (is (zero? (count failed)))
    (is (zero? (count read-errors)) "no read errors in own source")))

(deftest dogfood-per-form-resolve
  (let [{:keys [total succeeded failed read-errors]} (tu/roundtrip-file-forms "toolkit/src/meme/tools/clj/resolve.cljc")]
    (is (= total (count succeeded))
        (str "all forms roundtrip; failures: "
             (str/join ", " (map :name failed))))
    (is (zero? (count failed)))
    (is (zero? (count read-errors)) "no read errors in own source")))

(deftest dogfood-per-form-exp-stages
  (let [{:keys [total succeeded failed read-errors]} (tu/roundtrip-file-forms "toolkit/src/meme/tools/clj/stages.cljc")]
    (is (= total (count succeeded))
        (str "all forms roundtrip; failures: "
             (str/join ", " (map :name failed))))
    (is (zero? (count failed)))
    (is (zero? (count read-errors)) "no read errors in own source")))

(deftest dogfood-per-form-errors
  (let [{:keys [total succeeded failed read-errors]} (tu/roundtrip-file-forms "toolkit/src/meme/tools/clj/errors.cljc")]
    (is (= total (count succeeded))
        (str "all forms roundtrip; failures: "
             (str/join ", " (map :name failed))))
    (is (zero? (count failed)))
    (is (zero? (count read-errors)) "no read errors in own source")))

(deftest dogfood-per-form-parser
  (let [{:keys [total succeeded failed read-errors]} (tu/roundtrip-file-forms "toolkit/src/meme/tools/parser.cljc")]
    (is (= total (count succeeded))
        (str "all forms roundtrip; failures: "
             (str/join ", " (map :name failed))))
    (is (zero? (count failed)))
    (is (zero? (count read-errors)) "no read errors in own source")))

(deftest dogfood-per-form-render
  (let [{:keys [total succeeded failed read-errors]} (tu/roundtrip-file-forms "toolkit/src/meme/tools/render.cljc")]
    (is (= total (count succeeded))
        (str "all forms roundtrip; failures: "
             (str/join ", " (map :name failed))))
    (is (zero? (count failed)))
    (is (zero? (count read-errors)) "no read errors in own source")))

(deftest dogfood-per-form-grammar
  (let [{:keys [total succeeded failed read-errors]} (tu/roundtrip-file-forms "m1clj-lang/src/m1clj_lang/grammar.cljc")]
    (is (= total (count succeeded))
        (str "all forms roundtrip; failures: "
             (str/join ", " (map :name failed))))
    (is (zero? (count failed)))
    (is (zero? (count read-errors)) "no read errors in own source")))

(deftest dogfood-per-form-form-shape
  (let [{:keys [total succeeded failed read-errors]} (tu/roundtrip-file-forms "m1clj-lang/src/m1clj_lang/form_shape.cljc")]
    (is (= total (count succeeded))
        (str "all forms roundtrip; failures: "
             (str/join ", " (map :name failed))))
    (is (zero? (count failed)))
    (is (zero? (count read-errors)) "no read errors in own source")))

(deftest dogfood-per-form-formatter-canon
  (let [{:keys [total succeeded failed read-errors]} (tu/roundtrip-file-forms "m1clj-lang/src/m1clj_lang/formatter/canon.cljc")]
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
   m1clj->forms preserves ReaderConditional records by default.
   Returns the temp file path."
  [path]
  (let [read-results (tu/read-clj-forms path)
        forms (mapv :form (filterv :form read-results))
        meme-text (fmt-flat/format-forms forms)
        roundtripped (lang/m1clj->forms meme-text)
        tmp (java.io.File/createTempFile "dogfood" ".clj")]
    (spit tmp (str/join "\n\n" (map pr-str roundtripped)))
    tmp))

(deftest dogfood-semantic-equivalence
  (doseq [path ["m1clj-lang/src/m1clj_lang/api.cljc" "m1clj-lang/src/m1clj_lang/run.clj"
                "m1clj-lang/src/m1clj_lang/repl.clj" "cli/test/meme/test_runner.clj"
                "toolkit/src/meme/tools/clj/cst_reader.cljc"
                "m1clj-lang/src/m1clj_lang/printer.cljc"
                "toolkit/src/meme/tools/clj/resolve.cljc"
                "toolkit/src/meme/tools/clj/stages.cljc"
                "toolkit/src/meme/tools/clj/errors.cljc"
                "toolkit/src/meme/registry.clj"
                "cli/src/meme/cli.clj"
                "toolkit/src/meme/tools/parser.cljc"
                "toolkit/src/meme/tools/render.cljc"
                "m1clj-lang/src/m1clj_lang/grammar.cljc"
                "m1clj-lang/src/m1clj_lang/form_shape.cljc"
                "m1clj-lang/src/m1clj_lang/formatter/canon.cljc"]]
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
;; Self-hosting: roundtrip .m1clj files through m1clj→forms→m1clj→forms.
;; The CLI is written in .m1clj — this verifies m1clj can read its own syntax.
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
  "Roundtrip a .m1clj file: parse → print → re-parse. Returns {:total :succeeded :failed}.
   Compares structurally (ignoring metadata) since flat printing loses whitespace metadata."
  [path]
  (let [src (slurp path)
        forms1 (lang/m1clj->forms src)
        printed (fmt-flat/format-forms forms1)
        forms2 (lang/m1clj->forms printed)
        pairs (map vector forms1 forms2)
        succeeded (filterv (fn [[a b]] (= (normalize-for-compare a) (normalize-for-compare b))) pairs)
        failed (filterv (fn [[a b]] (not= (normalize-for-compare a) (normalize-for-compare b))) pairs)]
    {:total (count forms1)
     :succeeded (count succeeded)
     :failed failed}))


(deftest self-hosting-example-meme-files
  (doseq [path ["examples/stars.m1clj"]]
    (testing (str path " roundtrips")
      (let [{:keys [total succeeded failed]} (roundtrip-meme-file path)]
        (is (pos? total) (str path " should have forms"))
        (is (= total succeeded)
            (str path ": " (count failed) " of " total " forms failed"))
        (is (empty? failed))))))
