(ns meme.parse.reader.interop-test
  "Parser tests for Java interop syntax."
  (:require [clojure.test :refer [deftest is]]
            [meme.core :as core]))

(deftest parse-method-call
  (let [result (first (core/meme->forms ".toUpperCase(\"hello\")"))]
    (is (= '.toUpperCase (first result)))
    (is (= "hello" (second result)))))

(deftest parse-static-method
  (let [result (first (core/meme->forms "Math/abs(-1)"))]
    (is (= 'Math/abs (first result)))))

(deftest parse-field-access
  (let [result (first (core/meme->forms ".-x(point)"))]
    (is (= '.-x (first result)))
    (is (= 'point (second result)))))

(deftest parse-static-field
  (is (= 'Math/PI (first (core/meme->forms "Math/PI")))))

(deftest parse-constructor-call
  (let [result (first (core/meme->forms "java.util.Date.()"))]
    (is (= 'java.util.Date. (first result)))
    (is (= 1 (count result)))))

(deftest parse-constructor-with-args
  (let [result (first (core/meme->forms "StringBuilder.(\"init\")"))]
    (is (= 'StringBuilder. (first result)))
    (is (= "init" (second result)))))

(deftest parse-zero-arg-static-method
  (let [result (first (core/meme->forms "System/currentTimeMillis()"))]
    (is (= 'System/currentTimeMillis (first result)))
    (is (= 1 (count result)))))

(deftest parse-chained-interop
  (let [result (first (core/meme->forms ".toUpperCase(.trim(\" hello \"))"))]
    (is (= '.toUpperCase (first result)))
    (is (= '.trim (first (second result))))))

(deftest parse-interop-with-keyword-access
  (let [result (first (core/meme->forms ":name(.getData(obj))"))]
    (is (= :name (first result)))
    (is (seq? (second result)))
    (is (= '.getData (first (second result))))))

(deftest parse-multi-arg-method
  (let [result (first (core/meme->forms ".replace(\"hello\" \"l\" \"r\")"))]
    (is (= '.replace (first result)))
    (is (= 4 (count result)))))
