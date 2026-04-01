(ns meme.lang.meme-trs
  "meme-trs: token-stream term rewriting.

   Operates at the token level: tokenize → nest → rewrite → flatten → text.
   Bypasses the recursive-descent parser entirely for the meme→clj direction.
   Supports :run, :format, :to-clj, :to-meme. No :repl yet."
  (:require [meme.core :as core]
            [meme.emit.formatter.canon :as fmt-canon]
            [meme.rewrite :as rw]
            [meme.rewrite.rules :as rules]
            [meme.rewrite.emit :as remit]
            [meme.trs :as trs]))

(defn format-meme [source opts]
  (fmt-canon/format-forms (core/meme->forms source) opts))

(defn to-clj [source]
  (trs/meme->clj-text source))

#?(:clj
   (defn to-meme [source]
     (let [forms (core/clj->forms source)
           tagged (mapv #(rw/rewrite rules/s->m-rules %) forms)
           tagged (mapv #(rules/rewrite-inside-reader-conditionals
                           (fn [f] (rw/rewrite rules/s->m-rules f)) %)
                        tagged)]
       (remit/emit-forms tagged))))

#?(:clj
   (defn run-source [source opts]
     (let [clj-text (trs/meme->clj-text source)
           forms (core/clj->forms clj-text)
           eval-fn (or (:eval opts) eval)]
       (reduce (fn [_ form] (eval-fn form)) nil forms))))
