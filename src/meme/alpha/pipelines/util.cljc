(ns meme.alpha.pipelines.util
  "Shared utilities for pipeline definitions.")

(defn meme-source?
  "Determine conversion direction from opts or source heuristic.
   opts may contain :direction :to-clj or :to-meme to override.
   Returns true if source is meme (convert to clj), false if clj (convert to meme)."
  [source opts]
  (let [dir (:direction opts)]
    (cond
      (= dir :to-clj) true
      (= dir :to-meme) false
      :else (not (re-find #"^\s*\(" source)))))
