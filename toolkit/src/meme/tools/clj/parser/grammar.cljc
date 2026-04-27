(ns meme.tools.clj.parser.grammar
  "Native Clojure-surface grammar spec.

   Sibling to `m1clj-lang.grammar` but uses Clojure's S-expression call
   rule: `(f x y)` is parsed as a list whose first child is the head.
   No M-expression call adjacency, no head-outside-parens. Otherwise
   identical: same dispatch sublanguage, same atomic conventions, same
   trivia handling.

   Lexlets come from `meme.tools.clj.lex` (the Clojure-surface commons);
   compound parselets (dispatch, tilde, sign-followed-by-digit) come
   from `meme.tools.clj.parser.parselets`."
  (:require [meme.tools.parser :as pratt]
            [meme.tools.lexer :as lexer]
            [meme.tools.clj.lex :as lex]
            [meme.tools.clj.parser.parselets :as cp]
            [meme.tools.clj.forms :as forms]))

(declare grammar)

(defn with-grammar
  "Inject the native-Clojure grammar as `:grammar` on `opts` if the caller
   didn't supply one. Mirrors `m1clj-lang.grammar/with-grammar`."
  [opts]
  (if (:grammar opts) opts (assoc (or opts {}) :grammar grammar)))

(def grammar
  "Native Clojure grammar: characters → scanlets."
  {:nud
   {;; Delimiters — list/vector/map all use the standard delimited parselet.
    \(  (lexer/delimited-scanlet :list :open-paren \) :close-paren)
    \[  (lexer/delimited-scanlet :vector :open-bracket \] :close-bracket)
    \{  (lexer/delimited-scanlet :map :open-brace \} :close-brace)

    ;; Prefix operators
    \'  (lexer/single-char-scanlet :quote (pratt/nud-prefix :quote))
    \@  (lexer/single-char-scanlet :deref (pratt/nud-prefix :deref))
    \`  (lexer/single-char-scanlet :syntax-quote (pratt/nud-prefix :syntax-quote))
    \^  (lexer/single-char-scanlet :meta (pratt/nud-prefix-two :meta :meta :target))
    \~  cp/tilde-scanlet

    ;; Content atoms
    \\  (lexer/atom-scanlet :char-literal lex/consume-char-literal)
    \"  (lexer/atom-scanlet :string lex/consume-string)
    \:  (lexer/atom-scanlet :keyword lex/consume-keyword)

    ;; Dispatch — native Clojure wraps `#(...)` body as an implicit list.
    \#  (cp/make-dispatch-scanlet {:anon-fn-body :list})}

   :nud-pred
   [[(fn [ch _e] (lex/digit? ch))                 (lexer/atom-scanlet :number lex/consume-number)]
    [(fn [ch e] (cp/sign-followed-by-digit? e ch)) (lexer/atom-scanlet :number lex/consume-number)]
    [(fn [ch _e] (lex/symbol-start? ch))           (lexer/atom-scanlet :symbol lex/consume-symbol)]]

   :trivia
   {\space   lex/ws-consumer
    \tab     lex/ws-consumer
    \,       lex/ws-consumer
    \newline lex/newline-consumer
    \return  lex/newline-consumer
    \;       lex/comment-consumer
    \uFEFF   lex/bom-consumer}

   :trivia-pred
   [[lex/whitespace-char? lex/ws-consumer]
    [lex/newline-char?    lex/newline-consumer]]

   ;; No `:led` rules — Clojure has no postfix call syntax. Lists are
   ;; primary forms via the `\(` nud entry above.
   :led []

   :max-depth forms/max-parse-depth})
