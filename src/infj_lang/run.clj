(ns infj-lang.run
  "Infj-specific eval pipeline. Delegates to `meme-lang.run` with infj's
   grammar injected via the `:grammar` opt. All other meme conventions
   (shebang handling, BOM stripping, loader install, syntax-quote
   resolution) apply unchanged."
  (:require [meme-lang.run :as meme-run]
            [infj-lang.grammar :as grammar]))

(defn- with-grammar [opts]
  (assoc (or opts {}) :grammar grammar/grammar))

(defn run-string
  "Read infj source string, eval each form, return the last result."
  ([s] (run-string s {}))
  ([s eval-fn-or-opts]
   {:pre [(string? s)]}
   (let [opts (if (map? eval-fn-or-opts) eval-fn-or-opts {:eval eval-fn-or-opts})]
     (meme-run/run-string s (with-grammar opts)))))

(defn run-file
  "Read and eval an infj file. Returns the last result."
  ([path] (run-file path {}))
  ([path eval-fn-or-opts]
   (let [opts (if (map? eval-fn-or-opts) eval-fn-or-opts {:eval eval-fn-or-opts})]
     (meme-run/run-file path (with-grammar opts)))))
