# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

beme is a syntactic lens over Clojure — a reader (not a language) that translates M-expression syntax into standard Clojure forms. It replaces S-expression nesting with human-readable syntax via two rules:

1. **Call**: `f(x y)` → `(f x y)` — the head of a list is written outside the parens (spacing irrelevant)
2. **Begin/end**: `f begin x y end` → `(f x y)` — textual call delimiters, equivalent to parentheses

Everything else (data literals, reader syntax, destructuring, commas-as-whitespace) is unchanged from Clojure.

## Build & Test

```bash
# Unit tests (JVM Clojure)
clojure -X:test

# .beme example tests (Babashka) — runs test/examples/tests/*.beme
bb test-beme

# ClojureScript tests (Node.js, cleans up build artifacts)
bb test-cljs

# All test suites (Babashka + JVM + ClojureScript)
bb test-all

# Run a .beme file
bb beme run file.beme

# Start beme REPL
bb beme repl

# Convert between beme and Clojure (by extension)
bb beme convert file.beme    # .beme → Clojure
bb beme convert file.clj     # .clj → beme

# Format .beme files (normalize syntax via pprint)
bb beme format file.beme     # in-place
bb beme format src/          # directory, recursive
bb beme format file.beme --stdout  # print to stdout
```

No external dependencies. Only requires Clojure or Babashka. ClojureScript tests require Node.js.

## Architecture

```
.beme file → tokenizer → grouper → parser → Clojure forms → Babashka / Clojure JVM / ClojureScript
```

The pipeline has three stages (composed by `beme.alpha.pipeline`):
1. **Scan** (`beme.alpha.scan.tokenizer`) — characters → flat token vector. Opaque regions emit marker tokens (`:reader-cond-start`, `:namespaced-map-start`, `:syntax-quote-start`).
2. **Group** (`beme.alpha.scan.grouper`) — collapses marker tokens + balanced delimiters into single composite `-raw` tokens. Bracket matching is trivial because strings/chars/comments are already individual tokens.
3. **Parse** (`beme.alpha.parse.reader`) — recursive-descent parser, tokens → Clojure forms. Value resolution (numbers, strings, chars, regex, opaque forms) is delegated to `beme.alpha.parse.resolve`.

- The reader is a **pure function** from beme text to Clojure forms. No runtime dependency.
- A printer (`beme.alpha.emit.printer`) converts Clojure forms back to beme syntax (also pure).
- File extension: `.beme`
- `#` dispatch forms (`#?`, `#?@`, `#:ns{}`, tagged literals) and syntax-quote (`` ` ``) are **opaque** — the tokenizer emits markers, the grouper captures the balanced region as raw text, and `beme.alpha.parse.resolve` hands it off to Clojure's reader. `#{}`, `#""`, `#'`, `#_`, `#()` are parsed by beme since their contents use beme syntax.

### Key namespaces

- `beme.alpha.errors` (.cljc) — Error infrastructure: `beme-error` (throw with consistent `:line`/`:col` ex-data), `format-error` (display with source context and caret), `source-context`. Used by tokenizer, grouper, reader, and REPL. Portable.
- `beme.alpha.forms` (.cljc) — Shared form-level predicates and constructors. Cross-stage contracts that both the parser and printer depend on (e.g. deferred auto-resolve keyword encoding). Portable.
- `beme.alpha.scan.source` (.cljc) — Source-position utilities shared across pipeline stages. `line-col->offset` (the shared definition of how (line, col) maps to character offset — tokenizer and grouper must agree for the pipeline to be correct). Portable.
- `beme.alpha.scan.tokenizer` (.cljc) — Character scanning and token production. Emits flat token vector with marker tokens for opaque regions. Portable.
- `beme.alpha.scan.grouper` (.cljc) — Token grouping: collapses opaque-region markers + balanced delimiters into single composite `-raw` tokens. Operates on already-tokenized input where bracket matching is trivial. Portable.
- `beme.alpha.parse.reader` (.cljc) — Recursive-descent parser (grouped tokens → Clojure forms). Delegates value resolution to `beme.alpha.parse.resolve`. Portable.
- `beme.alpha.parse.resolve` (.cljc) — Value resolution: converts raw token text to Clojure values. Centralizes all host reader delegation (`read-string` calls) with consistent error wrapping. Handles platform asymmetries (JVM vs CLJS). Portable.
- `beme.alpha.emit.printer` (.cljc) — Pattern-matches on Clojure form structure to produce beme text. Portable.
- `beme.alpha.emit.pprint` (.cljc) — Pretty-printer: width-aware, uses `begin`/`end` for forms that exceed line width. Preserves comments from `:ws` metadata. Portable.
- `beme.alpha.pipeline` (.cljc) — Explicit pipeline composition: `scan → group → parse`. Each stage is a `ctx → ctx` function. Exposes intermediate state (`:raw-tokens`, `:tokens`, `:forms`) for tooling. Portable.
- `beme.alpha.core` (.cljc) — Public API in three tracks: text-to-form (`beme->forms`, `forms->beme`), form-to-text (`forms->clj`, `clj->forms`), text-to-text (`beme->clj`, `clj->beme`). Also `pprint-beme` for pretty-printing and `run-pipeline` for tooling access to intermediate pipeline state. `clj->forms` and `clj->beme` are JVM only. Deprecated aliases: `read-beme-string`, `print-beme-string`, `clj-string->beme`.
- `beme.alpha.runtime.repl` (.cljc) — REPL. Requires `eval`; JVM/Babashka only by default, CLJS with injected `:eval`/`:read-line`.
- `beme.alpha.runtime.run` (.cljc) — File runner. Requires `eval` + `slurp`; JVM/Babashka only by default.
- `beme.alpha.runtime.cli` (.clj + .beme) — Unified CLI: `run`, `repl`, `convert`, `format`, `version`. The `.clj` shim loads `cli.beme` — the first beme component implemented in beme itself. Babashka entry point via `bb.edn`.
- `beme.alpha.test-runner` (.clj) — Eval + fixture test runner. JVM only.

### Platform tiers

| Tier | Modules | Platforms |
|------|---------|-----------|
| Core translation | tokenizer, grouper, reader, resolve, printer, pprint, pipeline, core, errors, forms, source | JVM, Babashka, ClojureScript |
| Runtime | repl, run | JVM, Babashka (CLJS possible with injected eval) |
| Test infra | test-runner, dogfood-test | JVM only |

## Documentation

- `doc/PRD.md` — Product requirements, requirements table, known limitations, and future work. Update the requirements table when adding or changing reader/printer behavior.
- `doc/language-reference.md` — Complete syntax reference for writing .beme code.
- `doc/design-decisions.md` — Rationale for each design choice.
- `doc/api.md` — Public API reference.

## Testing conventions

- Every bug fix or behavioral change must include a **scar tissue test** — a regression test in `test/beme/alpha/regression_test.cljc` that prevents the specific issue from recurring.
- Roundtrip tests (read → print → re-read) go in `test/beme/alpha/roundtrip_test.cljc`.
- `.beme` example files in `test/examples/tests/` are eval-based (self-asserting). Numeric prefixes (`01_`, `02_`, ...) control execution order — the test runner sorts alphabetically, so fundamentals (core rules, definitions) run before features that build on them. New files should continue the numbering sequence.
- Fixture pairs in `test/examples/fixtures/` compare parsed output against `.edn` expected forms.

### Test file placement

| File | What belongs here |
|------|-------------------|
| `scan/tokenizer_test` | Tokenizer behavior in isolation (token types, column tracking) |
| `scan/grouper_test` | Grouper: opaque region collapsing, bracket depth |
| `scan/source_test` | Source-position contract: `line-col->offset` |
| `parse/reader/rule1_test` | Rule 1 (call syntax): head type x spacing x arity matrix |
| `parse/reader/calls_test` | All Clojure forms as calls: def, defn, fn, let, loop, for, if, when, cond, try, threading, ns, protocols, records, multimethods, concurrency, "everything is a call" |
| `parse/reader/interop_test` | Java interop: .method, Class/static, .-field, constructors |
| `parse/reader/data_literals_test` | Data literal passthrough: vectors, maps, sets, keywords, numbers |
| `parse/reader/dispatch_test` | Reader macros and dispatch: @, ^, ', #', #_, #(), regex, char, tagged literals, reader conditionals, namespaced maps |
| `parse/reader/errors_test` | Error cases, rejected forms (unquote outside backtick), error messages with locations, CLJS-specific errors |
| `parse/resolve_test` | Value resolution: numbers, strings, chars, regex, keywords, tagged literals |
| `emit/printer_test` | Printer: Clojure forms → beme text. Individual form cases. |
| `emit/pprint_test` | Pretty-printer: width-aware formatting, begin/end, comments |
| `roundtrip_test` | Read → print → re-read identity. Structural invariant tests. |
| `regression/scan_test` | Scar tissue: tokenizer and grouper bugs (opaque form depth, char/string in syntax-quote, symbol parsing, EOF handling) |
| `regression/reader_test` | Scar tissue: parser bugs (discard sentinel, depth limits, head types, spacing, duplicates, metadata) |
| `regression/emit_test` | Scar tissue: printer and pprint bugs (quoted lists, #() arity, empty list, map column, width) |
| `regression/errors_test` | Scar tissue: error infrastructure and resolve error-wrapping bugs (source-context, gutter width, CLJS guards) |
| `core_test` | Public API surface (`beme->forms`, `forms->beme`, `pprint-beme`, etc.) |
| `runtime/repl_test` | REPL infrastructure (`input-state`, `read-input`) |
| `runtime/run_test` | File runner: `run-string`, `run-file`, shebang handling, custom eval-fn |
| `examples_test` | Integration scenarios, multi-feature examples |
| `dogfood_test` | Meta: beme roundtrips its own source files |
| `pipeline_snapshot_test` | Characterization tests: exact token and form snapshots for the full pipeline. Regression net for pipeline refactoring. |
| `generative_test` | Property-based tests with test.check. Print→read roundtrip on generated forms. JVM only. |
| `errors_test` | Error infrastructure: `source-context`, `beme-error`, `format-error` |

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
  - `repl_test.cljc` unused CLJS requires — expected in `.cljc` reader conditionals.
  - `repl.cljc` "unused public var `start`" — entry point called from bb.edn, not from Clojure source.

clojure-lsp is configured via the `.claude-plugin/` directory for Claude Code integration. Requires `clojure-lsp` on PATH (`brew install clojure-lsp/brew/clojure-lsp`).

## beme Syntax Quick Reference (for writing .beme code)

- `symbol(args)` is a call — the head is written outside the parens
- `f (x)` is also a call — spacing between head and `(` is irrelevant
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
- `::keyword` — auto-resolve keywords are opaque (deferred to Clojure's reader)
- Threading macros (`->`, `->>`) are just calls per Rule 1
- `f begin x y end` — textual call delimiters, equivalent to `f(x y)`
- `[]` is always data; use `list(1 2 3)` for list literals
