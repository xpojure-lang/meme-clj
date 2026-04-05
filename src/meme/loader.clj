(ns meme.loader
  "Custom namespace loader for .meme files.
   Intercepts clojure.core/load to search for .meme source files
   on the classpath. When a .meme file is found for a namespace,
   it is read through the meme pipeline and eval'd.

   Usage:
     (meme.loader/install!)   — install the loader (idempotent)
     (meme.loader/uninstall!) — restore original load

   After install!, (require 'my.lib) will find my/lib.meme on the
   classpath and load it through the meme pipeline, just like Clojure
   finds my/lib.clj."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defonce ^:private original-load (atom nil))
(defonce ^:private installed? (atom false))

(defn- ns-to-path
  "Convert a namespace-style path segment to a file path.
   Clojure's load passes paths like /my/ns (with leading slash, underscored).
   We strip the slash and append .meme."
  [path]
  (str (if (str/starts-with? path "/") (subs path 1) path) ".meme"))

(defn- find-meme-resource
  "Find a .meme file on the classpath for the given load path."
  [path]
  (let [meme-path (ns-to-path path)]
    (io/resource meme-path)))

(defn- load-meme-ns
  "Load a .meme namespace from the given resource URL."
  [resource]
  (let [source (slurp resource)
        ;; Resolve the run infrastructure lazily to avoid circular deps
        run-string @(requiring-resolve 'meme-lang.run/run-string)]
    (run-string source {})))

(defn- meme-load
  "Replacement for clojure.core/load that checks for .meme files first."
  [& paths]
  (doseq [path paths]
    (let [meme-resource (find-meme-resource path)]
      (if meme-resource
        (load-meme-ns meme-resource)
        ;; No .meme file — delegate to original Clojure load
        (apply @original-load [path])))))

(defn install!
  "Install the meme loader. Idempotent — safe to call multiple times.
   After this, (require 'my.ns) will find my/ns.meme on the classpath."
  []
  (when (compare-and-set! installed? false true)
    (reset! original-load @#'clojure.core/load)
    (alter-var-root #'clojure.core/load (constantly meme-load)))
  :installed)

(defn uninstall!
  "Uninstall the meme loader, restoring original clojure.core/load."
  []
  (when (compare-and-set! installed? true false)
    (alter-var-root #'clojure.core/load (constantly @original-load))
    (reset! original-load nil))
  :uninstalled)
