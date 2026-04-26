(ns meme.vendor-cross-check-test
  "Native parser robustness net.

   For each .clj/.cljc file in the vendor submodules we parse the
   source via `meme.tools.clj.parser.api/clj->forms` and require that
   the parser does not crash on any file that `clojure.core/read-string`
   accepts cleanly. This catches real parser bugs: missing dispatch
   cases, unhandled grammar shapes, lowering crashes on real-world
   Clojure that smoke tests miss.

   Form-level equality between the two readers is deliberately NOT
   asserted. They produce the same SHAPE but differ in cosmetic ways:
   `fn*` vs `fn` for `#()`, gensym-named params vs source-preserved
   `%1`, syntax-quote-as-record vs expanded `(seq (concat ...))`. A
   tighter parity gate (with a normalising comparator) is future work.

   Once stable, the gate is: native parser must succeed on every file
   read-string parses. Files where read-string itself errors (auto-
   resolve keywords needing ns context, record literals requiring
   `*read-eval*`) are reported but don't fail."
  (:require [clojure.test :refer [deftest is]]
            [meme.test-util :as tu]
            [clojure.java.io :as io]))

(def ^:private vendor-dir "test/vendor")

(defn- cross-check-project
  [project-dir]
  (let [project-name (.getName project-dir)
        files (tu/find-clj-files project-dir)
        results (mapv (fn [f]
                        (assoc (tu/cross-check-file f) :path (str f)))
                      files)
        by-status (group-by :status results)]
    {:project project-name
     :total-files (count results)
     :ok-count (count (:ok by-status))
     :native-crash-count (count (:native-crash by-status))
     :native-crashes (:native-crash by-status)
     :native-only-count (count (:native-only by-status))
     :read-string-also-failed-count (count (:read-string-also-failed by-status))}))

(defn- report-cross-check
  [{:keys [project total-files ok-count native-crash-count native-crashes
           native-only-count read-string-also-failed-count]}]
  (println (format "\n=== %s native-parser check === %d/%d ok%s%s%s"
                   project ok-count total-files
                   (if (pos? native-crash-count)
                     (format "  CRASHES: %d" native-crash-count) "")
                   (if (pos? native-only-count)
                     (format "  native-only: %d" native-only-count) "")
                   (if (pos? read-string-also-failed-count)
                     (format "  both-failed: %d" read-string-also-failed-count) "")))
  (doseq [{:keys [path native-error]} (take 10 native-crashes)]
    (println (format "  CRASH %s\n      %s" path native-error))))

(defmacro ^:private defcrosscheck
  [project-name]
  (let [test-sym (symbol (str "vendor-native-parser-" project-name))
        dir-path (str vendor-dir "/" project-name)]
    `(deftest ~test-sym
       (let [dir# (io/file ~dir-path)]
         (when (.isDirectory dir#)
           (let [result# (cross-check-project dir#)]
             (report-cross-check result#)
             ;; Hard gate: the native parser must not crash on any file
             ;; that read-string accepts cleanly. Crashes are real bugs.
             (is (zero? (:native-crash-count result#))
                 (str ~project-name " — native parser crashed on "
                      (:native-crash-count result#)
                      " files that read-string parsed cleanly"))))))))

(defcrosscheck "core.async")
(defcrosscheck "specter")
(defcrosscheck "malli")
(defcrosscheck "ring")
(defcrosscheck "clj-http")
(defcrosscheck "medley")
(defcrosscheck "hiccup")
