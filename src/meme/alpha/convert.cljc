(ns meme.alpha.convert
  "Unified convert: meme↔clj via three named pipelines.

   Each pipeline is a map with :name, :parse, :meme->clj, and :clj->meme.
   The CLI and public API look up the pipeline by keyword and call through it."
  (:require [meme.alpha.core :as core]
            [meme.alpha.collapsar.meme :as collapsar]
            [meme.alpha.pipeline :as pipeline]
            [meme.alpha.rewrite :as rw]
            [meme.alpha.rewrite.tree :as tree]
            [meme.alpha.rewrite.rules :as rules]
            [meme.alpha.rewrite.emit :as remit]))

;; ---------------------------------------------------------------------------
;; Pipeline constants
;; ---------------------------------------------------------------------------

(def classic-pipeline
  "Classic: recursive-descent parser + Wadler-Lindig printer."
  {:name     :classic
   :parse    (fn [src opts] (:forms (pipeline/run src opts)))
   :meme->clj (fn [src opts] (core/meme->clj src opts))
   #?@(:clj [:clj->meme (fn [src] (core/clj->meme src))])})

(def rewrite-pipeline
  "Rewrite: tree builder + rewrite rules."
  {:name     :rewrite
   :parse    (fn [src opts] (:forms (pipeline/run src (assoc opts :parser tree/rewrite-parser))))
   :meme->clj (fn [src opts]
                (core/forms->clj
                  (:forms (pipeline/run src (merge opts {:parser tree/rewrite-parser
                                                         :read-cond :preserve})))))
   #?@(:clj [:clj->meme (fn [src]
                           (let [forms (core/clj->forms src)
                                 tagged (mapv #(rw/rewrite rules/s->m-rules %) forms)
                                 tagged (mapv #(rules/rewrite-inside-reader-conditionals
                                                 (fn [f] (rw/rewrite rules/s->m-rules f)) %)
                                              tagged)]
                             (remit/emit-forms tagged)))])})

(def collapsar-pipeline
  "Collapsar: tree builder + collapsar phases."
  {:name     :collapsar
   :parse    (fn [src opts] (:forms (pipeline/run src (assoc opts :parser tree/rewrite-parser))))
   :meme->clj (fn [src opts] (collapsar/meme->clj src opts))
   #?@(:clj [:clj->meme (fn [src] (collapsar/clj->meme src))])})

(def pipelines
  "Available pipelines by keyword."
  {:classic   classic-pipeline
   :rewrite   rewrite-pipeline
   :collapsar collapsar-pipeline})

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn resolve-pipeline
  "Look up a pipeline by keyword. Throws on unknown name."
  [pipeline-name]
  (or (get pipelines pipeline-name)
      (throw (ex-info (str "Unknown pipeline: " pipeline-name
                           " — must be one of: classic, rewrite, collapsar") {}))))

(defn meme->clj
  "Convert meme source to Clojure source using the named pipeline."
  ([src] (meme->clj src :classic))
  ([src pipeline-name]
   ((:meme->clj (resolve-pipeline pipeline-name)) src {:read-cond :preserve})))

#?(:clj
   (defn clj->meme
     "Convert Clojure source to meme source using the named pipeline.
   JVM/Babashka only."
     ([src] (clj->meme src :classic))
     ([src pipeline-name]
      ((:clj->meme (resolve-pipeline pipeline-name)) src))))
