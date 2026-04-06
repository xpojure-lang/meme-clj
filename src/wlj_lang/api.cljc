(ns wlj-lang.api
  (:require [clojure.string :as str]
            [wlj-lang.stages :as stages]
            #?(:clj [wlj-lang.run :as run])
            #?(:clj [wlj-lang.repl :as repl])))

(defn wlj->forms
  ([s] (wlj->forms s nil))
  ([s opts] (:forms (stages/run s opts))))

(defn wlj->clj
  ([s] (wlj->clj s nil))
  ([s opts] (str/join "\n" (map pr-str (wlj->forms s opts)))))

(defn to-clj
  ([source] (wlj->clj source))
  ([source _opts] (wlj->clj source)))

(def lang-map
  {:extension ".wlj"
   :to-clj to-clj
   #?@(:clj [:run  (fn [source opts] (run/run-string source opts))
             :repl (fn [opts] (repl/start opts))])})
