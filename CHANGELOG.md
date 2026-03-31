# Changelog

All notable changes to meme-clj will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/).

## [0.12.0] — 2026-04-01

### Added
- **Collapsar engine** (`meme.alpha.collapsar`): self-contained declarative rewrite engine (584 LOC) with phases, pipelines, head analysis, verified termination, and tower collapse
- **Collapsar meme↔clj pipeline** (`meme.alpha.collapsar.meme`): meme-to-Clojure and Clojure-to-meme conversion expressed as collapsar rule phases and procedural phases
- **Unified convert CLI**: `meme convert --pipeline classic|rewrite|collapsar` selects the conversion pipeline; `meme inspect --pipeline` shows pipeline structure
- **Unified convert module** (`meme.alpha.convert`): single dispatch point for all three pipelines
- **Comparative benchmark** (`benchmark_test.clj`): benchmarks all three pipelines across 11 meme fixtures and 7,526 vendor forms from 7 real-world Clojure libraries
- **Language platform**: `register!` API for guest languages with custom preludes, rewrite rules, and parsers (`meme.alpha.platform.registry`)
- **Term rewriter**: bottom-up rewrite engine with `?x`/`??x` pattern variables, cycle detection, and fixed-point iteration (`meme.alpha.rewrite`)
- **Rewrite-based parser**: alternative token→form path via tagged trees and rewrite rules (`meme.alpha.rewrite.tree`)
- **Pipeline contracts**: opt-in spec validation at stage boundaries (`meme.alpha.pipeline.contract`)
- **LANGBOOK.md**: language maker cookbook — patterns for building guest languages on the meme platform
- **Superficie example**: surface-syntax renderer reimplemented as a guest language (212 lines vs ~2000 original)

### Fixed
- **nil/true/false as call heads**: the M-expression rule is purely syntactic — `nil(1 2)` → `(nil 1 2)`. Any value can be a head. Previously these were rejected artificially.
- **Tokenizer ns// symbols**: `clojure.core//` now reads as one symbol (namespace `clojure.core`, name `/`). Previously split into two tokens.
- **Rewrite emitter type gaps**: BigDecimal `M` suffix, BigInt `N` suffix, `##NaN`/`##Inf`/`##-Inf`, tagged literals, named chars (`\newline` etc.), `pr-str` fallback for UUID/Date
- **Reader conditional preservation**: all three pipelines now pass `:read-cond :preserve` during conversion, preserving `#?(:clj ...)` branches in output
- **Prelude expansion**: prelude forms are now expanded through `step-expand-syntax-quotes` before eval, matching the user-code path
- **build-tree delimiter validation**: `build-tree` now validates expected delimiters after `#?` and `#:ns` prefixes
- **load-prelude docstring**: corrected to reflect actual behavior (parse-only, not eval)
- **README.md**: fixed dead link to `doc/development.md`

## [0.6.0-alpha] — 2026-03-30

### Fixed
- **defrecord-as-map bugs**: `MemeRaw`, `MemeSyntaxQuote`, and other AST node defrecords satisfy `(map? x)`, causing silent mishandling in `expand-sq`, `normalize-bare-percent`, `find-percent-params`, `pp`, and `max-percent-n`. All dispatch sites now guard with `forms/raw?`, `forms/syntax-quote?`, etc. before the `(map? form)` branch.
- **#?@ splicing**: `#?@(:clj [2 3])` inside a collection now correctly splices elements (`[1 #?@(:clj [2 3]) 4]` produces `[1 2 3 4]`, not `[1 [2 3] 4]`). Non-sequential splice values produce a clear error.
- **Positive-sign BigInt/Ratio**: `+42N` and `+3/4` now parse correctly. `BigInteger` constructor rejects leading `+`; sign is now stripped before construction (matching hex/octal/radix branches).
- **Literal head syntax**: `nil(x)`, `true(x)`, `false(x)` are now valid meme syntax, producing the lists `(nil x)`, `(true x)`, `(false x)`. The syntactic rule `f(args)` → `(f args)` applies uniformly regardless of head type.
- **Nested syntax-quote semantics**: `` ``x `` now correctly produces double-quoting (code that generates the inner expansion), matching Clojure's behavior. Previously the inner expansion was returned directly, losing one nesting level.

### Changed
- **Expander extracted**: Syntax-quote expansion (`expand-sq`, `expand-syntax-quotes`, `expand-forms`) moved from `meme.alpha.parse.reader` to new `meme.alpha.parse.expander` namespace.
- **Shared utilities**: Metadata exclusion key set (`strip-internal-meta`) and `percent-param-type` extracted to `meme.alpha.forms` to prevent drift between reader and printer.
- **CI deploy gate**: ClojureScript tests now required before deployment (added `test-cljs` to deploy job dependencies).

## [0.5.0-alpha] — 2025-03-30

Initial public alpha release.

### Added
- Three-stage pipeline: scan (tokenizer) -> group -> parse (reader)
- Printer and pretty-printer (width-aware, comment-preserving)
- Full Clojure syntax support: all special forms, reader macros, dispatch forms
- Syntactic transparency via `:meme/sugar` metadata preservation
- Syntax-quote preserved as AST node (`MemeSyntaxQuote`), expanded before eval
- Raw value wrapper (`MemeRaw`) for numbers/chars/strings with alternate notation
- `:read-cond :preserve` option for lossless reader conditional roundtrips
- REPL with multi-line input and `:incomplete` continuation protocol
- CLI (run, repl, convert, format) — self-hosted in `.meme`
- Cross-platform support: JVM, Babashka, ClojureScript
- Generative property-based tests (14 properties, 200-300 samples each)
- Vendor roundtrip tests against 7 real-world Clojure libraries
- Dogfood tests with clj-kondo semantic equivalence verification
- Clojars deployment via CI on version tags
