(ns meme.convert
  "Unified convert: meme↔clj via named langs.
   Delegates to meme.lang for resolution and dispatch."
  (:require [meme.lang :as lang]))

(defn meme->clj
  "Convert meme source to Clojure source using the named lang.
   Accepts legacy names (:classic, :rewrite, :ts-trs)."
  ([src] (meme->clj src :meme-classic))
  ([src lang-name]
   (let [l (lang/resolve-lang lang-name)]
     (lang/check-support! l lang-name :to-clj)
     ((:to-clj l) src))))

#?(:clj
   (defn clj->meme
     "Convert Clojure source to meme source using the named lang.
   JVM/Babashka only."
     ([src] (clj->meme src :meme-classic))
     ([src lang-name]
      (let [l (lang/resolve-lang lang-name)]
        (lang/check-support! l lang-name :to-meme)
        ((:to-meme l) src)))))
