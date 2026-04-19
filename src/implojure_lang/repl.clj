(ns implojure-lang.repl
  "Implojure's REPL. Injects implojure's grammar and delegates to the
   shared commons REPL (`meme.tools.clj.repl`) — peer pattern to
   meme-lang.repl."
  (:require [meme.tools.clj.repl :as clj-repl]
            [implojure-lang.grammar :as grammar]))

(defn start
  "Start the implojure REPL."
  ([] (start {}))
  ([opts]
   (clj-repl/start (assoc (or opts {}) :grammar grammar/grammar
                          :banner "implojure REPL. Ctrl-D to exit."))))
