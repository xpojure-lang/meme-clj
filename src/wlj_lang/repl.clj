(ns wlj-lang.repl
  (:require [meme.tools.repl :as tools-repl]
            [wlj-lang.stages :as stages]))

(defn start [opts]
  (tools-repl/start
    (merge opts
           {:run-fn       (fn [source reader-opts] (stages/run source reader-opts))
            :expand-forms (fn [forms _opts] forms)
            :banner       "wlj REPL — Wolfram-style syntax for Clojure"})))
