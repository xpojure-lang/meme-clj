(ns beme.alpha.scan.source
  "Source-position utilities shared across pipeline stages.
   The tokenizer and grouper must agree on how (line, col) maps to
   character offsets — this namespace is that shared definition.")

(defn line-col->offset
  "Convert 1-indexed line/col to a 0-indexed character offset in source."
  [source line col]
  (let [n (count source)]
    (loop [i 0 cur-line 1 cur-col 1]
      (cond
        (and (= cur-line line) (= cur-col col)) i
        (>= i n) i
        (= (nth source i) \newline) (recur (inc i) (inc cur-line) 1)
        :else (recur (inc i) cur-line (inc cur-col))))))
