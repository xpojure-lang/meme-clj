(ns wlj-lang.run
  (:require [meme.tools.run :as tools-run]
            [wlj-lang.stages :as stages]))

(defn run-string [s opts]
  (tools-run/run-string s
    {:run-fn       (fn [source reader-opts] (stages/run source reader-opts))
     :expand-forms (fn [forms _opts] forms)
     :reader-opts  (or opts {})
     :eval         (:eval opts)}))
