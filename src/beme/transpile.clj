(ns beme.transpile
  (:require [beme.core :as core]))

(defn -main [direction & args]
  (let [f (first args)]
    (when-not f
      (println (str "Usage: clj -M:beme-" direction " <file>"))
      (System/exit 1))
    (println
      (case direction
        "to"   (core/beme->clj (slurp f))
        "from" (core/clj->beme (slurp f))
        (do (println (str "Unknown direction: " direction))
            (System/exit 1))))))
