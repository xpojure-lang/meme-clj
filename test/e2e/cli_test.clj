(ns e2e.cli-test
  "End-to-end CLI tests. Shells out to `bb meme` and asserts output."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- bb-meme
  "Run `bb meme <args>`, return {:out :err :exit}."
  [& args]
  (let [pb (ProcessBuilder. ^java.util.List (into ["bb" "meme"] args))
        _  (.directory pb (io/file "."))
        p  (.start pb)
        out (slurp (.getInputStream p))
        err (slurp (.getErrorStream p))
        exit (.waitFor p)]
    {:out out :err err :exit exit}))

(defn- jvm-meme-run
  "Shell out to JVM `clojure -T:meme run` with extra-paths added to the classpath.
   Returns {:out :err :exit}. Used to exercise the JVM require path (Babashka's
   SCI bypasses clojure.core/load so it can't be tested via bb-meme)."
  [extra-paths file]
  (let [paths-edn (str "{:paths [\"src\" \"resources\" "
                       (str/join " " (map #(str "\"" % "\"") extra-paths))
                       "]}")
        pb (ProcessBuilder. ^java.util.List
            ["clojure" "-Sdeps" paths-edn "-T:meme" "run" ":file" (str "\"" file "\"")])
        _  (.directory pb (io/file "."))
        p  (.start pb)
        out (slurp (.getInputStream p))
        err (slurp (.getErrorStream p))
        exit (.waitFor p)]
    {:out out :err err :exit exit}))

(defn- tmp-meme
  "Write content to a temp .meme file, return the File."
  [content]
  (let [f (File/createTempFile "meme-e2e-" ".meme")]
    (.deleteOnExit f)
    (spit f content)
    f))

(defn- tmp-clj
  "Write content to a temp .clj file, return the File."
  [content]
  (let [f (File/createTempFile "meme-e2e-" ".clj")]
    (.deleteOnExit f)
    (spit f content)
    f))

(defn- tmp-ext
  "Write content to a temp file with the given extension, return the File."
  [ext content]
  (let [f (File/createTempFile "meme-e2e-" (str "." ext))]
    (.deleteOnExit f)
    (spit f content)
    f))

;; ---------------------------------------------------------------------------
;; version
;; ---------------------------------------------------------------------------

(deftest version-test
  (let [{:keys [out exit]} (bb-meme "version")]
    (is (zero? exit))
    (is (re-find #"^meme \d+\.\d+\.\d+" out))))

;; ---------------------------------------------------------------------------
;; help
;; ---------------------------------------------------------------------------

(deftest help-test
  (let [{:keys [out exit]} (bb-meme)]
    (is (zero? exit))
    (is (str/includes? out "Commands:"))
    (is (str/includes? out "meme run"))
    (is (str/includes? out "meme repl"))
    (is (str/includes? out "meme to-clj"))
    (is (str/includes? out "meme to-meme"))
    (is (str/includes? out "meme format"))
    (is (str/includes? out "meme inspect"))
    (is (str/includes? out "meme version"))))

(deftest unknown-command-test
  (let [{:keys [err exit]} (bb-meme "nonexistent")]
    (is (= 1 exit))
    (is (str/includes? err "Unknown command: nonexistent"))))

;; ---------------------------------------------------------------------------
;; inspect
;; ---------------------------------------------------------------------------

(deftest inspect-test
  (let [{:keys [out exit]} (bb-meme "inspect")]
    (is (zero? exit))
    (is (str/includes? out "Lang: meme"))
    (is (str/includes? out "run"))
    (is (str/includes? out "format"))
    (is (str/includes? out "to-clj"))
    (is (str/includes? out "to-meme"))))

(deftest inspect-lang-test
  (let [{:keys [out exit]} (bb-meme "inspect" "--lang" "meme-classic")]
    (is (zero? exit))
    (is (str/includes? out "Lang: meme-classic"))))

;; ---------------------------------------------------------------------------
;; run
;; ---------------------------------------------------------------------------

(deftest run-test
  (let [f (tmp-meme "println(+(1 2))")
        {:keys [out exit]} (bb-meme "run" (str f))]
    (is (zero? exit))
    (is (= "3\n" out))))

(deftest run-missing-file-test
  (let [{:keys [exit]} (bb-meme "run")]
    (is (= 1 exit))))

(deftest run-error-test
  (let [f (tmp-meme "def(")
        {:keys [exit err]} (bb-meme "run" (str f))]
    (is (= 1 exit))
    (is (not (str/blank? err)))))

;; ---------------------------------------------------------------------------
;; to-clj
;; ---------------------------------------------------------------------------

(deftest to-clj-stdout-test
  (let [f (tmp-meme "defn(foo [x] +(x 1))")
        {:keys [out exit]} (bb-meme "to-clj" (str f) "--stdout")]
    (is (zero? exit))
    (is (= "(defn foo [x] (+ x 1))\n" out))))

(deftest to-clj-file-test
  (let [f (tmp-meme "defn(foo [x] +(x 1))")
        clj-path (str/replace (str f) #"\.meme$" ".clj")
        clj-file (io/file clj-path)]
    (try
      (let [{:keys [exit]} (bb-meme "to-clj" (str f))]
        (is (zero? exit))
        (is (.exists clj-file))
        (is (= "(defn foo [x] (+ x 1))\n" (slurp clj-file))))
      (finally (.delete clj-file)))))

(deftest to-clj-lang-test
  (let [f (tmp-meme "f(x y)")
        {:keys [out exit]} (bb-meme "to-clj" (str f) "--stdout" "--lang" "meme-classic")]
    (is (zero? exit))
    (is (= "(f x y)\n" out))))

(deftest from-meme-is-alias-of-to-clj
  (let [f (tmp-meme "defn(foo [x] +(x 1))")
        {:keys [out exit]} (bb-meme "from-meme" (str f) "--stdout")]
    (is (zero? exit))
    (is (= "(defn foo [x] (+ x 1))\n" out))))

;; ---------------------------------------------------------------------------
;; to-meme
;; ---------------------------------------------------------------------------

(deftest to-meme-stdout-test
  (let [f (tmp-clj "(defn foo [x] (+ x 1))")
        {:keys [out exit]} (bb-meme "to-meme" (str f) "--stdout")]
    (is (zero? exit))
    (is (= "defn(foo [x] +(x 1))\n" out))))

(deftest from-clj-is-alias-of-to-meme
  (let [f (tmp-clj "(defn foo [x] (+ x 1))")
        {:keys [out exit]} (bb-meme "from-clj" (str f) "--stdout")]
    (is (zero? exit))
    (is (= "defn(foo [x] +(x 1))\n" out))))

(deftest to-meme-file-test
  (let [f (tmp-clj "(defn foo [x] (+ x 1))")
        meme-path (str/replace (str f) #"\.clj$" ".meme")
        meme-file (io/file meme-path)]
    (try
      (let [{:keys [exit]} (bb-meme "to-meme" (str f))]
        (is (zero? exit))
        (is (.exists meme-file))
        (is (= "defn(foo [x] +(x 1))\n" (slurp meme-file))))
      (finally (.delete meme-file)))))

(deftest to-meme-cljc-file-test
  (let [f (tmp-ext "cljc" "(defn foo [x] (+ x 1))")
        meme-path (str/replace (str f) #"\.cljc$" ".meme")
        meme-file (io/file meme-path)
        original-content (slurp f)]
    (try
      (let [{:keys [exit]} (bb-meme "to-meme" (str f))]
        (is (zero? exit))
        (is (.exists meme-file) ".meme output file should be created")
        (is (= "defn(foo [x] +(x 1))\n" (slurp meme-file)))
        ;; Critical: original .cljc must NOT be overwritten
        (is (= original-content (slurp f)) ".cljc source must not be overwritten"))
      (finally (.delete meme-file)))))

(deftest to-meme-cljs-file-test
  (let [f (tmp-ext "cljs" "(defn foo [x] (+ x 1))")
        meme-path (str/replace (str f) #"\.cljs$" ".meme")
        meme-file (io/file meme-path)
        original-content (slurp f)]
    (try
      (let [{:keys [exit]} (bb-meme "to-meme" (str f))]
        (is (zero? exit))
        (is (.exists meme-file) ".meme output file should be created")
        (is (= "defn(foo [x] +(x 1))\n" (slurp meme-file)))
        (is (= original-content (slurp f)) ".cljs source must not be overwritten"))
      (finally (.delete meme-file)))))

;; ---------------------------------------------------------------------------
;; format
;; ---------------------------------------------------------------------------

(deftest format-stdout-test
  (let [f (tmp-meme "defn(foo [x] +(x 1))")
        {:keys [out exit]} (bb-meme "format" (str f) "--stdout")]
    (is (zero? exit))
    (is (= "defn( foo [x] +(x 1))\n" out))))

(deftest format-style-flat-test
  (let [f (tmp-meme "defn(foo [x] +(x 1))")
        {:keys [out exit]} (bb-meme "format" (str f) "--stdout" "--style" "flat")]
    (is (zero? exit))
    (is (= "defn(foo [x] +(x 1))\n" out))))

(deftest format-style-clj-test
  (let [f (tmp-meme "defn(foo [x] +(x 1))")
        {:keys [out exit]} (bb-meme "format" (str f) "--stdout" "--style" "clj")]
    (is (zero? exit))
    (is (= "(defn foo [x] (+ x 1))\n" out))))

(deftest format-width-test
  (let [src "defn(greet [first-name last-name title] let([full str(title first-name last-name)] println(full)))"
        f (tmp-meme src)
        {:keys [out exit]} (bb-meme "format" (str f) "--stdout" "--width" "40")]
    (is (zero? exit))
    ;; width 40 should force multi-line
    (is (> (count (str/split-lines out)) 1))))

(deftest format-check-clean-test
  (let [f (tmp-meme "defn( foo [x] +(x 1))\n")
        {:keys [exit]} (bb-meme "format" (str f) "--check")]
    (is (zero? exit))))

(deftest format-check-dirty-test
  (let [f (tmp-meme "defn(foo    [x]    +(x    1))\n")
        {:keys [exit out]} (bb-meme "format" (str f) "--check")]
    (is (= 1 exit))
    (is (str/includes? out "would reformat"))))

(deftest format-inplace-test
  (let [f (tmp-meme "defn(foo    [x]    +(x    1))")
        {:keys [exit]} (bb-meme "format" (str f))]
    (is (zero? exit))
    (is (= "defn( foo [x] +(x 1))\n" (slurp f)))))

;; ---------------------------------------------------------------------------
;; format with --lang
;; ---------------------------------------------------------------------------

(deftest format-lang-classic-test
  (let [f (tmp-meme "defn(foo [x] +(x 1))")
        {:keys [out exit]} (bb-meme "format" (str f) "--stdout" "--lang" "meme-classic")]
    (is (zero? exit))
    (is (= "defn( foo [x] +(x 1))\n" out))))

;; ---------------------------------------------------------------------------
;; Scar tissue: Bug #3 — Babashka warns about .meme require limitation
;; Babashka's SCI require bypasses clojure.core/load, so the loader
;; cannot intercept. The loader now prints a warning instead of silently
;; doing nothing.
;; ---------------------------------------------------------------------------

(deftest run-babashka-loader-no-warning
  (let [f (tmp-meme "println(\"hello\")")
        {:keys [out err exit]} (bb-meme "run" (str f))]
    (is (zero? exit))
    (is (str/includes? out "hello"))
    (is (str/blank? err)
        "Babashka loader should be silent (SCI bypasses clojure.core/load)")))

;; ---------------------------------------------------------------------------
;; Scar tissue: Bug #4 — bb meme run with nested .meme require
;; The CLI run command now installs the loader. On Babashka, nested .meme
;; require is not supported (SCI limitation), but the file itself runs fine.
;; ---------------------------------------------------------------------------

(deftest run-file-with-require-of-clj-namespace
  (let [f (tmp-meme "require('[clojure.string :as s]) println(s/upper-case(\"hello\"))")
        {:keys [out exit]} (bb-meme "run" (str f))]
    (is (zero? exit))
    (is (= "HELLO\n" out)
        "require of standard .clj namespace should work")))

;; ---------------------------------------------------------------------------
;; compile
;; ---------------------------------------------------------------------------

(deftest compile-to-custom-dir
  (let [f (tmp-meme "defn(foo [x] +(x 1))")
        out-dir (str (System/getProperty "java.io.tmpdir") "/meme-e2e-compile-" (System/nanoTime))]
    (try
      (let [{:keys [out exit]} (bb-meme "compile" (str f) "--out" out-dir)]
        (is (zero? exit))
        (is (str/includes? out "transpiled to"))
        ;; Check output file exists and has correct content
        (let [clj-name (str/replace (.getName f) #"\.meme$" ".clj")
              clj-file (io/file out-dir clj-name)]
          (is (.exists clj-file) "compiled .clj should exist")
          (is (= "(defn foo [x] (+ x 1))\n" (slurp clj-file)))))
      (finally
        (doseq [f (reverse (file-seq (io/file out-dir)))]
          (.delete f))))))

(deftest compile-preserves-relative-paths
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "meme-e2e-src-" (System/nanoTime)))
        sub (io/file dir "my_ns")
        out-dir (str dir "-out")]
    (try
      (.mkdirs sub)
      (spit (io/file sub "core.meme") "ns(my-ns.core)\ndef(x 42)\n")
      (let [{:keys [exit]} (bb-meme "compile" (str dir) "--out" out-dir)]
        (is (zero? exit))
        (let [compiled (io/file out-dir "my_ns" "core.clj")]
          (is (.exists compiled) "should preserve my_ns/core.clj path")
          (is (str/includes? (slurp compiled) "(ns my-ns.core)"))))
      (finally
        (doseq [d [dir (io/file out-dir)]]
          (doseq [f (reverse (file-seq d))]
            (.delete f)))))))

(deftest compile-rejects-empty-out-dir
  (let [f (tmp-meme "def(x 1)")
        {:keys [exit out]} (bb-meme "compile" (str f) "--out" "")]
    (is (= 1 exit) "empty --out should exit 1")
    (is (str/includes? out "--out cannot be empty"))))

(deftest transpile-is-canonical-name-for-compile
  (let [f (tmp-meme "defn(foo [x] +(x 1))")
        out-dir (str (System/getProperty "java.io.tmpdir") "/meme-e2e-tx-" (System/nanoTime))]
    (try
      (let [{:keys [out exit]} (bb-meme "transpile" (str f) "--out" out-dir)]
        (is (zero? exit))
        (is (str/includes? out "transpiled to"))
        (let [clj-name (str/replace (.getName f) #"\.meme$" ".clj")]
          (is (.exists (io/file out-dir clj-name)))))
      (finally
        (doseq [f (reverse (file-seq (io/file out-dir)))]
          (.delete f))))))

(deftest build-produces-jvm-bytecode
  (let [src-dir (io/file (System/getProperty "java.io.tmpdir")
                         (str "meme-e2e-build-" (System/nanoTime)))
        demo (io/file src-dir "demo")
        aot-dir (str src-dir "-aot")]
    (try
      (.mkdirs demo)
      (spit (io/file demo "core.meme") "ns(demo.core)\ndefn(greet [x] str(\"hello \" x))")
      ;; Run from the user project dir so relative `target/meme` staging
      ;; doesn't collide with the meme-clj project's own target/.
      (let [pb (ProcessBuilder. ^java.util.List
                 ["bb" "--config"
                  (.getAbsolutePath (io/file "bb.edn"))
                  "meme" "build" (str src-dir) "--out" aot-dir])
            _  (.directory pb src-dir)
            p  (.start pb)
            out (slurp (.getInputStream p))
            _err (slurp (.getErrorStream p))
            exit (.waitFor p)]
        (is (zero? exit) (str "build should succeed, got: " out))
        (is (str/includes? out "Built 1 namespace"))
        (is (.exists (io/file aot-dir "demo" "core__init.class"))
            "AOT bytecode should include core__init.class"))
      (finally
        (doseq [d [src-dir
                   (io/file src-dir "target")
                   (io/file aot-dir)]]
          (when (.exists d)
            (doseq [f (reverse (file-seq d))]
              (.delete f))))))))

(deftest compile-reports-errors
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "meme-e2e-err-" (System/nanoTime)))
        out-dir (str dir "-out")]
    (try
      (.mkdirs dir)
      (spit (io/file dir "good.meme") "def(x 1)")
      (spit (io/file dir "bad.meme") "def(x")
      (let [{:keys [exit err out]} (bb-meme "compile" (str dir) "--out" out-dir)]
        (is (= 1 exit) "should exit 1 on compile error")
        (is (str/includes? (str out err) "failed"))
        ;; Good file should still be compiled
        (is (.exists (io/file out-dir "good.clj"))))
      (finally
        (doseq [d [dir (io/file out-dir)]]
          (doseq [f (reverse (file-seq d))]
            (.delete f)))))))

;; ---------------------------------------------------------------------------
;; load-file of .meme via bb meme run
;; ---------------------------------------------------------------------------

(deftest run-load-file-meme
  (let [lib (tmp-meme "defn(greet [x] str(\"hi \" x))")
        main (tmp-meme (str "load-file(\"" (.getAbsolutePath lib) "\")\nprintln(greet(\"bb\"))"))
        {:keys [out exit]} (bb-meme "run" (str main))]
    (is (zero? exit))
    (is (= "hi bb\n" out)
        "load-file of .meme should work from within bb meme run")))

;; ---------------------------------------------------------------------------
;; JVM require of .meme namespace (loader auto-install via language tier)
;;
;; Demonstrates that the loader is a core DX utility installed by
;; meme-lang.run automatically — the CLI does no manual install, yet
;; require of a .meme file on the classpath resolves through the meme
;; pipeline. If meme-lang.run/run-string ever stops auto-installing the
;; loader, this test fails.
;; ---------------------------------------------------------------------------

(deftest run-jvm-require-meme-namespace
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "meme-e2e-req-" (System/nanoTime)))
        lib-dir (io/file dir "mylib")]
    (try
      (.mkdirs lib-dir)
      (spit (io/file lib-dir "core.meme")
            "ns(mylib.core)\ndefn(greet [x] str(\"hi \" x))\n")
      (let [main (io/file dir "main.meme")]
        (spit main "require('[mylib.core])\nprintln(mylib.core/greet(\"jvm\"))\n")
        (let [{:keys [out err exit]}
              (jvm-meme-run [(.getAbsolutePath dir)] (.getAbsolutePath main))]
          (is (zero? exit) (str "exit=" exit "\nstdout=" out "\nstderr=" err))
          (is (str/includes? out "hi jvm")
              "require of .meme namespace from classpath should resolve through the loader")))
      (finally
        (doseq [f (reverse (file-seq dir))] (.delete f))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: *command-line-args* must not leak "run" and filename
;; ---------------------------------------------------------------------------

(deftest run-command-line-args-not-leaked
  (let [f (tmp-meme "println(pr-str(*command-line-args*))")
        {:keys [out exit]} (bb-meme "run" (str f) "--" "--user-arg" "value")]
    (is (zero? exit))
    (is (str/includes? out "--user-arg") "user args visible")
    (is (not (str/includes? out "run")) "CLI verb 'run' not leaked")
    (is (not (str/includes? out ".meme")) "filename not leaked")))
