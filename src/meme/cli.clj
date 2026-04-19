(ns meme.cli
  "Unified CLI. Commands dispatch through lang maps.

   This namespace is the app — it explicitly requires each built-in lang,
   which triggers its self-registration in meme.registry.  The registry
   itself imports no langs; adding a new built-in means a one-line
   require here plus the lang's own register-builtin! call."
  (:require [meme-lang.errors :as errors]
            [meme.registry :as registry]
            [meme.loader :as loader]
            [meme.config :as config]
            ;; Built-in lang registrations fire on ns-load:
            [meme-lang.api]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; calc-lang is a demo implemented in .meme files — optional, lazy-loaded.
;; Register it if the loader is available and calc-lang is on the classpath.
(when-not (some? (System/getProperty "babashka.version"))
  (try
    (loader/install!)
    (registry/register-builtin! :calc @(requiring-resolve 'calc-lang.api/lang-map))
    (catch Exception _)))

;; ---------------------------------------------------------------------------
;; CLI exit — throw instead of System/exit so commands are testable
;; ---------------------------------------------------------------------------

(defn- cli-exit!
  "Signal a CLI exit. Throws ex-info with ::exit data instead of calling
   System/exit directly, so command functions are testable and composable.
   -main catches this and calls System/exit."
  [code]
  (throw (ex-info "" {::exit code})))

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

(defn- meme-file? [path] (some? (registry/resolve-by-extension path)))
(defn- clj-file? [path] (boolean (re-find #"\.clj[cdsx]?$" path)))
(defn- swap-ext [path from to]
  (if (= from "meme")
    ;; meme→clj: strip any registered meme extension, append target
    (if-let [[_ lang] (registry/resolve-by-extension path)]
      (let [ext (first (filter #(str/ends-with? path %) (:extensions lang)))]
        (str (subs path 0 (- (count path) (count ext))) "." to))
      (str/replace path #"\.meme$" (str "." to)))
    ;; clj→meme: .clj[cdsx]? regex works for Clojure extensions
    (str/replace path (re-pattern (str "\\." from "[cdsx]?$")) (str "." to))))

;; ---------------------------------------------------------------------------
;; Generic file processor
;; ---------------------------------------------------------------------------

(defn- process-files [{:keys [inputs pred transform output-fn stdout check verb]}]
  (let [expanded (expand-inputs inputs pred)]
    (when (empty? expanded)
      (println "No matching files found.")
      (cli-exit! 1))
    (let [process-one
          (fn [path]
            (try
              (let [src (slurp path)]
                (cond
                  stdout (do (println (transform src)) :ok)
                  check  (let [formatted (str (transform src) "\n")]
                           (if (= src formatted) :ok
                               (do (println (str "would reformat: " path)) :fail)))
                  :else  (let [out    (if output-fn (output-fn path) path)
                               result (transform src)]
                           (spit out (str result "\n"))
                           (println (if (= path out) (str verb " " path) (str path " → " out)))
                           :ok)))
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
      (when (pos? failed) (cli-exit! 1)))))

;; ---------------------------------------------------------------------------
;; Lang resolution
;; ---------------------------------------------------------------------------

(def ^:private cli-keys #{:file :files :stdout :check :lang})

(defn- lang-opts [m] (apply dissoc m cli-keys))

(defn- get-lang [lang-str file]
  (cond
    (and lang-str (str/ends-with? lang-str ".edn"))
    (try [lang-str (registry/load-edn lang-str)]
         (catch Exception e
           (binding [*out* *err*]
             (println (str "Error loading lang " lang-str ": " (ex-message e))))
           (cli-exit! 1)))

    lang-str
    (let [kw (keyword lang-str)]
      (try [kw (registry/resolve-lang kw)]
           (catch Exception _
             (binding [*out* *err*]
               (println (str "Unknown lang: " kw))
               (println (str "Available: " (str/join ", " (map name (registry/available-langs))))))
             (cli-exit! 1))))

    file
    (or (registry/resolve-by-extension file)
        [registry/default-lang (registry/resolve-lang registry/default-lang)])

    :else [registry/default-lang (registry/resolve-lang registry/default-lang)]))

;; ---------------------------------------------------------------------------
;; Commands — all delegate to lang
;; ---------------------------------------------------------------------------

(defn- file-command
  "Generic: resolve lang, check support, process files via lang command."
  [{:keys [file files stdout check lang] :as opts}
   {:keys [cmd pred output-fn verb usage]}]
  (let [inputs (or files (when file [file]))]
    (when (empty? inputs) (println usage) (cli-exit! 1))
    (let [[lang-name l] (get-lang lang file)
          lopts (lang-opts opts)]
      (registry/check-support l lang-name cmd)
      (process-files
        {:inputs    inputs
         :pred      pred
         :transform (fn [src] ((cmd l) src lopts))
         :output-fn output-fn
         :stdout    stdout
         :check     check
         :verb      verb}))))

(defn run
  "Run a meme source file. Requires :file in opts.
   Binds *command-line-args* to the user's args (excluding the command verb and filename).
   The lang's :run installs the loader by default."
  [{:keys [file lang rest-args] :as opts}]
  (when-not file
    (binding [*out* *err*] (println "Usage: meme run <file> [--lang name] [-- args...]"))
    (cli-exit! 1))
  (let [[lang-name l] (get-lang lang file)]
    (registry/check-support l lang-name :run)
    (try (binding [*command-line-args* (or rest-args [])]
           ((:run l) (slurp file) (lang-opts opts)))
         (catch Exception e
           (binding [*out* *err*]
             (println (errors/format-error e (try (slurp file) (catch Exception _ nil)))))
           (cli-exit! 1)))))

(defn repl
  "Start an interactive meme REPL."
  [{:keys [lang] :as opts}]
  (let [[lang-name l] (get-lang lang nil)]
    (registry/check-support l lang-name :repl)
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

(defn- resolve-format-config
  "Discover and read `.meme-format.edn` from CWD, returning derived opts.
   Errors in the config file are reported to stderr and cause an exit;
   absence of a config file is silent."
  []
  (try (config/resolve-project-opts)
       (catch Exception e
         (binding [*out* *err*]
           (println (str "Error in .meme-format.edn: " (ex-message e))))
         (cli-exit! 1))))

(defn format-files
  "Format meme source files in canonical style.

   Reads `.meme-format.edn` from the working directory (walking up) if
   present; its settings are applied as defaults under CLI flags."
  [opts]
  (let [project-opts (resolve-format-config)
        ;; CLI flags override project config; project config overrides defaults.
        merged (merge project-opts opts)]
    (file-command merged
      {:cmd :format, :pred meme-file?, :output-fn nil
       :verb "formatted", :usage "Usage: meme format <file|dir> [--style canon|flat|clj] [--stdout] [--check]"})))

(defn compile-meme
  "Compile .meme files to .clj in a separate output directory.
   Preserves relative paths. Output can be added to :paths in deps.edn
   so that require, load-file, and nREPL all work without runtime patching."
  [{:keys [file files out lang] :as opts}]
  (let [inputs (or files (when file [file]))
        out-dir (or out "target/classes")]
    (when (empty? inputs)
      (println "Usage: meme compile <src-dir|file...> [--out target/classes] [--lang name]")
      (cli-exit! 1))
    (let [[lang-name l] (get-lang lang nil)
          _ (registry/check-support l lang-name :to-clj)
          to-clj-fn (:to-clj l)
          expanded (expand-inputs inputs meme-file?)
          ;; Find the common root of all inputs to compute relative paths
          roots (mapv (fn [input]
                        (let [f (io/file input)]
                          (if (.isDirectory f) (.getCanonicalPath f)
                              (.getCanonicalPath (.getParentFile f)))))
                      inputs)]
      (when (empty? expanded)
        (println "No .meme files found.")
        (cli-exit! 1))
      (let [process-one
            (fn [path]
              (try
                (let [abs (.getCanonicalPath (io/file path))
                      ;; Find the matching root to strip
                      root (some #(when (str/starts-with? abs (str % "/")) %) roots)
                      rel (if root (subs abs (inc (count root))) (.getName (io/file path)))
                      out-path (str out-dir "/" (swap-ext rel "meme" "clj"))
                      out-file (io/file out-path)]
                  (.mkdirs (.getParentFile out-file))
                  (spit out-file (str (to-clj-fn (slurp path)) "\n"))
                  (println (str path " → " out-path))
                  :ok)
                (catch Exception e
                  (binding [*out* *err*]
                    (println (errors/format-error e (try (slurp path) (catch Exception _ nil)))))
                  :fail)))
            results (doall (map process-one expanded))
            total (count results)
            failed (count (filter #{:fail} results))]
        (println)
        (println (str total " file(s) compiled to " out-dir
                      (when (pos? failed) (str ", " failed " failed"))))
        (when (pos? failed) (cli-exit! 1))))))

(defn inspect-lang
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
  (let [l (registry/resolve-lang registry/default-lang)
        has? #(registry/supports? l %)]
    (println "meme — M-expressions for Clojure")
    (println)
    (println "Commands:")
    (when (has? :run)     (println "  meme run <file> [--lang name]"))
    (when (has? :repl)    (println "  meme repl [--lang name]"))
    (when (has? :to-clj)  (println "  meme to-clj <file|dir> [--lang name] [--stdout]"))
    (when (has? :to-meme) (println "  meme to-meme <file|dir> [--lang name] [--stdout]"))
    (when (has? :format)  (println "  meme format <file|dir> [--style canon|flat|clj] [--stdout] [--check]"))
    (when (has? :to-clj)  (println "  meme compile <src-dir|file...> [--out target/classes] [--lang name]"))
    (println "  meme inspect [--lang name]")
    (println "  meme version")
    (println)
    (println (str "Langs: " (str/join ", " (map name (registry/available-langs)))))))

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
    (try
      (dispatch
        [{:cmds ["run"]     :fn (fn [{:keys [opts args]}]
                                      (run (assoc opts :rest-args (vec args))))
          :args->opts [:file] :spec {:lang {:coerce :string}}}
         {:cmds ["repl"]    :fn (comp repl :opts) :spec {:lang {:coerce :string}}}
         (assoc file-spec :cmds ["to-clj"]  :fn (file-cmd to-clj))
         (assoc file-spec :cmds ["to-meme"] :fn (file-cmd to-meme))
         {:cmds ["format"]  :fn (file-cmd format-files)
          :args->opts [:file] :spec {:stdout {:coerce :boolean} :check {:coerce :boolean}
                                     :lang {:coerce :string} :style {:coerce :string}
                                     :width {:coerce :long}}}
         {:cmds ["compile"] :fn (file-cmd compile-meme)
          :args->opts [:file] :spec {:out {:coerce :string} :lang {:coerce :string}}}
         {:cmds ["inspect"] :fn (comp inspect-lang :opts) :spec {:lang {:coerce :string}}}
         {:cmds ["version"] :fn (fn [_] (version nil))}
         {:cmds [] :fn (fn [{:keys [args]}]
                         (when (seq args)
                           (binding [*out* *err*] (println (str "Unknown command: " (first args))))
                           (println))
                         (help nil)
                         (when (seq args) (cli-exit! 1)))}]
        args)
      (catch Exception e
        (if-let [code (::exit (ex-data e))]
          (System/exit code)
          (let [msg (ex-message e)]
            (if (and msg (re-find #"(?i)coerce" msg))
              (do (binding [*out* *err*] (println (str "Error: " msg)))
                  (System/exit 1))
              (throw e))))))))
