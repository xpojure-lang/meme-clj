(ns beme.alpha.runtime.cli
  "Shim — loads cli.beme at require time which defines all vars in this namespace.
   The real implementation lives in cli.beme (the first beme component in beme).
   Not AOT-compatible: top-level run-string executes at load time by design."
  (:require [beme.alpha.runtime.run :as beme-run]
            [clojure.java.io :as io]))

;; The .beme file's ns form re-opens this namespace and adds its own requires.
(let [r (io/resource "beme/alpha/runtime/cli.beme")]
  (when-not r
    (throw (ex-info "cli.beme not found on classpath — check that src/ is in :paths" {})))
  (let [src (slurp r)]
    (try
      (beme-run/run-string src)
      (catch Exception e
        (binding [*out* *err*]
          (println ((requiring-resolve 'beme.alpha.errors/format-error) e src)))
        (throw (ex-info "Failed to load cli.beme" {} e))))))
