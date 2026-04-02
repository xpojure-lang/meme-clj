(ns meme.runtime.run
  "Run .meme files: read, eval, return last result."
  (:require [clojure.string :as str]
            [meme.stages :as stages]
            #?(:clj [meme.runtime.resolve :as resolve])))

(defn- step-strip-bom
  "M7: Strip UTF-8 BOM (U+FEFF) from the start of :source, if present.
   BOM markers from Windows editors corrupt Clojure output."
  [ctx]
  (let [source (:source ctx)]
    (if (and (string? source) (str/starts-with? source "\uFEFF"))
      (assoc ctx :source (subs source 1))
      ctx)))

(defn- step-strip-shebang
  "Strip a leading #! shebang line from a context's :source, if present."
  [ctx]
  (let [source (:source ctx)]
    (if (and (string? source) (str/starts-with? source "#!"))
      (let [nl (str/index-of source "\n")]
        (assoc ctx :source (if nl (subs source (inc nl)) "")))
      ctx)))

(defn- default-reader-opts
  "Build reader opts, merging caller-provided opts with platform defaults.
   On JVM/Babashka, provides a default :resolve-symbol that matches
   Clojure's syntax-quote resolution (namespace-qualifying symbols).
   Does NOT provide a default :resolve-keyword — :: keywords use the
   deferred eval path so they resolve in the file's declared namespace
   at eval time, not the caller's namespace at read time."
  [opts]
  (let [base (dissoc opts :eval)]
    #?(:clj (cond-> base
              (not (:resolve-symbol base))
              (assoc :resolve-symbol resolve/default-resolve-symbol))
       :cljs base)))

(defn run-string
  "Read meme source string, eval each form, return the last result.
   Strips shebang lines from source before parsing.
   opts:
     :eval              — eval fn (default: eval; required on CLJS)
     :resolve-keyword   — fn to resolve :: keywords at read time
     :rewrite-rules     — vector of rewrite rules (applied after expansion)
     :rewrite-max-iters — max rewrite iterations (default: 100)
     :prelude           — vector of forms to eval before user code"
  ([s] (run-string s {}))
  ([s eval-fn-or-opts]
   (let [opts (if (map? eval-fn-or-opts) eval-fn-or-opts {:eval eval-fn-or-opts})
         eval-fn (or (:eval opts)
                     #?(:clj eval
                        :cljs (throw (ex-info "run-string requires :eval option in ClojureScript" {}))))
         reader-opts (default-reader-opts opts)]
     ;; Expand and eval prelude before user code (must expand syntax-quotes,
     ;; matching the user-code path — raw parsed forms contain AST nodes)
     (when-let [prelude (seq (:prelude opts))]
       (let [expanded (:forms (stages/step-expand-syntax-quotes
                                {:forms (vec prelude) :opts reader-opts}))]
         (doseq [form expanded]
           (eval-fn form))))
     (let [forms (:forms (-> {:source s :opts reader-opts}
                             step-strip-bom
                             step-strip-shebang
                             stages/step-scan
                             stages/step-parse
                             stages/step-expand-syntax-quotes
                             stages/step-rewrite))]
       (reduce (fn [_ form] (eval-fn form)) nil forms)))))

(defn- resolve-lang-run
  "If path matches a registered user lang (by extension or explicit :lang opt),
   return its :run function. Returns nil for default meme.
   Uses requiring-resolve to avoid cyclic load dependency (lang → lang.meme-rewrite → run)."
  [path opts]
  #?(:clj
     (let [explicit (:lang opts)
           resolve-lang-fn @(requiring-resolve 'meme.lang/resolve-lang)
           resolve-ext-fn  @(requiring-resolve 'meme.lang/resolve-by-extension)]
       (if explicit
         (:run (resolve-lang-fn explicit))
         (when-let [[_name l] (resolve-ext-fn path)]
           (:run l))))
     :cljs nil))

(defn run-file
  "Read and eval a file. Returns the last result.
   Auto-detects guest language from file extension via registered langs.
   Second arg can be an eval-fn (backward compat) or an opts map."
  ([path] (run-file path {}))
  ([path eval-fn-or-opts]
   (let [opts (if (map? eval-fn-or-opts) eval-fn-or-opts {:eval eval-fn-or-opts})
         lang-run (resolve-lang-run path opts)
         src #?(:clj (slurp path)
                :cljs (throw (ex-info "run-file requires slurp — not available in ClojureScript" {})))]
     (if lang-run
       (lang-run src opts)
       (run-string src opts)))))
