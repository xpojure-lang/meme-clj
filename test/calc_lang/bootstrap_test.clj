(ns calc-lang.bootstrap-test
  "Bootstrap shim: the cognitect test runner only discovers .clj/.cljc
   files. This shim installs the loader, loads the .meme test namespace,
   and runs its tests."
  (:require [clojure.test :refer [deftest is]]
            [meme.registry]))

(require 'calc-lang.stages-test)

(deftest calc-lang-tests
  (let [result (clojure.test/run-tests 'calc-lang.stages-test)]
    (is (zero? (:fail result)) "no calc-lang test failures")
    (is (zero? (:error result)) "no calc-lang test errors")))
