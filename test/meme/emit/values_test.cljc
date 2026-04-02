(ns meme.emit.values-test
  "Tests for shared value serialization."
  (:require [clojure.test :refer [deftest is testing]]
            [meme.emit.values :as values]))

;; ============================================================
;; emit-regex-str
;; ============================================================

(deftest emit-regex-str-simple
  (is (= "#\"abc\"" (values/emit-regex-str #"abc"))))

(deftest emit-regex-str-with-quotes
  (is (= "#\"a\\\"b\"" (values/emit-regex-str #"a\"b"))))

(deftest emit-regex-str-with-backslashes
  (is (= "#\"a\\db\"" (values/emit-regex-str #"a\db"))))

#?(:clj
   (deftest emit-regex-str-empty
     (is (= "#\"\"" (values/emit-regex-str #"")))))

;; ============================================================
;; emit-char-str (JVM only)
;; ============================================================

#?(:clj
   (deftest emit-char-str-named
     (testing "named characters"
       (is (= "\\newline" (values/emit-char-str \newline)))
       (is (= "\\tab" (values/emit-char-str \tab)))
       (is (= "\\space" (values/emit-char-str \space)))
       (is (= "\\return" (values/emit-char-str \return)))
       (is (= "\\backspace" (values/emit-char-str \backspace)))
       (is (= "\\formfeed" (values/emit-char-str \formfeed))))))

#?(:clj
   (deftest emit-char-str-regular
     (testing "regular characters"
       (is (= "\\a" (values/emit-char-str \a)))
       (is (= "\\Z" (values/emit-char-str \Z)))
       (is (= "\\0" (values/emit-char-str \0))))))

;; ============================================================
;; emit-number-str
;; ============================================================

(deftest emit-number-str-integers
  (is (= "42" (values/emit-number-str 42)))
  (is (= "0" (values/emit-number-str 0)))
  (is (= "-1" (values/emit-number-str -1))))

(deftest emit-number-str-floats
  (is (= "3.14" (values/emit-number-str 3.14)))
  (is (= "0.5" (values/emit-number-str 0.5))))

(deftest emit-number-str-symbolic
  (is (= "##NaN" (values/emit-number-str ##NaN)))
  (is (= "##Inf" (values/emit-number-str ##Inf)))
  (is (= "##-Inf" (values/emit-number-str ##-Inf))))

#?(:clj
   (deftest emit-number-str-bigdecimal
     (is (= "3.14M" (values/emit-number-str 3.14M)))))

#?(:clj
   (deftest emit-number-str-bigint
     (is (= "42N" (values/emit-number-str 42N)))))

;; ============================================================
;; emit-value-str
;; ============================================================

(deftest emit-value-str-nil
  (is (= "nil" (values/emit-value-str nil str))))

(deftest emit-value-str-booleans
  (is (= "true" (values/emit-value-str true str)))
  (is (= "false" (values/emit-value-str false str))))

(deftest emit-value-str-string
  (is (= "\"hello\"" (values/emit-value-str "hello" str)))
  (is (= "\"a\\\"b\"" (values/emit-value-str "a\"b" str))))

(deftest emit-value-str-number
  (is (= "42" (values/emit-value-str 42 str)))
  (is (= "##NaN" (values/emit-value-str ##NaN str))))

(deftest emit-value-str-regex
  (is (= "#\"abc\"" (values/emit-value-str #"abc" str))))

#?(:clj
   (deftest emit-value-str-char
     (is (= "\\newline" (values/emit-value-str \newline str)))
     (is (= "\\a" (values/emit-value-str \a str)))))

#?(:clj
   (deftest emit-value-str-tagged-literal
     (let [tl (tagged-literal 'inst "2024-01-01")]
       (is (= "#inst 2024-01-01" (values/emit-value-str tl str))))))

(deftest emit-value-str-unknown-returns-nil
  (testing "non-atomic values return nil"
    (is (nil? (values/emit-value-str [1 2 3] str)))
    (is (nil? (values/emit-value-str {:a 1} str)))
    (is (nil? (values/emit-value-str '(+ 1 2) str)))))
