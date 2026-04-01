(ns meme.lang.meme-classic
  "meme-classic: recursive-descent parser + Wadler-Lindig printer.

   The default lang. Supports all commands: :run, :repl, :format, :to-clj, :to-meme."
  (:require [meme.core :as core]
            [meme.emit.formatter.canon :as fmt-canon]))

(defn format-meme [source opts]
  (fmt-canon/format-forms (core/meme->forms source) opts))

(defn to-clj [source]
  (core/meme->clj source {:read-cond :preserve}))

#?(:clj
   (defn to-meme [source]
     (core/clj->meme source)))
