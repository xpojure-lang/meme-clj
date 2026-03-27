(ns beme.alpha.runtime.cli
  "Shim — loads cli.beme which defines all vars in this namespace.
   The real implementation lives in cli.beme (the first beme component in beme)."
  (:require [beme.alpha.runtime.run :as beme-run]
            [clojure.java.io :as io]))

;; The .beme file's ns form re-opens this namespace and adds its own requires.
(beme-run/run-string (slurp (io/resource "beme/alpha/runtime/cli.beme")))
