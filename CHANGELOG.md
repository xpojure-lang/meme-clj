# Changelog

All notable changes to **meme-clj** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/).

> **Pre-5.0 history is archived in [CHANGELOG-archive.md](CHANGELOG-archive.md).**
> The language identifier was renamed twice during 5.0 development
> (`meme` → `mclj` → `m1clj`); the codebase now uses **m1clj** for the
> language and keeps **meme** / **meme-clj** for the toolkit, the CLI
> binary, and the project. Pre-5.0 entries describe an earlier shape of
> the codebase and are kept for historical reference only. See
> [doc/glossary.md](doc/glossary.md) for the current vocabulary.

## [Unreleased]

Post-5.0.0 work, in four threads: an **AST tier** (lossless intermediate
between CST and Clojure forms), a **native-Clojure parser** registered as
a sibling guest (`clj-lang`), a **fuzzing + cross-check parity gate** that
drove both to convergence with `clojure.core/read-string`, and a **third
guest language `m2clj`** seeded as a sovereign sibling to `m1clj`.

### Added

- **`m2clj` language.** A sovereign sibling to `m1clj`: same M-expression
  call rule, plus one extra — a paren without head adjacency (`(x y z)`)
  is a list literal that lowers to `(quote (x y z))` instead of being a
  parse error. Calls still require head adjacency, so call-vs-data
  remains structural at the reader layer. Full lang implementation under
  `src/m2clj_lang/*` (api, grammar, parselets, lexlets, form-shape,
  printer, formatter.flat, formatter.canon, run, repl) — sovereign tree,
  not a refactor of m1clj. Self-registers as `:m2clj` builtin via
  `meme.cli` (built-in alongside `:m1clj` and `:clj`). File extension:
  `.m2clj`.

- **`CljQuote{notation}` field.** The AST tier's `CljQuote` record gained
  a `:notation` field (`:tick` for `'x` sugar, `:bare` for m2clj's
  bare-paren list literal, `:call` for `(quote x)`). The printer
  dispatches on notation to reconstruct the original surface, preserving
  syntactic transparency across all three guests.

- **`:bare-list` CST node + form-layer cst-reader handling.** The
  parser's bare-paren branch produces a `:bare-list` CST node which the
  cst-reader and AST builder both lower to `(quote (…))`. New bare-paren
  branch in `meme.tools.clj.cst-reader`.

- **AST tier (`meme.tools.clj.ast.*`).** A lossless representation between
  CST and plain Clojure forms. Notation, position, and trivia live on
  record fields rather than form metadata, so they survive arbitrary
  walkers. 25 record types covering atoms (`CljSymbol`, `CljKeyword`,
  `CljNumber`, `CljString`, `CljChar`, `CljRegex`, `CljNil`, `CljBool`),
  collections (`CljList`, `CljVector`, `CljMap`, `CljSet`), reader-macro
  nodes (`CljQuote`, `CljDeref`, `CljVar`, `CljSyntaxQuote`,
  `CljUnquote`, `CljUnquoteSplicing`, `CljAnonFn`, `CljDiscard`),
  compound forms (`CljTagged`, `CljReaderCond`, `CljMeta`,
  `CljNamespacedMap`), and a top-level `CljRoot`. Each implements the
  `AstNode` protocol with `children` / `rebuild`. `ast=` is structural
  equality without notation; `defrecord =` stays strict.

- **`m1clj-lang.api/m1clj->ast` and `clj->ast`** — public entry points
  for the AST tier. Tooling that needs round-trip fidelity (formatters,
  refactorers, transpilers) consumes these directly. `m1clj->forms` is
  now a thin shim over `m1clj->ast` followed by `ast->forms`.

- **Native Clojure parser (`meme.tools.clj.parser.*`).** Parses native
  S-expression Clojure source through the same engine as m1clj —
  `parse-string` (CST), `clj->ast` (lossless AST), `clj->forms` (lossy
  forms). Reuses the parser engine, lex layer, AST builder, and
  lowering pipeline; only the call rule and `#(...)` body shape differ
  from m1clj's grammar.

- **`:clj` lang registered (`clj-lang.api`).** `meme format file.clj`
  now formats native Clojure source via the native parser; `meme
  to-m1clj file.clj` converts losslessly. The lang exposes `:format`,
  `:to-clj`, `:to-m1clj` and is registered as a built-in alongside
  `:m1clj`. `m1clj-lang.formatter.canon/format-form` gained a `:mode`
  opt (default `:m1clj`) so the canonical formatter renders both
  surfaces from one implementation.

- **`m1clj->clj` and `clj->m1clj` are lossless.** Both route through the
  AST tier. Reader sugar (`'`, `@`, `#'`, `#()`), namespaced-map
  prefixes, set source order, multi-tier metadata chains, and bare `%`
  notation all survive cross-surface conversion. `clj->forms` and
  `clj->m1clj` now also work on ClojureScript (no `read-string`
  dependency).

- **Vendor cross-check parity gate.** `test/meme/vendor_cross_check_test.clj`
  compares native parser output to `clojure.core/read-string` on each
  `.clj`/`.cljc` file across all 7 vendor projects (core.async, specter,
  malli, ring, clj-http, medley, hiccup), normalising cosmetic
  differences (`fn*`/`fn`, gensym suffixes, regex `Pattern` vs string,
  `##NaN` sentinel, `%&` → `<rest>`). Per-project parity baselines
  encode current state and act as a ratchet. **All seven projects sit
  at baseline 0** — full parity, any new divergence is a regression.

- **Fuzzer expanded to 7 targets.** Coverage-guided exploration of the
  toolkit, including the AST tier and native parser:
  - `RoundtripTarget` — m1clj parse → print → re-parse identity.
  - `FormatTarget` — formatter exception safety.
  - `IdempotentTarget` — `format(format(s)) == format(s)`.
  - `FormsToCljTarget` — m1clj→Clojure boundary differential.
  - `NativeCljTarget` — `clj->forms` round-trip.
  - `CrossCheckTarget` — promotes vendor cross-check to a property.
  - `CljModeIdempotentTarget` — `:clj`-mode formatter idempotence.
  Seed corpus of 18 hand-curated files (1140 lines), 92-entry
  `meme.dict`, 377 coverage-guided cases. `bb fuzz-quick` runs all
  seven at 50K runs each (~7 min).

### Fixed

- **`expand-sq` collection branches recurse into the unquoted form** —
  nested syntax-quotes inside `~`/`~@` items in lists/vectors/maps/sets
  no longer leak `CljSyntaxQuote` records past the expander. Cleared
  14 of 14 expander-error files in vendor cross-check (hiccup compiler,
  core.async ioc-macros, malli util/cljs).
- **`expand-sq-meta` wraps `concat` in `seq`** to match Clojure's
  `SyntaxQuoteReader` exactly under cross-check parity.
- **`expand-sq` quotes `ReaderConditional` records** when expanding
  `` `#?(:clj a :cljs b) `` under `:read-cond :preserve`.
- **`default-resolve-symbol` handles static methods, constructors, and
  fully-qualified class symbols** the way Clojure's `SyntaxQuoteReader`
  does. `Foo.` → `pkg.Foo.`; `Class/member` → `pkg.Class/member`;
  dotted FQN class names left unprefixed.
- **`#()` percent-param scoping handles nested user `(fn …)` bodies.**
  A `%` inside a user-written `(fn …)` that lives inside `#()` now
  correctly belongs to the outer `#()` (Clojure semantics). The form
  walker had been confusing user `fn` with already-lowered nested
  `#()`. Fixed at the AST tier (`CljAnonFn`) before lowering.
- **`resolve-tagged-literal` consults `*data-readers*` and
  `default-data-readers`.** `#uuid "…"` and `#inst "…"` resolve to
  `java.util.UUID` and `java.util.Date` at read time, matching
  `clojure.core/read-string`.
- **Tagged literals on ClojureScript no longer error.** `#inst`
  resolves to `js/Date`, `#uuid` to `cljs.core/UUID`; unknown tags fall
  back to a `TaggedLiteral` matching the JVM shape.
- **Duplicate-key detection across notations.** `{0xFF 1 255 2}` and
  `{A 1 \A 2}` no longer accept two keys that resolve to the same
  value — `CljRaw` is unwrapped before duplicate detection.
- **`cli/run` double-slurp** — file is now read once and reused.
- **`with-load-tracking` counter imbalance** when `swap! inc` throws.
- **Leftover unquote errors carry source location** through expansion.
- **`loader/uninstall!` dispatch-gap race** — concurrent
  install/uninstall during in-flight loads is now serialised under
  `install-lock`; stale references remain safe to deref.
- **`cli` bad-flag error matched structurally** via `(:type (ex-data e))`
  instead of regex on the message.
- **`columnar-pairs-doc` empty-input crash** — falls back to width 0
  when there are no key widths.
- **`registry/resolve-symbol` non-invocable result** rejects at
  resolution time with a clear message.
- **CLJS test suite is now green** (was 313 errors). Root cause:
  `(instance? meme.tools.clj.ast.nodes.CljSymbol x)` (dotted JVM FQN)
  doesn't resolve on ClojureScript — defrecord types are JS exports
  referenced by namespace path. Fixed across `m1clj-lang.printer`,
  `m1clj-lang.form-shape`, `meme.tools.clj.ast.lower`, and
  `clj-lang.api` by `:refer`-ing the record types on CLJS and using
  bare names at every call site.

### Internal

- **`registry/register-string-handler!` is now first-wins.** Previously
  later registrations silently overrode earlier ones — once `m2clj-lang`
  registered an identical `:run` string handler, load order across guests
  determined which won. The slot is now idempotent: the first lang to
  register wins, subsequent calls for the same command are no-ops.
  `m1clj-lang` loads first in `meme.cli`, so its handler is authoritative;
  `m2clj-lang`'s call is a no-op when bundled, but kept so the lang is
  self-sufficient when loaded standalone. Once a lang needs a genuinely
  divergent string convention, scope handlers per-lang (post-split
  shape: `{lang-name {command handler}}`).
- **`with-m1clj-grammar` triplication lifted into
  `m1clj-lang.grammar/with-grammar`** — single shared definition; all
  three call sites delegate.
- **BOM + shebang stripping consolidated** in `meme.tools.clj.stages`
  (`strip-bom`, `strip-source-preamble`).
- **`clear-user-langs!` moved out of production** to
  `test/meme/test_registry.clj`.
- **Tombstoned scar comments** in `test/meme/regression/reader_test.cljc`
  inlined as real `deftest` assertions so the regression net is
  locally guaranteed.
- **Dogfood roundtrip expanded** to include `meme.tools.parser`,
  `meme.tools.render`, `m1clj-lang.grammar`, `m1clj-lang.form-shape`,
  and `m1clj-lang.formatter.canon`.
- **Direct unit tests for `meme.tools.clj.lex`** —
  `consume-char-literal`, `consume-symbol`, `consume-keyword`,
  `consume-number`, `consume-string`, plus the `whitespace-char?` /
  `symbol-start?` predicates.
- **`registry_test.clj` flake-prone `Thread/sleep`** replaced with
  `future`s + `(run! deref futures)`.
- **Vendor roundtrip failure messages** show truncated `pr-str` for
  non-seq forms instead of `?`.
- **CLI `--out` validation deduplicated** behind `validate-out-dir!`.

## [5.0.0] — 2026-04-19

Reader-conditional handling becomes a pipeline stage instead of a reader
flag. Tooling-path APIs (`m1clj->forms`, `m1clj->clj`) are lossless by
default for `.cljc` sources.

### Breaking Changes

- **The `:read-cond` option is removed** from the read API and the
  `step-read` pipeline stage. Reader conditionals (`#?`, `#?@`) are
  always returned as `CljReaderConditional` records. Passing
  `:read-cond` throws `:m1clj/deprecated-opt` with migration text.

  **Migration:**
  - If you used `{:read-cond :preserve}`: remove it. Records are the default now.
  - If you relied on `:eval` (platform materialization at read time):
    compose `meme.tools.clj.stages/step-evaluate-reader-conditionals`
    after `step-read`, or use `run-string`/`run-file`/REPL — all of
    which do so automatically.

- **`m1clj->clj` is lossless by default.** Previously it evaluated `#?`
  for the current platform, silently dropping off-platform branches.
  Both branches are now preserved in the emitted Clojure text. Use
  `run-string` for eval-time behavior.

- **`meme compile` renamed to `meme transpile`.** The command is a
  same-level surface-syntax transform, not a lowering to bytecode —
  `transpile` is the accurate term. `compile` still works as an alias.

- **Default `--out` directory** for `transpile` changed to `target/m1clj`
  (was `target/classes`). Avoids collision with `tools.build` / AOT
  output. Either pass `--out target/classes` explicitly or update
  `:paths` in `deps.edn`.

- **Scanner / reader strictness.** Several malformed inputs that
  previously read silently now error at read time, matching Clojure's
  reader:
  - `//`, `//a`, `/foo` rejected (`/`, `ns//`, `foo/bar/baz` stay valid).
  - `\uNNNN` followed by any alphanumeric rejected.
  - Bare `` `~~x `` errors at expander time.
  - Variation selectors U+FE00–U+FE0F inside symbols rejected
    (stricter than Clojure; blocks look-alike-symbol attacks).
  - U+2028 / U+2029 count as line terminators in error positions.

### Added

- **`step-evaluate-reader-conditionals`** — pipeline stage in
  `meme.tools.clj.stages` that evaluates `#?`/`#?@` records for a
  target platform. Supports `:platform` opt (default: compile-time
  platform), `:default` fallback, and recurses into syntax-quote
  interiors matching native Clojure's reader-time evaluation order.
  Tooling paths skip the stage and preserve records.

- **`:default` fallback in reader conditionals.**
  `#?(:cljs 1 :default 99)` on JVM now returns `99`.

- **`from-clj` CLI alias** for users who prefer source-based naming.

- **`meme build` CLI command** — transpile + AOT-compile to JVM
  bytecode in one step. Stops at `.class` files; JAR packaging stays
  in the user's tools.build layer.

### Changed

- **`run-string`, `run-file`, REPL** — `step-evaluate-reader-conditionals`
  inserted automatically between `step-read` and
  `step-expand-syntax-quotes`.
- **Pipeline contract map** gains an entry for the new stage; four
  entries instead of three.

### Fixed

- **`m1clj->clj` silently dropped off-platform branches** of `#?` on
  `.cljc` sources. The asymmetry between library and CLI conversion
  paths is gone — both preserve faithfully.
- **`clj->forms` depth guard off-by-one** — both entry points now
  reject at exactly `max-parse-depth`.
- **`meme.loader/uninstall!` thread-safety** — replaced thread-local
  `*loading*` with a `load-counter` atom and `install-lock` monitor.
- **Bare `:/` reads as `(keyword "/")`** instead of erroring.
- **Bare `~~x` no longer leaks** an unquote record to eval.
- **`meme transpile` Windows path bug** — uses
  `java.io.File/separator` instead of hardcoded `/`.
- **`meme transpile --out ""`** fails fast.

### Known Limitation

- **`#?@` inside a map literal fails at read time** (odd-count children).
  Matches Clojure's `:read-cond :preserve` behavior.
