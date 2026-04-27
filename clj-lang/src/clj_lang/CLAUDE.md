# CLAUDE.md — clj-lang

Sovereign guest of the meme-clj toolkit. The native S-expression Clojure surface, registered as `:clj`.

## What clj-lang is

A thin **registration shim** that proves the toolkit is genuinely language-agnostic. No new syntax — the surface is just Clojure as Clojure has always been written. What clj-lang adds is a registry entry that wires existing toolkit pieces together so the CLI's `format`, `to-clj`, and `to-m1clj` commands work on `.clj` and `.cljc` files.

File extensions: `.clj`, `.cljc`. Registry key: `:clj`.

## What it reuses

clj-lang carries no parser, no printer, no formatter of its own. Every layer is borrowed:

- **Parse** — `meme.tools.clj.parser.api/clj->ast`
- **AST** — `meme.tools.clj.ast.{nodes,build,lower}`
- **Print** — `m1clj-lang.formatter.canon/format-forms` with `:mode :clj`
- **`to-m1clj`** — delegates to `m1clj-lang.api/clj->m1clj` (lossless via AST)
- **`form-shape`** — shares `m1clj-lang.form-shape/registry` (both langs carry Clojure semantics; only the surface differs)

The `:run` slot is intentionally absent — `meme.cli` already eval-loads `.clj` files via Clojure's standard mechanisms. clj-lang only handles surface conversions and formatting.

## Files

```
src/clj_lang/
└── api.cljc            Lang-map composition + self-registration as :clj.
```

That's the entire lang. Roughly 100 lines.

## Why it exists

The first guest (m1clj) was a syntactic experiment. Adding clj-lang as a sibling proved the toolkit's seams are real — the parser, AST, stages, and printer don't presume M-expression syntax. Anything Clojure-flavored can sit on the same backbone with a small registration shim.

The vendor cross-check parity gate (see `test/meme/vendor_cross_check_test.clj`) compares clj-lang's parse output to `clojure.core/read-string` on real-world Clojure libraries. All seven vendor projects sit at parity baseline 0 — full parity, any new divergence is a regression.

## Tests

`test/clj_lang/api_test.cljc` — surface tests for the lang's public API.

The bulk of clj-lang's correctness is covered by tests against the layers it reuses: `meme/tools/clj/parser/*`, `meme/tools/clj/ast/*`, the m1clj formatter in `:clj` mode, and the vendor cross-check suite.
