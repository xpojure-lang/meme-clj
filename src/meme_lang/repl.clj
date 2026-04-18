(ns meme-lang.repl
  "Meme-specific REPL. Wires meme stages, error formatting, keyword resolution,
   and syntax-quote resolution into the generic REPL infrastructure.

   Stays in the language tier: no dependency on meme.loader. Callers that need
   classpath .meme require support inject it via opts (:install-loader).
   JVM/Babashka only."
  (:require [meme.tools.repl :as repl]
            [meme-lang.stages :as stages]
            [meme-lang.errors :as errors]
            [meme-lang.run :as meme-run]
            [clojure.string :as str]
            [clojure.java.io :as io]))

(defn input-state
  "Returns :complete, :incomplete, or :invalid for the given input string."
  ([s] (input-state s nil))
  ([s opts] (input-state s opts stages/run))
  ([s opts run-fn]
   (repl/input-state s run-fn opts)))

(defn start
  "Start the meme REPL.

   Optional opts:
     :install-loader — zero-arg fn, called before the REPL loop starts (for
                       classpath .meme require support; the CLI passes
                       meme.loader/install!)."
  ([] (start {}))
  ([opts]
   (when-let [install (:install-loader opts)] (install))
   (let [stages-impl (:stages opts)
         run-fn (or (:run-fn stages-impl) stages/run)
         expand-fn (or (:expand-forms stages-impl) stages/expand-syntax-quotes)
         reader-opts (let [rk (or (:resolve-keyword opts)
                                  (fn [raw]
                                    (let [s (subs raw 2)]
                                      (if-let [idx (clojure.string/index-of s "/")]
                                        (let [alias-str (subs s 0 idx)
                                              kw-name (subs s (inc idx))
                                              alias-ns (get (ns-aliases *ns*) (symbol alias-str))]
                                          (if alias-ns
                                            (keyword (str alias-ns) kw-name)
                                            (keyword alias-str kw-name)))
                                        (keyword (str (ns-name *ns*)) s)))))
                           base (cond-> {:resolve-keyword rk}
                                  (:parser opts) (assoc :parser (:parser opts)))]
                       (cond-> base
                         (not (:resolve-symbol opts))
                         (assoc :resolve-symbol meme-run/default-resolve-symbol)))
         version (try (some-> (io/resource "meme/version.txt") slurp str/trim)
                      (catch Exception _ nil))
         banner (if version
                  (str "meme " version " REPL. Type meme expressions, balanced input to eval. Ctrl-D to exit.")
                  "meme REPL. Type meme expressions, balanced input to eval. Ctrl-D to exit.")]
     (repl/start {:run-fn run-fn
                  :expand-forms expand-fn
                  :format-error (fn [e input] (errors/format-error e input))
                  :eval (:eval opts)
                  :read-line (:read-line opts)
                  :banner banner
                  :reader-opts reader-opts
                  :prelude (:prelude opts)}))))
