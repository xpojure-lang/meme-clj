(ns meme.tools.clj.ast.equivalence-test
  "Phase A2 acceptance: prove that the AST tier round-trips through CST
  identically to the existing form-layer reader.

  For every input, we run two pipelines:
    Reference: source → parser → cst-reader/read-forms → forms
    AST:       source → parser → cst->ast → ast->forms → forms

  Both must produce the same forms, including metadata (`:m1clj/*` keys).
  Discrepancies fail the test — they reveal cases the AST tier can't yet
  represent, which become work items before A3 (the public API switch).

  Test inputs use m1clj syntax (every `(...)` has an adjacent head)."
  (:require [clojure.test :refer [deftest is testing]]
            [meme.tools.clj.ast.build :as ast-build]
            [meme.tools.clj.ast.lower :as ast-lower]
            [meme.tools.clj.cst-reader :as cst-reader]
            [meme.tools.parser :as parser]
            [m1clj-lang.grammar :as grammar])
  #?(:clj (:import [java.util.regex Pattern])))

(defn- parse [src]
  (parser/parse src grammar/grammar))

(defn- ref-forms
  ([src] (ref-forms src nil))
  ([src opts] (cst-reader/read-forms (parse src) opts)))

(defn- ast-forms
  ([src] (ast-forms src nil))
  ([src opts]
   (-> (parse src)
       (ast-build/cst->ast opts)
       (ast-lower/ast->forms opts))))

(defn- regex? [x]
  #?(:clj (instance? Pattern x) :cljs (instance? js/RegExp x)))

(defn- forms=
  "Structural + metadata equality, recursing into collections.
  Special-cases regex (Pattern equality is identity-based, so inner regex
  inside a vector would otherwise short-circuit `=` to false at the outer
  level) by comparing str representation."
  [a b]
  (cond
    (and (regex? a) (regex? b))
    (= (str a) (str b))

    (and (coll? a) (coll? b))
    (and (= (type a) (type b))
         (= (count a) (count b))
         (= (meta a) (meta b))
         (every? identity (map forms= a b)))

    :else
    (and (= a b)
         (= (meta a) (meta b)))))

(defn- check-equiv [src]
  (let [r (ref-forms src)
        a (ast-forms src)]
    (is (forms= r a)
        (str "form mismatch on: " (pr-str src)
             "\n  ref: " (pr-str r)
             "\n  ref-meta: " (pr-str (meta r))
             "\n  ast: " (pr-str a)
             "\n  ast-meta: " (pr-str (meta a))))))

;; ---------------------------------------------------------------------------
;; Atomic literals
;; ---------------------------------------------------------------------------

(deftest atoms-equiv
  (doseq [src ["nil" "true" "false"
               "42" "0x2A" "1.5e2" "-3" "0"
               "\\A" "\\u0041" "\\space"
               "\"hello\"" "\"hi\\nbye\""
               "#\"foo\\d+\""
               "foo" "my.ns/foo" "+"
               ":kw" ":ns/kw" ":/"
               "::foo"]]
    (testing src
      (check-equiv src))))

;; ---------------------------------------------------------------------------
;; Calls and lists
;; ---------------------------------------------------------------------------

(deftest calls-equiv
  (doseq [src ["()" "f()" "f(x)" "f(x y z)"
               "+(1 2 3)"
               "let([a 1 b 2] +(a b))"
               "if(true 1 2)"
               "defn(greet [name] println(name))"]]
    (testing src
      (check-equiv src))))

;; ---------------------------------------------------------------------------
;; Collections
;; ---------------------------------------------------------------------------

(deftest collections-equiv
  (doseq [src ["[]" "[1 2 3]" "[a [b c]]"
               "{}" "{:a 1 :b 2}"
               "#{}" "#{1 2 3}"
               "[1 \"two\" :three]"
               "{:nested {:k :v}}"]]
    (testing src
      (check-equiv src))))

;; ---------------------------------------------------------------------------
;; Reader sugar — m1clj syntax (no bare parens)
;; ---------------------------------------------------------------------------

(deftest sugar-equiv
  (doseq [src ["'x"
               "'foo(a b)"        ; '(foo a b)
               "@x"
               "@atom(1)"          ; @(atom 1) — but wait, is atom(1) valid?
               "#'foo"
               "`x"
               "`foo(~a ~@b)"
               "#(+ % 1)"
               "#(* %1 %2)"]]
    (testing src
      (check-equiv src))))

;; ---------------------------------------------------------------------------
;; Tagged / reader-cond / namespaced map
;; ---------------------------------------------------------------------------

(deftest dispatch-equiv
  (doseq [src ["#?(:clj 1 :cljs 2)"
               "#?@(:clj [1 2] :cljs [3 4])"
               "#:my.ns{:a 1 :b 2}"
               "#::{:a 1}"
               "#inst \"2024-01-01\""
               "#uuid \"00000000-0000-0000-0000-000000000000\""]]
    (testing src
      (check-equiv src))))

;; ---------------------------------------------------------------------------
;; Metadata
;; ---------------------------------------------------------------------------

(deftest meta-equiv
  (doseq [src ["^:private foo"
               "^String s"
               "^{:doc \"hi\"} x"
               "^:a ^:b ^:c x"]]
    (testing src
      (check-equiv src))))

;; ---------------------------------------------------------------------------
;; Trivia (comments, whitespace) on metadatable forms — m1clj syntax
;; ---------------------------------------------------------------------------

(deftest trivia-equiv
  (doseq [src [";; top-level comment\nfoo"
               "f(\n  ;; inner\n  bar)"
               "[1 ;; after\n2]"
               "{:k ;; comment\n:v}"]]
    (testing src
      (check-equiv src))))

;; ---------------------------------------------------------------------------
;; Discards — m1clj syntax
;; ---------------------------------------------------------------------------

(deftest discard-equiv
  (doseq [src ["#_x"
               "#_x y"
               "[1 #_2 3]"
               "f(#_arg x)"]]
    (testing src
      (check-equiv src))))

;; ---------------------------------------------------------------------------
;; Real-world snippets — proper m1clj syntax
;; ---------------------------------------------------------------------------

(deftest realistic-equiv
  (let [snippets
        ["ns(my.app :require([clojure.string :as str]))"
         "defn(consume [{:keys [a b]}] +(a b))"
         "->(coll filter(odd?) map(inc))"
         "cond(::done :a ::pending :b)"
         "defprotocol(P foo([this]))"]]
    (doseq [src snippets]
      (testing src
        (check-equiv src)))))
