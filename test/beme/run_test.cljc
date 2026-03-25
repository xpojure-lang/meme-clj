(ns beme.run-test
  "Tests for the beme.run module."
  (:require [clojure.test :refer [deftest is testing]]
            [beme.run :as run]))

;; ---------------------------------------------------------------------------
;; run-string
;; ---------------------------------------------------------------------------

(deftest run-string-single-form
  (testing "evaluates a single form and returns the result"
    #?(:clj (is (= 3 (run/run-string "+(1 2)"))))))

(deftest run-string-multiple-forms-returns-last
  (testing "evaluates all forms, returns the last result"
    #?(:clj (is (= 42 (run/run-string "+(1 2)\n42"))))))

(deftest run-string-empty
  (testing "empty string returns nil"
    #?(:clj (is (nil? (run/run-string ""))))))

(deftest run-string-parse-error
  (testing "parse error propagates"
    #?(:clj (is (thrown? Exception (run/run-string "foo("))))))

(deftest run-string-custom-eval-fn
  (testing "custom eval-fn is used instead of eval"
    (let [forms-seen (atom [])]
      (run/run-string "+(1 2)" (fn [form] (swap! forms-seen conj form) :ok))
      (is (= ['(+ 1 2)] @forms-seen)))))

(deftest run-string-custom-eval-fn-returns-last
  (testing "returns last eval-fn result"
    (is (= :second
           (run/run-string "foo()\nbar()" (fn [form] (if (= 'bar (first form)) :second :first)))))))

;; ---------------------------------------------------------------------------
;; run-file (JVM only — requires slurp)
;; ---------------------------------------------------------------------------

#?(:clj
(deftest run-file-basic
  (testing "runs a .beme file and returns last result"
    (let [tmp (java.io.File/createTempFile "beme-test" ".beme")]
      (try
        (spit tmp "def(x 42)\nx")
        (is (= 42 (run/run-file (str tmp))))
        (finally
          (.delete tmp)))))))

#?(:clj
(deftest run-file-not-found
  (testing "non-existent file throws"
    (is (thrown? Exception (run/run-file "/tmp/nonexistent-beme-file-12345.beme"))))))

;; ---------------------------------------------------------------------------
;; Shebang support
;; ---------------------------------------------------------------------------

(deftest run-string-shebang
  (testing "shebang line is stripped before parsing"
    #?(:clj (is (= 3 (run/run-string "#!/usr/bin/env bb beme-run\n+(1 2)")))))
  (testing "shebang-only file returns nil"
    #?(:clj (is (nil? (run/run-string "#!/usr/bin/env bb beme-run")))))
  (testing "no shebang — normal parsing"
    #?(:clj (is (= 42 (run/run-string "42"))))))
