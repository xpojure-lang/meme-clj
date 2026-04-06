(ns meme-lang.m-expr
  "M-expression data reader: #meme/m-expr (f (args...))
   Transforms M-expression notation into S-expressions at read time.
   Rule: (head (args...)) → (head args...) when second element is a list.")

(defn m->s
  "Transform M-expression form to S-expression.
   (f (x y)) → (f x y) — head with arg list spliced.
   (f x y)   → recurse into each element when second is not a list.
   Vectors, maps, sets, atoms pass through unchanged."
  [form]
  (if (seq? form)
    (let [[f args] form]
      (if (seq? args)
        (cons (m->s f) (map m->s args))
        (map m->s form)))
    form))
