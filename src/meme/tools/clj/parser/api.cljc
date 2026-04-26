(ns meme.tools.clj.parser.api
  "Public entry points for the native Clojure parser.

   The native parser is a sibling of m1clj's: same engine, same lex
   layer, same AST builder and lowering pipeline — only the call rule
   and `#(...)` body shape differ. It exists so `.clj` source can
   round-trip losslessly through the AST tier, on every platform,
   without depending on `clojure.core/read-string`.

   Three surfaces, each adding lowering on top of the previous:

     `parse-string`  → CST (lossless tokens, raw parser output)
     `clj->ast`      → CljRoot AST (parse + cst→ast lift)
     `clj->forms`    → vector of plain Clojure forms (parse + ast→forms)

   The form path is intentionally lossy (no notation metadata). Lossless
   tooling consumes the AST directly via `clj->ast`."
  (:require [meme.tools.parser :as parser]
            [meme.tools.clj.parser.grammar :as grammar]
            [meme.tools.clj.ast.build :as ast-build]
            [meme.tools.clj.ast.lower :as ast-lower]
            [meme.tools.clj.stages :as stages]))

(defn parse-string
  "Parse Clojure source into a vector of CST nodes.

   The CST is lossless: every character of source maps to a token, and
   trivia (whitespace, comments, BOM) rides on the token immediately
   following it as `:trivia/before`. Trailing trivia rides on the
   returned vector's metadata as `:trivia/after`.

   opts keys:
     :grammar  — alternate Pratt grammar spec (advanced; default native-Clojure)"
  ([clj-src] (parse-string clj-src nil))
  ([clj-src opts]
   {:pre [(string? clj-src)]}
   (let [src (stages/strip-source-preamble clj-src)]
     (parser/parse src (or (:grammar opts) grammar/grammar)))))

(defn clj->ast
  "Parse Clojure source string into a `CljRoot` AST node.

   The AST captures position, leading/closing trivia, raw token spelling,
   and reader sugar (quote/deref/var/anon-fn/syntax-quote/unquote/discard)
   as record fields rather than form metadata. Survives any walker, even
   ones that don't know about notation conventions.

   Use this when round-trip fidelity matters: refactoring tools, lossless
   conversions, source-driven formatters. For eval-ready forms use
   `clj->forms`.

   opts keys:
     :resolve-keyword  — fn applied at lowering for `::kw` (deferred)
     :grammar          — alternate Pratt grammar spec"
  ([clj-src] (clj->ast clj-src nil))
  ([clj-src opts]
   {:pre [(string? clj-src)]}
   (ast-build/cst->ast (parse-string clj-src opts) opts)))

(defn clj->forms
  "Parse Clojure source string into a vector of plain Clojure forms.

   Composes `clj->ast` followed by AST → form lowering. Reader
   conditionals (`#?`, `#?@`) are returned as `CljReaderConditional`
   records — pass through `meme.tools.clj.stages/step-evaluate-reader-conditionals`
   to materialise a platform branch.

   The form path is lossy: notation (sugar form, source order, raw
   spelling) is dropped. Consumers needing fidelity should use
   `clj->ast` directly.

   opts keys:
     :resolve-keyword  — fn to resolve auto-resolve keywords (`::kw`)
     :grammar          — alternate Pratt grammar spec"
  ([clj-src] (clj->forms clj-src nil))
  ([clj-src opts]
   {:pre [(string? clj-src)]}
   (ast-lower/ast->forms (clj->ast clj-src opts) opts)))
