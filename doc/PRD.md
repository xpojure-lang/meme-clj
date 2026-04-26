# meme-clj — Product Requirements Document

> **Scope.** This PRD describes both the **meme-clj toolkit** (the
> programme-level platform for hosting alternative Clojure surfaces) and
> the **m1clj language** (the first guest built on it). Section headers
> indicate which scope a requirement belongs to.

## Problem

Clojure's S-expression syntax requires structural editing (paredit) to
manage parenthesis nesting reliably. Without it, writing and reading
deeply nested forms is error-prone: mismatched parentheses, wrong bracket
types, incorrect nesting depth. These are bookkeeping errors, not
semantic ones — imposed by syntax that demands manual bracket management.

The wider question — *can a Clojure host carry alternative surface
syntaxes without losing macros, semantics, or ecosystem?* — has not had a
concrete platform to test it on. **meme-clj** is that platform; **m1clj**
is the first answer.


## Solution

**meme-clj** is a syntax-experimentation toolkit: parser engine, AST
tier, composable pipeline stages, printer, formatter, namespace loader,
lang registry, and CLI. Guest languages register against the toolkit and
share its Clojure-surface backbone.

**m1clj** is the first language. One rule replaces S-expression nesting:

`f(x y)` — call. Head of a list written outside the parens, adjacent to `(` (spacing significant).

Everything else is unchanged from Clojure. Programs run on Babashka,
Clojure JVM, or ClojureScript without modification. The CLI is
self-hosted in `.m1clj`.

A second guest, `clj-lang`, registers the native S-expression Clojure
surface on the same toolkit — the parser, AST, stages, and printer are
language-agnostic; only the grammar and a few syntax-specific parselets
differ between guests.


## Goals

### Programme (meme-clj toolkit)

- **Reusable backbone.** Parser engine, lex layer, AST tier, stages,
  printer, render are language-agnostic. New guests need a grammar and a
  handful of parselets, not a fresh pipeline.

- **Lossless tier.** The AST captures position, trivia, and notation as
  record fields, so tooling (formatters, refactorers, transpilers) can
  round-trip user syntax without metadata gymnastics.

- **Multi-platform.** Core toolkit runs on Clojure JVM, ClojureScript,
  and Babashka. Single codebase, `.cljc` files.

- **Open registry.** Guest languages register at namespace load time;
  the toolkit imports no language directly.

### Language (m1clj)

- **Human-readable Clojure.** The syntax should be immediately legible to
  anyone who knows Clojure, Python, Ruby, or JavaScript.

- **Full Clojure compatibility.** Every valid Clojure program has an
  m1clj equivalent. Every m1clj program produces valid Clojure forms.
  Data literals, destructuring, reader macros, macros, metadata — all
  work unchanged.

- **Self-hosting.** m1clj code should be able to build m1clj itself. The
  CLI is the first component written in `.m1clj`.

- **Roundtrippable.** m1clj text → Clojure forms → m1clj text should
  produce equivalent output. Lossless via the AST tier; structural via
  the form path.


## Non-goals

- **Replacing Clojure syntax.** m1clj is an alternative surface syntax.
  Developers who prefer S-expressions should keep using them.

- **Error recovery.** The reader fails fast on invalid input. Partial
  parsing and error recovery are future work.


## Target users

1. **Developers writing Clojure without paredit.** Terminal, basic text
   editors, web forms, notebooks. m1clj syntax eliminates the bookkeeping
   that structural editors normally handle.

2. **Anyone reading Clojure code.** m1clj is easier to scan than
   S-expressions — code review, diffs, logs, documentation.

3. **Agents generating Clojure.** What is good for humans is good for
   agents. m1clj reduces syntax errors on the structural dimension.

4. **Researchers / authors of alternative Clojure surfaces.** The
   toolkit is the platform; m1clj is one experiment built on it.


## Requirements

### Reader

| ID | Requirement | Status |
|----|-------------|--------|
| R1 | Parse `f(x y)` as `(f x y)` — head outside parens, adjacent `(` required (spacing significant) | Done |
| R5 | Parse `def(x 42)` as `(def x 42)` | Done |
| R6 | Parse `let([x 1] body)` — bindings in call form | Done |
| R7 | Parse `for([x xs] body)` — bindings in call form | Done |
| R8 | Parse `defn` — single arity, multi-arity, docstring | Done |
| R9 | Parse `fn` — anonymous functions | Done |
| R10 | Parse `if(cond then else)` as call form | Done |
| R13 | Parse `ns(...)` with `:require` and `:import` | Done |
| R15 | Parse all Clojure data literals unchanged | Done |
| R16 | Parse Clojure reader macros (`@`, `^`, `#'`, `#_`, `'`) | Done |
| R17 | Parse `#?()` reader conditionals natively (no read-string) | Done |
| R18 | Parse `defprotocol(...)`, `defrecord(...)`, `deftype(...)`, `reify(...)`, `defmulti(...)`, `defmethod(...)` | Done |
| R19 | Parse Java interop: `.method()`, `Class/static()`, `.-field()` | Done |
| R20 | Commas are whitespace | Done |
| R21 | Line/column tracking for error messages | Done |
| R22 | Portable `.cljc` — core reader/printer run on JVM, ClojureScript, Babashka | Done |
| R23 | Signed numbers: `-1` is number, `-(1 2)` is call to `-` | Done |
| R24 | `#:ns{...}` namespaced maps parsed natively (no read-string) | Done |
| R25 | `#()` uses m1clj syntax inside, `%` params → `fn` form | Done |
| R26 | `stages/run` exposes intermediate stage state for tooling | Done |
| R28 | `()` is the empty list (no head required) | Done |
| R29 | No S-expression escape hatch — `'(...)` uses m1clj syntax inside | Done |
| R30 | Syntax-quote parsed natively — m1clj syntax inside `` ` `` | Done |
| R31 | Zero `read-string` delegation — all values resolved natively | Done |

### Printer

| ID | Requirement | Status |
|----|-------------|--------|
| P1 | Print `(f x y)` as `f(x y)` | Done |
| P5 | Print `(def x 42)` as `def(x 42)` | Done |
| P6 | Print `(let [x 1] ...)` as `let([x 1] ...)` | Done |
| P7 | Print `(for [x xs] ...)` as `for([x xs] ...)` | Done |
| P8 | Print `defn` with proper m1clj syntax | Done |
| P9 | Print `if` as call form | Done |
| P11 | Print all Clojure data literals | Done |
| P12 | Proper indentation | Done |
| P13 | Roundtrip: read then print produces re-parseable output | Done |

### Formatter

| ID | Requirement | Status |
|----|-------------|--------|
| F1 | Three-layer formatter architecture: notation (`m1clj-lang.printer`), form-shape (`m1clj-lang.form-shape`), style (`canon/style` or alternative). Each independently composable via plain-data operations. | Done |
| F2 | Public slot vocabulary: `:name`, `:doc`, `:params`, `:bindings`, `:dispatch-val`, `:dispatch-fn`, `:test`, `:expr`, `:as-name`, `:clause`, `:default`, `:arity`, `:body`. Documented in `doc/form-shape.md`. | Done |
| F3 | Form-shape registry per lang; exposed under `:form-shape` in `lang-map`. `decompose (registry head args)` takes registry explicitly so langs are sovereign. | Done |
| F4 | Opt-in structural fallback via `with-structural-fallback` — infers defn-like and let-like shapes for unregistered heads. | Done |
| F5 | Style's `:slot-renderers` composes over `printer/default-slot-renderers` via plain map merge. Overrides compose independently of other style keys. | Done |
| F6 | ~~Project-local `.m1clj-format.edn`~~ | Removed — feature was unused; CLI flags cover practical formatting needs |

### REPL

| ID | Requirement | Status |
|----|-------------|--------|
| RE1 | Read m1clj input, eval as Clojure, print result | Done |
| RE2 | Multi-line input: wait for balanced brackets and parens | Done |
| RE3 | Run via `bb meme` | Done |

### CLI

| ID | Requirement | Status |
|----|-------------|--------|
| C1 | `meme run <file>` — run a .m1clj file | Done |
| C2 | `meme repl` — start interactive REPL | Done |
| C3a | `meme to-clj <file\|dir>` — convert .m1clj files to .clj | Done |
| C3b | `meme to-m1clj <file\|dir>` — convert .clj/.cljc/.cljs files to .m1clj | Done |
| C4 | `meme format <file\|dir>` — normalize .m1clj files via canonical formatter (in-place or stdout) | Done |
| C5 | `meme transpile <dir\|file...> [--out dir]` — transpile .m1clj to .clj in a separate output directory for classpath use. Alias: `compile`. | Done |
| C8 | `meme build <dir\|file...> [--out dir]` — transpile + AOT compile to JVM bytecode. Stops at `.class` files; JAR packaging stays in user's tools.build layer. | Done |
| C6 | `load-file` interception — `(load-file "path.m1clj")` runs through the toolkit pipeline (JVM + Babashka) | Done |
| C7 | `require` interception — `(require 'my.ns)` finds `.m1clj` files on the classpath (JVM only; Babashka's SCI bypasses `clojure.core/load`) | Done |

Note: Requirement IDs are not sequential — gaps (R2–R4, R11–R12, R14,
P2–P4, P10) are requirements that were merged into other IDs or dropped
during design iteration (the largest removal was the implojure-lang
proof-of-concept, which carried its own reader/printer requirements
before it was retired). IDs are stable references and are not
renumbered, so git history and this table stay cross-referenceable.

## Architecture

```
.m1clj source ──► parser ──► CST ──► AST ──► forms ──► eval
                (step-parse)  (cst→ast)  (ast→form)
                                  │
                                  ├──► printer ──► .m1clj text   (m1clj formatter)
                                  └──► printer ──► .clj text     (clj-mode print)
```

The AST is the lossless tier — trivia, position, and notation live on
record fields, surviving any walker. The form path is structural and
intentionally lossy.

The codebase has four layers:
- **`meme.tools.{parser, lexer, render}`** — Generic, language-agnostic infrastructure: Pratt parser engine, scanlet builders, Wadler-Lindig Doc layout.
- **`meme.tools.clj.*`** — Clojure-surface commons shared across any Clojure-flavored frontend: lexical conventions, atom resolution, CST reader, AST nodes/build/lower, stages, syntax-quote expander, value serialization, run/repl harnesses, native-Clojure parser.
- **`m1clj-lang.*`** — m1clj language: grammar, parselets, lexlets shim, form-shape, printer, formatters; plus thin `run`/`repl` shims that inject m1clj's grammar and delegate to `meme.tools.clj.{run,repl}`. **`clj-lang.*`** is the sibling guest registering the native Clojure surface.
- **`meme.*`** — Shared runtime infrastructure (`meme.registry`, `meme.loader`) and app tier (`meme.cli`).

The pipeline has composable stages (composed by `meme.tools.clj.stages`), each a `ctx → ctx` function with a `step-` prefix:
1. **strip-shebang** — remove `#!` line from `:source` (for executable scripts).
   Defined in `meme.tools.clj.stages`, called by runtime before the core pipeline.
2. **step-parse** (`meme.tools.parser` driven by a lang-supplied grammar) — unified
   scanlet-parselet Pratt parser → lossless CST. Scanning (character dispatch, trivia)
   and parsing (structure) are both defined in the grammar spec. Reads directly from
   source string. Requires `:grammar` in `:opts` — no implicit default.
3. **step-read** (`meme.tools.clj.cst-reader`) — lowers CST to Clojure forms. Value
   resolution delegated to `meme.tools.clj.resolve`. No `read-string` delegation.
   Reader conditionals preserved as `CljReaderConditional` records.
4. **step-evaluate-reader-conditionals** (`meme.tools.clj.stages`) — materializes the
   platform branch of `#?`/`#?@` for eval paths. Tooling paths skip this step.
   Supports `:platform` opt and `:default` fallback.
5. **step-expand-syntax-quotes** (`meme.tools.clj.expander`) — syntax-quote AST nodes →
   plain Clojure forms. Only needed before eval, not for tooling.
   `meme.tools.clj.stages/run` intentionally omits stages 4–5, returning AST
   nodes for tooling access.

The printer pattern-matches on form structure to reverse the transformation.
It detects special forms and produces their m1clj syntax equivalents.

All `#` dispatch forms (`#?`, `#?@`, `#:ns{}`, `#{}`, `#""`, `#'`, `#_`,
`#()`, tagged literals) and syntax-quote (`` ` ``) are parsed natively with
m1clj rules inside. No opaque regions.


## Known limitations

- **ClojureScript: some value types unsupported.** Ratio literals
  (`1/2`), BigInt (`42N`), and BigDecimal (`42M`) are JVM/Babashka only.
  Char literals read but return JS single-char strings (not chars —
  platform limitation). Tagged literals work on CLJS: `#inst` resolves
  to `js/Date`, `#uuid` to `cljs.core/UUID`, and unknown tags fall back
  to a `TaggedLiteral` (cljs.core does not carry a `*data-readers*`
  analogue, so user-registered tags are not consulted on CLJS).
  Reader conditionals (`#?`/`#?@`) and namespaced maps (`#:ns{}`) parse
  successfully on ClojureScript. The core call rule and all standard
  forms work on ClojureScript.

- **ClojureScript: `-0.0` does not roundtrip.** On CLJS, `(js/parseFloat
  "-0.0")` returns `-0` and `(pr-str -0)` returns `"0"`, so negative zero
  is indistinguishable from zero after a read/print cycle. JVM preserves
  it correctly.

- **Reader conditionals — lossless by default.** The reader always returns
  `#?`/`#?@` as `CljReaderConditional` records, so `m1clj->forms`,
  `m1clj->clj`, and `format-m1clj` preserve all branches faithfully.
  `run-string`/`run-file`/REPL insert `step-evaluate-reader-conditionals`
  between read and syntax-quote expansion to materialize the platform
  branch — matching native Clojure's order (reader evaluates `#?` before
  `` ` `` is processed). The step supports a `:platform` opt so tooling
  can materialize for a non-current platform. `:default` is respected as
  a fallback. `#?@` inside map literals is not supported at read time
  (same limitation as Clojure's `:read-cond :preserve`).

- **Nesting depth limit.** The parser enforces a maximum nesting depth of
  512 levels. Exceeding this produces a clear error. This prevents stack
  overflow on recursive descent.

- **Plain-form path: interior comments before non-metadatable atoms are
  dropped.** This applies to the structural form path (`m1clj->forms`,
  `forms->m1clj`). Comments above metadatable values (symbols, lists,
  vectors, maps, sets) survive on metadata; comments immediately above
  atom values (keywords, numbers, strings, booleans, chars) are lost
  because Clojure atoms cannot carry metadata. **The AST tier
  (`m1clj->ast`, `format-m1clj`) preserves these comments on
  `:leading-trivia` record fields and is lossless.** Tooling that
  needs comment fidelity should consume the AST.


## Completed work (post-initial release)

- **Syntax highlighting grammars.** TextMate grammar in `vscode-meme/`,
  Tree-sitter grammar in `tree-sitter-meme/` (both in the `xpojure-lang` org).
  Both cover `.m1clj` extension.

- **Platform / guest language system.** Includes:
  - **Lang registration** (`meme.registry`) — `register!` a guest language
    with `:extension`, `:run`, `:parser`, `:format`, `:to-clj`, `:to-m1clj`.
    The CLI auto-detects guest languages from file extension; `run-file`
    does the same when a `:resolve-lang-for-path` resolver is injected.
  - **Pipeline integration** — pluggable `:parser` in
    `step-parse`, `:prelude` option in `run-string`.
  - **Sibling guest shipped:** `clj-lang` registers the native Clojure
    surface, sharing the toolkit's parser engine, AST tier, and printer.

- **AST tier.** A lossless intermediate representation between CST and
  Clojure forms. Twenty-five record types (`Clj*`) capture position,
  trivia, and notation as fields; round-trip via the tier preserves
  comments, reader sugar, set source order, and namespaced-map prefixes
  that the structural form path drops. Public API:
  `m1clj-lang.api/m1clj->ast`, `clj->ast`, plus `meme.tools.clj.ast.*`.

- **Native Clojure parser.** `meme.tools.clj.parser.*` parses native
  S-expression Clojure source through the same engine as m1clj. Powers
  `clj-lang` and the `clj→m1clj` direction with no `read-string`
  delegation.

- **Three-layer formatter architecture.** The printer, form-shape, and
  style concerns are now separate namespaces with independent extension
  points, all composable via plain-data operations:
  - `m1clj-lang.printer` — notation only. Dispatches on slots from the
    form-shape registry provided via ctx. No hardcoded form names.
  - `m1clj-lang.form-shape` — language-owned registry of decomposers.
    Each decomposer emits `[slot-name value]` pairs. 13-slot vocabulary
    (`:name`, `:doc`, `:params`, `:bindings`, `:clause`, `:body`, etc.)
    is public and documented in `doc/form-shape.md`. `with-structural-fallback`
    enables opt-in inference for user macros matching defn-like or let-like
    shapes.
  - `m1clj-lang.formatter.canon/style` — slot-keyed opinions
    (`:head-line-slots`, `:force-open-space-for`, `:slot-renderers`).
    The canon style collapsed from ~60 form-keyed entries to 11 slot-keyed
    entries. Formatters accept `:style` override in opts for project-level
    tweaks.

### Platform requirements

| ID | Requirement | Status |
|----|-------------|--------|
| PL3 | Lang registration: `lang/register!`, `lang/resolve-by-extension`, `lang/resolve-lang` | Done |
| PL4 | CLI auto-detects guest language from file extension; `run-file` does the same when a `:resolve-lang-for-path` resolver is injected (the CLI wires this to `meme.registry`) | Done |
| PL5 | `run-string` accepts `:prelude` | Done |
| PL6 | Pluggable parser: `:parser` option in `step-parse` for guest language parsers | Done |
| PL8 | Stage contract: spec validation at stage boundaries | Removed — contract validation was deleted during pipeline unification to the scanlet-parselet architecture |
| PL10 | `meme to-clj --lang` / `meme to-m1clj --lang` CLI selector and `meme inspect` command | Done |
| PL11 | Namespace loader: intercept `clojure.core/load` to find `.m1clj` files on classpath. `install!`/`uninstall!`, auto-installed by `run-string`/`run-file`/REPL (opt out via `:install-loader? false`) | Done |
| PL12 | Multi-extension support: `:extension`/`:extensions` normalization, both string and vector accepted | Done |
| PL13 | Loader namespace denylist: `clojure.*`, `java.*`, `javax.*` etc. cannot be shadowed | Withdrawn — installing a lang is the trust decision; if a user puts `.m1clj` files at core namespace paths they did it on purpose. Recursion protection is via cached `extensions-fn`, not a denylist. |
| PL14 | Registry atomicity: extension conflict check inside `swap!` callback, thread-safe | Done |
| PL15 | Red team hardening: 11 confirmed fixes (OOM, TOCTOU, compat, metadata), 4 plausible concern fixes | Done |
| PL16 | Registry imports no langs directly — built-ins self-register from their own api ns; CLI is the "app" that requires each lang.  Dissolves the registry ↔ m1clj-lang cycle and four `requiring-resolve` workarounds. | Done |
| PL17 | Lightweight pipeline contract validation: stages declare required ctx keys via `stage-contracts` data; `check-contract!` runs at stage entry and throws `:m1clj/pipeline-error` with missing keys listed, instead of deep NPEs. | Done |

## Future work

- Error recovery: partial parsing for editor integration. This would require
  the parser to accumulate errors into a vector rather than throwing, return
  partial ASTs with error nodes, and add try/catch wrappers in `parse-form`
  that advance to resynchronization points (closing delimiters or newlines).
  This is a significant architectural change and should be its own project.
- nREPL middleware
- **meme-lsp**: Language Server Protocol implementation for `.m1clj` files.
  Diagnostics, go-to-definition, symbol navigation, completions, hover,
  and formatting via the existing lossless AST pipeline. Could extend
  clojure-lsp or be a standalone server using the toolkit parser directly.
- **meme-mcp**: Model Context Protocol server exposing the toolkit
  pipeline (parse, read, format, to-clj, to-m1clj) as MCP tools for AI
  agents. Enables LLMs to read, write, and transform `.m1clj` code
  natively without converting through Clojure first.
- **Additional guests.** The toolkit's plural-language story is its main
  thesis; `clj-lang` is one demonstration. More guests (rewrite-rule
  surfaces, term-rewriting frontends, paredit-friendly bracket variants)
  are the natural follow-ons.
