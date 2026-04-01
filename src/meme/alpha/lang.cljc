(ns meme.alpha.lang
  "Lang registry, resolution, and EDN loading.

   A lang is a map of command functions:
     :run     (fn [source opts] → result)   — run a file
     :repl    (fn [opts] → nil)             — interactive loop
     :format  (fn [source opts] → text)     — format a file
     :convert (fn [source opts] → text)     — convert a file (both directions)

   Every key is optional. A lang supports exactly the commands it has keys for.
   The CLI dispatches by looking up the command key in the lang map.

   All lang definitions — built-in and user-defined — are EDN:
     {:run     meme.alpha.runtime.run/run-string       ;; qualified symbol → fn
      :format  meme.alpha.lang.meme-classic/format-meme
      :convert meme.alpha.lang.meme-classic/convert}

   User langs can also use:
     {:run \"core.meme\"          ;; string → .meme file to eval before user file
      :format :meme-classic}      ;; keyword → inherit command from built-in lang

   Built-in langs (resources/meme/lang/):
     :meme-classic (default), :meme-rewrite, :meme-trs"
  (:require [clojure.edn :as edn]
            #?(:clj [clojure.java.io :as io])
            [meme.alpha.lang.meme-classic]
            [meme.alpha.lang.meme-rewrite]
            [meme.alpha.lang.meme-trs]))

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
      keyword  → look up that command from a built-in lang"
     [command value]
     (cond
       (symbol? value)
       (resolve-symbol value)

       (string? value)
       (let [run-string-fn (resolve-symbol 'meme.alpha.runtime.run/run-string)]
         (case command
           :run (fn [source opts]
                  (run-string-fn (slurp value) (dissoc opts :rewrite-rules :prelude))
                  (run-string-fn source opts))
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

       :else
       (throw (ex-info (str "Invalid lang value for :" (name command)
                            " — expected symbol, string, or keyword, got " (type value))
                       {:command command :value value})))))

#?(:clj
   (defn- resolve-edn
     "Resolve all values in an EDN map to functions."
     [edn-data]
     (into {} (map (fn [[k v]] [k (resolve-value k v)]) edn-data))))

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
     ;; Only :format and :convert are portable; :run and :repl need eval (JVM only).
     :cljs {:meme-classic {:format  meme.alpha.lang.meme-classic/format-meme
                           :convert meme.alpha.lang.meme-classic/convert}
            :meme-rewrite {:format  meme.alpha.lang.meme-rewrite/format-meme
                           :convert meme.alpha.lang.meme-rewrite/convert}
            :meme-trs     {:format  meme.alpha.lang.meme-trs/format-meme
                           :convert meme.alpha.lang.meme-trs/convert}}))

(def default-lang :meme-classic)

;; ---------------------------------------------------------------------------
;; Resolution
;; ---------------------------------------------------------------------------

(defn resolve-lang
  "Resolve a lang by keyword name. Returns the lang map.
   Throws on unknown name."
  [lang-name]
  (let [n (or lang-name default-lang)
        b #?(:clj @builtin :cljs builtin)]
    (or (get b n)
        (throw (ex-info (str "Unknown lang: " (pr-str n)
                             " — available: " (pr-str (keys b)))
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
