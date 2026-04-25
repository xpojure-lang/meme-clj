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
     :form-shape  registry           — lang-owned decomposer map consumed by
                                       the printer/formatter (see
                                       `mclj-lang.form-shape`)
   Both extension forms are accepted and normalized to :extensions [...].

   Every key is optional. A lang supports exactly the commands it has keys for.

   Built-in langs are self-describing: each defines a lang-map in its own namespace.
   User langs can be registered via register! with EDN-style config maps.

   String values in a lang-map (e.g. `:run \"prelude.meme\"` in EDN) are
   resolved through handlers installed via `register-string-handler!`. This
   keeps the registry lang-agnostic — langs install their own conventions
   rather than the registry hardcoding meme's."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(defonce ^:private registry (atom {}))

;; Command slots whose EDN/register! value is a string are resolved through a
;; handler that a lang installs via `register-string-handler!`. The registry
;; itself stays lang-agnostic — it does not know how to interpret a string
;; path; whichever lang wants string-convention support (e.g. meme's "path is a
;; prelude file") installs its own handler at load time.
(defonce ^:private string-handlers (atom {}))

(defn register-string-handler!
  "Install a handler for resolving string values in the given command slot.
   `handler` is (fn [string-value] → command-fn) and is called once per
   register!/load-edn. Later registrations override earlier ones."
  [command handler]
  (swap! string-handlers assoc command handler))

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

(defn register-builtin!
  "Register a built-in language. Called at ns-load time from each lang's
   own api namespace — the registry itself imports no langs.  User langs
   should use `register!` instead, which validates and guards against
   built-in overrides."
  [lang-name lang-map]
  (swap! registry assoc lang-name
         (vary-meta (normalize-extensions lang-map) assoc :builtin? true)))

(def default-lang "The default lang used when none is specified." :mclj)

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
   string   → dispatched through the handler installed via
              register-string-handler! for this command slot (e.g. meme
              installs a :run handler that treats the string as a prelude path)
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
        (if-let [handler (get @string-handlers command)]
          (handler value)
          (throw (ex-info (str "String value not supported for :" (name command)
                               " — no string handler registered for this command. "
                               "Use a qualified symbol, keyword, or fn, or install "
                               "one via meme.registry/register-string-handler!.")
                          {:command command :value value}))))

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
   via requiring-resolve (loads namespaces from classpath). String values are
   dispatched through string handlers a lang installed via
   register-string-handler! (meme installs a :run handler that slurps and
   runs the string as a prelude file). Only use with trusted EDN files."
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

(defn- validate-registration!
  "Throws if adding `resolved` as `lang-name` would conflict with `snapshot`."
  [lang-name resolved snapshot]
  (when-let [existing (get snapshot lang-name)]
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
    (doseq [[existing-name existing-lang] snapshot]
      (when (and (not= existing-name lang-name)
                 (some #{ext} (:extensions existing-lang)))
        (throw (ex-info (str "Cannot register lang " (pr-str lang-name)
                             " — extension " ext " already claimed by "
                             (pr-str existing-name))
                        {:lang lang-name :extension ext
                         :existing existing-name}))))))

(defn register!
  "Register a user lang at runtime. config is an EDN-style map — symbols
   are resolved via requiring-resolve, strings and keywords follow the same
   rules as load-edn. Pre-resolved functions are passed through.
   Rejects attempts to override built-in langs.

   Validation and insertion are atomic via a compare-and-set! retry loop:
   each iteration re-validates against the current snapshot, so concurrent
   registrations with conflicting extensions detect the conflict rather
   than racing through."
  [lang-name config]
  (let [resolved (resolve-edn config)]
    (loop []
      (let [snapshot @registry]
        (validate-registration! lang-name resolved snapshot)
        (if (compare-and-set! registry snapshot (assoc snapshot lang-name resolved))
          resolved
          (recur))))))

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

(defn ^:no-doc clear-user-langs!
  "Clear all registered user languages, preserving built-ins. Test-only
   helper — not part of the public API."
  []
  (swap! registry (fn [m] (into {} (filter (fn [[_ v]] (:builtin? (meta v)))) m))))

(defn available-langs
  "Return a set of all available lang names (built-in + user-registered)."
  []
  (set (keys @registry)))

(defn ^:no-doc registered-extensions
  "Return a seq of [dot-extension run-fn] for all langs with :extensions and :run.
   Internal plumbing — consumed only by `meme.loader` to search the classpath
   for lang source files. Not part of the public API."
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

(defn resolve-lang
  "Resolve a lang by keyword name. Returns the lang map.
   Throws on unknown name."
  [lang-name]
  (let [n (or lang-name default-lang)]
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

