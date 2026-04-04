(ns meme.tools.repl
  "Interactive eval loop. Reads meme syntax, evals as Clojure, prints results.
   JVM/Babashka only."
  (:require [meme.tools.reader.stages :as stages]
            [meme.tools.errors :as errors]
            [meme.tools.run :as run]
            [clojure.string :as str]
            [clojure.java.io :as io]))

(defn input-state
  "Returns :complete, :incomplete, or :invalid for the given input string.
   :complete   — parsed successfully
   :incomplete — unclosed delimiter (EOF-related error), keep reading
   :invalid    — malformed literal or other non-recoverable error
   opts are the same reader opts passed to stages/run (e.g. :resolve-keyword)
   so that the completeness check uses the same reader configuration as eval.
   run-fn overrides the pipeline (default: stages/run)."
  ([s] (input-state s nil))
  ([s opts] (input-state s opts stages/run))
  ([s opts run-fn]
   (try
     (run-fn s opts)
     :complete
     (catch Exception e
       (if (:incomplete (ex-data e))
         :incomplete
         :invalid)))))

(defn- try-parse
  "Try to parse input. Returns {:state :complete :forms [...]} on success,
   {:state :incomplete} for unbalanced input, or {:state :invalid :error e}
   for malformed input. Single parse — no re-parsing needed."
  [s opts run-fn]
  (try
    (let [result (run-fn s opts)]
      {:state :complete :forms (:forms result)})
    (catch Exception e
      (if (:incomplete (ex-data e))
        {:state :incomplete}
        {:state :invalid :error e}))))

(defn- print-error
  "Print an error to stderr."
  [e input]
  (let [msg (if (ex-message e)
              (errors/format-error e input)
              (str "Error: " (pr-str e)))]
    (binding [*out* *err*] (println msg))))

(defn- read-input
  "Read potentially multi-line input. Continues reading if brackets/parens are unbalanced.
   Returns malformed input immediately so the eval loop can report the error.
   Returns empty string for blank first line (so outer loop can skip it cleanly).
   On :complete, returns {:input s :forms [...]} with cached parse result.
   On :invalid, returns {:input s :error e}.
   Returns nil on EOF, \"\" on blank first line."
  ([prompt read-line-fn reader-opts] (read-input prompt read-line-fn reader-opts stages/run))
  ([prompt read-line-fn reader-opts run-fn]
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
             ;; Blank first line — return empty so outer loop recurs with fresh prompt
             (and (= (count all-lines) 1) (str/blank? input))
             ""

             :else
             (let [{:keys [state forms error]} (try-parse input reader-opts run-fn)]
               (case state
                 :complete {:input input :forms forms}
                 :invalid {:input input :error error}
                 :incomplete (do (print "  .. ")
                                 (flush)
                                 (recur all-lines)))))))))))

(defn start
  "Start the meme REPL. Reads meme syntax, evals as Clojure, prints results.
   opts:
     :read-line       — custom line reader fn (default: read-line)
     :eval            — custom eval fn (default: eval)
     :resolve-keyword — fn to resolve :: keywords at read time
     :prelude         — vector of forms to eval before the REPL loop starts
     :stages          — pipeline override map with:
                          :run-fn       (fn [source opts] -> ctx-with-:forms)
                          :expand-forms (fn [forms opts] -> expanded-forms)"
  ([] (start {}))
  ([opts]
   (let [read-line-fn (or (:read-line opts) read-line)
         eval-fn (or (:eval opts) eval)
         stages-impl (:stages opts)
         run-fn (or (:run-fn stages-impl) stages/run)
         expand-fn (or (:expand-forms stages-impl) stages/expand-syntax-quotes)
         reader-opts (let [rk (or (:resolve-keyword opts)
                                  (fn [raw]
                                    (let [s (subs raw 2)] ; strip leading ::
                                      (if-let [idx (clojure.string/index-of s "/")]
                                        ;; ::alias/name — resolve alias via ns-aliases
                                        (let [alias-str (subs s 0 idx)
                                              kw-name (subs s (inc idx))
                                              alias-ns (get (ns-aliases *ns*) (symbol alias-str))]
                                          (if alias-ns
                                            (keyword (str alias-ns) kw-name)
                                            (keyword alias-str kw-name)))
                                        ;; ::name — resolve to current namespace
                                        (keyword (str (ns-name *ns*)) s)))))
                           base (cond-> {:resolve-keyword rk}
                                  (:parser opts) (assoc :parser (:parser opts)))]
                       (cond-> base
                         (not (:resolve-symbol opts))
                         (assoc :resolve-symbol run/default-resolve-symbol)))]
     ;; Expand and eval prelude before REPL loop
     (when-let [prelude (seq (:prelude opts))]
       (doseq [form (expand-fn prelude reader-opts)]
         (eval-fn form)))
     (let [version (try (some-> (io/resource "meme/version.txt") slurp str/trim)
                        (catch Exception _ nil))
           banner (if version
                    (str "meme " version " REPL. Type meme expressions, balanced input to eval. Ctrl-D to exit.")
                    "meme REPL. Type meme expressions, balanced input to eval. Ctrl-D to exit.")]
       (println banner))
     (loop []
       (let [prompt (str (ns-name *ns*) "=> ")
             parsed (read-input prompt read-line-fn reader-opts run-fn)]
         (when parsed
           (cond
             (string? parsed) (recur) ;; blank line

             (:forms parsed)
             (do (try
                   (doseq [form (expand-fn (:forms parsed) reader-opts)]
                     (let [result (eval-fn form)]
                       (prn result)))
                   (catch Throwable e
                     (print-error e (:input parsed))))
                 (recur))

             (:error parsed)
             (do (print-error (:error parsed) (:input parsed))
                 (recur)))))))))
