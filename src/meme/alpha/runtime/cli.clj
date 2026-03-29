(ns meme.alpha.runtime.cli
  "Shim — loads cli.meme at require time which defines all vars in this namespace.
   The real implementation lives in cli.meme (the first meme component in meme).
   Not AOT-compatible: top-level run-string executes at load time by design."
  (:require [meme.alpha.runtime.run :as meme-run]
            [clojure.java.io :as io]))

;; The .meme file's ns form re-opens this namespace and adds its own requires.
(let [r (io/resource "meme/alpha/runtime/cli.meme")]
  (when-not r
    (throw (ex-info "cli.meme not found on classpath — check that src/ is in :paths" {})))
  (let [src (slurp r)]
    (try
      (meme-run/run-string src)
      (catch Exception e
        (binding [*out* *err*]
          (println ((requiring-resolve 'meme.alpha.errors/format-error) e src)))
        (throw (ex-info "Failed to load cli.meme" {} e))))))
