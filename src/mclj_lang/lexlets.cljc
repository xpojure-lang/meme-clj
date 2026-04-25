(ns mclj-lang.lexlets
  "Meme-lang's lexical layer. Meme inherits its lexical conventions
   wholesale from Clojure — M-expressions change syntax at the parser
   level (the `f(x)` call rule), not at the lexer level — so every
   entry here forwards to the shared `meme.tools.clj.lex` commons.
   This namespace exists to give mclj-lang a home for any future
   meme-specific lexical rule."
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
