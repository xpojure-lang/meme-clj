(ns meme.tools.render-test
  "Isolation tests for the Wadler-Lindig Doc algebra and layout engine."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string]
            [meme.tools.render :as r]))

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
    (is (nil? (r/doc-cat)))
    (is (nil? (r/doc-cat nil)))
    (is (nil? (r/doc-cat nil nil))))
  (testing "one non-nil"
    (is (= (r/text "a") (r/doc-cat nil (r/text "a")))))
  (testing "mixed"
    (let [doc (r/doc-cat nil (r/text "a") nil (r/text "b"))]
      (is (= "ab" (r/layout doc 80))))))

;; ============================================================
;; Layout: group flat vs break
;; ============================================================

(deftest group-flat-when-fits
  (testing "group renders flat when it fits"
    (let [doc (r/group (r/doc-cat (r/text "a") r/line (r/text "b")))]
      (is (= "a b" (r/layout doc 80))))))

(deftest group-breaks-when-exceeds-width
  (testing "group breaks to multi-line when too wide"
    (let [doc (r/group (r/doc-cat (r/text "aaa") r/line (r/text "bbb")))]
      (is (= "aaa\nbbb" (r/layout doc 5))))))

;; ============================================================
;; Nesting and indentation
;; ============================================================

(deftest nest-indents-after-break
  (testing "DocNest increases indent on new lines"
    (let [doc (r/group
                (r/doc-cat (r/text "head")
                       (r/nest 2 (r/doc-cat r/line (r/text "body")))))]
      ;; When broken: head\n  body (2 spaces indent)
      (is (= "head\n  body" (r/layout doc 5))))))

;; ============================================================
;; Hardline
;; ============================================================

(deftest hardline-always-breaks
  (testing "hardline forces break mode even in a flat group"
    (let [doc (r/group (r/doc-cat (r/text "a") r/hardline (r/text "b")))]
      (is (= "a\nb" (r/layout doc 80))))))

;; ============================================================
;; Infinite width = always flat
;; ============================================================

(deftest layout-infinite-width
  (testing "##Inf width always renders flat"
    (let [doc (r/group (r/doc-cat (r/text "a") r/line (r/text "b") r/line (r/text "c")))]
      (is (= "a b c" (r/layout doc ##Inf))))))

;; ============================================================
;; line0 — empty when flat
;; ============================================================

(deftest line0-empty-when-flat
  (testing "line0 produces empty string in flat mode"
    (let [doc (r/group (r/doc-cat (r/text "a") r/line0 (r/text "b")))]
      (is (= "ab" (r/layout doc 80)))))
  (testing "line0 breaks like line in break mode"
    (let [doc (r/group (r/doc-cat (r/text "aaa") r/line0 (r/text "bbb")))]
      (is (= "aaa\nbbb" (r/layout doc 5))))))

;; ============================================================
;; DocIfBreak — mode-sensitive rendering
;; ============================================================

(deftest if-break-selects-per-mode
  (let [ib (r/->DocIfBreak (r/text "BREAK") (r/text "FLAT"))]
    (testing "flat group renders flat branch"
      (is (= "FLAT" (r/layout (r/group ib) 80))))
    (testing "broken group renders break branch"
      ;; Force break by making the group too wide.
      (let [doc (r/group (r/doc-cat (r/text "aaaaaa") r/line ib))]
        ;; Width 3: group cannot fit flat, so IfBreak takes the break branch.
        (is (re-find #"BREAK" (r/layout doc 3)))))))

(deftest if-break-nils-skip-cleanly
  (testing "nil flat-doc in flat mode emits nothing and parse continues"
    (let [ib (r/->DocIfBreak (r/text "B") nil)
          doc (r/group (r/doc-cat (r/text "a") ib (r/text "b")))]
      (is (= "ab" (r/layout doc 80)))))
  (testing "nil break-doc in break mode emits nothing"
    (let [ib (r/->DocIfBreak nil (r/text "F"))
          ;; Force break.
          doc (r/group (r/doc-cat (r/text "xxxxx") r/line ib (r/text "y")))]
      (is (= "xxxxx\ny" (r/layout doc 3))))))

(deftest nested-if-break-inside-group
  (testing "outer group broken, inner if-break sees :break mode"
    (let [inner (r/->DocIfBreak (r/text ",") (r/text ";"))
          ;; Force the outer group to break; inner should use the break branch.
          doc (r/group (r/doc-cat (r/text "aaaaa") r/line (r/text "x") inner (r/text "y")))]
      (is (re-find #"x,y" (r/layout doc 3)))))
  (testing "outer group flat, inner if-break sees :flat mode"
    (let [inner (r/->DocIfBreak (r/text ",") (r/text ";"))
          doc (r/group (r/doc-cat (r/text "a") (r/text "x") inner (r/text "y")))]
      (is (= "ax;y" (r/layout doc 80))))))

;; ============================================================
;; Pathological widths
;; ============================================================

(deftest width-one-forces-everything-to-break
  (testing "width 1 forces every group that contains a line to break"
    (let [doc (r/group (r/doc-cat (r/text "a") r/line (r/text "b") r/line (r/text "c")))]
      (is (= "a\nb\nc" (r/layout doc 1))))))

(deftest width-validation-rejects-bad-values
  (testing "zero width is rejected"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"positive number"
                          (r/layout (r/text "x") 0))))
  (testing "negative width is rejected"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"positive number"
                          (r/layout (r/text "x") -5))))
  (testing "non-number width is rejected"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"positive number"
                          (r/layout (r/text "x") :not-a-width)))))

;; ============================================================
;; DocNest — deeply and compositionally
;; ============================================================

(deftest nest-accumulates-when-composed
  (testing "nest-in-nest sums indentation on break"
    (let [doc (r/group
                (r/doc-cat (r/text "a")
                           (r/nest 2
                             (r/doc-cat r/line
                                        (r/text "b")
                                        (r/nest 3
                                          (r/doc-cat r/line (r/text "c")))))))]
      ;; Width forces break; second line indented 2, third line indented 5.
      (is (= "a\n  b\n     c" (r/layout doc 1))))))

(deftest nest-indentation-deep-chain
  (testing "deeply composed nests render correct cumulative indent"
    (letfn [(stack [n inner]
              (if (zero? n) inner (r/nest 1 (stack (dec n) inner))))]
      (let [doc (r/group
                  (r/doc-cat (r/text "root")
                             (stack 5 (r/doc-cat r/line (r/text "leaf")))))]
        ;; Indent sum = 5.
        (is (= "root\n     leaf" (r/layout doc 1)))))))

;; ============================================================
;; Hardline propagation
;; ============================================================

(deftest hardline-forces-enclosing-group-to-break
  (testing "hardline inside a flat-fitting group still breaks"
    ;; Even at width 80 (would normally fit flat), the hardline forces a break.
    (let [doc (r/group (r/doc-cat (r/text "a") r/hardline (r/text "b")))]
      (is (= "a\nb" (r/layout doc 80)))))
  (testing "nested group with hardline still breaks at the enclosing group"
    (let [inner (r/group (r/doc-cat (r/text "x") r/hardline (r/text "y")))
          doc   (r/group (r/doc-cat (r/text "a") r/line inner))]
      (is (re-find #"x\ny" (r/layout doc 80))))))

;; ============================================================
;; Trailing content has no implicit newline
;; ============================================================

(deftest layout-output-has-no-trailing-newline
  (testing "a single text has no trailing newline"
    (is (= "abc" (r/layout (r/text "abc") 80))))
  (testing "broken group has no trailing newline"
    (let [doc (r/group (r/doc-cat (r/text "aaa") r/line (r/text "bbb")))]
      (is (not (clojure.string/ends-with? (r/layout doc 3) "\n"))))))
