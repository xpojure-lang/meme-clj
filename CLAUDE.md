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

# Coverage-guided fuzzing (requires Jazzer — see deps.edn :fuzzer alias)
bb fuzz-build          # Build fuzzer uberjar
bb fuzz-quick          # 50K runs per target (~3 min)

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

# Compile .meme to .clj (for classpath use without runtime patching)
bb meme transpile src/            # output to target/meme (default; alias: `compile`)
bb meme transpile src/ --out out/ # custom output directory

# Build to JVM bytecode (transpile + AOT compile)
bb meme build src/                # .class files in target/classes
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
.meme file → unified-pratt-parser → cst-reader → Clojure forms
               (step-parse)         (step-read)
```

The codebase is organized by *kind* of code, with shared infrastructure that both language implementations and the CLI depend on:

- **`meme.tools.*`** — Generic, language-agnostic building blocks (parser engine, scanlet builders, render engine).
- **`meme.tools.clj.*`** — Clojure-surface commons shared by any Clojure-flavored frontend (meme, implojure, future siblings): lexical conventions, CST reader, stages framework, error infrastructure, atom resolution, syntax-quote expander, the `Clj*` AST records, value serialization, eval pipeline, and REPL harness. Sits inside the toolkit tier but carries Clojure bias explicitly in the path.
- **`meme-lang.*`** — Meme language implementation. The *syntactic* surface lives here (grammar, parselets, printer, form-shape, formatters). Infrastructure files like `meme-lang.lexlets`, `meme-lang.run`, `meme-lang.repl` are thin shims that inject meme's grammar/banner and delegate to `meme.tools.clj.*`.
- **`meme.registry`, `meme.loader`** — Shared runtime infrastructure, peer to `meme.tools.*`. Both langs and the CLI depend on them: langs push themselves into the registry at load time and rely on the loader for `require`/`load-file`; the CLI dispatches through the registry.
- **`meme.cli`, `meme.config`** — App tier. Only consumer-facing code lives here.

This is a "kinds + infrastructure" layout, not a strict top-down tiered architecture. `meme-lang.api` requiring `meme.registry` (for self-registration) and `meme-lang.run` requiring `meme.loader` (for auto-install) are intentional — the infrastructure is shared. The real layering rule is: **`meme-lang.*` must not require `meme.cli` or `meme.config`**, and **`meme.tools.*` must not require anything from `meme-lang.*` or `meme.*`**.

Composable stages (`meme.tools.clj.stages`), each a `ctx → ctx` function. Tooling paths compose 1–2; eval paths (`run-string`, `run-file`, REPL) compose 1–4. `step-parse` requires `:grammar` in opts (no implicit default) — each lang passes its own grammar explicitly:
1. **step-parse** (`meme.tools.parser` driven by a lang-supplied grammar) — Unified scanlet-parselet Pratt parser. Reads directly from source string. Scanning (character dispatch, trivia) and parsing (structure) are both defined in the grammar spec as scanlets and parselets. Produces a lossless CST preserving every token.
2. **step-read** (`meme.tools.clj.cst-reader`) — lowers CST to Clojure forms. Reader conditionals (`#?`, `#?@`) are preserved as `CljReaderConditional` records.
3. **step-evaluate-reader-conditionals** (`meme.tools.clj.stages`) — materializes the platform branch of `#?`/`#?@` for eval paths. Tooling paths skip this step; records stay records. Supports `:platform` opt and `:default` fallback.
4. **step-expand-syntax-quotes** (`meme.tools.clj.expander`) — syntax-quote AST nodes → plain Clojure forms. Only needed before eval, not for tooling.

The pipeline is lossless (CST preserves all tokens including delimiters and trivia) and data-driven (the Pratt parser is generic — meme syntax is defined by a grammar spec in `meme-lang.grammar`).

The generic parser engine, scanlet builders, and render engine live in `meme.tools.*` — they are language-agnostic and reusable. Meme-specific grammar, scanlets (lexlets), parselets, and CST reader live in `meme-lang.*`.

### Formatter architecture — three layers

The printer/formatter split follows a three-layer model. Each layer owns one concern; they compose via plain data:

1. **Notation** (`meme-lang.printer`) — how a call renders (parens, delimiter placement, `:meme` vs `:clj` mode). Knows nothing about form names or slot semantics beyond the fallback recursion.
2. **Form-shape** (`meme-lang.form-shape`) — what the parts of a special form *mean*. A registry maps head symbols to decomposers; each decomposer produces `[[slot-name value] ...]`. Lang-owned: each lang carries its own registry. See `doc/form-shape.md` for the slot vocabulary.
3. **Style** (`meme-lang.formatter.canon/style` and alternatives) — opinions *per slot name*, not per form. `:head-line-slots` keeps named slots with the call head on break; `:force-open-space-for` controls the `head( ` convention; `:slot-renderers` overrides the printer defaults for `:bindings`/`:clause`/custom slots.

All four extension axes compose via `assoc`/`merge` on plain maps: swap a style, extend a registry, opt into structural fallback (`with-structural-fallback`), override one slot's rendering. No printer changes required for any of them.

- The reader is a **pure function** from meme text to Clojure forms. No runtime dependency. No `read-string` delegation — everything is parsed natively.
- A printer (`meme-lang.printer`) converts Clojure forms back to meme syntax (also pure). Supports `:meme` and `:clj` output modes.
- **Syntactic transparency:** meme is a syntactic lens — the stages must preserve the user's syntax choices. When two notations produce the same Clojure form (e.g., `'x` sugar vs `quote(x)` call), the reader tags the form with `:meme/sugar` metadata so the printer can reconstruct the original notation. See `doc/design-decisions.md` for the full principle. Any new syntax feature with multiple representations MUST preserve the distinction via metadata.
- File extension: `.meme`
- `()` is the empty list. Every `(content)` requires a head: `head(content)`. Any value can be a head — `nil(1 2)` → `(nil 1 2)`, `true(:a)` → `(true :a)`.
- All `#` dispatch forms (`#?`, `#?@`, `#:ns{}`, `#{}`, `#""`, `#'`, `#_`, `#()`, tagged literals) and syntax-quote (`` ` ``) are parsed natively with meme rules inside. No opaque regions.

### Key namespaces

**Generic tools** (`meme.tools.*`) — language-agnostic, reusable:

- `meme.tools.parser` (.cljc) — Unified scanlet-parselet Pratt parser engine. Reads directly from a source string. The grammar spec defines character dispatch (scanlets), trivia classification, prefix parselets (nud), and postfix rules (led). Parselet factories (`nud-atom`, `nud-prefix`, `nud-delimited`, `led-call`, `led-infix`) generate common patterns. `parse` takes source + grammar spec, produces a CST. Portable.
- `meme.tools.lexer` (.cljc) — Generic scanlet builders. Wraps language-specific consume functions into scanlets that the grammar spec can reference. Bridges "consume characters from source" to "produce a CST node". Portable.
- `meme.tools.render` (.cljc) — Wadler-Lindig document algebra and layout engine: `DocText`, `DocLine`, `DocCat`, `DocNest`, `DocGroup`, `DocIfBreak`, `layout` (Doc tree → string at given width). Pure, no meme-specific logic. Portable.
- `meme.tools.repl` (.clj) — Shared interactive eval loop. Parameterizable via `:parser`, `:prelude`. Lang implementations wire into `start` via their lang map `:repl` entry. JVM/Babashka only.
- `meme.tools.run` (.clj) — Shared eval pipeline: source → stages → eval. Parameterizable via `:parser`, `:prelude`. Lang implementations wire into `run-string` and `run-file` via their lang map `:run` entry. JVM/Babashka only.

**Clojure-surface commons** (`meme.tools.clj.*`) — inside the toolkit tier, but with Clojure-specific decisions baked in. Use only from Clojure-flavored langs (meme, implojure); langs with non-Clojure lexical conventions should bring their own lexlets.

- `meme.tools.clj.lex` (.cljc) — Clojure-surface lexical conventions: character predicates (`symbol-start?`, `symbol-char?`, `whitespace-char?`, `newline-char?`, `digit?`), consume helpers (`consume-keyword`, `consume-number`, `consume-char-literal`, `consume-string`, `consume-symbol`), and trivia consumers (`ws-consumer`, `newline-consumer`, `comment-consumer`, `bom-consumer`). Handles comma-as-whitespace, invisible-char rejection in identifiers, `::` auto-resolve keyword syntax, `\uXXXX`/`\oNNN`/named char literals. Portable.
- `meme.tools.clj.errors` (.cljc) — Error infrastructure: `meme-error` (throw with consistent `:line`/`:col` ex-data), `format-error` (display with source context and caret), `source-context`. Uses the **display line model** (`str/split-lines` — splits on `\n` and `\r\n`). `format-error` bridges scanner positions to display: clamps carets when scanner col exceeds display line length (CRLF). Portable.
- `meme.tools.clj.forms` (.cljc) — Shared AST records (`CljSyntaxQuote`, `CljUnquote`, `CljUnquoteSplicing`, `CljRaw`, `CljAutoKeyword`, `CljReaderConditional` polyfill), anonymous-function helpers (`find-percent-params`, `normalize-bare-percent`, `walk-anon-fn-body`), and the internal metadata vocabulary (`:meme/leading-trivia`, `:meme/sugar`, etc.). Portable.
- `meme.tools.clj.resolve` (.cljc) — Native atom resolution: raw token text → Clojure values. Numbers, strings, chars, regex, keywords, tagged literals all resolved natively (no `read-string` delegation). Handles JVM/CLJS asymmetries. Portable.
- `meme.tools.clj.expander` (.cljc) — Syntax-quote expansion: `CljSyntaxQuote` AST nodes → plain Clojure forms (`seq`/`concat`/`list`). Auto-gensym (`foo#`). Called by runtime paths before eval. Also unwraps `CljRaw`. Portable.
- `meme.tools.clj.cst-reader` (.cljc) — CST → Clojure forms: walks CST nodes (`:atom`, `:call`, `:list`, `:vector`, `:map`, `:set`, sugar forms, `:meta`, `:anon-fn`, `:namespaced-map`, `:reader-cond`, `:tagged`, `:error`, etc.) and produces forms with preserved metadata. Portable.
- `meme.tools.clj.stages` (.cljc) — Composable pipeline stages: `step-parse`, `step-read`, `step-evaluate-reader-conditionals`, `step-expand-syntax-quotes`. Each is `ctx → ctx`. `run` composes parse+read (tooling pipeline). `stage-contracts` declares required ctx keys and opts keys; `check-contract!` throws `:meme/pipeline-error` on miscomposition. `step-parse` requires `:grammar` in opts (no implicit default). Portable.
- `meme.tools.clj.values` (.cljc) — Value → string serialization for the printer: atomic Clojure values (strings, numbers, chars, regex, tagged literals). Portable.
- `meme.tools.clj.run` (.clj) — Clojure-surface eval pipeline: source → shebang/BOM strip → stages → eval. Grammar-agnostic (caller passes `:grammar`). `default-resolve-symbol` matches Clojure's `SyntaxQuoteReader`. Installs `meme.loader` unless `:install-loader? false`. JVM/Babashka only.
- `meme.tools.clj.repl` (.clj) — Clojure-surface REPL harness: `input-state` (complete/incomplete/invalid detection), `start`. Default `::kw` resolver via `*ns*` aliases. Grammar-agnostic. JVM/Babashka only.

**Meme language** (`meme-lang.*`) — meme-specific implementation:

- `meme-lang.api` (.cljc) — Public API and lang composition. Provides `meme->forms`, `forms->meme`, `forms->clj`, `clj->forms`, `meme->clj`, `clj->meme`, `format-meme-forms`. Also lang commands: `format-meme`, `to-clj`, `to-meme`, `lang-map`. Injects meme's grammar into the commons pipeline. `clj->forms` and `clj->meme` are JVM only. Portable.
- `meme-lang.grammar` (.cljc) — Meme language grammar spec: maps characters to scanlets and parselets. The complete syntactic specification of M-expression syntax as data. Portable.
- `meme-lang.parselets` (.cljc) — Meme-specific compound parselets: call adjacency detection, `#` dispatch sub-routing, tilde (`~`/`~@`), and the M-expression call rule. Portable.
- `meme-lang.lexlets` (.cljc) — Thin shim forwarding to `meme.tools.clj.lex`. Meme inherits all Clojure lexical conventions; this namespace keeps meme-lang's lexical identity and is the place where any future meme-specific lexical rule would live. Portable.
- `meme-lang.form-shape` (.cljc) — Semantic decomposition of special forms into named slots (`:name`, `:params`, `:bindings`, `:clause`, `:body`, etc.). The middle layer between notation (printer) and style (formatter). Owns the stable slot vocabulary. See `doc/form-shape.md`. Portable.
- `meme-lang.printer` (.cljc) — Wadler-Lindig Doc tree builder: `to-doc` (form → Doc tree). Single source of truth for meme and Clojure output modes. Delegates layout to `meme.tools.render`. Dispatches on form-shape slots and applies style's slot-keyed opinions. Portable.
- `meme-lang.formatter.flat` (.cljc) — Flat formatter: composes printer + render at infinite width. `format-form`, `format-forms`, `format-clj`. Single-line output. Portable.
- `meme-lang.formatter.canon` (.cljc) — Canonical formatter: composes printer + render at target width. `format-form`, `format-forms`. Width-aware multi-line output. Used by `meme format` CLI. Portable.
- `meme-lang.run` (.clj) — Thin shim: injects meme's grammar and delegates to `meme.tools.clj.run`. Re-exports `default-resolve-symbol` for backwards compat. JVM/Babashka only.
- `meme-lang.repl` (.clj) — Thin shim: injects meme's grammar and banner and delegates to `meme.tools.clj.repl`. JVM/Babashka only.

**Shared infrastructure** (`meme.*`, peer to `meme.tools.*`):

- `meme.registry` (.clj) — Lang registry: registration, resolution, and EDN loading. `default-lang`, `resolve-lang`, `supports?`, `check-support`, `load-edn`, `register!`, `register-builtin!`, `register-string-handler!`, `resolve-by-extension`, `registered-langs`, `available-langs`, `builtin-langs`. The registry imports no langs directly — each lang's api ns calls `register-builtin!` on its own load. The CLI is the "app" that triggers built-in registration by explicitly requiring each lang's api namespace. JVM/Babashka only.
- `meme.loader` (.clj) — Namespace loader: intercepts `clojure.core/load` (JVM only) and `clojure.core/load-file` (JVM + Babashka) to handle `.meme` files transparently. Installed automatically by `run-file`, REPL `start`, and the CLI `run` command. `require` finds `.meme` namespaces on the classpath (JVM only — Babashka's SCI bypasses `clojure.core/load`). `load-file` handles `.meme` files by filesystem path on both platforms. `.meme` takes precedence when both `.meme` and `.clj` exist. `install!`/`uninstall!` for manual control. JVM/Babashka only.

**App tier** (`meme.*`):

- `meme.cli` (.clj) — Unified CLI: `run`, `repl`, `to-clj`, `to-meme`, `format`, `transpile` (alias: `compile`), `build`, `inspect`, `version`. Generic dispatcher — commands delegate to lang map functions. Babashka entry point via `bb.edn`.
- `meme.config` (.clj) — Project-local formatter config: reads `.meme-format.edn` (walking up from CWD) and translates it into opts for `canon/format-form`. Schema: `:width`, `:structural-fallback?`, `:form-shape` (symbol → built-in alias), `:style` (partial canon override). Consumed by `meme format` CLI; CLI flags override config. JVM/Babashka only.
- `meme.test-runner` (.clj) — Eval + fixture test runner. Lives in `test/`, not `src/`. JVM only.

### Platform tiers

| Tier | Modules | Platforms |
|------|---------|-----------|
| Generic tools | meme.tools.{parser, lexer, render} | JVM, Babashka, ClojureScript |
| Clojure-surface commons | meme.tools.clj.{lex, errors, forms, resolve, expander, cst-reader, stages, values} | JVM, Babashka, ClojureScript |
| Core translation | meme-lang.{api, grammar, parselets, lexlets, form-shape, printer, formatter.flat, formatter.canon} | JVM, Babashka, ClojureScript |
| Runtime infra | meme.tools.{run, repl}, meme.tools.clj.{run, repl}, meme-lang.{run, repl}, meme.{registry, loader} | JVM, Babashka |
| App | meme.{cli, config} | JVM, Babashka |
| Test infra | meme.test-runner, dogfood-test, vendor-roundtrip-test | JVM only |

## Documentation

- `doc/PRD.md` — Product requirements, requirements table, known limitations, and future work. Update the requirements table when adding or changing reader/printer behavior.
- `doc/language-reference.md` — Complete syntax reference for writing .meme code.
- `doc/design-decisions.md` — Rationale for each design choice.
- `doc/api.md` — Public API reference.
- `doc/form-shape.md` — Slot vocabulary, three-layer formatter architecture, and extension patterns (custom decomposers, structural fallback, slot renderers).

## Testing conventions

- Every bug fix or behavioral change must include a **scar tissue test** — a regression test in the appropriate `test/meme/regression/*_test.cljc` file that prevents the specific issue from recurring.
- Roundtrip tests (read → print → re-read) go in `test/meme/roundtrip_test.cljc`.
- `.meme` example files in `test/examples/tests/` are eval-based (self-asserting). Numeric prefixes (`01_`, `02_`, ...) control execution order — the test runner sorts alphabetically, so fundamentals (core rules, definitions) run before features that build on them. New files should continue the numbering sequence.
- Fixture pairs in `test/examples/fixtures/` compare parsed output against `.edn` expected forms.
- **Vendor roundtrip tests** use git submodules in `test/vendor/` (core.async, specter, malli, ring, clj-http, medley, hiccup). Each `.clj`/`.cljc` file is roundtripped per-form using `:read-cond :preserve` so `ReaderConditional` objects survive the roundtrip. Initialize with `git submodule update --init`. Read errors (Clojure reader limitations) don't fail the test; roundtrip failures do.

### Test file placement

Tests are split across `test/meme_lang/` (language-specific) and `test/meme/` (infrastructure, integration, regression).

| File | What belongs here |
|------|-------------------|
| `meme_lang/stages_test` | Pipeline stages: parse → read, syntax-quote expansion |
| `meme_lang/reader/call_syntax_test` | M-expression call syntax: head type x spacing x arity matrix |
| `meme_lang/reader/calls_test` | All Clojure forms as calls: def, defn, fn, let, loop, for, if, when, cond, try, threading, ns, protocols, records, multimethods, concurrency, "everything is a call" |
| `meme_lang/reader/interop_test` | Java interop: .method, Class/static, .-field, constructors |
| `meme_lang/reader/data_literals_test` | Data literal passthrough: vectors, maps, sets, keywords, numbers |
| `meme_lang/reader/dispatch_test` | Reader macros and dispatch: @, ^, ', #', #_, #(), regex, char, tagged literals, reader conditionals, namespaced maps |
| `meme_lang/reader/errors_test` | Error cases, rejected forms (unquote outside backtick), error messages with locations, CLJS-specific errors |
| `meme_lang/expander_test` | Syntax-quote expansion: `expand-forms` passthrough, `MemeSyntaxQuote` expansion, `MemeRaw` unwrapping |
| `meme_lang/resolve_test` | Value resolution: numbers, strings, chars, regex, keywords, tagged literals |
| `meme_lang/form_shape_test` | Form-shape decomposition: per-form decomposer output, structural fallback, registry extension |
| `meme_lang/printer_test` | Printer-level seams: `:slot-renderers` override, default slot renderers, unknown-slot fallback |
| `meme_lang/formatter/flat_test` | Flat formatter: single-line meme/clj output, reader sugar, individual form cases |
| `meme_lang/formatter/canon_test` | Canonical formatter: width-aware formatting, multi-line layout, comments |
| `meme/tools/render_test` | Doc algebra and layout engine |
| `meme_lang/values_test` | Value serialization: regex, chars, numbers, strings |
| `meme_lang/forms_test` | Form predicates and contracts: AST nodes, metadata, reader conditionals |
| `meme_lang/errors_test` | Error infrastructure: `source-context`, `meme-error`, `format-error` |
| `meme/roundtrip_test` | Read → print → re-read identity. Structural invariant tests. |
| `meme/regression/scan_test` | Scar tissue: scanner bugs (bracket depth, char/string in syntax-quote, symbol parsing, EOF handling, keyword validation, unterminated literals) |
| `meme/regression/reader_test` | Scar tissue: parser bugs (discard sentinel, depth limits, head types, spacing, duplicates, metadata) |
| `meme/regression/emit_test` | Scar tissue: printer and formatter bugs (regex escaping, reader-sugar formatting, deferred auto-keywords, metadata, comments, width) |
| `meme/regression/errors_test` | Scar tissue: error infrastructure and resolve error-wrapping bugs (source-context, gutter width, CLJS guards) |
| `meme_lang/api_test` | Language API (`meme->forms`, `forms->meme`, `format-meme-forms`, etc.) |
| `meme/registry_test` | Lang registry: command maps, EDN loading, extension dispatch, user lang registration. JVM only. |
| `meme/cli_test` | CLI unit tests: file type checking, extension swapping |
| `meme/config_test` | `.meme-format.edn` validation, discovery (walking up), EDN parsing, config→opts translation |
| `e2e/cli_test` | End-to-end CLI integration tests. JVM only. |
| `meme_lang/repl_test` | REPL infrastructure (`input-state`, `read-input`). JVM only. |
| `meme_lang/run_test` | File runner: `run-string`, `run-file`, shebang handling, custom eval-fn |
| `meme/loader_test` | Namespace loader: `load` interception, classpath `.meme` discovery, `install!`/`uninstall!` lifecycle. JVM/Babashka. |
| `meme/examples_test` | Integration scenarios, multi-feature examples |
| `meme/emit_fixtures_test` | meme↔clj conversion fixture validation. JVM only. |
| `meme/dogfood_test` | Meta: meme roundtrips its own source files |
| `meme/snapshot_test` | Characterization tests: exact token and form snapshots. Regression net for stage refactoring. |
| `meme/generative_test` | Property-based tests with test.check. Print→read roundtrip on generated forms. JVM only. |
| `meme/generative_cljs_test` | Cross-platform property-based tests. |
| `meme/vendor_roundtrip_test` | Vendor roundtrip: real-world Clojure libraries (git submodules in `test/vendor/`) roundtripped per-form through clj→meme→clj. JVM only. |

## Development tools

### clojure-lsp

clojure-lsp (with clj-kondo) provides useful static analysis for development, testing, and debugging:

- **Symbol navigation**: `documentSymbol` lists all defs/defns in a file; `workspaceSymbol` searches across the entire codebase.
- **Cross-references**: `findReferences` traces usage across all source and test files.
- **Go to definition / hover**: Jump to any symbol's source or get inline docs.
- **Call hierarchy**: `incomingCalls`/`outgoingCalls` map the call graph between namespaces.
- **Diagnostics (clj-kondo)**: Catches unused requires, unresolved symbols, unused bindings. Known noise to ignore:
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
