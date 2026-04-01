(ns meme.alpha.lang-test
  "End-to-end tests for lang command maps and EDN loading.
   Iterates over all known langs for exhaustive coverage."
  (:require [clojure.test :refer [deftest is testing]]
            [meme.alpha.lang :as lang]))

(def all-langs @lang/builtin)

;; ============================================================
;; Every built-in lang has the expected shape
;; ============================================================

(deftest all-langs-have-convert
  (doseq [[lang-name l] all-langs]
    (testing (str lang-name " has :convert")
      (is (fn? (:convert l))))))

(deftest all-langs-have-format
  (doseq [[lang-name l] all-langs]
    (testing (str lang-name " has :format")
      (is (fn? (:format l))))))

(deftest all-langs-have-run
  (doseq [[lang-name l] all-langs]
    (testing (str lang-name " has :run")
      (is (fn? (:run l))))))

;; ============================================================
;; Every lang's commands work end-to-end
;; ============================================================

(deftest all-langs-run
  (doseq [[lang-name l] all-langs
          :when (:run l)]
    (testing (str lang-name " :run")
      (is (= 42 ((:run l) "+(21 21)" {}))))))

(deftest all-langs-format
  (doseq [[lang-name l] all-langs
          :when (:format l)]
    (testing (str lang-name " :format")
      (is (= "def(x 42)" ((:format l) "def(x 42)" {}))))))

(deftest all-langs-convert-to-clj
  (doseq [[lang-name l] all-langs
          :when (:convert l)]
    (testing (str lang-name " :convert to-clj")
      (is (= "(f x y)" ((:convert l) "f(x y)" {:direction :to-clj}))))))

(deftest all-langs-convert-to-meme
  (doseq [[lang-name l] all-langs
          :when (:convert l)]
    (testing (str lang-name " :convert to-meme")
      (is (= "f(x y)" ((:convert l) "(f x y)" {:direction :to-meme}))))))

;; ============================================================
;; check-support!
;; ============================================================

(deftest check-support-passes-for-all
  (doseq [[lang-name l] all-langs
          cmd (filter keyword? (keys l))]
    (lang/check-support! l lang-name cmd)
    (is true)))

(deftest check-support-passes-for-repl
  (testing "meme-classic supports :repl"
    (is (lang/supports? (:meme-classic all-langs) :repl)))
  (testing "meme-rewrite supports :repl"
    (is (lang/supports? (:meme-rewrite all-langs) :repl))))

(deftest check-support-fails-for-missing
  (testing "meme-trs has no :repl"
    (is (thrown-with-msg? Exception #"does not support :repl"
          (lang/check-support! (:meme-trs all-langs) :meme-trs :repl)))))

;; ============================================================
;; All langs agree on basic convert output
;; ============================================================

(deftest all-langs-agree-on-convert
  (doseq [src ["f(x y)" "+(1 2)" "def(x 42)" "[1 2 3]"]]
    (let [results (into {} (map (fn [[n l]] [n ((:convert l) src {:direction :to-clj})]) all-langs))
          first-result (val (first results))]
      (doseq [[lang-name result] results]
        (is (= first-result result)
            (str lang-name " diverges on: " src))))))

;; ============================================================
;; EDN lang loading
;; ============================================================

(deftest load-edn-calc
  (testing "calc lang EDN loads and :run works"
    (let [l (lang/load-edn "examples/languages/calc/lang.edn")]
      (is (fn? (:run l)))
      (is (fn? (:format l)))
      (is (= 'x ((:run l) "simplify('+(*(1 x) 0))" {}))))))

(deftest load-edn-prefix
  (testing "prefix lang EDN loads and :run works"
    (let [l (lang/load-edn "examples/languages/prefix/lang.edn")]
      (is (fn? (:run l))))))

(deftest load-edn-format-delegates
  (testing ":format :meme-classic in EDN resolves to built-in format"
    (let [l (lang/load-edn "examples/languages/calc/lang.edn")]
      (is (= "def(x 42)" ((:format l) "def(x 42)" {}))))))

(deftest load-edn-run-evals-core-then-user
  (testing "EDN :run evals core.meme before user source"
    (spit "/tmp/test-edn-lang-core.meme" "defn(double [x] *(2 x))")
    (spit "/tmp/test-edn-lang.edn" "{:run \"/tmp/test-edn-lang-core.meme\"}")
    (let [l (lang/load-edn "/tmp/test-edn-lang.edn")]
      (is (= 84 ((:run l) "double(42)" {}))))))
