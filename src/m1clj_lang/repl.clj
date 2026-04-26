(ns m1clj-lang.repl
  "Meme's REPL entry point. Injects meme's grammar and banner and
   delegates to `meme.tools.clj.repl`. Exists so m1clj-lang has a home
   for any meme-specific REPL concern that emerges later."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [meme.tools.clj.repl :as clj-repl]
            [meme.tools.clj.stages :as stages]
            [m1clj-lang.grammar :as grammar]))

(defn input-state
  "Returns :complete, :incomplete, or :invalid for the given input string."
  ([s] (input-state s nil))
  ([s opts] (clj-repl/input-state s (grammar/with-grammar opts)
              (fn [s o] (stages/run s (grammar/with-grammar o)))))
  ([s opts run-fn]
   (clj-repl/input-state s opts run-fn)))

(defn- meme-banner []
  (let [version (try (some-> (io/resource "meme/version.txt") slurp str/trim)
                     (catch Exception _ nil))]
    (if version
      (str "meme " version " REPL. Type meme expressions, balanced input to eval. Ctrl-D to exit.")
      "meme REPL. Type meme expressions, balanced input to eval. Ctrl-D to exit.")))

(defn start
  "Start the meme REPL.

   Optional opts:
     :install-loader? — default true; pass false to skip installing
                        `meme.loader`."
  ([] (start {}))
  ([opts]
   (clj-repl/start (assoc (grammar/with-grammar opts) :banner (meme-banner)))))
