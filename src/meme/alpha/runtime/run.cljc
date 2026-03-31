(ns meme.alpha.runtime.run
  "Run .meme files: read, eval, return last result."
  (:require [clojure.string :as str]
            [meme.alpha.core :as core]
            [meme.alpha.pipeline :as pipeline]
            [meme.alpha.platform.registry :as registry]
            #?(:clj [meme.alpha.runtime.resolve :as resolve])))

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
     ;; Eval prelude before user code
     (doseq [form (:prelude opts)]
       (eval-fn form))
     (let [forms (:forms (-> {:source s :opts reader-opts}
                             step-strip-shebang
                             pipeline/step-scan
                             pipeline/step-parse
                             pipeline/step-expand-syntax-quotes
                             pipeline/step-rewrite))]
       (reduce (fn [_ form] (eval-fn form)) nil forms)))))

(defn- resolve-lang-opts
  "If path matches a registered language, load its prelude/rules/parser and merge into opts."
  [path opts]
  #?(:clj
     (if-let [lang (or (:lang opts) (registry/resolve-lang path))]
       (let [config (registry/lang-config lang)]
         (cond-> opts
           (and (:prelude-file config) (not (:prelude opts)))
           (assoc :prelude (core/meme->forms (slurp (:prelude-file config))))

           (and (:prelude config) (not (:prelude opts)))
           (assoc :prelude (:prelude config))

           (and (:rules-file config) (not (:rewrite-rules opts)))
           (assoc :rewrite-rules (run-string (slurp (:rules-file config))))

           (and (:rules config) (not (:rewrite-rules opts)))
           (assoc :rewrite-rules (:rules config))

           (and (:parser config) (not (:parser opts)))
           (assoc :parser (:parser config))))
       opts)
     :cljs opts))

(defn run-file
  "Read and eval a file. Returns the last result.
   Auto-detects guest language from file extension via the registry.
   Second arg can be an eval-fn (backward compat) or an opts map."
  ([path] (run-file path {}))
  ([path eval-fn-or-opts]
   (let [opts (if (map? eval-fn-or-opts) eval-fn-or-opts {:eval eval-fn-or-opts})
         opts (resolve-lang-opts path opts)
         src #?(:clj (slurp path)
                :cljs (throw (ex-info "run-file requires slurp — not available in ClojureScript" {})))]
     (run-string src opts))))
