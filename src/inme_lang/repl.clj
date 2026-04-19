(ns inme-lang.repl
  "Inme-specific REPL. Delegates to `meme-lang.repl` with inme's grammar
   injected via the `:grammar` opt."
  (:require [meme-lang.repl :as meme-repl]
            [inme-lang.grammar :as grammar]))

(defn start
  "Start the inme REPL."
  ([] (start {}))
  ([opts]
   (meme-repl/start (assoc (or opts {}) :grammar grammar/grammar))))
