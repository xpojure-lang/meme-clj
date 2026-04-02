(ns meme.runtime.cli
  "Unified CLI. Commands dispatch through lang maps."
  (:require [meme.errors :as errors]
            [meme.lang :as lang]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; File utilities
;; ---------------------------------------------------------------------------

(defn- find-files [dir pred]
  (->> (file-seq (io/file dir))
       (filter #(.isFile ^java.io.File %))
       (map str)
       (filter pred)
       sort))

(defn- expand-inputs [inputs pred]
  (mapcat (fn [path]
            (if (.isDirectory (io/file path))
              (find-files path pred)
              (if (pred path) [path] [])))
          inputs))

(defn- meme-file? [path] (str/ends-with? path ".meme"))
(defn- clj-file? [path] (boolean (re-find #"\.clj[cdsx]?$" path)))
(defn- swap-ext [path from to]
  (str/replace path (re-pattern (str "\\." from "[cdsx]?$")) (str "." to)))

;; ---------------------------------------------------------------------------
;; Generic file processor
;; ---------------------------------------------------------------------------

(defn- process-files [{:keys [inputs pred transform output-fn stdout check verb]}]
  (let [expanded (expand-inputs inputs pred)]
    (when (empty? expanded)
      (println "No matching files found.")
      (System/exit 1))
    (let [process-one
          (fn [path]
            (try
              (cond
                stdout (do (println (transform path)) :ok)
                check  (let [src       (slurp path)
                             formatted (str (transform path) "\n")]
                         (if (= src formatted) :ok
                             (do (println (str "would reformat: " path)) :fail)))
                :else  (let [out    (if output-fn (output-fn path) path)
                             result (transform path)]
                         (spit out (str result "\n"))
                         (println (if (= path out) (str verb " " path) (str path " → " out)))
                         :ok))
              (catch Exception e
                (binding [*out* *err*]
                  (println (errors/format-error e (try (slurp path) (catch Exception _ nil)))))
                :fail)))
          results (doall (map process-one expanded))
          total   (count results)
          failed  (count (filter #{:fail} results))]
      (when-not stdout
        (println)
        (println (str total " file(s), " (- total failed) " " verb
                      (when (pos? failed) (str ", " failed " failed")))))
      (when (pos? failed) (System/exit 1)))))

;; ---------------------------------------------------------------------------
;; Lang resolution
;; ---------------------------------------------------------------------------

(def ^:private cli-keys #{:file :files :stdout :check :lang})

(defn- lang-opts [m] (apply dissoc m cli-keys))

(defn- get-lang [lang-str file]
  (cond
    (and lang-str (str/ends-with? lang-str ".edn"))
    (try [lang-str (lang/load-edn lang-str)]
         (catch Exception e
           (binding [*out* *err*]
             (println (str "Error loading lang " lang-str ": " (ex-message e))))
           (System/exit 1)))

    lang-str
    (let [kw (keyword lang-str)]
      (try [kw (lang/resolve-lang kw)]
           (catch Exception _
             (binding [*out* *err*]
               (println (str "Unknown lang: " kw))
               (println (str "Available: " (str/join ", " (map name (keys @lang/builtin))))))
             (System/exit 1))))

    file
    (or (lang/resolve-by-extension file)
        [:meme-classic (lang/resolve-lang :meme-classic)])

    :else [:meme-classic (lang/resolve-lang :meme-classic)]))

;; ---------------------------------------------------------------------------
;; Commands — all delegate to lang
;; ---------------------------------------------------------------------------

(defn- file-command
  "Generic: resolve lang, check support, process files via lang command."
  [{:keys [file files stdout check lang] :as opts}
   {:keys [cmd pred output-fn verb usage]}]
  (let [inputs (or files (when file [file]))]
    (when (empty? inputs) (println usage) (System/exit 1))
    (let [[lang-name l] (get-lang lang file)
          lopts (lang-opts opts)]
      (lang/check-support! l lang-name cmd)
      (process-files
        {:inputs    inputs
         :pred      pred
         :transform (fn [path] ((cmd l) (slurp path) lopts))
         :output-fn output-fn
         :stdout    stdout
         :check     check
         :verb      verb}))))

(defn run
  "Run a meme source file. Requires :file in opts."
  [{:keys [file lang] :as opts}]
  (when-not file
    (binding [*out* *err*] (println "Usage: meme run <file> [--lang name]"))
    (System/exit 1))
  (let [[lang-name l] (get-lang lang file)]
    (lang/check-support! l lang-name :run)
    (try ((:run l) (slurp file) (lang-opts opts))
         (catch Exception e
           (binding [*out* *err*]
             (println (errors/format-error e (try (slurp file) (catch Exception _ nil)))))
           (System/exit 1)))))

(defn repl
  "Start an interactive meme REPL."
  [{:keys [lang] :as opts}]
  (let [[lang-name l] (get-lang lang nil)]
    (lang/check-support! l lang-name :repl)
    ((:repl l) (lang-opts opts))))

(defn to-clj
  "Convert meme files to Clojure and print to stdout or write to .clj files."
  [opts]
  (file-command opts
    {:cmd :to-clj, :pred meme-file?, :output-fn #(swap-ext % "meme" "clj")
     :verb "converted", :usage "Usage: meme to-clj <file|dir> [--lang name] [--stdout]"}))

(defn to-meme
  "Convert Clojure files to meme and print to stdout or write to .meme files."
  [opts]
  (file-command opts
    {:cmd :to-meme, :pred clj-file?, :output-fn #(swap-ext % "clj" "meme")
     :verb "converted", :usage "Usage: meme to-meme <file|dir> [--lang name] [--stdout]"}))

(defn format-cmd
  "Format meme source files in canonical style."
  [opts]
  (file-command opts
    {:cmd :format, :pred meme-file?, :output-fn nil
     :verb "formatted", :usage "Usage: meme format <file|dir> [--style canon|flat|clj] [--stdout] [--check]"}))

(defn inspect-cmd
  "Print diagnostic info about the current lang configuration."
  [{:keys [lang]}]
  (let [[lang-name l] (get-lang lang nil)]
    (println (str "Lang: " (name lang-name)))
    (println (str "  Supported: " (str/join ", " (map name (filter keyword? (keys l))))))))

(defn version
  "Print the meme version."
  [_]
  (println (if-let [v (some-> (io/resource "meme/version.txt") slurp str/trim)]
             (str "meme " v) "meme (version unknown)")))

(defn help
  "Print CLI usage help."
  [_]
  (let [l (lang/resolve-lang lang/default-lang)
        has? #(lang/supports? l %)]
    (println "meme — M-expressions for Clojure")
    (println)
    (println "Commands:")
    (when (has? :run)     (println "  meme run <file> [--lang name]"))
    (when (has? :repl)    (println "  meme repl [--lang name]"))
    (when (has? :to-clj)  (println "  meme to-clj <file|dir> [--lang name] [--stdout]"))
    (when (has? :to-meme) (println "  meme to-meme <file|dir> [--lang name] [--stdout]"))
    (when (has? :format)  (println "  meme format <file|dir> [--style canon|flat|clj] [--stdout] [--check]"))
    (println "  meme inspect [--lang name]")
    (println "  meme version")
    (println)
    (println (str "Langs: " (str/join ", " (map name (keys @lang/builtin)))))))

;; ---------------------------------------------------------------------------
;; bb entry point
;; ---------------------------------------------------------------------------

(defn- collect-file-args [{:keys [opts args]}]
  (let [all  (into (if (:file opts) [(:file opts)] []) args)
        base (if (= 1 (count all)) {:file (first all)} {:files all})]
    (merge (dissoc opts :file) base)))

(def ^:private file-spec
  {:args->opts [:file] :spec {:stdout {:coerce :boolean} :lang {:coerce :string}}})

(defn -main
  "CLI entry point. Parses args and dispatches to subcommands."
  [& args]
  (require 'babashka.cli)
  (let [dispatch (resolve 'babashka.cli/dispatch)
        file-cmd (fn [f] (fn [m] (f (collect-file-args m))))]
    (dispatch
      [{:cmds ["run"]     :fn (comp run :opts) :args->opts [:file] :spec {:lang {:coerce :string}}}
       {:cmds ["repl"]    :fn (comp repl :opts) :spec {:lang {:coerce :string}}}
       (assoc file-spec :cmds ["to-clj"]  :fn (file-cmd to-clj))
       (assoc file-spec :cmds ["to-meme"] :fn (file-cmd to-meme))
       {:cmds ["format"]  :fn (file-cmd format-cmd)
        :args->opts [:file] :spec {:stdout {:coerce :boolean} :check {:coerce :boolean}
                                   :lang {:coerce :string} :style {:coerce :string}
                                   :width {:coerce :long}}}
       {:cmds ["inspect"] :fn (comp inspect-cmd :opts) :spec {:lang {:coerce :string}}}
       {:cmds ["version"] :fn (fn [_] (version nil))}
       {:cmds [] :fn (fn [{:keys [args]}]
                       (when (seq args)
                         (binding [*out* *err*] (println (str "Unknown command: " (first args))))
                         (println))
                       (help nil)
                       (when (seq args) (System/exit 1)))}]
      args)))
