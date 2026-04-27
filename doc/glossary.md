# Glossary

Source of truth for the names this project uses. Every other doc, docstring,
and prose line should mean exactly what this page says — no drift, no aliases.

The point of the glossary is to fix the **programme / language / toolkit**
distinction so the prose stops conflating them.


## The programme

**meme-clj** — a syntax-experimentation toolkit and research programme for
Clojure. The repo, the Clojars artifact, the GitHub project. *Not* a language.

The programme's claim is that a Clojure host can carry alternative surface
syntaxes without giving up macros, semantics, or ecosystem. The toolkit is the
durable artifact; specific languages are experiments built on top of it.


## The languages

**m1clj** — the **first** language of the programme. M-expressions for
Clojure, in the spirit of McCarthy (1960). One rule: `f(x y)` → `(f x y)`.
Everything else is Clojure. File extension: `.m1clj`. Registry key: `:m1clj`.

**m2clj** — m1clj plus one rule: a paren without head adjacency
(`(x y z)`) is a list literal that lowers to `(quote (x y z))` instead of
being a parse error. Calls still require head adjacency (`f(x y)`), so
call-vs-data remains structural at the reader layer. File extension:
`.m2clj`. Registry key: `:m2clj`.

**clj** (lang) — native Clojure surface (S-expressions) registered with the
toolkit as a sibling guest. Proves the toolkit is genuinely
language-agnostic: shared AST, shared resolvers, shared printer, different
grammar. Registry key: `:clj`. File extensions: `.clj` / `.cljc` / `.cljs`.

Future guests are expected. The plural is the whole point. Each guest is
**sovereign**: even when two langs look temporally similar (m1clj and
m2clj share most of their printer and form-shape today), the duplication is
intentional — langs may diverge, and the architectural direction is one
Clojars artifact per lang.


## The toolkit (`meme.*`)

These are programme-level names — they refer to the toolkit, never to a
specific language.

| Name | What it is |
|---|---|
| `meme-clj` | The repo / Clojars artifact / programme |
| `meme` (binary) | The CLI of the toolkit (`bb meme …`) |
| `meme.tools.*` | Generic, lang-agnostic toolkit: Pratt engine, render, lex builders |
| `meme.tools.clj.*` | Clojure-surface commons: AST, stages, expander, resolver — shared by every Clojure-flavored guest |
| `meme.registry` | Lang registry: registration, resolution, EDN loading |
| `meme.loader` | Namespace loader: intercepts `load` / `load-file` for guest extensions |
| `meme.cli` | App tier — the CLI dispatch |
| `meme.fuzz.*` | Fuzz targets (Jazzer) |
| `meme.test-runner` | Babashka eval-test driver for `.m1clj` examples |

The CLI binary is called `meme` because it dispatches on behalf of the toolkit
to whichever lang is registered for the input. `bb meme run foo.m1clj` runs
through the m1clj lang; `bb meme run foo.clj` runs through clj. The binary
name belongs to the programme.


## The languages' implementations

| Name | What it is |
|---|---|
| `m1clj-lang.*` | The m1clj language: grammar, parselets, printer, formatter, form-shape |
| `m2clj-lang.*` | The m2clj language: same shape as m1clj-lang, sovereign tree |
| `clj-lang/src/clj_lang/api.cljc` | The clj language registration shim (parser comes from `meme.tools.clj.parser.*`) |

Each `<lang>-lang.*` tree is the language's home and its public name in the
source. The lang-specific parselets, printer, and form-shape live there; the
shared backbone they sit on lives in `meme.tools.clj.*`.


## Editor packages

These predate the language rename. They target `.m1clj` files; their names
follow the toolkit, not the language they highlight.

- `tree-sitter-meme` — Tree-sitter grammar for `.m1clj`
- `vscode-meme` — VS Code extension
- `zed-meme` — Zed extension


## Historical note

During 5.0.0 development the language identifier went `meme` → `mclj` →
`m1clj`. Earlier prose used "meme" both as the programme name and as the
language name; that ambiguity is what this glossary exists to retire.

In current text:
- "meme" by itself **only** refers to the toolkit / programme / CLI.
- The language is **m1clj**.
- "M-expression syntax" is the right phrase when the surface is the point.

Pre-rename CHANGELOG entries are archived in `CHANGELOG-archive.md`.


## Quick-reference table

If you're editing prose and you see one of the patterns on the left, replace
it with one of the patterns on the right.

| Old / ambiguous | Use instead |
|---|---|
| "meme is a Clojure frontend" | "m1clj is a Clojure frontend" or "meme-clj is the toolkit" |
| "meme syntax" | "m1clj syntax" or "M-expression syntax" |
| "meme reader" | "m1clj reader" |
| "meme form" | "m1clj form" |
| "meme rules" | "m1clj rules" or "M-expression rules" |
| "meme REPL" | "m1clj REPL" |
| "meme language" | "the m1clj language" |
| `bb meme run foo.m1clj` | unchanged — `meme` is the CLI |
| `meme.tools.parser` | unchanged — toolkit namespace |
| `:m1clj` registry key | unchanged |
| `.m1clj` file | unchanged |
| `.meme` file | soft-deprecated, removal scheduled |
