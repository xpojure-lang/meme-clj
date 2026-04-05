(ns meme.loader-test
  "Tests for the meme namespace loader.
   Scar tissue: these tests were expanded after discovering four loader bugs
   that the original suite missed due to test-ordering dependencies and
   missing platform coverage. See commit history for details."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [meme.loader :as loader]))

(use-fixtures :each
  (fn [f]
    (let [orig-ns *ns*]
      (loader/install!)
      (try (f)
           (finally
             ;; Restore ns, remove test namespaces, uninstall loader
             (in-ns (ns-name orig-ns))
             (doseq [ns-sym '[test-meme-ns.greeter test-meme-ns.shadow test-meme-ns.broken test-meme-ns.caller]]
               (when (find-ns ns-sym)
                 (remove-ns ns-sym))
               ;; Also clear *loaded-libs* — remove-ns alone leaves the
               ;; "loaded" flag, causing require to skip reloading.
               (dosync (alter @#'clojure.core/*loaded-libs* disj ns-sym)))
             (loader/uninstall!))))))

(deftest loader-install-uninstall
  (testing "install! is idempotent"
    (is (= :installed (loader/install!)))
    (is (= :installed (loader/install!))))
  (testing "uninstall! restores original load"
    (loader/uninstall!)
    (is (= :uninstalled (loader/uninstall!)))
    ;; reinstall for rest of test
    (loader/install!)))

(deftest require-meme-namespace
  (testing "require loads .meme file from classpath"
    (require 'test-meme-ns.greeter)
    (let [hello (ns-resolve 'test-meme-ns.greeter 'hello)
          add (ns-resolve 'test-meme-ns.greeter 'add)]
      (is (some? hello) "hello should be defined")
      (is (some? add) "add should be defined")
      (is (= "Hello, World!" (hello "World")))
      (is (= 5 (add 2 3))))))

(deftest clj-require-still-works
  (testing "standard .clj require still works after install"
    (require 'clojure.string)
    (is (some? (ns-resolve 'clojure.string 'join)))))

(deftest meme-takes-precedence-over-clj
  (testing ".meme file is loaded when both .meme and .clj exist"
    (require 'test-meme-ns.shadow :reload)
    (let [source-var (ns-resolve 'test-meme-ns.shadow 'source)]
      (is (some? source-var) "source should be defined")
      (is (= :meme @source-var) ".meme file should take precedence over .clj"))))

(deftest meme-parse-error-propagates
  (testing "require of a .meme file with syntax error throws"
    (is (thrown? Exception
                (require 'test-meme-ns.broken :reload)))))

;; ---------------------------------------------------------------------------
;; C3: Namespace denylist — core infrastructure cannot be shadowed
;; ---------------------------------------------------------------------------

(deftest denied-namespaces-not-intercepted
  (testing "clojure.* namespaces are not intercepted by the loader"
    ;; If the denylist works, find-lang-resource returns nil for clojure/* paths
    (let [find-fn @(resolve 'meme.loader/find-lang-resource)]
      (is (nil? (find-fn "/clojure/string")) "clojure/string should be denied")
      (is (nil? (find-fn "/clojure/core")) "clojure/core should be denied")
      (is (nil? (find-fn "/java/lang")) "java/lang should be denied")
      (is (nil? (find-fn "/nrepl/core")) "nrepl/core should be denied"))))

;; ---------------------------------------------------------------------------
;; C4: Cannot uninstall loader from within a loaded .meme file
;; ---------------------------------------------------------------------------

(deftest uninstall-during-load-rejected
  (testing "uninstall! throws when called during lang-load"
    ;; Simulate: bind *loading* true and try to uninstall
    (binding [meme.loader/*loading* true]
      (is (thrown-with-msg? Exception #"Cannot uninstall"
                            (loader/uninstall!))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: Bug #1 — requiring-resolve infinite recursion
;; find-lang-resource used to call (requiring-resolve 'meme.registry/...)
;; on every invocation. When meme.registry wasn't pre-loaded, this caused
;; load → lang-load → find-lang-resource → requiring-resolve → require →
;; load → infinite StackOverflowError. The fix: eagerly resolve at install!
;; time and cache in an atom.
;; ---------------------------------------------------------------------------

(deftest extensions-fn-lifecycle
  (testing "extensions-fn is populated after install"
    (is (fn? @@#'meme.loader/extensions-fn)
        "extensions-fn atom should hold a function after install!"))
  (testing "extensions-fn is nil after uninstall"
    (loader/uninstall!)
    (is (nil? @@#'meme.loader/extensions-fn)
        "extensions-fn atom should be nil after uninstall!")
    ;; reinstall for remaining tests
    (loader/install!)))

(deftest no-requiring-resolve-in-find-lang-resource
  (testing "find-lang-resource uses cached extensions, not requiring-resolve"
    ;; If requiring-resolve were called during find-lang-resource, it would
    ;; trigger load → lang-load → find-lang-resource → infinite recursion.
    ;; This test verifies the fix for bug #1.
    (let [calls (atom 0)]
      (with-redefs [clojure.core/requiring-resolve
                    (fn [sym]
                      (swap! calls inc)
                      (resolve sym))]
        (let [find-fn @(resolve 'meme.loader/find-lang-resource)]
          (find-fn "/test_meme_ns/greeter")
          (is (zero? @calls)
              "find-lang-resource must not call requiring-resolve"))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: Bug #2 — own infrastructure not in denylist
;; meme/* and meme_lang/* namespaces were not denied, so the loader would
;; try to intercept its own infrastructure (e.g. meme.registry), enabling
;; the infinite recursion in bug #1. Defense in depth.
;; ---------------------------------------------------------------------------

(deftest own-infrastructure-not-intercepted
  (testing "meme.* and meme_lang.* namespaces are denied by the denylist"
    (let [find-fn @(resolve 'meme.loader/find-lang-resource)]
      (is (nil? (find-fn "/meme/registry")) "meme/registry should be denied")
      (is (nil? (find-fn "/meme/loader")) "meme/loader should be denied")
      (is (nil? (find-fn "/meme/cli")) "meme/cli should be denied")
      (is (nil? (find-fn "/meme_lang/api")) "meme_lang/api should be denied")
      (is (nil? (find-fn "/meme_lang/stages")) "meme_lang/stages should be denied")
      (is (nil? (find-fn "/meme_lang/run")) "meme_lang/run should be denied"))))

;; ---------------------------------------------------------------------------
;; Scar tissue: Bug #3 — Babashka detection
;; alter-var-root on clojure.core/load is silently ineffective on Babashka
;; because SCI's require doesn't dispatch through that var. The loader now
;; detects Babashka and skips the alter-var-root.
;; ---------------------------------------------------------------------------

(deftest babashka-detection
  (testing "babashka? returns false on JVM Clojure"
    (is (not (#'meme.loader/babashka?))
        "Should not detect Babashka on JVM")))

;; ---------------------------------------------------------------------------
;; Cross-require: .meme file that requires another .meme file
;; ---------------------------------------------------------------------------

(deftest require-meme-that-requires-meme
  (testing ".meme file requiring another .meme namespace works"
    (require 'test-meme-ns.caller :reload)
    (let [greet-world (ns-resolve 'test-meme-ns.caller 'greet-world)]
      (is (some? greet-world) "greet-world should be defined")
      (is (= "Hello, World!" (greet-world))))))

;; ---------------------------------------------------------------------------
;; load-file: .meme and .clj files by filesystem path
;; ---------------------------------------------------------------------------

(deftest load-file-meme
  (testing "load-file handles .meme files"
    (load-file "test/resources/test_meme_ns/greeter.meme")
    (let [hello (ns-resolve 'test-meme-ns.greeter 'hello)]
      (is (some? hello) "hello should be defined after load-file")
      (is (= "Hello, load-file!" (hello "load-file"))))))

(deftest load-file-clj-fallback
  (testing "load-file still works for .clj files"
    (load-file "test/resources/test_meme_ns/shadow.clj")
    (let [source-var (ns-resolve 'test-meme-ns.shadow 'source)]
      (is (some? source-var) "source should be defined")
      (is (= :clj @source-var) ".clj file should load normally"))))

(deftest load-file-meme-preserves-caller-ns
  (testing "load-file does not clobber the caller's *ns*"
    (let [ns-before *ns*]
      (load-file "test/resources/test_meme_ns/greeter.meme")
      (is (= ns-before *ns*) "*ns* should be restored after load-file"))))
