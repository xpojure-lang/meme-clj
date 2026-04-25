(ns meme.loader
  "Namespace loader for registered languages.
   Intercepts clojure.core/load to search for source files with
   registered extensions on the classpath. When found, the source
   is loaded through the lang's :run function.

   Any lang registered via meme.registry with an :extension and :run
   function gets require support automatically.

   Installed implicitly by run-file and the REPL — no manual setup.

   Concurrency: install!/uninstall! serialize on a shared monitor. A
   load counter tracks in-flight lang-loads across threads so that
   uninstall! cannot tear down the var overrides while another thread
   is mid-load. See `uninstall!` for the throw contract."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [meme.registry :as registry]))

(defonce ^:private original-load (atom nil))
(defonce ^:private original-load-file (atom nil))
(defonce ^:private installed? (atom false))
(defonce ^:private extensions-fn (atom nil))

;; Number of lang-load / lang-load-file invocations currently executing on
;; any thread. Incremented on entry, decremented in finally. uninstall!
;; observes this under the install-lock monitor to decide whether the loader
;; is quiescent and safe to remove.
(defonce ^:private load-counter (atom 0))

;; Shared monitor for install!/uninstall!. Holding this lock guarantees no
;; concurrent install or uninstall while we read/write the atom pair. Not
;; held during lang-load itself — loads are tracked via load-counter so that
;; concurrent loads proceed in parallel without mutual blocking.
(defonce ^:private install-lock (Object.))

;; ---------------------------------------------------------------------------
;; Resource lookup
;; ---------------------------------------------------------------------------

(defn- find-lang-resource
  "Search the classpath for a file matching any registered lang extension.
   Returns [resource run-fn] or nil. Uses the eagerly-resolved extensions-fn
   (set at install! time) to avoid calling requiring-resolve during load
   interception, which would cause infinite recursion through
   load → lang-load → find-lang-resource."
  [path]
  (when-let [ext-fn @extensions-fn]
    (let [base (if (str/starts-with? path "/") (subs path 1) path)]
      (some (fn [[ext run-fn]]
              (let [resource (io/resource (str base ext))]
                (when resource [resource run-fn])))
            (ext-fn)))))

;; ---------------------------------------------------------------------------
;; Load interception
;; ---------------------------------------------------------------------------

(defn- with-load-tracking
  "Run body-fn while this thread is considered mid-load. Increments the
   shared load-counter on entry and decrements it in finally. uninstall!
   reads the counter under install-lock to refuse quiescence if any thread
   is still inside a load.

   The increment runs before the try/finally so a throw from swap! itself
   leaves the counter in its original state rather than triggering a
   decrement that was never balanced by an increment.

   The increment is performed under install-lock — the same monitor
   uninstall! uses to read the counter. Without it, a thread that had
   already dispatched into lang-load (via the var override) but not yet
   incremented could race against uninstall!: uninstall would observe
   counter=0, tear down the var overrides, and the in-flight thread would
   resume into a now-invalid state. Holding the lock only for the single
   atom swap keeps uninstall!'s quiescence check honest without
   serializing the bodies of concurrent loads."
  [body-fn]
  (locking install-lock
    (swap! load-counter inc))
  (try (body-fn)
       (finally (swap! load-counter dec))))

(defn- lang-load
  "Replacement for clojure.core/load that checks registered lang extensions.
   Wraps run-fn in a binding to save/restore *ns*, matching the behavior of
   Clojure's Compiler.load() which pushes thread bindings for *ns*.

   Batches consecutive non-lang paths into a single original-load call to
   preserve Clojure's batched *loaded-libs* semantics — calling original-load
   per-path defeats its duplicate-load tracking across the batch."
  [& paths]
  (with-load-tracking
    (fn []
      (let [flush! (fn [pending]
                     (when (seq pending) (apply @original-load pending)))]
        (loop [remaining paths
               pending  []]
          (if (empty? remaining)
            (flush! pending)
            (let [path (first remaining)]
              (if-let [[resource run-fn] (find-lang-resource path)]
                (do (flush! pending)
                    (binding [*ns* *ns*]
                      (run-fn (slurp resource) {}))
                    (recur (rest remaining) []))
                (recur (rest remaining) (conj pending path))))))))))

(defn- lang-load-file
  "Replacement for clojure.core/load-file that handles .meme files.
   Delegates to the lang's :run function for registered extensions,
   falls back to original load-file for everything else.
   Wraps run-fn in a binding to save/restore *ns*, matching Compiler/loadFile."
  [path]
  (with-load-tracking
    (fn []
      (let [run-fn (when-let [ext-fn @extensions-fn]
                     (some (fn [[ext run-fn]]
                             (when (str/ends-with? path ext) run-fn))
                           (ext-fn)))]
        (if run-fn
          (binding [*ns* *ns*]
            (run-fn (slurp path) {}))
          (@original-load-file path))))))

;; ---------------------------------------------------------------------------
;; Platform detection
;; ---------------------------------------------------------------------------

(defn- babashka?
  "True when running under Babashka (SCI). Babashka's require does not
   dispatch through clojure.core/load, so alter-var-root is ineffective."
  []
  (some? (System/getProperty "babashka.version")))

;; ---------------------------------------------------------------------------
;; Install / uninstall
;; ---------------------------------------------------------------------------

(defn install!
  "Install the lang-aware loader. Idempotent — safe to call multiple times
   and safe to call concurrently from multiple threads.
   After this, (require 'my.ns) searches all registered lang extensions.
   On Babashka, require-based loading is not supported (SCI bypasses
   clojure.core/load)."
  []
  (locking install-lock
    (when (compare-and-set! installed? false true)
      (reset! extensions-fn registry/registered-extensions)
      (reset! original-load-file @#'clojure.core/load-file)
      (alter-var-root #'clojure.core/load-file (constantly lang-load-file))
      (when-not (babashka?)
        (reset! original-load @#'clojure.core/load)
        (alter-var-root #'clojure.core/load (constantly lang-load)))))
  :installed)

(defn uninstall!
  "Uninstall the loader, restoring the original clojure.core/load and
   clojure.core/load-file.

   Throws ex-info with {:reason :active-load, :in-flight N} if any thread
   — including the calling thread — is currently inside a lang-load. This
   prevents tearing down the var overrides while another thread is mid-load.

   Thread-safe: serialized with install! on a shared monitor. Safe to call
   concurrently with itself (second caller sees installed? false and is a
   no-op)."
  []
  (locking install-lock
    (let [in-flight @load-counter]
      (when (pos? in-flight)
        (throw (ex-info "Cannot uninstall loader while a lang file is being loaded"
                        {:reason :active-load
                         :in-flight in-flight}))))
    (when (compare-and-set! installed? true false)
      (when-let [orig @original-load]
        (alter-var-root #'clojure.core/load (constantly orig)))
      (when-let [orig @original-load-file]
        (alter-var-root #'clojure.core/load-file (constantly orig))))
    ;; Intentionally do NOT reset original-load, original-load-file, or
    ;; extensions-fn to nil. In-flight callers hold references to lang-load /
    ;; lang-load-file and dereference these atoms to delegate to the original
    ;; or look up registered extensions. Nilling them introduced an NPE
    ;; window between dispatch and the tracked-section increment; see the
    ;; note on `with-load-tracking`. A subsequent `install!` re-captures
    ;; these atoms (the CAS from false→true succeeds and overwrites), so
    ;; keeping stale references here is harmless.
    )
  :uninstalled)
