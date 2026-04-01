(ns meme.alpha.pipelines
  "Built-in pipeline definitions.

   A pipeline is a map of command functions:
     :run     (fn [source opts] → result)   — run a file
     :repl    (fn [opts] → nil)             — interactive loop
     :format  (fn [source opts] → text)     — format a file
     :convert (fn [source opts] → text)     — convert a file (both directions)

   Every key is optional. A pipeline supports exactly the commands it has keys for.
   The CLI dispatches by looking up the command key in the pipeline map.

   Built-in pipelines: :meme-classic (default), :meme-rewrite, :meme-trs."
  (:require [meme.alpha.core :as core]
            [meme.alpha.emit.formatter.canon :as fmt-canon]
            [meme.alpha.pipeline :as pipeline]
            [meme.alpha.rewrite :as rw]
            [meme.alpha.rewrite.tree :as tree]
            [meme.alpha.rewrite.rules :as rules]
            [meme.alpha.rewrite.emit :as remit]
            [meme.alpha.trs :as trs]
            #?(:clj [meme.alpha.runtime.run :as run])
            #?(:clj [meme.alpha.runtime.repl :as repl])))

;; ---------------------------------------------------------------------------
;; Direction detection
;; ---------------------------------------------------------------------------

(defn- meme-source?
  "Heuristic: does the source look like meme (vs Clojure)?
   Meme source has call syntax f(x); Clojure source has (f x).
   For convert, opts may contain :direction :to-clj or :to-meme to override."
  [source opts]
  (let [dir (:direction opts)]
    (cond
      (= dir :to-clj) true
      (= dir :to-meme) false
      ;; Heuristic: if source starts with ( it's likely Clojure
      ;; This matches the existing CLI behavior (detect from file extension)
      :else (not (re-find #"^\s*\(" source)))))

;; ---------------------------------------------------------------------------
;; clj→meme via S→M rewrite rules (used by rewrite and trs pipelines)
;; ---------------------------------------------------------------------------

#?(:clj
   (defn- clj->meme-via-rewrite [source]
     (let [forms (core/clj->forms source)
           tagged (mapv #(rw/rewrite rules/s->m-rules %) forms)
           tagged (mapv #(rules/rewrite-inside-reader-conditionals
                           (fn [f] (rw/rewrite rules/s->m-rules f)) %)
                        tagged)]
       (remit/emit-forms tagged))))

;; ---------------------------------------------------------------------------
;; meme-classic: recursive-descent parser + Wadler-Lindig printer
;; ---------------------------------------------------------------------------

(def meme-classic
  (merge
   {:format  (fn [source opts]
               (let [forms (core/meme->forms source)]
                 (fmt-canon/format-forms forms opts)))
    :convert (fn [source opts]
               (if (meme-source? source opts)
                 (core/meme->clj source opts)
                 #?(:clj (core/clj->meme source)
                    :cljs (throw (ex-info "clj→meme requires JVM" {})))))}
   #?(:clj {:run  (fn [source opts]
                     (run/run-string source opts))
            :repl (fn [opts]
                    (repl/start opts))})))

;; ---------------------------------------------------------------------------
;; meme-rewrite: tree builder + rewrite rules
;; ---------------------------------------------------------------------------

(def meme-rewrite
  (merge
   {:format  (fn [source opts]
               (let [forms (core/meme->forms source {:parser tree/rewrite-parser})]
                 (fmt-canon/format-forms forms opts)))
    :convert (fn [source opts]
               (if (meme-source? source opts)
                 (core/forms->clj
                  (:forms (pipeline/run source (merge opts {:parser tree/rewrite-parser
                                                             :read-cond :preserve}))))
                 #?(:clj (clj->meme-via-rewrite source)
                    :cljs (throw (ex-info "clj→meme requires JVM" {})))))}
   #?(:clj {:run  (fn [source opts]
                     (run/run-string source (assoc opts :parser tree/rewrite-parser)))
            :repl (fn [opts]
                    (repl/start (assoc opts :parser tree/rewrite-parser)))})))

;; ---------------------------------------------------------------------------
;; meme-trs: token-stream term rewriting
;; ---------------------------------------------------------------------------

(def meme-trs
  (merge
   {:format  (fn [source opts]
               (let [forms (core/meme->forms source)]
                 (fmt-canon/format-forms forms opts)))
    :convert (fn [source opts]
               (if (meme-source? source opts)
                 (trs/meme->clj-text source)
                 #?(:clj (clj->meme-via-rewrite source)
                    :cljs (throw (ex-info "clj→meme requires JVM" {})))))}
   #?(:clj {:run (fn [source opts]
                    (let [clj-text (trs/meme->clj-text source)]
                      (run/run-string clj-text opts)))})))
   ;; no :repl — trs doesn't support it yet

;; ---------------------------------------------------------------------------
;; Registry and resolution
;; ---------------------------------------------------------------------------

(def builtin
  {:meme-classic meme-classic
   :meme-rewrite meme-rewrite
   :meme-trs     meme-trs})

(def default-pipeline :meme-classic)

(defn resolve-pipeline
  "Resolve a pipeline by keyword name. Returns the pipeline map.
   Throws on unknown name."
  [pipeline-name]
  (let [name (or pipeline-name default-pipeline)]
    (or (get builtin name)
        (throw (ex-info (str "Unknown pipeline: " (pr-str name)
                             " — available: " (pr-str (keys builtin)))
                        {:pipeline name})))))

(defn supports?
  "Does the pipeline support the given command?"
  [pipeline command]
  (contains? pipeline command))

(defn check-support!
  "Assert that the pipeline supports the given command. Throws if not."
  [pipeline pipeline-name command]
  (when-not (supports? pipeline command)
    (throw (ex-info (str "Pipeline '" (name pipeline-name) "' does not support :"
                         (name command)
                         " — supported: " (pr-str (vec (filter keyword? (keys pipeline)))))
                    {:pipeline pipeline-name :command command}))))
