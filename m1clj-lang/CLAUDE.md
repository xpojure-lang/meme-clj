# CLAUDE.md — m1clj-lang

Sovereign guest of the meme-clj toolkit. The first language built on it.

## What m1clj is

**M-expressions for Clojure**, in the spirit of McCarthy (1960). One rule:

`f(x y)` → `(f x y)` — the head of a list is written outside the parens, adjacent to `(`. Spacing is significant: `f(x)` is a call, `f ()` is two forms.

Everything else (data literals, reader syntax, destructuring, commas-as-whitespace, `#` dispatch, syntax-quote) is unchanged from Clojure. File extension: `.m1clj`. Registry key: `:m1clj`.

## Files

```
m1clj-lang/src/m1clj_lang/
├── api.cljc            Public API + lang-map composition. Self-registers as :m1clj.
├── grammar.cljc        Pratt grammar spec — char dispatch, scanlets, parselets.
├── parselets.cljc      m1clj-specific compound parselets: call adjacency + M-expr call rule.
├── lexlets.cljc        Thin shim → meme.tools.clj.lex (m1clj inherits Clojure lexical conventions).
├── form-shape.cljc     Special-form decomposition into named slots (:name, :params, :bindings, …).
├── printer.cljc        Wadler-Lindig Doc tree builder. :m1clj and :clj output modes.
├── formatter/
│   ├── flat.cljc       Single-line format (printer + render at infinite width).
│   └── canon.cljc      Canonical multi-line format. Used by `meme format`.
├── run.clj             Thin shim → meme.tools.clj.run with m1clj's grammar injected.
└── repl.clj            Thin shim → meme.tools.clj.repl with m1clj's banner injected.
```

The shared backbone (`meme.tools.*`, `meme.tools.clj.*`) is the toolkit; everything in this directory is what makes m1clj specifically itself.

## Public API surface

`m1clj-lang.api` exports:

- `m1clj->forms`, `m1clj->ast`, `m1clj->clj` — read m1clj source
- `forms->m1clj`, `format-m1clj-forms` — emit m1clj source from forms
- `clj->m1clj`, `clj->forms`, `clj->ast` — read native Clojure source through the toolkit
- `forms->clj` — emit native Clojure source

The `m1clj→clj` and `clj→m1clj` paths are **lossless** (route through the AST tier) — comments, namespaced-map prefixes, set source order, multi-tier metadata, and bare `%` notation all survive. `clj->forms` and `clj->m1clj` work on JVM, Babashka, and ClojureScript (no `read-string` dependency).

## Divergence from sibling guests

- **vs `m2clj-lang`** — m2clj is m1clj plus one extra rule: a paren without head adjacency (`(x y z)`) is a list literal that lowers to `(quote (x y z))` instead of being a parse error. m1clj rejects bare parens. The two lang trees are sovereign — even where source files look temporally identical (most of `form-shape.cljc`, `formatter/*`, `printer.cljc`'s non-quote branches), they are owned independently and may diverge. **Do not lift duplicated code into `meme.tools.clj.*`** unless it's genuinely toolkit-level.
- **vs `clj-lang`** — clj-lang is the native S-expression Clojure surface (no M-expression rule). It's a thin registration shim that reuses `meme.tools.clj.parser.*` for parsing and m1clj's printer in `:clj` mode for emitting.

## Tests

Per-lang tests live under `m1clj-lang/test/m1clj_lang/`:

- `reader/call_syntax_test.cljc` — head type × spacing × arity matrix
- `reader/calls_test.cljc` — every Clojure form rendered as a call (def, defn, fn, let, …)
- `reader/interop_test.cljc` — Java interop (`.method`, `Class/static`, `.-field`, constructors)
- `reader/data_literals_test.cljc` — vectors, maps, sets, keywords, numbers
- `reader/dispatch_test.cljc` — `@`, `^`, `'`, `#'`, `#_`, `#()`, regex, char, tagged literals, reader conditionals, namespaced maps
- `reader/errors_test.cljc` — error cases, rejected forms, error messages with locations
- `stages_test.cljc` — pipeline stages (parse → read, syntax-quote expansion)
- `form_shape_test.cljc` — per-form decomposer output, structural fallback, registry extension
- `printer_test.cljc` — printer-level seams (`:slot-renderers`, default renderers, unknown-slot fallback)
- `formatter/flat_test.cljc` — single-line `:m1clj`/`:clj` output, reader sugar
- `formatter/canon_test.cljc` — width-aware multi-line layout, comments
- `api_test.cljc` — public API surface
- `repl_test.clj`, `run_test.clj` — REPL infrastructure and file runner

Cross-lang regression and infrastructure tests live under `test/meme/`; see the root `CLAUDE.md` "Test file placement" table.

## Syntax quick reference (for writing `.m1clj` code)

- `symbol(args)` is a call — head adjacent to `(`
- `f (x)` is NOT a call — spacing is significant
- Vectors can be heads: `[x](body)` → `([x] body)` (multi-arity clauses)
- Everything uses call syntax: `def(x 42)`, `let([x 1] body)`, `if(cond then else)`
- `defn(name [args] body)` — single arity
- `defn(name [args](body) [args](body))` — multi-arity (vector-as-head)
- `fn([x] expr)` — anonymous function
- `try(body catch(Exception e handler))` — error handling
- `when(cond body)`, `cond(pairs...)`, `case(expr pairs...)` — control flow
- `for([x xs] body)`, `doseq([x items] body)` — comprehensions
- `ns(my.ns :require([...]))` — namespace declaration
- `defprotocol(Name method-sigs...)`, `defrecord(Name [fields])` — protocols and records
- `defmulti(name dispatch-fn)`, `defmethod(name dispatch-val [args] body)` — multimethods
- `::keyword` — auto-resolve keywords resolved natively
- Threading macros (`->`, `->>`) are just calls
- `()` is the empty list
- `'x` quotes the next form; `'f(x)` → `(quote (f x))` — m1clj syntax inside, no S-expression escape
- `` `if(~test ~body) `` — syntax-quote uses m1clj syntax inside
- `[]` is always data; use `list(1 2 3)` for list literals
- No opaque regions — everything parsed natively

See `doc/language-reference.md` for the full spec.
