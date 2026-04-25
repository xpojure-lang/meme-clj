(ns mclj-lang.api-test
  "Tests for the mclj-lang.api language API."
  (:require [clojure.test :refer [deftest is testing]]
            [mclj-lang.api :as lang]
            #?(:clj [mclj-lang.repl :as repl])
            #?(:clj [mclj-lang.run :as run])))

;; ---------------------------------------------------------------------------
;; Text-to-form track
;; ---------------------------------------------------------------------------

(deftest meme->forms-test
  (testing "single form"
    (is (= '[(+ 1 2)] (lang/meme->forms "+(1 2)"))))
  (testing "multiple forms"
    (is (= '[(def x 42) (println x)]
           (lang/meme->forms "def(x 42)\nprintln(x)"))))
  (testing "empty string"
    (is (= [] (lang/meme->forms "")))))

(deftest forms->meme-test
  (testing "single form"
    (is (= "+(1 2)" (lang/forms->meme ['(+ 1 2)]))))
  (testing "multiple forms separated by blank line"
    (is (= "def(x 42)\n\nprintln(x)"
           (lang/forms->meme ['(def x 42) '(println x)])))))

;; ---------------------------------------------------------------------------
;; Form-to-text track
;; ---------------------------------------------------------------------------

(deftest forms->clj-test
  (testing "single form"
    (is (= "(+ 1 2)" (lang/forms->clj ['(+ 1 2)]))))
  (testing "multiple forms"
    (is (= "(def x 42)\n\n(println x)"
           (lang/forms->clj ['(def x 42) '(println x)])))))

#?(:clj
   (deftest clj->forms-test
     (testing "single form"
       (is (= '[(defn f [x] (+ x 1))]
              (lang/clj->forms "(defn f [x] (+ x 1))"))))
     (testing "multiple forms"
       (is (= '[(def x 42) (println x)]
              (lang/clj->forms "(def x 42)\n(println x)"))))))

;; ---------------------------------------------------------------------------
;; Text-to-text track
;; ---------------------------------------------------------------------------

(deftest meme->clj-test
  (testing "basic conversion"
    (is (= "(+ 1 2)" (lang/meme->clj "+(1 2)"))))
  (testing "multiple forms"
    (is (= "(def x 42)\n\n(println x)"
           (lang/meme->clj "def(x 42)\nprintln(x)")))))

#?(:clj
   (deftest clj->meme-test
     (testing "basic conversion"
       (is (= "+(1 2)" (lang/clj->meme "(+ 1 2)"))))))

;; ---------------------------------------------------------------------------
;; Eval tests
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest run-string-basic
     (testing "evaluates a single form"
       (is (= 3 (run/run-string "+(1 2)"))))
     (testing "evaluates multiple forms, returns last"
       (is (= 42 (run/run-string "+(1 2)\n42"))))
     (testing "empty string returns nil"
       (is (nil? (run/run-string ""))))))

#?(:clj
   (deftest run-string-shebang
     (testing "shebang line is stripped before parsing"
       (is (= 3 (run/run-string "#!/usr/bin/env bb meme-run\n+(1 2)"))))))

#?(:clj
   (deftest run-string-bom
     (testing "BOM prefix is stripped before parsing"
       (is (= 3 (run/run-string (str "\uFEFF" "+(1 2)")))))))

#?(:clj
   (deftest run-string-syntax-quote
     (testing "`map resolves to clojure.core/map"
       (is (= 'clojure.core/map (run/run-string "`map"))))
     (testing "`if stays unqualified (special form)"
       (is (= 'if (run/run-string "`if"))))))

#?(:clj
   (deftest run-string-auto-keyword
     (testing "::foo resolves in the file's declared namespace"
       (let [result (binding [*ns* *ns*]
                      (run/run-string "ns(my.meme.test.exp.ns)\n::foo"))]
         (is (= :my.meme.test.exp.ns/foo result))))))

#?(:clj
   (deftest run-string-meme-raw
     (testing "hex literal evaluates to correct value"
       (is (= 255 (run/run-string "0xFF"))))
     (testing "scientific notation evaluates correctly"
       (is (= 100.0 (run/run-string "1e2"))))))

#?(:clj
   (deftest run-string-prelude
     (testing "prelude with syntax-quote expands and evals correctly"
       (let [prelude-forms (lang/meme->forms "`map")
             result (run/run-string "42" {:prelude prelude-forms})]
         (is (= 42 result))))
     (testing "prelude with hex literal expands correctly"
       (let [prelude-forms (lang/meme->forms "def(hex-val 0xFF)")
             result (run/run-string "hex-val" {:prelude prelude-forms})]
         (is (= 255 result))))))

;; ---------------------------------------------------------------------------
;; REPL input-state
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest input-state-test
     (doseq [[input expected] [["+(1 2)" :complete]
                                ["+(1 2" :incomplete]
                                ["[1 2 3" :incomplete]
                                ["{:a 1" :incomplete]
                                [")" :invalid]
                                ["1/" :invalid]
                                ["42" :complete]
                                ["" :complete]]]
       (testing (str "input-state for: " (pr-str input) " → " expected)
         (is (= expected (repl/input-state input)))))))

;; ---------------------------------------------------------------------------
;; Format
;; ---------------------------------------------------------------------------

(deftest format-meme-test
  (testing "basic formatting"
    (is (= "+(1 2)" (lang/format-meme "+(1 2)" {}))))
  (testing "empty source returns source"
    (is (= "" (lang/format-meme "" {})))))

;; ---------------------------------------------------------------------------
;; C5: clj->forms deep nesting guard
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest clj->forms-deep-nesting-guard
     (testing "deeply nested Clojure input produces clean error, not StackOverflowError"
       (let [depth 10000
             input (str (apply str (repeat depth "(")) "x" (apply str (repeat depth ")")))]
         (is (thrown-with-msg? Exception #"nesting depth"
                               (lang/clj->forms input)))))))
