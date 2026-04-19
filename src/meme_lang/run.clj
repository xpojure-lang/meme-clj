(ns meme-lang.run
  "Meme's eval pipeline entry point. Injects meme's grammar and delegates
   to `meme.tools.clj.run`. Exists so meme-lang has a natural home for
   any meme-specific eval-time concern that emerges later."
  (:require [meme.tools.clj.run :as clj-run]
            [meme-lang.grammar :as grammar]))

(def default-resolve-symbol clj-run/default-resolve-symbol)

(defn- with-meme-grammar [opts]
  (if (:grammar opts) opts (assoc (or opts {}) :grammar grammar/grammar)))

(defn run-string
  "Read meme source string, eval each form, return the last result."
  ([s] (run-string s {}))
  ([s eval-fn-or-opts]
   (let [opts (if (map? eval-fn-or-opts) eval-fn-or-opts {:eval eval-fn-or-opts})]
     (clj-run/run-string s (with-meme-grammar opts)))))

(defn run-file
  "Read and eval a meme file. Returns the last result."
  ([path] (run-file path {}))
  ([path eval-fn-or-opts]
   (let [opts (if (map? eval-fn-or-opts) eval-fn-or-opts {:eval eval-fn-or-opts})]
     (clj-run/run-file path (with-meme-grammar opts)))))
