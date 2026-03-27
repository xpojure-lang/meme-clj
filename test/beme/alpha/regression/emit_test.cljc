(ns beme.alpha.regression.emit-test
  "Scar tissue: printer and pretty-printer regression tests.
   Every test here prevents a specific bug from recurring."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [beme.alpha.parse.reader :as r]
            [beme.alpha.emit.printer :as p]
            [beme.alpha.emit.pprint :as pprint]))

;; ---------------------------------------------------------------------------
;; Scar tissue: quoted lists print as '(...) not head(args).
;; Bug: printer's catch-all for non-symbol-headed lists emitted 1(2 3)
;; for (quote (1 2 3)), producing '1(2 3) instead of '(1 2 3).
;; ---------------------------------------------------------------------------

(deftest quoted-list-printer
  (testing "'(1 2 3) roundtrips"
    (let [form '(quote (1 2 3))
          printed (p/print-form form)
          read-back (first (r/read-beme-string printed))]
      (is (= "'(1 2 3)" printed))
      (is (= form read-back))))
  (testing "'(a b c) roundtrips"
    (let [form '(quote (a b c))
          printed (p/print-form form)
          read-back (first (r/read-beme-string printed))]
      (is (= "'(a b c)" printed))
      (is (= form read-back))))
  (testing "quoted empty list"
    (is (= "'()" (p/print-form '(quote ()))))))

;; ---------------------------------------------------------------------------
;; Empty list prints as valid beme: () → "'()" not "nil()".
;; Bug: print-form on empty list produced "nil()" which re-reads as (nil).
;; ---------------------------------------------------------------------------

(deftest empty-list-roundtrip
  (testing "empty list prints as '()"
    (is (= "'()" (p/print-form ()))))
  (testing "printed empty list re-reads correctly"
    (is (= '(quote ()) (first (r/read-beme-string "'()"))))))

;; ---------------------------------------------------------------------------
;; B4: #() printer drops surplus % params.
;; Bug: (fn [%1 %2] (inc %1)) printed as #(inc(%1)) — silent arity change.
;; ---------------------------------------------------------------------------

(deftest anon-fn-surplus-params-not-dropped
  (testing "fn with surplus % params prints as fn(...), not #()"
    (let [form '(fn [%1 %2] (inc %1))
          printed (p/print-form form)]
      (is (not (str/starts-with? printed "#("))
          "surplus params must not emit #() shorthand")
      (is (= form (first (r/read-beme-string printed)))
          "roundtrip must preserve arity")))
  (testing "fn with matching % params still uses #() shorthand"
    (let [form '(fn [%1 %2] (+ %1 %2))
          printed (p/print-form form)]
      (is (str/starts-with? printed "#(")
          "matching params should emit #() shorthand")))
  (testing "zero-param fn with %N in body must not use #() shorthand"
    (let [form '(fn [] (inc %1))
          printed (p/print-form form)]
      (is (not (str/starts-with? printed "#("))
          "body references %1 but params is [] — #() would change arity")
      (is (= form (first (r/read-beme-string printed)))
          "roundtrip must preserve zero arity")))
  (testing "zero-param fn without %N in body still uses #() shorthand"
    (let [form '(fn [] (rand))
          printed (p/print-form form)]
      (is (str/starts-with? printed "#(")
          "no % params in body — #() shorthand is safe"))))

;; ---------------------------------------------------------------------------
;; Bug: quoted list with non-callable-headed sublists produced broken output.
;; Fix: '(...) sugar only when all inner sublists have callable heads.
;; ---------------------------------------------------------------------------

(deftest quoted-list-with-clj-syntax-inside
  (testing "sublists inside quote print as S-expressions"
    (let [form '(quote (f (g x)))
          printed (p/print-form form)
          reread (first (r/read-beme-string printed))]
      (is (= "'(f (g x))" printed))
      (is (= form reread))))
  (testing "all-atoms quoted list still uses '(...) sugar"
    (let [form '(quote (1 2 3))
          printed (p/print-form form)]
      (is (= "'(1 2 3)" printed))
      (is (= form (first (r/read-beme-string printed))))))
  (testing "number-headed sublist now prints and roundtrips"
    (let [form (list 'quote (list (list 1 2 3)))
          printed (p/print-form form)
          reread (first (r/read-beme-string printed))]
      (is (= "'((1 2 3))" printed))
      (is (= form reread))))
  (testing "string-headed sublist roundtrips"
    (let [form (list 'quote (list (list "hello" 1)))
          printed (p/print-form form)
          reread (first (r/read-beme-string printed))]
      (is (= "'((\"hello\" 1))" printed))
      (is (= form reread))))
  (testing "nil-headed sublist roundtrips"
    (let [form (list 'quote (list (list nil 1)))
          printed (p/print-form form)
          reread (first (r/read-beme-string printed))]
      (is (= "'((nil 1))" printed))
      (is (= form reread))))
  (testing "mixed elements with non-callable sublist roundtrips"
    (let [form (list 'quote (list 'x (list 1 2) 'y))
          printed (p/print-form form)
          reread (first (r/read-beme-string printed))]
      (is (= "'(x (1 2) y)" printed))
      (is (= form reread)))))

;; ---------------------------------------------------------------------------
;; Scar tissue: quoted list shorthand checked only direct children.
;; Bug: (quote ((a (1 2)) b)) passed the every? check because (a (1 2))
;; has a symbol head, but the nested (1 2) has a number head.
;; Fix: recursive check of all nested sublists.
;; ---------------------------------------------------------------------------

(deftest quoted-list-nested-sublists-roundtrip
  (testing "nested non-callable head now prints and roundtrips"
    (let [form '(quote ((a (1 2)) b))
          printed (p/print-form form)
          reread (first (r/read-beme-string printed))]
      (is (= "'((a (1 2)) b)" printed))
      (is (= form reread))))
  (testing "all-callable nested sublists also roundtrip"
    (let [form '(quote ((a (b c)) d))
          printed (p/print-form form)]
      (is (str/starts-with? printed "'("))))
  (testing "deeply nested sublists roundtrip"
    (let [form '(quote ((a (b c)) d))
          printed (p/print-form form)
          read-back (first (r/read-beme-string printed))]
      (is (= form read-back)))))

;; ---------------------------------------------------------------------------
;; Scar tissue: pprint head-line args must respect width.
;; Bug: pp-call-smart always printed head-line args flat.
;; ---------------------------------------------------------------------------

#?(:clj
(deftest pprint-head-line-args-respect-width
  (testing "long if-condition falls back to body when it exceeds width"
    (let [form '(if (and (> x 100) (< y 200) (not= z 0)) (body1) (body2))
          result (pprint/pprint-form form {:width 40})]
      (is (not (re-find #"if begin and" result))
          "long condition should not stay on head line")
      (doseq [line (str/split-lines result)]
        (is (<= (count line) 42)
            (str "line exceeds width: " (pr-str line))))))
  (testing "short if-condition stays on head line when begin/end needed"
    (let [form '(if (> x 0) (do-something-with x) (do-something-else y))
          result (pprint/pprint-form form {:width 40})]
      (is (re-find #"if begin >" result)
          "short condition should stay on head line")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: pprint map value column must use actual key width.
;; ---------------------------------------------------------------------------

#?(:clj
(deftest pprint-map-value-column-uses-actual-key-width
  (testing "map value indentation based on actual key width, not flat width"
    (let [form {:k '(some-long-function arg1 arg2 arg3 arg4 arg5)}
          result (pprint/pprint-form form {:width 40})
          lines (str/split-lines result)]
      (is (some? result))
      (is (> (count lines) 1) "should be multi-line")))))

;; ---------------------------------------------------------------------------
;; Bug: pp-map underestimated val-col for single-line keys.
;; val-col omitted inner-col, so values got more horizontal space than
;; they actually had, printing flat when they should break.
;; ---------------------------------------------------------------------------

#?(:clj
(deftest pprint-map-value-column-includes-indent
  (testing "map value breaks when key + indent + value exceed width"
    ;; Map is multi-line (flat > width 30). Inside, inner-col = 2.
    ;; Key :k is 2 chars. Correct val-col = 2 + 2 + 1 = 5.
    ;; Bug val-col was 2 + 1 = 3 (missing inner-col).
    ;; Value "fff(arg1 arg2 arg3 arg4 a)" is 26 chars.
    ;; At correct val-col 5: 5 + 26 = 31 > 30 → value breaks.
    ;; At bug val-col 3:     3 + 26 = 29 ≤ 30 → value stays flat (wrong).
    (let [form (sorted-map :k '(fff arg1 arg2 arg3 arg4 a) :z 1)
          result (pprint/pprint-form form {:width 30})
          lines (str/split-lines result)]
      (is (> (count lines) 3) "value should break to multi-line")
      (doseq [line lines]
        (is (<= (count line) 34)
            (str "line exceeds width: " (pr-str line))))))))
