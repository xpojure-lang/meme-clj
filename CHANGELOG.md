# Changelog

All notable changes to meme-clj will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/).

## [4.0.0] — 2026-04-19

A reorganization release. No breaking changes to `.meme` syntax or runtime behavior; most of the work is in documentation, API hygiene, and internal boundaries.

### Architecture

- **Registry inversion** — `meme.registry` imports no concrete langs. Each lang's api namespace calls `register-builtin!` at its own load time, and the CLI triggers registration by explicitly requiring each lang it ships with. Dissolves the old registry↔lang cycle and four `requiring-resolve` workarounds.
- **Shared infrastructure reclassification** — `meme.registry` and `meme.loader` are now documented as shared infrastructure peer to `meme.tools.*`, not as a strict "above" tier over `meme-lang.*`. `meme-lang.api` requiring `meme.registry` (for self-registration) and `meme-lang.run` requiring `meme.loader` (for auto-install) are intentional; the CLAUDE.md tier table has been updated to match.
- **Pipeline contract validation** — stages declare required ctx keys via `stage-contracts`; `check-contract!` runs at entry and throws `:meme-lang/pipeline-error` with the missing key(s) when pipelines are miscomposed, instead of deep NPEs.
- **Engine seal** — `meme.tools.parser` exposes only `trivia-pending?` to language grammars; other engine internals are no longer part of the grammar-author contract.
- **`char-code` consolidated into `meme.tools.lexer`** — duplicate helper in `meme-lang.lexlets` removed.

### Added

- **`meme.registry/register-string-handler!`** — lang-agnostic hook for resolving string values (e.g. `:run "prelude.meme"`) in lang-map slots. Meme installs its own `:run` handler at load time. Replaces the previous hardcoded `requiring-resolve` of `meme-lang.run/run-string` inside the registry.
- **`meme-lang.run/run-file` opts** — `:install-loader?` (default `true`; pass `false` to skip auto-install of `meme.loader`) and `:resolve-lang-for-path` (extension-based lang dispatch hook, injected by the CLI).
- **`meme-lang.repl/start` opt** — `:install-loader?` mirrors the above.
- **Direct unit tests for `meme.tools.parser` and `meme.tools.lexer`** using a minimal synthetic calculator grammar, covering precedence (left/right-assoc), EOF recovery, max-depth, trivia attachment, `:when` predicate gating, and all scanlet/parselet factories.
- **Scar-tissue tests** for regex roundtrip and empty splice expansion.

### Changed

- **`run-string`, `run-file`, and `repl/start` auto-install `meme.loader`.** `require`/`load-file` of `.meme` namespaces work in the common programmatic case, not just via the CLI. Hosts that own their own `clojure.core/load` interception opt out via `:install-loader? false`.
- **Babashka loader warning suppressed** when SCI bypasses `clojure.core/load` — no cosmetic noise on REPL start.

### Fixed

- **Off-by-one in CST reader's depth guard** (`src/meme_lang/cst_reader.cljc`). Reader allowed one more level of recursion than the parser's limit (`>` → `>=`). Behavior now matches the parser at exactly `max-parse-depth` levels.
- **`meme.tools.lexer` cross-platform bug** — `digit?`/`ident-start?`/`ident-char?` claimed portability but returned wrong results on CLJS because `(int ch)` on a single-char string returns `NaN|0 = 0` rather than the code point. Fixed with a `char-code` helper that uses `.charCodeAt` on CLJS. Meme's grammar was unaffected (its own `meme-lang.lexlets` had the same pattern); `calc-lang` relied on the generic helpers and would have silently failed on CLJS.

### Internal / API hygiene

- **`meme-lang.api/to-clj` and `to-meme`** marked `^:no-doc`. These are CLI-dispatch adapters (they always apply `:read-cond :preserve`); library callers should use `meme->clj` / `clj->meme` directly.
- **`meme.registry/clear-user-langs!`** and **`registered-extensions`** marked `^:no-doc` — internal plumbing used by tests and the loader respectively.
- **`meme-lang.parselets/reader-cond-extra`** made private (used only within the file).
- **Missing docstrings** added: `meme.tools.parser/make-engine`, `meme.tools.lexer/{digit?, ident-char?, newline-consumer}`, `meme.config/config-filename`.

## [3.3.3] — 2026-04-12

### Fixed

- **Red team findings** — reader-cond validation, registry TOCTOU around extension conflicts, CLI testability improvements.
- **Deterministic depth guard in `clj->forms`** — no longer relies on catching `StackOverflowError` at CI JVM sizes; walks the returned forms against `max-parse-depth` instead.
- **Fuzzer state consolidated under `fuzz/`** — corpus and crash artifacts moved out of the project root.

### Documentation

- Added namespace-loading and `compile` sections to the README.
- Updated installation version pin.

## [3.3.0] — 2026-04-09

### Added

- **`load-file` interception** — `(load-file "path/to/file.meme")` runs through the meme pipeline on both JVM and Babashka.
- **`meme compile` CLI command** — compiles `.meme` to `.clj` into a separate output directory (`--out target/classes` by default) so `.meme` namespaces work via standard `require` without runtime patching. Primary use: Babashka projects that need `require` (SCI bypasses `clojure.core/load`, so runtime interception isn't enough).
- **Project-local `.meme-format.edn` config** — `meme format` discovers config by walking up from CWD. Schema: `:width`, `:structural-fallback?`, `:form-shape` (symbol→built-in alias), `:style` (partial canon override). Strict EDN, unknown tags rejected, unknown keys warn.

### Changed — Formatter (three-layer architecture)

- **Style policy extracted from the printer into the formatters.** The printer is now notation-only; `meme-lang.formatter.canon/style` carries the slot-keyed opinions.
- **Form-shape layer** (`meme-lang.form-shape`) — semantic decomposition of special forms into named slots (`:name`, `:doc`, `:params`, `:bindings`, `:clause`, `:body`, `:dispatch-val`, etc.). Lang-owned: each lang carries its own registry. The printer dispatches on slots, not form names. Documented as a public contract in `doc/form-shape.md`.
- **Opt-in structural fallback** — `with-structural-fallback` wraps a registry so user macros shaped like `defn` (name + params vector) or `let` (leading bindings vector) inherit canonical layout.
- **Slot renderers as override-able defaults** — style's `:slot-renderers` composes over `printer/default-slot-renderers` via plain map merge; formatters accept `:style` override in opts for project-level tweaks. The canon style collapsed from ~60 form-keyed entries to 11 slot-keyed entries.
- **Canon layout refinements:**
  - Inline vector/map/set layout — first element right after the open delimiter.
  - Binding vectors keep their head form (`let`/`loop`/`for`/`doseq`/...) on the head line; bindings render as pairs per line with columnar alignment.
  - Definition forms (`defn`/`defn-`/`defmacro`) keep name + params on the head line; always space after `(` for definition forms.
  - `case`, `cond`, `condp` render their bodies as paired clauses.
  - Multi-line values in paired layouts nest at the key column.
  - Closing paren moves to its own line for multi-line calls.

### Fixed

- **Four loader bugs** found during the loader work.

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
- **Metadata namespace hygiene**: all internal metadata keys moved from `:meme/*` to `:meme-lang/*`, with descriptive names. `:meme/ws` → `:meme-lang/leading-trivia`, `:meme/sugar` → `:meme-lang/sugar`, `:meme/order` → `:meme-lang/insertion-order`, `:meme/ns` → `:meme-lang/namespace-prefix`, `:meme/meta-chain` → `:meme-lang/meta-chain`, `:meme/bare-percent` → `:meme-lang/bare-percent`, `:meme/splice` → `:meme-lang/splice`. This separates meme-lang metadata from the generic `meme.tools` namespace, preventing collision with both user metadata and future languages built on `meme.tools.*`.
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
- **All `:meme/*` internal metadata keys**: replaced by `:meme-lang/*` namespaced equivalents with descriptive names. The `:meme/` namespace is now reserved for generic tooling.

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
