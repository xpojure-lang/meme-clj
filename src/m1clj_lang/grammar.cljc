(ns m1clj-lang.grammar
  "Meme language grammar spec.

   Maps characters to scanlets — the complete syntactic specification
   of M-expression syntax as data. Lexlets provide the scanning layer,
   parselets provide the compound constructs, and the parser engine
   provides generic factories."
  (:require [meme.tools.parser :as pratt]
            [meme.tools.lexer :as lexer]
            [m1clj-lang.lexlets :as lex]
            [m1clj-lang.parselets :as mp]
            [meme.tools.clj.forms :as forms]))

(declare grammar)

(defn with-grammar
  "Inject the m1clj grammar as `:grammar` on `opts` if the caller didn't
   supply one. Used by m1clj-lang's `api`, `run`, and `repl` shims so each
   stage entry-point can pass the grammar through to the generic toolkit
   without each caller threading it manually."
  [opts]
  (if (:grammar opts) opts (assoc (or opts {}) :grammar grammar)))

(def grammar
  "Meme language grammar: characters → scanlets."
  {:nud
   {;; Delimiters
    \(  (lexer/single-char-scanlet :open-paren
          (pratt/nud-empty-or-error :list \) :close-paren
            "Bare parentheses not allowed — every (...) needs a head"))
    \[  (lexer/delimited-scanlet :vector :open-bracket \] :close-bracket)
    \{  (lexer/delimited-scanlet :map :open-brace \} :close-brace)

    ;; Prefix operators
    \'  (lexer/single-char-scanlet :quote (pratt/nud-prefix :quote))
    \@  (lexer/single-char-scanlet :deref (pratt/nud-prefix :deref))
    \`  (lexer/single-char-scanlet :syntax-quote (pratt/nud-prefix :syntax-quote))
    \^  (lexer/single-char-scanlet :meta (pratt/nud-prefix-two :meta :meta :target))
    \~  mp/tilde-scanlet

    ;; Content atoms
    \\  (lexer/atom-scanlet :char-literal lex/consume-char-literal)
    \"  (lexer/atom-scanlet :string lex/consume-string)
    \:  (lexer/atom-scanlet :keyword lex/consume-keyword)

    ;; Dispatch
    \#  mp/dispatch-scanlet}

   :nud-pred
   [[(fn [ch _e] (lex/digit? ch))                          (lexer/atom-scanlet :number lex/consume-number)]
    [(fn [ch e] (mp/sign-followed-by-digit? e ch))          (lexer/atom-scanlet :number lex/consume-number)]
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
