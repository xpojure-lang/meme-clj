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

# Convert between meme and Clojure (by extension)
bb meme convert file.meme    # .meme → Clojure
bb meme convert file.clj     # .clj → meme

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
.meme file → tokenizer → parser → Clojure forms → Babashka / Clojure JVM / ClojureScript
```

The pipeline has composable stages (composed by `meme.alpha.pipeline`), each a `ctx → ctx` function:
1. **step-strip-shebang** — remove `#!` line from `:source` (for executable scripts). Defined in `runtime/run`, not part of the core pipeline.
2. **step-scan** (`meme.alpha.scan.tokenizer`) — characters → flat token vector. Compound forms emit marker tokens.
3. **step-parse** (`meme.alpha.parse.reader`) — recursive-descent parser, tokens → Clojure forms. Value resolution delegated to `meme.alpha.parse.resolve`.
4. **step-expand-syntax-quotes** (`meme.alpha.parse.expander`) — syntax-quote AST nodes → plain Clojure forms. Only needed before eval, not for tooling.

- The reader is a **pure function** from meme text to Clojure forms. No runtime dependency. No `read-string` delegation — everything is parsed natively.
- A printer (`meme.alpha.emit.printer`) converts Clojure forms back to meme syntax (also pure). Supports `:meme` and `:clj` output modes.
- **Syntactic transparency:** meme is a syntactic lens — the pipeline must preserve the user's syntax choices. When two notations produce the same Clojure form (e.g., `'x` sugar vs `quote(x)` call), the reader tags the form with `:meme/sugar` metadata so the printer can reconstruct the original notation. See `doc/design-decisions.md` for the full principle. Any new syntax feature with multiple representations MUST preserve the distinction via metadata.
- File extension: `.meme`
- `()` is the empty list. Every `(content)` requires a head: `head(content)`.
- All `#` dispatch forms (`#?`, `#?@`, `#:ns{}`, `#{}`, `#""`, `#'`, `#_`, `#()`, tagged literals) and syntax-quote (`` ` ``) are parsed natively with meme rules inside. No opaque regions.

### Key namespaces

- `meme.alpha.errors` (.cljc) — Error infrastructure: `meme-error` (throw with consistent `:line`/`:col` ex-data), `format-error` (display with source context and caret), `source-context`. Uses the **display line model** (`str/split-lines` — splits on `\n` and `\r\n`). `format-error` bridges scanner positions to display: clamps carets when scanner col exceeds display line length (CRLF). Used by tokenizer, reader, and REPL. Portable.
- `meme.alpha.forms` (.cljc) — Shared form-level predicates, constructors, and constants. Cross-stage contracts that both the parser and printer depend on (e.g. deferred auto-resolve keyword encoding, `percent-param-type`, `strip-internal-meta`). Portable.
- `meme.alpha.scan.source` (.cljc) — Scanner-level source-position utilities. `line-col->offset` uses the **scanner line model** (only `\n` is a line break, `\r` occupies a column). Note: the scanner and display line models diverge for CRLF sources — see `format-error` for how the bridge is handled. Portable.
- `meme.alpha.scan.tokenizer` (.cljc) — Character scanning and token production. Emits flat token vector with marker tokens for compound forms. Portable.
- `meme.alpha.parse.reader` (.cljc) — Recursive-descent parser (tokens → Clojure forms). Delegates value resolution to `meme.alpha.parse.resolve`. Portable.
- `meme.alpha.parse.expander` (.cljc) — Syntax-quote expansion: `MemeSyntaxQuote` AST nodes → plain Clojure forms (`seq`/`concat`/`list`). Called by runtime paths (run, repl) before eval. Also unwraps `MemeRaw` to plain values. Portable.
- `meme.alpha.parse.resolve` (.cljc) — Value resolution: converts raw token text to Clojure values. Centralizes all host reader delegation (`read-string` calls) with consistent error wrapping. Handles platform asymmetries (JVM vs CLJS). Portable.
- `meme.alpha.emit.printer` (.cljc) — Wadler-Lindig Doc tree builder: `to-doc` (form → Doc tree) + `extract-comments`. Single source of truth for meme and Clojure output modes. Delegates layout to `render`. Portable.
- `meme.alpha.emit.render` (.cljc) — Doc algebra and layout engine: `DocText`, `DocLine`, `DocCat`, `DocNest`, `DocGroup`, `DocIfBreak`, `layout` (Doc tree → string at given width). Pure, no meme-specific logic. Portable.
- `meme.alpha.emit.formatter.flat` (.cljc) — Flat formatter: composes printer + render at infinite width. `format-form`, `format-forms`, `format-clj`. Single-line output. Portable.
- `meme.alpha.emit.formatter.canon` (.cljc) — Canonical formatter: composes printer + render at target width. `format-form`, `format-forms`. Width-aware multi-line output. Used by `meme format` CLI. Portable.
- `meme.alpha.pipeline` (.cljc) — Composable pipeline stages: `step-scan`, `step-parse`, `step-expand-syntax-quotes`. Each is a `ctx → ctx` function. Context map contract documented in namespace docstring. Exposes intermediate state (`:raw-tokens`, `:tokens`, `:forms`) for tooling. Portable.
- `meme.alpha.core` (.cljc) — Public API in three tracks: text-to-form (`meme->forms`, `forms->meme`), form-to-text (`forms->clj`, `clj->forms`), text-to-text (`meme->clj`, `clj->meme`). Also `format-meme` for width-aware formatting and `run-pipeline` for tooling access to intermediate pipeline state. `clj->forms` and `clj->meme` are JVM only.
- `meme.alpha.runtime.resolve` (.cljc) — Default symbol resolution for syntax-quote. Matches Clojure's `SyntaxQuoteReader`: special forms stay unqualified, vars resolve to their defining namespace, unresolved symbols get current-ns qualification. JVM/Babashka only.
- `meme.alpha.runtime.repl` (.cljc) — REPL. Requires `eval`; JVM/Babashka only by default, CLJS with injected `:eval`/`:read-line`.
- `meme.alpha.runtime.run` (.cljc) — File runner. Requires `eval` + `slurp`; JVM/Babashka only by default.
- `meme.alpha.runtime.cli` (.clj + .meme) — Unified CLI: `run`, `repl`, `convert`, `format`, `version`. The `.clj` shim loads `cli.meme` at require time (top-level `run-string`) — the first meme component implemented in meme itself. Babashka entry point via `bb.edn`. Not AOT-compatible (load-time eval by design).
- `meme.alpha.test-runner` (.clj) — Eval + fixture test runner. JVM only.

### Platform tiers

| Tier | Modules | Platforms |
|------|---------|-----------|
| Core translation | tokenizer, reader, expander, resolve, printer, render, formatter.flat, formatter.canon, pipeline, core, errors, forms, source | JVM, Babashka, ClojureScript |
| Runtime | repl, run | JVM, Babashka (CLJS possible with injected eval) |
| Test infra | test-runner, dogfood-test, vendor-roundtrip-test | JVM only |

## Documentation

- `doc/PRD.md` — Product requirements, requirements table, known limitations, and future work. Update the requirements table when adding or changing reader/printer behavior.
- `doc/language-reference.md` — Complete syntax reference for writing .meme code.
- `doc/design-decisions.md` — Rationale for each design choice.
- `doc/api.md` — Public API reference.

## Testing conventions

- Every bug fix or behavioral change must include a **scar tissue test** — a regression test in the appropriate `test/meme/alpha/regression/*_test.cljc` file that prevents the specific issue from recurring.
- Roundtrip tests (read → print → re-read) go in `test/meme/alpha/roundtrip_test.cljc`.
- `.meme` example files in `test/examples/tests/` are eval-based (self-asserting). Numeric prefixes (`01_`, `02_`, ...) control execution order — the test runner sorts alphabetically, so fundamentals (core rules, definitions) run before features that build on them. New files should continue the numbering sequence.
- Fixture pairs in `test/examples/fixtures/` compare parsed output against `.edn` expected forms.
- **Vendor roundtrip tests** use git submodules in `test/vendor/` (core.async, specter, malli, ring, clj-http, medley, hiccup). Each `.clj`/`.cljc` file is roundtripped per-form using `:read-cond :preserve` so `ReaderConditional` objects survive the roundtrip. Initialize with `git submodule update --init`. Read errors (Clojure reader limitations) don't fail the test; roundtrip failures do.

### Test file placement

| File | What belongs here |
|------|-------------------|
| `scan/tokenizer_test` | Tokenizer behavior in isolation (token types, column tracking) |
| `scan/source_test` | Source-position contract: `line-col->offset` |
| `parse/reader/rule1_test` | Rule 1 (call syntax): head type x spacing x arity matrix |
| `parse/reader/calls_test` | All Clojure forms as calls: def, defn, fn, let, loop, for, if, when, cond, try, threading, ns, protocols, records, multimethods, concurrency, "everything is a call" |
| `parse/reader/interop_test` | Java interop: .method, Class/static, .-field, constructors |
| `parse/reader/data_literals_test` | Data literal passthrough: vectors, maps, sets, keywords, numbers |
| `parse/reader/dispatch_test` | Reader macros and dispatch: @, ^, ', #', #_, #(), regex, char, tagged literals, reader conditionals, namespaced maps |
| `parse/reader/errors_test` | Error cases, rejected forms (unquote outside backtick), error messages with locations, CLJS-specific errors |
| `parse/resolve_test` | Value resolution: numbers, strings, chars, regex, keywords, tagged literals |
| `emit/formatter/flat_test` | Flat formatter: single-line meme/clj output, reader sugar, individual form cases |
| `emit/formatter/canon_test` | Canonical formatter: width-aware formatting, multi-line layout, comments |
| `roundtrip_test` | Read → print → re-read identity. Structural invariant tests. |
| `regression/scan_test` | Scar tissue: tokenizer bugs (bracket depth, char/string in syntax-quote, symbol parsing, EOF handling) |
| `regression/reader_test` | Scar tissue: parser bugs (discard sentinel, depth limits, head types, spacing, duplicates, metadata) |
| `regression/emit_test` | Scar tissue: printer and formatter bugs (regex escaping, reader-sugar formatting, deferred auto-keywords, metadata, comments, width) |
| `regression/errors_test` | Scar tissue: error infrastructure and resolve error-wrapping bugs (source-context, gutter width, CLJS guards) |
| `core_test` | Public API surface (`meme->forms`, `forms->meme`, `format-meme`, etc.) |
| `runtime/repl_test` | REPL infrastructure (`input-state`, `read-input`) |
| `runtime/run_test` | File runner: `run-string`, `run-file`, shebang handling, custom eval-fn |
| `examples_test` | Integration scenarios, multi-feature examples |
| `dogfood_test` | Meta: meme roundtrips its own source files |
| `pipeline_snapshot_test` | Characterization tests: exact token and form snapshots for the full pipeline. Regression net for pipeline refactoring. |
| `generative_test` | Property-based tests with test.check. Print→read roundtrip on generated forms. JVM only. |
| `errors_test` | Error infrastructure: `source-context`, `meme-error`, `format-error` |
| `vendor_roundtrip_test` | Vendor roundtrip: real-world Clojure libraries (git submodules in `test/vendor/`) roundtripped per-form through clj→meme→clj. JVM only. |
| `pipeline/contract_test` | Pipeline spec validation: token and context-map specs at stage boundaries |
| `rewrite_test` | Rewrite engine: pattern matching, substitution, splice variables, cycle detection |
| `rewrite/rules_test` | Rewrite rules: S→M and M→S transformations |
| `rewrite/tree_test` | Rewrite tree builder: tokens→tagged tree, cross-test vs main parser |
| `platform/registry_test` | Language registration, extension dispatch, prelude injection, custom parser. JVM only. |

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
  - `repl.cljc` "unused public var `start`" / `test_runner.clj` "unused public var `run-all-meme-tests`" — entry points called externally (bb.edn, CLI), not from Clojure source.

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
