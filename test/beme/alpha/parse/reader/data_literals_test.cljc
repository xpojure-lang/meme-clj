(ns beme.alpha.parse.reader.data-literals-test
  "Parser tests for data literal passthrough (vectors, maps, sets, keywords, numbers)."
  (:require [clojure.test :refer [deftest is]]
            [beme.alpha.core :as core]))

(deftest parse-vector-literal
  (is (= '[[1 2 3]] (core/beme->forms "[1 2 3]"))))

(deftest parse-map-literal
  (let [result (first (core/beme->forms "{:name \"Andriy\" :age 45}"))]
    (is (map? result))
    (is (= "Andriy" (:name result)))))

(deftest parse-set-literal
  (is (= #{1 2 3} (first (core/beme->forms "#{1 2 3}")))))

(deftest parse-nested-data
  (let [result (first (core/beme->forms "{:users [{:name \"a\"} {:name \"b\"}]}"))]
    (is (vector? (:users result)))))

(deftest parse-empty-collections
  (is (= [] (first (core/beme->forms "[]"))))
  (is (= {} (first (core/beme->forms "{}"))))
  (is (= #{} (first (core/beme->forms "#{}")))))

(deftest parse-negative-number
  (is (= [-1] (core/beme->forms "-1"))))

(deftest parse-negative-float
  (is (= [-3.14] (core/beme->forms "-3.14"))))

(deftest parse-auto-resolve-keyword
  #?(:clj
     (is (= (list 'clojure.core/read-string "::local")
            (first (core/beme->forms "::local"))))
     :cljs
     (is (thrown-with-msg? js/Error #"resolve-keyword"
           (core/beme->forms "::local")))))

(deftest parse-namespaced-keyword
  (is (= :foo/bar (first (core/beme->forms ":foo/bar")))))
