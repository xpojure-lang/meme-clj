(ns beme.reader.data-literals-test
  "Parser tests for data literal passthrough (vectors, maps, sets, keywords, numbers)."
  (:require [clojure.test :refer [deftest is]]
            [beme.reader :as r]))

(deftest parse-vector-literal
  (is (= '[[1 2 3]] (r/read-beme-string "[1 2 3]"))))

(deftest parse-map-literal
  (let [result (first (r/read-beme-string "{:name \"Andriy\" :age 45}"))]
    (is (map? result))
    (is (= "Andriy" (:name result)))))

(deftest parse-set-literal
  (is (= #{1 2 3} (first (r/read-beme-string "#{1 2 3}")))))

(deftest parse-nested-data
  (let [result (first (r/read-beme-string "{:users [{:name \"a\"} {:name \"b\"}]}"))]
    (is (vector? (:users result)))))

(deftest parse-empty-collections
  (is (= [] (first (r/read-beme-string "[]"))))
  (is (= {} (first (r/read-beme-string "{}"))))
  (is (= #{} (first (r/read-beme-string "#{}")))))

(deftest parse-negative-number
  (is (= [-1] (r/read-beme-string "-1"))))

(deftest parse-negative-float
  (is (= [-3.14] (r/read-beme-string "-3.14"))))

(deftest parse-auto-resolve-keyword
  #?(:clj
     (is (= (list 'clojure.core/read-string "::local")
            (first (r/read-beme-string "::local"))))
     :cljs
     (is (thrown-with-msg? js/Error #"resolve-keyword"
           (r/read-beme-string "::local")))))

(deftest parse-namespaced-keyword
  (is (= :foo/bar (first (r/read-beme-string ":foo/bar")))))
