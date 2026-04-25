(ns meme.registry-test
  "End-to-end tests for lang command maps, EDN loading, and user
   lang registration."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [meme.registry :as registry]
            [meme.test-registry :as test-registry]
            ;; Explicit require triggers :mclj self-registration.
            [mclj-lang.api]
            [mclj-lang.run :as run]))

(def all-langs
  (into {} (map (fn [n] [n (registry/resolve-lang n)])
                [:mclj])))

(defn- tmp-file
  "Create a uniquely-named temp file with the given extension. Auto-deleted on JVM exit."
  [prefix ext]
  (let [f (java.io.File/createTempFile prefix ext)]
    (.deleteOnExit f)
    (str f)))

(use-fixtures :each (fn [f] (test-registry/clear-user-langs!) (f) (test-registry/clear-user-langs!)))

(defn- registry-resolver
  "Test-side glue matching what the CLI wires: :lang opt wins, extension falls
   through to the registry. Used to exercise run-file's injected dispatch hook."
  [path opts]
  (let [explicit (:lang opts)]
    (if explicit
      (:run (registry/resolve-lang explicit))
      (when-let [[_n l] (registry/resolve-by-extension path)]
        (:run l)))))

;; ============================================================
;; Every built-in lang has the expected shape
;; ============================================================

(deftest all-langs-have-to-clj
  (doseq [[lang-name l] all-langs]
    (testing (str lang-name " has :to-clj")
      (is (fn? (:to-clj l))))))

(deftest all-langs-have-to-mclj
  (doseq [[lang-name l] all-langs]
    (testing (str lang-name " has :to-mclj")
      (is (fn? (:to-mclj l))))))

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
      (is (= "def( x 42)" ((:format l) "def( x 42)" {}))))))

(deftest all-langs-to-clj
  (doseq [[lang-name l] all-langs
          :when (:to-clj l)]
    (testing (str lang-name " :to-clj")
      (is (= "(f x y)" ((:to-clj l) "f(x y)"))))))

(deftest all-langs-to-mclj
  (doseq [[lang-name l] all-langs
          :when (:to-mclj l)]
    (testing (str lang-name " :to-mclj")
      (is (= "f(x y)" ((:to-mclj l) "(f x y)"))))))

;; ============================================================
;; check-support
;; ============================================================

(deftest check-support-passes-for-all
  (doseq [[lang-name l] all-langs
          cmd (filter keyword? (keys l))]
    (registry/check-support l lang-name cmd)))

(deftest check-support-passes-for-repl
  (testing "meme supports :repl"
    (is (registry/supports? (:mclj all-langs) :repl))))

;; ============================================================
;; All langs agree on basic to-clj output
;; ============================================================

(deftest all-langs-agree-on-to-clj
  (doseq [src ["f(x y)" "+(1 2)" "def( x 42)" "[1 2 3]"]]
    (let [results (into {} (map (fn [[n l]] [n ((:to-clj l) src)]) all-langs))
          first-result (val (first results))]
      (doseq [[lang-name result] results]
        (is (= first-result result)
            (str lang-name " diverges on: " src))))))

;; ============================================================
;; EDN lang loading
;; ============================================================

(deftest load-edn-lang
  (testing "EDN lang file loads and :run resolves to a fn"
    (let [core-path (tmp-file "test-edn-core" ".mclj")
          edn-path  (tmp-file "test-edn-lang" ".edn")]
      (spit core-path "; empty prelude")
      (spit edn-path (str "{:extension \".myl\"\n"
                          " :run \"" core-path "\"\n"
                          " :format :mclj}"))
      (let [l (registry/load-edn edn-path)]
        (is (fn? (:run l)))
        (is (= [".myl"] (:extensions l)))))))

(deftest load-edn-format-delegates
  (testing ":format :mclj in EDN resolves to built-in format"
    (let [core-path (tmp-file "test-edn-core" ".mclj")
          edn-path  (tmp-file "test-edn-lang" ".edn")]
      (spit core-path "; empty prelude")
      (spit edn-path (str "{:extension \".myl\"\n"
                          " :run \"" core-path "\"\n"
                          " :format :mclj}"))
      (let [l (registry/load-edn edn-path)]
        (is (= "def( x 42)" ((:format l) "def( x 42)" {})))))))

(deftest load-edn-run-evals-core-then-user
  (testing "EDN :run evals core.mclj before user source"
    (let [core-path (tmp-file "test-edn-core" ".mclj")
          edn-path (tmp-file "test-edn-lang" ".edn")]
      (spit core-path "defn(double [x] *(2 x))")
      (spit edn-path (str "{:run \"" core-path "\"}"))
      (let [l (registry/load-edn edn-path)]
        (is (= 84 ((:run l) "double(42)" {})))))))

;; ============================================================
;; User lang registration (migrated from registry_test)
;; ============================================================

(deftest register-and-resolve-by-extension
  (testing "register a user lang and resolve from extension"
    (registry/register! :test-lang {:extension ".tstl"
                           :run 'mclj-lang.run/run-string})
    (let [[name _lang] (registry/resolve-by-extension "app.tstl")]
      (is (= :test-lang name)))
    (let [[meme-name _] (registry/resolve-by-extension "app.meme")]
      (is (= :mclj meme-name) "built-in mclj resolves by extension"))
    (is (nil? (registry/resolve-by-extension "app.clj"))))
  (testing "registered-langs returns names"
    (is (contains? (set (registry/registered-langs)) :test-lang)))
  (testing "resolve-lang finds user langs"
    (is (map? (registry/resolve-lang :test-lang)))))

(deftest register-with-prelude-file
  (testing "registered lang auto-loads prelude from extension via run-file"
    (let [core-path (tmp-file "test-prelude-core" ".mclj")]
      (spit core-path "defn(double [x] *(2 x))")
      (registry/register! :myl {:extension ".myl"
                                :run core-path})
      (let [f (tmp-file "test-lang-dispatch" ".myl")]
        (spit f "double(21)")
        (is (= 42 (run/run-file f {:resolve-lang-for-path registry-resolver})))))))

(deftest register-with-pre-resolved-fn
  (testing "register! accepts pre-resolved functions"
    (registry/register! :mini {:extension ".mini"
                           :run (fn [source opts]
                                  (let [run-string @(resolve 'mclj-lang.run/run-string)]
                                    (run-string "defn(greet [n] str(\"Hi \" n))" opts)
                                    (run-string source opts)))})
    (let [f (tmp-file "test-mini" ".mini")]
      (spit f "greet(\"world\")")
      (is (= "Hi world" (run/run-file f {:resolve-lang-for-path registry-resolver}))))))

(deftest run-with-explicit-lang
  (testing ":lang opt overrides extension detection"
    (let [core-path (tmp-file "test-explicit-core" ".mclj")]
      (spit core-path "; empty prelude")
      (registry/register! :myl {:extension ".myl" :run core-path})
      ;; Run a .mclj file AS :myl (explicit lang, mismatched extension)
      (let [f (tmp-file "test-explicit" ".mclj")]
        (spit f "+(21 21)")
        (is (= 42 (run/run-file f {:lang :myl
                                   :resolve-lang-for-path registry-resolver})))))))

(deftest run-with-unregistered-lang-throws
  (testing ":lang with an unregistered name throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown lang"
                          (run/run-file "/tmp/test.mclj"
                            {:lang :nonexistent
                             :resolve-lang-for-path registry-resolver})))))

(deftest clear-user-langs-works
  (testing "clear-user-langs! empties user registry"
    (registry/register! :test {:extension ".tst"})
    (is (seq (registry/registered-langs)))
    (test-registry/clear-user-langs!)
    (is (empty? (registry/registered-langs)))))

;; ---------------------------------------------------------------------------
;; RT2-M14: register! should reject built-in lang name overrides.
;; ---------------------------------------------------------------------------

(deftest register-builtin-override-rejected
  (testing ":mclj override is rejected"
    (is (thrown-with-msg? Exception #"(?i)cannot override"
                          (registry/register! :mclj {:to-clj identity}))))
  (testing "custom name still works"
    (registry/register! :my-custom-lang {:to-clj identity})
    (test-registry/clear-user-langs!)))

;; ---------------------------------------------------------------------------
;; RT2-H5: EDN :run path with .. traversal should be rejected.
;; ---------------------------------------------------------------------------

(deftest edn-path-traversal-rejected
  (testing ":run with .. is rejected in load-edn"
    (let [f (tmp-file "test-traversal" ".edn")]
      (spit f "{:run \"../../etc/passwd\"}")
      (is (thrown? Exception (registry/load-edn f))))))

;; ---------------------------------------------------------------------------
;; Multi-extension support
;; ---------------------------------------------------------------------------

(deftest multi-extension-registration
  (testing ":extensions vector — both extensions resolve"
    (registry/register! :multi {:extensions [".aa" ".bb"]
                                :run 'mclj-lang.run/run-string})
    (let [[n _] (registry/resolve-by-extension "app.aa")]
      (is (= :multi n)))
    (let [[n _] (registry/resolve-by-extension "app.bb")]
      (is (= :multi n)))
    (is (nil? (registry/resolve-by-extension "app.cc"))))

  (testing ":extension string normalizes to :extensions vector"
    (let [l (registry/resolve-lang :multi)]
      (is (vector? (:extensions l)))
      (is (= [".aa" ".bb"] (:extensions l)))
      (is (nil? (:extension l)) ":extension key removed after normalization")))

  (testing "mixed :extension + :extensions merged"
    (registry/register! :mixed {:extension ".xx"
                                :extensions [".yy" ".zz"]
                                :run 'mclj-lang.run/run-string})
    (is (= [".xx" ".yy" ".zz"] (:extensions (registry/resolve-lang :mixed))))
    (is (some? (registry/resolve-by-extension "app.xx")))
    (is (some? (registry/resolve-by-extension "app.zz")))))

(deftest builtin-mclj-extensions-resolve
  (testing ".mclj resolves to :mclj"
    (is (= :mclj (first (registry/resolve-by-extension "app.mclj")))))
  (testing ".mcljc resolves to :mclj"
    (is (= :mclj (first (registry/resolve-by-extension "app.mcljc")))))
  (testing ".mcljj resolves to :mclj"
    (is (= :mclj (first (registry/resolve-by-extension "app.mcljj")))))
  (testing ".mcljs resolves to :mclj"
    (is (= :mclj (first (registry/resolve-by-extension "app.mcljs")))))
  (testing "back-compat: deprecated .meme/.memec/.memej/.memejs still resolve to :mclj"
    (is (= :mclj (first (registry/resolve-by-extension "app.meme"))))
    (is (= :mclj (first (registry/resolve-by-extension "app.memec"))))
    (is (= :mclj (first (registry/resolve-by-extension "app.memej"))))
    (is (= :mclj (first (registry/resolve-by-extension "app.memejs")))))
  (testing "unknown extension returns nil"
    (is (nil? (registry/resolve-by-extension "app.txt")))))

;; ---------------------------------------------------------------------------
;; C2: Concurrent register! with conflicting extensions — atomic conflict check
;; ---------------------------------------------------------------------------

(deftest concurrent-register-conflict-detection
  (testing "concurrent registrations with same extension — at most one succeeds"
    (let [results (atom [])
          barrier (java.util.concurrent.CyclicBarrier. 2)]
      (dotimes [i 2]
        (.start (Thread. (fn []
                           (.await barrier)
                           (try
                             (registry/register! (keyword (str "conc" i))
                                                 {:extension ".conflict-test"
                                                  :run 'mclj-lang.run/run-string})
                             (swap! results conj [:ok i])
                             (catch Exception _
                               (swap! results conj [:error i])))))))
      (Thread/sleep 500)
      (let [ok-count (count (filter #(= :ok (first %)) @results))
            error-count (count (filter #(= :error (first %)) @results))]
        (is (<= ok-count 1) "at most one registration should succeed")
        (is (= 2 (+ ok-count error-count)) "both threads should complete")))))

(deftest multi-extension-conflict-detection
  (testing "conflict when new extension overlaps existing extensions vector"
    (registry/register! :owner {:extensions [".p" ".q"]
                                :run 'mclj-lang.run/run-string})
    (is (thrown-with-msg? Exception #"already claimed"
                          (registry/register! :thief {:extension ".q"
                                                      :run 'mclj-lang.run/run-string})))))

;; ---------------------------------------------------------------------------
;; Scar tissue: register! used to perform validation inside swap!. Under CAS
;; retries, that meant conflict checks could re-run redundantly. The observable
;; guarantee is that after a failing validation, the registry state is exactly
;; its pre-call state (no partial write, no stray entry for the failing name).
;; ---------------------------------------------------------------------------

(deftest register-failure-leaves-registry-unchanged
  (testing "builtin override throws and does not insert the new name"
    (is (thrown? Exception (registry/register! :mclj {:to-clj identity})))
    ;; Mclj's builtin entry is still there, unaltered — :mclj's lang-map has
    ;; :to-clj from the real api, not our identity function.
    (is (not= identity (:to-clj (registry/resolve-lang :mclj)))))
  (testing "extension conflict throws and does not insert the new name"
    (registry/register! :holder {:extension ".reg-scar"
                                 :run 'mclj-lang.run/run-string})
    (is (thrown? Exception (registry/register! :intruder {:extension ".reg-scar"
                                                          :run 'mclj-lang.run/run-string})))
    (is (not (contains? (registry/available-langs) :intruder))
        "failing registration must not leak a partial entry"))
  (testing "reserved .mclj extension throws and does not insert the new name"
    (is (thrown? Exception (registry/register! :sneaky {:extension ".mclj"
                                                        :run 'mclj-lang.run/run-string})))
    (is (thrown? Exception (registry/register! :sneakier {:extension ".meme"
                                                          :run 'mclj-lang.run/run-string})))
    (is (not (contains? (registry/available-langs) :sneaky))
        "reserved-extension rejection must not leak a partial entry")))
