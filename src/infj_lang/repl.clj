(ns infj-lang.repl
  "Infj-specific REPL. Delegates to `meme-lang.repl` with infj's grammar
   injected via the `:grammar` opt."
  (:require [meme-lang.repl :as meme-repl]
            [infj-lang.grammar :as grammar]))

(defn start
  "Start the infj REPL."
  ([] (start {}))
  ([opts]
   (meme-repl/start (assoc (or opts {}) :grammar grammar/grammar))))
