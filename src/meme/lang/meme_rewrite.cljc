(ns meme.lang.meme-rewrite
  "meme-rewrite: tree builder + rewrite rules.

   Alternative parser that builds explicit m-call/bracket/brace tagged trees,
   then applies rewrite rules to transform to S-expressions.
   Supports all commands: :run, :repl, :format, :to-clj, :to-meme."
  (:require [meme.core :as core]
            [meme.emit.formatter.canon :as fmt-canon]
            [meme.emit.formatter.flat :as fmt-flat]
            [meme.stages :as stages]
            [meme.rewrite :as rw]
            [meme.rewrite.tree :as tree]
            [meme.rewrite.rules :as rules]
            [meme.rewrite.emit :as remit]
            #?(:clj [meme.runtime.run :as run])
            #?(:clj [meme.runtime.repl :as repl])))

(def ^:private rewrite-opts {:parser tree/rewrite-parser})

(defn format-meme [source opts]
  (let [forms (core/meme->forms source rewrite-opts)]
    (case (:style opts)
      "flat" (fmt-flat/format-forms forms)
      "clj"  (fmt-flat/format-clj forms)
      (fmt-canon/format-forms forms opts))))

(defn to-clj
  ([source]
   (core/forms->clj
     (:forms (stages/run source (merge rewrite-opts {:read-cond :preserve})))))
  ([source _opts] (to-clj source)))

#?(:clj
   (defn to-meme
     ([source]
      (let [forms (core/clj->forms source)
            tagged (mapv #(rw/rewrite rules/s->m-rules %) forms)
            tagged (mapv #(rules/rewrite-inside-reader-conditionals
                            (fn [f] (rw/rewrite rules/s->m-rules f)) %)
                         tagged)]
        (remit/emit-forms tagged)))
     ([source _opts] (to-meme source))))

#?(:clj
   (defn run-source [source opts]
     (run/run-string source (merge opts rewrite-opts))))

#?(:clj
   (defn start-repl [opts]
     (repl/start (merge opts rewrite-opts))))
