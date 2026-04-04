(ns meme.tools.run
  "Eval pipeline: source → stages → eval. Parameterizable via
   :prelude. Lang implementations wire into run-string and
   run-file via their lang map :run entry.
   JVM/Babashka only."
  (:require [clojure.string :as str]
            [meme.tools.reader.stages :as stages]))

;; ---------------------------------------------------------------------------
;; Syntax-quote symbol resolution (inlined from meme.tools.resolve)
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

(defn step-strip-bom
  "M7: Strip UTF-8 BOM (U+FEFF) from the start of :source, if present.
   BOM markers from Windows editors corrupt Clojure output."
  [ctx]
  (let [source (:source ctx)]
    (if (and (string? source) (str/starts-with? source "\uFEFF"))
      (assoc ctx :source (subs source 1))
      ctx)))


(defn- default-reader-opts
  "Build reader opts, merging caller-provided opts with platform defaults.
   Provides a default :resolve-symbol that matches Clojure's syntax-quote
   resolution (namespace-qualifying symbols).
   Does NOT provide a default :resolve-keyword — :: keywords use the
   deferred eval path so they resolve in the file's declared namespace
   at eval time, not the caller's namespace at read time."
  [opts]
  (let [base (dissoc opts :eval :stages)]
    (cond-> base
      (not (:resolve-symbol base))
      (assoc :resolve-symbol default-resolve-symbol))))

(defn run-string
  "Read meme source string, eval each form, return the last result.
   Strips shebang lines from source before parsing.
   opts:
     :eval              — eval fn (default: eval)
     :resolve-keyword   — fn to resolve :: keywords at read time
     :prelude           — vector of forms to eval before user code
     :stages            — pipeline override map with:
                            :run-fn       (fn [source opts] -> ctx-with-:forms)
                            :expand-forms (fn [forms opts] -> expanded-forms)"
  ([s]
   {:pre [(string? s)]}
   (run-string s {}))
  ([s eval-fn-or-opts]
   {:pre [(string? s)]}
   (let [opts (if (map? eval-fn-or-opts) eval-fn-or-opts {:eval eval-fn-or-opts})
         eval-fn (or (:eval opts) eval)
         stages-impl (:stages opts)
         expand-fn (or (:expand-forms stages-impl) stages/expand-syntax-quotes)
         reader-opts (default-reader-opts opts)]
     ;; Expand and eval prelude before user code (must expand syntax-quotes,
     ;; matching the user-code path — raw parsed forms contain AST nodes)
     (when-let [prelude (seq (:prelude opts))]
       (doseq [form (expand-fn prelude reader-opts)]
         (eval-fn form)))
     (let [run-fn (or (:run-fn stages-impl)
                      (fn [source opts]
                        (let [source (stages/strip-shebang source)
                              source (if (and (string? source)
                                             (str/starts-with? source "\uFEFF"))
                                       (subs source 1) source)]
                          (-> {:source source :opts opts}
                              stages/step-scan
                              stages/step-trivia
                              stages/step-parse
                              stages/step-read
                              stages/step-expand-syntax-quotes))))
           forms (:forms (run-fn s reader-opts))]
       (reduce (fn [_ form] (eval-fn form)) nil forms)))))

(defn- resolve-lang-run
  "If path matches a registered user lang (by extension or explicit :lang opt),
   return its :run function. Returns nil for default meme.
   Uses requiring-resolve to avoid cyclic load dependency."
  [path opts]
  (let [explicit (:lang opts)
        resolve-lang-fn @(requiring-resolve 'meme.registry/resolve-lang)
        resolve-ext-fn  @(requiring-resolve 'meme.registry/resolve-by-extension)]
    (if explicit
      (:run (resolve-lang-fn explicit))
      (when-let [[_name l] (resolve-ext-fn path)]
        (:run l)))))

(defn run-file
  "Read and eval a file. Returns the last result.
   Auto-detects guest language from file extension via registered langs.
   Second arg can be an eval-fn (backward compat) or an opts map."
  ([path] (run-file path {}))
  ([path eval-fn-or-opts]
   (let [opts (if (map? eval-fn-or-opts) eval-fn-or-opts {:eval eval-fn-or-opts})
         lang-run (resolve-lang-run path opts)
         src (slurp path)]
     (if lang-run
       (lang-run src opts)
       (run-string src opts)))))
