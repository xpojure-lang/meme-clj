(ns meme.alpha.pipelines
  "Pipeline registry, resolution, and EDN loading.

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
     :meme-trs               — token-stream term rewriting

   User pipelines are EDN files:
     {:run \"core.meme\" :format :meme-classic}
   Values: string → .meme file to eval before user file (for :run)
           keyword → reference to a built-in pipeline's command"
  (:require [clojure.edn :as edn]
            [meme.alpha.pipelines.meme-classic :as meme-classic]
            [meme.alpha.pipelines.meme-rewrite :as meme-rewrite]
            [meme.alpha.pipelines.meme-trs :as meme-trs]
            #?(:clj [meme.alpha.runtime.run :as run])))

(def builtin
  {:meme-classic meme-classic/pipeline
   :meme-rewrite meme-rewrite/pipeline
   :meme-trs     meme-trs/pipeline})

(def default-pipeline :meme-classic)

;; ---------------------------------------------------------------------------
;; EDN pipeline loading
;; ---------------------------------------------------------------------------

#?(:clj
   (defn- resolve-command-value
     "Resolve a single command value from an EDN pipeline definition.
      string → for :run, wraps as: eval the .meme file, then eval user source
      keyword → look up that command from a built-in pipeline"
     [command value]
     (cond
       (string? value)
       (case command
         :run (fn [source opts]
                (let [prelude-src (slurp value)]
                  (run/run-string prelude-src opts)
                  (run/run-string source opts)))
         ;; For other commands, string doesn't make sense yet
         (throw (ex-info (str "String value not supported for :" (name command)
                              " — use a keyword to reference a built-in")
                         {:command command :value value})))

       (keyword? value)
       (let [base (get builtin value)]
         (when-not base
           (throw (ex-info (str "Unknown built-in pipeline: " value) {:value value})))
         (let [cmd-fn (get base command)]
           (when-not cmd-fn
             (throw (ex-info (str "Built-in '" (name value) "' does not support :" (name command))
                             {:pipeline value :command command})))
           cmd-fn))

       :else
       (throw (ex-info (str "Invalid pipeline value for :" (name command)
                            " — expected string or keyword, got " (type value))
                       {:command command :value value})))))

#?(:clj
   (defn load-edn-pipeline
     "Load a pipeline from an EDN file. Returns a pipeline map with functions.
      Each key-value pair is resolved: strings become run-with-prelude functions,
      keywords reference built-in pipeline commands."
     [path]
     (let [edn-data (edn/read-string (slurp path))]
       (when-not (map? edn-data)
         (throw (ex-info (str "Pipeline EDN must be a map, got " (type edn-data))
                         {:path path})))
       (into {} (map (fn [[k v]] [k (resolve-command-value k v)]) edn-data)))))

;; ---------------------------------------------------------------------------
;; Resolution
;; ---------------------------------------------------------------------------

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
    (throw (ex-info (str "Pipeline '" (if (keyword? pipeline-name) (name pipeline-name) pipeline-name)
                         "' does not support :"
                         (name command)
                         " — supported: " (pr-str (vec (filter keyword? (keys pipeline)))))
                    {:pipeline pipeline-name :command command}))))
