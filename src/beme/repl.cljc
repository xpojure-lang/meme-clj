(ns beme.repl
  "beme REPL: read beme, eval as Clojure, print result."
  (:require [beme.reader :as reader]
            [beme.errors :as errors]
            [clojure.string :as str]))

(defn input-state
  "Returns :complete, :incomplete, or :invalid for the given input string.
   :complete   — parsed successfully
   :incomplete — unclosed delimiter (EOF-related error), keep reading
   :invalid    — malformed literal or other non-recoverable error"
  [s]
  (try
    (reader/read-beme-string s)
    :complete
    (catch #?(:clj Exception :cljs :default) e
      (if (:incomplete (ex-data e))
        :incomplete
        :invalid))))

(defn- read-input
  "Read potentially multi-line input. Continues reading if brackets/parens are unbalanced.
   Returns malformed input immediately so the eval loop can report the error.
   Returns empty string for blank first line (so outer loop can skip it cleanly)."
  [prompt read-line-fn]
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

            ;; Blank continuation — keep prompting (user may be adding blank lines mid-form)
            (str/blank? input)
            (do (print "  .. ")
                (flush)
                (recur all-lines))

            :else
            (case (input-state input)
              :complete   input
              :invalid    input
              :incomplete (do (print "  .. ")
                              (flush)
                              (recur all-lines)))))))))

(defn start
  "Start the beme REPL. Reads beme syntax, evals as Clojure, prints results.
   opts:
     :read-line       — custom line reader fn (default: read-line; required on CLJS)
     :eval            — custom eval fn (default: eval; required on CLJS)
     :resolve-keyword — fn to resolve :: keywords at read time
                        (default: clojure.core/read-string on JVM; required on CLJS
                        for code that uses :: keywords)"
  ([] (start {}))
  ([opts]
   (let [read-line-fn (or (:read-line opts)
                         #?(:clj read-line
                            :cljs (throw (ex-info "REPL requires :read-line option in ClojureScript" {}))))
         eval-fn (or (:eval opts)
                     #?(:clj eval
                        :cljs (throw (ex-info "REPL requires :eval option in ClojureScript" {}))))
         reader-opts (let [rk (or (:resolve-keyword opts)
                                  #?(:clj #(clojure.core/read-string %)
                                     :cljs nil))]
                       (when rk {:resolve-keyword rk}))]
     (println "beme REPL. Type beme expressions, balanced input to eval. Ctrl-D to exit.")
     (loop []
       (let [prompt #?(:clj (str (ns-name *ns*) "=> ")
                       :cljs "beme=> ")
             input (read-input prompt read-line-fn)]
         (when input
           (if (str/blank? input)
             (recur)
             (do
               (try
                 (let [forms (reader/read-beme-string input reader-opts)]
                   (doseq [form forms]
                     (let [result (eval-fn form)]
                       (prn result))))
                 (catch #?(:clj Throwable :cljs :default) e
                   (println (errors/format-error e input))))
               (recur)))))))))
