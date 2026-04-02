(ns meme.rewrite
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
   Returns bindings map or nil on failure.
   L14: WARNING — complexity is O(n^k) where k is the number of splice variables
   in the pattern. Patterns with more than one splice variable should be avoided
   for large inputs."
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

   Map patterns: keys are **literal anchors** (not pattern variables).
   `{:a ?v}` matches `{:a 42}` binding ?v=42, but `{?k ?v}` looks for
   a literal key named `?k` — it does NOT bind ?k as a variable.

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

     ;; map pattern — keys are literal anchors, values may contain variables.
     ;; Records are excluded (they are internal tagged types, not user data).
     (and (map? pattern) (not (record? pattern))
          (map? expr) (not (record? expr)))
     (when (= (count pattern) (count expr))
       (reduce-kv (fn [bindings pk pv]
                    (when bindings
                      (if-let [entry (find expr pk)]
                        (match-pattern pv (val entry) bindings)
                        nil)))
                  bindings
                  pattern))

     ;; set pattern — literal element membership.
     (and (set? pattern) (set? expr))
     (when (= (count pattern) (count expr))
       (reduce (fn [bindings p-elem]
                 (when bindings
                   (when (contains? expr p-elem)
                     bindings)))
               bindings
               pattern))

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

(defn- check-suspicious-vars!
  "L8/L14: warn about confusing pattern variable usage."
  [pattern rule-name]
  (when (sequential? pattern)
    (doseq [p pattern]
      ;; L8: ?&foo looks like a splice var but isn't — user probably meant ??foo
      (when (and (symbol? p) (str/starts-with? (name p) "?&"))
        (throw (ex-info (str "Pattern variable " p " in rule " (pr-str rule-name)
                             " starts with ?& — did you mean ?? for a splice variable?")
                        {:rule rule-name :var p})))
      (check-suspicious-vars! p rule-name))))

(defn make-rule
  "Create a rule from a pattern and replacement template.
   Optionally takes a guard function."
  ([rule-name pattern replacement]
   (check-suspicious-vars! pattern rule-name)
   {:name rule-name :pattern pattern :replacement replacement})
  ([rule-name pattern replacement guard]
   (check-suspicious-vars! pattern rule-name)
   {:name rule-name :pattern pattern :replacement replacement :guard guard}))

;; PT-F4: sentinel for "rule did not match" — distinguishes from nil as a valid
;; substitution result. Rules that produce nil (deletion) must not be confused
;; with "pattern didn't match".
(def ^:private no-match
  #?(:clj (Object.) :cljs #js {}))

(defn- no-match?
  "Is x the no-match sentinel?"
  [x]
  (identical? x no-match))

(defn- apply-rule*
  "Internal: try to apply a single rule. Returns result or no-match sentinel."
  [rule expr]
  (let [bindings (match-pattern (:pattern rule) expr)]
    (if bindings
      (if-let [guard (:guard rule)]
        (let [ok? (try (guard bindings)
                    (catch #?(:clj Exception :cljs :default) e
                      (throw (ex-info (str "Guard function failed for rule "
                                          (pr-str (:name rule)) ": "
                                          #?(:clj (.getMessage ^Exception e) :cljs (.-message e)))
                                     {:rule (:name rule) :expr expr :bindings bindings}
                                     e))))]
          (if ok? (substitute (:replacement rule) bindings) no-match))
        (substitute (:replacement rule) bindings))
      no-match)))

(defn apply-rule
  "Try to apply a single rule to an expression.
   Returns the rewritten expression, or nil if the rule doesn't match."
  [rule expr]
  (let [result (apply-rule* rule expr)]
    (when-not (no-match? result) result)))

(defn- apply-rules
  "Try each rule in order against an expression.
   Returns the first successful rewrite, or no-match sentinel if none match."
  [rules expr]
  (reduce
   (fn [_ rule]
     (let [result (apply-rule* rule expr)]
       (if (no-match? result)
         no-match
         (reduced result))))
   no-match
   rules))

;; ============================================================
;; Tree Walking Strategies
;; ============================================================

(defn rewrite-once
  "One bottom-up pass: try to rewrite each node, innermost first.
   Returns [changed? result]."
  [rules expr]
  (cond
    (sequential? expr)
    ;; first, rewrite children
    (let [was-list (list? expr)
          children (if was-list (vec expr) expr)
          rewritten-children (mapv (fn [child]
                                    (let [[_ r] (rewrite-once rules child)] r))
                                  children)
          rebuilt (if was-list
                    (with-meta (apply list rewritten-children) (meta expr))
                    (with-meta rewritten-children (meta expr)))
          ;; then try to rewrite this node
          result (apply-rules rules rebuilt)]
      (if (no-match? result)
        (let [changed (not= rebuilt expr)]
          [changed rebuilt])
        [true result]))

    (and (map? expr) (not (record? expr)))
    (let [rewritten (with-meta
                      (into {} (map (fn [[k v]]
                                      (let [[_ rk] (rewrite-once rules k)
                                            [_ rv] (rewrite-once rules v)]
                                        [rk rv]))
                                    expr))
                      (meta expr))
          result (apply-rules rules rewritten)]
      (if (no-match? result)
        [(not= rewritten expr) rewritten]
        [true result]))

    (set? expr)
    (let [rewritten (with-meta
                      (set (map (fn [el]
                                  (let [[_ r] (rewrite-once rules el)] r))
                                expr))
                      (meta expr))
          result (apply-rules rules rewritten)]
      (if (no-match? result)
        [(not= rewritten expr) rewritten]
        [true result]))

    ;; leaf node — try to rewrite
    :else
    (let [result (try (apply-rules rules expr)
                   (catch #?(:clj Exception :cljs :default) e
                     (throw (ex-info (str "Rewrite error on leaf: "
                                          #?(:clj (.getMessage ^Exception e) :cljs (.-message e)))
                                     {:expr expr :stage :rewrite-leaf} e))))]
      (if (no-match? result)
        [false expr]
        [true result]))))

(def ^:const default-max-iters
  "Default safety cap on rewrite iterations. This is a guard against
   non-terminating rule sets, not structural cycle detection. If a
   legitimate transformation needs more passes, pass a higher value
   as the max-iters argument."
  100)

(def ^:const default-max-size
  "M8: maximum expression tree size (node count) to prevent size-exploding rules."
  100000)

(defn- tree-size
  "Approximate node count of an expression tree."
  [expr]
  (cond
    (sequential? expr) (reduce + 1 (map tree-size expr))
    (map? expr) (reduce + 1 (mapcat (fn [[k v]] [(tree-size k) (tree-size v)]) expr))
    (set? expr) (reduce + 1 (map tree-size expr))
    :else 1))

(defn rewrite
  "Apply rules repeatedly (bottom-up) until fixed point or max iterations.
   Returns the final expression. Default max-iters is 100 — a safety cap,
   not true cycle detection. Override with the 3-arity form if a rule set
   needs more passes to converge.
   M8: also guards against size-exploding rules via tree-size budget."
  ([rules expr] (rewrite rules expr default-max-iters))
  ([rules expr max-iters]
   (loop [expr expr
          i 0]
     (when (>= i max-iters)
       (throw (ex-info "Rewrite did not reach fixed point (possible cycle)"
                       {:iterations max-iters :expr expr})))
     ;; M8: check size budget every 10 iterations to avoid O(n) counting overhead
     (when (and (pos? i) (zero? (mod i 10)))
       (let [size (tree-size expr)]
         (when (> size default-max-size)
           (throw (ex-info (str "Rewrite exceeded maximum expression size (" size " nodes)")
                           {:iterations i :size size :max-size default-max-size})))))
     (let [[changed? result] (rewrite-once rules expr)]
       (if changed?
         (recur result (inc i))
         result)))))

(defn rewrite-top
  "Apply rules only at the top level (no descent into children).
   Repeat until fixed point. Default max-iters is 100."
  ([rules expr] (rewrite-top rules expr default-max-iters))
  ([rules expr max-iters]
   (loop [expr expr
          i 0]
     (when (>= i max-iters)
       (throw (ex-info "Rewrite did not reach fixed point"
                       {:iterations max-iters :expr expr})))
     (let [result (apply-rules rules expr)]
       (if (no-match? result)
         expr
         (recur result (inc i)))))))

(defn rewrite-once-top
  "Try rules at top level only, return first match or original."
  [rules expr]
  (let [result (apply-rules rules expr)]
    (if (no-match? result) expr result)))

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
