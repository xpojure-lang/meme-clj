(ns meme.emit-fixtures-test
  "Emit fixture tests: verify meme→clj and clj→meme conversion output
   matches expected fixture files in test/examples/fixtures-emit/.
   Runs against every built-in lang pipeline.
   Every .meme file must have both .meme.clj and .meme.cljs counterparts.
   Every .clj source file must have a .clj.meme counterpart.
   Missing files are test errors, not skips."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [meme.registry :as registry]))

(def ^:private emit-dir "test/examples/fixtures-emit")

(defn- resolve-lang-fns
  "Resolve conversion functions for a built-in lang by namespace convention."
  [lang-name]
  (let [ns-sym 'meme-lang.api]
    (require ns-sym)
    {:meme->clj (ns-resolve (find-ns ns-sym) 'meme->clj)
     :clj->meme (ns-resolve (find-ns ns-sym) 'clj->meme)}))

(deftest emit-fixture-meme-to-clj
  (let [dir (io/file emit-dir)
        meme-files (sort (filter #(and (.endsWith (.getName %) ".meme")
                                       (not (str/includes? (.getName %) ".clj.")))
                                 (.listFiles dir)))
        builtins (sort-by key (registry/builtin-langs))]
    (doseq [[lang-name _] builtins]
      (let [{:keys [meme->clj]} (resolve-lang-fns lang-name)]
        (when meme->clj
          (doseq [meme-file meme-files]
            (let [base (.getName meme-file)
                  clj-file (io/file emit-dir (str base ".clj"))
                  cljs-file (io/file emit-dir (str base ".cljs"))]
              (testing (str (name lang-name) ": " base " → .clj")
                (is (.exists clj-file) (str "Missing required: " (.getName clj-file)))
                (when (.exists clj-file)
                  (is (= (str/trim-newline (slurp clj-file))
                         (meme->clj (str/trim-newline (slurp meme-file))))
                      (str base " meme→clj mismatch"))))
              (testing (str (name lang-name) ": " base " → .cljs (existence)")
                (is (.exists cljs-file) (str "Missing required: " (.getName cljs-file)))))))))))

(deftest emit-fixture-clj-to-meme
  (let [dir (io/file emit-dir)
        clj-files (sort (filter #(and (.endsWith (.getName %) ".clj")
                                      (not (str/includes? (.getName %) ".meme.")))
                                (.listFiles dir)))
        builtins (sort-by key (registry/builtin-langs))]
    (doseq [[lang-name _] builtins]
      (let [{:keys [clj->meme]} (resolve-lang-fns lang-name)]
        (when clj->meme
          (doseq [clj-file clj-files]
            (let [base (.getName clj-file)
                  meme-file (io/file emit-dir (str base ".meme"))]
              (testing (str (name lang-name) ": " base " → .meme")
                (is (.exists meme-file) (str "Missing required: " base ".meme"))
                (when (.exists meme-file)
                  (is (= (str/trim-newline (slurp meme-file))
                         (clj->meme (str/trim-newline (slurp clj-file))))
                      (str base " clj→meme mismatch")))))))))))
