(ns meme.alpha.platform.registry
  "Guest language registry. Maps language names to configurations.
   A guest language is: a name, a file extension, an optional prelude,
   and optional rewrite rules.

   (register! :pattern {:extension \".pat\"
                        :prelude-file \"langs/pattern/core.meme\"
                        :rules-file  \"langs/pattern/rules.meme\"})

   (resolve-lang \"app.pat\")  → :pattern
   (lang-config :pattern)     → {:extension ... :prelude ... :rules ...}"
  (:require [clojure.string :as str]))

(defonce ^:private registry (atom {}))

(defn register!
  "Register a guest language.
   config keys:
     :extension    — file extension (e.g. \".pat\")
     :prelude-file — path to prelude .meme file (eval'd before user code)
     :rules-file   — path to rules .meme file (eval'd, must return rule vector)
     :prelude      — prelude forms (alternative to :prelude-file)
     :rules        — rule vector (alternative to :rules-file)
     :parser       — custom parser fn: (fn [tokens opts source] forms-vector)
                     If nil, uses the default meme parser.
                     The parser receives meme's token vector and should
                     return a vector of Clojure forms."
  [lang-name config]
  (swap! registry assoc lang-name config))

(defn lang-config
  "Get the config for a registered language. Returns nil if not found."
  [lang-name]
  (get @registry lang-name))

(defn resolve-lang
  "Given a file path, determine the guest language from its extension.
   Returns the language name keyword, or nil for unrecognized extensions.
   .meme files return nil (default meme, no guest language)."
  [path]
  (some (fn [[lang-name {:keys [extension]}]]
          (when (and extension
                     (str/ends-with? path extension))
            lang-name))
        @registry))

(defn registered-langs
  "List all registered language names."
  []
  (keys @registry))

(defn clear!
  "Clear all registered languages. For testing."
  []
  (reset! registry {}))
