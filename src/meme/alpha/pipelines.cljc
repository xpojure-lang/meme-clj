(ns meme.alpha.pipelines
  "Pipeline registry and resolution.

   A pipeline is a map of command functions:
     :run     (fn [source opts] → result)   — run a file
     :repl    (fn [opts] → nil)             — interactive loop
     :format  (fn [source opts] → text)     — format a file
     :convert (fn [source opts] → text)     — convert a file (both directions)

   Every key is optional. A pipeline supports exactly the commands it has keys for.
   The CLI dispatches by looking up the command key in the pipeline map.

   Built-in pipelines are defined in meme.alpha.pipelines.*:
     :meme-classic (default) — recursive-descent parser + Wadler-Lindig printer
     :meme-rewrite           — tree builder + rewrite rules
     :meme-trs               — token-stream term rewriting"
  (:require [meme.alpha.pipelines.meme-classic :as meme-classic]
            [meme.alpha.pipelines.meme-rewrite :as meme-rewrite]
            [meme.alpha.pipelines.meme-trs :as meme-trs]))

(def builtin
  {:meme-classic meme-classic/pipeline
   :meme-rewrite meme-rewrite/pipeline
   :meme-trs     meme-trs/pipeline})

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
