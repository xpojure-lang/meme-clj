(ns meme.alpha.convert
  "Unified convert: meme↔clj via named pipelines.

   Delegates to meme.alpha.pipelines — each pipeline is a command map.
   This module provides the public API for conversion, maintaining backward
   compatibility with the legacy :classic/:rewrite/:ts-trs pipeline names."
  (:require [meme.alpha.pipelines :as pipelines]))

;; ---------------------------------------------------------------------------
;; Legacy name mapping: :classic → :meme-classic, etc.
;; ---------------------------------------------------------------------------

(def ^:private legacy-names
  {:classic :meme-classic
   :rewrite :meme-rewrite
   :ts-trs  :meme-trs})

(defn- normalize-name [pipeline-name]
  (get legacy-names pipeline-name pipeline-name))

;; ---------------------------------------------------------------------------
;; Public API (stable — callers don't need to change)
;; ---------------------------------------------------------------------------

(def pipelines
  "Available pipelines by keyword. Includes both legacy and new names."
  (merge pipelines/builtin
         (zipmap (keys legacy-names)
                 (map #(get pipelines/builtin %) (vals legacy-names)))))

(defn resolve-pipeline
  "Look up a pipeline by keyword. Supports both legacy names (:classic, :rewrite, :ts-trs)
   and new names (:meme-classic, :meme-rewrite, :meme-trs).
   Throws on unknown name."
  [pipeline-name]
  (or (get pipelines pipeline-name)
      (throw (ex-info (str "Unknown pipeline: " pipeline-name
                           " — must be one of: " (pr-str (keys pipelines))) {}))))

(defn meme->clj
  "Convert meme source to Clojure source using the named pipeline."
  ([src] (meme->clj src :classic))
  ([src pipeline-name]
   (let [p (resolve-pipeline (normalize-name pipeline-name))]
     (pipelines/check-support! p pipeline-name :convert)
     ((:convert p) src {:read-cond :preserve :direction :to-clj}))))

#?(:clj
   (defn clj->meme
     "Convert Clojure source to meme source using the named pipeline.
   JVM/Babashka only."
     ([src] (clj->meme src :classic))
     ([src pipeline-name]
      (let [p (resolve-pipeline (normalize-name pipeline-name))]
        (pipelines/check-support! p pipeline-name :convert)
        ((:convert p) src {:direction :to-meme})))))
