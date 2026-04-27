# Changelog Archive (pre-5.0)

Pre-5.0 release notes for `meme-clj`. Kept for historical reference.

> **Naming note.** Entries below predate the language rename (and the
> programme/language split). Many of them describe a "meme language"
> with `.meme` extensions, `meme-lang.*` namespaces, `:meme` registry
> key, and a `meme->forms` API. Those names no longer exist:
> - The language is now **m1clj** (extension `.m1clj`, registry `:m1clj`).
> - Implementation namespaces are **`m1clj-lang.*`**.
> - The toolkit (`meme.tools.*`, `meme.registry`, `meme.loader`,
>   `meme.cli`, the `meme` binary) keeps the historic prefix because
>   the toolkit is what `meme-clj` actually names.
>
> See `doc/glossary.md` for the current vocabulary, and the `[5.0.0]`
> entry in `CHANGELOG.md` for the post-rename baseline.

---

## [4.0.0] — 2026-04-19

A reorganization release. No breaking changes to `.meme` syntax or runtime behavior; most of the work is in documentation, API hygiene, and internal boundaries.

### Architecture

- **Registry inversion** — `meme.registry` imports no concrete langs. Each lang's api namespace calls `register-builtin!` at its own load time, and the CLI triggers registration by explicitly requiring each lang it ships with. Dissolves the old registry↔lang cycle and four `requiring-resolve` workarounds.
- **Shared infrastructure reclassification** — `meme.registry` and `meme.loader` are now documented as shared infrastructure peer to `meme.tools.*`, not as a strict "above" tier over `meme-lang.*`.
- **Pipeline contract validation** — stages declare required ctx keys via `stage-contracts`; `check-contract!` runs at entry and throws `:meme/pipeline-error` when pipelines are miscomposed.
- **Engine seal** — `meme.tools.parser` exposes only `trivia-pending?` to language grammars.
- **`char-code` consolidated into `meme.tools.lexer`**.

### Added

- **`meme.registry/register-string-handler!`** — lang-agnostic hook for resolving string values in lang-map slots.
- **`meme-lang.run/run-file` opts** — `:install-loader?`, `:resolve-lang-for-path`.
- **`meme-lang.repl/start` opt** — `:install-loader?`.
- **Direct unit tests for `meme.tools.parser` and `meme.tools.lexer`** using a synthetic calculator grammar.
- **Scar-tissue tests** for regex roundtrip and empty splice expansion.

### Changed

- **`run-string`, `run-file`, and `repl/start` auto-install `meme.loader`.**
- **Babashka loader warning suppressed** when SCI bypasses `clojure.core/load`.

### Fixed

- **Off-by-one in CST reader's depth guard** (`>` → `>=`).
- **`meme.tools.lexer` cross-platform bug** — `digit?`/`ident-start?`/`ident-char?` returned wrong results on CLJS because `(int ch)` produced `NaN|0`.

### Internal / API hygiene

- **`meme-lang.api/to-clj` and `to-meme`** marked `^:no-doc`.
- **`meme.registry/clear-user-langs!`** and **`registered-extensions`** marked `^:no-doc`.
- **`meme-lang.parselets/reader-cond-extra`** made private.
- **Missing docstrings** added.

## [3.3.3] — 2026-04-12

### Fixed

- **Red team findings** — reader-cond validation, registry TOCTOU around extension conflicts, CLI testability improvements.
- **Deterministic depth guard in `clj->forms`** — no longer relies on catching `StackOverflowError`.
- **Fuzzer state consolidated under `fuzz/`**.

### Documentation

- Added namespace-loading and `compile` sections to the README.
- Updated installation version pin.

## [3.3.0] — 2026-04-09

### Added

- **`load-file` interception** — `(load-file "path/to/file.meme")` runs through the meme pipeline on both JVM and Babashka.
- **`meme compile` CLI command** — compiles `.meme` to `.clj` into a separate output directory.
- **Project-local `.meme-format.edn` config** — `meme format` discovers config by walking up from CWD.

### Changed — Formatter (three-layer architecture)

- **Style policy extracted from the printer into the formatters.** The printer is now notation-only; `meme-lang.formatter.canon/style` carries the slot-keyed opinions.
- **Form-shape layer** (`meme-lang.form-shape`) — semantic decomposition of special forms into named slots.
- **Opt-in structural fallback** — `with-structural-fallback` wraps a registry so user macros shaped like `defn` or `let` inherit canonical layout.
- **Slot renderers as override-able defaults**.
- **Canon layout refinements:** inline collection layout, head-line bindings, definition-form spacing, paired clause bodies, columnar pair alignment, closing paren on own line for multi-line calls.

### Fixed

- **Four loader bugs** found during the loader work.

## [3.0.0] — 2026-04-05

### Added
- **Namespace loader** (`meme.loader`): intercepts `clojure.core/load` to find `.meme` files on the classpath. Auto-installed by `run-file` and REPL start.
- **Multi-extension support**: `register!` accepts both `:extension` (string) and `:extensions` (vector).
- **Namespace denylist in loader**: `clojure.*`, `java.*`, `javax.*`, `cljs.*`, `nrepl.*`, `cider.*` cannot be shadowed by `.meme` files. *(Removed in post-5.0.0.)*
- **Loader uninstall guard**: `uninstall!` throws when called from within a lang-load.
- **`#::{}` bare auto-resolve**: namespaced maps with empty alias accepted.
- **Red team report**: `doc/red-team/report.md`.
- **Design documentation**: "The call/data tension" section in `doc/design-decisions.md`.

### Changed
- **Architecture**: three-layer reorganization — `meme.tools.*` (generic parser/render), `meme-lang.*` (meme language), `meme.*` (CLI/registry/loader). The Pratt parser is fully data-driven via grammar spec.
- **Metadata namespace hygiene**: all internal metadata keys moved from bare `:meme/*` to `:meme-lang/*`. *(Renamed back to `:meme/*` in post-5.0.0 work, then collapsed entirely once the AST tier replaced metadata-based notation tracking.)*
- **`register!` conflict check is atomic**.
- **CLI reads file once**: `process-files` eliminates TOCTOU between read and write.
- **`meme-file?` and `swap-ext`** consult the registry for all registered extensions.
- **`strip-shebang`** correctly handles CRLF line endings.
- **`clj->forms`** catches `StackOverflowError` on deeply nested input.

### Fixed
- **`%N` param OOM**: anonymous function params capped at `%20`.
- **`:/foo` keyword accepted**: leading-slash keywords now rejected.
- **`:shebang` atom in CST reader**: double-shebang files no longer error.
- **Empty hex literal `0x`**: produces "Empty hex literal".
- **`:meme/order` stale metadata**: printer falls back to unordered when stale.
- **U+2007 FIGURE SPACE** classified as whitespace.
- **UTF-16 surrogate pairs**: consumed as single `:invalid` token.
- **Intermediate `#_` discard tokens** capture start position.
- **Scar tissue triage**: ~15 comment-only regression blocks converted to active tests.

### Removed
- **All bare `:meme/*` short-form internal metadata keys**: replaced by `:meme-lang/*`. *(See post-5.0.0 work for the eventual collapse of these into AST node fields.)*

## [1.0.0] — 2026-04-01

### Changed
- **Graduated to 1.0.0**: all namespaces are `meme.*` (no more `meme.alpha.*`).
- **JAR packaging**: resources directory now included.
- **CLI `get-lang`**: fixed variable shadowing.
- **CLJS unicode escapes**: `\uNNNN` with invalid hex digits now throws.
- **Error messages** improved across `load-resource-edn`, `clj->forms`, CLJS tagged-literal.
- **Reflection warnings** eliminated.
- **CLI load failure**: friendly error without extra stack trace.
- **Docstrings** added to public vars; doc fixes.
- **Documentation** stale-reference cleanup.
- **CLI rewrite**: replaced `.clj` shim + `.meme` bootstrap with plain Clojure.
- **CI**: pinned Babashka and Clojure CLI versions.

### Removed
- **`meme.convert` module**: removed in favor of `:to-clj` / `:to-meme` commands on lang maps.

### Added
- **`meme.core/version`**: runtime version access.

### Deprecated
- **Legacy lang aliases**: `:classic`, `:rewrite`, `:ts-trs`. *(Aliases later removed entirely in post-5.0.0.)*

## [0.13.0] — 2026-04-01

### Added
- **Map/set pattern matching** in rewrite engine.
- **Map/set traversal** in `rewrite-once`.
- **TRS chained calls**: `f(x)(y)` → `((f x) y)`.
- **Comment preservation** in formatter.
- **CLJS generative parity**: 5 property-based tests now run on ClojureScript.
- **Formatter idempotency tests** (deterministic + 300 trials property-based).
- **Comment preservation fixture**.
- **Three-lang benchmark** across 11 fixtures and 7,526 vendor forms.

### Fixed
- **Tokenizer EOF-after-backslash**.
- **Rewrite emitter regex escaping**.
- **Prelude spec** corrected.
- **Generative set duplicates** in test generator.

## [0.12.0] — 2026-04-01

### Added
- **Unified convert CLI**: `meme convert --lang meme-classic|meme-rewrite|meme-ts-trs`.
- **Unified convert module** (`meme.convert`).
- **Comparative benchmark** across 11 meme fixtures and 7,526 vendor forms.
- **Language platform**: `register!` API for guest languages (`meme.lang`).
- **Term rewriter**: bottom-up rewrite engine with `?x`/`??x` pattern variables (`meme.rewrite`).
- **Rewrite-based parser** (`meme.rewrite.tree`).
- **Stage contracts**: opt-in spec validation (`meme.stages.contract`).
- **LANGBOOK.md** — language-maker cookbook.

### Fixed
- **nil/true/false as call heads**: `nil(1 2)` → `(nil 1 2)` accepted.
- **Tokenizer ns// symbols**: `clojure.core//` reads as one symbol.
- **Rewrite emitter type gaps**: BigDecimal `M`, BigInt `N`, `##NaN`/`##Inf`/`##-Inf`, tagged literals, named chars, `pr-str` fallback.
- **Reader conditional preservation** across all three langs during conversion.
- **Prelude expansion** through `step-expand-syntax-quotes`.
- **build-tree delimiter validation** after `#?` and `#:ns`.
- **load-prelude docstring**.
- **README.md dead link**.

## [0.6.0-alpha] — 2026-03-30

### Fixed
- **defrecord-as-map bugs**: AST node defrecords satisfy `(map? x)`, causing silent mishandling. All dispatch sites now guard with type predicates.
- **#?@ splicing**: `#?@(:clj [2 3])` inside a collection now correctly splices elements.
- **Positive-sign BigInt/Ratio**: `+42N` and `+3/4` now parse correctly.
- **Literal head syntax**: `nil(x)`, `true(x)`, `false(x)` are valid syntax.
- **Nested syntax-quote semantics**: `` ``x `` now correctly produces double-quoting.

### Changed
- **Expander extracted** to new `meme.parse.expander` namespace.
- **Shared utilities**: `strip-internal-meta` and `percent-param-type` moved to `meme.forms`.
- **CI deploy gate**: ClojureScript tests now required before deployment.

## [0.5.0-alpha] — 2025-03-30

Initial public alpha release.

### Added
- Three-stage reader: scan (tokenizer) → group → parse (reader).
- Printer and pretty-printer (width-aware, comment-preserving).
- Full Clojure syntax support.
- Syntactic transparency via `:meme/sugar` metadata.
- Syntax-quote preserved as AST node (`MemeSyntaxQuote`), expanded before eval.
- Raw value wrapper (`MemeRaw`).
- `:read-cond :preserve` option.
- REPL with multi-line input.
- CLI (run, repl, convert, format) — self-hosted in `.meme`.
- Cross-platform support: JVM, Babashka, ClojureScript.
- Generative property-based tests.
- Vendor roundtrip tests against 7 real-world Clojure libraries.
- Dogfood tests with clj-kondo semantic equivalence.
- Clojars deployment via CI on version tags.
