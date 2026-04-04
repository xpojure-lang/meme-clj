(ns meme.tools.repl
  "Generic interactive eval loop. Language-agnostic.
   All language-specific behavior is injected via opts:
     :run-fn        — (fn [source opts] → ctx-with-:forms)
     :expand-forms  — (fn [forms opts] → expanded-forms)
     :format-error  — (fn [exception input] → error-string)
     :eval          — eval function (default: clojure.core/eval)
     :read-line     — line reader fn (default: read-line)
     :banner        — REPL banner string
     :reader-opts   — opts map passed to run-fn
     :prelude       — vector of forms to eval before REPL loop
   JVM/Babashka only."
  (:require [clojure.string :as str]))

(defn input-state
  "Returns :complete, :incomplete, or :invalid for the given input string.
   run-fn: (fn [source opts] → ctx-with-:forms)"
  ([s run-fn] (input-state s run-fn nil))
  ([s run-fn opts]
   (try
     (run-fn s opts)
     :complete
     (catch Exception e
       (if (:incomplete (ex-data e))
         :incomplete
         :invalid)))))

(defn- try-parse
  [s opts run-fn]
  (try
    (let [result (run-fn s opts)]
      {:state :complete :forms (:forms result)})
    (catch Exception e
      (if (:incomplete (ex-data e))
        {:state :incomplete}
        {:state :invalid :error e}))))

(defn- print-error
  [e input format-error-fn]
  (let [msg (if format-error-fn
              (format-error-fn e input)
              (if (ex-message e)
                (ex-message e)
                (str "Error: " (pr-str e))))]
    (binding [*out* *err*] (println msg))))

(defn- read-input
  "Read potentially multi-line input. Continues on :incomplete.
   Returns {:input s :forms [...]} on success, {:input s :error e} on error,
   nil on EOF, \"\" on blank first line."
  [prompt read-line-fn reader-opts run-fn]
  (print prompt)
  (flush)
  (loop [lines []]
    (let [line (read-line-fn)]
      (cond
        (nil? line) nil
        :else
        (let [all-lines (conj lines line)
              input (str/join "\n" all-lines)]
          (cond
            (and (= (count all-lines) 1) (str/blank? input))
            ""

            :else
            (let [{:keys [state forms error]} (try-parse input reader-opts run-fn)]
              (case state
                :complete {:input input :forms forms}
                :invalid {:input input :error error}
                :incomplete (do (print "  .. ")
                                (flush)
                                (recur all-lines))))))))))

(defn start
  "Start a REPL. All language-specific behavior injected via opts.
   opts:
     :run-fn        — REQUIRED (fn [source opts] → ctx-with-:forms)
     :expand-forms  — REQUIRED (fn [forms opts] → expanded-forms)
     :format-error  — (fn [exception input] → error-string)
     :eval          — eval fn (default: eval)
     :read-line     — line reader fn (default: read-line)
     :banner        — banner string
     :reader-opts   — opts passed to run-fn
     :prelude       — forms to eval before REPL loop"
  [opts]
  {:pre [(fn? (:run-fn opts)) (fn? (:expand-forms opts))]}
  (let [read-line-fn (or (:read-line opts) read-line)
        eval-fn (or (:eval opts) eval)
        run-fn (:run-fn opts)
        expand-fn (:expand-forms opts)
        format-error-fn (:format-error opts)
        reader-opts (or (:reader-opts opts) {})]
    (when-let [prelude (seq (:prelude opts))]
      (doseq [form (expand-fn prelude reader-opts)]
        (eval-fn form)))
    (when-let [banner (:banner opts)]
      (println banner))
    (loop []
      (let [prompt (str (ns-name *ns*) "=> ")
            parsed (read-input prompt read-line-fn reader-opts run-fn)]
        (when parsed
          (cond
            (string? parsed) (recur)

            (:forms parsed)
            (do (try
                  (doseq [form (expand-fn (:forms parsed) reader-opts)]
                    (let [result (eval-fn form)]
                      (prn result)))
                  (catch Throwable e
                    (print-error e (:input parsed) format-error-fn)))
                (recur))

            (:error parsed)
            (do (print-error (:error parsed) (:input parsed) format-error-fn)
                (recur))))))))
