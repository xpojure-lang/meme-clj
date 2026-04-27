(ns meme-lang.repl
  "Meme-specific REPL. Wires meme stages, error formatting, keyword resolution,
   and syntax-quote resolution into the generic REPL infrastructure.

   Installs `meme.loader` by default so that `require` and `load-file` of
   `.meme` namespaces work from the REPL. Pass `:install-loader? false` to
   skip (for hosts that own their own load interception).
   JVM/Babashka only."
  (:require [meme.tools.repl :as repl]
            [meme.loader :as loader]
            [meme-lang.stages :as stages]
            [meme-lang.errors :as errors]
            [meme-lang.run :as meme-run]
            [clojure.string :as str]
            [clojure.java.io :as io]))

(defn input-state
  "Returns :complete, :incomplete, or :invalid for the given input string.
   Uses `stages/run` (parse + read) — completeness checking needs no eval."
  ([s] (input-state s nil))
  ([s opts] (input-state s opts stages/run))
  ([s opts run-fn]
   (repl/input-state s run-fn opts)))

(defn- meme-repl-pipeline
  "REPL eval pipeline: parse, read, evaluate reader conditionals.
   Syntax-quote expansion is applied separately via expand-fn by the
   generic REPL harness."
  [source opts]
  (-> {:source source :opts opts}
      stages/step-parse
      stages/step-read
      stages/step-evaluate-reader-conditionals))

(defn start
  "Start the meme REPL.

   Optional opts:
     :install-loader? — default true; pass false to skip installing
                        `meme.loader`."
  ([] (start {}))
  ([opts]
   (when-not (false? (:install-loader? opts)) (loader/install!))
   (let [stages-impl (:stages opts)
         run-fn (or (:run-fn stages-impl) meme-repl-pipeline)
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
