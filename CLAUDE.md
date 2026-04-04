# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

meme is a complete Clojure frontend — a reader, printer, formatter, REPL, and file runner that replaces S-expression syntax with M-expressions. One rule:

**Call**: `f(x y)` → `(f x y)` — the head of a list is written outside the parens, adjacent to `(` (spacing significant: `f(x)` is a call, `f ()` is two forms)

Everything else (data literals, reader syntax, destructuring, commas-as-whitespace) is unchanged from Clojure. The CLI is self-hosted in `.meme`. Programs run on Babashka, Clojure JVM, or ClojureScript without modification.

## Build & Test

```bash
# Unit tests (JVM Clojure) — CI uses -J-Xss4m for deep recursion tests
clojure -X:test

# .meme example tests (Babashka) — runs test/examples/tests/*.meme
bb test-meme

# ClojureScript tests (Node.js, cleans up build artifacts)
bb test-cljs

# All test suites (Babashka + JVM + ClojureScript)
bb test-all

# Run a .meme file
bb meme run file.meme

# Start meme REPL
bb meme repl

# Convert between meme and Clojure
bb meme to-clj file.meme     # .meme → Clojure
bb meme to-meme file.clj     # .clj → meme

# Format .meme files (normalize syntax via canonical formatter)
bb meme format file.meme     # in-place
bb meme format src/          # directory, recursive
bb meme format file.meme --stdout  # print to stdout
```

No external dependencies. Only requires Clojure or Babashka. ClojureScript tests require Node.js.

```bash
# Build JAR
clojure -T:build jar

# Deploy to Clojars
clojure -T:build deploy
```

## Architecture

```
.meme file → scanner → trivia-attacher → pratt-parser → cst-reader → Clojure forms
```

Composable stages (`meme.tools.reader.stages`), each a `ctx → ctx` function:
1. **step-scan** (`meme.tools.reader.tokenizer`) — exhaustive byte-level scanner. Never throws. Partition invariant: `(= input (apply str (map :raw tokens)))`.
2. **step-trivia** (`meme.tools.pratt.trivia`) — attaches trivia (whitespace, comments) to semantic tokens as structured `:trivia/before` metadata. Trivia classification is configurable.
3. **step-parse** (`meme.tools.pratt.parser`) — data-driven Pratt parser. Produces a lossless CST preserving every token. Grammar is a map of token types to parselet functions.
4. **step-read** (`meme.tools.reader.cst-reader`) — lowers CST to Clojure forms.
5. **step-expand-syntax-quotes** (`meme.tools.parse.expander`) — syntax-quote AST nodes → plain Clojure forms. Only needed before eval, not for tooling.

The pipeline is lossless (CST preserves all tokens including delimiters and trivia) and data-driven (the Pratt parser is generic — meme syntax is defined by a grammar spec in `meme.tools.reader.meme-grammar`).

The generic Pratt parser and trivia transformer live in `meme.tools.pratt.*` — they are language-agnostic and reusable. Meme-specific grammar, parselets, and CST reader live in `meme.tools.reader.*`.

- The reader is a **pure function** from meme text to Clojure forms. No runtime dependency. No `read-string` delegation — everything is parsed natively.
- A printer (`meme.tools.emit.printer`) converts Clojure forms back to meme syntax (also pure). Supports `:meme` and `:clj` output modes.
- **Syntactic transparency:** meme is a syntactic lens — the stages must preserve the user's syntax choices. When two notations produce the same Clojure form (e.g., `'x` sugar vs `quote(x)` call), the reader tags the form with `:meme/sugar` metadata so the printer can reconstruct the original notation. See `doc/design-decisions.md` for the full principle. Any new syntax feature with multiple representations MUST preserve the distinction via metadata.
- File extension: `.meme`
- `()` is the empty list. Every `(content)` requires a head: `head(content)`. Any value can be a head — `nil(1 2)` → `(nil 1 2)`, `true(:a)` → `(true :a)`.
- All `#` dispatch forms (`#?`, `#?@`, `#:ns{}`, `#{}`, `#""`, `#'`, `#_`, `#()`, tagged literals) and syntax-quote (`` ` ``) are parsed natively with meme rules inside. No opaque regions.

### Key namespaces

- `meme.langs.meme` (.cljc) — Unified lang composition and public API. Provides `meme->forms`, `forms->meme`, `forms->clj`, `clj->forms`, `meme->clj`, `clj->meme`, `format-meme-forms`. Also lang commands: `format-meme`, `to-clj`, `to-meme`, `lang-map`. Orchestrates the lossless CST-based pipeline. `clj->forms` and `clj->meme` are JVM only. Portable.
- `meme.tools.errors` (.cljc) — Error infrastructure: `meme-error` (throw with consistent `:line`/`:col` ex-data), `format-error` (display with source context and caret), `source-context`. Uses the **display line model** (`str/split-lines` — splits on `\n` and `\r\n`). `format-error` bridges scanner positions to display: clamps carets when scanner col exceeds display line length (CRLF). Used by tokenizer, reader, and REPL. Portable.
- `meme.tools.forms` (.cljc) — Shared form-level predicates, constructors, and constants. Cross-stage contracts that both the parser and printer depend on (e.g. deferred auto-resolve keyword encoding, `percent-param-type`, `strip-internal-meta`). Portable.
- `meme.tools.reader.tokenizer` (.cljc) — Byte-level exhaustive tokenizer. Structural invariant: `(= input (apply str (map :raw tokens)))` for any input. Token stream is a partition — no gaps, no overlaps, no throws. Invalid input gets `:invalid` tokens. Portable.
- `meme.tools.reader.stages` (.cljc) — Composable pipeline stages: `step-scan`, `step-trivia`, `step-parse`, `step-read`, `step-expand-syntax-quotes`. Each is `ctx → ctx`. Grammar and trivia types configurable via `:opts`. Also provides `run` which composes scan → trivia → parse → read. Portable.
- `meme.tools.reader.meme-grammar` (.cljc) — Meme language grammar: maps token types to parselet functions for the Pratt parser. The complete syntactic specification of M-expression syntax as data. Portable.
- `meme.tools.reader.meme-parselets` (.cljc) — Meme-specific parselet logic: `no-trivia?` (call predicate), `reader-cond-extra` (splicing detection). Portable.
- `meme.tools.reader.cst-reader` (.cljc) — CST reader: walks CST nodes and produces Clojure forms. Handles value resolution, metadata, syntax-quote AST nodes, anonymous functions, namespaced maps, reader conditionals. Portable.
- `meme.tools.pratt.parser` (.cljc) — Generic data-driven Pratt parser engine. Parselet functions define syntax; parselet factories (`nud-atom`, `nud-prefix`, `nud-delimited`, `led-call`, `led-infix`) generate common patterns. `parse` takes a token stream and a grammar spec, produces a CST. Language-agnostic. Portable.
- `meme.tools.pratt.trivia` (.cljc) — Generic trivia transformer. Attaches trivia tokens to adjacent semantic tokens as structured `:trivia/before` vectors. Trivia classification is configurable via a set of token types. Default: `#{:whitespace :newline}`. Language-agnostic. Portable.
- `meme.tools.parse.expander` (.cljc) — Syntax-quote expansion: `MemeSyntaxQuote` AST nodes → plain Clojure forms (`seq`/`concat`/`list`). Called by runtime paths (run, repl) before eval. Also unwraps `MemeRaw` to plain values. Portable.
- `meme.tools.parse.resolve` (.cljc) — Native value resolution: converts raw token text to Clojure values. No `read-string` delegation — numbers, strings, chars, regex, keywords, tagged literals all resolved natively. Handles platform asymmetries (JVM vs CLJS). Portable.
- `meme.tools.emit.values` (.cljc) — Shared value → string serialization for the printer. Handles atomic Clojure values (strings, numbers, chars, regex). Portable.
- `meme.tools.emit.printer` (.cljc) — Wadler-Lindig Doc tree builder: `to-doc` (form → Doc tree) + `extract-comments`. Single source of truth for meme and Clojure output modes. Delegates layout to `render`. Portable.
- `meme.tools.emit.render` (.cljc) — Doc algebra and layout engine: `DocText`, `DocLine`, `DocCat`, `DocNest`, `DocGroup`, `DocIfBreak`, `layout` (Doc tree → string at given width). Pure, no meme-specific logic. Portable.
- `meme.tools.emit.formatter.flat` (.cljc) — Flat formatter: composes printer + render at infinite width. `format-form`, `format-forms`, `format-clj`. Single-line output. Portable.
- `meme.tools.emit.formatter.canon` (.cljc) — Canonical formatter: composes printer + render at target width. `format-form`, `format-forms`. Width-aware multi-line output. Used by `meme format` CLI. Portable.
- `meme.tools.repl` (.clj) — REPL. Requires `eval`; JVM/Babashka only.
- `meme.tools.run` (.clj) — File runner. Requires `eval` + `slurp`. Also contains inlined syntax-quote symbol resolution (matching Clojure's `SyntaxQuoteReader`). JVM/Babashka only.
- `meme.cli` (.clj) — Unified CLI: `run`, `repl`, `to-clj`, `to-meme`, `format`, `inspect`, `version`. Generic dispatcher — commands delegate to lang map functions. Babashka entry point via `bb.edn`.
- `meme.registry` (.clj) — Lang registry: registration, resolution, and EDN loading. `default-lang`, `resolve-lang`, `supports?`, `check-support`, `load-edn`, `register!`, `resolve-by-extension`, `registered-langs`, `clear-user-langs!`, `available-langs`. Built-in lang is `:meme`; user langs register via EDN. JVM/Babashka only.
- `meme.test-runner` (.clj) — Eval + fixture test runner. Lives in `test/`, not `src/`. JVM only.

### Platform tiers

| Tier | Modules | Platforms |
|------|---------|-----------|
| Core translation | tools.{reader.tokenizer, reader.stages, reader.meme-grammar, reader.meme-parselets, reader.cst-reader, pratt.parser, pratt.trivia, parse.resolve, parse.expander, emit.printer, emit.render, emit.values, emit.formatter.flat, emit.formatter.canon, errors, forms}, langs.meme | JVM, Babashka, ClojureScript |
| Runtime | tools.{run, repl}, registry, cli | JVM, Babashka |
| Test infra | test-runner, dogfood-test, vendor-roundtrip-test | JVM only |

## Documentation

- `doc/PRD.md` — Product requirements, requirements table, known limitations, and future work. Update the requirements table when adding or changing reader/printer behavior.
- `doc/language-reference.md` — Complete syntax reference for writing .meme code.
- `doc/design-decisions.md` — Rationale for each design choice.
- `doc/api.md` — Public API reference.

## Testing conventions

- Every bug fix or behavioral change must include a **scar tissue test** — a regression test in the appropriate `test/meme/regression/*_test.cljc` file that prevents the specific issue from recurring.
- Roundtrip tests (read → print → re-read) go in `test/meme/roundtrip_test.cljc`.
- `.meme` example files in `test/examples/tests/` are eval-based (self-asserting). Numeric prefixes (`01_`, `02_`, ...) control execution order — the test runner sorts alphabetically, so fundamentals (core rules, definitions) run before features that build on them. New files should continue the numbering sequence.
- Fixture pairs in `test/examples/fixtures/` compare parsed output against `.edn` expected forms.
- **Vendor roundtrip tests** use git submodules in `test/vendor/` (core.async, specter, malli, ring, clj-http, medley, hiccup). Each `.clj`/`.cljc` file is roundtripped per-form using `:read-cond :preserve` so `ReaderConditional` objects survive the roundtrip. Initialize with `git submodule update --init`. Read errors (Clojure reader limitations) don't fail the test; roundtrip failures do.

### Test file placement

| File | What belongs here |
|------|-------------------|
| `tools/reader/tokenizer_test` | Tokenizer behavior in isolation (token types, partition invariant, column tracking) |
| `tools/reader/stages_test` | Pipeline stages: scan → trivia → parse → read, syntax-quote expansion |
| `tools/parse/reader/call_syntax_test` | M-expression call syntax: head type x spacing x arity matrix |
| `tools/parse/reader/calls_test` | All Clojure forms as calls: def, defn, fn, let, loop, for, if, when, cond, try, threading, ns, protocols, records, multimethods, concurrency, "everything is a call" |
| `tools/parse/reader/interop_test` | Java interop: .method, Class/static, .-field, constructors |
| `tools/parse/reader/data_literals_test` | Data literal passthrough: vectors, maps, sets, keywords, numbers |
| `tools/parse/reader/dispatch_test` | Reader macros and dispatch: @, ^, ', #', #_, #(), regex, char, tagged literals, reader conditionals, namespaced maps |
| `tools/parse/reader/errors_test` | Error cases, rejected forms (unquote outside backtick), error messages with locations, CLJS-specific errors |
| `tools/parse/expander_test` | Syntax-quote expansion: `expand-forms` passthrough, `MemeSyntaxQuote` expansion, `MemeRaw` unwrapping |
| `tools/parse/resolve_test` | Value resolution: numbers, strings, chars, regex, keywords, tagged literals |
| `tools/emit/formatter/flat_test` | Flat formatter: single-line meme/clj output, reader sugar, individual form cases |
| `tools/emit/formatter/canon_test` | Canonical formatter: width-aware formatting, multi-line layout, comments |
| `tools/emit/render_test` | Doc algebra and layout engine |
| `tools/emit/values_test` | Value serialization: regex, chars, numbers, strings |
| `tools/forms_test` | Form predicates and contracts: AST nodes, metadata, reader conditionals |
| `tools/errors_test` | Error infrastructure: `source-context`, `meme-error`, `format-error` |
| `roundtrip_test` | Read → print → re-read identity. Structural invariant tests. |
| `regression/scan_test` | Scar tissue: tokenizer bugs (bracket depth, char/string in syntax-quote, symbol parsing, EOF handling) |
| `regression/reader_test` | Scar tissue: parser bugs (discard sentinel, depth limits, head types, spacing, duplicates, metadata) |
| `regression/emit_test` | Scar tissue: printer and formatter bugs (regex escaping, reader-sugar formatting, deferred auto-keywords, metadata, comments, width) |
| `regression/errors_test` | Scar tissue: error infrastructure and resolve error-wrapping bugs (source-context, gutter width, CLJS guards) |
| `langs/meme_test` | Language API (`meme->forms`, `forms->meme`, `format-meme-forms`, etc.) |
| `registry_test` | Lang registry: command maps, EDN loading, extension dispatch, user lang registration. JVM only. |
| `cli_test` | CLI unit tests: file type checking, extension swapping |
| `tools/repl_test` | REPL infrastructure (`input-state`, `read-input`). JVM only. |
| `tools/run_test` | File runner: `run-string`, `run-file`, shebang handling, custom eval-fn |
| `examples_test` | Integration scenarios, multi-feature examples |
| `emit_fixtures_test` | meme↔clj conversion fixture validation. JVM only. |
| `dogfood_test` | Meta: meme roundtrips its own source files |
| `snapshot_test` | Characterization tests: exact token and form snapshots. Regression net for stage refactoring. |
| `generative_test` | Property-based tests with test.check. Print→read roundtrip on generated forms. JVM only. |
| `generative_cljs_test` | Cross-platform property-based tests. |
| `vendor_roundtrip_test` | Vendor roundtrip: real-world Clojure libraries (git submodules in `test/vendor/`) roundtripped per-form through clj→meme→clj. JVM only. |

## Development tools

### clojure-lsp

clojure-lsp (with clj-kondo) provides useful static analysis for development, testing, and debugging:

- **Symbol navigation**: `documentSymbol` lists all defs/defns in a file; `workspaceSymbol` searches across the entire codebase.
- **Cross-references**: `findReferences` traces usage across all source and test files.
- **Go to definition / hover**: Jump to any symbol's source or get inline docs.
- **Call hierarchy**: `incomingCalls`/`outgoingCalls` map the call graph between namespaces.
- **Diagnostics (clj-kondo)**: Catches unused requires, unresolved symbols, unused bindings. Known noise to ignore:
  - `tokenizer.cljc` "unused value" warnings — these are `case` branch return values, not bugs.
  - `generative_test.clj` "unresolved symbol" errors — macro-generated `deftest` names from `defspec`.
  - `.cljc` files with `#?` reader conditionals — clj-kondo analyzes one platform branch and flags requires/vars used only in the other branch as unused. Affects `resolve.cljc`, `repl_test.cljc`, `errors_test.cljc`, `dispatch_test.cljc`, `scan_test.cljc`.
  - `repl.clj` "unused public var `start`" / `test_runner.clj` "unused public var `run-all-meme-tests`" — entry points called externally (bb.edn, CLI), not from Clojure source.

clojure-lsp is configured via the `.claude-plugin/` directory for Claude Code integration. Requires `clojure-lsp` on PATH (`brew install clojure-lsp/brew/clojure-lsp`).

## meme Syntax Quick Reference (for writing .meme code)

- `symbol(args)` is a call — the head is written outside the parens, adjacent to `(`
- `f (x)` is NOT a call — spacing is significant; `f(x)` is a call, `f ()` is two forms
- Vectors can also be heads: `[x](body)` → `([x] body)` (used for multi-arity clauses)
- Everything uses call syntax: `def(x 42)`, `let([x 1] body)`, `if(cond then else)`
- `defn(name [args] body)` — single arity function definition
- `defn(name [args](body) [args](body))` — multi-arity (vector-as-head)
- `fn([x] expr)` — anonymous function
- `try(body catch(Exception e handler))` — error handling
- `when(cond body)`, `cond(pairs...)`, `case(expr pairs...)` — control flow
- `for([x xs] body)`, `doseq([x items] body)` — comprehensions
- `ns(my.ns :require([...]))` — namespace declaration
- `defprotocol(Name method-sigs...)`, `defrecord(Name [fields])` — protocols and records
- `defmulti(name dispatch-fn)`, `defmethod(name dispatch-val [args] body)` — multimethods
- `::keyword` — auto-resolve keywords resolved natively
- Threading macros (`->`, `->>`) are just calls
- `()` is the empty list
- `'x` quotes the next form; `'f(x)` → `(quote (f x))` — meme syntax inside, no S-expression escape
- `` `if(~test ~body) `` — syntax-quote uses meme syntax inside
- `[]` is always data; use `list(1 2 3)` for list literals
- No opaque regions — everything parsed natively by meme
