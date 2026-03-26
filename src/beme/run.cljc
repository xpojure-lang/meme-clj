(ns beme.run
  "Run .beme files: read, eval, return last result."
  (:require [beme.reader :as reader]
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
   Provides :resolve-keyword default on JVM when not supplied by caller.
   Passes through all other reader opts unchanged."
  [opts]
  (let [rk (or (:resolve-keyword opts)
               #?(:clj #(clojure.core/read-string %)
                  :cljs nil))
        reader-opts (dissoc opts :eval)]
    (not-empty (cond-> reader-opts
                 rk (assoc :resolve-keyword rk)))))

(defn run-string
  "Read beme source string, eval each form, return the last result.
   Strips shebang lines from source before parsing.
   opts:
     :eval            — eval fn (default: eval; required on CLJS)
     :resolve-keyword — fn to resolve :: keywords at read time
                        (default: clojure.core/read-string on JVM; required on CLJS
                        for code that uses :: keywords)"
  ([s] (run-string s {}))
  ([s eval-fn-or-opts]
   (let [opts (if (map? eval-fn-or-opts) eval-fn-or-opts {:eval eval-fn-or-opts})
         eval-fn (or (:eval opts)
                     #?(:clj eval
                        :cljs (throw (ex-info "run-string requires :eval option in ClojureScript" {}))))
         reader-opts (default-reader-opts opts)
         src (strip-shebang s)
         forms (reader/read-beme-string src reader-opts)]
     (reduce (fn [_ form] (eval-fn form)) nil forms))))

(defn run-file
  "Read and eval a .beme file. Returns the last result.
   Second arg can be an eval-fn (backward compat) or an opts map."
  ([path] (run-string (#?(:clj slurp
                          :cljs (throw (ex-info "run-file requires slurp — not available in ClojureScript" {}))) path)))
  ([path eval-fn-or-opts] (run-string (#?(:clj slurp
                                          :cljs (throw (ex-info "run-file requires slurp — not available in ClojureScript" {}))) path) eval-fn-or-opts)))

#?(:clj
(defn -main [& args]
  (let [f (first args)]
    (when-not f
      (println "Usage: clj -M:beme-run <file.beme>")
      (System/exit 1))
    (try
      (run-file f)
      (catch Exception e
        (let [src (try (slurp f) (catch Exception _ nil))]
          (println ((requiring-resolve 'beme.errors/format-error) e src)))
        (System/exit 1))))))
