(ns meme.lang.meme-rewrite
  "meme-rewrite: tree builder + rewrite rules.

   Alternative parser that builds explicit m-call/bracket/brace tagged trees,
   then applies rewrite rules to transform to S-expressions.
   Supports all commands: :run, :repl, :format, :to-clj, :to-meme."
  (:require [meme.core :as core]
            [meme.emit.formatter.canon :as fmt-canon]
            [meme.emit.formatter.flat :as fmt-flat]
            [meme.stages :as stages]
            [meme.rewrite.tree :as tree]
            #?(:clj [meme.lang.shared :as shared])
            #?(:clj [meme.runtime.run :as run])
            #?(:clj [meme.runtime.repl :as repl])))

(def ^:private rewrite-opts {:parser tree/rewrite-parser})

(defn format-meme
  "Format meme source via rewrite-based parser. Supports :style in opts."
  [source opts]
  (let [forms (core/meme->forms source rewrite-opts)]
    (case (:style opts)
      "flat" (fmt-flat/format-forms forms)
      "clj"  (fmt-flat/format-clj forms)
      (fmt-canon/format-forms forms opts))))

(defn to-clj
  "Convert meme source to Clojure text using rewrite-based tree builder."
  ([source]
   (core/forms->clj
     (:forms (stages/run source (merge rewrite-opts {:read-cond :preserve})))))
  ([source _opts] (to-clj source)))

#?(:clj
   (def ^{:doc "Convert Clojure source text to meme syntax via rewrite rules. JVM only."}
     to-meme shared/clj->meme-text))

#?(:clj
   (defn run-source
     "Eval meme source text using the rewrite parser. JVM only."
     [source opts]
     (run/run-string source (merge opts rewrite-opts))))

#?(:clj
   (defn start-repl
     "Start an interactive REPL using the rewrite parser. JVM only."
     [opts]
     (repl/start (merge opts rewrite-opts))))
