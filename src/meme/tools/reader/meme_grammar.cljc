(ns meme.tools.reader.meme-grammar
  "Meme language grammar for the Pratt parser.

   Maps token types to parselets — the complete syntactic specification
   of M-expression syntax. Uses factory functions from the parser engine
   for common patterns, and custom parselets from meme-parselets for
   meme-specific constructs."
  (:require [meme.tools.pratt.parser :as pratt]
            [meme.tools.reader.meme-parselets :as mp]))

(def grammar
  "Meme language grammar: token types → parselets."
  {:nud
   {;; Atoms
    :symbol        (pratt/nud-atom :atom)
    :number        (pratt/nud-atom :atom)
    :string        (pratt/nud-atom :atom)
    :keyword       (pratt/nud-atom :atom)
    :char-literal  (pratt/nud-atom :atom)
    :regex         (pratt/nud-atom :atom)

    ;; Collections
    :open-bracket  (pratt/nud-delimited :vector :close-bracket)
    :open-brace    (pratt/nud-delimited :map :close-brace)
    :open-paren    (pratt/nud-empty-or-error :list :close-paren
                     "Bare parentheses not allowed — every (...) needs a head")

    ;; Prefix operators
    :quote            (pratt/nud-prefix :quote)
    :deref            (pratt/nud-prefix :deref)
    :syntax-quote     (pratt/nud-prefix :syntax-quote)
    :unquote          (pratt/nud-prefix :unquote)
    :unquote-splicing (pratt/nud-prefix :unquote-splicing)
    :meta             (pratt/nud-prefix-two :meta :meta :target)

    ;; Dispatch prefixes
    :var-quote       (pratt/nud-prefix :var-quote)
    :discard         (pratt/nud-prefix :discard)
    :hashtag-symbol  (pratt/nud-prefix :tagged)
    :open-set        (pratt/nud-delimited :set :close-brace)
    :open-anon-fn    (pratt/nud-delimited :anon-fn :close-paren)
    :namespaced-map  (pratt/nud-prefixed-delimited :namespaced-map :open-brace :close-brace)
    :reader-cond     (pratt/nud-prefixed-delimited :reader-cond :open-paren :close-paren
                                                   mp/reader-cond-extra)}

   :led
   [{:token-type :open-paren
     :bp 100
     :when mp/no-trivia?
     :fn (pratt/led-call :call :close-paren)}]})
