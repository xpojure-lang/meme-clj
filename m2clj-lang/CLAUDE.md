# CLAUDE.md — m2clj-lang

Sovereign guest of the meme-clj toolkit. Strict superset of m1clj's surface.

## What m2clj is

m1clj plus **one extra rule**:

A paren without head adjacency — `(x y z)` — is a list literal. It lowers to `(quote (x y z))`. Calls still require head adjacency (`f(x y z)` → `(f x y z)`), so call-vs-data remains structural at the reader layer — visible without resolving symbols or knowing macro context.

The empty paren `()` stays the empty list (no quote applied — there's nothing to quote).

File extension: `.m2clj`. Registry key: `:m2clj`.

## Why the rule

Bare parens are the natural visual home for list literals when calls have moved outside the parens. m1clj treats them as parse errors, which is conservative; m2clj lifts them into quoted lists. Both rules respect the call-adjacency invariant.

## Surgical diff from m1clj

The two lang trees are sovereign — most files are temporally similar to their m1clj counterparts. **Do not lift duplicated code into shared modules.** The genuine divergence is concentrated in three places:

1. **`grammar.cljc`** — the `(` parselet routes to `m2clj-lang.parselets/bare-paren-nud` instead of m1clj's reject-on-bare-paren.
2. **`parselets.cljc`** — adds `bare-paren-nud`. Empty body → `:list` CST. Non-empty body → `:bare-list` CST. The shared call-adjacency parselet is unchanged.
3. **`printer.cljc`** — three branches added under `(= :m2clj mode)`:
   - `CljQuote` with `:notation :bare` and a non-empty `CljList` form → render as bare paren.
   - `CljList` containing a `(quote …)` AST call → canonicalize to bare paren in `:m2clj` mode.
   - The plain-forms `to-doc-form` seq path → same canonicalization for non-AST paths.

Everything else (`form-shape.cljc`, `formatter/{flat,canon}.cljc`, the rest of `printer.cljc`, `lexlets.cljc`, `run.clj`, `repl.clj`) mirrors m1clj's shape today. That mirroring is intentional — sovereignty over deduplication.

## AST integration

The bare-paren rule is encoded across two layers:

- **CST** — `:bare-list` is a distinct node type produced by the m2clj parser. The form-layer `cst-reader` lowers it to `(quote (children))`.
- **AST** — `meme.tools.clj.ast.build` lifts `:bare-list` to `CljQuote{form: CljList, notation: :bare}`. The `:notation` field on `CljQuote` (added with m2clj) preserves the user's source choice — `:tick` for `'x` sugar, `:bare` for m2clj's bare paren, `:call` for an explicit `(quote x)`. The printer dispatches on `:notation` to reconstruct the original surface, preserving syntactic transparency.

## Files

```
m2clj-lang/src/m2clj_lang/
├── api.cljc            Public API + lang-map composition. Self-registers as :m2clj.
├── grammar.cljc        Pratt grammar — routes `(` to bare-paren-nud.
├── parselets.cljc      bare-paren-nud + the inherited call-adjacency rule.
├── lexlets.cljc        Thin shim → meme.tools.clj.lex.
├── form-shape.cljc     Special-form decomposition (mirrors m1clj today; sovereign).
├── printer.cljc        Doc tree builder. Adds bare-paren rendering branches.
├── formatter/
│   ├── flat.cljc       Single-line format.
│   └── canon.cljc      Canonical multi-line format.
├── run.clj             Thin shim → meme.tools.clj.run with m2clj's grammar.
└── repl.clj            Thin shim → meme.tools.clj.repl.
```

`meme.cli` requires `m2clj-lang.api` at startup, which triggers self-registration via `register-builtin!`.

The `register-string-handler! :run` slot is registered first-wins, so when bundled alongside m1clj (which loads first) m2clj's call is a no-op. The call is kept so the lang is self-sufficient when loaded standalone.

## Tests

Per-lang tests live under `m2clj-lang/test/m2clj_lang/`:

- `reader/bare_paren_test.cljc` — the distinguishing rule. Covers empty `()`, non-empty `(x y z)`, nesting, mixed call+bare-paren forms.
- `printer_test.cljc` — bare-paren rendering of `CljQuote{:notation :bare}` and `(quote …)` canonicalization.
- `api_test.cljc` — public API surface.

The shared AST/parser/lexer machinery is exercised by `test/meme/tools/clj/*` regardless of guest.

## Public API surface

`m2clj-lang.api` mirrors `m1clj-lang.api`:

- `m2clj->forms`, `m2clj->ast`, `m2clj->clj`
- `forms->m2clj`, `format-m2clj-forms`
- `clj->m2clj`, `clj->forms`, `clj->ast`
- `forms->clj`

`clj→m2clj` routes through the AST tier — quoted lists `'(x y z)` become m2clj's bare-paren `(x y z)`; quoted symbols/keywords/numbers stay as `'x`; quoted empty list stays as `'()`.
