(ns meme-lang.run-test
  "Tests for the meme-lang.run module."
  (:require [clojure.test :refer [deftest is testing]]
            [meme-lang.api :as lang]
            [meme-lang.run :as run]))

;; ---------------------------------------------------------------------------
;; run-string
;; ---------------------------------------------------------------------------

(deftest run-string-single-form
  (testing "evaluates a single form and returns the result"
    (is (= 3 (run/run-string "+(1 2)")))))

(deftest run-string-multiple-forms-returns-last
  (testing "evaluates all forms, returns the last result"
    (is (= 42 (run/run-string "+(1 2)\n42")))))

(deftest run-string-empty
  (testing "empty string returns nil"
    (is (nil? (run/run-string "")))))

(deftest run-string-parse-error
  (testing "parse error propagates"
    (is (thrown? Exception (run/run-string "foo(")))))

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
;; run-file
;; ---------------------------------------------------------------------------

(deftest run-file-basic
  (testing "runs a .meme file and returns last result"
    (let [tmp (java.io.File/createTempFile "meme-test" ".meme")]
      (try
        (spit tmp "def(x 42)\nx")
        (is (= 42 (run/run-file (str tmp))))
        (finally
          (.delete tmp))))))

(deftest run-file-not-found
  (testing "non-existent file throws"
    (is (thrown? Exception (run/run-file "/tmp/nonexistent-meme-file-12345.meme")))))

;; ---------------------------------------------------------------------------
;; Shebang support
;; ---------------------------------------------------------------------------

(deftest run-string-shebang
  (testing "shebang line is stripped before parsing"
    (is (= 3 (run/run-string "#!/usr/bin/env bb meme-run\n+(1 2)"))))
  (testing "shebang-only file returns nil"
    (is (nil? (run/run-string "#!/usr/bin/env bb meme-run"))))
  (testing "no shebang — normal parsing"
    (is (= 42 (run/run-string "42")))))

(deftest run-string-auto-keyword-resolves-in-declared-ns
  (testing "::foo resolves in the file's declared namespace, not the caller's"
    (let [result (binding [*ns* *ns*]
                   (run/run-string "ns(my.meme.test.ns.kw)\n::foo"))]
      (is (= :my.meme.test.ns.kw/foo result))))
  (testing "::foo without ns declaration resolves in current namespace"
    (is (= (keyword (str (ns-name *ns*)) "bar")
           (run/run-string "::bar")))))

(deftest run-string-prelude-expands-syntax-quotes
  (testing "prelude with syntax-quote expands and evals correctly"
    (let [prelude-forms (lang/meme->forms "`map")
          result (run/run-string "42" {:prelude prelude-forms})]
      (is (= 42 result))))
  (testing "prelude with MemeRaw values (hex literals) expands correctly"
    (let [prelude-forms (lang/meme->forms "def(hex-val 0xFF)")
          result (run/run-string "hex-val" {:prelude prelude-forms})]
      (is (= 255 result)))))

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
      (is (= (name (ns-name *ns*)) (namespace result)))
      (is (= "nonexistent-sym" (name result)))))
  (testing "class resolves to full name"
    (is (= 'java.lang.String (run/run-string "`String"))))
  (testing "gensym still works with resolution"
    (let [result (run/run-string "`x#")]
      (is (re-find #"__auto__$" (name result))))))

;; Test moved to meme.registry-test — lang dispatch is no longer a
;; meme-lang.run concern (see run-file's :resolve-lang-for-path opt).

(deftest bom-stripping
  (testing "BOM prefix is stripped before parsing"
    (is (= 3 (run/run-string (str "\uFEFF" "+(1 2)")))))
  (testing "source without BOM still works"
    (is (= 3 (run/run-string "+(1 2)")))))
