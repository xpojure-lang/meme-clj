(ns meme.lang-test
  "End-to-end tests for lang command maps, EDN loading, and user
   lang registration (migrated from platform.registry-test)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [meme.lang :as lang]
            [meme.runtime.run :as run]))

(def all-langs @lang/builtin)

(defn- tmp-file
  "Create a uniquely-named temp file with the given extension. Auto-deleted on JVM exit."
  [prefix ext]
  (let [f (java.io.File/createTempFile prefix ext)]
    (.deleteOnExit f)
    (str f)))

(use-fixtures :each (fn [f] (lang/clear-user-langs!) (f) (lang/clear-user-langs!)))

;; ============================================================
;; Every built-in lang has the expected shape
;; ============================================================

(deftest all-langs-have-to-clj
  (doseq [[lang-name l] all-langs]
    (testing (str lang-name " has :to-clj")
      (is (fn? (:to-clj l))))))

(deftest all-langs-have-to-meme
  (doseq [[lang-name l] all-langs]
    (testing (str lang-name " has :to-meme")
      (is (fn? (:to-meme l))))))

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

(deftest all-langs-to-clj
  (doseq [[lang-name l] all-langs
          :when (:to-clj l)]
    (testing (str lang-name " :to-clj")
      (is (= "(f x y)" ((:to-clj l) "f(x y)"))))))

(deftest all-langs-to-meme
  (doseq [[lang-name l] all-langs
          :when (:to-meme l)]
    (testing (str lang-name " :to-meme")
      (is (= "f(x y)" ((:to-meme l) "(f x y)"))))))

;; ============================================================
;; check-support
;; ============================================================

(deftest check-support-passes-for-all
  (doseq [[lang-name l] all-langs
          cmd (filter keyword? (keys l))]
    (lang/check-support l lang-name cmd)))

(deftest check-support-passes-for-repl
  (testing "meme-classic supports :repl"
    (is (lang/supports? (:meme-classic all-langs) :repl)))
  (testing "meme-rewrite supports :repl"
    (is (lang/supports? (:meme-rewrite all-langs) :repl))))

(deftest check-support-fails-for-missing
  (testing "meme-trs has no :repl"
    (is (thrown-with-msg? Exception #"does not support :repl"
          (lang/check-support (:meme-trs all-langs) :meme-trs :repl)))))

;; ============================================================
;; All langs agree on basic to-clj output
;; ============================================================

(deftest all-langs-agree-on-to-clj
  (doseq [src ["f(x y)" "+(1 2)" "def(x 42)" "[1 2 3]"]]
    (let [results (into {} (map (fn [[n l]] [n ((:to-clj l) src)]) all-langs))
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
      (is (= ".calc" (:extension l)))
      (is (= 'x ((:run l) "simplify('+(*(1 x) 0))" {}))))))

(deftest load-edn-prefix
  (testing "prefix lang EDN loads and :run works"
    (let [l (lang/load-edn "examples/languages/prefix/lang.edn")]
      (is (fn? (:run l)))
      (is (= ".pfx" (:extension l))))))

(deftest load-edn-format-delegates
  (testing ":format :meme-classic in EDN resolves to built-in format"
    (let [l (lang/load-edn "examples/languages/calc/lang.edn")]
      (is (= "def(x 42)" ((:format l) "def(x 42)" {}))))))

(deftest load-edn-run-evals-core-then-user
  (testing "EDN :run evals core.meme before user source"
    (let [core-path (tmp-file "test-edn-core" ".meme")
          edn-path (tmp-file "test-edn-lang" ".edn")]
      (spit core-path "defn(double [x] *(2 x))")
      (spit edn-path (str "{:run \"" core-path "\"}"))
      (let [l (lang/load-edn edn-path)]
        (is (= 84 ((:run l) "double(42)" {})))))))

;; ============================================================
;; User lang registration (migrated from registry_test)
;; ============================================================

(deftest register-and-resolve-by-extension
  (testing "register a user lang and resolve from extension"
    (lang/register! :calc {:extension ".calc"
                           :run 'meme.runtime.run/run-string})
    (let [[name _lang] (lang/resolve-by-extension "app.calc")]
      (is (= :calc name)))
    (is (nil? (lang/resolve-by-extension "app.meme")))
    (is (nil? (lang/resolve-by-extension "app.clj"))))
  (testing "registered-langs returns names"
    (is (contains? (set (lang/registered-langs)) :calc)))
  (testing "resolve-lang finds user langs"
    (is (map? (lang/resolve-lang :calc)))))

(deftest register-with-prelude-file
  (testing "registered lang auto-loads prelude from extension via run-file"
    (lang/register! :calc {:extension ".calc"
                           :run "examples/languages/calc/core.meme"})
    (let [f (tmp-file "test-lang-dispatch" ".calc")]
      (spit f "simplify('+(*(1 x) 0))")
      (is (= 'x (run/run-file f))))))

(deftest register-with-pre-resolved-fn
  (testing "register! accepts pre-resolved functions"
    (lang/register! :mini {:extension ".mini"
                           :run (fn [source opts]
                                  (let [run-string @(resolve 'meme.runtime.run/run-string)]
                                    (run-string "defn(greet [n] str(\"Hi \" n))" opts)
                                    (run-string source opts)))})
    (let [f (tmp-file "test-mini" ".mini")]
      (spit f "greet(\"world\")")
      (is (= "Hi world" (run/run-file f))))))

(deftest register-with-custom-parser
  (testing "registered lang with rewrite-based parser"
    (lang/register! :rwm {:extension ".rwm"
                          :run 'meme.runtime.run/run-string
                          :parser 'meme.rewrite.tree/rewrite-parser})
    (let [f (tmp-file "test-rwm" ".rwm")]
      (spit f "+(21 21)")
      (is (= 42 (run/run-file f))))))

(deftest run-with-explicit-lang
  (testing ":lang opt overrides extension detection"
    (lang/register! :calc {:extension ".calc"
                           :run "examples/languages/calc/core.meme"})
    ;; Run a .meme file AS calc (explicit lang, mismatched extension)
    (let [f (tmp-file "test-explicit" ".meme")]
      (spit f "simplify('+(0 42))")
      (is (= 42 (run/run-file f {:lang :calc}))))))

(deftest clear-user-langs-works
  (testing "clear-user-langs! empties user registry"
    (lang/register! :test {:extension ".tst"})
    (is (seq (lang/registered-langs)))
    (lang/clear-user-langs!)
    (is (empty? (lang/registered-langs)))))

;; ---------------------------------------------------------------------------
;; RT2-M14: register! should reject built-in lang name overrides.
;; ---------------------------------------------------------------------------

(deftest register-builtin-override-rejected
  (testing ":meme-classic override is rejected"
    (is (thrown-with-msg? Exception #"(?i)cannot override"
                          (lang/register! :meme-classic {:to-clj identity}))))
  (testing ":meme-rewrite override is rejected"
    (is (thrown-with-msg? Exception #"(?i)cannot override"
                          (lang/register! :meme-rewrite {:to-clj identity}))))
  (testing ":meme-trs override is rejected"
    (is (thrown-with-msg? Exception #"(?i)cannot override"
                          (lang/register! :meme-trs {:to-clj identity}))))
  (testing "custom name still works"
    (lang/register! :my-custom-lang {:to-clj identity})
    (lang/clear-user-langs!)))

;; ---------------------------------------------------------------------------
;; RT2-H5: EDN :run path with .. traversal should be rejected.
;; ---------------------------------------------------------------------------

(deftest edn-path-traversal-rejected
  (testing ":run with .. is rejected in load-edn"
    (let [f (tmp-file "test-traversal" ".edn")]
      (spit f "{:run \"../../etc/passwd\"}")
      (is (thrown? Exception (lang/load-edn f)))))
  ;; PT-F7: :rules path traversal also rejected (via resolve-value)
  (testing ":rules with .. is rejected in load-edn"
    (let [f (tmp-file "test-traversal-rules" ".edn")]
      (spit f "{:to-clj meme.lang.meme-classic/to-clj :rules \"../../../etc/passwd\"}")
      (is (thrown-with-msg? Exception #"must not contain"
                            (lang/load-edn f))))))
