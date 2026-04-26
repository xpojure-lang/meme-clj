(ns meme.cli-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [meme.cli :as cli])
  (:import [java.io File]))

(deftest m1clj-file?-test
  (let [m1clj-file? #'cli/m1clj-file?]
    (testing "returns true for .m1clj files"
      (is (true? (m1clj-file? "foo.m1clj")))
      (is (true? (m1clj-file? "path/to/bar.m1clj")))
      (is (true? (m1clj-file? "/absolute/path.m1clj"))))
    (testing "returns true for registered m1clj extensions"
      (is (true? (m1clj-file? "foo.m1cljc")))
      (is (true? (m1clj-file? "foo.m1cljj")))
      (is (true? (m1clj-file? "foo.m1cljs"))))
    (testing "back-compat: deprecated .meme extensions still recognized"
      (is (true? (m1clj-file? "foo.meme")))
      (is (true? (m1clj-file? "foo.memec")))
      (is (true? (m1clj-file? "foo.memej")))
      (is (true? (m1clj-file? "foo.memejs"))))
    (testing "returns false for non-m1clj files"
      (is (false? (m1clj-file? "foo.clj")))
      (is (false? (m1clj-file? "foo.cljc")))
      (is (false? (m1clj-file? "foo.txt")))
      (is (false? (m1clj-file? "foo.memes")))
      (is (false? (m1clj-file? "m1clj"))))))

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
      (is (false? (clj-file? "foo.m1clj")))
      (is (false? (clj-file? "foo.meme")))
      (is (false? (clj-file? "foo.cljfoo")))
      (is (false? (clj-file? "clj"))))))

(deftest swap-ext-test
  (let [swap-ext #'cli/swap-ext]
    (testing "swaps .m1clj to .clj"
      (is (= "foo.clj" (swap-ext "foo.m1clj" "m1clj" "clj")))
      (is (= "path/to/bar.clj" (swap-ext "path/to/bar.m1clj" "m1clj" "clj"))))
    (testing "swaps registered m1clj extensions to .clj"
      (is (= "foo.clj" (swap-ext "foo.m1cljc" "m1clj" "clj")))
      (is (= "foo.clj" (swap-ext "foo.m1cljj" "m1clj" "clj")))
      (is (= "foo.clj" (swap-ext "foo.m1cljs" "m1clj" "clj")))
      (is (= "path/to/bar.clj" (swap-ext "path/to/bar.m1cljc" "m1clj" "clj"))))
    (testing "back-compat: deprecated .meme extensions still swap to .clj"
      (is (= "foo.clj" (swap-ext "foo.meme" "m1clj" "clj")))
      (is (= "foo.clj" (swap-ext "foo.memec" "m1clj" "clj")))
      (is (= "foo.clj" (swap-ext "foo.memej" "m1clj" "clj")))
      (is (= "foo.clj" (swap-ext "foo.memejs" "m1clj" "clj"))))
    (testing "swaps .clj variants to .m1clj"
      (is (= "foo.m1clj" (swap-ext "foo.clj" "clj" "m1clj")))
      (is (= "foo.m1clj" (swap-ext "foo.cljc" "clj" "m1clj")))
      (is (= "foo.m1clj" (swap-ext "foo.cljs" "clj" "m1clj"))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: `run` used to call (slurp file) twice — once for exec, once in
;; the error path. A file that changed between reads (or was unreadable on the
;; second read) would either show stale content in the error or lose source
;; context entirely. Fix: read once, pass same source to both paths.
;; ---------------------------------------------------------------------------

(defn- write-temp-meme! [src]
  (let [f (File/createTempFile "m1clj-cli-test" ".m1clj")]
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

;; ---------------------------------------------------------------------------
;; Scar tissue: process-files and transpile-m1clj re-slurped the file in their
;; error handlers, even though src was already loaded inside the try.
;; The redundant read is wasteful and gives an inconsistent error context if
;; the file changes between reads.
;; Fix: hoist src above the inner try; reuse it in catch.
;; ---------------------------------------------------------------------------

(deftest format-reads-source-once
  (testing "successful format reads source exactly once"
    (let [f       (write-temp-meme! "println(\"ok\")")
          counter (atom 0)
          real-slurp slurp
          out-buf (java.io.StringWriter.)]
      (with-redefs [slurp (fn [arg] (swap! counter inc) (real-slurp arg))
                    cli/cli-exit! (fn [_] nil)]
        (binding [*out* out-buf]
          (cli/format-files {:file (.getAbsolutePath f) :stdout true})))
      (is (= 1 @counter))))
  (testing "format error path reads source exactly once"
    (let [f       (write-temp-meme! "(broken")
          counter (atom 0)
          real-slurp slurp
          err-buf (java.io.StringWriter.)]
      (with-redefs [slurp (fn [arg] (swap! counter inc) (real-slurp arg))
                    cli/cli-exit! (fn [_] nil)]
        (binding [*err* err-buf]
          (cli/format-files {:file (.getAbsolutePath f) :stdout true})))
      (is (= 1 @counter)))))

(deftest transpile-reads-source-once
  (testing "successful transpile reads source exactly once"
    (let [f       (write-temp-meme! "println(\"ok\")")
          out-dir (.getAbsolutePath
                    (doto (File/createTempFile "m1clj-cli-transpile" "")
                      (.delete)
                      (.mkdirs)))
          counter (atom 0)
          real-slurp slurp
          out-buf (java.io.StringWriter.)]
      (with-redefs [slurp (fn [arg] (swap! counter inc) (real-slurp arg))
                    cli/cli-exit! (fn [_] nil)]
        (binding [*out* out-buf]
          (cli/transpile-m1clj {:file (.getAbsolutePath f) :out out-dir})))
      (is (= 1 @counter))))
  (testing "transpile error path reads source exactly once"
    (let [f       (write-temp-meme! "(broken")
          out-dir (.getAbsolutePath
                    (doto (File/createTempFile "m1clj-cli-transpile-err" "")
                      (.delete)
                      (.mkdirs)))
          counter (atom 0)
          real-slurp slurp
          err-buf (java.io.StringWriter.)]
      (with-redefs [slurp (fn [arg] (swap! counter inc) (real-slurp arg))
                    cli/cli-exit! (fn [_] nil)]
        (binding [*err* err-buf]
          (cli/transpile-m1clj {:file (.getAbsolutePath f) :out out-dir})))
      (is (= 1 @counter)))))

(deftest lang-opts-test
  (let [lang-opts #'cli/lang-opts]
    (testing "removes CLI-specific keys"
      (is (= {} (lang-opts {:file "x" :files ["a"] :stdout true :check true :lang "m1clj"}))))
    (testing "preserves non-CLI keys"
      (is (= {:width 80 :style "canon"}
             (lang-opts {:file "x" :stdout true :width 80 :style "canon"}))))
    (testing "empty map passes through"
      (is (= {} (lang-opts {}))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: `--out ""` / `--out "  "` must fail fast with a clear message.
;; Without the guard, `(str "" sep rel)` resolves to `/rel`, so a blank --out
;; silently writes the output tree into the filesystem root. The validation
;; was duplicated across transpile and build; extracted into
;; `validate-out-dir!` so both commands share the same error path.
;; ---------------------------------------------------------------------------

(deftest out-dir-validation
  (testing "validate-out-dir! rejects blank strings"
    (let [validate! #'cli/validate-out-dir!
          out-buf   (java.io.StringWriter.)
          exit-code (atom nil)]
      (with-redefs [cli/cli-exit! (fn [code] (reset! exit-code code))]
        (binding [*out* out-buf]
          (validate! "")
          (validate! "   ")))
      (is (= 1 @exit-code) "empty or whitespace-only --out must trigger exit code 1")
      (is (str/includes? (str out-buf) "--out cannot be empty"))))
  (testing "validate-out-dir! accepts nil (use default) and non-blank strings"
    (let [validate! #'cli/validate-out-dir!
          exit-called? (atom false)]
      (with-redefs [cli/cli-exit! (fn [_] (reset! exit-called? true))]
        (validate! nil)
        (validate! "target/m1clj")
        (validate! "/absolute/path"))
      (is (false? @exit-called?)
          "nil and non-blank strings must not trigger exit"))))

;; ---------------------------------------------------------------------------
;; help / version / inspect-lang — direct unit tests for the small println
;; commands that were only exercised via subprocess e2e.
;; ---------------------------------------------------------------------------

(deftest help-prints-commands-and-langs
  (let [out (with-out-str (cli/help nil))]
    (is (str/includes? out "meme — M-expressions for Clojure"))
    (is (str/includes? out "Commands:"))
    (is (str/includes? out "meme run"))
    (is (str/includes? out "meme repl"))
    (is (str/includes? out "Langs:"))))

(deftest version-prints-meme-prefix
  (let [out (with-out-str (cli/version nil))]
    (is (str/starts-with? out "meme "))))

(deftest inspect-lang-prints-supported-commands
  (testing "default lang"
    (let [out (with-out-str (cli/inspect-lang {}))]
      (is (str/includes? out "Lang: m1clj"))
      (is (str/includes? out "Supported:"))
      (is (str/includes? out "run"))
      (is (str/includes? out "format"))))
  (testing "explicit --lang m1clj"
    (let [out (with-out-str (cli/inspect-lang {:lang "m1clj"}))]
      (is (str/includes? out "Lang: m1clj")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: `run` used to slurp outside its try, so a missing or
;; unreadable file produced a raw java.io.FileNotFoundException stack trace
;; instead of a clean CLI error. Fix: wrap slurp; emit "Cannot read file:".
;; ---------------------------------------------------------------------------

(deftest run-missing-file-clean-error
  (testing "missing file produces clean error message and exit 1"
    (let [exit-code (atom nil)
          err-buf   (java.io.StringWriter.)]
      ;; cli-exit! must throw so control flow halts (matches production where
      ;; it throws ::exit ex-info caught by -main); otherwise the function
      ;; would proceed to call (:run l) on a nil source.
      (with-redefs [cli/cli-exit! (fn [code]
                                    (reset! exit-code code)
                                    (throw (ex-info "test-exit" {::test-exit code})))]
        (binding [*err* err-buf]
          (try (cli/run {:file "/nonexistent/path/that/does/not/exist.m1clj"})
               (catch clojure.lang.ExceptionInfo e
                 (when-not (::test-exit (ex-data e)) (throw e))))))
      (is (= 1 @exit-code))
      (is (str/includes? (str err-buf) "Cannot read file"))
      (is (not (str/includes? (str err-buf) "FileNotFoundException"))
          "raw Java exception must not surface to user"))))
