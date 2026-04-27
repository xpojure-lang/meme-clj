(ns m2clj-lang.grammar
  "m2clj language grammar spec.

   m2clj extends m1clj with one additional rule: bare parentheses (no
   adjacency to a preceding expression) read as a literal list — i.e.
   `(x y z)` lowers to `(quote (x y z))`. Calls still require adjacency
   (`f(x y)`), so the data/call distinction is structural at the reader
   layer and never depends on context or symbol resolution.

   The grammar reuses Clojure-surface lexical conventions and dispatch
   sublanguage from `meme.tools.clj.*`. Only the `\\(` nud differs from
   m1clj — m1clj rejects bare non-empty parens; m2clj reads them as
   `:bare-list` CST nodes."
  (:require [meme.tools.parser :as pratt]
            [meme.tools.lexer :as lexer]
            [m2clj-lang.lexlets :as lex]
            [m2clj-lang.parselets :as mp]
            [meme.tools.clj.parser.parselets :as cp]
            [meme.tools.clj.forms :as forms]))

(declare grammar)

(defn with-grammar
  "Inject the m2clj grammar as `:grammar` on `opts` if the caller didn't
   supply one. Mirrors `m1clj-lang.grammar/with-grammar`."
  [opts]
  (if (:grammar opts) opts (assoc (or opts {}) :grammar grammar)))

(def grammar
  "m2clj language grammar: characters → scanlets."
  {:nud
   {;; Delimiters — `(` is the m2clj-specific bit. Empty stays a plain
    ;; (empty) list; non-empty produces a :bare-list CST node so the AST
    ;; builder can lift it into a CljQuote with :bare notation.
    \(  (lexer/single-char-scanlet :open-paren mp/bare-paren-nud)
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

    ;; Dispatch
    \#  cp/dispatch-scanlet}

   :nud-pred
   [[(fn [ch _e] (lex/digit? ch))                          (lexer/atom-scanlet :number lex/consume-number)]
    [(fn [ch e] (cp/sign-followed-by-digit? e ch))          (lexer/atom-scanlet :number lex/consume-number)]
    [(fn [ch _e] (lex/symbol-start? ch))                    (lexer/atom-scanlet :symbol lex/consume-symbol)]]

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

   :led
   [{:char \( :bp 100 :open-type :open-paren :when mp/adjacent? :fn mp/call-scanlet}]

   :max-depth forms/max-parse-depth})
