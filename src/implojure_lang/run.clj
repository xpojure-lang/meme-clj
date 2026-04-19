(ns implojure-lang.run
  "Implojure's eval pipeline. Injects implojure's grammar and delegates to
   the shared commons run (`meme.tools.clj.run`) — same pattern as
   meme-lang.run. Implojure depends on the commons, not on meme-lang's
   infrastructure."
  (:require [meme.tools.clj.run :as clj-run]
            [implojure-lang.grammar :as grammar]))

(defn- with-grammar [opts]
  (assoc (or opts {}) :grammar grammar/grammar))

(defn run-string
  "Read implojure source string, eval each form, return the last result."
  ([s] (run-string s {}))
  ([s eval-fn-or-opts]
   {:pre [(string? s)]}
   (let [opts (if (map? eval-fn-or-opts) eval-fn-or-opts {:eval eval-fn-or-opts})]
     (clj-run/run-string s (with-grammar opts)))))

(defn run-file
  "Read and eval an implojure file. Returns the last result."
  ([path] (run-file path {}))
  ([path eval-fn-or-opts]
   (let [opts (if (map? eval-fn-or-opts) eval-fn-or-opts {:eval eval-fn-or-opts})]
     (clj-run/run-file path (with-grammar opts)))))
