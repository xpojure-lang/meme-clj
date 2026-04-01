(ns meme.lang
  "Lang registry, resolution, and EDN loading.

   A lang is a map of command functions:
     :run      (fn [source opts] → result)  — run a file
     :repl     (fn [opts] → nil)            — interactive loop
     :format   (fn [source opts] → text)    — format a file
     :to-clj   (fn [source] → clj-text)     — convert meme→clj (self-contained)
     :to-meme  (fn [source] → meme-text)    — convert clj→meme (JVM only, self-contained)

   Plus optional metadata:
     :extension  \".ext\"   — file extension for auto-detection

   Every key is optional. A lang supports exactly the commands it has keys for.
   The CLI dispatches by looking up the command key in the lang map.

   All lang definitions — built-in and user-defined — are EDN:
     {:run      meme.runtime.run/run-string
      :format   meme.lang.meme-classic/format-meme
      :to-clj   meme.lang.meme-classic/to-clj
      :to-meme  meme.lang.meme-classic/to-meme}

   User langs can also use:
     {:extension \".calc\"        ;; file extension for auto-detection
      :run \"core.meme\"          ;; string → .meme file to eval before user file
      :rules \"rules.meme\"       ;; string → .meme file returning rewrite rules
      :parser my.ns/parser-fn    ;; symbol → custom parser function
      :format :meme-classic}     ;; keyword → inherit command from built-in lang

   Built-in langs (resources/meme/lang/):
     :meme-classic (default), :meme-rewrite, :meme-trs"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            #?(:clj [clojure.java.io :as io])
            [meme.lang.meme-classic]
            [meme.lang.meme-rewrite]
            [meme.lang.meme-trs]))

;; ---------------------------------------------------------------------------
;; EDN value resolution
;; ---------------------------------------------------------------------------

#?(:clj
   (defn- resolve-symbol
     "Resolve a qualified symbol to a var's value via requiring-resolve."
     [sym]
     (if-let [v (requiring-resolve sym)]
       @v
       (throw (ex-info (str "Cannot resolve symbol: " sym) {:symbol sym})))))

(declare builtin)

#?(:clj
   (defn- resolve-value
     "Resolve a single EDN value for a command.
      symbol   → requiring-resolve to a function
      string   → for :run, wraps as: eval .meme file then eval user source
                 for :rules, eval file to get rule vector
      keyword  → look up that command from a built-in lang
      fn/other → pass through (for register! with pre-resolved fns)"
     [command value]
     (cond
       (symbol? value)
       (resolve-symbol value)

       (string? value)
       (let [run-string-fn (resolve-symbol 'meme.runtime.run/run-string)]
         (case command
           :run (fn [source opts]
                  (run-string-fn (slurp value) (dissoc opts :rewrite-rules :prelude))
                  (run-string-fn source opts))
           :rules (run-string-fn (slurp value))
           (throw (ex-info (str "String value not supported for :" (name command)
                                " — use a qualified symbol or keyword")
                           {:command command :value value}))))

       (keyword? value)
       (let [base (get @builtin value)]
         (when-not base
           (throw (ex-info (str "Unknown built-in lang: " value) {:value value})))
         (let [cmd-fn (get base command)]
           (when-not cmd-fn
             (throw (ex-info (str "Built-in '" (name value) "' does not support :" (name command))
                             {:lang value :command command})))
           cmd-fn))

       (ifn? value)
       value

       :else
       (throw (ex-info (str "Invalid lang value for :" (name command)
                            " — expected symbol, string, keyword, or fn, got " (type value))
                       {:command command :value value})))))

#?(:clj
   (defn- resolve-edn
     "Resolve all values in an EDN map to functions.
      Extracts :extension as metadata (not a command).
      Bakes :rules and :parser into the :run closure."
     [edn-data]
     (let [ext (get edn-data :extension)
           commands (dissoc edn-data :extension)
           base (into {} (map (fn [[k v]] [k (resolve-value k v)]) commands))
           run-fn (:run base)
           rules  (:rules base)
           parser (:parser base)
           base (cond-> base
                  (and run-fn (or rules parser))
                  (assoc :run (fn [source opts]
                                (run-fn source
                                  (cond-> opts
                                    rules  (assoc :rewrite-rules rules)
                                    parser (assoc :parser parser)))))
                  rules  (dissoc :rules)
                  parser (dissoc :parser))]
       (cond-> base ext (assoc :extension ext)))))

;; ---------------------------------------------------------------------------
;; EDN loading
;; ---------------------------------------------------------------------------

#?(:clj
   (defn load-edn
     "Load a lang from an EDN file. Returns a lang map with functions."
     [path]
     (let [edn-data (edn/read-string (slurp path))]
       (when-not (map? edn-data)
         (throw (ex-info (str "Lang EDN must be a map, got " (type edn-data))
                         {:path path})))
       (resolve-edn edn-data))))

#?(:clj
   (defn- load-resource-edn
     "Load a lang from a classpath resource EDN file."
     [resource-path]
     (let [edn-data (edn/read-string (slurp (io/resource resource-path)))]
       (resolve-edn edn-data))))

;; ---------------------------------------------------------------------------
;; Built-in langs (loaded from EDN resources)
;; ---------------------------------------------------------------------------

(def builtin
  #?(:clj (delay
            {:meme-classic (load-resource-edn "meme/lang/meme-classic.edn")
             :meme-rewrite (load-resource-edn "meme/lang/meme-rewrite.edn")
             :meme-trs     (load-resource-edn "meme/lang/meme-trs.edn")})
     ;; CLJS: no requiring-resolve or io/resource, so build directly from functions.
     ;; Only :format and :to-clj are portable; :run, :repl, :to-meme need JVM.
     :cljs {:meme-classic {:format meme.lang.meme-classic/format-meme
                           :to-clj meme.lang.meme-classic/to-clj}
            :meme-rewrite {:format meme.lang.meme-rewrite/format-meme
                           :to-clj meme.lang.meme-rewrite/to-clj}
            :meme-trs     {:format meme.lang.meme-trs/format-meme
                           :to-clj meme.lang.meme-trs/to-clj}}))

(def default-lang :meme-classic)

;; ---------------------------------------------------------------------------
;; User lang registration (JVM only)
;; ---------------------------------------------------------------------------

#?(:clj (defonce ^:private user-langs (atom {})))

#?(:clj
   (defn register!
     "Register a user lang at runtime. config is an EDN-style map — symbols
      are resolved via requiring-resolve, strings and keywords follow the same
      rules as load-edn. Pre-resolved functions are passed through."
     [lang-name config]
     (swap! user-langs assoc lang-name (resolve-edn config))))

#?(:clj
   (defn resolve-by-extension
     "Given a file path, find the lang whose :extension matches.
      Returns [lang-name lang-map] or nil. Checks user langs only
      (built-in langs don't have :extension)."
     [path]
     (some (fn [[n l]]
             (when-let [ext (:extension l)]
               (when (str/ends-with? path ext) [n l])))
           @user-langs)))

#?(:clj
   (defn registered-langs
     "List all registered user language names."
     []
     (keys @user-langs)))

#?(:clj
   (defn clear-user-langs!
     "Clear all registered user languages. For testing."
     []
     (reset! user-langs {})))

;; ---------------------------------------------------------------------------
;; Resolution
;; ---------------------------------------------------------------------------

(def ^:private legacy-names
  "Backward-compatible aliases from the pre-lang naming."
  {:classic :meme-classic
   :rewrite :meme-rewrite
   :ts-trs  :meme-trs})

(defn resolve-lang
  "Resolve a lang by keyword name. Returns the lang map.
   Accepts legacy names (:classic, :rewrite, :ts-trs).
   Checks user-registered langs first, then built-ins. Throws on unknown name."
  [lang-name]
  (let [n (or lang-name default-lang)
        n (get legacy-names n n)
        user #?(:clj @user-langs :cljs nil)
        b #?(:clj @builtin :cljs builtin)]
    (or (get user n)
        (get b n)
        (throw (ex-info (str "Unknown lang: " (pr-str n)
                             " — available: " (pr-str (concat (keys user) (keys b))))
                        {:lang n})))))

(defn supports?
  "Does the lang support the given command?"
  [lang command]
  (contains? lang command))

(defn check-support!
  "Assert that the lang supports the given command. Throws if not."
  [lang lang-name command]
  (when-not (supports? lang command)
    (throw (ex-info (str "Lang '" (if (keyword? lang-name) (name lang-name) lang-name)
                         "' does not support :"
                         (name command)
                         " — supported: " (pr-str (vec (filter keyword? (keys lang)))))
                    {:lang lang-name :command command}))))
