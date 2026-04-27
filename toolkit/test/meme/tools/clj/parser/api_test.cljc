(ns meme.tools.clj.parser.api-test
  "Public API for the native Clojure parser. Smoke-tests each entry
   point in isolation; deeper coverage of grammar wiring is in
   `meme.tools.clj.parser.grammar-test`."
  (:require [clojure.test :refer [deftest testing is]]
            [meme.tools.clj.parser.api :as clj-parser]))

(deftest parse-string-produces-lossless-cst
  (testing "every top-level form yields a CST node"
    (let [cst (clj-parser/parse-string "(defn f [x] (* x x))")]
      (is (= 1 (count cst)))
      (is (= :list (:node (first cst))))))

  (testing "trailing trivia is preserved on the CST root metadata"
    (let [cst (clj-parser/parse-string "1\n;; tail comment\n")]
      (is (some? (:trivia/after (meta cst)))))))

(deftest clj->ast-returns-CljRoot
  (let [ast (clj-parser/clj->ast "[1 2 3]")]
    (is (instance? meme.tools.clj.ast.nodes.CljRoot ast))
    (is (instance? meme.tools.clj.ast.nodes.CljVector (first (:children ast))))))

(deftest clj->forms-evaluates-to-plain-data
  (testing "primitive forms"
    (is (= [42] (clj-parser/clj->forms "42")))
    (is (= ['(f x y)] (clj-parser/clj->forms "(f x y)")))
    (is (= [[1 2 3]] (clj-parser/clj->forms "[1 2 3]"))))

  (testing "shebang preamble is stripped before parsing"
    (let [src "#!/usr/bin/env clojure\n(println \"hi\")"
          forms (clj-parser/clj->forms src)]
      (is (= 1 (count forms)))
      (is (= 'println (first (first forms))))))

  (testing "BOM preamble is stripped"
    (let [src (str (char 0xFEFF) "(+ 1 2)")
          forms (clj-parser/clj->forms src)]
      (is (= ['(+ 1 2)] forms)))))

(deftest opts-grammar-override
  (testing "a caller can supply an alternate grammar"
    ;; We pass m1clj's grammar shape (loaded only on JVM in this test) to
    ;; verify the override is respected. Using clj's grammar by default
    ;; means `f(x)` is two forms; m1clj's makes it a call.
    #?(:clj
       (let [m1clj-grammar @(requiring-resolve 'm1clj-lang.grammar/grammar)
             ast (clj-parser/clj->ast "f(x)" {:grammar m1clj-grammar})
             forms (clj-parser/clj->forms "f(x)" {:grammar m1clj-grammar})]
         (is (= 1 (count (:children ast))))
         (is (= ['(f x)] forms)))
       :cljs (is true))))
