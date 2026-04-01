(ns meme.trs-test
  "Tests for the token-stream term rewriting system."
  (:require [clojure.test :refer [deftest is testing]]
            [meme.trs :as trs]
            [meme.convert :as convert]))

;; ---------------------------------------------------------------------------
;; Token-stream rewriting: meme→sexp
;; ---------------------------------------------------------------------------

(deftest trs-simple-call
  (testing "f(x) → (f x)"
    (is (= "(f x)" (trs/meme->clj-text "f(x)")))))

(deftest trs-multi-arg
  (testing "f(x y) → (f x y)"
    (is (= "(f x y)" (trs/meme->clj-text "f(x y)")))))

(deftest trs-nested-calls
  (testing "+(1 *(2 3)) → (+ 1 (* 2 3))"
    (is (= "(+ 1 (* 2 3))" (trs/meme->clj-text "+(1 *(2 3))")))))

(deftest trs-defn
  (testing "defn with call in body"
    (is (= "(defn foo [x] (+ x 1))"
           (trs/meme->clj-text "defn(foo [x] +(x 1))")))))

(deftest trs-empty-call
  (testing "f() → (f)"
    (is (= "(f)" (trs/meme->clj-text "f()")))))

(deftest trs-non-call-spacing
  (testing "f (x) — space prevents call"
    (is (= "f (x)" (trs/meme->clj-text "f (x)")))))

(deftest trs-data-literals-passthrough
  (testing "vectors, maps, sets pass through unchanged"
    (is (= "[1 2 3]" (trs/meme->clj-text "[1 2 3]")))
    (is (= "{:a 1 :b 2}" (trs/meme->clj-text "{:a 1 :b 2}")))
    (is (= "#{1 2}" (trs/meme->clj-text "#{1 2}")))))

(deftest trs-vector-as-head
  (testing "[x](1) → ([x] 1)"
    (is (= "([x] 1)" (trs/meme->clj-text "[x](1)")))))

(deftest trs-multi-arity
  (testing "defn(name [x](body1) [y](body2)) → (defn name ([x] body1) ([y] body2))"
    (is (= "(defn name ([x] body1) ([y] body2))"
           (trs/meme->clj-text "defn(name [x](body1) [y](body2))")))))

(deftest trs-keyword-head
  (testing ":key(map) → (:key map)"
    (is (= "(:key map)" (trs/meme->clj-text ":key(map)")))))

(deftest trs-nil-head
  (testing "nil(1 2) → (nil 1 2)"
    (is (= "(nil 1 2)" (trs/meme->clj-text "nil(1 2)")))))

(deftest trs-quote-call
  (testing "'f(x) → '(f x)"
    (is (= "'(f x)" (trs/meme->clj-text "'f(x)")))))

(deftest trs-deref
  (testing "@atom passthrough"
    (is (= "@atom" (trs/meme->clj-text "@atom")))))

(deftest trs-deeply-nested
  (testing "f(g(h(i(x)))) → (f (g (h (i x))))"
    (is (= "(f (g (h (i x))))" (trs/meme->clj-text "f(g(h(i(x))))")))))

(deftest trs-chained-calls
  (testing "f(x)(y) → ((f x) y)"
    (is (= "((f x) y)" (trs/meme->clj-text "f(x)(y)")))))

(deftest trs-triple-chain
  (testing "f(x)(y)(z) → (((f x) y) z)"
    (is (= "(((f x) y) z)" (trs/meme->clj-text "f(x)(y)(z)")))))

(deftest trs-prefix-on-call
  (testing "@f(x) → @(f x)"
    (is (= "@(f x)" (trs/meme->clj-text "@f(x)"))))
  (testing "'f(x) covers prefix + call"
    (is (= "'(f x)" (trs/meme->clj-text "'f(x)")))))

(deftest trs-metadata-on-call
  (testing "^:foo f(x) preserves metadata prefix"
    (is (= "^:foo (f x)" (trs/meme->clj-text "^:foo f(x)")))))

(deftest trs-reader-conditional-passthrough
  (testing "#?(:clj x :cljs y) passes through"
    (is (= "#?(:clj x :cljs y)" (trs/meme->clj-text "#?(:clj x :cljs y)")))))

;; ---------------------------------------------------------------------------
;; Lang integration: ts-trs agrees with classic
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest lang-agreement
     (testing "ts-trs agrees with classic on basic cases"
       (doseq [src ["f(x y)"
                     "+(1 2)"
                     "defn(foo [x] +(x 1))"
                     "[1 2 3]"
                     "f()"
                     "'f(x)"
                     "[x](1)"
                     ":key(map)"
                     ;; chained calls
                     "f(x)(y)"
                     "f(x)(y)(z)"
                     ;; prefix + call
                     "@f(x)"
                     "^:foo f(x)"
                     ;; calls inside collections
                     "[f(x) g(y)]"
                     "{:a f(x) :b g(y)}"]]
         (is (= (convert/meme->clj src :meme-classic)
                (convert/meme->clj src :meme-trs))
             (str "disagreement on: " src))))))
