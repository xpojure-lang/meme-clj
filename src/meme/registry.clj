(ns meme.registry
  "Lang registry: registration, resolution, and EDN loading.
   JVM/Babashka only.

   A lang is a map of command functions:
     :run      (fn [source opts] → result)  — run a file
     :repl     (fn [opts] → nil)            — interactive loop
     :format   (fn [source opts] → text)    — format a file
     :to-clj   (fn [source] → clj-text)     — convert meme→clj
     :to-meme  (fn [source] → meme-text)    — convert clj→meme

   Plus optional metadata:
     :extension   \".ext\"           — file extension (string or vector)
     :extensions  [\".ext\" \".e\"]  — file extensions (string or vector)
   Both forms are accepted and normalized to :extensions [...].

   Every key is optional. A lang supports exactly the commands it has keys for.

   Built-in langs are self-describing: each defines a lang-map in its own namespace.
   User langs can be registered via register! with EDN-style config maps."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [meme-lang.api :as meme-lang]))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(defonce ^:private registry (atom {}))

(defn- normalize-extensions
  "Normalize :extension/:extensions into a flat vector of dot-prefixed strings.
   Accepts string or vector for either key. Both keys are merged."
  [m]
  (let [ext  (:extension m)
        exts (:extensions m)
        raw  (concat (if (sequential? ext) ext (when ext [ext]))
                     (if (sequential? exts) exts (when exts [exts])))
        normalized (mapv #(if (str/starts-with? % ".") % (str "." %)) raw)]
    (-> (dissoc m :extension :extensions)
        (cond-> (seq normalized) (assoc :extensions normalized)))))

(defn- register-builtin! [lang-name lang-map]
  (swap! registry assoc lang-name
         (vary-meta (normalize-extensions lang-map) assoc :builtin? true)))

;; Register built-in langs from their self-describing lang-maps
(register-builtin! :meme meme-lang/lang-map)

(def default-lang "The default lang used when none is specified." :meme)

;; ---------------------------------------------------------------------------
;; EDN value resolution (for user-defined langs)
;; ---------------------------------------------------------------------------

;; NOTE: resolve-symbol creates invisible runtime dependencies via requiring-resolve.
(defn- resolve-symbol
  "Resolve a qualified symbol to a var's value via requiring-resolve."
  [sym]
  (if-let [v (requiring-resolve sym)]
    @v
    (throw (ex-info (str "Cannot resolve symbol: " sym) {:symbol sym}))))

(defn- resolve-value
  "Resolve a single EDN value for a command.
   symbol   → requiring-resolve to a function
   string   → for :run, wraps as: eval .meme file then eval user source
   keyword  → look up that command from a registered lang
   fn/other → pass through (for register! with pre-resolved fns)"
  [command value]
  (cond
    (symbol? value)
    (resolve-symbol value)

    (string? value)
    (do (when (str/includes? value "..")
          (throw (ex-info (str "Path must not contain '..': " (pr-str value))
                          {:command command :value value})))
        (let [run-string-fn (resolve-symbol 'meme-lang.run/run-string)]
          (case command
            :run (fn [source opts]
                   (run-string-fn (slurp value) (dissoc opts :prelude :lang))
                   (run-string-fn source opts))
            (throw (ex-info (str "String value not supported for :" (name command)
                                 " — use a qualified symbol or keyword")
                            {:command command :value value})))))

    (keyword? value)
    (let [base (get @registry value)]
      (when-not base
        (throw (ex-info (str "Unknown lang: " value) {:value value})))
      (let [cmd-fn (get base command)]
        (when-not cmd-fn
          (throw (ex-info (str "Lang '" (name value) "' does not support :" (name command))
                          {:lang value :command command})))
        cmd-fn))

    (ifn? value)
    value

    :else
    (throw (ex-info (str "Invalid lang value for :" (name command)
                         " — expected symbol, string, keyword, or fn, got " (type value))
                    {:command command :value value}))))

(defn- resolve-edn
  "Resolve all values in an EDN map to functions.
   Normalizes :extension/:extensions into :extensions vector.
   Bakes :parser into the :run closure."
  [edn-data]
  (let [commands (dissoc edn-data :extension :extensions)
        base (into {} (map (fn [[k v]] [k (resolve-value k v)]) commands))
        run-fn (:run base)
        parser (:parser base)
        base (cond-> base
               (and run-fn parser)
               (assoc :run (fn [source opts]
                             (run-fn source (assoc opts :parser parser))))
               parser (dissoc :parser))]
    (normalize-extensions (merge base (select-keys edn-data [:extension :extensions])))))

;; ---------------------------------------------------------------------------
;; EDN loading (for user-defined langs)
;; ---------------------------------------------------------------------------

(defn load-edn
  "Load a lang from an EDN file. Returns a lang map with functions.
   H5 WARNING: this function executes code. Symbols in the EDN are resolved
   via requiring-resolve (loads namespaces from classpath). String values for
   :run are slurp'd and eval'd. Only use with trusted EDN files."
  [path]
  (let [edn-data (edn/read-string (slurp path))]
    (when-not (map? edn-data)
      (throw (ex-info (str "Lang EDN must be a map, got " (type edn-data))
                      {:path path})))
    (when-let [run-val (:run edn-data)]
      (when (string? run-val)
        (when (str/includes? run-val "..")
          (throw (ex-info (str "Lang :run path must not contain ..: " (pr-str run-val))
                          {:path path :run run-val})))))
    (resolve-edn edn-data)))

;; ---------------------------------------------------------------------------
;; User lang registration
;; ---------------------------------------------------------------------------

(defn register!
  "Register a user lang at runtime. config is an EDN-style map — symbols
   are resolved via requiring-resolve, strings and keywords follow the same
   rules as load-edn. Pre-resolved functions are passed through.
   Rejects attempts to override built-in langs.
   All conflict checks are atomic — performed inside swap!."
  [lang-name config]
  (let [resolved (resolve-edn config)]
    (swap! registry
      (fn [reg]
        (when-let [existing (get reg lang-name)]
          (when (:builtin? (meta existing))
            (throw (ex-info (str "Cannot override built-in lang " (pr-str lang-name)
                                 " — choose a different name")
                            {:lang lang-name}))))
        (doseq [ext (:extensions resolved)]
          (when (str/blank? ext)
            (throw (ex-info (str "Cannot register lang " (pr-str lang-name)
                                 " — extension must be a non-empty string")
                            {:lang lang-name})))
          (when (= ext ".meme")
            (throw (ex-info (str "Cannot register lang " (pr-str lang-name)
                                 " — extension .meme is reserved for built-in langs")
                            {:lang lang-name :extension ext})))
          (doseq [[existing-name existing-lang] reg]
            (when (and (not= existing-name lang-name)
                       (some #{ext} (:extensions existing-lang)))
              (throw (ex-info (str "Cannot register lang " (pr-str lang-name)
                                   " — extension " ext " already claimed by "
                                   (pr-str existing-name))
                              {:lang lang-name :extension ext
                               :existing existing-name})))))
        (assoc reg lang-name resolved)))))

(defn resolve-by-extension
  "Given a file path, find the lang whose :extensions match.
   Returns [lang-name lang-map] or nil."
  [path]
  (some (fn [[n l]]
          (when (some #(str/ends-with? path %) (:extensions l))
            [n l]))
        @registry))

(defn registered-langs
  "List all registered user language names (excludes built-ins)."
  []
  (keep (fn [[n l]] (when-not (:builtin? (meta l)) n)) @registry))

(defn clear-user-langs!
  "Clear all registered user languages, preserving built-ins. For testing."
  []
  (swap! registry (fn [m] (into {} (filter (fn [[_ v]] (:builtin? (meta v)))) m))))

(defn available-langs
  "Return a set of all available lang names (built-in + user-registered)."
  []
  (set (keys @registry)))

(defn registered-extensions
  "Return a seq of [dot-extension run-fn] for all langs with :extensions and :run.
   Used by the generic loader to search the classpath for lang source files."
  []
  (mapcat (fn [[_name lang-map]]
            (when-let [run-fn (:run lang-map)]
              (map (fn [ext] [ext run-fn]) (:extensions lang-map))))
          @registry))

(defn builtin-langs
  "Return a map of {lang-name lang-map} for all built-in langs."
  []
  (into {} (filter (fn [[_ v]] (:builtin? (meta v)))) @registry))

;; ---------------------------------------------------------------------------
;; Resolution
;; ---------------------------------------------------------------------------

(def ^:private legacy-names
  "Backward-compatible aliases from the pre-lang naming."
  {:classic :meme
   :meme-classic :meme
   :meme-experimental :meme})

(defn resolve-lang
  "Resolve a lang by keyword name. Returns the lang map.
   Deprecated name (:classic) is accepted but emits a warning.
   Throws on unknown name."
  [lang-name]
  (let [n (or lang-name default-lang)
        legacy? (contains? legacy-names n)
        n (get legacy-names n n)
        _ (when legacy?
            (binding [*out* *err*]
              (println (str "WARNING: :" (name lang-name) " is deprecated, use :" (name n)))))]
    (or (get @registry n)
        (throw (ex-info (str "Unknown lang: " (pr-str n)
                             " — available: " (pr-str (vec (keys @registry))))
                        {:lang n})))))

(defn supports?
  "Does the lang support the given command?"
  [lang command]
  (contains? lang command))

(defn check-support
  "Assert that the lang supports the given command. Throws if not."
  [lang lang-name command]
  (when-not (supports? lang command)
    (throw (ex-info (str "Lang '" (if (keyword? lang-name) (name lang-name) lang-name)
                         "' does not support :"
                         (name command)
                         " — supported: " (pr-str (vec (filter keyword? (keys lang)))))
                    {:lang lang-name :command command}))))

;; ---------------------------------------------------------------------------
;; Bootstrap: load .meme-based langs via the loader
;; ---------------------------------------------------------------------------
;; calc-lang is implemented entirely in .meme files.
;; Install the loader so requiring-resolve can find .meme on classpath,
;; then register calc-lang as a built-in.

(try
  (require 'meme.loader)
  ((resolve 'meme.loader/install!))
  (register-builtin! :calc @(requiring-resolve 'calc-lang.api/lang-map))
  (catch Exception _))
