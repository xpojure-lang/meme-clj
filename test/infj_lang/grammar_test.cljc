(ns infj-lang.grammar-test
  "Tests for infj-lang: infix operators, precedence, grouping, word-
   operator boundaries, and interaction with meme syntax."
  (:require [clojure.test :refer [deftest is testing]]
            [infj-lang.api :as infj]))

(defn- parse-one [src] (first (infj/infj->forms src)))

;; ---------------------------------------------------------------------------
;; Atoms still work (everything meme parses, infj parses)
;; ---------------------------------------------------------------------------

(deftest atoms-pass-through
  (is (= 42 (parse-one "42")))
  (is (= 'foo (parse-one "foo")))
  (is (= :k (parse-one ":k")))
  (is (= "hi" (parse-one "\"hi\"")))
  (is (= [1 2 3] (parse-one "[1 2 3]")))
  (is (= {:a 1} (parse-one "{:a 1}"))))

;; ---------------------------------------------------------------------------
;; Binary infix operators
;; ---------------------------------------------------------------------------

(deftest arithmetic-operators
  (is (= '(+ 1 2) (parse-one "1 + 2")))
  (is (= '(- 5 3) (parse-one "5 - 3")))
  (is (= '(* 2 3) (parse-one "2 * 3")))
  (is (= '(/ 10 2) (parse-one "10 / 2")))
  (is (= '(mod 10 3) (parse-one "10 mod 3"))))

(deftest mod-shares-precedence-with-multiply
  (is (= '(+ 1 (mod 10 3)) (parse-one "1 + 10 mod 3")))
  (is (= '(mod (* 2 10) 3) (parse-one "2 * 10 mod 3"))))

(deftest comparison-operators
  (is (= '(< 1 2) (parse-one "1 < 2")))
  (is (= '(> 3 1) (parse-one "3 > 1")))
  (is (= '(<= 1 1) (parse-one "1 <= 1")))
  (is (= '(>= 2 2) (parse-one "2 >= 2"))))

(deftest equality-operators
  (is (= '(= 1 1) (parse-one "1 = 1")))
  (is (= '(not= 1 2) (parse-one "1 not= 2"))))

(deftest boolean-operators
  (is (= '(and x y) (parse-one "x and y")))
  (is (= '(or x y) (parse-one "x or y")))
  (is (= '(not x) (parse-one "not x"))))

;; ---------------------------------------------------------------------------
;; Named pipeline `|name|>` — lowers chains to a single flat `let`
;; ---------------------------------------------------------------------------

(deftest named-pipeline-basic
  (is (= '(let [n x] (f n))     (parse-one "x |n|> f(n)")))
  (is (= '(let [v 42] (+ v 1))  (parse-one "42 |v|> v + 1"))))

(deftest named-pipeline-chain-flattens
  (testing "chained pipes collapse into a single `let` with sequential rebindings"
    (is (= '(let [n x n (f n)] (g n))
           (parse-one "x |n|> f(n) |n|> g(n)")))
    (is (= '(let [a x b (f a)] (g b))
           (parse-one "x |a|> f(a) |b|> g(b)")))
    (is (= '(let [xs coll xs (filter pred xs)] (count xs))
           (parse-one "coll |xs|> filter(pred xs) |xs|> count(xs)")))))

(deftest named-pipeline-has-lowest-precedence
  (testing "LHS of |n|> captures the whole boolean/arithmetic expression before it"
    (is (= '(let [n (or x y)] (f n))  (parse-one "x or y |n|> f(n)")))
    (is (= '(let [n (+ 1 2)] (* n 3)) (parse-one "1 + 2 |n|> n * 3")))))

(deftest named-pipeline-name-scoped-to-rhs
  (testing "the name in `|n|>` becomes the binding symbol in the emitted `let`"
    (is (= '(let [k m] (get m k))  (parse-one "m |k|> get(m k)")))))

(deftest pipe-non-match-falls-through-to-symbol
  (testing "`|foo|` without a closing `|>` reads as a normal Clojure symbol"
    (is (= '[x |foo|] (infj/infj->forms "x |foo|")))
    (is (= '[|foo|]   (infj/infj->forms "|foo|")))))

(deftest pipe-slot-accepts-map-destructure
  (is (= '(let [{:keys [a b]} m] (+ a b))
         (parse-one "m |{:keys [a b]}|> +(a b)"))))

(deftest pipe-slot-accepts-vector-destructure
  (is (= '(let [[x y] pair] (+ x y))
         (parse-one "pair |[x y]|> +(x y)"))))

(deftest pipe-slot-accepts-mixed-chain
  (testing "a single chain can mix symbol slots and destructure slots"
    (is (= '(let [n x {:keys [a b]} (f n)] (+ a b))
           (parse-one "x |n|> f(n) |{:keys [a b]}|> +(a b)")))))

(deftest pipe-slot-handles-destructure-with-nested-brackets
  (testing "slot balance tracking lets nested `[...]` inside `{...}` work"
    (is (= '(let [{:keys [a b c]} m] (+ a b c))
           (parse-one "m |{:keys [a b c]}|> +(a b c)")))))

(deftest pipe-slot-rejects-stray-pipe-at-depth-zero
  (testing "`x |foo| bar` — no `|>` ahead at depth 0, falls through to symbols"
    (is (= '[x |foo| bar] (infj/infj->forms "x |foo| bar")))))

;; ---------------------------------------------------------------------------
;; Nested pipelines — inner chain is bounded inside maps/vectors/calls
;; ---------------------------------------------------------------------------

(deftest nested-pipeline-inside-map-value
  (testing "inner pipe chain inside a map value flattens independently"
    (is (= '(let [x (source)
                  {:keys [simple complex other]}
                  {:simple (f x)
                   :complex (let [y x z (parse y) w (validate z)] (enrich w))
                   :other   (g x)}]
              (combine simple complex other))
           (parse-one
             "source() |x|> {:simple f(x)
                             :complex x |y|> parse(y) |z|> validate(z) |w|> enrich(w)
                             :other g(x)}
              |{:keys [simple complex other]}|> combine(simple complex other)")))))

;; ---------------------------------------------------------------------------
;; Precedence
;; ---------------------------------------------------------------------------

(deftest multiplicative-binds-tighter-than-additive
  (is (= '(+ 1 (* 2 3)) (parse-one "1 + 2 * 3")))
  (is (= '(+ (* 2 3) 1) (parse-one "2 * 3 + 1"))))

(deftest additive-binds-tighter-than-comparison
  (is (= '(< (+ 1 2) 5) (parse-one "1 + 2 < 5"))))

(deftest comparison-binds-tighter-than-equality
  (is (= '(= (< 1 2) true) (parse-one "1 < 2 = true"))))

(deftest equality-binds-tighter-than-and
  (is (= '(and (= 1 1) (= 2 2)) (parse-one "1 = 1 and 2 = 2"))))

(deftest and-binds-tighter-than-or
  (testing "`a or b and c` → `(or a (and b c))`"
    (is (= '(or x (and y z)) (parse-one "x or y and z"))))
  (testing "`a and b or c` → `(or (and a b) c)`"
    (is (= '(or (and x y) z) (parse-one "x and y or z")))))

(deftest not-binds-tighter-than-and
  (is (= '(and (not x) y) (parse-one "not x and y"))))

(deftest not-binds-looser-than-comparison-and-equality
  (testing "`not` scoops up a comparison or equality RHS"
    (is (= '(not (= x y))  (parse-one "not x = y")))
    (is (= '(not (< x y))  (parse-one "not x < y")))))

(deftest left-associative
  (testing "same-precedence ops associate left to right"
    (is (= '(- (+ 1 2) 3) (parse-one "1 + 2 - 3")))
    (is (= '(/ (* 10 2) 5) (parse-one "10 * 2 / 5")))
    (is (= '(and (and a b) c) (parse-one "a and b and c")))
    (is (= '(or (or a b) c) (parse-one "a or b or c")))))

;; ---------------------------------------------------------------------------
;; Word boundaries
;; ---------------------------------------------------------------------------

(deftest word-operators-require-trailing-boundary
  (testing "identifiers that merely start with a word operator stay symbols"
    (is (= '(+ andrew 1) (parse-one "andrew + 1")))
    (is (= '(+ orbital 1) (parse-one "orbital + 1")))
    (is (= '(+ note 1) (parse-one "note + 1")))
    (is (= '(+ modern 1) (parse-one "modern + 1")))))

(deftest bare-word-operator-is-still-a-symbol
  (testing "`and` / `or` alone at top level read as symbols"
    (is (= 'and (parse-one "and")))
    (is (= 'or (parse-one "or")))))

;; ---------------------------------------------------------------------------
;; Grouping
;; ---------------------------------------------------------------------------

(deftest parens-group-single-expression
  (is (= 42 (parse-one "(42)")))
  (is (= '(+ 1 2) (parse-one "(1 + 2)")))
  (is (= '(* (+ 1 2) 3) (parse-one "(1 + 2) * 3"))))

(deftest empty-parens-are-empty-list
  (is (= '() (parse-one "()"))))

(deftest bare-multi-expression-parens-error
  (testing "`(a b)` is not valid — grouping expects a single expression"
    (is (thrown? #?(:clj Exception :cljs :default)
                 (parse-one "(1 2)")))))

;; ---------------------------------------------------------------------------
;; Interaction with meme syntax
;; ---------------------------------------------------------------------------

(deftest infix-inside-call
  (is (= '(f (+ 1 2)) (parse-one "f(1 + 2)")))
  (is (= '(vec [(+ 1 2) (* 3 4)]) (parse-one "vec([1 + 2 3 * 4])"))))

(deftest call-inside-infix
  (is (= '(+ (f 1) (g 2)) (parse-one "f(1) + g(2)")))
  (is (= '(* (count xs) 2) (parse-one "count(xs) * 2"))))

(deftest negative-literals-still-work
  (testing "meme's nud-pred for -digit keeps parsing negative numbers"
    (is (= '(+ -5 3) (parse-one "-5 + 3")))
    (is (= '(+ 1 -5) (parse-one "1 + -5")))))

(deftest reader-sugar-works
  (is (= '(+ (quote x) (quote y)) (parse-one "'x + 'y")))
  (is (= '(+ (clojure.core/deref a) (clojure.core/deref b)) (parse-one "@a + @b"))))

(deftest vectors-and-maps-mixed-with-infix
  (is (= '(+ [1 2] [3 4]) (parse-one "[1 2] + [3 4]"))))

;; ---------------------------------------------------------------------------
;; Multi-form sources
;; ---------------------------------------------------------------------------

(deftest multiple-top-level-forms
  (is (= '[(def x 10) (* x 2)]
         (infj/infj->forms "def(x 10)\nx * 2"))))

;; ---------------------------------------------------------------------------
;; infj->clj
;; ---------------------------------------------------------------------------

(deftest to-clj-produces-clojure-syntax
  (is (= "(+ 1 2)" (infj/infj->clj "1 + 2")))
  (is (= "(+ (f 1) (g 2))" (infj/infj->clj "f(1) + g(2)")))
  (is (= "(and x y)" (infj/infj->clj "x and y"))))

;; ---------------------------------------------------------------------------
;; Registry dispatch
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest lang-registered
     (testing ":infj is registered and .infj extension resolves to it"
       (let [reg    (requiring-resolve 'meme.registry/resolve-lang)
             by-ext (requiring-resolve 'meme.registry/resolve-by-extension)]
         (is (some? (reg :infj)))
         (let [[lang-kw lang-map] (by-ext ".infj")]
           (is (= :infj lang-kw))
           (is (some? (:format lang-map))))))))

;; ---------------------------------------------------------------------------
;; run-string (JVM only)
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest run-string-evaluates-infix
     (let [run-str (requiring-resolve 'infj-lang.run/run-string)]
       (is (= 3 (run-str "1 + 2")))
       (is (= 20 (run-str "def(x 10) x * 2")))
       (is (= :yes (run-str "if(1 < 2 :yes :no)")))
       (is (= true (run-str "1 = 1 and 2 < 3")))
       (is (= false (run-str "not (1 = 1)"))))))

#?(:clj
   (deftest run-string-evaluates-named-pipeline
     (let [run-str (requiring-resolve 'infj-lang.run/run-string)]
       (testing "single-stage pipeline"
         (is (= 11 (run-str "def(x 10) x |n|> n + 1"))))
       (testing "chained pipeline with same name"
         (is (= 26 (run-str "5 |n|> n * n |n|> n + 1"))))
       (testing "chained pipeline with different names"
         (is (= 12 (run-str "def(xs [1 2 3 4 5]) xs |v|> filter(fn([n] n > 2) v) |v|> reduce(+ v)")))))))
