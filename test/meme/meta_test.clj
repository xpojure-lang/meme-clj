(ns meme.meta-test
  "Meta-tests over deps.edn — guard against silent test-discovery drift.

   Two failure modes have already shipped:
   1. A new lang's tests were left out of `:test :exec-args :patterns`,
      so `clojure -X:test` reported green while skipping them.
   2. New `.cljc` tests were added under a directory that wasn't passed
      via `-d` to `cljs-test-runner`, so `bb test-cljs` skipped them.

   These tests assert that every test file under a configured test
   directory has a namespace name matched by at least one configured
   pattern (for the JVM aliases) or sits under a configured `-d` arg
   (for the CLJS alias)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private deps-edn
  (edn/read-string (slurp "deps.edn")))

(defn- read-ns-form
  "Read the first top-level `(ns ...)` form from a file. Returns the ns
   symbol; throws if the ns form can't be read (silent nil here would
   make the meta-test pass vacuously on unreadable files)."
  [^java.io.File f]
  (with-open [r (java.io.PushbackReader. (java.io.FileReader. f))]
    (loop [n 50]
      (when (pos? n)
        (let [form (read {:read-cond :allow} r)]
          (cond
            (and (seq? form) (= 'ns (first form)) (symbol? (second form)))
            (second form)
            :else (recur (dec n))))))))

(def ^:private excluded-path-prefixes
  ;; test/vendor/ is git submodules — vendored real-world Clojure libraries
  ;; whose own test suites are pulled in for roundtrip checking, not part
  ;; of meme-clj's test surface.
  ["test/vendor/"])

(defn- excluded? [^java.io.File f]
  (let [path (.getPath f)]
    (some #(str/starts-with? path %) excluded-path-prefixes)))

(defn- test-files-under
  "All *_test.{clj,cljc} files under any of the given dir paths, excluding
   vendored submodule trees. File-name convention uses underscore; the
   namespace name (where the pattern regex applies) uses the corresponding
   hyphen."
  [dirs]
  (for [dir dirs
        :let [d (io/file dir)]
        :when (.isDirectory d)
        f (file-seq d)
        :when (.isFile ^java.io.File f)
        :when (not (excluded? f))
        :let [n (.getName ^java.io.File f)]
        :when (or (str/ends-with? n "_test.clj")
                  (str/ends-with? n "_test.cljc"))]
    f))

(defn- ns-matches-any? [patterns ns-sym]
  (let [s (name ns-sym)]
    (boolean (some #(re-matches (re-pattern %) s) patterns))))

(deftest pattern-aliases-cover-every-test-file
  (testing "every *-test file under :test/:e2e :dirs is matched by at least one :patterns regex"
    (let [aliases [:test :e2e]
          all-dirs (distinct
                    (mapcat #(get-in deps-edn [:aliases % :exec-args :dirs])
                            aliases))
          all-patterns (mapcat #(get-in deps-edn [:aliases % :exec-args :patterns])
                               aliases)
          files (test-files-under all-dirs)]
      (doseq [f files
              :let [ns-sym (read-ns-form f)]]
        (is ns-sym
            (str "no (ns ...) form found in " (.getPath ^java.io.File f)))
        (when ns-sym
          (is (ns-matches-any? all-patterns ns-sym)
              (str "ns " ns-sym " in " (.getPath ^java.io.File f)
                   " is not matched by any :patterns regex in :test or :e2e — "
                   "the test runner will silently skip it.")))))))

(defn- cljs-test-d-args
  "Extract the `-d <dir>` values from :cljs-test :main-opts."
  [opts]
  (->> opts
       (partition 2 1)
       (keep (fn [[a b]] (when (= "-d" a) b)))))

(deftest cljs-test-d-args-cover-every-cljc-test-file
  (testing "every *_test.cljc file under :cljs-test :extra-paths is reachable from at least one -d arg"
    (let [extra-paths (get-in deps-edn [:aliases :cljs-test :extra-paths])
          d-args (cljs-test-d-args (get-in deps-edn [:aliases :cljs-test :main-opts]))
          files (->> (test-files-under extra-paths)
                     (filter #(str/ends-with? (.getName ^java.io.File %) "_test.cljc")))]
      (doseq [f files
              :let [path (.getPath ^java.io.File f)]]
        (is (some #(or (= path %) (str/starts-with? path (str % "/"))) d-args)
            (str path " is not under any -d arg in :cljs-test :main-opts — "
                 "cljs-test-runner will silently skip it. Current -d args: "
                 (pr-str (vec d-args))))))))

(deftest configured-test-dirs-exist
  (testing "every :dirs entry in :test/:e2e and every :extra-paths entry in :cljs-test points at a real directory"
    (doseq [[alias-key path-key] [[:test :exec-args]
                                  [:e2e :exec-args]]
            dir (get-in deps-edn [:aliases alias-key path-key :dirs])]
      (is (.isDirectory (io/file dir))
          (str ":" (name alias-key) " :dirs entry " (pr-str dir) " does not exist")))
    (doseq [path (get-in deps-edn [:aliases :cljs-test :extra-paths])]
      (is (.isDirectory (io/file path))
          (str ":cljs-test :extra-paths entry " (pr-str path) " does not exist")))
    (doseq [d (cljs-test-d-args (get-in deps-edn [:aliases :cljs-test :main-opts]))]
      (is (.isDirectory (io/file d))
          (str ":cljs-test -d arg " (pr-str d) " does not exist")))))
