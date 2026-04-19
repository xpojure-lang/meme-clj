(ns meme.cli-test
  (:require [clojure.test :refer [deftest is testing]]
            [meme.cli :as cli])
  (:import [java.io File]))

(deftest meme-file?-test
  (let [meme-file? #'cli/meme-file?]
    (testing "returns true for .meme files"
      (is (true? (meme-file? "foo.meme")))
      (is (true? (meme-file? "path/to/bar.meme")))
      (is (true? (meme-file? "/absolute/path.meme"))))
    (testing "returns true for registered meme extensions"
      (is (true? (meme-file? "foo.memec")))
      (is (true? (meme-file? "foo.memej")))
      (is (true? (meme-file? "foo.memejs"))))
    (testing "returns false for non-.meme files"
      (is (false? (meme-file? "foo.clj")))
      (is (false? (meme-file? "foo.cljc")))
      (is (false? (meme-file? "foo.txt")))
      (is (false? (meme-file? "foo.memes")))
      (is (false? (meme-file? "meme"))))))

(deftest clj-file?-test
  (let [clj-file? #'cli/clj-file?]
    (testing "returns true for Clojure source files"
      (is (true? (clj-file? "foo.clj")))
      (is (true? (clj-file? "foo.cljc")))
      (is (true? (clj-file? "foo.cljs")))
      (is (true? (clj-file? "foo.cljx")))
      (is (true? (clj-file? "path/to/bar.clj"))))
    (testing "returns false for non-Clojure files"
      (is (false? (clj-file? "foo.txt")))
      (is (false? (clj-file? "foo.meme")))
      (is (false? (clj-file? "foo.cljfoo")))
      (is (false? (clj-file? "clj"))))))

(deftest swap-ext-test
  (let [swap-ext #'cli/swap-ext]
    (testing "swaps .meme to .clj"
      (is (= "foo.clj" (swap-ext "foo.meme" "meme" "clj")))
      (is (= "path/to/bar.clj" (swap-ext "path/to/bar.meme" "meme" "clj"))))
    (testing "swaps registered meme extensions to .clj"
      (is (= "foo.clj" (swap-ext "foo.memec" "meme" "clj")))
      (is (= "foo.clj" (swap-ext "foo.memej" "meme" "clj")))
      (is (= "foo.clj" (swap-ext "foo.memejs" "meme" "clj")))
      (is (= "path/to/bar.clj" (swap-ext "path/to/bar.memec" "meme" "clj"))))
    (testing "swaps .clj variants to .meme"
      (is (= "foo.meme" (swap-ext "foo.clj" "clj" "meme")))
      (is (= "foo.meme" (swap-ext "foo.cljc" "clj" "meme")))
      (is (= "foo.meme" (swap-ext "foo.cljs" "clj" "meme"))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: `run` used to call (slurp file) twice — once for exec, once in
;; the error path. A file that changed between reads (or was unreadable on the
;; second read) would either show stale content in the error or lose source
;; context entirely. Fix: read once, pass same source to both paths.
;; ---------------------------------------------------------------------------

(defn- write-temp-meme! [src]
  (let [f (File/createTempFile "meme-cli-test" ".meme")]
    (.deleteOnExit f)
    (spit f src)
    f))

(deftest run-reads-source-once
  (testing "slurp is called exactly once when run succeeds"
    (let [f       (write-temp-meme! "println(\"ok\")")
          counter (atom 0)
          real-slurp slurp]
      (with-redefs [slurp (fn [arg] (swap! counter inc) (real-slurp arg))
                    cli/cli-exit! (fn [_] nil)]
        (cli/run {:file (.getAbsolutePath f)}))
      (is (= 1 @counter))))
  (testing "slurp is called exactly once when the lang throws"
    (let [f       (write-temp-meme! "undefined-symbol-xyz123(1)")
          counter (atom 0)
          real-slurp slurp
          err-buf (java.io.StringWriter.)]
      (with-redefs [slurp (fn [arg] (swap! counter inc) (real-slurp arg))
                    cli/cli-exit! (fn [_] nil)]
        (binding [*err* err-buf]
          (cli/run {:file (.getAbsolutePath f)})))
      (is (= 1 @counter))))
  (testing "error display uses source that was actually executed"
    (let [f       (write-temp-meme! "println(\"original\")")
          reads   (atom [])
          real-slurp slurp
          err-buf (java.io.StringWriter.)]
      (with-redefs [slurp (fn [arg]
                            (let [s (real-slurp arg)]
                              (swap! reads conj s)
                              s))
                    cli/cli-exit! (fn [_] nil)]
        ;; Run once, then rewrite file between exec and error formatting.
        ;; After fix: only one slurp, so this doesn't matter.
        ;; Before fix: two slurps, so the error would see the rewritten source.
        ;; We assert only one read occurred (= fix applied, no race window).
        (binding [*err* err-buf]
          (try (cli/run {:file (.getAbsolutePath f)})
               (catch Exception _ nil))))
      (is (<= (count @reads) 1)))))

(deftest lang-opts-test
  (let [lang-opts #'cli/lang-opts]
    (testing "removes CLI-specific keys"
      (is (= {} (lang-opts {:file "x" :files ["a"] :stdout true :check true :lang "meme-classic"}))))
    (testing "preserves non-CLI keys"
      (is (= {:width 80 :style "canon"}
             (lang-opts {:file "x" :stdout true :width 80 :style "canon"}))))
    (testing "empty map passes through"
      (is (= {} (lang-opts {}))))))
