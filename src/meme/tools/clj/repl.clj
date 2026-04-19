(ns meme.tools.clj.repl
  "Clojure-surface REPL. Wires commons stages, error formatting, keyword
   resolution, and syntax-quote resolution into the generic REPL
   infrastructure.

   Grammar-agnostic — callers pass `:grammar` in opts. Each lang (meme,
   implojure, etc.) provides a thin wrapper that injects its grammar and
   banner text, then delegates here.

   Installs `meme.loader` by default so `require`/`load-file` of
   user-source files work from the REPL. Pass `:install-loader? false`
   to skip. JVM/Babashka only."
  (:require [clojure.string :as str]
            [meme.tools.repl :as repl]
            [meme.loader :as loader]
            [meme.tools.clj.stages :as stages]
            [meme.tools.clj.errors :as errors]
            [meme.tools.clj.run :as clj-run]))

(defn input-state
  "Returns :complete, :incomplete, or :invalid for the given input.
   Uses `stages/run` (parse + read). Requires `:grammar` in opts.

   Call with `run-fn` if you need to inject a wrapped pipeline (e.g.
   pre-opts grammar injection)."
  ([s opts]
   (input-state s opts (fn [s opts] (stages/run s opts))))
  ([s opts run-fn]
   (repl/input-state s run-fn opts)))

(defn- default-resolve-keyword
  "Clojure-surface `::kw` resolver: consults `*ns*` aliases, else treats
   the prefix as a namespace name directly. Matches Clojure's reader."
  [raw]
  (let [s (subs raw 2)]
    (if-let [idx (str/index-of s "/")]
      (let [alias-str (subs s 0 idx)
            kw-name (subs s (inc idx))
            alias-ns (get (ns-aliases *ns*) (symbol alias-str))]
        (if alias-ns
          (keyword (str alias-ns) kw-name)
          (keyword alias-str kw-name)))
      (keyword (str (ns-name *ns*)) s))))

(defn- repl-pipeline
  "REPL eval pipeline: parse, read, evaluate reader conditionals.
   Syntax-quote expansion runs separately via expand-fn in the harness.
   Caller-provided opts must include :grammar."
  [source opts]
  (-> {:source source :opts opts}
      stages/step-parse
      stages/step-read
      stages/step-evaluate-reader-conditionals))

(defn start
  "Start a Clojure-surface REPL.

   Required opts:
     :grammar  — Grammar spec for step-parse.

   Optional opts:
     :banner          — string shown at REPL start.
     :install-loader? — default true; pass false to skip `meme.loader`.
     :resolve-keyword — override for `::kw` resolution.
     :resolve-symbol  — override for syntax-quote symbol resolution.
     :parser, :eval, :read-line, :prelude — passed to the generic harness."
  [opts]
  (when-not (false? (:install-loader? opts)) (loader/install!))
  (let [grammar (or (:grammar opts)
                    (throw (ex-info "meme.tools.clj.repl/start requires :grammar"
                                    {:type :meme-lang/missing-grammar})))
        stages-impl (:stages opts)
        base-run (or (:run-fn stages-impl) repl-pipeline)
        ;; Inject :grammar into every opts the REPL hands to the run-fn.
        run-fn (fn [source ropts]
                 (base-run source (assoc (or ropts {}) :grammar grammar)))
        expand-fn (or (:expand-forms stages-impl) stages/expand-syntax-quotes)
        reader-opts (let [rk (or (:resolve-keyword opts) default-resolve-keyword)
                          base (cond-> {:resolve-keyword rk}
                                 (:parser opts) (assoc :parser (:parser opts)))]
                      (cond-> base
                        (not (:resolve-symbol opts))
                        (assoc :resolve-symbol clj-run/default-resolve-symbol)))]
    (repl/start {:run-fn run-fn
                 :expand-forms expand-fn
                 :format-error (fn [e input] (errors/format-error e input))
                 :eval (:eval opts)
                 :read-line (:read-line opts)
                 :banner (:banner opts)
                 :reader-opts reader-opts
                 :prelude (:prelude opts)})))
