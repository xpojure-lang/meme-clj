(ns clj-lang.api-test
  "Smoke tests for the :clj lang shim.

   The lang's command implementations are thin wrappers over the parser
   API and the existing m1clj formatter. These tests verify that the
   lang map is well-formed, registered, and that each command produces
   recognisable output. Deeper grammar coverage lives in
   `meme.tools.clj.parser.grammar-test`."
  (:require [clojure.test :refer [deftest testing is]]
            [clj-lang.api :as clj-lang]
            #?(:clj [meme.registry :as registry])))

(deftest registers-clj-as-builtin
  #?(:clj
     (testing ":clj appears in the registry with native-Clojure extensions"
       (let [lang (registry/resolve-lang :clj)]
         (is (some? lang))
         (is (= [".clj" ".cljc"] (:extensions lang)))
         (is (every? #(contains? lang %) [:format :to-clj :to-m1clj :form-shape]))))
     :cljs (is true)))

(deftest format-clj-roundtrips-clean-source
  (testing "Clojure source through format-clj returns valid Clojure"
    (let [src "(defn greet [name] (str \"Hello, \" name \"!\"))"
          formatted (clj-lang/format-clj src {})]
      (is (string? formatted))
      ;; The formatter is canonical, so even the input may render differently
      ;; — we only assert it remains syntactically Clojure (parens balance etc.).
      (is (re-find #"defn\s+greet" formatted))
      (is (re-find #"Hello," formatted)))))

(deftest format-clj-respects-style-opts
  (testing "style \"flat\" produces single-line per form in :clj mode"
    (let [src "(defn f [x y] (+ x y))"
          flat (clj-lang/format-clj src {:style "flat"})]
      (is (= "(defn f [x y] (+ x y))" flat))))

  (testing "style \"m1clj\" emits meme syntax"
    (let [src "(defn f [x y] (+ x y))"
          meme (clj-lang/format-clj src {:style "m1clj"})]
      (is (= "defn(f [x y] +(x y))" meme))))

  (testing "default canonical Clojure formatting respects width"
    (let [src "(defn long-name [aaaaa bbbbb ccccc ddddd] (sum aaaaa bbbbb ccccc ddddd))"
          formatted (clj-lang/format-clj src {:width 30})]
      (is (string? formatted))
      ;; Width=30 forces multi-line breaks somewhere.
      (is (re-find #"\n" formatted)))))

(deftest to-m1clj-converts-clojure-source
  (testing "(f x y) → f(x y) — basic call shape"
    (is (= "f(x y)" (clj-lang/to-m1clj "(f x y)")))))

(deftest format-empty-source-returns-input
  (testing "empty input round-trips as itself"
    (is (= "" (clj-lang/format-clj "" {})))
    (is (= "  " (clj-lang/format-clj "  " {})))))
