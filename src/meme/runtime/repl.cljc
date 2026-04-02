(ns meme.runtime.repl
  "meme REPL: read meme, eval as Clojure, print result."
  (:require [meme.stages :as stages]
            [meme.errors :as errors]
            [clojure.string :as str]
            #?(:clj [clojure.java.io :as io])
            #?(:clj [meme.runtime.resolve :as resolve])))

(defn input-state
  "Returns :complete, :incomplete, or :invalid for the given input string.
   :complete   — parsed successfully
   :incomplete — unclosed delimiter (EOF-related error), keep reading
   :invalid    — malformed literal or other non-recoverable error
   opts are the same reader opts passed to stages/run (e.g. :resolve-keyword)
   so that the completeness check uses the same reader configuration as eval."
  ([s] (input-state s nil))
  ([s opts]
   (try
     (stages/run s opts)
     :complete
     (catch #?(:clj Exception :cljs :default) e
       (if (:incomplete (ex-data e))
         :incomplete
         :invalid)))))

(defn- try-parse
  "Try to parse input. Returns {:state :complete :forms [...]} on success,
   {:state :incomplete} for unbalanced input, or {:state :invalid :error e}
   for malformed input. Single parse — no re-parsing needed."
  [s opts]
  (try
    (let [result (stages/run s opts)]
      {:state :complete :forms (:forms result)})
    (catch #?(:clj Exception :cljs :default) e
      (if (:incomplete (ex-data e))
        {:state :incomplete}
        {:state :invalid :error e}))))

(defn- print-error
  "Print an error to stderr (JVM) or stdout (CLJS)."
  [e input]
  (let [msg (if (ex-message e)
              (errors/format-error e input)
              (str "Error: " (pr-str e)))]
    #?(:clj  (binding [*out* *err*] (println msg))
       :cljs (println msg))))

(defn- read-input
  "Read potentially multi-line input. Continues reading if brackets/parens are unbalanced.
   Returns malformed input immediately so the eval loop can report the error.
   Returns empty string for blank first line (so outer loop can skip it cleanly).
   On :complete, returns {:input s :forms [...]} with cached parse result.
   On :invalid, returns {:input s :error e}.
   Returns nil on EOF, \"\" on blank first line."
  [prompt read-line-fn reader-opts]
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
            (let [{:keys [state forms error]} (try-parse input reader-opts)]
              (case state
                :complete   {:input input :forms forms}
                :invalid    {:input input :error error}
                :incomplete (do (print "  .. ")
                                (flush)
                                (recur all-lines))))))))))

(defn start
  "Start the meme REPL. Reads meme syntax, evals as Clojure, prints results.
   opts:
     :read-line       — custom line reader fn (default: read-line; required on CLJS)
     :eval            — custom eval fn (default: eval; required on CLJS)
     :resolve-keyword — fn to resolve :: keywords at read time
     :prelude         — vector of forms to eval before the REPL loop starts"
  ([] (start {}))
  ([opts]
   (let [read-line-fn (or (:read-line opts)
                          #?(:clj read-line
                             :cljs (throw (ex-info "REPL requires :read-line option in ClojureScript" {}))))
         eval-fn (or (:eval opts)
                     #?(:clj eval
                        :cljs (throw (ex-info "REPL requires :eval option in ClojureScript" {}))))
         ;; I5: resolve :: keywords using keyword constructor instead of read-string.
         ;; read-string is the full Clojure reader which accepts arbitrary dispatch
         ;; forms — using keyword constructor is sufficient and avoids eval risk.
         reader-opts (let [rk (or (:resolve-keyword opts)
                                  #?(:clj (fn [raw]
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
                                                (keyword (str (ns-name *ns*)) s))))
                                     :cljs nil))
                           base (cond-> (if rk {:resolve-keyword rk} {})
                                  (:parser opts) (assoc :parser (:parser opts)))]
                       #?(:clj (cond-> base
                                 (not (:resolve-symbol opts))
                                 (assoc :resolve-symbol resolve/default-resolve-symbol))
                          :cljs base))]
     ;; Expand and eval prelude before REPL loop (must expand syntax-quotes,
     ;; matching the user-input path — raw parsed forms contain AST nodes)
     ;; PT-F6: provide full context map so stage contract validation succeeds
     (when-let [prelude (seq (:prelude opts))]
       (let [expanded (:forms (stages/step-expand-syntax-quotes
                                {:source "" :raw-tokens [] :tokens []
                                 :forms (vec prelude) :opts reader-opts}))]
         (doseq [form expanded]
           (eval-fn form))))
     (let [version #?(:clj (try (some-> (io/resource "meme/version.txt") slurp str/trim)
                                (catch Exception _ nil))
                      :cljs nil)
           banner (if version
                    (str "meme " version " REPL. Type meme expressions, balanced input to eval. Ctrl-D to exit.")
                    "meme REPL. Type meme expressions, balanced input to eval. Ctrl-D to exit.")]
       (println banner))
     (loop []
       (let [prompt #?(:clj (str (ns-name *ns*) "=> ")
                       :cljs "meme=> ")
             parsed (read-input prompt read-line-fn reader-opts)]
         (when parsed
           (cond
             (string? parsed) (recur) ;; blank line

             (:forms parsed)
             (do (try
                   ;; PT-F6: provide full context map for *validate* compatibility
                   (let [expanded (:forms (stages/step-expand-syntax-quotes
                                           {:source "" :raw-tokens [] :tokens []
                                            :forms (:forms parsed) :opts reader-opts}))]
                     (doseq [form expanded]
                       (let [result (eval-fn form)]
                         (prn result))))
                   (catch #?(:clj Throwable :cljs :default) e
                     (print-error e (:input parsed))))
                 (recur))

             (:error parsed)
             (do (print-error (:error parsed) (:input parsed))
                 (recur)))))))))
