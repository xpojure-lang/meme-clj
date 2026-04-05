(ns meme-lang.run
  "Meme-specific eval pipeline. Wires meme stages, syntax-quote resolution,
   and BOM stripping into the generic run infrastructure.
   JVM/Babashka only."
  (:require [clojure.string :as str]
            [meme.tools.run :as run]
            [meme-lang.stages :as stages]))

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
;; Meme pipeline functions
;; ---------------------------------------------------------------------------

(defn- default-reader-opts
  "Build reader opts with meme defaults (resolve-symbol)."
  [opts]
  (let [base (dissoc opts :eval :stages :prelude)]
    (cond-> base
      (not (:resolve-symbol base))
      (assoc :resolve-symbol default-resolve-symbol))))

(defn- meme-run-fn
  "The meme pipeline: strip shebang, strip BOM, parse, read, expand."
  [source opts]
  (let [source (stages/strip-shebang source)
        source (if (and (string? source) (str/starts-with? source "\uFEFF"))
                 (subs source 1) source)]
    (-> {:source source :opts opts}
        stages/step-parse
        stages/step-read
        stages/step-expand-syntax-quotes)))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn run-string
  "Read meme source string, eval each form, return the last result."
  ([s] (run-string s {}))
  ([s eval-fn-or-opts]
   {:pre [(string? s)]}
   (let [opts (if (map? eval-fn-or-opts) eval-fn-or-opts {:eval eval-fn-or-opts})
         stages-impl (:stages opts)
         reader-opts (default-reader-opts opts)]
     (run/run-string s {:run-fn (or (:run-fn stages-impl) meme-run-fn)
                        :expand-forms (or (:expand-forms stages-impl) stages/expand-syntax-quotes)
                        :reader-opts reader-opts
                        :eval (:eval opts)
                        :prelude (:prelude opts)}))))

(defn- resolve-lang-run
  [path opts]
  (let [explicit (:lang opts)
        resolve-lang-fn @(requiring-resolve 'meme.registry/resolve-lang)
        resolve-ext-fn  @(requiring-resolve 'meme.registry/resolve-by-extension)]
    (if explicit
      (:run (resolve-lang-fn explicit))
      (when-let [[_name l] (resolve-ext-fn path)]
        (:run l)))))

(defn run-file
  "Read and eval a meme file. Returns the last result."
  ([path] (run-file path {}))
  ([path eval-fn-or-opts]
   (@(requiring-resolve 'meme.loader/install!))
   (let [opts (if (map? eval-fn-or-opts) eval-fn-or-opts {:eval eval-fn-or-opts})
         stages-impl (:stages opts)
         reader-opts (default-reader-opts opts)
         generic-opts {:run-fn (or (:run-fn stages-impl) meme-run-fn)
                       :expand-forms (or (:expand-forms stages-impl) stages/expand-syntax-quotes)
                       :reader-opts reader-opts
                       :eval (:eval opts)
                       :prelude (:prelude opts)}]
     (run/run-file path generic-opts
                   (fn [p _generic-opts]
                     (resolve-lang-run p opts))))))
