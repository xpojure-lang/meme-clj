(ns meme.tools.clj.run
  "Clojure-surface eval pipeline. Wires commons stages, syntax-quote
   resolution, and BOM/shebang stripping into the generic run infrastructure.

   Grammar-agnostic — callers must pass `:grammar` in opts. Each language
   (meme, implojure, future Clojure-flavored siblings) provides its own
   thin wrapper that injects its grammar and delegates here.

   Installs `meme.loader` by default so that `require` and `load-file`
   of `.meme` namespaces work from within the evaluated code. Callers
   embedding meme in a host that owns its own `clojure.core/load`
   interception can pass `:install-loader? false` to skip.

   Extension-based lang dispatch (running `.implj` or other sibling-lang
   files) is opt-in via `:resolve-lang-for-path` — programmatic callers
   that pass a real `.meme` path don't need it; the CLI wires it.
   JVM/Babashka only."
  (:require [clojure.string :as str]
            [meme.tools.run :as run]
            [meme.loader :as loader]
            [meme.tools.clj.stages :as stages]))

;; ---------------------------------------------------------------------------
;; Syntax-quote symbol resolution (Clojure's SyntaxQuoteReader)
;; ---------------------------------------------------------------------------

(def ^:private sq-special-forms
  "Clojure compiler special forms — must stay unqualified in syntax-quote."
  #{'def 'if 'do 'let* 'fn* 'loop* 'try 'catch 'finally 'recur
    'quote 'var 'throw 'new 'set! 'monitor-enter 'monitor-exit
    'deftype* 'reify* 'letfn* 'case* 'clojure.core/import* '& '.})

(defn default-resolve-symbol
  "Resolve a symbol for syntax-quote, matching Clojure's SyntaxQuoteReader."
  [sym]
  (cond
    (namespace sym) sym
    (contains? sq-special-forms sym) sym
    (str/starts-with? (name sym) ".") sym
    (str/ends-with? (name sym) ".") sym
    :else
    (if-let [resolved (ns-resolve *ns* sym)]
      (cond
        (var? resolved)
        (symbol (name (ns-name (.-ns ^clojure.lang.Var resolved))) (name sym))
        (class? resolved)
        (symbol (.getName ^Class resolved))
        :else sym)
      (symbol (name (ns-name *ns*)) (name sym)))))

;; ---------------------------------------------------------------------------
;; Pipeline
;; ---------------------------------------------------------------------------

(defn- default-reader-opts
  "Build reader opts with Clojure-surface defaults (resolve-symbol)."
  [opts]
  (let [base (dissoc opts :eval :stages :prelude)]
    (cond-> base
      (not (:resolve-symbol base))
      (assoc :resolve-symbol default-resolve-symbol))))

(defn- clj-run-fn
  "The Clojure-surface pipeline: strip shebang, strip BOM, parse, read,
   evaluate reader conditionals, expand syntax quotes. Caller must have
   injected `:grammar` into opts."
  [source opts]
  (let [source (stages/strip-shebang source)
        source (if (and (string? source) (str/starts-with? source "\uFEFF"))
                 (subs source 1) source)]
    (-> {:source source :opts opts}
        stages/step-parse
        stages/step-read
        stages/step-evaluate-reader-conditionals
        stages/step-expand-syntax-quotes)))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn- maybe-install-loader! [opts]
  (when-not (false? (:install-loader? opts)) (loader/install!)))

(defn run-string
  "Read Clojure-surface source string, eval each form, return the last
   result. Requires `:grammar` in opts. Installs `meme.loader` unless
   `:install-loader? false` is passed."
  ([s] (run-string s {}))
  ([s eval-fn-or-opts]
   {:pre [(string? s)]}
   (let [opts (if (map? eval-fn-or-opts) eval-fn-or-opts {:eval eval-fn-or-opts})
         stages-impl (:stages opts)
         reader-opts (default-reader-opts opts)]
     (maybe-install-loader! opts)
     (run/run-string s {:run-fn (or (:run-fn stages-impl) clj-run-fn)
                        :expand-forms (or (:expand-forms stages-impl) stages/expand-syntax-quotes)
                        :reader-opts reader-opts
                        :eval (:eval opts)
                        :prelude (:prelude opts)}))))

(defn run-file
  "Read and eval a file of Clojure-surface source. Returns the last result.

   Optional opts:
     :grammar               — REQUIRED. Grammar spec for step-parse.
     :install-loader?       — default true; pass false to skip installing
                              `meme.loader` (e.g. when embedding in a host
                              that owns its own load interception).
     :resolve-lang-for-path — (fn [path opts] → run-fn-or-nil) for extension-
                              based lang dispatch. When provided, its return
                              value (if non-nil) runs the file instead."
  ([path] (run-file path {}))
  ([path eval-fn-or-opts]
   (let [opts (if (map? eval-fn-or-opts) eval-fn-or-opts {:eval eval-fn-or-opts})
         stages-impl (:stages opts)
         reader-opts (default-reader-opts opts)
         generic-opts {:run-fn (or (:run-fn stages-impl)
                                   (fn [src _opts] (clj-run-fn src opts)))
                       :expand-forms (or (:expand-forms stages-impl) stages/expand-syntax-quotes)
                       :reader-opts reader-opts
                       :eval (:eval opts)
                       :prelude (:prelude opts)}
         resolve-lang (:resolve-lang-for-path opts)]
     (maybe-install-loader! opts)
     (run/run-file path generic-opts
                   (when resolve-lang
                     (fn [p _generic-opts] (resolve-lang p opts)))))))
