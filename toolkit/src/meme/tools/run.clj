(ns meme.tools.run
  "Generic eval pipeline: source → parse → expand → eval.
   Language-agnostic. All language-specific behavior is injected via opts:
     :run-fn       — (fn [source opts] → ctx-with-:forms)
     :expand-forms — (fn [forms opts] → expanded-forms)
     :eval         — eval function (default: clojure.core/eval)
     :prelude      — vector of forms to eval before user code
   JVM/Babashka only."
)

(defn run-string
  "Parse source string, eval each form, return the last result.
   opts:
     :run-fn        — (fn [source opts] → {:forms [...]}) — REQUIRED
     :expand-forms  — (fn [forms opts] → expanded-forms) — REQUIRED
     :reader-opts   — opts map passed to run-fn
     :eval          — eval fn (default: eval)
     :prelude       — vector of forms to eval before user code"
  ([s opts]
   {:pre [(string? s) (fn? (:run-fn opts)) (fn? (:expand-forms opts))]}
   (let [eval-fn (or (:eval opts) eval)
         run-fn (:run-fn opts)
         expand-fn (:expand-forms opts)
         reader-opts (or (:reader-opts opts) {})]
     (when-let [prelude (seq (:prelude opts))]
       (doseq [form (expand-fn prelude reader-opts)]
         (eval-fn form)))
     (let [forms (:forms (run-fn s reader-opts))]
       (reduce (fn [_ form] (eval-fn form)) nil forms)))))

(defn run-file
  "Read and eval a file. Returns the last result.
   resolve-run-fn: optional (fn [path opts] → run-fn-or-nil) for lang dispatch.
   Falls back to run-string with the provided opts."
  ([path opts]
   (run-file path opts nil))
  ([path opts resolve-run-fn]
   (let [lang-run (when resolve-run-fn (resolve-run-fn path opts))
         src (slurp path)]
     (if lang-run
       (lang-run src opts)
       (run-string src opts)))))
