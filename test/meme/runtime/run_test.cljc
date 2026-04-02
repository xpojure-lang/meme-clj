(ns meme.runtime.run-test
  "Tests for the meme.runtime.run module."
  (:require [clojure.test :refer [deftest is testing]]
            [meme.core :as core]
            [meme.runtime.run :as run]))

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

(deftest run-string-opts-map-eval
  (testing "opts map with :eval is used"
    (let [forms-seen (atom [])]
      (run/run-string "+(1 2)" {:eval (fn [form] (swap! forms-seen conj form) :ok)})
      (is (= ['(+ 1 2)] @forms-seen))))
  (testing "opts map :eval returns last result"
    (is (= :second
           (run/run-string "foo()\nbar()" {:eval (fn [form] (if (= 'bar (first form)) :second :first))})))))

;; ---------------------------------------------------------------------------
;; run-file (JVM only — requires slurp)
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest run-file-basic
     (testing "runs a .meme file and returns last result"
       (let [tmp (java.io.File/createTempFile "meme-test" ".meme")]
         (try
           (spit tmp "def(x 42)\nx")
           (is (= 42 (run/run-file (str tmp))))
           (finally
             (.delete tmp)))))))

#?(:clj
   (deftest run-file-not-found
     (testing "non-existent file throws"
       (is (thrown? Exception (run/run-file "/tmp/nonexistent-meme-file-12345.meme"))))))

;; ---------------------------------------------------------------------------
;; Shebang support
;; ---------------------------------------------------------------------------

(deftest run-string-shebang
  (testing "shebang line is stripped before parsing"
    #?(:clj (is (= 3 (run/run-string "#!/usr/bin/env bb meme-run\n+(1 2)")))))
  (testing "shebang-only file returns nil"
    #?(:clj (is (nil? (run/run-string "#!/usr/bin/env bb meme-run")))))
  (testing "no shebang — normal parsing"
    #?(:clj (is (= 42 (run/run-string "42"))))))

;; ---------------------------------------------------------------------------
;; Bug: :: keywords resolved in caller's namespace, not the file's declared ns.
;; default-reader-opts provided #(clojure.core/read-string %) as default
;; :resolve-keyword, which eagerly resolved :: at read time — before the
;; file's ns form had been eval'd. A file with ns(my.ns) followed by ::foo
;; silently got :user/foo instead of :my.ns/foo.
;; Fix: no default :resolve-keyword — :: keywords use the deferred eval path.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest run-string-auto-keyword-resolves-in-declared-ns
     (testing "::foo resolves in the file's declared namespace, not the caller's"
       (let [result (binding [*ns* *ns*]
                      (run/run-string "ns(my.meme.test.ns.kw)\n::foo"))]
         (is (= :my.meme.test.ns.kw/foo result))))
     (testing "::foo without ns declaration resolves in current namespace"
       (is (= (keyword (str (ns-name *ns*)) "bar")
              (run/run-string "::bar"))))))

;; ---------------------------------------------------------------------------
;; Bug: prelude forms were eval'd without syntax-quote expansion.
;; run-string and REPL start eval'd (:prelude opts) directly, but prelude
;; forms from meme->forms contain MemeSyntaxQuote/MemeRaw AST nodes that
;; must be expanded before eval — matching the user-code path.
;; Fix: expand prelude through step-expand-syntax-quotes before eval.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest run-string-prelude-expands-syntax-quotes
     (testing "prelude with syntax-quote expands and evals correctly"
       (let [prelude-forms (core/meme->forms "`map")
             result (run/run-string "42" {:prelude prelude-forms})]
         (is (= 42 result)
             "prelude with syntax-quote should not crash")))
     (testing "prelude with MemeRaw values (hex literals) expands correctly"
       (let [prelude-forms (core/meme->forms "def(hex-val 0xFF)")
             result (run/run-string "hex-val" {:prelude prelude-forms})]
         (is (= 255 result)
             "prelude with hex literal should eval to correct value")))))

;; ---------------------------------------------------------------------------
;; Syntax-quote symbol resolution
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest run-string-syntax-quote-resolves-symbols
     (testing "`map resolves to clojure.core/map"
       (is (= 'clojure.core/map (run/run-string "`map"))))
     (testing "`if stays unqualified (special form)"
       (is (= 'if (run/run-string "`if"))))
     (testing "`do stays unqualified (special form)"
       (is (= 'do (run/run-string "`do"))))
     (testing "already-qualified symbol stays as-is"
       (is (= 'clojure.string/join (run/run-string "`clojure.string/join"))))
     (testing "interop .method stays unqualified"
       (is (= '.toString (run/run-string "`.toString"))))
     (testing "unresolved symbol gets current-ns qualification"
       (let [result (run/run-string "`nonexistent-sym")]
         (is (= (name (ns-name *ns*)) (namespace result))
             "unresolved symbol should be qualified with current ns")
         (is (= "nonexistent-sym" (name result)))))
     (testing "class resolves to full name"
       (is (= 'java.lang.String (run/run-string "`String"))))
     (testing "gensym still works with resolution"
       (let [result (run/run-string "`x#")]
         (is (re-find #"__auto__$" (name result))
             "gensym should still produce auto-gensym")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: unregistered guest language must error
;; ---------------------------------------------------------------------------
;; run-file with :lang pointing to an unregistered language silently ran
;; with default meme parser — no error, no warning.
;; Fix: validate that the lang is known when :lang is explicitly specified.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest run-file-unregistered-lang-throws
     (testing "explicit :lang with unregistered name throws"
       (is (thrown-with-msg? clojure.lang.ExceptionInfo
                             #"Unknown lang"
                             (run/run-file "/tmp/test.meme" {:lang :nonexistent}))))))

;; ---------------------------------------------------------------------------
;; RT2-M7: BOM marker (U+FEFF) at start of source was passed through to
;; tokenizer, corrupting the first token. Fix: step-strip-bom removes it.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest bom-stripping
     (testing "BOM prefix is stripped before parsing"
       (is (= 3 (run/run-string (str "\uFEFF" "+(1 2)")))))
     (testing "source without BOM still works"
       (is (= 3 (run/run-string "+(1 2)"))))))
