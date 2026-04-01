# Changelog

All notable changes to meme-clj will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/).

## [0.13.0] — 2026-04-01

### Added
- **Map/set pattern matching**: rewrite engine `match-pattern` now matches map patterns by key (`{:k ?x}` matches `{:k 42}`) and set patterns by element presence
- **Map/set traversal**: `rewrite-once` descends into maps (excluding records) and sets, so rules match subexpressions inside map values/keys and set elements
- **TRS chained calls**: `f(x)(y)` → `((f x) y)` — fixed left-to-right scan in `rewrite-level` with re-check after match
- **Comment preservation**: formatter never silently drops comments — forms with comments always break to multi-line
- **CLJS generative parity**: 5 property-based tests (matrix roundtrip, mixed forms, error locations, unclosed-is-incomplete, formatter idempotency) now run on ClojureScript
- **Formatter idempotency tests**: deterministic + property-based (300 trials) asserting `format(format(x)) == format(x)`
- **Comment preservation fixture**: comprehensive `.meme` fixture covering Clojure/meme code in comments, multiple semicolons, commented-out code, mid-expression and trailing comments
- **Three-lang benchmark**: `benchmark_test` now exercises classic, rewrite, and ts-trs across 11 fixtures and 7,526 vendor forms

### Fixed
- **Tokenizer EOF-after-backslash**: `"hello\` now reports "Incomplete escape sequence" instead of misleading "Unterminated string"
- **Rewrite emitter regex escaping**: `"` inside regex now escaped in output, matching `printer.cljc` behavior
- **Prelude spec**: `:meme.opts/prelude` corrected from `string?` to `(s/coll-of any?)` matching runtime type (vector of forms)
- **Generative set duplicates**: `gen-meme-text` set generator now deduplicates elements, fixing intermittent `prop-meme-text-roundtrip` failure

## [0.12.0] — 2026-04-01

### Added
- **Unified convert CLI**: `meme convert --lang meme-classic|meme-rewrite` selects the conversion lang; `meme inspect --lang` shows lang info
- **Unified convert module** (`meme.convert`): single dispatch point for all three langs
- **Comparative benchmark** (`benchmark_test.clj`): benchmarks all three langs across 11 meme fixtures and 7,526 vendor forms from 7 real-world Clojure libraries
- **Language platform**: `register!` API for guest languages with custom preludes, rewrite rules, and parsers (`meme.lang`)
- **Term rewriter**: bottom-up rewrite engine with `?x`/`??x` pattern variables, cycle detection, and fixed-point iteration (`meme.rewrite`)
- **Rewrite-based parser**: alternative token→form path via tagged trees and rewrite rules (`meme.rewrite.tree`)
- **Stage contracts**: opt-in spec validation at stage boundaries (`meme.stages.contract`)
- **LANGBOOK.md**: language maker cookbook — patterns for building guest languages on the meme platform
- **Superficie example**: surface-syntax renderer reimplemented as a guest language (212 lines vs ~2000 original)

### Fixed
- **nil/true/false as call heads**: the M-expression rule is purely syntactic — `nil(1 2)` → `(nil 1 2)`. Any value can be a head. Previously these were rejected artificially.
- **Tokenizer ns// symbols**: `clojure.core//` now reads as one symbol (namespace `clojure.core`, name `/`). Previously split into two tokens.
- **Rewrite emitter type gaps**: BigDecimal `M` suffix, BigInt `N` suffix, `##NaN`/`##Inf`/`##-Inf`, tagged literals, named chars (`\newline` etc.), `pr-str` fallback for UUID/Date
- **Reader conditional preservation**: all three langs now pass `:read-cond :preserve` during conversion, preserving `#?(:clj ...)` branches in output
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
- **Expander extracted**: Syntax-quote expansion (`expand-sq`, `expand-syntax-quotes`, `expand-forms`) moved from `meme.parse.reader` to new `meme.parse.expander` namespace.
- **Shared utilities**: Metadata exclusion key set (`strip-internal-meta`) and `percent-param-type` extracted to `meme.forms` to prevent drift between reader and printer.
- **CI deploy gate**: ClojureScript tests now required before deployment (added `test-cljs` to deploy job dependencies).

## [0.5.0-alpha] — 2025-03-30

Initial public alpha release.

### Added
- Three-stage reader: scan (tokenizer) -> group -> parse (reader)
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
