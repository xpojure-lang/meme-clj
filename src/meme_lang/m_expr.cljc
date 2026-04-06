(ns meme-lang.m-expr
  "M-expression data reader: #meme/m-expr (f (args...))
   Transforms M-expression notation into S-expressions at read time.

   One rule: a two-element list (head (args...)) splices to (head args...).
   The second element is ALWAYS the arg list — never a nested call.
   Nested calls as single args need double-wrapping:

     (f (x y))         → (f x y)         ;; f with two args
     (f ((g (x))))     → (f (g x))       ;; f with one arg: g(x)
     (f ((g (x)) y))   → (f (g x) y)    ;; f with two args: g(x) and y

   Lists with 1 or 3+ elements are not spliced — just recursed into.
   Vectors, maps, and sets are walked recursively. Atoms pass through.

   Usage: #meme/m-expr (defn (greet [name] (str (\"Hello \" name))))
   Ships via data_readers.clj — available when meme-clj is on the classpath.")

(defn m->s
  "Transform M-expression form to S-expression.
   (f (args)) → (f args...) — two-element list with arg list spliced.
   The outer parens are always the arg list. Always. Nested calls as
   single args need double-wrapping: (f ((g (x)))) → (f (g x)).
   Lists with 1 or 3+ elements recurse into each element.
   Vectors, maps, sets walked recursively. Atoms pass through."
  [form]
  (cond
    (seq? form)
    (let [[f args & more] form]
      (if (and (seq? args) (nil? more))
        (cons (m->s f) (map m->s args))
        (map m->s form)))

    (vector? form)
    (mapv m->s form)

    (map? form)
    (into {} (map (fn [[k v]] [(m->s k) (m->s v)])) form)

    (set? form)
    (into #{} (map m->s) form)

    :else form))
