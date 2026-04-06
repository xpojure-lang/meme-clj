(ns wlj-lang.stages
  (:require [meme.tools.parser :as pratt]
            [wlj-lang.grammar :as grammar]
            [wlj-lang.cst-reader :as cst-reader]))

(defn step-parse [ctx]
  (assoc ctx :cst (pratt/parse (:source ctx) grammar/grammar)))

(defn step-read [ctx]
  (assoc ctx :forms (cst-reader/read-forms (:cst ctx) (:opts ctx))))

(defn run [source opts]
  (-> {:source source :opts opts} step-parse step-read))
