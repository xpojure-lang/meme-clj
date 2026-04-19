(ns meme.config
  "Project-local formatter configuration.

   Reads `.meme-format.edn` from the project root (walking up from a
   starting directory), validates it, and translates it into opts
   suitable for `meme-lang.formatter.canon/format-form`.

   Config schema:

     {:width                 positive integer (default 80)
      :structural-fallback?  boolean — infer defn-/let-like shapes
                             for unregistered heads
      :form-shape            map of symbol → symbol.  Each entry aliases
                             a user macro to an existing registry entry,
                             e.g. {my-defn defn, my-let let}.
      :style                 partial override of the canonical style.
                             Merged on top of canon/style; supports
                             :head-line-slots and :force-open-space-for.
                             :slot-renderers is not supported from EDN
                             (renderers are fns — not EDN-representable).}

   Unknown keys are ignored with a warning so that newer configs stay
   forward-compatible with older tooling."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [meme-lang.form-shape :as form-shape]
            [meme-lang.formatter.canon :as canon]))

(def config-filename
  "Filename used for project-local formatter config. Discovered by walking
   up the directory tree from the CWD."
  ".meme-format.edn")

(def ^:private known-keys
  #{:width :structural-fallback? :form-shape :style})

;; ---------------------------------------------------------------------------
;; Discovery
;; ---------------------------------------------------------------------------

(defn find-config-file
  "Walk up from `start-dir` looking for `.meme-format.edn`.
   Returns the absolute File, or nil if none is found before the root."
  [start-dir]
  (loop [dir (io/file (or start-dir "."))]
    (when dir
      (let [abs (.getCanonicalFile dir)
            candidate (io/file abs config-filename)]
        (cond
          (and (.isFile candidate) (.canRead candidate)) candidate
          :else (recur (.getParentFile abs)))))))

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(defn- fail! [msg data]
  (throw (ex-info msg (assoc data ::config-error true))))

(defn- validate-width! [v]
  (when v
    (when-not (and (integer? v) (pos? v))
      (fail! (str ":width must be a positive integer, got: " (pr-str v))
             {:key :width, :value v}))))

(defn- validate-boolean! [k v]
  (when (some? v)
    (when-not (boolean? v)
      (fail! (str k " must be a boolean, got: " (pr-str v))
             {:key k, :value v}))))

(defn- validate-form-shape! [fs]
  (when fs
    (when-not (map? fs)
      (fail! (str ":form-shape must be a map of symbol → symbol, got: " (pr-str fs))
             {:key :form-shape, :value fs}))
    (doseq [[k v] fs]
      (when-not (and (symbol? k) (symbol? v))
        (fail! (str ":form-shape entries must be symbol → symbol, got: "
                    (pr-str k) " → " (pr-str v))
               {:key :form-shape, :entry [k v]}))
      (when-not (contains? form-shape/registry v)
        (fail! (str ":form-shape alias target is not a registered head: " v
                    " (known: " (sort (map str (keys form-shape/registry))) ")")
               {:key :form-shape, :alias v})))))

(defn- validate-style! [s]
  (when s
    (when-not (map? s)
      (fail! (str ":style must be a map, got: " (pr-str s))
             {:key :style, :value s}))
    (doseq [k [:head-line-slots :force-open-space-for]]
      (when-let [v (get s k)]
        (when-not (and (set? v) (every? keyword? v))
          (fail! (str ":style " k " must be a set of keywords, got: " (pr-str v))
                 {:key [:style k], :value v}))))))

(defn- warn-unknown-keys! [config]
  (doseq [k (keys config)
          :when (not (contains? known-keys k))]
    (binding [*out* *err*]
      (println (str "warning: unknown .meme-format.edn key: " k " (ignored)")))))

(defn validate-config
  "Validate a config map (already parsed from EDN).  Throws ex-info with
   ::config-error on any violation.  Warns on unknown keys."
  [config]
  (when-not (map? config)
    (fail! (str ".meme-format.edn must contain a map, got: " (pr-str config))
           {:value config}))
  (validate-width! (:width config))
  (validate-boolean! :structural-fallback? (:structural-fallback? config))
  (validate-form-shape! (:form-shape config))
  (validate-style! (:style config))
  (warn-unknown-keys! config)
  config)

;; ---------------------------------------------------------------------------
;; Reading
;; ---------------------------------------------------------------------------

(defn read-config-file
  "Read and validate a `.meme-format.edn` file.  Returns the parsed
   config map.  Throws ex-info with ::config-error if the file is
   malformed or fails validation."
  [^java.io.File file]
  (let [text (try (slurp file)
                  (catch Exception e
                    (fail! (str "Could not read " (.getPath file) ": " (ex-message e))
                           {:file (.getPath file)})))
        parsed (try (edn/read-string {:default (fn [tag val]
                                                 (fail! (str "Unknown tag in config: #" tag " " (pr-str val))
                                                        {:tag tag}))}
                                     text)
                    (catch Exception e
                      (fail! (str "Malformed EDN in " (.getPath file) ": " (ex-message e))
                             {:file (.getPath file)})))]
    (validate-config parsed)))

;; ---------------------------------------------------------------------------
;; Translation: config → opts
;; ---------------------------------------------------------------------------

(defn config->opts
  "Translate a validated config map into opts for
   `meme-lang.formatter.canon/format-form`.

   Computes the effective form-shape registry (base + aliases, optionally
   wrapped with structural fallback) and the effective style (canon/style
   merged with any override).  Opts that were absent from the config are
   omitted so that per-call arguments can still override."
  [config]
  (let [aliases (reduce-kv (fn [m sym target-sym]
                             (assoc m sym (get form-shape/registry target-sym)))
                           {}
                           (:form-shape config))
        registry (cond-> (merge form-shape/registry aliases)
                   (:structural-fallback? config)
                   form-shape/with-structural-fallback)
        style (merge canon/style (:style config))]
    (cond-> {}
      (:width config)                 (assoc :width (:width config))
      (or (seq aliases)
          (:structural-fallback? config)) (assoc :form-shape registry)
      (:style config)                 (assoc :style style))))

;; ---------------------------------------------------------------------------
;; Convenience
;; ---------------------------------------------------------------------------

(defn resolve-project-opts
  "Locate `.meme-format.edn` starting from `start-dir` (or CWD), read it,
   and return the derived opts map.  Returns `{}` if no config is found.
   Errors in the config are thrown — the CLI catches and reports them."
  ([] (resolve-project-opts nil))
  ([start-dir]
   (if-let [f (find-config-file start-dir)]
     (assoc (config->opts (read-config-file f))
            ::source (.getPath f))
     {})))
