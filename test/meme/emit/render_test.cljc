(ns meme.emit.render-test
  "Isolation tests for the Wadler-Lindig Doc algebra and layout engine."
  (:require [clojure.test :refer [deftest is testing]]
            [meme.emit.render :as r]))

;; ============================================================
;; Smart constructors
;; ============================================================

(deftest text-returns-nil-for-empty
  (is (nil? (r/text nil)))
  (is (nil? (r/text "")))
  (is (some? (r/text "a"))))

(deftest nest-returns-nil-for-nil-doc
  (is (nil? (r/nest 2 nil)))
  (is (some? (r/nest 2 (r/text "a")))))

(deftest group-returns-nil-for-nil-doc
  (is (nil? (r/group nil)))
  (is (some? (r/group (r/text "a")))))

(deftest cat-filters-nils
  (testing "nil inputs"
    (is (nil? (r/cat)))
    (is (nil? (r/cat nil)))
    (is (nil? (r/cat nil nil))))
  (testing "one non-nil"
    (is (= (r/text "a") (r/cat nil (r/text "a")))))
  (testing "mixed"
    (let [doc (r/cat nil (r/text "a") nil (r/text "b"))]
      (is (= "ab" (r/layout doc 80))))))

;; ============================================================
;; Layout: group flat vs break
;; ============================================================

(deftest group-flat-when-fits
  (testing "group renders flat when it fits"
    (let [doc (r/group (r/cat (r/text "a") r/line (r/text "b")))]
      (is (= "a b" (r/layout doc 80))))))

(deftest group-breaks-when-exceeds-width
  (testing "group breaks to multi-line when too wide"
    (let [doc (r/group (r/cat (r/text "aaa") r/line (r/text "bbb")))]
      (is (= "aaa\nbbb" (r/layout doc 5))))))

;; ============================================================
;; Nesting and indentation
;; ============================================================

(deftest nest-indents-after-break
  (testing "DocNest increases indent on new lines"
    (let [doc (r/group
                (r/cat (r/text "head")
                       (r/nest 2 (r/cat r/line (r/text "body")))))]
      ;; When broken: head\n  body (2 spaces indent)
      (is (= "head\n  body" (r/layout doc 5))))))

;; ============================================================
;; Hardline
;; ============================================================

(deftest hardline-always-breaks
  (testing "hardline forces break mode even in a flat group"
    (let [doc (r/group (r/cat (r/text "a") r/hardline (r/text "b")))]
      (is (= "a\nb" (r/layout doc 80))))))

;; ============================================================
;; DocIfBreak
;; ============================================================

(deftest if-break-conditional
  (testing "renders flat-doc when group is flat"
    (let [doc (r/group
                (r/cat (r/text "a")
                       (r/if-break (r/text "BREAK") (r/text ","))
                       (r/text "b")))]
      (is (= "a,b" (r/layout doc 80)))))
  (testing "renders break-doc when group is broken"
    (let [doc (r/group
                (r/cat (r/text "aaa")
                       (r/if-break (r/cat r/line (r/text "BREAK")) (r/text " "))
                       (r/text "bbb")))]
      ;; Force break with narrow width — break-doc (line + "BREAK") replaces flat " "
      ;; so output is "aaa" + newline + "BREAK" + "bbb"
      (is (= "aaa\nBREAKbbb" (r/layout doc 5))))))

;; ============================================================
;; Infinite width = always flat
;; ============================================================

(deftest layout-infinite-width
  (testing "##Inf width always renders flat"
    (let [doc (r/group (r/cat (r/text "a") r/line (r/text "b") r/line (r/text "c")))]
      (is (= "a b c" (r/layout doc ##Inf))))))

;; ============================================================
;; line0 — empty when flat
;; ============================================================

(deftest line0-empty-when-flat
  (testing "line0 produces empty string in flat mode"
    (let [doc (r/group (r/cat (r/text "a") r/line0 (r/text "b")))]
      (is (= "ab" (r/layout doc 80)))))
  (testing "line0 breaks like line in break mode"
    (let [doc (r/group (r/cat (r/text "aaa") r/line0 (r/text "bbb")))]
      (is (= "aaa\nbbb" (r/layout doc 5))))))
