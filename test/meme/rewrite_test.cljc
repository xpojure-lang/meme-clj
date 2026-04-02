(ns meme.rewrite-test
  "Tests for the term rewriting engine."
  (:require [clojure.test :refer [deftest is testing]]
            [meme.rewrite :as rw]))

;; ============================================================
;; Pattern Matching
;; ============================================================

(deftest match-pattern-variables
  (testing "?x matches anything"
    (is (= '{x 42} (rw/match-pattern '?x 42))))
  (testing "_ matches anything, no binding"
    (is (= {} (rw/match-pattern '_ 42))))
  (testing "list pattern with vars"
    (is (= '{x 1 y 2} (rw/match-pattern '(f ?x ?y) '(f 1 2)))))
  (testing "head mismatch fails"
    (is (nil? (rw/match-pattern '(f ?x) '(g 1)))))
  (testing "arity mismatch fails"
    (is (nil? (rw/match-pattern '(f ?x ?y) '(f 1)))))
  (testing "repeated var must bind consistently"
    (is (= '{x 1} (rw/match-pattern '(f ?x ?x) '(f 1 1)))))
  (testing "repeated var inconsistency fails"
    (is (nil? (rw/match-pattern '(f ?x ?x) '(f 1 2)))))
  (testing "var matches nested expression"
    (is (= '{x (a b)} (rw/match-pattern '(f ?x) '(f (a b))))))
  (testing "literal matches literal"
    (is (= {} (rw/match-pattern 42 42))))
  (testing "literal mismatch fails"
    (is (nil? (rw/match-pattern 42 43))))
  (testing "nested pattern matching"
    (is (= '{x 1 y 2} (rw/match-pattern '(+ (f ?x) ?y) '(+ (f 1) 2))))))

;; ============================================================
;; Splice Variables
;; ============================================================

(deftest match-pattern-splice
  (testing "??xs matches rest of list"
    (is (= '{xs [1 2 3]} (rw/match-pattern '(f ??xs) '(f 1 2 3)))))
  (testing "??xs matches empty"
    (is (= '{xs []} (rw/match-pattern '(f ??xs) '(f)))))
  (testing "?x then ??xs"
    (is (= '{x 1 xs [2 3]} (rw/match-pattern '(f ?x ??xs) '(f 1 2 3)))))
  (testing "??xs then ?y"
    (is (= '{xs [1 2] y 3} (rw/match-pattern '(f ??xs ?y) '(f 1 2 3))))))

;; ============================================================
;; Substitution
;; ============================================================

(deftest substitute-test
  (testing "simple var substitution"
    (is (= 42 (rw/substitute '?x '{x 42}))))
  (testing "list substitution"
    (is (= '(f 1 2) (rw/substitute '(f ?x ?y) '{x 1 y 2}))))
  (testing "splice substitution"
    (is (= '(f 1 2 3) (rw/substitute '(f ??xs) '{xs [1 2 3]}))))
  (testing "splice in middle"
    (is (= '(f 0 1 2 3 4) (rw/substitute '(f 0 ??xs 4) '{xs [1 2 3]}))))
  (testing "nested substitution"
    (is (= '(g (h 1) 2) (rw/substitute '(g (h ?x) ?y) '{x 1 y 2}))))
  (testing "set template substitution"
    (is (= #{1 2} (rw/substitute '#{?x ?y} '{x 1 y 2}))))
  (testing "map template substitution"
    (is (= {:a 1} (rw/substitute '{:a ?x} '{x 1})))))

;; ============================================================
;; Rule Application
;; ============================================================

(deftest apply-rule-test
  (testing "identity-plus rule"
    (let [r (rw/rule '(+ ?a 0) '?a)]
      (is (= '(* x y) (#'rw/apply-rule r '(+ (* x y) 0))))))
  (testing "rule doesn't match"
    (let [r (rw/rule '(* ?a 1) '?a)]
      (is (nil? (#'rw/apply-rule r '(+ x 1))))))
  (testing "swap rule"
    (let [r (rw/rule '(swap ?x ?y) '(swap ?y ?x))]
      (is (= '(swap b a) (#'rw/apply-rule r '(swap a b)))))))

;; ============================================================
;; Rewriting to Fixed Point
;; ============================================================

(deftest rewrite-fixed-point
  (let [rules [(rw/rule '(+ ?a 0) '?a)
               (rw/rule '(+ 0 ?a) '?a)
               (rw/rule '(* ?a 1) '?a)
               (rw/rule '(* 1 ?a) '?a)]]
    (testing "nested simplification"
      (is (= 'x (rw/rewrite rules '(+ (* x 1) 0)))))
    (testing "simplify (+ (* a b) 0)"
      (is (= '(* a b) (rw/rewrite rules '(+ (* a b) 0)))))
    (testing "simplify (* (+ a b) 1)"
      (is (= '(+ a b) (rw/rewrite rules '(* (+ a b) 1)))))
    (testing "deep nested"
      (is (= 'x (rw/rewrite rules '(+ 0 (* 1 (+ x 0)))))))))

;; ============================================================
;; Rewriting with Splice
;; ============================================================

(deftest rewrite-splice
  (let [rules [(rw/rule '(call ?f ??args) '(?f ??args))]]
    (testing "call rewrite with splice"
      (is (= '(println 1 2 3) (rw/rewrite rules '(call println 1 2 3)))))
    (testing "call rewrite operators"
      (is (= '(+ a b c) (rw/rewrite rules '(call + a b c)))))))

;; ============================================================
;; M-expression style rule
;; ============================================================

(deftest rewrite-m-expression
  (let [rules [(rw/rule '(m-call ?f (args ??a)) '(?f ??a))]]
    (testing "println(\"hello\")"
      (is (= '(println "hello")
             (rw/rewrite rules '(m-call println (args "hello"))))))
    (testing "+(1,2,3)"
      (is (= '(+ 1 2 3)
             (rw/rewrite rules '(m-call + (args 1 2 3))))))
    (testing "f(g(x)) nested"
      (is (= '(f (g x))
             (rw/rewrite rules '(m-call f (args (m-call g (args x))))))))))

;; ============================================================
;; Map and Set Pattern Matching
;; ============================================================

(deftest match-pattern-map-with-vars
  (testing "map pattern with variable value"
    (is (= '{x 42} (rw/match-pattern '{:k ?x} '{:k 42}))))
  (testing "map pattern with multiple vars"
    (is (= '{x 1 y 2} (rw/match-pattern '{:a ?x :b ?y} '{:a 1 :b 2})))))

(deftest match-pattern-map-nested
  (testing "nested expression in map value"
    (is (= '{x 1} (rw/match-pattern '{:k (f ?x)} '{:k (f 1)})))))

(deftest match-pattern-map-size-mismatch
  (testing "different sizes don't match"
    (is (nil? (rw/match-pattern '{:a ?x} '{:a 1 :b 2})))))

(deftest match-pattern-map-key-mismatch
  (testing "missing key doesn't match"
    (is (nil? (rw/match-pattern '{:a ?x} '{:b 1})))))

(deftest match-pattern-set-literal
  (testing "set of literals matches"
    (is (= {} (rw/match-pattern '#{:a :b} '#{:a :b}))))
  (testing "set size mismatch fails"
    (is (nil? (rw/match-pattern '#{:a :b} '#{:a :b :c})))))

(deftest match-pattern-set-mismatch
  (testing "different elements fail"
    (is (nil? (rw/match-pattern '#{:a :b} '#{:a :c})))))

(deftest rewrite-with-map-pattern
  (let [rules [(rw/rule '{:op :add :l ?a :r ?b} '(+ ?a ?b))]]
    (testing "map pattern rule rewrites to list"
      (is (= '(+ 1 2) (rw/rewrite rules '{:op :add :l 1 :r 2}))))
    (testing "map pattern inside list"
      (is (= '(f (+ 1 2)) (rw/rewrite rules '(f {:op :add :l 1 :r 2})))))))

;; ============================================================
;; Map and Set Traversal
;; ============================================================

(deftest rewrite-descends-into-map-values
  (let [rules [(rw/rule '(+ ?a 0) '?a)]]
    (testing "rule rewrites inside map values"
      (is (= '{:k x} (rw/rewrite rules '{:k (+ x 0)}))))
    (testing "rule rewrites deeply nested in map"
      (is (= '{:k {:j x}} (rw/rewrite rules '{:k {:j (+ x 0)}}))))))

(deftest rewrite-descends-into-map-keys
  (let [rules [(rw/rule '(+ ?a 0) '?a)]]
    (testing "rule rewrites a map key"
      (is (= '{x 1} (rw/rewrite rules '{(+ x 0) 1}))))))

(deftest rewrite-descends-into-sets
  (let [rules [(rw/rule '(+ ?a 0) '?a)]]
    (testing "rule rewrites inside set elements"
      (is (= '#{x y} (rw/rewrite rules '#{(+ x 0) y}))))))

(deftest rewrite-map-top-level-unchanged
  (let [rules [(rw/rule '(+ ?a 0) '?a)]]
    (testing "map itself does not match a sequential rule"
      (is (= '{:a 1 :b 2} (rw/rewrite rules '{:a 1 :b 2}))))))

;; ============================================================
;; Guard Rules
;; ============================================================

(deftest guard-rules
  (let [r (rw/make-rule 'pos-only '?x 'positive
                        (fn [bindings] (and (number? (get bindings 'x))
                                            (pos? (get bindings 'x)))))]
    (testing "guard passes"
      (is (= 'positive (#'rw/apply-rule r 42))))
    (testing "guard fails on negative"
      (is (nil? (#'rw/apply-rule r -1))))
    (testing "guard fails on symbol"
      (is (nil? (#'rw/apply-rule r 'x))))))

;; ============================================================
;; Cycle Detection
;; ============================================================

(deftest cycle-detection
  (let [rules [(rw/rule '(a) '(b))
               (rw/rule '(b) '(a))]]
    (testing "cycle detected and reported"
      (is (thrown-with-msg?
           #?(:clj Exception :cljs js/Error)
           #"cycle"
           (rw/rewrite rules '(a)))))))

;; ============================================================
;; Metadata Preservation
;; ============================================================

(deftest rewrite-once-preserves-list-metadata
  (testing "metadata on lists survives rewrite-once"
    (let [rules [(rw/rule '(nonexistent) '(also-nonexistent))]
          expr (with-meta (list 'a 'b) {:meme/sugar true :custom "val"})
          [_ result] (rw/rewrite-once rules expr)]
      (is (= {:meme/sugar true :custom "val"} (meta result)))))

  (testing "metadata on lists survives when children are rewritten"
    (let [rules [(rw/rule 'old 'new)]
          expr (with-meta (list 'old 'b) {:meme/sugar true})
          [changed? result] (rw/rewrite-once rules expr)]
      ;; children changed (old→new), but list metadata preserved
      (is changed?)
      (is (= {:meme/sugar true} (meta result))))))

(deftest rewrite-once-preserves-vector-metadata
  (let [rules [(rw/rule 'old 'new)]
        expr (with-meta ['old 'b] {:my-key 1})
        [_ result] (rw/rewrite-once rules expr)]
    (is (= {:my-key 1} (meta result)))))

(deftest rewrite-once-preserves-map-metadata
  (let [rules [(rw/rule 'old 'new)]
        expr (with-meta {'old 1} {:my-key 2})
        [_ result] (rw/rewrite-once rules expr)]
    (is (= {:my-key 2} (meta result)))))

(deftest rewrite-once-preserves-set-metadata
  (let [rules [(rw/rule 'old 'new)]
        expr (with-meta #{'old 'b} {:my-key 3})
        [_ result] (rw/rewrite-once rules expr)]
    (is (= {:my-key 3} (meta result)))))

;; ---------------------------------------------------------------------------
;; RT2-M8: Size-exploding rules should be caught by size budget.
;; ---------------------------------------------------------------------------

(deftest rewrite-size-budget
  (testing "cycling rule is caught by iteration cap"
    ;; Rule that cycles: (a) → (b) → (a) → ...
    (let [r1 (rw/rule 'a 'b)
          r2 (rw/rule 'b 'a)]
      (is (thrown-with-msg?
            #?(:clj Exception :cljs js/Error)
            #"(?i)(fixed point|cycle)"
            (rw/rewrite [r1 r2] 'a))))))

;; ---------------------------------------------------------------------------
;; RT2-L8: ?&foo pattern variables should be rejected with helpful error.
;; ---------------------------------------------------------------------------

(deftest suspicious-pattern-var-rejected
  (testing "?&args in pattern throws with hint about ??"
    (is (thrown-with-msg?
          #?(:clj Exception :cljs js/Error)
          #"\\?&"
          (rw/make-rule :test '(f ?&args) '(g ?&args))))))

;; ---------------------------------------------------------------------------
;; RT2-I2: Leaf rewrite error wraps with context.
;; ---------------------------------------------------------------------------

(deftest leaf-rewrite-error-wrapped
  (testing "guard exception on leaf includes context"
    (let [bad-rule (rw/make-rule :bomb '?x 'boom
                                 (fn [_] (throw (ex-info "boom" {}))))]
      (is (thrown-with-msg?
            #?(:clj Exception :cljs js/Error)
            #"Guard function failed"
            (rw/rewrite [bad-rule] 'x))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: nil replacement is a valid rule result, not "no match" (PT-F4)
;; Previously: (foo ?x) → nil was silently ignored because nil = no-match.
;; ---------------------------------------------------------------------------

(deftest nil-replacement-rule-applied
  (testing "rule producing nil is applied (not confused with no-match)"
    (let [r (rw/make-rule :to-nil '(foo ?x) nil)]
      (is (nil? (rw/rewrite [r] '(foo 42)))
          "rule should produce nil, not leave form unchanged")))
  (testing "rule producing false is applied"
    (let [r (rw/make-rule :to-false '(bar ?x) false)]
      (is (false? (rw/rewrite [r] '(bar 42)))
          "rule should produce false, not leave form unchanged")))
  (testing "non-matching rule still leaves form unchanged"
    (let [r (rw/make-rule :no-match '(baz ?x) nil)]
      (is (= '(quux 1) (rw/rewrite [r] '(quux 1)))
          "non-matching rule should not change the form"))))

;; ---------------------------------------------------------------------------
;; C1: rewrite-once must track changed? explicitly for all container types.
;; Previously: set/map/seq branches discarded the child changed? flag and
;; relied on (not= rewritten expr) which can false-negative for sets.
;; ---------------------------------------------------------------------------

(deftest rewrite-once-set-changed-flag
  (testing "changed? is true when set element is rewritten"
    (let [rules [(rw/rule 'old 'new)]
          [changed? result] (rw/rewrite-once rules #{:a 'old :b})]
      (is changed? "set child rewrite must propagate changed? flag")
      (is (= #{:a 'new :b} result))))
  (testing "changed? is false when no set element matches"
    (let [rules [(rw/rule 'old 'new)]
          [changed? result] (rw/rewrite-once rules #{:a :b :c})]
      (is (not changed?))
      (is (= #{:a :b :c} result))))
  (testing "changed? propagates from deeply nested set element"
    (let [rules [(rw/rule 'old 'new)]
          [changed? _] (rw/rewrite-once rules #{:a #{:b 'old}})]
      (is changed? "nested set child rewrite must propagate changed?"))))

;; ---------------------------------------------------------------------------
;; I2: Repeated splice variables in patterns must match the same subsequence.
;; Verifies the consistency check in match-seq (lines 78-80).
;; ---------------------------------------------------------------------------

(deftest match-pattern-repeated-splice
  (testing "repeated ??xs matches same subsequence"
    (is (= '{xs [1 2]} (rw/match-pattern '(f ??xs g ??xs) '(f 1 2 g 1 2)))))
  (testing "repeated ??xs rejects different subsequences"
    (is (nil? (rw/match-pattern '(f ??xs g ??xs) '(f 1 2 g 3 4)))))
  (testing "repeated ??xs with empty match"
    (is (= '{xs []} (rw/match-pattern '(f ??xs g ??xs) '(f g)))))
  (testing "repeated ??xs with single element"
    (is (= '{xs [42]} (rw/match-pattern '(a ??xs b ??xs c) '(a 42 b 42 c))))))
