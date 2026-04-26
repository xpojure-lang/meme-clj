(ns meme.tools.clj.ast.nodes
  "AST node taxonomy for the Clojure-surface tier.

  Twenty-five record types representing every Clojure form shape with
  notation, position, and trivia preserved as fields (not metadata).
  This is the lossless tier: tooling that needs round-trip fidelity,
  scoped refactoring, or position-attributed warnings consumes AST
  nodes directly.  The plain-form pipeline (`forms->m1clj`,
  `forms->clj`) is intentionally lossy — it sees plain Clojure values
  with no notation data.

  Field shapes:
    pos     — {:line :col :end-line :end-col :offset :end-offset :file}
              (nil on programmatically constructed nodes)
    trivia  — [{:type :comment|:whitespace|:discard, :raw \"...\"} ...]
              leading trivia, in source order
    raw     — preserved source text where escape spellings differ from
              the resolved value (numbers, strings, chars)

  All records implement the AstNode protocol, exposing `children` (a vec
  of child nodes in source order, empty for atomic literals) and
  `rebuild` (return a same-type node with new children substituted).")

(defprotocol AstNode
  (-children [n] "Vec of child AST nodes in source order. Empty for atomic literals.")
  (-rebuild [n new-children] "Return a same-type node with new children substituted."))

(defn children
  "Vec of child AST nodes in source order."
  [n]
  (-children n))

(defn rebuild
  "Return a same-type AST node with `new-children` substituted."
  [n new-children]
  (-rebuild n new-children))

;; ---------------------------------------------------------------------------
;; Atomic literals — no children.
;; ---------------------------------------------------------------------------

(defrecord CljSymbol [name ns pos trivia]
  AstNode
  (-children [_] [])
  (-rebuild [n _] n))

(defrecord CljKeyword [name ns auto-resolve? pos trivia]
  AstNode
  (-children [_] [])
  (-rebuild [n _] n))

(defrecord CljNumber [value raw pos trivia]
  AstNode
  (-children [_] [])
  (-rebuild [n _] n))

(defrecord CljString [value raw pos trivia]
  AstNode
  (-children [_] [])
  (-rebuild [n _] n))

(defrecord CljChar [value raw pos trivia]
  AstNode
  (-children [_] [])
  (-rebuild [n _] n))

(defrecord CljRegex [pattern pos trivia]
  AstNode
  (-children [_] [])
  (-rebuild [n _] n))

(defrecord CljNil [pos trivia]
  AstNode
  (-children [_] [])
  (-rebuild [n _] n))

(defrecord CljBool [value pos trivia]
  AstNode
  (-children [_] [])
  (-rebuild [n _] n))

;; ---------------------------------------------------------------------------
;; Collections — children-bearing.
;; ---------------------------------------------------------------------------

(defrecord CljList [children pos trivia close-trivia]
  AstNode
  (-children [_] children)
  (-rebuild [n cs] (assoc n :children (vec cs))))

(defrecord CljVector [children pos trivia close-trivia]
  AstNode
  (-children [_] children)
  (-rebuild [n cs] (assoc n :children (vec cs))))

(defrecord CljMap [pairs pos trivia close-trivia]
  AstNode
  (-children [_] (into [] cat pairs))
  (-rebuild [n cs] (assoc n :pairs (vec (partition-all 2 cs)))))

(defrecord CljSet [children pos trivia close-trivia]
  AstNode
  (-children [_] children)
  (-rebuild [n cs] (assoc n :children (vec cs))))

;; ---------------------------------------------------------------------------
;; Reader-macro nodes — sugar that survives round-trip.
;;
;; Each wraps a single inner `form`. The notation (quote, deref, var,
;; backtick, tilde, tilde-at, anon-fn, discard) is the node type itself,
;; — sugar form lives on the AST node, not in metadata.
;; ---------------------------------------------------------------------------

(defrecord CljQuote [form pos trivia]
  AstNode
  (-children [_] [form])
  (-rebuild [n [c]] (assoc n :form c)))

(defrecord CljDeref [form pos trivia]
  AstNode
  (-children [_] [form])
  (-rebuild [n [c]] (assoc n :form c)))

(defrecord CljVar [form pos trivia]
  AstNode
  (-children [_] [form])
  (-rebuild [n [c]] (assoc n :form c)))

(defrecord CljSyntaxQuote [form pos trivia]
  AstNode
  (-children [_] [form])
  (-rebuild [n [c]] (assoc n :form c)))

(defrecord CljUnquote [form pos trivia]
  AstNode
  (-children [_] [form])
  (-rebuild [n [c]] (assoc n :form c)))

(defrecord CljUnquoteSplicing [form pos trivia]
  AstNode
  (-children [_] [form])
  (-rebuild [n [c]] (assoc n :form c)))

(defrecord CljAnonFn [body arity pos trivia]
  AstNode
  (-children [_] [body])
  (-rebuild [n [c]] (assoc n :body c)))

(defrecord CljDiscard [form pos trivia]
  AstNode
  (-children [_] [form])
  (-rebuild [n [c]] (assoc n :form c)))

;; ---------------------------------------------------------------------------
;; Compound nodes.
;; ---------------------------------------------------------------------------

(defrecord CljTagged [tag form pos trivia]
  AstNode
  (-children [_] [form])
  (-rebuild [n [c]] (assoc n :form c)))

(defrecord CljReaderCond [pairs splicing? pos trivia]
  AstNode
  (-children [_] (into [] cat pairs))
  (-rebuild [n cs] (assoc n :pairs (vec (partition-all 2 cs)))))

(defrecord CljMeta [chain target pos trivia]
  AstNode
  (-children [_] (conj (vec chain) target))
  (-rebuild [n cs] (assoc n :chain (vec (butlast cs)) :target (last cs))))

(defrecord CljNamespacedMap [ns auto-resolve? inner pos trivia]
  AstNode
  (-children [_] [inner])
  (-rebuild [n [c]] (assoc n :inner c)))

;; ---------------------------------------------------------------------------
;; Top-level container.
;; ---------------------------------------------------------------------------

(defrecord CljRoot [children trailing-trivia pos]
  AstNode
  (-children [_] children)
  (-rebuild [n cs] (assoc n :children (vec cs))))

;; ---------------------------------------------------------------------------
;; Predicates & helpers
;; ---------------------------------------------------------------------------

(defn ast-node?
  "True if `x` participates in the AstNode protocol."
  [x]
  (satisfies? AstNode x))

(def ^:private notation-keys
  "Fields that capture how the source was spelled, not what it means.
  Stripped before `ast=` comparison so that `42` and `0x2A` are equal,
  identical symbols at different positions are equal, etc."
  #{:pos :trivia :close-trivia :trailing-trivia :raw})

(def ^:private child-bearing-keys
  "Fields holding child AST nodes. Compared recursively via `ast=`
  rather than directly so children's own notation fields don't leak in."
  #{:children :pairs :form :body :chain :target :inner})

(defn ast=
  "Structural equality ignoring notation (pos, trivia, raw source spelling).

  Two nodes are `ast=` if they have the same record type, matching
  semantic fields (name, value, tag, etc.), and matching children
  (compared recursively). Use this instead of `=` when comparing AST
  nodes for refactor / lint purposes — defrecord's `=` is too strict
  because it includes positional metadata."
  [a b]
  (and (= (type a) (type b))
       (let [strip #(apply dissoc % (concat notation-keys child-bearing-keys))
             ca (children a)
             cb (children b)]
         (and (= (strip a) (strip b))
              (= (count ca) (count cb))
              (every? identity (map ast= ca cb))))))
