(ns meme.emit-fixtures-test
  "Emit fixture tests: verify m1clj→clj and clj→m1clj conversion output
   matches expected fixture files in test/examples/fixtures-emit/.
   Runs against every built-in lang pipeline.
   Every .m1clj file must have both .m1clj.clj and .m1clj.cljs counterparts.
   Every .clj source file must have a .clj.m1clj counterpart.
   Missing files are test errors, not skips."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [meme.registry :as registry]))

(def ^:private emit-dir "test/examples/fixtures-emit")

(defn- resolve-lang-fns
  "Resolve conversion functions for a built-in lang by namespace convention."
  [lang-name]
  (let [ns-sym 'm1clj-lang.api]
    (require ns-sym)
    {:m1clj->clj (ns-resolve (find-ns ns-sym) 'm1clj->clj)
     :clj->m1clj (ns-resolve (find-ns ns-sym) 'clj->m1clj)}))

(deftest emit-fixture-meme-to-clj
  (let [dir (io/file emit-dir)
        meme-files (sort (filter #(and (.endsWith (.getName %) ".m1clj")
                                       (not (str/includes? (.getName %) ".clj.")))
                                 (.listFiles dir)))
        builtins (sort-by key (registry/builtin-langs))]
    (doseq [[lang-name _] builtins]
      (let [{:keys [m1clj->clj]} (resolve-lang-fns lang-name)]
        (when m1clj->clj
          (doseq [meme-file meme-files]
            (let [base (.getName meme-file)
                  clj-file (io/file emit-dir (str base ".clj"))
                  cljs-file (io/file emit-dir (str base ".cljs"))]
              (testing (str (name lang-name) ": " base " → .clj")
                (is (.exists clj-file) (str "Missing required: " (.getName clj-file)))
                (when (.exists clj-file)
                  (is (= (str/trim-newline (slurp clj-file))
                         (m1clj->clj (str/trim-newline (slurp meme-file))))
                      (str base " meme→clj mismatch"))))
              (testing (str (name lang-name) ": " base " → .cljs (existence)")
                (is (.exists cljs-file) (str "Missing required: " (.getName cljs-file)))))))))))

(deftest emit-fixture-clj-to-m1clj
  (let [dir (io/file emit-dir)
        clj-files (sort (filter #(and (.endsWith (.getName %) ".clj")
                                      (not (str/includes? (.getName %) ".m1clj.")))
                                (.listFiles dir)))
        builtins (sort-by key (registry/builtin-langs))]
    (doseq [[lang-name _] builtins]
      (let [{:keys [clj->m1clj]} (resolve-lang-fns lang-name)]
        (when clj->m1clj
          (doseq [clj-file clj-files]
            (let [base (.getName clj-file)
                  meme-file (io/file emit-dir (str base ".m1clj"))]
              (testing (str (name lang-name) ": " base " → .m1clj")
                (is (.exists meme-file) (str "Missing required: " base ".m1clj"))
                (when (.exists meme-file)
                  (is (= (str/trim-newline (slurp meme-file))
                         (clj->m1clj (str/trim-newline (slurp clj-file))))
                      (str base " clj→m1clj mismatch")))))))))))
