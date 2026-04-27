(ns calc-lang.bootstrap-test
  "Bootstrap shim: the cognitect test runner only discovers .clj/.cljc
   files. This shim installs the loader, loads the .meme test namespace,
   and runs its tests."
  (:require [clojure.test :refer [deftest is]]
            [meme.registry]
            [meme.loader :as loader]
            ;; Registers :meme as a built-in lang so calc-lang.api (a .meme
            ;; file) can delegate `:run` to it.  Post-refactor: built-in
            ;; registration lives in each lang's own ns, not the registry.
            [meme-lang.api]))

(loader/install!)
(require 'calc-lang.stages-test)

(deftest calc-lang-tests
  (let [result (clojure.test/run-tests 'calc-lang.stages-test)]
    (is (zero? (:fail result)) "no calc-lang test failures")
    (is (zero? (:error result)) "no calc-lang test errors")))
