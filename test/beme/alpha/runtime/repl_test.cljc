(ns beme.alpha.runtime.repl-test
  "Tests for beme.alpha.runtime.repl input-state logic and read-input multi-line accumulation.
   input-state determines when the REPL has received complete, incomplete, or invalid input."
  (:require [clojure.test :refer [deftest is testing]]
            [beme.alpha.parse.reader :as reader]
            [beme.alpha.runtime.repl]))

;; Access private fns via var on JVM/Babashka.
#?(:clj (def ^:private input-state beme.alpha.runtime.repl/input-state))
#?(:clj (def ^:private read-input @#'beme.alpha.runtime.repl/read-input))

#?(:clj
(deftest input-state-complete
  (testing "simple balanced call"
    (is (= :complete (input-state "+(1 2)"))))
  (testing "nested calls balanced"
    (is (= :complete (input-state "map(inc filter(even? [1 2 3]))"))))
  (testing "vector balanced"
    (is (= :complete (input-state "[1 2 3]"))))
  (testing "nested vectors"
    (is (= :complete (input-state "[[1 2] [3 4]]"))))
  (testing "map balanced"
    (is (= :complete (input-state "{:a 1 :b 2}"))))
  (testing "set literal balanced"
    (is (= :complete (input-state "#{1 2 3}"))))
  (testing "def call balanced"
    (is (= :complete (input-state "def(x 42)"))))
  (testing "nested call balanced"
    (is (= :complete (input-state "defn(f [x] +(x 1))"))))
  (testing "empty input is complete"
    (is (= :complete (input-state ""))))
  (testing "simple symbol"
    (is (= :complete (input-state "foo"))))
  (testing "string with parens inside"
    (is (= :complete (input-state "\"hello (world)\""))))
  (testing "comment containing unbalanced paren"
    (is (= :complete (input-state "foo(1) ; unclosed ("))))
  (testing "#() is complete"
    (is (= :complete (input-state "#(inc(%))"))))
  (testing "#_ discard-only input is complete"
    (is (= :complete (input-state "#_foo"))))
  (testing "#_ #_ double discard is complete"
    (is (= :complete (input-state "#_ #_ a b"))))))

#?(:clj
(deftest input-state-incomplete
  (testing "unbalanced open paren"
    (is (= :incomplete (input-state "+(1 2"))))
  (testing "unbalanced open bracket"
    (is (= :incomplete (input-state "[1 2 3"))))
  (testing "unbalanced open brace"
    (is (= :incomplete (input-state "{:a 1"))))
  (testing "unclosed call"
    (is (= :incomplete (input-state "defn(f [x] +(x 1)"))))))

#?(:clj
(deftest input-state-invalid
  (testing "1/ is invalid, not incomplete — malformed number"
    (is (= :invalid (input-state "1/"))))
  (testing "unbalanced close paren is invalid"
    (is (= :invalid (input-state "+(1 2))"))))
  (testing ") alone is invalid"
    (is (= :invalid (input-state ")"))))))

#?(:clj
(deftest incomplete-errors-carry-ex-data-key
  (testing "EOF errors have :incomplete true in ex-data"
    (doseq [input ["+(1 2" "[1 2 3" "{:a 1" "\"unterminated"]]
      (let [e (try (reader/read-beme-string input)
                   nil
                   (catch Exception e e))]
        (is (some? e) (str "expected error for: " input))
        (is (true? (:incomplete (ex-data e)))
            (str "expected :incomplete in ex-data for: " input)))))
  (testing "non-EOF errors do NOT have :incomplete in ex-data"
    (doseq [input [")" "~x"]]
      (let [e (try (reader/read-beme-string input)
                   nil
                   (catch Exception e e))]
        (is (some? e) (str "expected error for: " input))
        (is (not (:incomplete (ex-data e)))
            (str "expected no :incomplete for: " input)))))))

;; ---------------------------------------------------------------------------
;; read-input — multi-line accumulation, EOF, and edge cases
;; ---------------------------------------------------------------------------

#?(:clj
(defn- mock-read-line
  "Returns a read-line-fn that yields successive strings from `lines`,
   then `nil` (EOF) for all subsequent calls."
  [lines]
  (let [remaining (atom lines)]
    (fn []
      (let [xs @remaining]
        (if (seq xs)
          (do (swap! remaining rest)
              (first xs))
          nil))))))

#?(:clj
(deftest read-input-single-line
  (testing "balanced single-line input returns immediately"
    (let [result (with-out-str
                   ;; capture prompt output; we want the return value
                   nil)
          input (binding [*out* (java.io.StringWriter.)]
                  (read-input "=> " (mock-read-line ["+(1 2)"])))]
      (is (= "+(1 2)" input))))))

#?(:clj
(deftest read-input-multi-line
  (testing "unbalanced input accumulates until balanced"
    (let [input (binding [*out* (java.io.StringWriter.)]
                  (read-input "=> " (mock-read-line ["defn(f [x]"
                                                     "  +(x 1))"])))]
      (is (= "defn(f [x]\n  +(x 1))" input))))))

#?(:clj
(deftest read-input-eof
  (testing "nil from read-line-fn on first call returns nil"
    (let [input (binding [*out* (java.io.StringWriter.)]
                  (read-input "=> " (mock-read-line [])))]
      (is (nil? input))))
  (testing "nil after partial unbalanced input returns nil"
    (let [input (binding [*out* (java.io.StringWriter.)]
                  (read-input "=> " (mock-read-line ["+(1 2"])))]
      (is (nil? input))))))

#?(:clj
(deftest read-input-discard-only
  (testing "discard-only input is complete and returns immediately"
    (let [input (binding [*out* (java.io.StringWriter.)]
                  (read-input "=> " (mock-read-line ["#_foo"])))]
      (is (= "#_foo" input))))))

#?(:clj
(deftest read-input-malformed-returns-immediately
  (testing "malformed input returns immediately, not infinite continuation — Bug 3"
    (let [input (binding [*out* (java.io.StringWriter.)]
                  (read-input "=> " (mock-read-line ["1/"])))]
      (is (= "1/" input))))
  (testing ") alone returns immediately"
    (let [input (binding [*out* (java.io.StringWriter.)]
                  (read-input "=> " (mock-read-line [")"])))]
      (is (= ")" input))))))

#?(:clj
(deftest read-input-blank-first-line
  (testing "blank first line returns empty string immediately, not continuation"
    (let [input (binding [*out* (java.io.StringWriter.)]
                  (read-input "=> " (mock-read-line [""])))]
      (is (= "" input))))))
