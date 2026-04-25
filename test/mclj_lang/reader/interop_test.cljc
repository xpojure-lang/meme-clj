(ns mclj-lang.reader.interop-test
  "Parser tests for Java interop syntax."
  (:require [clojure.test :refer [deftest is]]
            [mclj-lang.api :as lang]))

(deftest parse-method-call
  (let [result (first (lang/mclj->forms ".toUpperCase(\"hello\")"))]
    (is (= '.toUpperCase (first result)))
    (is (= "hello" (second result)))))

(deftest parse-static-method
  (let [result (first (lang/mclj->forms "Math/abs(-1)"))]
    (is (= 'Math/abs (first result)))))

(deftest parse-field-access
  (let [result (first (lang/mclj->forms ".-x(point)"))]
    (is (= '.-x (first result)))
    (is (= 'point (second result)))))

(deftest parse-static-field
  (is (= 'Math/PI (first (lang/mclj->forms "Math/PI")))))

(deftest parse-constructor-call
  (let [result (first (lang/mclj->forms "java.util.Date.()"))]
    (is (= 'java.util.Date. (first result)))
    (is (= 1 (count result)))))

(deftest parse-constructor-with-args
  (let [result (first (lang/mclj->forms "StringBuilder.(\"init\")"))]
    (is (= 'StringBuilder. (first result)))
    (is (= "init" (second result)))))

(deftest parse-zero-arg-static-method
  (let [result (first (lang/mclj->forms "System/currentTimeMillis()"))]
    (is (= 'System/currentTimeMillis (first result)))
    (is (= 1 (count result)))))

(deftest parse-chained-interop
  (let [result (first (lang/mclj->forms ".toUpperCase(.trim(\" hello \"))"))]
    (is (= '.toUpperCase (first result)))
    (is (= '.trim (first (second result))))))

(deftest parse-interop-with-keyword-access
  (let [result (first (lang/mclj->forms ":name(.getData(obj))"))]
    (is (= :name (first result)))
    (is (seq? (second result)))
    (is (= '.getData (first (second result))))))

(deftest parse-multi-arg-method
  (let [result (first (lang/mclj->forms ".replace(\"hello\" \"l\" \"r\")"))]
    (is (= '.replace (first result)))
    (is (= 4 (count result)))))

;; ---------------------------------------------------------------------------
;; Extended interop coverage
;; ---------------------------------------------------------------------------

(deftest parse-double-dot-interop
  (let [result (first (lang/mclj->forms "..(obj method1() method2())"))]
    (is (= '.. (first result)))))

(deftest parse-doto-interop
  (let [result (first (lang/mclj->forms "doto(StringBuilder.() .append(\"a\") .append(\"b\"))"))]
    (is (= 'doto (first result)))
    (is (= 4 (count result)))))

(deftest parse-method-on-expression
  (let [result (first (lang/mclj->forms ".trim(.concat(\"a\" \"b\"))"))]
    (is (= '.trim (first result)))
    (is (seq? (second result)))
    (is (= '.concat (first (second result))))))

(deftest parse-static-field-standalone
  (is (= 'Integer/MAX_VALUE (first (lang/mclj->forms "Integer/MAX_VALUE")))))

(deftest parse-constructor-in-binding
  (let [result (first (lang/mclj->forms "let([sb StringBuilder.()] sb)"))]
    (is (= 'let (first result)))
    (let [bindings (second result)]
      (is (vector? bindings))
      (is (seq? (second bindings)))
      (is (= 'StringBuilder. (first (second bindings)))))))
