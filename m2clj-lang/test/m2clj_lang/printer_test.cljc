(ns m2clj-lang.printer-test
  "Scar tissue: the m2clj printer's bare-paren rendering rule.

   In :m2clj mode, a quoted non-empty list (whether stored as a CljQuote
   AST node or as a plain (quote ...) form) renders as bare-paren
   `(elements)`. Tick-over-list `'(x y z)` is canonicalised away because
   in m2clj source it would mean double-quote.

   In :clj mode, all CljQuote variants render as `'<inner>` (Clojure has
   no bare-paren-as-list notation)."
  (:require [clojure.test :refer [deftest is testing]]
            [meme.tools.parser :as parser]
            [meme.tools.clj.ast.build :as ast-build]
            [meme.tools.clj.ast.lower :as ast-lower]
            [meme.tools.clj.ast.nodes :as nodes]
            [m2clj-lang.grammar :as grammar]
            [m2clj-lang.formatter.flat :as flat]))

(defn- read-forms [src]
  (-> (parser/parse src grammar/grammar)
      (ast-build/cst->ast nil)
      (ast-lower/ast->forms nil)))

(defn- roundtrip
  "Parse src, format as m2clj, re-parse. Return [printed reparsed-forms]."
  [src]
  (let [forms (read-forms src)
        printed (flat/format-forms forms)
        reparsed (read-forms printed)]
    [printed reparsed forms]))

;; ---------------------------------------------------------------------------
;; Plain-form path: (quote ...) special-case
;; ---------------------------------------------------------------------------

(deftest plain-form-quote-of-list-renders-as-bare-paren
  (testing "(quote (x y z)) form → bare paren"
    (is (= "(x y z)" (flat/format-form '(quote (x y z))))))
  (testing "(quote (1 2 3)) form → bare paren"
    (is (= "(1 2 3)" (flat/format-form '(quote (1 2 3))))))
  (testing "(quote (quote x)) form → bare paren containing a symbol"
    (is (= "(quote x)" (flat/format-form '(quote (quote x)))))))

(deftest plain-form-quote-of-symbol-renders-as-tick
  (testing "(quote foo) form → 'foo"
    (is (= "'foo" (flat/format-form '(quote foo)))))
  (testing "(quote :kw) form → ':kw"
    (is (= "':kw" (flat/format-form '(quote :kw)))))
  (testing "(quote 42) form → '42"
    (is (= "'42" (flat/format-form '(quote 42))))))

(deftest plain-form-quote-of-empty-list-renders-as-tick
  (testing "(quote ()) form → '() — bare paren on empty would lose the quote"
    (is (= "'()" (flat/format-form '(quote ()))))))

;; ---------------------------------------------------------------------------
;; AST-driven path: CljQuote rendering
;; ---------------------------------------------------------------------------

(deftest ast-bare-notation-renders-as-bare-paren
  (testing "CljQuote with :bare notation over a non-empty list → (...)"
    (let [list-node (nodes/->CljList
                      [(nodes/->CljSymbol "x" nil nil [])
                       (nodes/->CljSymbol "y" nil nil [])]
                      nil [] [])
          q (nodes/->CljQuote list-node nil [] :bare)]
      (is (= "(x y)" (flat/format-form q))))))

(deftest ast-tick-notation-over-list-canonicalises-to-bare-paren
  (testing ":tick over a non-empty list also renders as bare paren in m2clj mode"
    (let [list-node (nodes/->CljList
                      [(nodes/->CljSymbol "x" nil nil [])]
                      nil [] [])
          q (nodes/->CljQuote list-node nil [] :tick)]
      (is (= "(x)" (flat/format-form q))
          ":tick over list canonicalises — `'(x)` would mean double-quote in m2clj"))))

(deftest ast-tick-over-symbol-stays-tick
  (testing "'foo (CljQuote :tick over CljSymbol) → 'foo"
    (let [sym (nodes/->CljSymbol "foo" nil nil [])
          q (nodes/->CljQuote sym nil [] :tick)]
      (is (= "'foo" (flat/format-form q))))))

;; ---------------------------------------------------------------------------
;; :clj mode never uses bare-paren — Clojure has no such notation
;; ---------------------------------------------------------------------------

(deftest clj-mode-always-emits-tick
  (testing ":clj mode renders (quote (x y z)) as '(x y z)"
    (is (= "'(x y z)" (flat/format-clj ['(quote (x y z))]))))
  (testing ":clj mode renders (quote foo) as 'foo"
    (is (= "'foo" (flat/format-clj ['(quote foo)])))))

;; ---------------------------------------------------------------------------
;; Roundtrip: source → forms → m2clj-print → re-parse → forms
;; ---------------------------------------------------------------------------

(deftest source-roundtrip-bare-paren
  (let [cases ["(x y z)"
               "()"
               "(quote 1 2 3)"
               "+(1 2)"
               "f((a b) c)"
               "let([x 1] (+ x x))"
               "let([x 1] +(x x))"
               "(f(a b) c)"
               "((1 2) 3)"
               "f(() 1)"]]
    (doseq [src cases]
      (testing (str "round-trip: " src)
        (let [[printed reparsed forms] (roundtrip src)]
          (is (= forms reparsed)
              (str "form equality lost: src=" src
                   " printed=" printed
                   " reparsed=" reparsed)))))))

(deftest tick-over-bare-paren-roundtrips-as-double-quote
  (testing "user-written '(x y z) means double-quote in m2clj; print + reparse preserves the form"
    (let [[_ reparsed forms] (roundtrip "'(x y z)")]
      (is (= forms reparsed))
      (is (= [(list 'quote (list 'quote '(x y z)))] forms)
          "the form layer sees the double-quote unambiguously"))))

;; ---------------------------------------------------------------------------
;; The (quote 1 2 3) example — the canonical "this would be illegal in Clojure
;; but is fine as data in m2clj" case
;; ---------------------------------------------------------------------------

(deftest quote-symbol-as-data-roundtrips
  (testing "(quote 1 2 3) — illegal call in Clojure, valid data in m2clj"
    (let [[printed _ forms] (roundtrip "(quote 1 2 3)")]
      (is (= "(quote 1 2 3)" printed))
      (is (= '[(quote (quote 1 2 3))] forms)))))
