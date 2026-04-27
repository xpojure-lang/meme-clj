(ns m2clj-lang.run
  "m2clj's eval pipeline entry point. Injects m2clj's grammar and delegates
   to `meme.tools.clj.run`. Exists so m2clj-lang has a natural home for
   any m2clj-specific eval-time concern that emerges later."
  (:require [meme.tools.clj.run :as clj-run]
            [m2clj-lang.grammar :as grammar]))

(def default-resolve-symbol clj-run/default-resolve-symbol)

(defn run-string
  "Read m2clj source string, eval each form, return the last result."
  ([s] (run-string s {}))
  ([s eval-fn-or-opts]
   (let [opts (if (map? eval-fn-or-opts) eval-fn-or-opts {:eval eval-fn-or-opts})]
     (clj-run/run-string s (grammar/with-grammar opts)))))

(defn run-file
  "Read and eval an m2clj file. Returns the last result."
  ([path] (run-file path {}))
  ([path eval-fn-or-opts]
   (let [opts (if (map? eval-fn-or-opts) eval-fn-or-opts {:eval eval-fn-or-opts})]
     (clj-run/run-file path (grammar/with-grammar opts)))))
