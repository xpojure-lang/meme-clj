(ns meme.alpha.parse.reader.data-literals-test
  "Parser tests for data literal passthrough (vectors, maps, sets, keywords, numbers)."
  (:require [clojure.test :refer [deftest is]]
            [meme.alpha.core :as core]))

(deftest parse-vector-literal
  (is (= '[[1 2 3]] (core/meme->forms "[1 2 3]"))))

(deftest parse-map-literal
  (let [result (first (core/meme->forms "{:name \"Andriy\" :age 45}"))]
    (is (map? result))
    (is (= "Andriy" (:name result)))))

(deftest parse-set-literal
  (is (= #{1 2 3} (first (core/meme->forms "#{1 2 3}")))))

(deftest parse-nested-data
  (let [result (first (core/meme->forms "{:users [{:name \"a\"} {:name \"b\"}]}"))]
    (is (vector? (:users result)))))

(deftest parse-empty-collections
  (is (= [] (first (core/meme->forms "[]"))))
  (is (= {} (first (core/meme->forms "{}"))))
  (is (= #{} (first (core/meme->forms "#{}")))))

(deftest parse-negative-number
  (is (= [-1] (core/meme->forms "-1"))))

(deftest parse-negative-float
  (is (= [-3.14] (core/meme->forms "-3.14"))))

(deftest parse-auto-resolve-keyword
  #?(:clj
     (is (= (list 'clojure.core/read-string "::local")
            (first (core/meme->forms "::local"))))
     :cljs
     (is (thrown-with-msg? js/Error #"resolve-keyword"
           (core/meme->forms "::local")))))

(deftest parse-namespaced-keyword
  (is (= :foo/bar (first (core/meme->forms ":foo/bar")))))
