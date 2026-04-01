(ns meme.lang.meme-classic
  "meme-classic: recursive-descent parser + Wadler-Lindig printer.

   The default lang. Supports all commands: :run, :repl, :format, :to-clj, :to-meme."
  (:require [meme.core :as core]
            [meme.emit.formatter.canon :as fmt-canon]
            [meme.emit.formatter.flat :as fmt-flat]))

(defn format-meme [source opts]
  (let [forms (core/meme->forms source)]
    (case (:style opts)
      "flat" (fmt-flat/format-forms forms)
      "clj"  (fmt-flat/format-clj forms)
      (fmt-canon/format-forms forms opts))))

(defn to-clj
  ([source] (core/meme->clj source {:read-cond :preserve}))
  ([source _opts] (to-clj source)))

#?(:clj
   (defn to-meme
     ([source] (core/clj->meme source))
     ([source _opts] (to-meme source))))
