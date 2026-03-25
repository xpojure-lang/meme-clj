(ns beme.reader.interop-test
  "Parser tests for Java interop syntax."
  (:require [clojure.test :refer [deftest is]]
            [beme.reader :as r]))

(deftest parse-method-call
  (let [result (first (r/read-beme-string ".toUpperCase(\"hello\")"))]
    (is (= '.toUpperCase (first result)))
    (is (= "hello" (second result)))))

(deftest parse-static-method
  (let [result (first (r/read-beme-string "Math/abs(-1)"))]
    (is (= 'Math/abs (first result)))))

(deftest parse-field-access
  (let [result (first (r/read-beme-string ".-x(point)"))]
    (is (= '.-x (first result)))
    (is (= 'point (second result)))))

(deftest parse-static-field
  (is (= 'Math/PI (first (r/read-beme-string "Math/PI")))))

(deftest parse-constructor-call
  (let [result (first (r/read-beme-string "java.util.Date.()"))]
    (is (= 'java.util.Date. (first result)))
    (is (= 1 (count result)))))

(deftest parse-constructor-with-args
  (let [result (first (r/read-beme-string "StringBuilder.(\"init\")"))]
    (is (= 'StringBuilder. (first result)))
    (is (= "init" (second result)))))

(deftest parse-zero-arg-static-method
  (let [result (first (r/read-beme-string "System/currentTimeMillis()"))]
    (is (= 'System/currentTimeMillis (first result)))
    (is (= 1 (count result)))))

(deftest parse-chained-interop
  (let [result (first (r/read-beme-string ".toUpperCase(.trim(\" hello \"))"))]
    (is (= '.toUpperCase (first result)))
    (is (= '.trim (first (second result))))))

(deftest parse-interop-with-keyword-access
  (let [result (first (r/read-beme-string ":name(.getData(obj))"))]
    (is (= :name (first result)))
    (is (seq? (second result)))
    (is (= '.getData (first (second result))))))

(deftest parse-multi-arg-method
  (let [result (first (r/read-beme-string ".replace(\"hello\" \"l\" \"r\")"))]
    (is (= '.replace (first result)))
    (is (= 4 (count result)))))
