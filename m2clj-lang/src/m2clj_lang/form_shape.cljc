(ns m2clj-lang.form-shape
  "Form-shape decomposition: semantic structure of Clojure special forms.

   Decomposes a call's args into a vector of [slot-name value] pairs that
   formatters can apply layout opinions to.  A decomposer returns nil when
   the form has no special structure — the printer falls back to plain-call
   rendering.

   Polymorphic over both inputs: args may be plain Clojure values
   (forms-with-metadata path) or `meme.tools.clj.ast.nodes/Clj*` records
   (AST tier path). The predicate helpers below abstract type checks so
   decomposers don't care which kind of value they receive.

   Slot vocabulary (the contract between form-shape and printer/style):

     :name         identifier being defined (defn, def, deftest, catch-binding)
     :doc          docstring
     :params       parameter vector
     :dispatch-val multimethod dispatch value; catch-exception class
     :dispatch-fn  multimethod dispatch fn; condp pred
     :test         if/when condition
     :expr         case/condp/threading target
     :bindings     let-style pair binding vector (rendered with columnar layout)
     :as-name      as-> binding name
     :clause       test/value pair (value is a [test value] 2-tuple)
     :arity        complete single-arity form ([params] body+)
     :default      case/condp default expression
     :body         ordinary body expression

   Slots are emitted in source order; style maps opine on slot names to
   choose head-line vs body placement and open-paren spacing."
  (:require [meme.tools.clj.ast.nodes :as nodes
             #?@(:cljs [:refer [CljSymbol CljString CljVector CljList]])])
  #?(:clj (:import [meme.tools.clj.ast.nodes
                    CljSymbol CljString CljVector CljList])))

;; ---------------------------------------------------------------------------
;; Polymorphic predicates: accept both plain Clojure values and Clj* AST nodes.
;; ---------------------------------------------------------------------------

(defn- ast-symbol? [x]
  (instance? CljSymbol x))

(defn- ast-string? [x]
  (instance? CljString x))

(defn- ast-vector? [x]
  (instance? CljVector x))

(defn- ast-list? [x]
  (instance? CljList x))

(defn- arg-string?
  "True for plain strings or CljString AST nodes."
  [x]
  (or (string? x) (ast-string? x)))

(defn- arg-vector?
  "True for plain vectors or CljVector AST nodes."
  [x]
  (or (vector? x) (ast-vector? x)))

(defn- arg-symbol?
  "True for plain symbols or CljSymbol AST nodes."
  [x]
  (or (symbol? x) (ast-symbol? x)))

(defn- arg-list-with-vector-head?
  "True for an arity form like `([x] body+)`. Handles both
   (seq? x) + (vector? (first x)) and CljList with CljVector first child."
  [x]
  (cond
    (ast-list? x)
    (boolean (some-> x :children first ast-vector?))

    (seq? x)
    (vector? (first x))

    :else false))

(defn- head-symbol-name
  "Extract the head symbol name as a Clojure symbol. Returns nil if the
   head isn't a symbol-typed AST or value."
  [head]
  (cond
    (symbol? head) head
    (ast-symbol? head) (if (:ns head)
                         (symbol (:ns head) (:name head))
                         (symbol (:name head)))
    :else nil))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- split-doc
  "If the first arg is a docstring followed by more args, return [doc rest].
   Otherwise [nil args]. Polymorphic: handles plain strings or CljString AST."
  [args]
  (if (and (arg-string? (first args)) (seq (rest args)))
    [(first args) (rest args)]
    [nil args]))

(defn- multi-arity?
  "True if x looks like an arity form: a list/seq starting with a params vector."
  [x]
  (arg-list-with-vector-head? x))

;; ---------------------------------------------------------------------------
;; Decomposers — one per special-form shape
;; ---------------------------------------------------------------------------

(defn- decompose-defn-like
  "defn / defn- / defmacro:
   (name doc? ([params] body+ | ([params] body+)+))"
  [args]
  (when (seq args)
    (let [[name & r] args
          [doc r] (split-doc r)
          head (cond-> [[:name name]] doc (conj [:doc doc]))]
      (cond
        (and (seq r) (multi-arity? (first r)))
        (into head (map (fn [a] [:arity a])) r)

        (and (seq r) (arg-vector? (first r)))
        (into (conj head [:params (first r)])
              (map (fn [b] [:body b]))
              (rest r))

        :else
        (into head (map (fn [b] [:body b])) r)))))

(defn- decompose-defmethod
  "(defmethod name dispatch-val [params] body+)"
  [args]
  (when (>= (count args) 2)
    (let [[name dispatch & r] args
          head [[:name name] [:dispatch-val dispatch]]]
      (cond
        (and (seq r) (arg-vector? (first r)))
        (into (conj head [:params (first r)])
              (map (fn [b] [:body b]))
              (rest r))

        (and (seq r) (multi-arity? (first r)))
        (into head (map (fn [a] [:arity a])) r)

        :else
        (into head (map (fn [b] [:body b])) r)))))

(defn- decompose-defmulti
  "(defmulti name doc? dispatch-fn & opts)"
  [args]
  (when (seq args)
    (let [[name & r] args
          [doc r] (split-doc r)
          head (cond-> [[:name name]] doc (conj [:doc doc]))]
      (if (seq r)
        (into (conj head [:dispatch-fn (first r)])
              (map (fn [o] [:body o]))
              (rest r))
        head))))

(defn- decompose-def
  "def / def- / defonce / ns / defprotocol: (name doc? body*)"
  [args]
  (when (seq args)
    (let [[name & r] args
          [doc r] (split-doc r)
          head (cond-> [[:name name]] doc (conj [:doc doc]))]
      (into head (map (fn [b] [:body b])) r))))

(defn- decompose-defrecord
  "(defrecord Name [fields] & body) / deftype"
  [args]
  (when (seq args)
    (let [[name & r] args
          head [[:name name]]]
      (if (and (seq r) (arg-vector? (first r)))
        (into (conj head [:params (first r)])
              (map (fn [b] [:body b]))
              (rest r))
        (into head (map (fn [b] [:body b])) r)))))

(defn- decompose-deftest
  "(deftest name body+)"
  [args]
  (when (seq args)
    (let [[name & body] args]
      (into [[:name name]] (map (fn [b] [:body b])) body))))

(defn- decompose-testing
  "(testing doc body+) — docstring is the head-line piece"
  [args]
  (when (seq args)
    (let [[doc & body] args]
      (into [[:doc doc]] (map (fn [b] [:body b])) body))))

(defn- decompose-bindings-body
  "(let [bindings] body+), (loop ...), (for ...), (doseq ...),
   (binding ...), (with-open ...), (if-let [b] ...), (when-let [b] ...), etc."
  [args]
  (when (seq args)
    (let [[bindings & body] args]
      (when (arg-vector? bindings)
        (into [[:bindings bindings]] (map (fn [b] [:body b])) body)))))

(defn- decompose-if-when
  "(if test then else?), (when test body+), (if-not ...), (when-not ...)"
  [args]
  (when (seq args)
    (let [[test & body] args]
      (into [[:test test]] (map (fn [b] [:body b])) body))))

(defn- decompose-case
  "(case expr (test value)+ default?)"
  [args]
  (when (seq args)
    (let [[expr & r] args]
      (into [[:expr expr]]
            (loop [acc [] xs r]
              (cond
                (empty? xs) acc
                (= 1 (count xs)) (conj acc [:default (first xs)])
                :else (recur (conj acc [:clause [(first xs) (second xs)]])
                             (drop 2 xs))))))))

(defn- decompose-cond
  "(cond test value ...)"
  [args]
  (cond
    (empty? args) nil
    (odd? (count args)) nil
    :else (mapv (fn [[t v]] [:clause [t v]]) (partition 2 args))))

(defn- decompose-condp
  "(condp pred expr (test result)+ default?)"
  [args]
  (when (>= (count args) 2)
    (let [[pred expr & r] args]
      (into [[:dispatch-fn pred] [:expr expr]]
            (loop [acc [] xs r]
              (cond
                (empty? xs) acc
                (= 1 (count xs)) (conj acc [:default (first xs)])
                :else (recur (conj acc [:clause [(first xs) (second xs)]])
                             (drop 2 xs))))))))

(defn- decompose-threading
  "-> / ->> / some-> / some->> / cond-> / cond->>: (-> expr form+)"
  [args]
  (when (seq args)
    (let [[expr & forms] args]
      (into [[:expr expr]] (map (fn [f] [:body f])) forms))))

(defn- decompose-as->
  "(as-> expr name form+)"
  [args]
  (when (>= (count args) 2)
    (let [[expr as-name & forms] args]
      (into [[:expr expr] [:as-name as-name]]
            (map (fn [f] [:body f]))
            forms))))

(defn- decompose-catch
  "(catch ExClass binding body+)"
  [args]
  (when (>= (count args) 2)
    (let [[cls bnd & body] args]
      (into [[:dispatch-val cls] [:name bnd]]
            (map (fn [b] [:body b]))
            body))))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(def registry
  "Built-in form-shape decomposers, keyed by head symbol."
  (merge
   (zipmap '[def def- defonce] (repeat decompose-def))
   (zipmap '[defn defn- defmacro] (repeat decompose-defn-like))
   {'defmethod     decompose-defmethod
    'defmulti      decompose-defmulti
    'defprotocol   decompose-def
    'defrecord     decompose-defrecord
    'deftype       decompose-defrecord
    'ns            decompose-def
    'deftest       decompose-deftest
    'testing       decompose-testing
    'case          decompose-case
    'cond          decompose-cond
    'condp         decompose-condp
    'catch         decompose-catch
    'as->          decompose-as->}
   (zipmap '[let loop for doseq binding
             with-open with-local-vars with-redefs
             if-let when-let if-some when-some]
           (repeat decompose-bindings-body))
   (zipmap '[if if-not when when-not]
           (repeat decompose-if-when))
   (zipmap '[-> ->> some-> some->> cond-> cond->>]
           (repeat decompose-threading))))

(defn decompose
  "Look up the decomposer for `head` in the given registry and apply it
   to `args`.  Returns a vector of [slot-name value] pairs in source
   order, or nil if no decomposer is registered or the decomposer
   rejects the shape.

   The registry is passed in explicitly so that each lang supplies its
   own form-shape vocabulary (see `m2clj-lang.form-shape/registry` for
   the m1clj-lang built-in).  When `registry` is nil — e.g. a bare call
   to `printer/to-doc` without a lang in play — every head is treated
   as having no shape.

   A registry may opt into structural fallback by carrying a metadata
   key `::fallback-fn` — a function `(fn [head args] → slots)`.  When
   the direct lookup misses, the fallback is consulted; this lets user
   macros with a defn- or let-like shape inherit layout without being
   registered.  See `with-structural-fallback`."
  [registry head args]
  (when (and registry (some? head))
    (let [head-sym (head-symbol-name head)
          args-vec (vec args)]
      (or (when-let [f (and head-sym (get registry head-sym))]
            (f args-vec))
          (when-let [fallback (::fallback-fn (meta registry))]
            (fallback head args-vec))))))

;; ---------------------------------------------------------------------------
;; Structural fallback — infer shape for unregistered heads
;; ---------------------------------------------------------------------------
;;
;; When a head isn't in the registry but its argument shape resembles a
;; known pattern, inference can recover the decomposition.  This covers
;; user macros like `my-defn`/`my-let`/DSL forms that mirror built-ins.
;;
;; Two patterns are recognized — the unambiguous ones.  Anything narrower
;; (test-then-else, expr-followed-by-clauses, ...) would match too many
;; plain calls and risk misrendering.

(defn- infer-structurally
  "Infer a decomposition from structural cues.  Recognizes:
     (HEAD name [params] body*) — defn-like  (second arg is a vector)
     (HEAD [bindings] body*)    — let-like   (first arg is a vector)
   Returns nil for any other shape."
  [_head args]
  (cond
    (and (>= (count args) 2)
         (arg-symbol? (first args))
         (arg-vector? (second args)))
    (decompose-defn-like args)

    (and (seq args)
         (arg-vector? (first args)))
    (decompose-bindings-body args)

    :else nil))

(defn with-structural-fallback
  "Return a registry (same entries) that uses structural inference when
   no entry matches a given head.  Users of the returned registry get
   automatic layout for user macros whose shape resembles `defn`
   (name + params) or `let` (bindings vector)."
  [registry]
  (vary-meta registry assoc ::fallback-fn infer-structurally))
