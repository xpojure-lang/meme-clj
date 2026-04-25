(ns meme.loader-test
  "Tests for the meme namespace loader.
   Scar tissue: these tests were expanded after discovering four loader bugs
   that the original suite missed due to test-ordering dependencies and
   missing platform coverage. See commit history for details."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [meme.loader :as loader]
            ;; Required so mclj-lang registers itself with the registry.
            ;; Without this, the loader installs but finds no `.mclj` extension,
            ;; so require/load-file of `.mclj` files falls through to Clojure's
            ;; original load and fails. This made the tests implicitly depend
            ;; on test-ordering (passing only if some earlier test happened
            ;; to have loaded mclj-lang.api first).
            [mclj-lang.api])
  (:import (java.util.concurrent CountDownLatch TimeUnit)))

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

(deftest require-mclj-namespace
  (testing "require loads .mclj/.meme file from classpath"
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

(deftest mclj-takes-precedence-over-clj
  (testing ".mclj file is loaded when both .mclj and .clj exist"
    (require 'test-meme-ns.shadow :reload)
    (let [source-var (ns-resolve 'test-meme-ns.shadow 'source)]
      (is (some? source-var) "source should be defined")
      (is (= :mclj @source-var) ".mclj file should take precedence over .clj"))))

(deftest mclj-parse-error-propagates
  (testing "require of a .mclj file with syntax error throws"
    (is (thrown? Exception
                (require 'test-meme-ns.broken :reload)))))

;; ---------------------------------------------------------------------------
;; C4: Cannot uninstall loader while a lang-load is in flight on any thread.
;; Replaces the earlier thread-local `*loading*` guard, which only protected
;; same-thread uninstall and was documented as "not safe concurrently." The
;; guard now observes a shared load-counter under install-lock.
;; ---------------------------------------------------------------------------

(deftest uninstall-during-load-rejected
  (testing "uninstall! throws when counter shows any thread mid-load"
    (let [counter @(resolve 'meme.loader/load-counter)]
      (swap! counter inc)
      (try
        (let [ex (is (thrown? Exception (loader/uninstall!)))]
          (is (= :active-load (:reason (ex-data ex))))
          (is (pos? (:in-flight (ex-data ex) 0))))
        (finally (swap! counter dec)))))
  (testing "uninstall! succeeds once counter drains"
    ;; counter is 0 now; uninstall + reinstall should both work cleanly
    (loader/uninstall!)
    (loader/install!)))

;; ---------------------------------------------------------------------------
;; Scar tissue: concurrent uninstall from a second thread must observe the
;; shared load-counter, not a thread-local binding. The previous
;; `^:dynamic *loading*` guard silently allowed this to tear down the var
;; overrides mid-load on the other thread — documented as "not safe
;; concurrently" but not enforced. See src/meme/loader.clj comments.
;; ---------------------------------------------------------------------------

(deftest uninstall-from-other-thread-blocked-while-loading
  (testing "a second thread's uninstall! throws while another is mid-load"
    (let [entered  (CountDownLatch. 1)
          release  (CountDownLatch. 1)
          ;; Use the private `with-load-tracking` helper — it's the same
          ;; counter hook the real lang-load uses. We simulate a load that
          ;; parks until the main thread has exercised uninstall!.
          track    @(resolve 'meme.loader/with-load-tracking)
          loader-thread
          (future
            (track
              (fn []
                (.countDown entered)
                (.await release 5 TimeUnit/SECONDS))))]
      (try
        (is (.await entered 5 TimeUnit/SECONDS)
            "loader thread should enter its tracked section")
        (let [ex (is (thrown? Exception (loader/uninstall!)))]
          (is (= :active-load (:reason (ex-data ex)))))
        (finally
          (.countDown release)
          @loader-thread))
      ;; After the loader thread exits its tracked section the counter is
      ;; 0 again; uninstall must now succeed and we re-install for the
      ;; fixture teardown.
      (loader/uninstall!)
      (loader/install!))))

;; ---------------------------------------------------------------------------
;; Scar tissue: the dispatch-to-increment gap.
;;
;; A thread that has dispatched into `lang-load` via the var override but has
;; not yet incremented `load-counter` was previously unprotected. uninstall!
;; could acquire install-lock, observe counter=0, and tear down the var
;; overrides — including resetting the captured originals to nil — leaving the
;; first thread to resume into NPE on `@original-load`.
;;
;; Two fixes cover this:
;;  (a) `with-load-tracking` now performs the `swap! inc` under install-lock,
;;      so the tracked region opens atomically w.r.t. uninstall's observation.
;;  (b) `uninstall!` no longer nils the captured originals, so even a stale
;;      in-flight reference remains safe to deref.
;;
;; This test simulates the dispatch gap deterministically: we hold
;; install-lock ourselves (standing in for a thread paused between dispatch
;; and the swap!-inc), kick off uninstall! on another thread, and assert it
;; cannot tear down until we release the lock. Before fix (a) uninstall!
;; would have raced through; with the fix it blocks on the monitor.
;; ---------------------------------------------------------------------------

(deftest uninstall-blocks-on-install-lock-during-dispatch-gap
  (testing "uninstall! cannot proceed while install-lock is held by a pre-increment dispatch"
    (let [install-lock @(resolve 'meme.loader/install-lock)
          counter @(resolve 'meme.loader/load-counter)
          holding (CountDownLatch. 1)
          release (CountDownLatch. 1)
          uninstall-done (CountDownLatch. 1)
          uninstall-result (atom ::pending)
          lock-holder
          (future
            (locking install-lock
              (.countDown holding)
              (.await release 5 TimeUnit/SECONDS)
              ;; Simulate the thread reaching its tracked-section increment
              ;; while still holding the lock. Under the fix this is the
              ;; invariant that keeps uninstall out: counter becomes pos?
              ;; before the lock is released.
              (swap! counter inc)))]
      (try
        (is (.await holding 5 TimeUnit/SECONDS)
            "lock holder should have acquired install-lock")
        (let [_uninstall-thread
              (future
                (try (loader/uninstall!)
                     (reset! uninstall-result ::succeeded)
                     (catch Exception e
                       (reset! uninstall-result (ex-data e)))
                     (finally (.countDown uninstall-done))))]
          ;; Give uninstall! a moment to attempt to acquire the lock.
          ;; It must not proceed while we still hold it.
          (is (not (.await uninstall-done 200 TimeUnit/MILLISECONDS))
              "uninstall! must block while install-lock is held")
          (is (= ::pending @uninstall-result)
              "uninstall! must not have torn down while lock was held")
          ;; Release; lock-holder runs swap! inc and releases. uninstall!
          ;; then acquires, observes counter>0, and throws :active-load.
          (.countDown release)
          @lock-holder
          (is (.await uninstall-done 5 TimeUnit/SECONDS)
              "uninstall! should proceed after lock released")
          (is (map? @uninstall-result)
              "uninstall! must see in-flight counter and throw")
          (is (= :active-load (:reason @uninstall-result))))
        (finally
          ;; Drain counter and restore install state for the fixture.
          (swap! counter dec)
          (when (false? @@(resolve 'meme.loader/installed?))
            (loader/install!)))))))

(deftest concurrent-installs-are-idempotent
  (testing "10 threads calling install! in parallel leave loader in install state"
    (let [n         10
          start     (CountDownLatch. 1)
          done      (CountDownLatch. n)
          futures   (doall (for [_ (range n)]
                             (future
                               (.await start)
                               (try (loader/install!)
                                    (finally (.countDown done))))))]
      (.countDown start)
      (is (.await done 5 TimeUnit/SECONDS))
      (run! deref futures)
      ;; install! is idempotent by design; verify final state is consistent.
      (is (true? @@(resolve 'meme.loader/installed?)))
      (is (some? @@(resolve 'meme.loader/extensions-fn))))))

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
  (testing "uninstall! restores var overrides; installed? flag flips"
    (loader/uninstall!)
    (is (false? @@#'meme.loader/installed?)
        "installed? flag must be false after uninstall!")
    ;; extensions-fn (and the captured originals) intentionally retain
    ;; their values — in-flight callers dereference them, and nilling would
    ;; open an NPE window between dispatch and the tracked-section
    ;; increment. See `with-load-tracking` for the race this closes.
    (is (fn? @@#'meme.loader/extensions-fn)
        "extensions-fn atom intentionally retains its function after uninstall!")
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
;; Cross-require: .mclj file that requires another mclj-flavored file
;; (greeter is intentionally still a .meme file — exercises mixed-extension
;; loading and the deprecated-extension back-compat path together.)
;; ---------------------------------------------------------------------------

(deftest require-mclj-that-requires-mclj
  (testing ".mclj file requiring another mclj-lang namespace works"
    (require 'test-meme-ns.caller :reload)
    (let [greet-world (ns-resolve 'test-meme-ns.caller 'greet-world)]
      (is (some? greet-world) "greet-world should be defined")
      (is (= "Hello, World!" (greet-world))))))

;; ---------------------------------------------------------------------------
;; load-file: .mclj/.meme and .clj files by filesystem path
;; ---------------------------------------------------------------------------

(deftest load-file-mclj-by-deprecated-extension
  (testing "load-file handles .meme files via the back-compat path"
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

(deftest load-file-mclj-preserves-caller-ns
  (testing "load-file does not clobber the caller's *ns*"
    (let [ns-before *ns*]
      (load-file "test/resources/test_meme_ns/greeter.meme")
      (is (= ns-before *ns*) "*ns* should be restored after load-file"))))

;; ---------------------------------------------------------------------------
;; (Below) Scar tissue: lang-load called the original Clojure load once per non-lang
;; path inside a doseq. Clojure's load batches *loaded-libs* updates across
;; paths in a single call; per-path delegation defeated that tracking.
;; Fix: collect consecutive non-lang paths and flush them in one call,
;; preserving order around interleaved lang paths.
;; ---------------------------------------------------------------------------

(deftest lang-load-batches-non-lang-paths
  (let [lang-load (resolve 'meme.loader/lang-load)
        original-load-atom @(resolve 'meme.loader/original-load)]
    (testing "all-non-lang paths delegate to original-load in a single call"
      (let [calls (atom [])
            stub  (fn [& paths] (swap! calls conj (vec paths)))
            saved @original-load-atom]
        (try
          (reset! original-load-atom stub)
          (with-redefs [meme.loader/find-lang-resource (constantly nil)]
            (lang-load "/some/clj/a" "/some/clj/b" "/some/clj/c"))
          (is (= [["/some/clj/a" "/some/clj/b" "/some/clj/c"]] @calls)
              "all three paths should be delegated in one batched call")
          (finally (reset! original-load-atom saved)))))
    (testing "lang and non-lang paths are flushed in source order"
      ;; "/test_meme_ns/greeter" exists as a .meme classpath resource.
      ;; Mix it with non-lang paths and assert original-load batching boundaries.
      (let [calls (atom [])
            lang-runs (atom [])
            stub-load (fn [& paths] (swap! calls conj (vec paths)))
            stub-run-fn (fn [_src _opts] (swap! lang-runs conj :ran))
            saved @original-load-atom]
        (try
          (reset! original-load-atom stub-load)
          (with-redefs [meme.loader/find-lang-resource
                        (fn [path]
                          (when (= path "/test_meme_ns/greeter")
                            ;; Return a stub resource + run-fn so we don't
                            ;; actually slurp from the classpath.
                            [(java.io.StringReader. "") stub-run-fn]))
                        ;; slurp accepts a Reader, so the stub Reader works.
                        ]
            (lang-load "/a" "/b" "/test_meme_ns/greeter" "/c"))
          (is (= [["/a" "/b"] ["/c"]] @calls)
              "non-lang paths before and after the lang file should be batched separately, in order")
          (is (= 1 (count @lang-runs)) "lang file should be loaded once")
          (finally (reset! original-load-atom saved)))))
    (testing "a single non-lang path still delegates correctly"
      (let [calls (atom [])
            stub  (fn [& paths] (swap! calls conj (vec paths)))
            saved @original-load-atom]
        (try
          (reset! original-load-atom stub)
          (with-redefs [meme.loader/find-lang-resource (constantly nil)]
            (lang-load "/single/path"))
          (is (= [["/single/path"]] @calls))
          (finally (reset! original-load-atom saved)))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: a .mclj file with an `ns` form must produce a fully-interned
;; namespace (find-ns returns it), its vars carry correct :ns metadata, and
;; the caller's *ns* is unchanged after require. The code review flagged
;; that (binding [*ns* *ns*] ...) alone doesn't guarantee this when the
;; loaded file's ns form calls in-ns via a custom eval path.
;; ---------------------------------------------------------------------------

(deftest require-meme-ns-interns-namespace-and-preserves-caller
  (testing "the loaded namespace is interned and discoverable via find-ns"
    (require 'test-meme-ns.greeter :reload)
    (let [loaded-ns (find-ns 'test-meme-ns.greeter)]
      (is (some? loaded-ns)
          "find-ns must return the namespace, not just ns-resolve'd vars")
      (is (= 'test-meme-ns.greeter (ns-name loaded-ns)))))
  (testing "vars in the loaded ns carry correct :ns metadata"
    (let [hello-var (ns-resolve 'test-meme-ns.greeter 'hello)]
      (is (some? hello-var))
      (is (= 'test-meme-ns.greeter
             (ns-name (.-ns ^clojure.lang.Var hello-var)))
          "var's home namespace must be the loaded one, not the caller's")))
  (testing "caller's *ns* is unchanged after require"
    (let [ns-before *ns*]
      (require 'test-meme-ns.greeter :reload)
      (is (= ns-before *ns*)
          "require must not leak *ns* changes from the .meme ns form"))))

;; ---------------------------------------------------------------------------
;; Scar tissue: with-load-tracking must not decrement the counter if the
;; increment itself never succeeded. Previously both swap! calls lived inside
;; the try, so a throw on the inc path still fired the finally's dec — leaving
;; the counter at N-1 and breaking quiescence checks.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Deprecation warning for .meme/.memec/.memej/.memejs (one-time per process)
;; ---------------------------------------------------------------------------

(deftest deprecated-extension-emits-once-per-process
  (testing "first .meme path emits warning to *err*; subsequent paths are silent"
    (let [warned-atom @(resolve 'meme.loader/deprecation-warned?)
          saved       @warned-atom]
      (try
        (reset! warned-atom false)
        (let [err1 (java.io.StringWriter.)]
          (binding [*err* err1]
            (loader/warn-deprecated-extension! "/path/to/foo.meme"))
          (is (str/includes? (str err1) "deprecated"))
          (is (str/includes? (str err1) ".mclj"))
          (is (str/includes? (str err1) "foo.meme")))
        (let [err2 (java.io.StringWriter.)]
          (binding [*err* err2]
            (loader/warn-deprecated-extension! "/path/to/bar.memec"))
          (is (str/blank? (str err2))
              "second deprecated path should be silent (once-per-process)"))
        (finally (reset! warned-atom saved))))))

(deftest deprecated-extension-non-deprecated-paths-do-not-warn
  (testing ".mclj path does not emit a warning"
    (let [warned-atom @(resolve 'meme.loader/deprecation-warned?)
          saved       @warned-atom]
      (try
        (reset! warned-atom false)
        (let [err (java.io.StringWriter.)]
          (binding [*err* err]
            (loader/warn-deprecated-extension! "/path/to/foo.mclj")
            (loader/warn-deprecated-extension! "/path/to/bar.clj")
            (loader/warn-deprecated-extension! nil))
          (is (str/blank? (str err)))
          (is (false? @warned-atom)
              "warning state must remain unset after non-deprecated calls"))
        (finally (reset! warned-atom saved))))))

(deftest with-load-tracking-counter-balance
  (let [counter-atom @(resolve 'meme.loader/load-counter)
        track        @(resolve 'meme.loader/with-load-tracking)]
    (testing "counter unchanged after a successful body"
      (let [before @counter-atom]
        (track (fn [] :ok))
        (is (= before @counter-atom))))
    (testing "counter unchanged after a throwing body"
      (let [before @counter-atom]
        (try (track (fn [] (throw (ex-info "boom" {}))))
             (catch Exception _ nil))
        (is (= before @counter-atom))))
    (testing "counter unchanged if swap! inc itself throws (no erroneous dec)"
      (let [before @counter-atom
            orig-swap! swap!
            calls (volatile! 0)]
        (with-redefs [swap! (fn
                              ([a f]
                               (if (and (identical? a counter-atom) (= 0 @calls))
                                 (do (vswap! calls inc)
                                     (throw (ex-info "inc failed" {})))
                                 (orig-swap! a f)))
                              ([a f & args] (apply orig-swap! a f args)))]
          (try (track (fn [] :unreached))
               (catch Exception _ nil)))
        (is (= before @counter-atom))))))
