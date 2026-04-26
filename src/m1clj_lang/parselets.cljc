(ns m1clj-lang.parselets
  "Meme-specific parselets for the Pratt parser.

   Holds the M-expression call rule: adjacency detection plus the
   call-scanlet that pulls the head out from the parens. Everything
   else Clojure-surface (dispatch, tilde, sign-followed-by-digit,
   reader-cond extras) lives in `meme.tools.clj.parser.parselets` —
   shared with the native Clojure parser."
  (:require [meme.tools.parser :as pratt]))

;; ---------------------------------------------------------------------------
;; Predicates
;; ---------------------------------------------------------------------------

(defn adjacent?
  "Call predicate: ( is a call only when no trivia was accumulated since
   the last expression ended."
  [engine]
  (not (pratt/trivia-pending? engine)))

;; ---------------------------------------------------------------------------
;; Call scanlet
;; ---------------------------------------------------------------------------

(defn call-scanlet
  "Led call scanlet — the M-expression rule. Receives engine, lhs, and open-tok."
  [engine lhs open-tok]
  (let [[args close-tok] (pratt/parse-until engine \) :close-paren)]
    (pratt/cst :call {:head lhs :open open-tok :args args :close close-tok})))
