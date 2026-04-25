(ns mclj-lang.reader.call-syntax-test
  "Systematic tests for M-expression call syntax: head outside parens.
   Organized as a matrix across three dimensions:
     1. Head type: symbol, keyword, vector, set, map
     2. Spacing: adjacent, space, tab, newline, multiple newlines
     3. Arity: 0, 1, 2, 3+
   Plus: bare paren rejection, quote exception, nesting, and real-world patterns."
  (:require [clojure.test :refer [deftest is testing]]
            [mclj-lang.api :as lang]))

;; ===========================================================================
;; Dimension 1 × 3: Head type × Arity
;; ===========================================================================

;; ---------------------------------------------------------------------------
;; Symbol heads
;; ---------------------------------------------------------------------------

(deftest symbol-head-arity-0
  (is (= '[(foo)] (lang/mclj->forms "foo()"))))

(deftest symbol-head-arity-1
  (is (= '[(foo x)] (lang/mclj->forms "foo(x)"))))

(deftest symbol-head-arity-2
  (is (= '[(foo x y)] (lang/mclj->forms "foo(x y)"))))

(deftest symbol-head-arity-3+
  (is (= '[(foo a b c d)] (lang/mclj->forms "foo(a b c d)"))))

(deftest symbol-head-operator
  (is (= '[(+ 1 2 3)] (lang/mclj->forms "+(1 2 3)"))))

(deftest symbol-head-nested
  (is (= '[(a (b (c x)))] (lang/mclj->forms "a(b(c(x)))"))))

;; ---------------------------------------------------------------------------
;; Keyword heads
;; ---------------------------------------------------------------------------

(deftest keyword-head-arity-0
  (is (= '(:foo) (first (lang/mclj->forms ":foo()")))))

(deftest keyword-head-arity-1
  (is (= '(:foo x) (first (lang/mclj->forms ":foo(x)")))))

(deftest keyword-head-arity-2
  (is (= '(:foo x y) (first (lang/mclj->forms ":foo(x y)")))))

(deftest keyword-head-arity-3+
  (is (= '(:foo a b c d) (first (lang/mclj->forms ":foo(a b c d)")))))

(deftest keyword-head-namespaced
  (is (= '(:ns/key x) (first (lang/mclj->forms ":ns/key(x)")))))

(deftest keyword-head-require-pattern
  (is (= '(:require [bar]) (first (lang/mclj->forms ":require([bar])")))))

(deftest keyword-head-import-pattern
  (is (= '(:import [java.util Date]) (first (lang/mclj->forms ":import([java.util Date])")))))

;; ---------------------------------------------------------------------------
;; Vector heads
;; ---------------------------------------------------------------------------

(deftest vector-head-arity-0
  (is (= '([x]) (first (lang/mclj->forms "[x]()")))))

(deftest vector-head-arity-1
  (is (= '([x] 1) (first (lang/mclj->forms "[x](1)")))))

(deftest vector-head-arity-2
  (is (= '([x] (+ x 1) x) (first (lang/mclj->forms "[x](+(x 1) x)")))))

(deftest vector-head-multi-params
  (is (= '([x y] (+ x y)) (first (lang/mclj->forms "[x y](+(x y))")))))

(deftest vector-head-empty-vector
  (is (= '([] 42) (first (lang/mclj->forms "[](42)")))))

;; ---------------------------------------------------------------------------
;; Set heads
;; ---------------------------------------------------------------------------

(deftest set-head-arity-1
  (let [form (first (lang/mclj->forms "#{:a :b}(x)"))]
    (is (set? (first form)))
    (is (= 'x (second form)))))

(deftest set-head-arity-0
  (let [form (first (lang/mclj->forms "#{:a}()"))]
    (is (set? (first form)))
    (is (= 1 (count form)))))

;; ---------------------------------------------------------------------------
;; Map heads
;; ---------------------------------------------------------------------------

(deftest map-head-arity-1
  (let [form (first (lang/mclj->forms "{:a 1}(:a)"))]
    (is (map? (first form)))
    (is (= :a (second form)))))

(deftest map-head-arity-0
  (let [form (first (lang/mclj->forms "{:a 1}()"))]
    (is (map? (first form)))
    (is (= 1 (count form)))))

;; ===========================================================================
;; Dimension 2: Spacing — head type × whitespace variant
;; ===========================================================================

;; ---------------------------------------------------------------------------
;; Symbol + spacing
;; ---------------------------------------------------------------------------

(deftest symbol-spacing-adjacent
  (is (= '[(f x)] (lang/mclj->forms "f(x)"))))

(deftest symbol-spacing-space
  (testing "space between head and ( — NOT a call, bare paren error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Bare parentheses"
                          (lang/mclj->forms "f (x)")))))

(deftest symbol-spacing-multi-space
  (testing "multiple spaces between head and ( — NOT a call"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Bare parentheses"
                          (lang/mclj->forms "f   (x)")))))

(deftest symbol-spacing-tab
  (testing "tab between head and ( — NOT a call"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Bare parentheses"
                          (lang/mclj->forms "f\t(x)")))))

(deftest symbol-spacing-newline
  (testing "newline between head and ( — NOT a call"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Bare parentheses"
                          (lang/mclj->forms "f\n(x)")))))

(deftest symbol-spacing-multi-newline
  (testing "multiple newlines between head and ( — NOT a call"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Bare parentheses"
                          (lang/mclj->forms "f\n\n(x)")))))

(deftest symbol-spacing-mixed
  (testing "mixed whitespace between head and ( — NOT a call"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Bare parentheses"
                          (lang/mclj->forms "f \t\n (x)")))))

;; ---------------------------------------------------------------------------
;; Keyword + spacing
;; ---------------------------------------------------------------------------

(deftest keyword-spacing-adjacent
  (is (= '(:k x) (first (lang/mclj->forms ":k(x)")))))

(deftest keyword-spacing-space
  (testing "space between keyword and ( — NOT a call"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Bare parentheses"
                          (lang/mclj->forms ":k (x)")))))

(deftest keyword-spacing-newline
  (testing "newline between keyword and ( — NOT a call"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Bare parentheses"
                          (lang/mclj->forms ":k\n(x)")))))

;; ---------------------------------------------------------------------------
;; Vector + spacing
;; ---------------------------------------------------------------------------

(deftest vector-spacing-adjacent
  (is (= '([x] 1) (first (lang/mclj->forms "[x](1)")))))

(deftest vector-spacing-space
  (testing "space between vector and ( — NOT a call"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Bare parentheses"
                          (lang/mclj->forms "[x] (1)")))))

(deftest vector-spacing-newline
  (testing "newline between vector and ( — NOT a call"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Bare parentheses"
                          (lang/mclj->forms "[x]\n(1)")))))

;; ---------------------------------------------------------------------------
;; Set + spacing
;; ---------------------------------------------------------------------------

(deftest set-spacing-adjacent
  (let [form (first (lang/mclj->forms "#{:a}(x)"))]
    (is (= 'x (second form)))))

(deftest set-spacing-space
  (testing "space between set and ( — NOT a call"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Bare parentheses"
                          (lang/mclj->forms "#{:a} (x)")))))

;; ---------------------------------------------------------------------------
;; Map + spacing
;; ---------------------------------------------------------------------------

(deftest map-spacing-adjacent
  (let [form (first (lang/mclj->forms "{:a 1}(:a)"))]
    (is (= :a (second form)))))

(deftest map-spacing-space
  (testing "space between map and ( — NOT a call"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Bare parentheses"
                          (lang/mclj->forms "{:a 1} (:a)")))))

;; ===========================================================================
;; Bare parens rejection
;; ===========================================================================

(deftest empty-list
  (is (= [(list)] (lang/mclj->forms "()"))))

(deftest bare-parens-error-with-content
  (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                        #"[Bb]are parentheses"
                        (lang/mclj->forms "(1 2 3)"))))

(deftest bare-parens-error-with-symbols
  (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                        #"[Bb]are parentheses"
                        (lang/mclj->forms "(x y)"))))

(deftest bare-parens-error-nested
  (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                        #"[Bb]are parentheses"
                        (lang/mclj->forms "do((1 2))"))))

;; ===========================================================================
;; Quote exception — '(...) is the only bare-paren form
;; ===========================================================================

(deftest quote-empty-list
  (is (= '[(quote ())] (lang/mclj->forms "'()"))))

(deftest quote-call-form
  (testing "'f(x) quotes the call form (f x)"
    (is (= '[(quote (f x))] (lang/mclj->forms "'f(x)"))))
  (testing "'+(1 2) quotes the call form (+ 1 2)"
    (is (= '[(quote (+ 1 2))] (lang/mclj->forms "'+(1 2)")))))

(deftest quote-bare-parens-error
  (testing "'(1 2 3) is quote + bare parens — error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"[Bb]are parentheses"
                          (lang/mclj->forms "'(1 2 3)")))))

(deftest quote-symbol-not-list
  (is (= '[(quote x)] (lang/mclj->forms "'x"))))

;; ===========================================================================
;; Non-head forms do NOT eat parens
;; ===========================================================================

(deftest number-does-not-eat-paren
  (testing "number followed by call — two separate forms"
    (is (= '[42 (foo x)] (lang/mclj->forms "42 foo(x)")))))

(deftest string-does-not-eat-paren
  (testing "string followed by call — two separate forms"
    (is (= '["hi" (foo x)] (lang/mclj->forms "\"hi\" foo(x)")))))

(deftest boolean-does-not-eat-paren
  (testing "true followed by call — two separate forms"
    (is (= '[true (foo x)] (lang/mclj->forms "true foo(x)")))))

(deftest nil-does-not-eat-paren
  (testing "nil followed by call — two separate forms"
    (is (= '[nil (foo x)] (lang/mclj->forms "nil foo(x)")))))

;; ===========================================================================
;; Head consumption in multi-form contexts
;; ===========================================================================

(deftest symbol-eats-paren-in-call-args
  (testing "def(x(1 2 3)) — x eats the ( when adjacent"
    (is (= '[(def (x 1 2 3))] (lang/mclj->forms "def(x(1 2 3))"))))
  (testing "def(x (1 2 3)) — space prevents call, bare paren error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Bare parentheses"
                          (lang/mclj->forms "def(x (1 2 3))")))))

(deftest vector-eats-paren-in-do
  (testing "do([1 2 3](4 5)) — vector eats the ( when adjacent"
    (is (= '[(do ([1 2 3] 4 5))] (lang/mclj->forms "do([1 2 3](4 5))"))))
  (testing "do([1 2 3] (4 5)) — space prevents call, bare paren error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Bare parentheses"
                          (lang/mclj->forms "do([1 2 3] (4 5))")))))

(deftest chained-call-after-non-head
  (testing "do(42(foo)) — 42(foo) chains to (42 foo) when adjacent"
    (is (= '[(do (42 foo))] (lang/mclj->forms "do(42(foo))"))))
  (testing "do(42 (foo)) — space prevents chain, bare paren error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Bare parentheses"
                          (lang/mclj->forms "do(42 (foo))")))))

(deftest vector-followed-by-symbol-call
  (testing "do([1 2 3] foo(x)) — vector does not eat symbol call"
    (is (= '[(do [1 2 3] (foo x))] (lang/mclj->forms "do([1 2 3] foo(x))")))))

;; ===========================================================================
;; Real-world patterns
;; ===========================================================================

(deftest pattern-defn-single-arity
  (is (= '[(defn greet [name] (str "Hello " name))]
         (lang/mclj->forms "defn(greet [name] str(\"Hello \" name))"))))

(deftest pattern-defn-multi-arity
  (let [result (first (lang/mclj->forms "defn(foo [x](x) [x y](+(x y)))"))]
    (is (= 'defn (first result)))
    (is (= 'foo (second result)))
    (is (seq? (nth result 2)))
    (is (vector? (first (nth result 2))))
    (is (seq? (nth result 3)))
    (is (vector? (first (nth result 3))))))

(deftest pattern-ns-require
  (let [result (first (lang/mclj->forms "ns(my.app :require([clojure.string :as str]))"))]
    (is (= 'ns (first result)))
    (is (= 'my.app (second result)))
    (is (= :require (first (nth result 2))))))

(deftest pattern-ns-require-import
  (let [result (first (lang/mclj->forms "ns(my.app :require([clojure.string :as str]) :import([java.util Date]))"))]
    (is (= 4 (count result)))
    (is (= :require (first (nth result 2))))
    (is (= :import (first (nth result 3))))))

(deftest pattern-defprotocol
  (let [result (first (lang/mclj->forms "defprotocol(Drawable draw([this canvas]) bounds([this]))"))]
    (is (= 'defprotocol (first result)))
    (is (= 'Drawable (second result)))
    (is (= 'draw (first (nth result 2))))
    (is (= 'bounds (first (nth result 3))))))

(deftest pattern-defrecord-with-impls
  (let [result (first (lang/mclj->forms "defrecord(Circle [r] Shape area([this] *(Math/PI r r)))"))]
    (is (= 'defrecord (first result)))
    (is (= 'Shape (nth result 3)))
    (is (= 'area (first (nth result 4))))))

(deftest pattern-let-with-calls
  (is (= '[(let [x (+ 1 2)] (* x x))]
         (lang/mclj->forms "let([x +(1 2)] *(x x))"))))

(deftest pattern-threading
  (is (= '[(-> x (foo 1) (bar 2))]
         (lang/mclj->forms "->(x foo(1) bar(2))"))))

(deftest pattern-try-catch
  (is (= '[(try (risky) (catch Exception e (handle e)))]
         (lang/mclj->forms "try(risky() catch(Exception e handle(e)))"))))
