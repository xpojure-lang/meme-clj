(ns meme.loader
  "Namespace loader for registered languages.
   Intercepts clojure.core/load to search for source files with
   registered extensions on the classpath. When found, the source
   is loaded through the lang's :run function.

   Any lang registered via meme.registry with an :extension and :run
   function gets require support automatically.

   Installed implicitly by run-file and the REPL — no manual setup.

   Security: a denylist prevents interception of core infrastructure
   namespaces (clojure.*, java.*, etc.). Only user/library namespaces
   are eligible for lang-based loading."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defonce ^:private original-load (atom nil))
(defonce ^:private original-load-file (atom nil))
(defonce ^:private installed? (atom false))
(defonce ^:private extensions-fn (atom nil))

;; ---------------------------------------------------------------------------
;; Namespace denylist — core infrastructure that must never be shadowed
;; ---------------------------------------------------------------------------

(def ^:private denied-prefixes
  "Namespace path prefixes that the loader must never intercept.
   These are core JVM/Clojure/tooling namespaces."
  ["clojure/" "java/" "javax/" "cljs/" "nrepl/" "cider/"
   "cognitect/" "clj_kondo/" "clj-kondo/" "meme/" "meme_lang/"])

(defn- denied-namespace?
  "Return true if the load path is for a denied namespace."
  [path]
  (let [base (if (str/starts-with? path "/") (subs path 1) path)]
    (some #(str/starts-with? base %) denied-prefixes)))

;; ---------------------------------------------------------------------------
;; Resource lookup
;; ---------------------------------------------------------------------------

(defn- find-lang-resource
  "Search the classpath for a file matching any registered lang extension.
   Returns [resource run-fn] or nil. Denies core infrastructure namespaces.
   Uses the eagerly-resolved extensions-fn (set at install! time) to avoid
   calling requiring-resolve during load interception, which would cause
   infinite recursion through load → lang-load → find-lang-resource."
  [path]
  (when-let [ext-fn @extensions-fn]
    (when-not (denied-namespace? path)
      (let [base (if (str/starts-with? path "/") (subs path 1) path)]
        (some (fn [[ext run-fn]]
                (let [resource (io/resource (str base ext))]
                  (when resource [resource run-fn])))
              (ext-fn))))))

;; ---------------------------------------------------------------------------
;; Load interception
;; ---------------------------------------------------------------------------

(def ^:private ^:dynamic *loading*
  "True while lang-load is executing a lang file. Prevents uninstall during load."
  false)

(defn- lang-load
  "Replacement for clojure.core/load that checks registered lang extensions.
   Wraps run-fn in a binding to save/restore *ns*, matching the behavior of
   Clojure's Compiler.load() which pushes thread bindings for *ns*."
  [& paths]
  (binding [*loading* true]
    (doseq [path paths]
      (if-let [[resource run-fn] (find-lang-resource path)]
        (binding [*ns* *ns*]
          (run-fn (slurp resource) {}))
        ;; No lang file found — delegate to original Clojure load
        (apply @original-load [path])))))

(defn- lang-load-file
  "Replacement for clojure.core/load-file that handles .meme files.
   Delegates to the lang's :run function for registered extensions,
   falls back to original load-file for everything else.
   Wraps run-fn in a binding to save/restore *ns*, matching Compiler/loadFile."
  [path]
  (let [run-fn (when-let [ext-fn @extensions-fn]
                 (some (fn [[ext run-fn]]
                         (when (str/ends-with? path ext) run-fn))
                       (ext-fn)))]
    (if run-fn
      (binding [*ns* *ns*]
        (run-fn (slurp path) {}))
      (@original-load-file path))))

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
  "Install the lang-aware loader. Idempotent — safe to call multiple times.
   After this, (require 'my.ns) searches all registered lang extensions.
   On Babashka, require-based loading is not supported (SCI bypasses
   clojure.core/load). A warning is printed on first install."
  []
  (when (compare-and-set! installed? false true)
    (reset! extensions-fn @(requiring-resolve 'meme.registry/registered-extensions))
    (reset! original-load-file @#'clojure.core/load-file)
    (alter-var-root #'clojure.core/load-file (constantly lang-load-file))
    (if (babashka?)
      (binding [*out* *err*]
        (println "meme: .meme require not available on Babashka (SCI bypasses clojure.core/load)."))
      (do (reset! original-load @#'clojure.core/load)
          (alter-var-root #'clojure.core/load (constantly lang-load)))))
  :installed)

(defn uninstall!
  "Uninstall the loader, restoring original clojure.core/load.
   Throws if called from within a lang-load (e.g. from .meme code during require)."
  []
  (when *loading*
    (throw (ex-info "Cannot uninstall loader while a lang file is being loaded"
                    {:reason :active-load})))
  (when (compare-and-set! installed? true false)
    (when-let [orig @original-load]
      (alter-var-root #'clojure.core/load (constantly orig)))
    (when-let [orig @original-load-file]
      (alter-var-root #'clojure.core/load-file (constantly orig)))
    (reset! original-load nil)
    (reset! original-load-file nil)
    (reset! extensions-fn nil))
  :uninstalled)
