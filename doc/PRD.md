# meme clojure — Product Requirements Document

## Problem

Clojure's S-expression syntax requires structural editing (paredit) to
manage parenthesis nesting reliably. Without it, writing and reading
deeply nested forms is error-prone: mismatched parentheses, wrong bracket
types, incorrect nesting depth. These are bookkeeping errors, not
semantic ones — imposed by syntax that demands manual bracket management.


## Solution

meme is a complete Clojure frontend. One rule replaces S-expression
nesting with readable, familiar syntax:

`f(x y)` — call. Head of a list written outside the parens, adjacent to `(` (spacing significant).

Everything else is unchanged from Clojure. Programs run on Babashka,
Clojure JVM, or ClojureScript without modification. The platform
includes a reader, printer, formatter, REPL, file runner, and CLI —
the CLI itself is written in `.meme`.


## Goals

- **Human-readable Clojure.** The syntax should be immediately legible to
  anyone who knows Clojure, Python, Ruby, or JavaScript.

- **Full Clojure compatibility.** Every valid Clojure program has a meme
  equivalent. Every meme program produces valid Clojure forms. Data literals,
  destructuring, reader macros, macros, metadata — all work unchanged.

- **Self-hosting.** meme code should be able to build meme itself. The CLI
  is the first component written in `.meme`.

- **Roundtrippable.** meme text → Clojure forms → meme text should produce
  equivalent output. This enables tooling: formatters, linters, editors.

- **Portable.** Core pipeline runs on Clojure JVM, ClojureScript, and
  Babashka. Single codebase, `.cljc` files.

- **Platform for guest languages.** The parser engine, pipeline, and FullForm
  representation are designed as a foundation for languages beyond Clojure.


## Non-goals

- **Replacing Clojure syntax.** meme is an alternative surface syntax.
  Developers who prefer S-expressions should keep using them.

- **Error recovery.** The reader fails fast on invalid input. Partial
  parsing and error recovery are future work.


## Target users

1. **Developers writing Clojure without paredit.** Terminal, basic text
   editors, web forms, notebooks. meme syntax eliminates the bookkeeping
   that structural editors normally handle.

2. **Anyone reading Clojure code.** meme is easier to scan than
   S-expressions — code review, diffs, logs, documentation.

3. **Agents generating Clojure.** What is good for humans is good for
   agents. meme reduces syntax errors on the structural dimension.


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
| R25 | `#()` uses meme syntax inside, `%` params → `fn` form | Done |
| R26 | `stages/run` exposes intermediate stage state for tooling | Done |
| R28 | `()` is the empty list (no head required) | Done |
| R29 | No S-expression escape hatch — `'(...)` uses meme syntax inside | Done |
| R30 | Syntax-quote parsed natively — meme syntax inside `` ` `` | Done |
| R31 | Zero `read-string` delegation — all values resolved natively | Done |

### Printer

| ID | Requirement | Status |
|----|-------------|--------|
| P1 | Print `(f x y)` as `f(x y)` | Done |
| P5 | Print `(def x 42)` as `def(x 42)` | Done |
| P6 | Print `(let [x 1] ...)` as `let([x 1] ...)` | Done |
| P7 | Print `(for [x xs] ...)` as `for([x xs] ...)` | Done |
| P8 | Print `defn` with proper meme syntax | Done |
| P9 | Print `if` as call form | Done |
| P11 | Print all Clojure data literals | Done |
| P12 | Proper indentation | Done |
| P13 | Roundtrip: read then print produces re-parseable output | Done |

### Formatter

| ID | Requirement | Status |
|----|-------------|--------|
| F1 | Three-layer formatter architecture: notation (`meme-lang.printer`), form-shape (`meme-lang.form-shape`), style (`canon/style` or alternative). Each independently composable via plain-data operations. | Done |
| F2 | Public slot vocabulary: `:name`, `:doc`, `:params`, `:bindings`, `:dispatch-val`, `:dispatch-fn`, `:test`, `:expr`, `:as-name`, `:clause`, `:default`, `:arity`, `:body`. Documented in `doc/form-shape.md`. | Done |
| F3 | Form-shape registry per lang; exposed under `:form-shape` in `lang-map`. `decompose (registry head args)` takes registry explicitly so langs are sovereign. | Done |
| F4 | Opt-in structural fallback via `with-structural-fallback` — infers defn-like and let-like shapes for unregistered heads. | Done |
| F5 | Style's `:slot-renderers` composes over `printer/default-slot-renderers` via plain map merge. Overrides compose independently of other style keys. | Done |
| F6 | Project-local `.meme-format.edn` — `meme format` discovers config by walking up from CWD. Schema: `:width`, `:structural-fallback?`, `:form-shape` (symbol-to-symbol aliases), `:style` (partial canon override). Strict EDN parsing; unknown reader tags rejected; unknown keys warn but don't fail. | Done |

### REPL

| ID | Requirement | Status |
|----|-------------|--------|
| RE1 | Read meme input, eval as Clojure, print result | Done |
| RE2 | Multi-line input: wait for balanced brackets and parens | Done |
| RE3 | Run via `bb meme` | Done |

### CLI

| ID | Requirement | Status |
|----|-------------|--------|
| C1 | `meme run <file>` — run a .meme file | Done |
| C2 | `meme repl` — start interactive REPL | Done |
| C3a | `meme to-clj <file\|dir>` — convert .meme files to .clj | Done |
| C3b | `meme to-meme <file\|dir>` — convert .clj/.cljc/.cljs files to .meme | Done |
| C4 | `meme format <file\|dir>` — normalize .meme files via canonical formatter (in-place or stdout) | Done |
| C5 | `meme transpile <dir\|file...> [--out dir]` — transpile .meme to .clj in a separate output directory for classpath use. Alias: `compile`. | Done |
| C6 | `load-file` interception — `(load-file "path.meme")` runs through the meme pipeline (JVM + Babashka) | Done |
| C7 | `require` interception — `(require 'my.ns)` finds `.meme` files on the classpath (JVM only; Babashka's SCI bypasses `clojure.core/load`) | Done |

Note: Requirement IDs are not sequential — gaps (R2–R4, R11–R12, R14,
P2–P4, P10) are requirements that were merged into other IDs or dropped
during design iteration (the largest removal was the wlj-lang
proof-of-concept, which carried its own reader/printer requirements
before it was retired). IDs are stable references and are not
renumbered, so git history and this table stay cross-referenceable.

## Architecture

```
.meme file ──→ unified-pratt-parser ──→ cst-reader ──→ Clojure forms
                  (step-parse)          (step-read)         │
                                                            ▼
                                                      expander ──→ eval
                                                            │
                                                       printer ──→ .meme text
                                                     formatter ──→ .meme text
```

The codebase has three layers:
- **`meme.tools.*`** — Generic infrastructure: parser engine, scanlet builders, render engine
- **`meme-lang.*`** — Meme language: grammar, scanlets, parselets, stages, printer, formatter
- **`meme.*`** — CLI and lang registry

The pipeline has composable stages (composed by `meme-lang.stages`), each a `ctx → ctx` function with a `step-` prefix:
1. **strip-shebang** — remove `#!` line from `:source` (for executable scripts).
   Defined in `meme-lang.stages`, called by runtime before the core pipeline.
2. **step-parse** (`meme.tools.parser` with `meme-lang.grammar`) — unified scanlet-parselet
   Pratt parser → lossless CST. Scanning (character dispatch, trivia) and parsing (structure)
   are both defined in the grammar spec. Reads directly from source string.
3. **step-read** (`meme-lang.cst-reader`) — lowers CST to Clojure forms. Value
   resolution delegated to `meme-lang.resolve`. No `read-string` delegation.
4. **step-expand-syntax-quotes** (`meme-lang.expander`) — syntax-quote AST nodes →
   plain Clojure forms. Only needed before eval, not for tooling.
   `stages/run` intentionally omits this stage, returning AST
   nodes for tooling access.

The printer pattern-matches on form structure to reverse the transformation.
It detects special forms and produces their meme syntax equivalents.

All `#` dispatch forms (`#?`, `#?@`, `#:ns{}`, `#{}`, `#""`, `#'`, `#_`,
`#()`, tagged literals) and syntax-quote (`` ` ``) are parsed natively with
meme rules inside. No opaque regions.


## Known limitations

- **ClojureScript: some value types unsupported.** Tagged literals
  (`#uuid`, `#inst`), ratio literals (`1/2`), BigInt (`42N`), and
  BigDecimal (`42M`) are JVM/Babashka only. Char literals read but
  return JS single-char strings (not chars — platform limitation).
  Reader conditionals (`#?`/`#?@`) and namespaced maps (`#:ns{}`) parse
  successfully on ClojureScript. The core call rule and all standard
  forms work on ClojureScript.

- **ClojureScript: `-0.0` does not roundtrip.** On CLJS, `(js/parseFloat
  "-0.0")` returns `-0` and `(pr-str -0)` returns `"0"`, so negative zero
  is indistinguishable from zero after a read/print cycle. JVM preserves
  it correctly.

- **Reader conditionals — lossless by default.** The reader always returns
  `#?`/`#?@` as `MemeReaderConditional` records, so `meme->forms`,
  `meme->clj`, and `format-meme` preserve all branches faithfully.
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


## Completed work (post-initial release)

- **Syntax highlighting grammars.** TextMate grammar in `vscode-meme/`,
  Tree-sitter grammar in `tree-sitter-meme/` (both in the `xpojure-lang` org).
  Both cover `.meme` extension.

- **Platform / guest language system.** Includes:
  - **Lang registration** (`meme.registry`) — `register!` a guest language
    with `:extension`, `:run`, `:parser`, `:format`, `:to-clj`, `:to-meme`.
    The CLI auto-detects guest languages from file extension; `run-file`
    does the same when a `:resolve-lang-for-path` resolver is injected.
  - **Pipeline integration** — pluggable `:parser` in
    `step-parse`, `:prelude` option in `run-string`.
  - **Example languages** in `examples/languages/`: calc, prefix, superficie.

- **Three-layer formatter architecture.** The printer, form-shape, and
  style concerns are now separate namespaces with independent extension
  points, all composable via plain-data operations:
  - `meme-lang.printer` — notation only. Dispatches on slots from the
    form-shape registry provided via ctx. No hardcoded form names.
  - `meme-lang.form-shape` — language-owned registry of decomposers.
    Each decomposer emits `[slot-name value]` pairs. 13-slot vocabulary
    (`:name`, `:doc`, `:params`, `:bindings`, `:clause`, `:body`, etc.)
    is public and documented in `doc/form-shape.md`. `with-structural-fallback`
    enables opt-in inference for user macros matching defn-like or let-like
    shapes.
  - `meme-lang.formatter.canon/style` — slot-keyed opinions
    (`:head-line-slots`, `:force-open-space-for`, `:slot-renderers`).
    The canon style collapsed from ~60 form-keyed entries to 11 slot-keyed
    entries. Formatters accept `:style` override in opts for project-level
    tweaks.
  - `meme.config` — reads `.meme-format.edn` from project root (walking up
    from CWD) and translates it into formatter opts. Schema: `:width`,
    `:structural-fallback?`, `:form-shape` (symbol-to-symbol aliases),
    `:style` (partial override).

### Platform requirements

| ID | Requirement | Status |
|----|-------------|--------|
| PL3 | Lang registration: `lang/register!`, `lang/resolve-by-extension`, `lang/resolve-lang` | Done |
| PL4 | CLI auto-detects guest language from file extension; `run-file` does the same when a `:resolve-lang-for-path` resolver is injected (the CLI wires this to `meme.registry`) | Done |
| PL5 | `run-string` accepts `:prelude` | Done |
| PL6 | Pluggable parser: `:parser` option in `step-parse` for guest language parsers | Done |
| PL8 | Stage contract: spec validation at stage boundaries | Removed — contract validation was deleted during pipeline unification to the scanlet-parselet architecture |
| PL10 | `meme to-clj --lang` / `meme to-meme --lang` CLI selector and `meme inspect` command | Done |
| PL11 | Namespace loader: intercept `clojure.core/load` to find `.meme` files on classpath. `install!`/`uninstall!`, auto-installed by `run-string`/`run-file`/REPL (opt out via `:install-loader? false`) | Done |
| PL12 | Multi-extension support: `:extension`/`:extensions` normalization, both string and vector accepted | Done |
| PL13 | Loader namespace denylist: `clojure.*`, `java.*`, `javax.*` etc. cannot be shadowed | Done |
| PL14 | Registry atomicity: extension conflict check inside `swap!` callback, thread-safe | Done |
| PL15 | Red team hardening: 11 confirmed fixes (OOM, TOCTOU, compat, metadata), 4 plausible concern fixes | Done |
| PL16 | Registry imports no langs directly — built-ins self-register from their own api ns; CLI is the "app" that requires each lang.  Dissolves the registry ↔ meme-lang cycle and four `requiring-resolve` workarounds. | Done |
| PL17 | Lightweight pipeline contract validation: stages declare required ctx keys via `stage-contracts` data; `check-contract!` runs at stage entry and throws `:meme-lang/pipeline-error` with missing keys listed, instead of deep NPEs. | Done |

## Future work

- Error recovery: partial parsing for editor integration. This would require
  the parser to accumulate errors into a vector rather than throwing, return
  partial ASTs with error nodes, and add try/catch wrappers in `parse-form`
  that advance to resynchronization points (closing delimiters or newlines).
  This is a significant architectural change and should be its own project.
- nREPL middleware
- **meme-lsp**: Language Server Protocol implementation for `.meme` files.
  Diagnostics, go-to-definition, symbol navigation, completions, hover,
  and formatting via the existing lossless CST pipeline. Could extend
  clojure-lsp or be a standalone server using the meme parser directly.
- **meme-mcp**: Model Context Protocol server exposing meme's pipeline
  (parse, read, format, to-clj, to-meme) as MCP tools for AI agents.
  Enables LLMs to read, write, and transform `.meme` code natively
  without converting through Clojure first.
