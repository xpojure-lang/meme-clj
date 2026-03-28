(ns beme.alpha.runtime.run
  "Run .beme files: read, eval, return last result."
  (:require [beme.alpha.pipeline :as pipeline]
            [clojure.string :as str]))

(defn- strip-shebang
  "Strip a leading #! shebang line from source, if present."
  [s]
  (if (str/starts-with? s "#!")
    (let [nl (str/index-of s "\n")]
      (if nl (subs s (inc nl)) ""))
    s))

(defn- default-reader-opts
  "Build reader opts, merging caller-provided opts with platform defaults.
   Does NOT provide a default :resolve-keyword — :: keywords use the
   deferred eval path so they resolve in the file's declared namespace
   at eval time, not the caller's namespace at read time.
   Passes through all other reader opts unchanged."
  [opts]
  (dissoc opts :eval))

(defn run-string
  "Read beme source string, eval each form, return the last result.
   Strips shebang lines from source before parsing.
   opts:
     :eval            — eval fn (default: eval; required on CLJS)
     :resolve-keyword — fn to resolve :: keywords at read time
                        (default: none — :: keywords resolve at eval time
                        in the file's declared namespace. Required on CLJS
                        for code that uses :: keywords)"
  ([s] (run-string s {}))
  ([s eval-fn-or-opts]
   (let [opts (if (map? eval-fn-or-opts) eval-fn-or-opts {:eval eval-fn-or-opts})
         eval-fn (or (:eval opts)
                     #?(:clj eval
                        :cljs (throw (ex-info "run-string requires :eval option in ClojureScript" {}))))
         reader-opts (default-reader-opts opts)
         src (strip-shebang s)
         forms (:forms (pipeline/run src reader-opts))]
     (reduce (fn [_ form] (eval-fn form)) nil forms))))

(defn run-file
  "Read and eval a .beme file. Returns the last result.
   Second arg can be an eval-fn (backward compat) or an opts map."
  ([path] (run-string (#?(:clj slurp
                          :cljs (throw (ex-info "run-file requires slurp — not available in ClojureScript" {}))) path)))
  ([path eval-fn-or-opts] (run-string (#?(:clj slurp
                                          :cljs (throw (ex-info "run-file requires slurp — not available in ClojureScript" {}))) path) eval-fn-or-opts)))
