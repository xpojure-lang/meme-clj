(ns meme.tools.parse.reader.data-literals-test
  "Parser tests for data literal passthrough (vectors, maps, sets, keywords, numbers)."
  (:require [clojure.test :refer [deftest is]]
            [meme.langs.meme :as lang]
            [meme.tools.forms :as forms]))

(deftest parse-vector-literal
  (is (= '[[1 2 3]] (lang/meme->forms "[1 2 3]"))))

(deftest parse-map-literal
  (let [result (first (lang/meme->forms "{:name \"Andriy\" :age 45}"))]
    (is (map? result))
    (is (= "Andriy" (:name result)))))

(deftest parse-set-literal
  (is (= #{1 2 3} (first (lang/meme->forms "#{1 2 3}")))))

(deftest parse-nested-data
  (let [result (first (lang/meme->forms "{:users [{:name \"a\"} {:name \"b\"}]}"))]
    (is (vector? (:users result)))))

(deftest parse-empty-collections
  (is (= [] (first (lang/meme->forms "[]"))))
  (is (= {} (first (lang/meme->forms "{}"))))
  (is (= #{} (first (lang/meme->forms "#{}")))))

(deftest parse-negative-number
  (is (= [-1] (lang/meme->forms "-1"))))

(deftest parse-negative-float
  (is (= [-3.14] (lang/meme->forms "-3.14"))))

(deftest parse-auto-resolve-keyword
  (let [form (first (lang/meme->forms "::local"))]
    (is (forms/deferred-auto-keyword? form))
    (is (= "::local" (forms/deferred-auto-keyword-raw form)))))

(deftest parse-namespaced-keyword
  (is (= :foo/bar (first (lang/meme->forms ":foo/bar")))))

;; ---------------------------------------------------------------------------
;; Extended data literal coverage
;; ---------------------------------------------------------------------------

(deftest parse-nested-maps
  (let [result (first (lang/meme->forms "{:a {:b {:c 1}}}"))]
    (is (= 1 (get-in result [:a :b :c])))))

(deftest parse-numeric-keys-in-maps
  (let [result (first (lang/meme->forms "{1 \"one\" 2 \"two\"}"))]
    (is (map? result))
    (is (= "one" (get result 1)))))

(deftest parse-keyword-as-function
  (let [result (first (lang/meme->forms ":name({:name \"foo\"})"))]
    (is (= :name (first result)))
    (is (map? (second result)))))

(deftest parse-comma-as-whitespace
  (is (= '[[1 2 3]] (lang/meme->forms "[1, 2, 3]")))
  (let [result (first (lang/meme->forms "{:a 1, :b 2}"))]
    (is (= 1 (:a result)))
    (is (= 2 (:b result)))))

(deftest parse-deeply-nested-vectors
  (is (= [[[1]]] (first (lang/meme->forms "[[[1]]]")))))

(deftest parse-mixed-collection-nesting
  (let [result (first (lang/meme->forms "[{:a #{1 2}} {:b [3 4]}]"))]
    (is (vector? result))
    (is (= 2 (count result)))
    (is (set? (:a (first result))))
    (is (vector? (:b (second result))))))
