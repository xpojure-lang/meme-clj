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
(defonce ^:private installed? (atom false))

;; ---------------------------------------------------------------------------
;; Namespace denylist — core infrastructure that must never be shadowed
;; ---------------------------------------------------------------------------

(def ^:private denied-prefixes
  "Namespace path prefixes that the loader must never intercept.
   These are core JVM/Clojure/tooling namespaces."
  ["clojure/" "java/" "javax/" "cljs/" "nrepl/" "cider/"
   "cognitect/" "clj_kondo/" "clj-kondo/"])

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
   Returns [resource run-fn] or nil. Denies core infrastructure namespaces."
  [path]
  (when-not (denied-namespace? path)
    (let [base (if (str/starts-with? path "/") (subs path 1) path)
          registry-fn @(requiring-resolve 'meme.registry/registered-extensions)]
      (some (fn [[ext run-fn]]
              (let [resource (io/resource (str base ext))]
                (when resource [resource run-fn])))
            (registry-fn)))))

;; ---------------------------------------------------------------------------
;; Load interception
;; ---------------------------------------------------------------------------

(def ^:private ^:dynamic *loading*
  "True while lang-load is executing a lang file. Prevents uninstall during load."
  false)

(defn- lang-load
  "Replacement for clojure.core/load that checks registered lang extensions."
  [& paths]
  (binding [*loading* true]
    (doseq [path paths]
      (if-let [[resource run-fn] (find-lang-resource path)]
        (run-fn (slurp resource) {})
        ;; No lang file found — delegate to original Clojure load
        (apply @original-load [path])))))

;; ---------------------------------------------------------------------------
;; Install / uninstall
;; ---------------------------------------------------------------------------

(defn install!
  "Install the lang-aware loader. Idempotent — safe to call multiple times.
   After this, (require 'my.ns) searches all registered lang extensions."
  []
  (when (compare-and-set! installed? false true)
    (reset! original-load @#'clojure.core/load)
    (alter-var-root #'clojure.core/load (constantly lang-load)))
  :installed)

(defn uninstall!
  "Uninstall the loader, restoring original clojure.core/load.
   Throws if called from within a lang-load (e.g. from .meme code during require)."
  []
  (when *loading*
    (throw (ex-info "Cannot uninstall loader while a lang file is being loaded"
                    {:reason :active-load})))
  (when (compare-and-set! installed? true false)
    (alter-var-root #'clojure.core/load (constantly @original-load))
    (reset! original-load nil))
  :uninstalled)
