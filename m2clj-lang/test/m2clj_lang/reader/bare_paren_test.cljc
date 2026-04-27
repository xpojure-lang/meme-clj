(ns m2clj-lang.reader.bare-paren-test
  "Scar tissue: the m2clj bare-paren-as-list rule.

   Every test here pins a behavior that distinguishes m2clj from m1clj:
   parens with no adjacency to a head are list literals, not parse errors.
   The lowered Clojure form is always (quote (...)) — semantically
   equivalent to '(...) — distinguished only by source notation."
  (:require [clojure.test :refer [deftest is testing]]
            [meme.tools.parser :as parser]
            [meme.tools.clj.ast.build :as ast-build]
            [meme.tools.clj.ast.lower :as ast-lower]
            [meme.tools.clj.ast.nodes :as nodes]
            [m2clj-lang.grammar :as grammar]))

;; ---------------------------------------------------------------------------
;; Helpers — read m2clj source through the full pipeline
;; ---------------------------------------------------------------------------

(defn- read-forms
  "Parse m2clj source → CST → AST → forms."
  [src]
  (-> (parser/parse src grammar/grammar)
      (ast-build/cst->ast nil)
      (ast-lower/ast->forms nil)))

(defn- read-ast
  "Parse m2clj source → CST → AST root."
  [src]
  (-> (parser/parse src grammar/grammar)
      (ast-build/cst->ast nil)))

(defn- top-form [src] (first (read-forms src)))

;; ---------------------------------------------------------------------------
;; The core rule: bare paren = literal, adjacent paren = call
;; ---------------------------------------------------------------------------

(deftest empty-bare-paren-is-empty-list
  (testing "() reads as the empty list — no quote applied"
    (is (= '() (top-form "()"))))
  (testing "() AST is CljList, NOT CljQuote — no quote semantically"
    (let [ast (read-ast "()")
          child (first (:children ast))]
      (is (instance? meme.tools.clj.ast.nodes.CljList child))
      (is (not (instance? meme.tools.clj.ast.nodes.CljQuote child))))))

(deftest non-empty-bare-paren-is-quoted-list
  (testing "(x y z) lowers to (quote (x y z))"
    (is (= '(quote (x y z)) (top-form "(x y z)"))))
  (testing "(x y z) AST is CljQuote{form: CljList, notation: :bare}"
    (let [ast (read-ast "(x y z)")
          child (first (:children ast))]
      (is (instance? meme.tools.clj.ast.nodes.CljQuote child))
      (is (= :bare (:notation child)))
      (is (instance? meme.tools.clj.ast.nodes.CljList (:form child))))))

(deftest single-element-bare-paren
  (testing "(x) is the 1-element literal list (x)"
    (is (= '(quote (x)) (top-form "(x)")))))

(deftest call-via-adjacency
  (testing "f(x y) is a call, not a literal"
    (is (= '(f x y) (top-form "f(x y)")))
    (let [ast (read-ast "f(x y)")
          child (first (:children ast))]
      (is (instance? meme.tools.clj.ast.nodes.CljList child)
          "call AST is a CljList — head + args"))))

(deftest spacing-distinguishes-call-from-literal
  (testing "f(x) — adjacent — is a call"
    (is (= '(f x) (top-form "f(x)"))))
  (testing "f (x) — non-adjacent — is two forms: symbol + literal"
    (let [forms (read-forms "f (x)")]
      (is (= 2 (count forms)))
      (is (= 'f (first forms)))
      (is (= '(quote (x)) (second forms))))))

;; ---------------------------------------------------------------------------
;; The (quote ...) example that motivates the rule
;; ---------------------------------------------------------------------------

(deftest quote-as-symbol-in-bare-paren
  (testing "(quote 1 2 3) is a 4-element literal list, NOT a call to special form quote"
    (is (= '(quote (quote 1 2 3)) (top-form "(quote 1 2 3)")))
    (testing "the inner list contains the symbol 'quote' as data"
      (let [v (second (top-form "(quote 1 2 3)"))]
        (is (= 'quote (first v)))
        (is (= [1 2 3] (vec (rest v))))))))

;; ---------------------------------------------------------------------------
;; Nesting — parens never become calls regardless of context
;; ---------------------------------------------------------------------------

(deftest call-with-literal-list-arg
  (testing "f((a b) c) is a call with a literal-list arg"
    (is (= '(f (quote (a b)) c) (top-form "f((a b) c)")))))

(deftest literal-list-containing-call-form
  (testing "(f(a b) c) is a literal containing a call form"
    ;; Inner f(a b) is structurally a call, but the outer bare-paren wraps
    ;; everything in quote — eval doesn't fire the inner call.
    (is (= '(quote ((f a b) c)) (top-form "(f(a b) c)")))))

(deftest literal-of-literals
  (testing "((1 2) 3) is a literal whose first element is itself a literal"
    (is (= '(quote ((quote (1 2)) 3)) (top-form "((1 2) 3)")))))

(deftest let-body-must-use-adjacency-for-call
  (testing "let body written with bare parens is a literal, not a call"
    (is (= '(let [x 1] (quote (+ x x)))
           (top-form "let([x 1] (+ x x))"))))
  (testing "let body written with adjacency is a call"
    (is (= '(let [x 1] (+ x x))
           (top-form "let([x 1] +(x x))")))))

;; ---------------------------------------------------------------------------
;; Tick-over-bare-paren = double quote (no source-level shortcut)
;; ---------------------------------------------------------------------------

(deftest tick-over-bare-paren-is-double-quote
  (testing "'(x y z) in m2clj is double-quoted — tick over the already-quoting bare paren"
    (is (= '(quote (quote (x y z))) (top-form "'(x y z)")))))

;; ---------------------------------------------------------------------------
;; Vectors and other delimiters are unaffected
;; ---------------------------------------------------------------------------

(deftest vectors-and-maps-and-sets-unchanged
  (testing "[x y z] is a vector literal (Clojure-equivalent)"
    (is (= '[x y z] (top-form "[x y z]"))))
  (testing "{:a 1} is a map literal"
    (is (= {:a 1} (top-form "{:a 1}"))))
  (testing "#{1 2 3} is a set literal"
    (is (= #{1 2 3} (top-form "#{1 2 3}")))))

(deftest vector-of-bare-lists
  (testing "[(a b) (c d)] is a vector of two literal lists"
    (is (= '[(quote (a b)) (quote (c d))] (top-form "[(a b) (c d)]")))))

;; ---------------------------------------------------------------------------
;; Dispatch sublanguage interaction — `#?(...)` `#(...)` `#{...}` etc.
;; The dispatch scanlet consumes `#X` first; the inner `(` is its own delimited
;; body, NOT independently a bare paren. No interaction with the new rule.
;; ---------------------------------------------------------------------------

(deftest reader-conditional-not-affected
  (testing "#?(:clj x :cljs y) is a reader conditional, not a bare paren"
    (let [forms (read-forms "#?(:clj x :cljs y)")]
      (is (= 1 (count forms)))
      ;; reader-conditional is preserved as a record by the toolkit
      (is (some? (first forms))))))

(deftest anon-fn-not-affected
  (testing "#(*(% %)) is an anonymous function — # adjacent to ("
    (let [forms (read-forms "#(*(% %))")]
      (is (= 1 (count forms)))
      (is (= 'fn (first (first forms)))))))

(deftest set-not-affected
  (testing "#{1 2 3} is a set — # adjacent to {"
    (is (= #{1 2 3} (top-form "#{1 2 3}")))))

;; ---------------------------------------------------------------------------
;; AST equivalence: tick-quoted-list and bare-paren-list are ast= equal
;; (notation field is in the ast= ignore set)
;; ---------------------------------------------------------------------------

(deftest tick-and-bare-list-are-ast-equal
  (testing "AST equivalence ignores notation"
    ;; Build CljQuote with the same form via both notations
    (let [list-node (nodes/->CljList
                      [(nodes/->CljSymbol "x" nil nil [])
                       (nodes/->CljSymbol "y" nil nil [])]
                      nil [] [])
          tick (nodes/->CljQuote list-node nil [] :tick)
          bare (nodes/->CljQuote list-node nil [] :bare)]
      (is (nodes/ast= tick bare)
          "CljQuote with :tick and :bare notation should be ast= when forms match"))))

;; ---------------------------------------------------------------------------
;; Empty list inside bare paren: () nested as an arg
;; ---------------------------------------------------------------------------

(deftest empty-list-in-arg-slot
  (testing "f(() 1) is a call where the first arg is the empty list"
    (is (= '(f () 1) (top-form "f(() 1)")))))
