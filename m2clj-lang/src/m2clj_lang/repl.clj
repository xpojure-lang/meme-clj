(ns m2clj-lang.repl
  "m2clj's REPL entry point. Injects m2clj's grammar and banner and
   delegates to `meme.tools.clj.repl`. Exists so m2clj-lang has a home
   for any m2clj-specific REPL concern that emerges later."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [meme.tools.clj.repl :as clj-repl]
            [meme.tools.clj.stages :as stages]
            [m2clj-lang.grammar :as grammar]))

(defn input-state
  "Returns :complete, :incomplete, or :invalid for the given input string."
  ([s] (input-state s nil))
  ([s opts] (clj-repl/input-state s (grammar/with-grammar opts)
              (fn [s o] (stages/run s (grammar/with-grammar o)))))
  ([s opts run-fn]
   (clj-repl/input-state s opts run-fn)))

(defn- m2clj-banner []
  (let [version (try (some-> (io/resource "meme/version.txt") slurp str/trim)
                     (catch Exception _ nil))]
    (if version
      (str "m2clj " version " REPL. Type m2clj expressions, balanced input to eval. Ctrl-D to exit.")
      "m2clj REPL. Type m2clj expressions, balanced input to eval. Ctrl-D to exit.")))

(defn start
  "Start the m2clj REPL.

   Optional opts:
     :install-loader? — default true; pass false to skip installing
                        `meme.loader`."
  ([] (start {}))
  ([opts]
   (clj-repl/start (assoc (grammar/with-grammar opts) :banner (m2clj-banner)))))
