(ns m2clj-lang.lexlets
  "m2clj's lexical layer. Like m1clj, m2clj inherits Clojure's lexical
   conventions wholesale — its surface change is at the parser level
   (the bare-paren-as-list-literal rule), not at the lexer level.
   This namespace exists to give m2clj-lang a home for any future
   m2clj-specific lexical rule."
  (:require [meme.tools.clj.lex :as clj-lex]))

;; Character predicates
(def digit?           clj-lex/digit?)
(def whitespace-char? clj-lex/whitespace-char?)
(def newline-char?    clj-lex/newline-char?)
(def symbol-start?    clj-lex/symbol-start?)
(def symbol-char?     clj-lex/symbol-char?)

;; Consume helpers
(def consume-string       clj-lex/consume-string)
(def consume-char-literal clj-lex/consume-char-literal)
(def consume-keyword      clj-lex/consume-keyword)
(def consume-number       clj-lex/consume-number)
(def consume-symbol       clj-lex/consume-symbol)

;; Trivia consumers
(def ws-consumer      clj-lex/ws-consumer)
(def newline-consumer clj-lex/newline-consumer)
(def comment-consumer clj-lex/comment-consumer)
(def bom-consumer     clj-lex/bom-consumer)
