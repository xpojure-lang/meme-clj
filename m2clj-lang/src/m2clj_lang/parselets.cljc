(ns m2clj-lang.parselets
  "m2clj-specific parselets for the Pratt parser.

   m2clj is a strict superset of m1clj at the surface level:

   • Adjacency rule (inherited from m1clj): `f(x y)` parses as `(f x y)`.
     A `(` immediately following an expression (no trivia between) is a
     postfix call applied to that expression. Implemented as the led rule
     in `grammar` plus `adjacent?` and `call-scanlet` here.

   • Bare-paren rule (new in m2clj): `(x y z)` with no adjacency parses
     as the literal list `(quote (x y z))`. Empty `()` stays the empty
     list (no quote applied — there's nothing to quote). Implemented as
     the `\\(` nud in `grammar` via `bare-paren-nud` here.

   Other Clojure-surface dispatch (`#?`, tilde, sign-followed-by-digit)
   is reused unchanged from `meme.tools.clj.parser.parselets`."
  (:require [meme.tools.parser :as pratt]))

;; ---------------------------------------------------------------------------
;; Call (adjacency) — same rule as m1clj
;; ---------------------------------------------------------------------------

(defn adjacent?
  "Call predicate: ( is a call only when no trivia was accumulated since
   the last expression ended."
  [engine]
  (not (pratt/trivia-pending? engine)))

(defn call-scanlet
  "Led call scanlet — the M-expression rule. Receives engine, lhs, and open-tok."
  [engine lhs open-tok]
  (let [[args close-tok] (pratt/parse-until engine \) :close-paren)]
    (pratt/cst :call {:head lhs :open open-tok :args args :close close-tok})))

;; ---------------------------------------------------------------------------
;; Bare-paren-as-list — new in m2clj
;; ---------------------------------------------------------------------------
;;
;; The nud for `(`. After the open-paren token has been produced by the
;; calling scanlet, this function consumes the body up to `)` and decides:
;;
;;   • empty body         → CST `:list` (the empty list — no quote applied)
;;   • non-empty body     → CST `:bare-list` (lifts to (quote (...)) at AST)
;;
;; The CST node distinction lets the AST builder produce a `CljQuote` with
;; `:notation :bare` for non-empty bare parens, while keeping the empty
;; `()` as a plain `CljList` (semantics: empty list, no quote).

(defn bare-paren-nud
  "m2clj's `(` nud. Empty `()` → `:list`. Non-empty → `:bare-list`."
  [engine open-tok]
  (let [[children close-tok] (pratt/parse-until engine \) :close-paren)]
    (if (empty? children)
      (pratt/cst :list      {:open open-tok :close close-tok})
      (pratt/cst :bare-list {:open open-tok :children children :close close-tok}))))
