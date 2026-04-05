# Changelog

All notable changes to meme-clj will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/).

## [3.0.0] — 2026-04-05

### Added
- **Namespace loader** (`meme.loader`): intercepts `clojure.core/load` to find `.meme` files on the classpath. Auto-installed by `run-file` and REPL start. `install!`/`uninstall!` for manual control. `require` in `.meme` code finds both `.meme` and `.clj` namespaces; `.meme` takes precedence when both exist.
- **Multi-extension support**: `register!` accepts both `:extension` (string) and `:extensions` (vector); both are normalized to `:extensions [...]`. Built-in meme lang registers `.meme`, `.memec`, `.memej`, `.memejs`.
- **Namespace denylist in loader**: `clojure.*`, `java.*`, `javax.*`, `cljs.*`, `nrepl.*`, `cider.*` namespaces cannot be shadowed by `.meme` files on the classpath.
- **Loader uninstall guard**: `uninstall!` throws when called from within a lang-load (prevents `.meme` code from disabling the loader mid-execution).
- **`#::{}` bare auto-resolve**: namespaced maps with empty alias (`#::{:a 1}`) are now accepted, matching Clojure's behavior. Keys stay unqualified; qualification deferred to eval time.
- **Red team report**: `doc/red-team/report.md` — 71 adversarial hypotheses tested across parser, value resolution, expansion, printer, loader, registry, and CLI.
- **Design documentation**: "The call/data tension" section in `doc/design-decisions.md` — analysis of lists, homoiconicity, and M-expressions.

### Changed
- **Architecture**: three-layer reorganization — `meme.tools.*` (generic parser/render), `meme-lang.*` (meme language), `meme.*` (CLI/registry/loader). The Pratt parser is fully data-driven via grammar spec.
- **Internal metadata key `:ws` renamed to `:meme/ws`**: prevents collision with user-supplied `:ws` metadata that was silently consumed as comments by the printer.
- **`register!` conflict check is atomic**: extension validation moved inside `swap!` callback, preventing TOCTOU race on concurrent registrations.
- **CLI reads file once**: `process-files` reads source via `slurp` once and passes content to transform, eliminating TOCTOU between read and write.
- **`meme-file?` and `swap-ext`** consult the registry for all registered extensions, not just hard-coded `.meme`.
- **`strip-shebang`** correctly handles CRLF (`\r\n`) line endings.
- **`clj->forms`** catches `StackOverflowError` on deeply nested Clojure input and rethrows as `ex-info`.

### Fixed
- **`%N` param OOM**: anonymous function params capped at `%20` (was ~10^11 → instant heap exhaustion). Matches Clojure's LispReader limit.
- **`:/foo` keyword accepted**: leading-slash keywords now rejected, matching Clojure's reader.
- **`:shebang` atom in CST reader**: double-shebang files no longer produce "Unknown atom type" error.
- **Empty hex literal `0x`**: produces "Empty hex literal" instead of leaking Java's "Zero length BigInteger" message.
- **`:meme/order` stale metadata**: printer validates order vector count against set size; falls back to unordered when stale.
- **U+2007 FIGURE SPACE**: classified as whitespace on all platforms, preventing invisible characters in symbol names.
- **UTF-16 surrogate pairs**: consumed as single `:invalid` token instead of two confusing errors per surrogate half.
- **Intermediate `#_` discard tokens**: capture start position before advance, producing correct non-zero-length spans.
- **Scar tissue triage**: ~15 comment-only regression blocks in `reader_test.cljc` converted to active tests or documented design decisions. Two stale comments corrected (duplicate keys and `%0` ARE rejected by the current pipeline).

### Removed
- **Legacy internal `:ws` metadata key**: replaced by namespaced `:meme/ws` throughout pipeline (CST reader, printer, formatter, forms).

## [1.0.0] — 2026-04-01

### Changed
- **Graduated to 1.0.0**: all namespaces are `meme.*` (no more `meme.alpha.*`)
- **JAR packaging**: resources directory now included in JAR (lang EDN files were missing)
- **CLI `get-lang`**: fixed variable shadowing that broke "Available langs" display
- **CLJS unicode escapes**: `\uNNNN` with invalid hex digits now throws instead of silently producing wrong values
- **Error messages**: `load-resource-edn` gives clear message when resource not found on classpath; `clj->forms` preserves source context in error ex-data; removed inconsistent trailing period in CLJS tagged-literal error
- **Reflection warnings**: eliminated in `meme.emit.printer` (tagged literal field access)
- **CLI load failure**: shows friendly error without extra stack trace
- **Docstrings**: added missing docstrings to public vars in `meme.lang`, `meme.forms`, `meme.emit.render`; fixed `run-stages` "full pipeline" misrepresentation; fixed indentation on `clj->` function docstrings
- **Documentation**: fixed stale `pipeline` namespace references in api.md, PRD.md, design-decisions.md; fixed wrong REPL launch command in api.md; removed phantom `lang.util` from platform tiers; corrected convert lang count in PRD.md
- **CLI rewrite**: replaced `.clj` shim + `.meme` bootstrap with plain Clojure; generic dispatcher delegates to lang map functions; CLI opts (e.g. `--width`) pass through to langs
- **CI**: pinned Babashka and Clojure CLI versions for reproducibility

### Removed
- **`meme.convert` module**: removed in favor of `:to-clj` / `:to-meme` commands on lang maps. Use `((:to-clj (meme.lang/resolve-lang :meme-rewrite)) src)` for multi-lang conversion, or `meme.core/meme->clj` for the classic path.

### Added
- **`meme.core/version`**: runtime version access — `@meme.core/version` returns `"1.0.0"`

### Deprecated
- **Legacy lang aliases**: `:classic`, `:rewrite`, `:ts-trs` in `meme.lang/resolve-lang` now emit a deprecation warning. Use `:meme-classic`, `:meme-rewrite`, `:meme-trs` instead.

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
