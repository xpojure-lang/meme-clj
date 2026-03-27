(ns beme.alpha.scan.source-test
  (:require [clojure.test :refer [deftest is testing]]
            [beme.alpha.scan.source :as source]))

(deftest line-col->offset-basics
  (testing "first character"
    (is (= 0 (source/line-col->offset "hello" 1 1))))
  (testing "within first line"
    (is (= 4 (source/line-col->offset "hello" 1 5))))
  (testing "start of second line"
    (is (= 4 (source/line-col->offset "abc\ndef" 2 1))))
  (testing "within second line"
    (is (= 6 (source/line-col->offset "abc\ndef" 2 3))))
  (testing "start of third line"
    (is (= 6 (source/line-col->offset "ab\ncd\nef" 3 1)))))

(deftest line-col->offset-edge-cases
  (testing "past end of source returns length"
    (is (= 3 (source/line-col->offset "abc" 1 10))))
  (testing "empty source"
    (is (= 0 (source/line-col->offset "" 1 1))))
  (testing "newline as last char — line 2 col 1 is past end"
    (is (= 4 (source/line-col->offset "abc\n" 2 1))))
  (testing "consecutive newlines (blank line)"
    (is (= 3 (source/line-col->offset "a\n\nb" 3 1)))))

(deftest line-col->offset-contract
  (testing "offset points to the expected character"
    (let [src "foo\nbar\nbaz"]
      (is (= \f (nth src (source/line-col->offset src 1 1))))
      (is (= \o (nth src (source/line-col->offset src 1 3))))
      (is (= \b (nth src (source/line-col->offset src 2 1))))
      (is (= \r (nth src (source/line-col->offset src 2 3))))
      (is (= \b (nth src (source/line-col->offset src 3 1))))
      (is (= \z (nth src (source/line-col->offset src 3 3)))))))
