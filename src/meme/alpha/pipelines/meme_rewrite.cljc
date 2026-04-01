(ns meme.alpha.pipelines.meme-rewrite
  "meme-rewrite pipeline: tree builder + rewrite rules.

   Alternative parser that builds explicit m-call/bracket/brace tagged trees,
   then applies rewrite rules to transform to S-expressions.
   Supports all commands: :run, :repl, :format, :convert."
  (:require [meme.alpha.core :as core]
            [meme.alpha.emit.formatter.canon :as fmt-canon]
            [meme.alpha.pipeline :as pipeline]
            [meme.alpha.pipelines.util :as util]
            [meme.alpha.rewrite :as rw]
            [meme.alpha.rewrite.tree :as tree]
            [meme.alpha.rewrite.rules :as rules]
            [meme.alpha.rewrite.emit :as remit]
            #?(:clj [meme.alpha.runtime.run :as run])
            #?(:clj [meme.alpha.runtime.repl :as repl])))

#?(:clj
   (defn- clj->meme [source]
     (let [forms (core/clj->forms source)
           tagged (mapv #(rw/rewrite rules/s->m-rules %) forms)
           tagged (mapv #(rules/rewrite-inside-reader-conditionals
                           (fn [f] (rw/rewrite rules/s->m-rules f)) %)
                        tagged)]
       (remit/emit-forms tagged))))

(def pipeline
  (merge
   {:format  (fn [source opts]
               (let [forms (core/meme->forms source {:parser tree/rewrite-parser})]
                 (fmt-canon/format-forms forms opts)))
    :convert (fn [source opts]
               (if (util/meme-source? source opts)
                 (core/forms->clj
                  (:forms (pipeline/run source (merge opts {:parser tree/rewrite-parser
                                                             :read-cond :preserve}))))
                 #?(:clj (clj->meme source)
                    :cljs (throw (ex-info "clj→meme requires JVM" {})))))}
   #?(:clj {:run  (fn [source opts]
                     (run/run-string source (assoc opts :parser tree/rewrite-parser)))
            :repl (fn [opts]
                    (repl/start (assoc opts :parser tree/rewrite-parser)))})))
