# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**meme-clj** is a syntax-experimentation toolkit and research programme for
Clojure. The toolkit (parser engine, AST, stages, printer, formatter, loader,
registry, CLI) hosts a family of guest languages on a shared Clojure-surface
backbone.

**m1clj** is the first language built on the toolkit — M-expressions for
Clojure, in the spirit of McCarthy (1960). One rule:

**Call**: `f(x y)` → `(f x y)` — the head of a list is written outside the parens, adjacent to `(` (spacing significant: `f(x)` is a call, `f ()` is two forms)

Everything else (data literals, reader syntax, destructuring, commas-as-whitespace) is unchanged from Clojure. The CLI is self-hosted in `.m1clj`. Programs run on Babashka, Clojure JVM, or ClojureScript without modification.

Two more guests are bundled today:

- **`m2clj`** — m1clj plus one rule: bare-paren forms with no head adjacency
  (e.g. `(x y z)`) lower to `(quote (x y z))` instead of being parse errors.
  Calls still require head adjacency, so call vs data is structural at the
  reader layer.
- **`clj-lang`** — native S-expression Clojure surface, registered on the
  same toolkit. Demonstrates that the parser, AST, stages, and printer are
  language-agnostic; only the grammar and the few syntax-specific parselets
  differ between guests.

> **Naming.** "meme-clj" is the toolkit / programme; "m1clj", "m2clj", and
> "clj" are languages (each is its own bundled guest). The `meme` binary is
> the toolkit's CLI. Toolkit namespaces (`meme.tools.*`, `meme.registry`,
> `meme.loader`, `meme.cli`) keep the historic `meme` prefix. Each guest
> lives under its own `<lang>-lang.*` namespace tree (`m1clj-lang.*`,
> `m2clj-lang.*`, `clj-lang.*`). See the **Glossary** section at the end
> of this file for the full vocabulary.

> **Direction.** Each guest is sovereign and lives in its own top-level
> directory: `m1clj-lang/`, `m2clj-lang/`, `clj-lang/`. The meme-clj
> substrate (toolkit + CLI as one artifact) lives at `src/meme/`. Today
> everything still resolves through one `deps.edn` with multiple `:paths`
> roots; the eventual move to one Clojars artifact per directory is
> additive from here. Don't dedupe across `<lang>-lang.*` directories —
> temporal similarity between sovereign langs is fine; shared code belongs
> in `meme.tools.*` / `meme.tools.clj.*` only when it's genuinely
> toolkit-level.

## Build & Test

```bash
# Unit tests (JVM Clojure) — CI uses -J-Xss4m for deep recursion tests
clojure -X:test

# .m1clj example tests (Babashka) — runs test/examples/tests/*.m1clj
bb test-meme

# ClojureScript tests (Node.js, cleans up build artifacts)
bb test-cljs

# All test suites (Babashka + JVM + ClojureScript)
bb test-all

# Coverage-guided fuzzing (requires Jazzer — see deps.edn :fuzzer alias)
bb fuzz-build          # Build fuzzer uberjar
bb fuzz-quick          # 50K runs per target (~3 min)

# Run a .m1clj file
bb meme run file.m1clj

# Start m1clj REPL
bb meme repl

# Convert between m1clj and Clojure
bb meme to-clj file.m1clj     # .m1clj → Clojure
bb meme to-m1clj file.clj     # .clj → .m1clj

# Format .m1clj files (normalize syntax via canonical formatter)
bb meme format file.m1clj     # in-place
bb meme format src/          # directory, recursive
bb meme format file.m1clj --stdout  # print to stdout

# Compile .m1clj to .clj (for classpath use without runtime patching)
bb meme transpile src/            # output to target/m1clj (default; alias: `compile`)
bb meme transpile src/ --out out/ # custom output directory

# Build to JVM bytecode (transpile + AOT compile)
bb meme build src/                # .class files in target/classes
```

No external runtime dependencies. Only requires Clojure or Babashka. The `:test`, `:cljs-test`, `:e2e`, and `:fuzzer` aliases in `deps.edn` pull in test-runner, clj-kondo, test.check, ClojureScript, cljs-test-runner, and Jazzer — those are dev-time only. ClojureScript tests require Node.js.

```bash
# Build JAR
clojure -T:build jar

# Deploy to Clojars
clojure -T:build deploy
```

## Architecture

```
.m1clj source ─► parser ─► CST ─► AST ─► forms (eval-ready)
                (step-parse)   (cst→ast)  (ast→form / step-read)
                                  ▲
                       lossless tier (trivia, pos, notation
                       on record fields — survives walkers)
```

The pipeline has two consumer-facing exits:
- **AST** (lossless) for tooling — formatters, refactorers, transpilers.
- **Forms** (structural) for evaluation.

Both `m1clj-lang` and `clj-lang` share the AST tier and the lowering pass;
only the parser grammar differs.

The codebase is organized by *kind* of code, with shared infrastructure that both language implementations and the CLI depend on:

- **`meme.tools.*`** — Generic, language-agnostic building blocks (parser engine, scanlet builders, render engine).
- **`meme.tools.clj.*`** — Clojure-surface commons shared across any Clojure-flavored frontend: lexical conventions, CST reader, stages framework, error infrastructure, atom resolution, syntax-quote expander, the `Clj*` AST records, value serialization, eval pipeline, and REPL harness. Sits inside the toolkit tier but carries Clojure bias explicitly in the path.
- **`m1clj-lang.*`** — m1clj language implementation. The *syntactic* surface lives here (grammar, parselets, printer, form-shape, formatters). Infrastructure files like `m1clj-lang.lexlets`, `m1clj-lang.run`, `m1clj-lang.repl` are thin shims that inject m1clj's grammar/banner and delegate to `meme.tools.clj.*`.
- **`clj-lang.*`** — Native Clojure surface as a sibling guest. Registers `:clj` with the registry; reuses `meme.tools.clj.parser.*` for parsing and `m1clj-lang.formatter.*` for printing.
- **`meme.registry`, `meme.loader`** — Shared runtime infrastructure, peer to `meme.tools.*`. Both langs and the CLI depend on them: langs push themselves into the registry at load time and rely on the loader for `require`/`load-file`; the CLI dispatches through the registry.
- **`meme.cli`** — App tier. Only consumer-facing code lives here.

This is a "kinds + infrastructure" layout, not a strict top-down tiered architecture. `m1clj-lang.api` requiring `meme.registry` (for self-registration) and `m1clj-lang.run` requiring `meme.loader` (for auto-install) are intentional — the infrastructure is shared. The real layering rule is: **`m1clj-lang.*` must not require `meme.cli`**, and **`meme.tools.{parser,lexer,render}` plus `meme.tools.clj.*` must not require anything from `m1clj-lang.*` or `meme.*`** — with one carve-out: `meme.tools.clj.run` and `meme.tools.clj.repl` depend on `meme.loader` so embedders get `.m1clj` `require`/`load-file` for free. That coupling is intentional and narrow: each calls `loader/install!`, plus `meme.tools.clj.run` calls `loader/warn-deprecated-extension!` for the soft-deprecated `.meme*` extensions. No other `meme.tools.*` ns reaches into `meme.*`.

Composable stages (`meme.tools.clj.stages`), each a `ctx → ctx` function. Tooling paths compose 1–2; eval paths (`run-string`, `run-file`, REPL) compose 1–4. `step-parse` requires `:grammar` in opts (no implicit default) — each lang passes its own grammar explicitly:
1. **step-parse** (`meme.tools.parser` driven by a lang-supplied grammar) — Unified scanlet-parselet Pratt parser. Reads directly from source string. Scanning (character dispatch, trivia) and parsing (structure) are both defined in the grammar spec as scanlets and parselets. Produces a lossless CST preserving every token.
2. **step-read** (`meme.tools.clj.cst-reader`) — lowers CST to Clojure forms. Reader conditionals (`#?`, `#?@`) are preserved as `CljReaderConditional` records.
3. **step-evaluate-reader-conditionals** (`meme.tools.clj.stages`) — materializes the platform branch of `#?`/`#?@` for eval paths. Tooling paths skip this step; records stay records. Supports `:platform` opt and `:default` fallback.
4. **step-expand-syntax-quotes** (`meme.tools.clj.expander`) — syntax-quote AST nodes → plain Clojure forms. Only needed before eval, not for tooling.

The pipeline is lossless (CST preserves all tokens including delimiters and trivia) and data-driven (the Pratt parser is generic — m1clj syntax is defined by a grammar spec in `m1clj-lang.grammar`; native Clojure syntax is defined by `meme.tools.clj.parser.grammar`).

The generic parser engine, scanlet builders, and render engine live in `meme.tools.*` — they are language-agnostic and reusable. m1clj-specific grammar, scanlets (lexlets), parselets, and form-shape live in `m1clj-lang.*`.

### Formatter architecture — three layers

The printer/formatter split follows a three-layer model. Each layer owns one concern; they compose via plain data:

1. **Notation** (`m1clj-lang.printer`) — how a call renders (parens, delimiter placement, `:m1clj` vs `:clj` output mode). Knows nothing about form names or slot semantics beyond the fallback recursion.
2. **Form-shape** (`m1clj-lang.form-shape`) — what the parts of a special form *mean*. A registry maps head symbols to decomposers; each decomposer produces `[[slot-name value] ...]`. Lang-owned: each lang carries its own registry. See `m1clj-lang/CLAUDE.md` for the slot vocabulary.
3. **Style** (`m1clj-lang.formatter.canon/style` and alternatives) — opinions *per slot name*, not per form. `:head-line-slots` keeps named slots with the call head on break; `:force-open-space-for` controls the `head( ` convention; `:slot-renderers` overrides the printer defaults for `:bindings`/`:clause`/custom slots.

All four extension axes compose via `assoc`/`merge` on plain maps: swap a style, extend a registry, opt into structural fallback (`with-structural-fallback`), override one slot's rendering. No printer changes required for any of them.

- The reader is a **pure function** from m1clj text to Clojure forms. No runtime dependency. No `read-string` delegation — everything is parsed natively.
- A printer (`m1clj-lang.printer`) converts Clojure forms back to m1clj syntax (also pure). Supports `:m1clj` and `:clj` output modes.
- **Syntactic transparency:** m1clj is a syntactic lens — the AST tier must preserve the user's syntax choices. When two notations produce the same Clojure form (e.g., `'x` sugar vs `quote(x)` call), the AST records the distinction on a node field so the printer can reconstruct the original notation. See `doc/design-decisions.md` for the full principle. Any new syntax feature with multiple representations MUST preserve the distinction in the AST.
- File extension: `.m1clj`
- `()` is the empty list. Every `(content)` requires a head: `head(content)`. Any value can be a head — `nil(1 2)` → `(nil 1 2)`, `true(:a)` → `(true :a)`.
- All `#` dispatch forms (`#?`, `#?@`, `#:ns{}`, `#{}`, `#""`, `#'`, `#_`, `#()`, tagged literals) and syntax-quote (`` ` ``) are parsed natively with m1clj rules inside. No opaque regions.

### Key namespaces

**Generic tools** (`meme.tools.*`) — language-agnostic, reusable:

- `meme.tools.parser` (.cljc) — Unified scanlet-parselet Pratt parser engine. Reads directly from a source string. The grammar spec defines character dispatch (scanlets), trivia classification, prefix parselets (nud), and postfix rules (led). Parselet factories (`nud-atom`, `nud-prefix`, `nud-delimited`, `led-call`, `led-infix`) generate common patterns. `parse` takes source + grammar spec, produces a CST. Portable.
- `meme.tools.lexer` (.cljc) — Generic scanlet builders. Wraps language-specific consume functions into scanlets that the grammar spec can reference. Bridges "consume characters from source" to "produce a CST node". Portable.
- `meme.tools.render` (.cljc) — Wadler-Lindig document algebra and layout engine: `DocText`, `DocLine`, `DocCat`, `DocNest`, `DocGroup`, `DocIfBreak`, `layout` (Doc tree → string at given width). Pure, no language-specific logic. Portable.
- `meme.tools.repl` (.clj) — Shared interactive eval loop. Parameterizable via `:parser`, `:prelude`. Lang implementations wire into `start` via their lang map `:repl` entry. JVM/Babashka only.
- `meme.tools.run` (.clj) — Shared eval pipeline: source → stages → eval. Parameterizable via `:parser`, `:prelude`. Lang implementations wire into `run-string` and `run-file` via their lang map `:run` entry. JVM/Babashka only.

**Clojure-surface commons** (`meme.tools.clj.*`) — inside the toolkit tier, but with Clojure-specific decisions baked in. Intended for Clojure-flavored langs; langs with non-Clojure lexical conventions should bring their own lexlets.

- `meme.tools.clj.lex` (.cljc) — Clojure-surface lexical conventions: character predicates (`symbol-start?`, `symbol-char?`, `whitespace-char?`, `newline-char?`, `digit?`), consume helpers (`consume-keyword`, `consume-number`, `consume-char-literal`, `consume-string`, `consume-symbol`), and trivia consumers (`ws-consumer`, `newline-consumer`, `comment-consumer`, `bom-consumer`). Handles comma-as-whitespace, invisible-char rejection in identifiers, `::` auto-resolve keyword syntax, `\uXXXX`/`\oNNN`/named char literals. Portable.
- `meme.tools.clj.errors` (.cljc) — Error infrastructure: `meme-error` (throw with consistent `:line`/`:col` ex-data), `format-error` (display with source context and caret), `source-context`. Uses the **display line model** (`str/split-lines` — splits on `\n` and `\r\n`). `format-error` bridges scanner positions to display: clamps carets when scanner col exceeds display line length (CRLF). Portable.
- `meme.tools.clj.forms` (.cljc) — Shared AST records (`CljSyntaxQuote`, `CljUnquote`, `CljUnquoteSplicing`, `CljRaw`, `CljAutoKeyword`, `CljReaderConditional` polyfill), anonymous-function helpers (`find-percent-params`, `normalize-bare-percent`, `walk-anon-fn-body`), and the internal metadata vocabulary (`:m1clj/leading-trivia`, `:m1clj/sugar`, etc.). Portable.
- `meme.tools.clj.resolve` (.cljc) — Native atom resolution: raw token text → Clojure values. Numbers, strings, chars, regex, keywords, tagged literals all resolved natively (no `read-string` delegation). Handles JVM/CLJS asymmetries. Portable.
- `meme.tools.clj.expander` (.cljc) — Syntax-quote expansion: `CljSyntaxQuote` AST nodes → plain Clojure forms (`seq`/`concat`/`list`). Auto-gensym (`foo#`). Called by runtime paths before eval. Also unwraps `CljRaw`. Portable.
- `meme.tools.clj.cst-reader` (.cljc) — CST → Clojure forms: walks CST nodes (`:atom`, `:call`, `:list`, `:vector`, `:map`, `:set`, `:bare-list`, sugar forms, `:meta`, `:anon-fn`, `:namespaced-map`, `:reader-cond`, `:tagged`, `:error`, etc.) and produces forms with preserved metadata. Portable.
- `meme.tools.clj.stages` (.cljc) — Composable pipeline stages: `step-parse`, `step-read`, `step-evaluate-reader-conditionals`, `step-expand-syntax-quotes`. Each is `ctx → ctx`. `run` composes parse+read (tooling pipeline). `stage-contracts` (public data) declares required ctx keys and opts keys per stage; each stage validates its ctx against the contract at entry and throws `:m1clj/pipeline-error` on miscomposition via an internal `check-contract!`. `step-parse` requires `:grammar` in opts (no implicit default). Portable.
- `meme.tools.clj.values` (.cljc) — Value → string serialization for the printer: atomic Clojure values (strings, numbers, chars, regex, tagged literals). Portable.
- `meme.tools.clj.parser.api` (.cljc) — Public surface for the native Clojure parser: `clj->ast`, `clj->forms`. Used by `clj-lang` and as the implementation behind every guest's `clj->m1clj` / `clj->m2clj` lossless converter. Portable.
- `meme.tools.clj.parser.grammar` (.cljc) — Native Clojure grammar spec for the Pratt parser. Defines the S-expression `(...)` list parselet and the shared dispatch behaviors. Portable.
- `meme.tools.clj.parser.parselets` (.cljc) — Shared compound parselets reused by every Clojure-surface guest: `#` sub-routing (reader macros), tilde dispatch (unquote/unquote-splicing), syntax-quote expansion entry. Portable.
- `meme.tools.clj.ast.nodes` (.cljc) — The lossless `Clj*` AST record types — position, leading/trailing trivia, and notation as record fields (so they survive walkers). Includes `CljRoot`, `CljList`, `CljCall`, `CljVector`, `CljMap`, `CljSet`, `CljQuote`, `CljSyntaxQuote`, `CljMeta`, `CljAnonFn`, `CljNamespacedMap`, `CljReaderConditional`, `CljTagged`, `CljDiscard`, etc. Portable.
- `meme.tools.clj.ast.build` (.cljc) — CST → AST: lifts the lossless CST into `Clj*` records, attaching position and trivia as fields. Lang-agnostic — the `:bare-list` CST node lifts to `CljQuote{form: CljList, notation: :bare}` for any guest that produces it. Portable.
- `meme.tools.clj.ast.lower` (.cljc) — AST → forms: lowers `Clj*` records to plain Clojure values for eval. The structural exit point of the pipeline; tooling consumers stop at the AST. Portable.
- `meme.tools.clj.run` (.clj) — Clojure-surface eval pipeline: source → shebang/BOM strip → stages → eval. Grammar-agnostic (caller passes `:grammar`). `default-resolve-symbol` matches Clojure's `SyntaxQuoteReader`. Installs `meme.loader` unless `:install-loader? false`. JVM/Babashka only.
- `meme.tools.clj.repl` (.clj) — Clojure-surface REPL harness: `input-state` (complete/incomplete/invalid detection), `start`. Default `::kw` resolver via `*ns*` aliases. Grammar-agnostic. JVM/Babashka only.

**Per-guest documentation** — each lang owns its own contract, file map, and divergence notes:

- `m1clj-lang/CLAUDE.md` — m1clj (M-expressions for Clojure). Includes the syntax quick reference for writing `.m1clj` code.
- `m2clj-lang/CLAUDE.md` — m2clj (m1clj plus bare-paren-as-list-literal). The surgical diff from m1clj is concentrated in three files; do not lift the rest into shared modules.
- `clj-lang/CLAUDE.md` — clj-lang (native S-expression Clojure surface as a sibling guest, ~100 lines, mostly a registration shim).

When working in a single lang's tree, treat that lang's `CLAUDE.md` as the local source of truth — it covers the per-guest invariants and divergence points without dragging the others into context.

**Shared infrastructure** (`meme.*`, peer to `meme.tools.*`):

- `meme.registry` (.clj) — Lang registry: registration, resolution, and EDN loading. `default-lang`, `resolve-lang`, `supports?`, `check-support`, `load-edn`, `register!`, `register-builtin!`, `register-string-handler!`, `resolve-by-extension`, `registered-langs`, `available-langs`, `builtin-langs`. The registry imports no langs directly — each lang's api ns calls `register-builtin!` on its own load. The CLI is the "app" that triggers built-in registration by explicitly requiring each lang's api namespace. JVM/Babashka only.
- `meme.loader` (.clj) — Namespace loader: intercepts `clojure.core/load` (JVM only) and `clojure.core/load-file` (JVM + Babashka) to handle `.m1clj` files transparently. Installed automatically by `run-file`, REPL `start`, and the CLI `run` command. `require` finds `.m1clj` namespaces on the classpath (JVM only — Babashka's SCI bypasses `clojure.core/load`). `load-file` handles `.m1clj` files by filesystem path on both platforms. `.m1clj` takes precedence when both `.m1clj` and `.clj` exist. `install!`/`uninstall!` for manual control. JVM/Babashka only.

**App tier** (`meme.*`):

- `meme.cli` (.clj) — Unified CLI: `run`, `repl`, `to-clj`, `to-m1clj`, `format`, `transpile` (alias: `compile`), `build`, `inspect`, `version`. Generic dispatcher — commands delegate to lang map functions. Babashka entry point via `bb.edn`.
- `meme.test-runner` (.clj) — Eval + fixture test runner. Lives in `test/`, not `src/`. JVM only.

### Platform tiers

| Tier | Modules | Platforms |
|------|---------|-----------|
| Generic tools | meme.tools.{parser, lexer, render} | JVM, Babashka, ClojureScript |
| Clojure-surface commons | meme.tools.clj.{lex, errors, forms, resolve, expander, cst-reader, stages, values}, meme.tools.clj.parser.{api, grammar, parselets}, meme.tools.clj.ast.{nodes, build, lower} | JVM, Babashka, ClojureScript |
| Core translation (per guest) | m1clj-lang.{api, grammar, parselets, lexlets, form-shape, printer, formatter.flat, formatter.canon}, m2clj-lang.{api, grammar, parselets, lexlets, form-shape, printer, formatter.flat, formatter.canon}, clj-lang.api | JVM, Babashka, ClojureScript |
| Runtime infra | meme.tools.{run, repl}, meme.tools.clj.{run, repl}, m1clj-lang.{run, repl}, m2clj-lang.{run, repl}, meme.{registry, loader} | JVM, Babashka |
| App | meme.cli | JVM, Babashka |
| Test infra | meme.test-runner, dogfood-test, vendor-roundtrip-test | JVM only |

## Documentation

- `doc/PRD.md` — Product requirements, requirements table, known limitations, and future work. Update the requirements table when adding or changing reader/printer behavior.
- `doc/language-reference.md` — Complete syntax reference for writing .m1clj code.
- `doc/design-decisions.md` — Rationale for each design choice.
- `doc/api.md` — Public API reference.
- `m1clj-lang/CLAUDE.md` — Slot vocabulary, three-layer formatter architecture, and extension patterns (custom decomposers, structural fallback, slot renderers).

## Testing conventions

- Every bug fix or behavioral change must include a **scar tissue test** — a regression test in the appropriate `test/meme/regression/*_test.cljc` file that prevents the specific issue from recurring.
- Roundtrip tests (read → print → re-read) go in `test/meme/roundtrip_test.cljc`.
- `.m1clj` example files in `test/examples/tests/` are eval-based (self-asserting). Numeric prefixes (`01_`, `02_`, ...) control execution order — the test runner sorts alphabetically, so fundamentals (core rules, definitions) run before features that build on them. New files should continue the numbering sequence.
- Fixture pairs in `test/examples/fixtures/` compare parsed output against `.edn` expected forms.
- **Vendor roundtrip tests** use git submodules in `test/vendor/` (core.async, specter, malli, ring, clj-http, medley, hiccup). Each `.clj`/`.cljc` file is roundtripped per-form (clj→m1clj→clj) using `:read-cond :preserve` so `ReaderConditional` objects survive the roundtrip. Initialize with `git submodule update --init`. Read errors (Clojure reader limitations) don't fail the test; roundtrip failures do.

### Test file placement

Tests follow the directory split. Each guest owns its own tests; the meme-clj substrate (toolkit + CLI) and cross-lang tests share `test/`:

- `<lang>-lang/test/` — language-specific tests (one tree per guest: `m1clj-lang/test/m1clj_lang/`, `m2clj-lang/test/m2clj_lang/`, `clj-lang/test/clj_lang/`).
- `test/meme/` — meme-clj substrate unit tests (`registry_test`, `loader_test`, `tools/*`), CLI tests (`cli_test`, `test_runner`), and cross-lang integration (dogfood, vendor, examples, snapshot, roundtrip, generative, regression scars). `test_util` is the shared helper.
- `test/e2e/` — end-to-end CLI tests (shell out to `bb meme`).

Do not put cross-lang tests under a single guest's directory.

| File | What belongs here |
|------|-------------------|
| `m1clj_lang/stages_test` | Pipeline stages: parse → read, syntax-quote expansion |
| `m1clj_lang/reader/call_syntax_test` | M-expression call syntax: head type x spacing x arity matrix |
| `m1clj_lang/reader/calls_test` | All Clojure forms as calls: def, defn, fn, let, loop, for, if, when, cond, try, threading, ns, protocols, records, multimethods, concurrency, "everything is a call" |
| `m1clj_lang/reader/interop_test` | Java interop: .method, Class/static, .-field, constructors |
| `m1clj_lang/reader/data_literals_test` | Data literal passthrough: vectors, maps, sets, keywords, numbers |
| `m1clj_lang/reader/dispatch_test` | Reader macros and dispatch: @, ^, ', #', #_, #(), regex, char, tagged literals, reader conditionals, namespaced maps |
| `m1clj_lang/reader/errors_test` | Error cases, rejected forms (unquote outside backtick), error messages with locations, CLJS-specific errors |
| `meme/tools/clj/expander_test` | Syntax-quote expansion: `expand-forms` passthrough, `CljSyntaxQuote` expansion, `CljRaw` unwrapping |
| `meme/tools/clj/resolve_test` | Value resolution: numbers, strings, chars, regex, keywords, tagged literals |
| `m1clj_lang/form_shape_test` | Form-shape decomposition: per-form decomposer output, structural fallback, registry extension |
| `m1clj_lang/printer_test` | Printer-level seams: `:slot-renderers` override, default slot renderers, unknown-slot fallback |
| `m1clj_lang/formatter/flat_test` | Flat formatter: single-line `:m1clj`/`:clj` output, reader sugar, individual form cases |
| `m1clj_lang/formatter/canon_test` | Canonical formatter: width-aware formatting, multi-line layout, comments |
| `meme/tools/render_test` | Doc algebra and layout engine |
| `meme/tools/clj/values_test` | Value serialization: regex, chars, numbers, strings |
| `meme/tools/clj/forms_test` | Form predicates and contracts: AST nodes, metadata, reader conditionals |
| `meme/tools/clj/errors_test` | Error infrastructure: `source-context`, `meme-error`, `format-error` |
| `meme/roundtrip_test` | Read → print → re-read identity. Structural invariant tests. |
| `meme/regression/scan_test` | Scar tissue: scanner bugs (bracket depth, char/string in syntax-quote, symbol parsing, EOF handling, keyword validation, unterminated literals) |
| `meme/regression/reader_test` | Scar tissue: parser bugs (discard sentinel, depth limits, head types, spacing, duplicates, metadata) |
| `meme/regression/emit_test` | Scar tissue: printer and formatter bugs (regex escaping, reader-sugar formatting, deferred auto-keywords, metadata, comments, width) |
| `meme/regression/errors_test` | Scar tissue: error infrastructure and resolve error-wrapping bugs (source-context, gutter width, CLJS guards) |
| `m1clj_lang/api_test` | Language API (`m1clj->forms`, `forms->m1clj`, `format-m1clj-forms`, etc.) |
| `meme/registry_test` | Lang registry: command maps, EDN loading, extension dispatch, user lang registration. JVM only. |
| `meme/cli_test` | CLI unit tests: file type checking, extension swapping |
| `e2e/cli_test` | End-to-end CLI integration tests. JVM only. |
| `m1clj_lang/repl_test` | REPL infrastructure (`input-state`, `read-input`). JVM only. |
| `m1clj_lang/run_test` | File runner: `run-string`, `run-file`, shebang handling, custom eval-fn |
| `meme/loader_test` | Namespace loader: `load` interception, classpath `.m1clj` discovery, `install!`/`uninstall!` lifecycle. JVM/Babashka. |
| `meme/examples_test` | Integration scenarios, multi-feature examples |
| `meme/emit_fixtures_test` | m1clj↔clj conversion fixture validation. JVM only. |
| `meme/dogfood_test` | Meta: m1clj roundtrips its own source files |
| `meme/snapshot_test` | Characterization tests: exact token and form snapshots. Regression net for stage refactoring. |
| `meme/generative_test` | Property-based tests with test.check. Print→read roundtrip on generated forms. JVM only. |
| `meme/generative_cljs_test` | Cross-platform property-based tests. |
| `meme/vendor_roundtrip_test` | Vendor roundtrip: real-world Clojure libraries (git submodules in `test/vendor/`) roundtripped per-form through clj→m1clj→clj. JVM only. |
| `meme/tools/parser_test` | Generic Pratt parser engine: grammar-driven scanning, precedence, depth guards. |
| `meme/tools/lexer_test` | Scanlet builders (wrappers that turn consume-fns into scanlet nodes). |
| `meme/tools/run_test` | Generic run pipeline (grammar-agnostic): source → stages → eval, error paths, custom eval-fn halt semantics. |
| `meme/tools/clj/cst_reader_test` | CST → Clojure forms: node types (atom/call/list/vector/map/set/bare-list/meta/anon-fn/namespaced-map/reader-cond/tagged), metadata propagation. |
| `meme/tools/clj/lex_test` | Clojure-surface lexical conventions: char predicates, consume helpers, trivia consumers. |
| `meme/tools/clj/parser/api_test` | Native Clojure parser public surface: `clj->ast`, `clj->forms`. |
| `meme/tools/clj/parser/grammar_test` | Native Clojure grammar spec: list parselet, dispatch routing. |
| `meme/tools/clj/ast/nodes_test` | AST record types: field round-trip, predicates, equality. |
| `meme/tools/clj/ast/equivalence_test` | AST tier semantic equivalence: build → lower → expand collapses to the same plain forms across guests. |
| `m2clj_lang/api_test` | m2clj language API surface (mirrors `m1clj_lang/api_test`). |
| `m2clj_lang/printer_test` | m2clj printer seams — bare-paren rendering of `CljQuote{notation: :bare}` and `(quote …)` canonicalization. |
| `m2clj_lang/reader/bare_paren_test` | The distinguishing m2clj rule: bare-paren forms lower to `(quote …)`; head-adjacency still required for calls. |
| `clj_lang/api_test` | clj-lang surface: native S-expression parsing through the toolkit's pipeline. |

## Development tools

### clojure-lsp

clojure-lsp (with clj-kondo) provides useful static analysis for development, testing, and debugging:

- **Symbol navigation**: `documentSymbol` lists all defs/defns in a file; `workspaceSymbol` searches across the entire codebase.
- **Cross-references**: `findReferences` traces usage across all source and test files.
- **Go to definition / hover**: Jump to any symbol's source or get inline docs.
- **Call hierarchy**: `incomingCalls`/`outgoingCalls` map the call graph between namespaces.
- **Diagnostics (clj-kondo)**: Catches unused requires, unresolved symbols, unused bindings. Known noise to ignore:
  - `generative_test.clj` "unresolved symbol" errors — macro-generated `deftest` names from `defspec`.
  - `.cljc` files with `#?` reader conditionals — clj-kondo analyzes one platform branch and flags requires/vars used only in the other branch as unused. Affects `meme/tools/clj/resolve.cljc`, `m1clj_lang/repl_test.cljc`, `meme/tools/clj/errors_test.cljc`, `meme/regression/errors_test.cljc`, `m1clj_lang/reader/dispatch_test.cljc`, `meme/regression/scan_test.cljc`.
  - `repl.clj` "unused public var `start`" / `test_runner.clj` "unused public var `run-all-meme-tests`" — entry points called externally (bb.edn, CLI), not from Clojure source.

clojure-lsp is configured via the `.claude-plugin/` directory for Claude Code integration. Requires `clojure-lsp` on PATH (`brew install clojure-lsp/brew/clojure-lsp`).

## Per-lang syntax quick references

Each guest's syntax cheatsheet lives next to its source:

- `m1clj-lang/CLAUDE.md` — m1clj M-expression syntax (the dominant surface; CLI default).
- `m2clj-lang/CLAUDE.md` — m2clj's surgical diff from m1clj (bare-paren-as-list).
- `clj-lang/CLAUDE.md` — clj-lang (native Clojure, no surface changes).

For the full m1clj language spec, see `doc/language-reference.md`.

## Glossary

Source of truth for the names this project uses. Every doc, docstring, and prose line should mean exactly what this section says — no drift, no aliases.

The point is to fix the **programme / language / toolkit** distinction so the prose stops conflating them.

### The programme

**meme-clj** — a syntax-experimentation toolkit and research programme for Clojure. The repo, the Clojars artifact, the GitHub project. *Not* a language. The programme's claim is that a Clojure host can carry alternative surface syntaxes without giving up macros, semantics, or ecosystem. The toolkit is the durable artifact; specific languages are experiments built on top of it.

### The languages

- **m1clj** — the **first** language of the programme. M-expressions for Clojure, in the spirit of McCarthy (1960). One rule: `f(x y)` → `(f x y)`. File extension: `.m1clj`. Registry key: `:m1clj`.
- **m2clj** — m1clj plus one rule: a paren without head adjacency (`(x y z)`) is a list literal that lowers to `(quote (x y z))` instead of being a parse error. Calls still require head adjacency (`f(x y)`), so call-vs-data remains structural at the reader layer. File extension: `.m2clj`. Registry key: `:m2clj`.
- **clj** (lang) — native Clojure surface (S-expressions) registered with the toolkit as a sibling guest. Proves the toolkit is genuinely language-agnostic. Registry key: `:clj`. File extensions: `.clj` / `.cljc` / `.cljs`.

Future guests are expected. The plural is the whole point. Each guest is **sovereign**: even when two langs look temporally similar (m1clj and m2clj share most of their printer and form-shape today), the duplication is intentional — langs may diverge, and the architectural direction is one Clojars artifact per lang.

### The toolkit (`meme.*`)

These are programme-level names — they refer to the toolkit, never to a specific language.

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

The CLI binary is called `meme` because it dispatches on behalf of the toolkit to whichever lang is registered for the input. `bb meme run foo.m1clj` runs through the m1clj lang; `bb meme run foo.clj` runs through clj. The binary name belongs to the programme.

### The languages' implementations

| Name | What it is |
|---|---|
| `m1clj-lang.*` | The m1clj language: grammar, parselets, printer, formatter, form-shape |
| `m2clj-lang.*` | The m2clj language: same shape as m1clj-lang, sovereign tree |
| `clj-lang/src/clj_lang/api.cljc` | The clj language registration shim (parser comes from `meme.tools.clj.parser.*`) |

Each `<lang>-lang.*` tree is the language's home and its public name in the source. The lang-specific parselets, printer, and form-shape live there; the shared backbone they sit on lives in `meme.tools.clj.*`.

### Editor packages

These predate the language rename. They target `.m1clj` files; their names follow the toolkit, not the language they highlight.

- `tree-sitter-meme` — Tree-sitter grammar for `.m1clj`
- `vscode-meme` — VS Code extension
- `zed-meme` — Zed extension
