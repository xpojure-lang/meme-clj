(ns meme.cli
  "Unified CLI. Commands dispatch through lang maps.

   This namespace is the app — it explicitly requires each built-in lang,
   which triggers its self-registration in meme.registry.  The registry
   itself imports no langs; adding a new built-in means a one-line
   require here plus the lang's own register-builtin! call."
  (:require [meme.tools.clj.errors :as errors]
            [meme.registry :as registry]
            ;; Built-in lang registrations fire on ns-load:
            [m1clj-lang.api]
            [m2clj-lang.api]
            [clj-lang.api]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

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

(defn- clj-file? [path] (boolean (re-find #"\.clj[cdsx]?$" path)))

(defn- guest-file?
  "True if `path` is a guest-lang file (any registered lang EXCEPT :clj).
   Used by lang-specific commands (`to-clj`, `transpile`, `build`) that
   operate on guest source — not on Clojure files. `format` uses
   `recognized-file?` instead, which accepts every registered lang
   including :clj."
  [path]
  (boolean (when-let [[lang-name _] (registry/resolve-by-extension path)]
             (not= :clj lang-name))))

(defn- recognized-file?
  "True if any registered lang claims `path`'s extension. Used by
   `format`, which dispatches on the file's lang per the registry."
  [path]
  (some? (registry/resolve-by-extension path)))

(defn- swap-ext [path from to]
  (if (= from "m1clj")
    ;; guest→clj: strip the registered guest extension, append target.
    ;; The `from` parameter is historic; the actual extension swap comes
    ;; from the registry-resolved lang for the file.
    (if-let [[_ lang] (registry/resolve-by-extension path)]
      (let [ext (first (filter #(str/ends-with? path %) (:extensions lang)))]
        (str (subs path 0 (- (count path) (count ext))) "." to))
      (str/replace path #"\.(m1clj|m2clj|meme)$" (str "." to)))
    ;; clj→guest: .clj[cdsx]? regex works for Clojure extensions
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
            (let [src (try (slurp path) (catch Exception _ nil))]
              (try
                (cond
                  (nil? src) (throw (ex-info (str "Cannot read file: " path) {}))
                  stdout (do (println (transform src)) :ok)
                  check  (let [formatted (str (transform src) "\n")]
                           (if (= src formatted) :ok
                               (do (println (str "would reformat: " path)) :fail)))
                  :else  (let [out    (if output-fn (output-fn path) path)
                               result (transform src)]
                           (spit out (str result "\n"))
                           (println (if (= path out) (str verb " " path) (str path " → " out)))
                           :ok))
                (catch Exception e
                  (binding [*out* *err*]
                    (println (errors/format-error e src)))
                  :fail))))
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
  "Generic: resolve lang, check support, process files via lang command.

   `:lang-override` (in cmd-spec) pins the lang to a specific keyword
   regardless of file extension or `--lang` opt. Used by `to-m1clj` /
   `to-m2clj` whose target is implicit in the command name and whose
   source files are always Clojure (so extension dispatch would resolve
   to clj-lang, which doesn't carry guest-target slots)."
  [{:keys [file files stdout check lang] :as opts}
   {:keys [cmd pred output-fn verb usage lang-override]}]
  (let [inputs (or files (when file [file]))]
    (when (empty? inputs) (println usage) (cli-exit! 1))
    (let [[lang-name l] (if lang-override
                          [lang-override (registry/resolve-lang lang-override)]
                          (get-lang lang file))
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
  "Run an m1clj source file (or any registered guest). Requires :file in opts.
   Binds *command-line-args* to the user's args (excluding the command verb and filename).
   The lang's :run installs the loader by default."
  [{:keys [file lang rest-args] :as opts}]
  (when-not file
    (binding [*out* *err*] (println "Usage: meme run <file> [--lang name] [-- args...]"))
    (cli-exit! 1))
  (let [[lang-name l] (get-lang lang file)
        source (try (slurp file)
                    (catch Exception e
                      (binding [*out* *err*]
                        (println (str "Cannot read file: " file
                                      (when-let [m (ex-message e)] (str " — " m)))))
                      (cli-exit! 1)))]
    (registry/check-support l lang-name :run)
    (try (binding [*command-line-args* (or rest-args [])]
           ((:run l) source (lang-opts opts)))
         (catch Exception e
           (binding [*out* *err*]
             (println (errors/format-error e source)))
           (cli-exit! 1)))))

(defn repl
  "Start an interactive REPL (m1clj by default; selectable via --lang)."
  [{:keys [lang] :as opts}]
  (let [[lang-name l] (get-lang lang nil)]
    (registry/check-support l lang-name :repl)
    ((:repl l) (lang-opts opts))))

(defn to-clj
  "Convert guest files (m1clj, m2clj, …) to Clojure and print to stdout or
   write to .clj files. Auto-detects the source lang via file extension."
  [opts]
  (file-command opts
    {:cmd :to-clj, :pred guest-file?, :output-fn #(swap-ext % "m1clj" "clj")
     :verb "converted", :usage "Usage: meme to-clj <file|dir> [--lang name] [--stdout]"}))

(defn to-m1clj
  "Convert Clojure files to m1clj and print to stdout or write to .m1clj files."
  [opts]
  (file-command opts
    {:cmd :to-m1clj, :pred clj-file?, :output-fn #(swap-ext % "clj" "m1clj")
     :lang-override :m1clj
     :verb "converted", :usage "Usage: meme to-m1clj <file|dir> [--stdout]"}))

(defn to-m2clj
  "Convert Clojure files to m2clj and print to stdout or write to .m2clj files."
  [opts]
  (file-command opts
    {:cmd :to-m2clj, :pred clj-file?, :output-fn #(swap-ext % "clj" "m2clj")
     :lang-override :m2clj
     :verb "converted", :usage "Usage: meme to-m2clj <file|dir> [--stdout]"}))

(defn format-files
  "Format source files in canonical style (any registered guest). Pass --width, --style,
   --stdout, --check as needed."
  [opts]
  (file-command opts
    {:cmd :format, :pred recognized-file?, :output-fn nil
     :verb "formatted", :usage "Usage: meme format <file|dir> [--width N] [--style canon|flat|clj] [--stdout] [--check]"}))

(defn- validate-out-dir!
  "Reject an explicit but blank --out value. Without this, `(str \"\" sep rel)`
   resolves to `/rel` and we silently write into the filesystem root."
  [out]
  (when (and (some? out) (str/blank? out))
    (println "Error: --out cannot be empty")
    (cli-exit! 1)))

(defn transpile-m1clj
  "Transpile .m1clj files to .clj in a separate output directory.
   Preserves relative paths. Output can be added to :paths in deps.edn
   so that require, load-file, and nREPL all work without runtime patching."
  [{:keys [file files out lang]}]
  (validate-out-dir! out)
  (let [inputs (or files (when file [file]))
        out-dir (or out "target/m1clj")]
    (when (empty? inputs)
      (println "Usage: meme transpile <src-dir|file...> [--out target/m1clj] [--lang name]")
      (cli-exit! 1))
    (let [[lang-name l] (get-lang lang nil)
          _ (registry/check-support l lang-name :to-clj)
          to-clj-fn (:to-clj l)
          expanded (expand-inputs inputs guest-file?)
          ;; Find the common root of all inputs to compute relative paths
          roots (mapv (fn [input]
                        (let [f (io/file input)]
                          (if (.isDirectory f) (.getCanonicalPath f)
                              (.getCanonicalPath (.getParentFile f)))))
                      inputs)]
      (when (empty? expanded)
        (println "No .m1clj files found.")
        (cli-exit! 1))
      (let [sep java.io.File/separator
            process-one
            (fn [path]
              (let [src (try (slurp path) (catch Exception _ nil))]
                (try
                  (when (nil? src) (throw (ex-info (str "Cannot read file: " path) {})))
                  (let [abs (.getCanonicalPath (io/file path))
                        ;; Find the matching root to strip. Use the platform
                        ;; File separator — getCanonicalPath returns `\` on
                        ;; Windows and `/` elsewhere; a hardcoded `/` would
                        ;; never match on Windows and collapse every file to
                        ;; its basename in the flat `out-dir` fallback.
                        root (some #(when (str/starts-with? abs (str % sep)) %) roots)
                        rel (if root (subs abs (inc (count root))) (.getName (io/file path)))
                        out-path (str out-dir sep (swap-ext rel "m1clj" "clj"))
                        out-file (io/file out-path)]
                    (.mkdirs (.getParentFile out-file))
                    (spit out-file (str (to-clj-fn src) "\n"))
                    (println (str path " → " out-path))
                    :ok)
                  (catch Exception e
                    (binding [*out* *err*]
                      (println (errors/format-error e src)))
                    :fail))))
            results (doall (map process-one expanded))
            total (count results)
            failed (count (filter #{:fail} results))]
        (println)
        (println (str total " file(s) transpiled to " out-dir
                      (when (pos? failed) (str ", " failed " failed"))))
        (when (pos? failed) (cli-exit! 1))))))

(defn- ns-of-clj-file
  "Read the first `(ns ...)` form from a .clj file, return its symbol."
  [^java.io.File f]
  (with-open [r (java.io.PushbackReader. (java.io.FileReader. f))]
    (let [form (try (read r) (catch Exception _ nil))]
      (when (and (seq? form) (= 'ns (first form)) (symbol? (second form)))
        (second form)))))

(defn build
  "AOT-compile .m1clj sources to JVM bytecode.

   Pipeline: transpile to a staging dir (fixed at `target/m1clj`), then
   spawn `clojure` with that dir on the classpath and run
   `clojure.core/compile` on each discovered namespace. Output is
   `.class` files in `--out` (default `target/classes`).

   Meme stops at bytecode — JAR packaging stays in your own tools.build
   layer. For finer control (skip the staging dir, use the meme loader
   at compile time, or integrate with an existing build.clj), see the
   recipes in doc/language-reference.md."
  [{:keys [file files out lang]}]
  (validate-out-dir! out)
  (let [inputs (or files (when file [file]) ["src"])
        stage-dir "target/m1clj"
        aot-dir (or out "target/classes")]
    (println (str "[1/2] Transpiling to " stage-dir "..."))
    (transpile-m1clj (cond-> {:out stage-dir :lang lang}
                      (seq files) (assoc :files files)
                      (and file (not files)) (assoc :file file)
                      (not (or file files)) (assoc :files inputs)))
    (println)
    (let [stage (io/file stage-dir)
          clj-files (filter #(and (.isFile ^java.io.File %)
                                  (str/ends-with? (.getName ^java.io.File %) ".clj"))
                            (file-seq stage))
          nses (keep ns-of-clj-file clj-files)]
      (when (empty? nses)
        (println "No compilable namespaces found in" stage-dir)
        (cli-exit! 1))
      (println (str "[2/2] AOT-compiling " (count nses) " namespace(s) to " aot-dir "..."))
      (.mkdirs (io/file aot-dir))
      (let [path-sep java.io.File/pathSeparator
            stage-abs (.getCanonicalPath (io/file stage-dir))
            cur-cp (or (System/getProperty "java.class.path") "")
            cp-paths (->> (conj (str/split cur-cp (re-pattern path-sep)) stage-abs)
                          (remove str/blank?) distinct)
            paths-edn (str "{:paths [" (str/join " " (map pr-str cp-paths)) "]}")
            compile-form (format (str "(binding [*compile-path* %s]"
                                      " (doseq [n '%s]"
                                      "   (println (str \"  compile \" n))"
                                      "   (compile n)))")
                                 (pr-str aot-dir)
                                 (pr-str (vec nses)))
            {:keys [exit out err]}
            (shell/sh "clojure" "-Sdeps" paths-edn "-M" "-e" compile-form)]
        (when (seq out) (print out) (flush))
        (when (seq err) (binding [*out* *err*] (print err) (flush)))
        (if (zero? exit)
          (println (str "Built " (count nses) " namespace(s) to " aot-dir))
          (do (binding [*out* *err*]
                (println (str "AOT compile failed (exit " exit ")")))
              (cli-exit! exit)))))))

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
    (println "meme-clj — syntax-experimentation toolkit (m1clj is the first language)")
    (println)
    (println "Commands:")
    (when (has? :run)     (println "  meme run <file> [--lang name]"))
    (when (has? :repl)    (println "  meme repl [--lang name]"))
    (when (has? :to-clj)  (println "  meme to-clj   <file|dir> [--lang name] [--stdout]  (alias: from-meme)"))
    (when (has? :to-m1clj) (println "  meme to-m1clj  <file|dir> [--lang name] [--stdout]  (alias: from-clj)"))
    (when (some #(get (registry/resolve-lang %) :to-m2clj) (registry/available-langs))
      (println "  meme to-m2clj  <file|dir> [--stdout]"))
    (when (has? :format)  (println "  meme format <file|dir> [--style canon|flat|clj] [--stdout] [--check]"))
    (when (has? :to-clj)  (println "  meme transpile <src-dir|file...> [--out target/m1clj] [--lang name]  (alias: compile)"))
    (when (has? :to-clj)  (println "  meme build     <src-dir|file...> [--out target/classes] [--lang name]"))
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
  ;; babashka.cli is loaded lazily here rather than via `ns :require` so
  ;; non-CLI consumers of meme.cli (programmatic embedders) don't pay
  ;; the dep cost. Babashka bundles it; on JVM it comes via the :meme
  ;; alias's classpath at invocation time.
  (require 'babashka.cli)
  (let [dispatch (resolve 'babashka.cli/dispatch)
        file-cmd (fn [f] (fn [m] (f (collect-file-args m))))]
    (try
      (dispatch
        [{:cmds ["run"]     :fn (fn [{:keys [opts args]}]
                                      (run (assoc opts :rest-args (vec args))))
          :args->opts [:file] :spec {:lang {:coerce :string}}}
         {:cmds ["repl"]    :fn (comp repl :opts) :spec {:lang {:coerce :string}}}
         (assoc file-spec :cmds ["to-clj"]    :fn (file-cmd to-clj))
         (assoc file-spec :cmds ["from-meme"] :fn (file-cmd to-clj))
         (assoc file-spec :cmds ["to-m1clj"]   :fn (file-cmd to-m1clj))
         (assoc file-spec :cmds ["from-clj"]  :fn (file-cmd to-m1clj))
         (assoc file-spec :cmds ["to-m2clj"]   :fn (file-cmd to-m2clj))
         {:cmds ["format"]  :fn (file-cmd format-files)
          :args->opts [:file] :spec {:stdout {:coerce :boolean} :check {:coerce :boolean}
                                     :lang {:coerce :string} :style {:coerce :string}
                                     :width {:coerce :long}}}
         {:cmds ["transpile"] :fn (file-cmd transpile-m1clj)
          :args->opts [:file] :spec {:out {:coerce :string} :lang {:coerce :string}}}
         {:cmds ["compile"]   :fn (file-cmd transpile-m1clj)
          :args->opts [:file] :spec {:out {:coerce :string} :lang {:coerce :string}}}
         {:cmds ["build"]     :fn (file-cmd build)
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
        (let [data (ex-data e)]
          (cond
            ;; Internal exit signal from cli-exit!
            (::exit data)
            (System/exit (::exit data))

            ;; babashka.cli argument/coerce errors — tagged with :type
            ;; :org.babashka/cli and carry a :cause keyword (:coerce,
            ;; :require, etc.). Structured match supersedes the earlier
            ;; regex that pattern-matched the English "Coerce" message
            ;; and would miss other failure modes (e.g. :require).
            (= :org.babashka/cli (:type data))
            (do (binding [*out* *err*] (println (str "Error: " (ex-message e))))
                (System/exit 1))

            :else (throw e)))))))
