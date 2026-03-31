(ns meme.alpha.platform.registry-test
  "Tests for guest language registry and language dispatch."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [meme.alpha.platform.registry :as reg]
            [meme.alpha.runtime.run :as run]))

(use-fixtures :each (fn [f] (reg/clear!) (f) (reg/clear!)))

;; ============================================================
;; Registry
;; ============================================================

(deftest register-and-resolve
  (testing "register a language and resolve from extension"
    (reg/register! :calc {:extension ".calc"})
    (is (= :calc (reg/resolve-lang "app.calc")))
    (is (nil? (reg/resolve-lang "app.meme")))
    (is (nil? (reg/resolve-lang "app.clj"))))
  (testing "lang-config returns config"
    (reg/register! :test {:extension ".tst" :prelude ['(def x 1)]})
    (is (= ".tst" (:extension (reg/lang-config :test))))
    (is (= ['(def x 1)] (:prelude (reg/lang-config :test)))))
  (testing "unregistered language returns nil"
    (is (nil? (reg/lang-config :nonexistent)))))

;; ============================================================
;; Language dispatch: prelude
;; ============================================================

(deftest run-with-prelude-lang
  (testing "registered language auto-loads prelude from extension"
    (reg/register! :calc {:extension ".calc"
                          :prelude-file "examples/languages/calc/core.meme"})
    (spit "/tmp/test-lang-dispatch.calc" "simplify('+(*(1 x) 0))")
    (is (= 'x (run/run-file "/tmp/test-lang-dispatch.calc")))))

(deftest run-with-inline-prelude
  (testing "registered language with inline prelude forms"
    (reg/register! :mini {:extension ".mini"
                          :prelude ['(defn greet [n] (str "Hi " n))]})
    (spit "/tmp/test-mini.mini" "greet(\"world\")")
    (is (= "Hi world" (run/run-file "/tmp/test-mini.mini")))))

;; ============================================================
;; Language dispatch: custom parser
;; ============================================================

(deftest run-with-custom-parser
  (testing "registered language with rewrite-based parser"
    (require 'meme.alpha.rewrite.tree)
    (let [rw-parser (resolve 'meme.alpha.rewrite.tree/rewrite-parser)]
      (reg/register! :rwm {:extension ".rwm"
                           :parser rw-parser})
      (spit "/tmp/test-rwm.rwm" "+(21 21)")
      (is (= 42 (run/run-file "/tmp/test-rwm.rwm"))))))

;; ============================================================
;; Explicit --lang flag
;; ============================================================

(deftest run-with-explicit-lang
  (testing ":lang opt overrides extension detection"
    (reg/register! :calc {:extension ".calc"
                          :prelude-file "examples/languages/calc/core.meme"})
    ;; Run a .meme file AS calc (explicit lang, mismatched extension)
    (spit "/tmp/test-explicit.meme" "simplify('+(0 42))")
    (is (= 42 (run/run-file "/tmp/test-explicit.meme" {:lang :calc})))))
