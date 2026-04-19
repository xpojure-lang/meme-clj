(ns implojure-lang.repl
  "Implojure-specific REPL. Delegates to `meme-lang.repl` with implojure's grammar
   injected via the `:grammar` opt."
  (:require [meme-lang.repl :as meme-repl]
            [implojure-lang.grammar :as grammar]))

(defn start
  "Start the implojure REPL."
  ([] (start {}))
  ([opts]
   (meme-repl/start (assoc (or opts {}) :grammar grammar/grammar))))
