(ns meme.alpha.rewrite
  "Term rewriting engine.

   Patterns:
     ?x        — match anything, bind to x
     ??x       — match zero or more (splice), bind to x
     (f ?x ?y) — match a list with head f, bind x and y
     _         — match anything, don't bind

   Rules:
     (defrule name pattern => replacement)

   Engine:
     (rewrite rules expr)         — apply rules bottom-up to fixed point
     (rewrite-once rules expr)    — one bottom-up pass
     (rewrite-top rules expr)     — top-level only to fixed point"
  (:require [clojure.string :as str]))

;; ============================================================
;; Pattern Matching
;; ============================================================

(defn pattern-var?
  "Is this a pattern variable like ?x"
  [x]
  (and (symbol? x)
       (str/starts-with? (name x) "?")
       (not (str/starts-with? (name x) "??"))
       (not= x '?)))

(defn splice-var?
  "Is this a splice variable like ??x (matches zero or more)"
  [x]
  (and (symbol? x)
       (str/starts-with? (name x) "??")))

(defn wildcard?
  "Is this the wildcard _ (matches anything, no binding)"
  [x]
  (= x '_))

(defn var-name
  "Extract the name from ?x or ??x"
  [x]
  (let [n (name x)]
    (cond
      (str/starts-with? n "??") (symbol (subs n 2))
      (str/starts-with? n "?") (symbol (subs n 1))
      :else x)))

(declare match-pattern)

(defn match-seq
  "Match a pattern sequence against an expression sequence.
   Handles splice variables (??x).
   Returns bindings map or nil on failure."
  [patterns exprs bindings]
  (cond
    ;; both empty — success
    (and (empty? patterns) (empty? exprs))
    bindings

    ;; patterns empty but exprs remain — fail
    (empty? patterns)
    nil

    ;; splice variable — try matching 0..n expressions
    (splice-var? (first patterns))
    (let [var (var-name (first patterns))
          rest-pats (rest patterns)]
      (loop [n 0]
        (when (<= n (count exprs))
          (let [taken (vec (take n exprs))
                remaining (drop n exprs)
                new-bindings (if (contains? bindings var)
                               (when (= (get bindings var) taken) bindings)
                               (assoc bindings var taken))]
            (or (when new-bindings
                  (match-seq rest-pats remaining new-bindings))
                (recur (inc n)))))))

    ;; exprs empty but patterns remain — fail (unless remaining are all splice vars)
    (empty? exprs)
    (if (splice-var? (first patterns))
      (match-seq patterns exprs bindings)
      nil)

    ;; normal: match first, recurse
    :else
    (when-let [new-bindings (match-pattern (first patterns) (first exprs) bindings)]
      (match-seq (rest patterns) (rest exprs) new-bindings))))

(defn match-pattern
  "Match a pattern against an expression.
   Returns a bindings map {symbol value} on success, nil on failure.

   Examples:
     (match-pattern '?x 42)           => {x 42}
     (match-pattern '(f ?x) '(f 1))   => {x 1}
     (match-pattern '_ anything)       => {}"
  ([pattern expr] (match-pattern pattern expr {}))
  ([pattern expr bindings]
   (cond
     ;; wildcard — matches anything
     (wildcard? pattern)
     bindings

     ;; pattern variable — bind or check consistency
     (pattern-var? pattern)
     (let [var (var-name pattern)]
       (if (contains? bindings var)
         (when (= (get bindings var) expr) bindings)
         (assoc bindings var expr)))

     ;; both are sequential and same type — match element by element.
     ;; List patterns match lists/seqs, vector patterns match vectors.
     (and (sequential? pattern) (sequential? expr)
          (= (vector? pattern) (vector? expr)))
     (match-seq (vec pattern) (vec expr) bindings)

     ;; literal equality
     (= pattern expr)
     bindings

     ;; no match
     :else
     nil)))

;; ============================================================
;; Substitution
;; ============================================================

(defn substitute
  "Replace pattern variables in template with values from bindings.
   Handles splice variables — ??x splices its seq into the parent list."
  [template bindings]
  (cond
    ;; pattern variable — replace
    (pattern-var? template)
    (let [var (var-name template)]
      (get bindings var template))

    ;; splice variable at top level — just return the value
    (splice-var? template)
    (let [var (var-name template)]
      (get bindings var []))

    ;; sequential — rebuild, handling splices
    (sequential? template)
    (let [was-list (list? template)
          result (reduce
                  (fn [acc item]
                    (if (splice-var? item)
                      (let [var (var-name item)
                            vals (get bindings var [])]
                        (into acc vals))
                      (conj acc (substitute item bindings))))
                  []
                  template)]
      (if was-list
        (apply list result)
        result))

    ;; map — substitute keys and values
    (map? template)
    (into {} (map (fn [[k v]]
                    [(substitute k bindings)
                     (substitute v bindings)])
                  template))

    ;; literal — pass through
    :else
    template))

;; ============================================================
;; Rule Application
;; ============================================================

(defn make-rule
  "Create a rule from a pattern and replacement template.
   Optionally takes a guard function."
  ([rule-name pattern replacement]
   {:name rule-name :pattern pattern :replacement replacement})
  ([rule-name pattern replacement guard]
   {:name rule-name :pattern pattern :replacement replacement :guard guard}))

(defn apply-rule
  "Try to apply a single rule to an expression.
   Returns the rewritten expression, or nil if the rule doesn't match."
  [rule expr]
  (when-let [bindings (match-pattern (:pattern rule) expr)]
    (if-let [guard (:guard rule)]
      (when (guard bindings)
        (substitute (:replacement rule) bindings))
      (substitute (:replacement rule) bindings))))

(defn apply-rules
  "Try each rule in order against an expression.
   Returns the first successful rewrite, or nil if none match."
  [rules expr]
  (reduce
   (fn [_ rule]
     (when-let [result (apply-rule rule expr)]
       (reduced result)))
   nil
   rules))

;; ============================================================
;; Tree Walking Strategies
;; ============================================================

(defn rewrite-once
  "One bottom-up pass: try to rewrite each node, innermost first.
   Returns [changed? result]."
  [rules expr]
  (if (sequential? expr)
    ;; first, rewrite children
    (let [was-list (list? expr)
          children (if was-list (vec expr) expr)
          rewritten-children (mapv (fn [child]
                                    (let [[_ r] (rewrite-once rules child)] r))
                                  children)
          rebuilt (if was-list
                    (apply list rewritten-children)
                    rewritten-children)
          ;; then try to rewrite this node
          result (apply-rules rules rebuilt)]
      (if result
        [true result]
        (let [changed (not= rebuilt expr)]
          [changed rebuilt])))
    ;; leaf node — try to rewrite
    (if-let [result (apply-rules rules expr)]
      [true result]
      [false expr])))

(defn rewrite
  "Apply rules repeatedly (bottom-up) until fixed point or max iterations.
   Returns the final expression."
  ([rules expr] (rewrite rules expr 100))
  ([rules expr max-iters]
   (loop [expr expr
          i 0
          seen #{}]
     (when (> i max-iters)
       (throw (ex-info "Rewrite did not reach fixed point"
                       {:iterations max-iters :expr expr})))
     (when (contains? seen expr)
       (throw (ex-info "Rewrite cycle detected"
                       {:expr expr :iteration i})))
     (let [[changed? result] (rewrite-once rules expr)]
       (if changed?
         (recur result (inc i) (conj seen expr))
         result)))))

(defn rewrite-top
  "Apply rules only at the top level (no descent into children).
   Repeat until fixed point."
  ([rules expr] (rewrite-top rules expr 100))
  ([rules expr max-iters]
   (loop [expr expr
          i 0]
     (when (> i max-iters)
       (throw (ex-info "Rewrite did not reach fixed point"
                       {:iterations max-iters :expr expr})))
     (if-let [result (apply-rules rules expr)]
       (recur result (inc i))
       expr))))

(defn rewrite-once-top
  "Try rules at top level only, return first match or original."
  [rules expr]
  (or (apply-rules rules expr) expr))

;; ============================================================
;; Rule DSL helpers
;; ============================================================

(defn rule
  "Shorthand: (rule '(+ ?a 0) '?a)"
  ([pattern replacement]
   (make-rule (gensym "rule_") pattern replacement))
  ([pattern replacement guard]
   (make-rule (gensym "rule_") pattern replacement guard)))

#?(:clj
   (defmacro defrule
     "Define a named rule.
      (defrule identity-plus (+ ?a 0) => ?a)"
     [rule-name pattern _ replacement]
     `(def ~rule-name (make-rule '~rule-name '~pattern '~replacement))))

#?(:clj
   (defmacro defrule-guard
     "Define a named rule with a guard.
      (defrule-guard positive-check ?x => :pos (fn [b] (pos? (b 'x))))"
     [rule-name pattern _ replacement guard]
     `(def ~rule-name (make-rule '~rule-name '~pattern '~replacement ~guard))))

#?(:clj
   (defmacro ruleset
     "Define a vector of rules.
      (ruleset
        (+ ?a 0) => ?a
        (* ?a 1) => ?a)"
     [& forms]
     (let [rules (partition 3 forms)]
       `(vec (list ~@(map (fn [[pattern _ replacement]]
                            `(rule '~pattern '~replacement))
                          rules))))))
