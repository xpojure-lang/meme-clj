(ns meme.alpha.pipelines.meme-classic
  "meme-classic pipeline: recursive-descent parser + Wadler-Lindig printer.

   The default pipeline. Supports all commands: :run, :repl, :format, :convert."
  (:require [meme.alpha.core :as core]
            [meme.alpha.emit.formatter.canon :as fmt-canon]
            [meme.alpha.pipelines.util :as util]
            #?(:clj [meme.alpha.runtime.run :as run])
            #?(:clj [meme.alpha.runtime.repl :as repl])))

(def pipeline
  (merge
   {:format  (fn [source opts]
               (let [forms (core/meme->forms source)]
                 (fmt-canon/format-forms forms opts)))
    :convert (fn [source opts]
               (if (util/meme-source? source opts)
                 (core/meme->clj source opts)
                 #?(:clj (core/clj->meme source)
                    :cljs (throw (ex-info "clj→meme requires JVM" {})))))}
   #?(:clj {:run  (fn [source opts]
                     (run/run-string source opts))
            :repl (fn [opts]
                    (repl/start opts))})))
