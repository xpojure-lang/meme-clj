(ns meme.alpha.parse.reader.rule1-test
  "Systematic tests for Rule 1: head outside parens.
   Organized as a matrix across three dimensions:
     1. Head type: symbol, keyword, vector, set, map
     2. Spacing: adjacent, space, tab, newline, multiple newlines
     3. Arity: 0, 1, 2, 3+
   Plus: bare paren rejection, quote exception, nesting, and real-world patterns."
  (:require [clojure.test :refer [deftest is testing]]
            [meme.alpha.core :as core]))

;; ===========================================================================
;; Dimension 1 × 3: Head type × Arity
;; ===========================================================================

;; ---------------------------------------------------------------------------
;; Symbol heads
;; ---------------------------------------------------------------------------

(deftest symbol-head-arity-0
  (is (= '[(foo)] (core/meme->forms "foo()"))))

(deftest symbol-head-arity-1
  (is (= '[(foo x)] (core/meme->forms "foo(x)"))))

(deftest symbol-head-arity-2
  (is (= '[(foo x y)] (core/meme->forms "foo(x y)"))))

(deftest symbol-head-arity-3+
  (is (= '[(foo a b c d)] (core/meme->forms "foo(a b c d)"))))

(deftest symbol-head-operator
  (is (= '[(+ 1 2 3)] (core/meme->forms "+(1 2 3)"))))

(deftest symbol-head-nested
  (is (= '[(a (b (c x)))] (core/meme->forms "a(b(c(x)))"))))

;; ---------------------------------------------------------------------------
;; Keyword heads
;; ---------------------------------------------------------------------------

(deftest keyword-head-arity-0
  (is (= '(:foo) (first (core/meme->forms ":foo()")))))

(deftest keyword-head-arity-1
  (is (= '(:foo x) (first (core/meme->forms ":foo(x)")))))

(deftest keyword-head-arity-2
  (is (= '(:foo x y) (first (core/meme->forms ":foo(x y)")))))

(deftest keyword-head-arity-3+
  (is (= '(:foo a b c d) (first (core/meme->forms ":foo(a b c d)")))))

(deftest keyword-head-namespaced
  (is (= '(:ns/key x) (first (core/meme->forms ":ns/key(x)")))))

(deftest keyword-head-require-pattern
  (is (= '(:require [bar]) (first (core/meme->forms ":require([bar])")))))

(deftest keyword-head-import-pattern
  (is (= '(:import [java.util Date]) (first (core/meme->forms ":import([java.util Date])")))))

;; ---------------------------------------------------------------------------
;; Vector heads
;; ---------------------------------------------------------------------------

(deftest vector-head-arity-0
  (is (= '([x]) (first (core/meme->forms "[x]()")))))

(deftest vector-head-arity-1
  (is (= '([x] 1) (first (core/meme->forms "[x](1)")))))

(deftest vector-head-arity-2
  (is (= '([x] (+ x 1) x) (first (core/meme->forms "[x](+(x 1) x)")))))

(deftest vector-head-multi-params
  (is (= '([x y] (+ x y)) (first (core/meme->forms "[x y](+(x y))")))))

(deftest vector-head-empty-vector
  (is (= '([] 42) (first (core/meme->forms "[](42)")))))

;; ---------------------------------------------------------------------------
;; Set heads
;; ---------------------------------------------------------------------------

(deftest set-head-arity-1
  (let [form (first (core/meme->forms "#{:a :b}(x)"))]
    (is (set? (first form)))
    (is (= 'x (second form)))))

(deftest set-head-arity-0
  (let [form (first (core/meme->forms "#{:a}()"))]
    (is (set? (first form)))
    (is (= 1 (count form)))))

;; ---------------------------------------------------------------------------
;; Map heads
;; ---------------------------------------------------------------------------

(deftest map-head-arity-1
  (let [form (first (core/meme->forms "{:a 1}(:a)"))]
    (is (map? (first form)))
    (is (= :a (second form)))))

(deftest map-head-arity-0
  (let [form (first (core/meme->forms "{:a 1}()"))]
    (is (map? (first form)))
    (is (= 1 (count form)))))

;; ===========================================================================
;; Dimension 2: Spacing — head type × whitespace variant
;; ===========================================================================

;; ---------------------------------------------------------------------------
;; Symbol + spacing
;; ---------------------------------------------------------------------------

(deftest symbol-spacing-adjacent
  (is (= '[(f x)] (core/meme->forms "f(x)"))))

(deftest symbol-spacing-space
  (is (= '[(f x)] (core/meme->forms "f (x)"))))

(deftest symbol-spacing-multi-space
  (is (= '[(f x)] (core/meme->forms "f   (x)"))))

(deftest symbol-spacing-tab
  (is (= '[(f x)] (core/meme->forms "f\t(x)"))))

(deftest symbol-spacing-newline
  (is (= '[(f x)] (core/meme->forms "f\n(x)"))))

(deftest symbol-spacing-multi-newline
  (is (= '[(f x)] (core/meme->forms "f\n\n(x)"))))

(deftest symbol-spacing-mixed
  (is (= '[(f x)] (core/meme->forms "f \t\n (x)"))))

;; ---------------------------------------------------------------------------
;; Keyword + spacing
;; ---------------------------------------------------------------------------

(deftest keyword-spacing-adjacent
  (is (= '(:k x) (first (core/meme->forms ":k(x)")))))

(deftest keyword-spacing-space
  (is (= '(:k x) (first (core/meme->forms ":k (x)")))))

(deftest keyword-spacing-newline
  (is (= '(:k x) (first (core/meme->forms ":k\n(x)")))))

;; ---------------------------------------------------------------------------
;; Vector + spacing
;; ---------------------------------------------------------------------------

(deftest vector-spacing-adjacent
  (is (= '([x] 1) (first (core/meme->forms "[x](1)")))))

(deftest vector-spacing-space
  (is (= '([x] 1) (first (core/meme->forms "[x] (1)")))))

(deftest vector-spacing-newline
  (is (= '([x] 1) (first (core/meme->forms "[x]\n(1)")))))

;; ---------------------------------------------------------------------------
;; Set + spacing
;; ---------------------------------------------------------------------------

(deftest set-spacing-adjacent
  (let [form (first (core/meme->forms "#{:a}(x)"))]
    (is (= 'x (second form)))))

(deftest set-spacing-space
  (let [form (first (core/meme->forms "#{:a} (x)"))]
    (is (= 'x (second form)))))

;; ---------------------------------------------------------------------------
;; Map + spacing
;; ---------------------------------------------------------------------------

(deftest map-spacing-adjacent
  (let [form (first (core/meme->forms "{:a 1}(:a)"))]
    (is (= :a (second form)))))

(deftest map-spacing-space
  (let [form (first (core/meme->forms "{:a 1} (:a)"))]
    (is (= :a (second form)))))

;; ===========================================================================
;; Bare parens rejection
;; ===========================================================================

(deftest bare-parens-error-empty
  (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                        #"[Bb]are parentheses"
                        (core/meme->forms "()"))))

(deftest bare-parens-error-with-content
  (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                        #"[Bb]are parentheses"
                        (core/meme->forms "(1 2 3)"))))

(deftest bare-parens-error-with-symbols
  (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                        #"[Bb]are parentheses"
                        (core/meme->forms "(x y)"))))

(deftest bare-parens-error-nested
  (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                        #"[Bb]are parentheses"
                        (core/meme->forms "do((1 2))"))))

;; ===========================================================================
;; Quote exception — '(...) is the only bare-paren form
;; ===========================================================================

(deftest quote-list-allowed
  (is (= '[(quote (1 2 3))] (core/meme->forms "'(1 2 3)"))))

(deftest quote-empty-list
  (is (= '[(quote ())] (core/meme->forms "'()"))))

(deftest quote-nested-list
  (testing "inside quoted list, Clojure S-expression syntax — parens create lists"
    (is (= '[(quote (a (b c)))] (core/meme->forms "'(a (b c))"))))
  (testing "adjacent paren after symbol is NOT a call inside quote"
    (is (= '[(quote (1 b (c)))] (core/meme->forms "'(1 b(c))")))))

(deftest quote-symbol-not-list
  (is (= '[(quote x)] (core/meme->forms "'x"))))

;; ===========================================================================
;; Non-head forms do NOT eat parens
;; ===========================================================================

(deftest number-does-not-eat-paren
  (testing "number followed by call — two separate forms"
    (is (= '[42 (foo x)] (core/meme->forms "42 foo(x)")))))

(deftest string-does-not-eat-paren
  (testing "string followed by call — two separate forms"
    (is (= '["hi" (foo x)] (core/meme->forms "\"hi\" foo(x)")))))

(deftest boolean-does-not-eat-paren
  (testing "true followed by call — two separate forms"
    (is (= '[true (foo x)] (core/meme->forms "true foo(x)")))))

(deftest nil-does-not-eat-paren
  (testing "nil followed by call — two separate forms"
    (is (= '[nil (foo x)] (core/meme->forms "nil foo(x)")))))

;; ===========================================================================
;; Head consumption in multi-form contexts
;; ===========================================================================

(deftest symbol-eats-paren-in-call-args
  (testing "def(x (1 2 3)) — x eats the ("
    (is (= '[(def (x 1 2 3))] (core/meme->forms "def(x (1 2 3))")))))

(deftest vector-eats-paren-in-do
  (testing "do([1 2 3] (4 5)) — vector eats the ("
    (is (= '[(do ([1 2 3] 4 5))] (core/meme->forms "do([1 2 3] (4 5))")))))

(deftest chained-call-after-non-head
  (testing "do(42 (foo)) — 42(foo) chains to (42 foo) via call-chain"
    (is (= '[(do (42 foo))] (core/meme->forms "do(42 (foo))")))))

(deftest vector-followed-by-symbol-call
  (testing "do([1 2 3] foo(x)) — vector does not eat symbol call"
    (is (= '[(do [1 2 3] (foo x))] (core/meme->forms "do([1 2 3] foo(x))")))))

;; ===========================================================================
;; Real-world patterns
;; ===========================================================================

(deftest pattern-defn-single-arity
  (is (= '[(defn greet [name] (str "Hello " name))]
         (core/meme->forms "defn(greet [name] str(\"Hello \" name))"))))

(deftest pattern-defn-multi-arity
  (let [result (first (core/meme->forms "defn(foo [x](x) [x y](+(x y)))"))]
    (is (= 'defn (first result)))
    (is (= 'foo (second result)))
    (is (seq? (nth result 2)))
    (is (vector? (first (nth result 2))))
    (is (seq? (nth result 3)))
    (is (vector? (first (nth result 3))))))

(deftest pattern-ns-require
  (let [result (first (core/meme->forms "ns(my.app :require([clojure.string :as str]))"))]
    (is (= 'ns (first result)))
    (is (= 'my.app (second result)))
    (is (= :require (first (nth result 2))))))

(deftest pattern-ns-require-import
  (let [result (first (core/meme->forms "ns(my.app :require([clojure.string :as str]) :import([java.util Date]))"))]
    (is (= 4 (count result)))
    (is (= :require (first (nth result 2))))
    (is (= :import (first (nth result 3))))))

(deftest pattern-defprotocol
  (let [result (first (core/meme->forms "defprotocol(Drawable draw([this canvas]) bounds([this]))"))]
    (is (= 'defprotocol (first result)))
    (is (= 'Drawable (second result)))
    (is (= 'draw (first (nth result 2))))
    (is (= 'bounds (first (nth result 3))))))

(deftest pattern-defrecord-with-impls
  (let [result (first (core/meme->forms "defrecord(Circle [r] Shape area([this] *(Math/PI r r)))"))]
    (is (= 'defrecord (first result)))
    (is (= 'Shape (nth result 3)))
    (is (= 'area (first (nth result 4))))))

(deftest pattern-let-with-calls
  (is (= '[(let [x (+ 1 2)] (* x x))]
         (core/meme->forms "let([x +(1 2)] *(x x))"))))

(deftest pattern-threading
  (is (= '[(-> x (foo 1) (bar 2))]
         (core/meme->forms "->(x foo(1) bar(2))"))))

(deftest pattern-try-catch
  (is (= '[(try (risky) (catch Exception e (handle e)))]
         (core/meme->forms "try(risky() catch(Exception e handle(e)))"))))

;; ===========================================================================
;; begin/end delimiters
;; ===========================================================================

;; ---------------------------------------------------------------------------
;; begin/end is equivalent to () for calls
;; ---------------------------------------------------------------------------

(deftest begin-end-basic-call
  (is (= '[(foo x y)] (core/meme->forms "foo begin x y end"))))

(deftest begin-end-zero-arity
  (is (= '[(foo)] (core/meme->forms "foo begin end"))))

(deftest begin-end-nested-parens
  (is (= '[(foo (bar x))] (core/meme->forms "foo begin bar(x) end"))))

(deftest begin-end-nested-begin-end
  (is (= '[(foo (bar x))] (core/meme->forms "foo begin bar begin x end end"))))

(deftest begin-end-with-spacing
  (testing "spacing irrelevant before begin"
    (is (= '[(f x)] (core/meme->forms "f  begin x end")))
    (is (= '[(f x)] (core/meme->forms "f\nbegin x end")))))

;; ---------------------------------------------------------------------------
;; begin/end with all head types
;; ---------------------------------------------------------------------------

(deftest begin-end-keyword-head
  (is (= '(:require [bar]) (first (core/meme->forms ":require begin [bar] end")))))

(deftest begin-end-vector-head
  (is (= '([x] 1) (first (core/meme->forms "[x] begin 1 end")))))

;; ---------------------------------------------------------------------------
;; begin/end as symbols when not in call position
;; ---------------------------------------------------------------------------

(deftest begin-as-standalone-symbol
  (testing "begin without following ( or begin is a symbol"
    (is (= '[begin] (core/meme->forms "begin")))))

(deftest end-as-standalone-symbol
  (testing "end outside begin-block is a symbol"
    (is (= '[end] (core/meme->forms "end")))))

(deftest begin-end-as-call-head
  (testing "begin can be a call head with parens"
    (is (= '[(begin x)] (core/meme->forms "begin(x)")))))

;; ---------------------------------------------------------------------------
;; Real-world patterns with begin/end
;; ---------------------------------------------------------------------------

(deftest begin-end-defn
  (is (= '[(defn greet [name] (str "Hello " name))]
         (core/meme->forms "defn begin greet [name] str(\"Hello \" name) end"))))

(deftest begin-end-let
  (is (= '[(let [x 1] (+ x 2))]
         (core/meme->forms "let begin [x 1] +(x 2) end"))))

(deftest begin-end-try-catch
  (is (= '[(try (risky) (catch Exception e (handle e)))]
         (core/meme->forms "try begin risky() catch begin Exception e handle(e) end end"))))

;; ---------------------------------------------------------------------------
;; begin/end error paths
;; ---------------------------------------------------------------------------

(deftest begin-end-unclosed-at-eof
  (testing "f begin x — missing end at EOF"
    (let [e (try (core/meme->forms "f begin x")
                 nil
                 (catch #?(:clj Exception :cljs js/Error) e e))]
      (is (some? e))
      (is (:incomplete (ex-data e)) "should be :incomplete for REPL continuation")
      (is (re-find #"end" (ex-message e)))))
  (testing "f begin — empty body, missing end"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"end"
          (core/meme->forms "f begin"))))
  (testing "nested begin without inner end"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"end"
          (core/meme->forms "f begin g begin x end")))))
