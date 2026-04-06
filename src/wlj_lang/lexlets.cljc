(ns wlj-lang.lexlets
  "Lexlets for wlj — delegates to meme.tools.lexer for common patterns.
   Only language-specific overrides here."
  (:require [meme.tools.lexer :as lexer]))

;; Re-export common lexlets
(def digit?             lexer/digit?)
(def ident-char?        lexer/ident-char?)
(def consume-number     lexer/consume-number)
(def consume-identifier lexer/consume-identifier)
(def consume-string     lexer/consume-string)
(def newline-consumer   lexer/newline-consumer)
(def block-comment-consumer lexer/block-comment-consumer)

;; wlj-specific: ident-start includes $
(defn ident-start? [ch]
  (or (lexer/ident-start? ch) (and ch (= ch \$))))

;; wlj-specific: comma is whitespace
(defn ws-consumer [engine]
  (lexer/ws-consumer engine (fn [ch] (or (= ch \space) (= ch \tab) (= ch \,)))))
