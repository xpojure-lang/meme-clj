# Changelog

All notable changes to meme-clj will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

Post-5.0.0: platform / lang separation, Clojure-surface extraction (`meme.tools.clj.*`), correctness pack, **meme-lang → mclj-lang rename**, then **mclj → m1clj rename** layered on top (file extension, namespace, registry key, public API, metadata vocabulary). The two renames compose: a 4.x consumer migrating today should apply the meme-lang→mclj-lang mapping first, then mclj→m1clj.

### Breaking Changes

- **`mclj` → `m1clj` rename (literal substring sweep).** Every `mclj` token becomes `m1clj`:
  - **Namespaces**: `mclj-lang.*` → `m1clj-lang.*` (directory `src/mclj_lang/` → `src/m1clj_lang/`, `test/mclj_lang/` → `test/m1clj_lang/`).
  - **Registry key**: `:mclj` → `:m1clj`. `meme.registry/default-lang` is now `:m1clj`. EDN lang files passing `:format :mclj` must use `:format :m1clj`.
  - **Public API**: `mclj->forms` → `m1clj->forms`, `forms->mclj` → `forms->m1clj`, `mclj->clj` → `m1clj->clj`, `clj->mclj` → `clj->m1clj`, `format-mclj` → `format-m1clj`, `format-mclj-forms` → `format-m1clj-forms`.
  - **Lang-map keys**: `:to-mclj` → `:to-m1clj`. CLI subcommand `meme to-mclj` → `meme to-m1clj` (the `from-clj` alias still works).
  - **File extensions**: `.mclj` → `.m1clj`, `.mcljc` → `.m1cljc`, `.mcljj` → `.m1cljj`, `.mcljs` → `.m1cljs`. The soft-deprecated `.meme*` extensions are unchanged (still recognized for one release with the existing warning).
  - **Printer mode keyword**: `:mclj` → `:m1clj` (paired with `:clj`). Affects `m1clj-lang.printer/to-doc` callers passing the mode explicitly.
  - **ex-info vocabulary**: `:mclj/*` ex-info `:type` keys become `:m1clj/*` — `:m1clj/pipeline-error`, `:m1clj/deprecated-opt`, `:m1clj/missing-grammar`. External code catching `:mclj/pipeline-error` ex-infos must update. (The `:m1clj/leading-trivia` / `:m1clj/sugar` / `:m1clj/insertion-order` / `:m1clj/namespace-prefix` / `:m1clj/meta-chain` / `:m1clj/bare-percent` form-metadata vocabulary that briefly existed under both spellings is gone — see "Removed".)
  - **Build staging directory**: `target/mclj` → `target/m1clj`. Affects `meme transpile` (default `--out`) and `meme build` (fixed staging path).
  - **AST record names** (`Clj*`) and **project / CLI names** (`meme-clj/`, `meme` binary) are untouched — none contain the `mclj` substring. The `meme.tools.*`, `meme.tools.clj.*`, `meme.registry`, `meme.loader`, `meme.cli` toolkit namespaces are likewise unchanged.

- **Namespace `meme-lang.*` → `mclj-lang.*`.** All 8 `src/mclj_lang/*` files and 15+ `test/mclj_lang/*` files. Toolkit (`meme.tools.*`, `meme.cli`, `meme.registry`, `meme.loader`) is unchanged.

- **Lang registry key `:meme` → `:mclj`.** `meme.registry/default-lang` is now `:mclj`. CLI invocations using `--lang meme` must use `--lang mclj`. EDN lang files passing `:format :meme` must use `:format :mclj`.

- **Public API renames (no aliases):**
  - `meme->forms` → `mclj->forms`
  - `forms->meme` → `forms->mclj`
  - `meme->clj` → `mclj->clj`
  - `clj->meme` → `clj->mclj`
  - `format-meme` → `format-mclj`
  - `format-meme-forms` → `format-mclj-forms`

- **Lang-map key `:to-meme` → `:to-mclj`.** CLI subcommand `meme to-meme` → `meme to-mclj`. The `from-clj` alias still works.

- **Printer mode keyword `:meme` → `:mclj`** (paired with `:clj`). Affects `mclj-lang.printer/to-doc` callers passing the mode explicitly.

- **Metadata + ex-info vocabulary `:meme-lang/*` → `:mclj/*`.** All 9 keys: `:mclj/leading-trivia`, `:mclj/sugar`, `:mclj/insertion-order`, `:mclj/namespace-prefix`, `:mclj/meta-chain`, `:mclj/bare-percent`, `:mclj/pipeline-error`, `:mclj/deprecated-opt`, `:mclj/missing-grammar`. External code walking emitted forms or catching `:mclj/pipeline-error` ex-infos must update. (Mid-cycle dev builds carried these briefly as `:meme/*` after the toolkit-vs-lang split landed; the final shape is `:mclj/*`.)

- **AST record names: `Meme*` → `Clj*`.** `MemeSyntaxQuote`, `MemeUnquote`, `MemeUnquoteSplicing`, `MemeRaw`, `MemeAutoKeyword`, `MemeReaderConditional` → `CljSyntaxQuote`, `CljUnquote`, `CljUnquoteSplicing`, `CljRaw`, `CljAutoKeyword`, `CljReaderConditional`. Content is Clojure-semantic; names follow suit. Predicate `meme-reader-conditional?` → `clj-reader-conditional?`.

- **Namespace moves: `meme-lang.*` → `meme.tools.clj.*`** for the Clojure-surface commons shared by every Clojure-flavored frontend. Moved: `stages`, `cst-reader`, `resolve`, `expander`, `forms`, `errors`, `values`, plus `lex` (extracted from `meme-lang.lexlets`), `run`, `repl`. `meme-lang.{stages,cst-reader,...}` no longer exist as namespaces; import from `meme.tools.clj.*`. `meme-lang.run`, `meme-lang.repl`, `meme-lang.lexlets` remain as thin shims that inject meme's grammar/banner and delegate.

- **Build staging directory `target/meme` → `target/mclj`.** Affects `meme transpile` (default `--out`) and `meme build` (fixed staging path).

### Deprecated

- **File extensions `.meme`, `.memec`, `.memej`, `.memejs` are deprecated.** `.mclj`, `.mcljc`, `.mcljj`, `.mcljs` are the new primary extensions. Deprecated extensions are still recognized for one release; loading a file with a deprecated extension emits a one-time `*err*` warning per process. Removal planned in the next major.

### Removed

- **`:m1clj/*` form-metadata vocabulary.** `:m1clj/leading-trivia`, `:m1clj/sugar`, `:m1clj/insertion-order`, `:m1clj/namespace-prefix`, `:m1clj/meta-chain`, and `:m1clj/bare-percent` are gone. Sugar form, comments/trivia, set source order, namespaced-map prefixes, and bare-percent notation all live on AST nodes (`meme.tools.clj.ast.*`) — the lossless tier. The plain-form pipeline (`m1clj->forms`, `forms->m1clj`, `forms->clj`) is intentionally lossy and prints structurally. Helpers tied to these keys (`forms/notation-meta-keys`, `forms/walk-meme-set`, `forms/meme-set-source-seq`, `forms/with-refreshed-set-order`, `forms/restore-bare-percent`) are deleted; `forms/internal-meta-keys` shrinks to position-only keys (`:line :col :column :file`). Callers that walked emitted forms looking for these keys now read AST node fields instead.

- **`implojure-lang` removed.** The sibling Clojure-flavored frontend and its grammar test suite are gone (`src/implojure_lang/`, `test/implojure_lang/`, the `[implojure-lang.api]` require in `meme.cli`, related test patterns in `deps.edn`). The Clojure-surface commons (`meme.tools.clj.*`) remain shared-by-design — the abstraction is still right and pre-pays for the next sibling — but the only registered built-in lang is now meme.

- **`.meme-format.edn` / `meme.config` removed.** The project-local formatter config feature (added as requirement F6) was never adopted — no `.meme-format.edn` file ever existed in this repo or any consumer. The 196-line reader plus 19 validation tests were dead weight. `meme format` now takes CLI flags only (`--width`, `--style`, `--stdout`, `--check`); if project-local config is ever wanted for real, a ~40-line reader can be rebuilt when the need materializes.

- **calc-lang demo and `examples/languages/` directory removed.** The multi-lang platform claim is now carried by meme + the user-registerable lang mechanism. The demo added maintenance surface without further demonstrating anything.

- **Loader namespace denylist.** `denied-prefixes` / `denied-namespace?` and the `when-not` guard in `find-lang-resource` are gone. Installing a lang is the trust decision — if a user puts a `.meme` file at `clojure/core.meme` on the classpath, the loader now honors it rather than paternalistically refusing. The infinite-recursion concern the denylist was framed against is actually handled by caching `registered-extensions` as a function value at `install!` time (so `find-lang-resource` never does `requiring-resolve` during load interception); that guard is unchanged. Removes `denied-namespaces-not-intercepted` and `own-infrastructure-not-intercepted` tests. PRD row PL13 withdrawn.

- **Legacy lang aliases** `:classic`, `:meme-classic`, `:meme-experimental` and their deprecation-warning branch in `registry/resolve-lang`. The aliases had no callers in `src/`, `test/`, `doc/`, or any consumer — just dead cruft since the pre-lang naming was retired. Users must pass `:meme` directly; the existing "Unknown lang: ..." error still lists available langs if someone hits a removed name.

### Added

- **AST tier (`meme.tools.clj.ast.*`).** A structural representation between CST and Clojure forms, with notation, position, and trivia preserved as record fields rather than metadata. Survives any walker — including ones that don't know about m1clj's metadata. 25 record types covering atoms (`CljSymbol`, `CljKeyword`, `CljNumber`, `CljString`, `CljChar`, `CljRegex`, `CljNil`, `CljBool`), collections (`CljList`, `CljVector`, `CljMap`, `CljSet`), reader-macro nodes (`CljQuote`, `CljDeref`, `CljVar`, `CljSyntaxQuote`, `CljUnquote`, `CljUnquoteSplicing`, `CljAnonFn`, `CljDiscard`), compound forms (`CljTagged`, `CljReaderCond`, `CljMeta`, `CljNamespacedMap`), and a top-level container (`CljRoot`). Each implements the `AstNode` protocol with `children` / `rebuild`. `ast=` is structural-equality-without-notation (ignores `pos`, `trivia`, `raw`); defrecord `=` stays strict. Lives in `meme.tools.clj.ast.nodes`. Public API — semver-protected.

- **`meme.tools.clj.ast.build/cst->ast`** — walks a CST and produces a `CljRoot` AST. Mirrors `cst-reader/read-node` node-by-node, reusing the existing atomic resolvers (resolve-number / -char / -regex) for value parsing. Defers `::keyword` and tagged-literal resolution to the lowering phase — the AST captures source structure, not resolved values.

- **`meme.tools.clj.ast.lower/ast->form` + `ast->forms`** — projects an AST to plain eval-ready Clojure forms (no notation metadata). The form path is intentionally lossy now; lossless tooling consumes the AST directly. Wired via the `Lowerable` protocol extended once across all 24 record types.

- **`m1clj-lang.api/m1clj->ast`** — public entry point for the AST tier. Returns a `CljRoot`. Tooling that needs round-trip fidelity (refactor, lint, transpile, format) consumes this directly. `m1clj->forms` is now a thin shim: `(ast->forms (m1clj->ast s opts) opts)`.

- **`m1clj->clj` is now lossless** — routes through the AST tier and the printer's `:clj` mode rather than the lossy form path. Reader sugar (`'`, `@`, `#'`, `#()`), namespaced-map prefixes, set source order, multi-tier metadata chains, and bare `%` notation all survive the meme→Clojure conversion.

- **Single-pipeline cutover for `:m1clj/*` metadata.** The pipeline previously decorated forms with `:m1clj/leading-trivia` / `:m1clj/sugar` / `:m1clj/insertion-order` / `:m1clj/namespace-prefix` / `:m1clj/meta-chain` / `:m1clj/bare-percent`. After this release the AST is the lossless tier and the form path is structural; tooling that needs round-trip fidelity consumes AST nodes via `m1clj-lang.api/m1clj->ast` and `meme.tools.clj.ast.*`.

- **Native Clojure parser (`meme.tools.clj.parser.*`).** A sibling to `m1clj-lang.grammar` that parses native Clojure surface (`(f x y)` with the head inside the parens, no M-expression call adjacency). Reuses the same parser engine, lex layer, AST builder, and lowering pipeline — only the call rule and `#(...)` body shape differ. `meme.tools.clj.parser.parselets` houses dispatch-scanlet (now a factory: `make-dispatch-scanlet {:anon-fn-body :expressions|:list}`), tilde-scanlet, and `sign-followed-by-digit?` — all extracted from `m1clj-lang.parselets`, so both grammars share one source of truth for Clojure-surface dispatch. `m1clj-lang.parselets` shrinks to the meme-specific bits: adjacency detection and the M-expression call-scanlet. Lower-tier infrastructure: lives in `meme.tools.clj.*`, not as a peer lang. Built so `.clj` source can round-trip losslessly through the AST tier without depending on `clojure.core/read-string`.

- **Public Clojure parser API (`meme.tools.clj.parser.api`).** Three entry points: `parse-string` (CST), `clj->ast` (CljRoot AST — lossless), `clj->forms` (vector of plain forms — lossy). Same `Clj*` AST taxonomy as `m1clj->ast`; consumer code that walks AST nodes works unchanged on either source surface.

- **`m1clj-lang.api/clj->ast` + cross-platform `clj->forms` + lossless `clj->m1clj`.** `clj->forms` no longer requires JVM/Babashka — it routes through the native parser and works on ClojureScript too. `clj->m1clj` is now lossless via the AST tier: reader sugar (`'`, `@`, `#'`, `#()`), comments, namespaced-map prefixes, set source order, and metadata chains all survive the conversion. Reader conditionals are preserved as `#?(...)` rather than evaluated.

- **`#=` read-eval explicitly rejected.** The dispatch-scanlet now emits an error CST node for `#=` instead of treating it as a tagged literal. m1clj's grammar caught this incidentally via the bare-parens rule; the native Clojure grammar needs an explicit reject so `#=(+ 1 2)` doesn't quietly become a `#=`-tagged form. Matches the prior `read-string`-with-`*read-eval* false` behavior of `clj->forms`.

- **Vendor cross-check (`test/meme/vendor_cross_check_test.clj`).** A regression net for the native Clojure parser: every `.clj`/`.cljc` file in the vendor submodules is parsed via `clj->forms`, and the test fails if the native parser crashes on any input that `clojure.core/read-string` accepts cleanly. Form-level equality between the two readers is intentionally not asserted — they differ in cosmetic ways (`fn*` vs `fn` for `#()`, gensym-named params vs source-preserved `%1`, syntax-quote-as-record vs expanded `(seq (concat ...))`) — a tighter parity gate is future work. Currently green on all 7 vendor projects (core.async, specter, malli, ring, clj-http, medley, hiccup); native parser handles real-world Clojure source the same set as `read-string` modulo features that need namespace context (auto-resolve keywords) or `*read-eval*` (record literals).

- **`:clj` lang registered (`clj-lang.api`).** `meme format file.clj` now formats native Clojure source via the native parser; `meme to-m1clj file.clj` converts losslessly. The lang exposes `:format`, `:to-clj`, `:to-m1clj` and is registered as a built-in alongside `:m1clj`. `m1clj-lang.formatter.canon/format-form` gained a `:mode` opt (default `:m1clj`) so the canonical formatter renders both surfaces from one implementation. CLI predicate split: `m1clj-file?` (lang-specific, used by `to-clj` / `transpile` / `build`) vs `recognized-file?` (any registered lang, used by `format`).

- **Cross-check parity gate (vendor cross-check tightened).** The `meme.vendor-cross-check-test` ns now compares form vectors structurally between `clojure.core/read-string` and the native parser, after expanding syntax-quote on both sides and applying a small set of cosmetic normalisations (`fn*`/`fn`, `pN__M#`/`%N` → `<arg-N>`, auto-gensym suffixes, regex Pattern → `.pattern` string). Files where read-string can't read everything (auto-resolve keyword without ns context, record literals) are skipped from the parity gate; files where the expander throws are tracked separately. Per-project divergence baselines encode current state and act as a ratchet — lowering one means a bug was fixed, the test fails on regressions that raise the count.

### Fixed

- **`expand-sq` collection branches recurse into the unquoted form.** The list / vector / map / set cases inside `meme.tools.clj.expander` returned `(:form item)` raw for `~`/`~@` items — meaning a nested `` ` `` AST node inside the unquoted form stayed as a record and later tripped `check-no-leftover-unquotes!`. The classic case is `\`(let [x 1] ~@(map (fn [y] \`(do ~y)) xs))` — the inner `\`(do ~y)` lives in normal-eval context (we exited the outer `\`` via `~@`) and must be expanded as its own scope. Fix: collection branches now call `expand-syntax-quotes` on the unquoted form. Cleared 14 of 14 expander-error files across the vendor cross-check (hiccup compiler, core.async ioc-macros, malli util/cljs, etc.).

- **`default-resolve-symbol` handles static-method and constructor symbols.** `Foo.` now resolves to `pkg.Foo.` (constructor — class lookup, dot preserved) and `Class/member` resolves to `pkg.Class/member` (static call — namespace component rewritten to fully-qualified class name). Previously both passed through unchanged, which diverged from Clojure's `SyntaxQuoteReader` and surfaced as parity failures across every macro that emitted `\`(StringBuilder.)` or `\`(java.lang.String/valueOf …)`. Cleared roughly 50 vendor-cross-check divergences (most of ring's 36 baseline plus residual cases in hiccup, core.async, specter).

### Changed

- **`register!` atomicity** — validation moved out of the `swap!` updater into a `compare-and-set!` retry loop. Previous shape threw from inside the updater; new shape validates against the current snapshot on each CAS attempt and commits only if the CAS wins. Concurrent conflicting registrations now consistently detect the conflict.

### Fixed

- **Duplicate-key detection across notations.** `{0xFF 1 255 2}` and `{\u0041 1 \A 2}` silently accepted two keys that resolve to the same value because `CljRaw` records don't `=` their unwrapped equivalents. The check now unwraps `CljRaw` before `frequencies`; same for sets.

- **`cli/run` double-slurp.** The file was read twice — once for execution and once in the error formatter — so a changing file or a transient I/O error on the second read could mangle the displayed source context. Source is now bound once and reused.

- **`with-load-tracking` counter imbalance.** If `swap! inc` threw, the surrounding `try/finally` still ran the `dec`, leaving the counter at N-1. Moved the `inc` outside the `try`.

- **Leftover unquote errors lost source location.** When expand-sq delegated `~x` to expand-syntax-quotes with `:inside-sq`, a further `~` was re-wrapped as a `CljUnquote` record with no metadata, so `check-no-leftover-unquotes!` reported the error with empty `:line`/`:col`. Source metadata is now threaded through.

- **`loader/uninstall!` dispatch-gap race.** A thread that had dispatched into `lang-load` via the var override but had not yet incremented `load-counter` was unprotected: another thread could acquire `install-lock`, observe counter=0, tear down the var overrides, and nil the captured originals — leaving the first thread to NPE on `@original-load`. Two complementary fixes: (a) `with-load-tracking` now performs the `swap! inc` under `install-lock`, making the tracked region open atomically w.r.t. uninstall's observation; (b) `uninstall!` no longer nils `original-load` / `original-load-file` / `extensions-fn`, so even a stale in-flight reference remains safe to deref (a subsequent `install!` re-captures them). Scar-tissue regression: `uninstall-blocks-on-install-lock-during-dispatch-gap` in `test/meme/loader_test.clj`.

- **`cli` bad-flag error matched by regex.** The `-main` catch used `(re-find #"(?i)coerce" msg)` to detect babashka.cli failures, which would miss `:require` and future failure modes. Replaced with a structured `(= :org.babashka/cli (:type (ex-data e)))` match. Scar-tissue regression: `bad-coerce-flag-is-clean-error` in `test/e2e/cli_test.clj`.

- **`columnar-pairs-doc` empty-input crash.** `src/mclj_lang/printer.cljc:166` called `(apply max key-widths)` with no guard. All 3 current callers happen to pre-empty-check, but the function itself was a trap for any future caller. Now falls back to 0 when `key-widths` is empty.

- **`registry/resolve-symbol` non-invocable result.** When a user EDN symbol resolved to a non-fn value (e.g. a config var or nil-bound var), the value flowed through `resolve-value` and either trapped at the `:else` branch with `"got nil"` or sneaked past as `ifn?` (maps/vectors are `ifn?`) and failed cryptically at call time. Now rejects at the symbol-resolution site with `"resolved to a non-invocable value of type X"`.

### Internal

- **`with-mclj-grammar` triplication lifted into `mclj-lang.grammar/with-grammar`.** The same opts-injection helper was copy-pasted verbatim in `mclj-lang.api`, `mclj-lang.run`, and `mclj-lang.repl`. Single shared definition now lives next to the grammar value it injects; all three call sites delegate to it.

- **`registry_test.clj` flake-prone `Thread/sleep 500`.** `concurrent-register-conflict-detection` waited for two raw `Thread`s by sleeping instead of joining, so worker completion was timing-dependent. Replaced with `future`s plus `(run! deref futures)` so the test waits exactly as long as needed and surfaces worker exceptions.

- **Vendor roundtrip failure messages — non-seq forms.** `test_util/form-name` returned `nil` for non-seq forms (bare symbols, literals, `ReaderConditional` records), so failures rendered as `"  - ?: <message>"`. `form-name` now falls back to a truncated `pr-str`, and the report formatter no longer needs the `(or name "?")` shim.

- **CLI `--out` validation deduplicated.** Extracted `validate-out-dir!` — both `transpile-meme` and `build` now share a single error path for blank `--out` values instead of two copy-pasted inline blocks. Test: `out-dir-validation` in `test/meme/cli_test.clj`.

- **BOM + shebang stripping consolidated in `meme.tools.clj.stages`.** New `strip-bom` and `strip-source-preamble` helpers; `strip-source-preamble` composes BOM-strip followed by shebang-strip (BOM comes first — a UTF-8 file may legally begin with a BOM before any shebang). `clj-run-fn` now calls `strip-source-preamble` instead of re-implementing BOM-strip inline, and the tooling convenience `stages/run` tolerates leading BOMs as well. Tests: `bom-stripping` and `strip-source-preamble-composition` in `test/mclj_lang/stages_test.cljc`.

- **`clear-user-langs!` moved out of production code.** Was a `^:no-doc` test-only helper in `src/meme/registry.clj`; production `src/` now carries no test-only API surface. Moved to `test/meme/test_registry.clj` (reaches into the private registry atom via `@#'meme.registry/registry`). Test fixtures and the dedicated `clear-user-langs-works` test in `registry_test.clj` updated to use `test-registry/clear-user-langs!`.

- **Tombstoned scar comments inlined as real assertions.** `test/meme/regression/reader_test.cljc` had three scar headers (auto-resolve keyword deferred, leading-zero octal rejection, leading-BOM acceptance) with bare `;; Covered by X` comments and no `deftest` body — meaning the named scar would silently lose coverage if the "covering" test in another file was renamed or moved. Added local asserts (`auto-resolve-keyword-deferred`, `leading-zero-octal-with-8-or-9-rejected`, `leading-bom-stripped-as-trivia`) so the regression net is locally guaranteed.

- **Dogfood roundtrip expanded.** `test/meme/dogfood_test.clj` now includes `meme.tools.parser`, `meme.tools.render`, `mclj-lang.grammar`, `mclj-lang.form_shape`, and `mclj-lang.formatter.canon` in both per-form roundtrip and clj-kondo semantic-equivalence — the largest source files were previously only exercised via vendor libs.

- **Direct unit test for `meme.tools.clj.lex`.** New `test/meme/tools/clj/lex_test.cljc`. The lex layer was previously tested only indirectly via grammar/parser; this adds targeted asserts for `consume-char-literal` (`\\uXXXX` malformed-then-consume-trailing semantics, `\\oNNN`, named chars, single-char literals), `consume-symbol` (invisible-char rejection: ZWSP, BOM, variation selectors), `consume-keyword` (`::auto-resolve`, namespaced), `consume-number` (hex, ratio, exponent), `consume-string` (escaped quote, unterminated-to-EOF), and the `whitespace-char?` / `symbol-start?` predicates.

- **CLAUDE.md carve-out wording.** The "kinds + infrastructure" rule said the `meme.tools.clj.{run,repl} → meme.loader` carve-out was "one `install!` call each"; in fact `meme.tools.clj.run` also calls `loader/warn-deprecated-extension!` for soft-deprecated `.meme*` extensions. Wording updated to acknowledge both call sites.

### Docs

- **CHANGELOG `[5.0.0]` rename banner.** The 5.0.0 migration entries were written before the post-5.0.0 rename pack landed, so symbol names (`meme->forms`, `MemeReaderConditional`, `meme-lang.stages`, etc.) and the `target/meme` build directory are pre-rename. Added a banner at the top of the section listing the rename mapping so a reader applying 5.0.0 instructions today knows what to translate. Removed the redundant inline `meme-lang.stages → meme.tools.clj.stages` note (now covered by the banner).

- **Docstring drift after the rename.** `mclj-lang.api/clj->mclj` docstring said "Convert Clojure source to meme"; `meme.registry` docstring's `:to-mclj` slot still called the return value "meme-text"; `doc/api.md` listed `from-meme` / `from-clj` aliases without context. Updated in place.

- **PRD.md typo.** "the largest removal was the wlj-lang proof-of-concept" → "implojure-lang" (the actual sibling that was retired).

- **`meme.tools.parser` namespace docstring** rewritten to enumerate the parselet-author API — engine primitives (`peek-char`, `advance!`, `eof?`, `cursor`, etc.), token helpers, sub-parsing (`parse-expr`, `parse-until`, `expect-close!`), CST construction, and the full nud/led factory set. The 4.0.0 CHANGELOG entry described the engine as "exposing only `trivia-pending?` to language grammars", but grammars routinely call the broader surface; the docstring is now aligned with actual usage.

- **Stale references swept from prose docs.** `doc/PRD.md` title `"meme clojure"` → `"mclj"`; `doc/design-decisions.md` `read-meme-string` → `mclj->forms` (function had been renamed; the old name no longer existed anywhere in the codebase); `doc/api.md` now documents `mclj-lang.api/format-mclj` (source-to-source convenience used by `meme format` CLI) and `meme.tools.clj.stages/strip-bom`, both previously public but undocumented.

- **`stages/run` doc updated for the grammar-required contract.** The `doc/api.md` example called `(stages/run "+(1 2)")`, which throws `:mclj/pipeline-error` since `step-parse` requires `:grammar` in opts (no implicit default). Removed the broken example and the no-arg signature; the remaining 2-arg signature now states the `:grammar` requirement inline.

## [5.0.0] — 2026-04-19

Reader-conditional handling is now a pipeline stage instead of a reader flag. `meme->forms` and `meme->clj` are lossless by default for `.cljc` sources.

> **Names below are pre-rename.** Post-5.0.0 the project renamed
> `meme-lang.*` → `mclj-lang.*`,
> `meme->forms` / `meme->clj` / `clj->meme` / `format-meme` →
> `mclj->forms` / `mclj->clj` / `clj->mclj` / `format-mclj`,
> `MemeReaderConditional` / `MemeUnquote` → `CljReaderConditional` / `CljUnquote`,
> `meme-lang.stages` → `meme.tools.clj.stages`,
> `:meme/*` metadata → `:mclj/*`,
> `.meme` extension → `.mclj`,
> and the build staging directory `target/meme` → `target/mclj`.
> Apply these renames when following the migration steps below; see the
> Unreleased section for the full rename pack.

### Breaking Changes

- **The `:read-cond` option is removed from `meme->forms`, `meme->clj`, and the `step-read` pipeline stage.** Reader conditionals (`#?`, `#?@`) are always returned as `MemeReaderConditional` records. Passing `:read-cond` throws `:meme/deprecated-opt` with migration text.

  **Migration:**
  - If you used `{:read-cond :preserve}`: remove it. Records are the default now.
  - If you relied on the old `:eval` behavior (platform materialization at read time): compose `meme-lang.stages/step-evaluate-reader-conditionals` after `step-read`, or use `run-string`/`run-file`/REPL — all of which do so automatically.

- **`meme->clj` is now lossless by default.** Previously it evaluated `#?` for the current platform, silently dropping off-platform branches. Now both branches are preserved in the emitted Clojure text. Use `run-string` for eval-time behavior.

- **`meme compile` renamed to `meme transpile`.** The command is a same-level surface-syntax transform (`.meme` → `.clj`), not a lowering to bytecode — `transpile` is the accurate term. `compile` still works as an alias; no script changes required, but new docs and help text use `transpile`.

- **Default `--out` is now `target/meme`** (was `target/classes`). Avoids collision with `tools.build`/AOT output in the same directory tree. Projects that relied on the old default should either pass `--out target/classes` explicitly or update `:paths` in `deps.edn` to point at `target/meme`.

- **Scanner / reader strictness.** Several malformed inputs that previously read silently now error at read time, matching Clojure's reader:
  - `//`, `//a`, `/foo` — rejected. `/`, `ns//`, `foo/bar/baz` stay valid.
  - `\uNNNN` followed by any alphanumeric (e.g. `\u00410`, `\u0041G`) — rejected.
  - Bare `` `~~x `` (two unquotes with only one enclosing ``` ` ```) — errors at expander time. Balanced `` ``~~x `` still expands to `x`.
  - Variation selectors U+FE00-U+FE0F inside symbols — rejected (stricter than Clojure; blocks look-alike-symbol attacks).
  - U+2028 / U+2029 now count as line terminators in error-position reporting.

### Added

- **`meme-lang.stages/step-evaluate-reader-conditionals`** — pipeline stage that evaluates `#?`/`#?@` records in `:forms` for a target platform. Supports `:platform` opt (default: compile-time platform). Handles `#?` (branch pick), `#?@` (splice into parent collection), `:default` fallback, and validates even-count branch lists. Recurses into `` ` `` / `~` / `~@` interiors, matching native Clojure's reader-time evaluation order. Does not fire for tooling paths — `meme->forms`, `meme->clj`, `format-meme`, and `to-clj` skip the stage and preserve records.

- **`:default` fallback in reader conditionals.** Previously only matched named platform keys; `#?(:cljs 1 :default 99)` on JVM returned nothing. Now returns `99`.

- **`meme from-meme` / `meme from-clj` CLI aliases** for users who prefer to name the source rather than the destination. `from-meme` ≡ `to-clj`; `from-clj` ≡ `to-meme`.

- **`meme build` CLI command** — transpile + AOT compile `.meme` sources to JVM bytecode in a single step. Staging lives under `target/meme` (implementation detail); bytecode goes to `--out target/classes`. Meme stops at bytecode; JAR packaging stays in the user's tools.build layer. The `Building to JVM bytecode` section in `doc/language-reference.md` documents alternate `build.clj`-integrated recipes for projects that want tighter control.

### Changed

- **`run-string`, `run-file`, REPL** — `step-evaluate-reader-conditionals` inserted automatically between `step-read` and `step-expand-syntax-quotes`.
- **`meme->forms`, `format-meme`, `to-clj`** — internal `:read-cond :preserve` wiring removed. Records are the default; no opt required.
- **Pipeline contract map** (`meme-lang.stages/stage-contracts`) gains an entry for the new stage; now has four entries instead of three.

### Fixed

- **`meme->clj` silently dropped off-platform branches of `#?` on `.cljc` sources.** The asymmetry between library `meme->clj` (evaluated) and CLI `to-clj` (preserved) is eliminated — both now preserve faithfully. Scar-tissue regression: `meme->clj-reader-conditional-lossless` in `test/meme/regression/reader_test.cljc`.

- **`clj->forms` depth guard off-by-one.** The sibling fix in `cst_reader.cljc` (4.0.0) tightened `>` to `>=`, but `meme-lang.api/clj->forms/check-depth` retained `>`, so Clojure source at exactly `max-parse-depth` levels parsed successfully while meme source at the same depth was rejected. Both entry points now reject at exactly `max-parse-depth`, matching the 4.0.0 CHANGELOG intent. Scar-tissue regression: `clj-forms-depth-boundary-matches-meme-forms` in `test/meme/regression/reader_test.cljc`.

- **`meme.loader/uninstall!` thread-safety.** The in-flight-load guard used a `^:dynamic *loading*` thread-local binding, which only blocked same-thread uninstall. A second thread could call `uninstall!` and restore `clojure.core/load` while the first thread was still inside a `lang-load`, leaving the in-flight load executing against a torn-down override. Replaced with a shared `load-counter` atom and an `install-lock` monitor. `install!`/`uninstall!` now serialize safely across threads; `uninstall!` throws `{:reason :active-load, :in-flight N}` if any thread is still inside a load. Scar-tissue regressions: `uninstall-during-load-rejected`, `uninstall-from-other-thread-blocked-while-loading`, and `concurrent-installs-are-idempotent` in `test/meme/loader_test.clj`.

- **Bare `:/` now reads as `(keyword "/")`** instead of erroring with "Invalid token". Matches Clojure.

- **Bare `~~x` leaked a `MemeUnquote` record to eval** instead of erroring. Added a post-expansion sweep that rejects leftover unquote records. Balanced `` ``~~x `` still works.

- **`meme compile` Windows path bug** — `getCanonicalPath` returns `\`-separated paths on Windows, but the root-prefix match hardcoded `/`. Files collapsed into the flat output directory with their relative paths lost. Uses `java.io.File/separator` now.

- **`meme compile --out ""`** now fails fast with a clear error instead of silently writing to the filesystem root.

### Internal

- **`cst-reader.cljc`** — `:reader-cond` case simplified to a single always-preserve path. `:eval` branch, `::no-match` sentinel for reader conditionals, and `:meme/splice` metadata machinery are gone. `splice-and-filter` now only filters the shebang sentinel.
- **`meme-lang.run`** and **`meme-lang.repl`** compose the new stage in their run-fn pipelines.

### Known Limitation

- **`#?@` inside a map literal fails at read time** (odd-count children). Matches Clojure's own `:read-cond :preserve` behavior. Use a collection other than map, or construct the map at runtime.

## [4.0.0] — 2026-04-19

A reorganization release. No breaking changes to `.meme` syntax or runtime behavior; most of the work is in documentation, API hygiene, and internal boundaries.

### Architecture

- **Registry inversion** — `meme.registry` imports no concrete langs. Each lang's api namespace calls `register-builtin!` at its own load time, and the CLI triggers registration by explicitly requiring each lang it ships with. Dissolves the old registry↔lang cycle and four `requiring-resolve` workarounds.
- **Shared infrastructure reclassification** — `meme.registry` and `meme.loader` are now documented as shared infrastructure peer to `meme.tools.*`, not as a strict "above" tier over `meme-lang.*`. `meme-lang.api` requiring `meme.registry` (for self-registration) and `meme-lang.run` requiring `meme.loader` (for auto-install) are intentional; the CLAUDE.md tier table has been updated to match.
- **Pipeline contract validation** — stages declare required ctx keys via `stage-contracts`; `check-contract!` runs at entry and throws `:meme/pipeline-error` with the missing key(s) when pipelines are miscomposed, instead of deep NPEs.
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
- **Metadata namespace hygiene**: all internal metadata keys moved from bare `:meme/*` to `:meme-lang/*`, with descriptive names. `:meme/ws` → `:meme-lang/leading-trivia`, `:meme/sugar` → `:meme-lang/sugar`, `:meme/order` → `:meme-lang/insertion-order`, `:meme/ns` → `:meme-lang/namespace-prefix`, `:meme/meta-chain` → `:meme-lang/meta-chain`, `:meme/bare-percent` → `:meme-lang/bare-percent`, `:meme/splice` → `:meme-lang/splice`. This separated meme-lang metadata from the generic `meme.tools` namespace, preventing collision with both user metadata and future languages built on `meme.tools.*`. *(The `:meme-lang/*` keys were later renamed back to `:meme/*` in the post-5.0.0 work once the keys were recognised as toolkit-emitted rather than lang-emitted — see Unreleased.)*
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
- **All bare `:meme/*` short-form internal metadata keys**: replaced by `:meme-lang/*` namespaced equivalents with descriptive names. The bare `:meme/` namespace was freed for generic tooling at this release. *(See Unreleased — the keys were later re-homed at `:meme/*` once they were recognised as toolkit-emitted vocabulary.)*

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
