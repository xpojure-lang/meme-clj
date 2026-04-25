# meme API Reference

Namespaces are organized in four tiers: `meme.tools.{parser,lexer,render}` (generic tools), `meme.tools.clj.*` (Clojure-surface commons shared across Clojure-flavored frontends), `mclj-lang.*` (meme language), and `meme.*` (shared runtime infra — `meme.registry`, `meme.loader` — plus the `meme.cli` app).

## mclj-lang.api

The public API for reading and printing meme syntax, organized in three tracks:

```
         text-to-form          form-to-text
meme str ──→ mclj->forms ──→ forms ──→ forms->mclj ──→ meme str
clj str  ──→ clj->forms  ──→ forms ──→ forms->clj  ──→ clj str

         text-to-text (compositions)
meme str ──→ mclj->clj ──→ clj str
clj str  ──→ clj->mclj ──→ meme str
```

### Text-to-form track

#### mclj->forms

```clojure
(mclj-lang.api/mclj->forms s)
(mclj-lang.api/mclj->forms s opts)
```

Read a meme source string. Returns a vector of Clojure forms. All platforms.

Options:
- `:resolve-keyword` — function that resolves auto-resolve keyword strings (`"::foo"`) to keywords at read time. When absent on JVM/Babashka, `::` keywords are deferred to eval time via `(read-string "::foo")`. Required on CLJS (errors without it, since `cljs.reader` cannot resolve `::` in the correct namespace).
- `:resolve-symbol` — function that resolves symbols during syntax-quote expansion (e.g., `foo` → `my.ns/foo`). On JVM/Babashka, `run-string`/`run-file`/`start` inject a default that matches Clojure's `SyntaxQuoteReader` (inlined in `mclj-lang.run`). When calling `mclj->forms` directly, symbols in syntax-quote are left unqualified unless this option is provided. On CLJS, no default is available.

Reader conditionals (`#?`, `#?@`) are always returned as `CljReaderConditional` records. To materialize the platform branch, compose `meme.tools.clj.stages/step-evaluate-reader-conditionals` after reading, or use `run-string`/`run-file`/`start` (which do so automatically). The `:read-cond` option is no longer accepted — passing it throws `:mclj/deprecated-opt`.

```clojure
(mclj->forms "+(1 2 3)")
;=> [(+ 1 2 3)]

(mclj->forms "def(x 42)\nprintln(x)")
;=> [(def x 42) (println x)]
```

**Note:** `mclj->forms` may return internal record types for forms that preserve source notation: `CljRaw` (for hex numbers, unicode escapes, etc.) wraps a `:value` and `:raw` text; `CljAutoKeyword` (for `::` keywords) wraps a `:raw` string. These are unwrapped to plain values by `step-expand-syntax-quotes` (which `run-string`/`run-file` call before eval). If you need plain Clojure values from `mclj->forms`, compose with `meme.tools.clj.stages/step-expand-syntax-quotes`.

#### forms->mclj

```clojure
(mclj-lang.api/forms->mclj forms)
```

Print a sequence of Clojure forms as meme text. All platforms.

**Note:** Reference types (atoms, refs, agents) print as `#object[...]` which cannot be round-tripped. This matches Clojure's own behavior.

```clojure
(forms->mclj ['(+ 1 2 3)])
;=> "+(1 2 3)"
```

### Form-to-text track

#### forms->clj

```clojure
(mclj-lang.api/forms->clj forms)
```

Print Clojure forms as a Clojure source string. All platforms.

```clojure
(forms->clj ['(def x 42) '(println x)])
;=> "(def x 42)\n\n(println x)"
```

#### clj->forms

```clojure
(mclj-lang.api/clj->forms clj-src)
```

Read a Clojure source string, return a vector of forms. JVM/Babashka only.

```clojure
(clj->forms "(defn f [x] (+ x 1))")
;=> [(defn f [x] (+ x 1))]
```

### Pretty-printing

#### format-mclj-forms

```clojure
(mclj-lang.api/format-mclj-forms forms)
(mclj-lang.api/format-mclj-forms forms opts)
```

Format Clojure forms as canonical (width-aware, multi-line) meme text. Uses indented parenthesized form for calls that exceed the line width. Preserves comments from `:mclj/leading-trivia` metadata (attached by the pipeline's scan stage). All platforms.

Options:
- `:width` — target line width (default: 80)

```clojure
(format-mclj-forms ['(defn greet [name] (println (str "Hello " name)))])
;=> "defn(greet [name]\n  println(str(\"Hello \" name)))"
```

#### format-mclj

```clojure
(mclj-lang.api/format-mclj meme-src)
(mclj-lang.api/format-mclj meme-src opts)
```

Source-to-source convenience: parses a meme source string and re-emits it via `format-mclj-forms` at the requested width. Equivalent to `(format-mclj-forms (mclj->forms meme-src) opts)`. Used by the `meme format` CLI command. All platforms.

Options:
- `:width` — target line width (default: 80)

### Text-to-text track

#### mclj->clj

```clojure
(mclj-lang.api/mclj->clj meme-src)
(mclj-lang.api/mclj->clj meme-src opts)
```

Convert meme source string to Clojure source string (lossless by default). All platforms. Equivalent to `(forms->clj (mclj->forms meme-src opts))`.

Reader conditionals are preserved as `#?(...)` in the output — faithful for `.cljc` conversion. For the eval-time value, use `run-string` instead.

Options: same as `mclj->forms` (`:resolve-keyword`, `:resolve-symbol`).

```clojure
(mclj->clj "println(\"hello\")")
;=> "(println \"hello\")"
```

#### clj->mclj

```clojure
(mclj-lang.api/clj->mclj clj-src)
```

Convert a Clojure source string to meme source string. JVM/Babashka only. Equivalent to `(forms->mclj (clj->forms clj-src))`.

**Known limitation:** Clojure's reader expands reader sugar before meme sees the forms. `'(f x)` becomes `(quote (f x))` → `quote(f(x))` instead of `'f(x)`. Similarly, `@atom` → `clojure.core/deref(atom)`, and `#(+ % 1)` → `fn*([p1__N#] +(p1__N# 1))`. The `mclj->clj->mclj` roundtrip preserves semantics but not notation for these forms.

```clojure
(clj->mclj "(defn f [x] (+ x 1))")
;=> "defn(f [x] +(x 1))"
```

### lang-map

```clojure
mclj-lang.api/lang-map
```

The self-description map the CLI and registry consume. Keys: `:extension`, `:extensions` (additional variants), `:format`, `:to-clj`, `:form-shape`, and — JVM-only — `:run`, `:repl`. `:to-mclj` (CLI alias `from-clj`) is optional — only present on langs that own a syntax printer. Meme registers itself under `:mclj` at ns-load via `meme.registry/register-builtin!`; other hosts can inspect or reference `lang-map` directly.

## mclj-lang.form-shape

Semantic decomposition of Clojure special forms into named slots. See [doc/form-shape.md](form-shape.md) for the slot vocabulary, extension patterns, and consumer sketches. The three slot layers — notation (printer), form-shape (this namespace), style (formatter) — are independently composable.

### registry

```clojure
mclj-lang.form-shape/registry
```

The built-in registry of decomposers, a plain map `{head-symbol → (fn [args-vec] → slots)}`. Extend with `assoc`, compose with `merge`, or wrap with `with-structural-fallback`. Consumed by `lang-map`'s `:form-shape` key and threaded through formatters to the printer.

### decompose

```clojure
(mclj-lang.form-shape/decompose registry head args)
```

Look up `head` in `registry` and apply its decomposer to `args`. Returns a vector of `[slot-name value]` pairs in source order, or `nil` if no decomposer matches (and no structural fallback is installed). A nil `registry` yields nil for every head — every form renders as a plain call.

When the registry carries a `::fallback-fn` in its metadata (see `with-structural-fallback`), a missed lookup consults the fallback; this lets user macros inherit layout from structural shape alone.

### with-structural-fallback

```clojure
(mclj-lang.form-shape/with-structural-fallback registry)
```

Return a registry (same entries) that uses structural inference when no entry matches a given head. Two patterns are recognized:

- `(HEAD name [params] body*)` — inferred as defn-like
- `(HEAD [bindings] body*)` — inferred as let-like

A user macro matching either pattern inherits canonical layout without being registered. Other shapes return nil (plain-call rendering).

Opt-in: the built-in `registry` has no fallback by default, so existing code doesn't silently change layout for user macros.


## mclj-lang.printer

Low-level Doc tree builder. Most callers should use `formatter.flat` or `formatter.canon` instead.

### to-doc

```clojure
(mclj-lang.printer/to-doc form)
(mclj-lang.printer/to-doc form mode)
(mclj-lang.printer/to-doc form mode style)
(mclj-lang.printer/to-doc form mode style form-shape)
```

Convert a Clojure form to a Wadler-Lindig Doc tree. The Doc tree is passed to `meme.tools.render/layout` for final string output.

- `mode` — `:mclj` (call notation, default) or `:clj` (standard Clojure with reader sugar).
- `style` — layout policy map (nil = pass-through). Keyed by semantic slot names from `mclj-lang.form-shape` (`:name`, `:params`, `:bindings`, etc.), not by form names. See `mclj-lang.formatter.canon/style` for the canonical policy.
- `form-shape` — registry map `{head-symbol → decomposer-fn}`. When nil, no special-form decomposition runs and every call renders as a plain body sequence. Callers normally pass `mclj-lang.form-shape/registry`; the lang's `lang-map` exposes its registry under `:form-shape`.

## mclj-lang.formatter.flat

Flat (single-line) formatter. Composes printer + render at infinite width.

### format-form

```clojure
(mclj-lang.formatter.flat/format-form form)
```

Format a single Clojure form as flat meme text (single-line).

```clojure
(format-form '(+ 1 2))
;=> "+(1 2)"

(format-form '(:balance account))
;=> ":balance(account)"

(format-form '(def x 42))
;=> "def(x 42)"
```

### format-forms

```clojure
(mclj-lang.formatter.flat/format-forms forms)
```

Format a sequence of Clojure forms as flat meme text, separated by blank lines.

### format-clj

```clojure
(mclj-lang.formatter.flat/format-clj forms)
```

Format Clojure forms as Clojure text with reader sugar (`'quote`, `@deref`, `#'var`).


## mclj-lang.formatter.canon

Canonical (width-aware) formatter. Composes printer + render at target width. Used by `meme format` CLI.

### format-form

```clojure
(mclj-lang.formatter.canon/format-form form)
(mclj-lang.formatter.canon/format-form form opts)
```

Format a single Clojure form as canonical meme text. Width-aware — uses indented multi-line layout for forms that exceed the target width. Preserves comments from `:mclj/leading-trivia` metadata.

Options:
- `:width` — target line width (default: 80)
- `:form-shape` — form-shape registry (default: `mclj-lang.form-shape/registry`). Override to add user-defined defining macros or to disable decomposition entirely (pass `nil` for plain-call layout). Wrap with `mclj-lang.form-shape/with-structural-fallback` to enable structural inference for user macros that mirror `defn` (name + params vector) or `let` (leading bindings vector) shapes.
- `:style` — slot-keyed style map (default: `mclj-lang.formatter.canon/style`). Override for project-level tweaks — e.g. a narrower `:head-line-slots` set, or a custom `:slot-renderers` map that replaces the printer defaults for `:bindings` or `:clause`. See [form-shape.md](form-shape.md) for the style map schema.

### format-forms

```clojure
(mclj-lang.formatter.canon/format-forms forms)
(mclj-lang.formatter.canon/format-forms forms opts)
```

Format a sequence of Clojure forms as canonical meme text, separated by blank lines. Preserves comments from `:mclj/leading-trivia` metadata, including trailing comments after the last form.


## mclj-lang.repl

### input-state

```clojure
(mclj-lang.repl/input-state s)
(mclj-lang.repl/input-state s opts)
```

Returns the parse state of a meme input string: `:complete` (parsed successfully), `:incomplete` (unclosed delimiter — keep reading), or `:invalid` (malformed, non-recoverable error). Used internally by the REPL for multi-line input handling; also useful for editor integration.

The optional `opts` map is forwarded to `stages/run` — useful for callers that need `::` keywords or custom parsers to be resolved during input validation.

```clojure
(input-state "+(1 2)")      ;=> :complete
(input-state "f(")          ;=> :incomplete
(input-state "(bare parens)") ;=> :invalid
```

### start

```clojure
(mclj-lang.repl/start)
(mclj-lang.repl/start opts)
```

Start the meme REPL. Reads meme syntax, evaluates as Clojure, prints results.

Options:
- `:read-line` — custom line reader function (default: `read-line`, required on ClojureScript)
- `:eval` — custom eval function (default: `eval`, required on ClojureScript)
- `:resolve-keyword` — function to resolve `::` keywords at read time (default: `clojure.core/read-string` on JVM; required on CLJS for code that uses `::` keywords)
- `:prelude` — vector of forms to eval before the first user input (e.g., guest language standard library)

On JVM/Babashka, `:resolve-symbol` is automatically injected (matching Clojure's syntax-quote resolution, inlined in `mclj-lang.run`) unless explicitly provided.

```
$ bb meme repl
meme REPL. Type meme expressions, balanced input to eval. Ctrl-D to exit.
user=> +(1 2)
3
user=> map(inc [1 2 3])
(2 3 4)
```

The prompt shows the current namespace (e.g., `user=>` on JVM/Babashka, `meme=>` on ClojureScript).


## mclj-lang.run

Run `.mclj` files or meme source strings.

### run-string

```clojure
(mclj-lang.run/run-string s)
(mclj-lang.run/run-string s eval-fn)
(mclj-lang.run/run-string s opts)
```

Read meme source string, eval each form, return the last result. Strips leading `#!` shebang lines before parsing. The second argument can be an eval function (backward compatible) or an opts map.

Options (when passing a map):
- `:eval` — eval function (default: `eval`; required on CLJS)
- `:resolve-keyword` — function to resolve `::` keywords at read time (default: none — `::` keywords resolve at eval time in the file's declared namespace. Required on CLJS for code that uses `::` keywords)
- `:prelude` — vector of forms to eval before user code (e.g., guest language standard library)

On JVM/Babashka, `:resolve-symbol` is automatically injected (matching Clojure's syntax-quote resolution, inlined in `mclj-lang.run`) unless explicitly provided.

```clojure
(run-string "def(x 42)\n+(x 1)")
;=> 43
```

### run-file

```clojure
(mclj-lang.run/run-file path)
(mclj-lang.run/run-file path eval-fn)
(mclj-lang.run/run-file path opts)
```

Read and eval a `.mclj` file. Returns the last result. Uses `slurp` internally (JVM/Babashka only). Second argument follows the same convention as `run-string`.

By default runs the file as meme and installs `meme.loader` so `require`/`load-file` of `.mclj` namespaces work from within. Optional opts:

- `:install-loader?` — default `true`. Pass `false` to skip installing the loader (for hosts that own their own `clojure.core/load` interception).
- `:resolve-lang-for-path` — `(fn [path opts] → run-fn-or-nil)` for extension-based lang dispatch. When non-nil return value handles the file; otherwise falls through to meme. The CLI wires this to `meme.registry`.

```clojure
;; Default — always runs as meme
(run-file "test/examples/tests/01_core_rules.mclj")

;; With registry-driven dispatch (e.g. to run a sibling lang by extension)
(run-file "app.sibling"
          {:resolve-lang-for-path
           (fn [p _] (when-let [[_n l] (registry/resolve-by-extension p)]
                      (:run l)))})
```


## meme.tools.clj.stages

Explicit pipeline composition. Each stage is a `ctx → ctx` function operating on a shared context map with keys `:source`, `:opts`, `:cst`, `:forms`.

Each stage validates its required keys at entry against `stage-contracts` — miscomposed pipelines (e.g. calling `step-read` before `step-parse`) throw a clear `:mclj/pipeline-error` with the missing key(s) and the actual ctx keys present, instead of surfacing a deep-inside NPE.

### stage-contracts

```clojure
meme.tools.clj.stages/stage-contracts
;=> {:step-parse                         {:requires #{:source} :requires-opts #{:grammar} :produces #{:cst}}
;    :step-read                          {:requires #{:cst}    :produces #{:forms}}
;    :step-evaluate-reader-conditionals  {:requires #{:forms}  :produces #{:forms}}
;    :step-expand-syntax-quotes          {:requires #{:forms}  :produces #{:forms}}}
```

Machine-readable pipeline contract.  Tools that compose custom stages can extend their own contracts in the same shape.

Pipelines:
- **Tooling** (`mclj->forms`, `mclj->clj`, `format-mclj`): `step-parse → step-read`.
- **Eval** (`run-string`, `run-file`, REPL): `step-parse → step-read → step-evaluate-reader-conditionals → step-expand-syntax-quotes`.

### step-parse

```clojure
(meme.tools.clj.stages/step-parse ctx)
```

Parse source string into a lossless CST via the unified Pratt parser. Scanning (character dispatch, trivia) and parsing (structure) are handled in a single pass. Requires `:grammar` in `:opts` — no implicit default; each lang passes its own grammar (e.g. `mclj-lang.grammar/grammar`). Reads `:source`, assocs `:cst`.

### step-read

```clojure
(meme.tools.clj.stages/step-read ctx)
```

Lower CST to Clojure forms. Reads `:cst`, `:opts`, assocs `:forms`. Reader conditionals are preserved as `CljReaderConditional` records — materialize via `step-evaluate-reader-conditionals`. Passing `:read-cond` in `:opts` throws `:mclj/deprecated-opt`.

### step-evaluate-reader-conditionals

```clojure
(meme.tools.clj.stages/step-evaluate-reader-conditionals ctx)
```

Evaluate `#?` / `#?@` records in `:forms` for the target platform. `#?` is replaced by the matched branch (or removed if no branch matches); `#?@` splices its matched sequence into the containing collection. Recurses into syntax-quote / unquote / unquote-splicing interiors to match native Clojure's order (the reader evaluates `#?` before `` ` `` is processed). Handles `:default` as fallback when no platform key matches. Validates even-count branch lists.

Opts (via `:opts`):
- `:platform` — `:clj`, `:cljs`, or any platform keyword. Default: current compile-time platform.

### step-expand-syntax-quotes

```clojure
(meme.tools.clj.stages/step-expand-syntax-quotes ctx)
```

Expand syntax-quote AST nodes (`CljSyntaxQuote`) into plain Clojure forms (`seq`/`concat`/`list`). Also unwraps `CljRaw` values. Only needed before eval — tooling paths work with AST nodes directly.

### expand-syntax-quotes (convenience wrapper)

```clojure
(meme.tools.clj.stages/expand-syntax-quotes forms opts)
```

Plain-forms convenience wrapper around `step-expand-syntax-quotes` that doesn't require building a ctx map. Takes a vector of forms plus an opts map, returns the expanded forms. Use this when you already have a forms vector in hand (e.g. from `mclj->forms`) and want eval-ready output without the stages machinery.

### strip-shebang

```clojure
(meme.tools.clj.stages/strip-shebang source)
```

Remove a leading `#!` line from a source string. Invoked by runtime paths before `step-parse` so executable scripts (`#!/usr/bin/env bb meme run`) parse cleanly. Handles both `\n` and `\r\n` line endings. Returns the source unchanged if it does not start with `#!`.

### strip-bom

```clojure
(meme.tools.clj.stages/strip-bom source)
```

Remove a single leading UTF-8 BOM (`﻿`) from a source string. Invoked by runtime paths before `step-parse` so files saved with a BOM by Windows editors parse cleanly. Returns the source unchanged when no BOM is present. For most callers, prefer `strip-source-preamble`, which composes BOM and shebang stripping in the right order.

### run

```clojure
(meme.tools.clj.stages/run source)
(meme.tools.clj.stages/run source opts)
```

Run the pipeline: `step-parse → step-read`. Returns the complete context map. Does **not** include `step-expand-syntax-quotes` — forms contain AST nodes (`CljSyntaxQuote`, `CljRaw`) for tooling access. Call `step-expand-syntax-quotes` separately if you need eval-ready forms.

```clojure
(meme.tools.clj.stages/run "+(1 2)")
;=> {:source "+(1 2)", :opts nil,
;    :cst [...], :forms [(+ 1 2)]}
```


## meme.cli

Unified CLI for meme. JVM/Babashka only.

### Commands

| Command | Description |
|---------|-------------|
| `meme run <file>` | Run a `.mclj` file |
| `meme repl` | Start the meme REPL |
| `meme to-clj <file\|dir>` | Convert `.mclj` files to `.clj` (in-place). Alias: `from-meme`. |
| `meme to-mclj <file\|dir>` | Convert `.clj`/`.cljc`/`.cljs` files to `.mclj`. Alias: `from-clj`. |
| `meme format <file\|dir>` | Format `.mclj` files via canonical formatter (in-place by default, `--stdout` to print, `--check` for CI) |
| `meme transpile <dir\|file...>` | Transpile `.mclj` to `.clj` in a separate output directory (`--out target/mclj` by default). Output preserves relative paths — add the output dir to `:paths` in deps.edn for standard `require` without runtime patching. Alias: `compile`. |
| `meme build <dir\|file...>` | Transpile then AOT-compile to JVM bytecode. Staging in `target/mclj` (implementation detail), output `.class` files to `--out target/classes`. Spawns `clojure` with the staging dir on the classpath and runs `clojure.core/compile` per namespace. Stops at bytecode; use your own tools.build for JAR packaging. See `doc/language-reference.md` for `build.clj`-integrated recipes. |
| `meme inspect [--lang]` | Show lang info and supported commands |
| `meme version` | Print version |

All file commands accept directories (processed recursively) and multiple paths. `to-clj`, `to-mclj`, and `format` accept `--stdout` to print to stdout instead of writing files. Use `--lang` to select a lang backend (default: mclj).

Entry point: `-main` dispatches via `babashka.cli`. For Clojure JVM, use `-T:meme` (e.g., `clojure -T:meme run :file '"hello.mclj"'`).


## meme.tools.clj.resolve

Native value resolution. Converts raw token text to Clojure values — no `read-string` delegation. Consistent error wrapping and location info.

### Resolver functions

All resolvers take the raw token text and a `loc` map (`{:line N :col M}`) for error reporting:

```clojure
(meme.tools.clj.resolve/resolve-number raw loc)        ;; "42" → 42
(meme.tools.clj.resolve/resolve-string raw loc)        ;; "\"hi\"" → "hi"
(meme.tools.clj.resolve/resolve-char raw loc)          ;; "\\newline" → \newline
(meme.tools.clj.resolve/resolve-regex raw loc)         ;; "#\"\\d+\"" → #"\d+"
```

### resolve-auto-keyword

```clojure
(meme.tools.clj.resolve/resolve-auto-keyword raw loc resolve-fn)
```

Resolve an auto-resolve keyword (`::foo`). If `resolve-fn` is provided, resolves at read time. Otherwise, defers to eval time via `(read-string "::foo")`.

### resolve-tagged-literal

```clojure
(meme.tools.clj.resolve/resolve-tagged-literal tag data loc)
```

Resolve a tagged literal. JVM: produces a `TaggedLiteral` object via `clojure.core/tagged-literal`. CLJS: throws an error.


## Error Handling

The meme reader fails fast on invalid input. Parse errors are thrown as `ex-info` exceptions with `:line` and `:col` data when available.

Common errors:

| Error message | Cause |
|---------------|-------|
| `Expected :close-paren but got EOF` | Unclosed `(` |
| `Unquote (~) outside syntax-quote` | `~` only valid inside `` ` `` |

```clojure
(try
  (mclj-lang.api/mclj->forms "foo(")
  (catch Exception e
    (ex-data e)))
;=> {:line 1, :col 4}
```

Error recovery is not supported — the reader stops at the first error. This is documented as future work in the PRD.

## meme.loader

Namespace loader for `.mclj` files. Intercepts `clojure.core/load` and `clojure.core/load-file` to handle `.mclj` files transparently. JVM/Babashka only.

### install! / uninstall!

```clojure
(meme.loader/install!)    ;; => :installed
(meme.loader/uninstall!)  ;; => :uninstalled
```

`install!` is idempotent — safe to call multiple times and safe to call concurrently from multiple threads.

`uninstall!` throws `ex-info` with `{:reason :active-load, :in-flight N}` if any thread — including the calling thread — is currently inside a lang-load when uninstall is requested. This prevents tearing down the `clojure.core/load`/`load-file` overrides while another thread is still dispatching through them. `install!` and `uninstall!` serialize on a shared monitor, so concurrent calls are safe.

### What gets intercepted

| Function | Effect | JVM | Babashka |
|----------|--------|-----|----------|
| `require` | `(require 'my.ns)` searches for `my/ns.mclj` on the classpath | Yes | No (SCI bypasses `clojure.core/load`) |
| `load-file` | `(load-file "path/to/file.mclj")` runs through the meme pipeline | Yes | Yes |

**Automatic installation:** `mclj-lang.run/run-string`, `run-file`, and `mclj-lang.repl/start` install the loader by default. The CLI (`bb meme run`, `bb meme repl`) goes through these, so the loader is up before any user code runs. Opt out with `:install-loader? false` (e.g. when embedding meme in a host that owns its own `clojure.core/load` interception).

**Precedence:** When both `my/ns.mclj` and `my/ns.clj` exist on the classpath, `.mclj` takes priority.

**Babashka limitation:** Babashka's SCI interpreter does not dispatch `require` through `clojure.core/load`, so `require` of `.mclj` namespaces is JVM-only. `load-file` works on both platforms. For Babashka projects that need `require`, use `meme transpile` to precompile `.mclj` to `.clj`.

## meme.tools.clj.errors

Error infrastructure used by the scanner, reader, and resolver. Portable (.cljc).

### source-context

```clojure
(meme.tools.clj.errors/source-context source line)
```

Extract the source line at a 1-indexed line number from `source` (a string). Returns the line text, or `nil` if out of range.

### meme-error

```clojure
(meme.tools.clj.errors/meme-error message opts)
```

Throw `ex-info` with a consistent error structure. `opts` is a map with:
- `:line`, `:col` — source location (appended to message as `(line N, col M)`)
- `:cause` — optional upstream exception
- `:incomplete` — when `true`, signals the REPL that more input may complete the form

All scanner, reader, and resolver errors go through this function.

### format-error

```clojure
(meme.tools.clj.errors/format-error exception source)
```

Format an exception for display. Produces a multi-line string with:
1. The error message (prefixed with `"Error: "`)
2. The source line at the error location (with line-number gutter)
3. A caret (`^`) or span underline (`~~~`) pointing at the column(s) — uses `^` for single-column errors, `~` for multi-column spans when `:end-col` is present in ex-data
4. Secondary source locations with labels (when `:secondary` is present in ex-data — a sequence of `{:line :col :label}` maps)
5. A hint line (when `:hint` is present in ex-data)

If `source` is `nil`/blank or the exception lacks `:line`/`:col`, only the prefixed message is returned.


## meme.registry — Lang registration

The registry is generic infrastructure: it imports no concrete langs.  Each lang's api namespace calls `register-builtin!` at its own load time; the CLI (or any host application) triggers this by explicitly requiring the langs it ships with.  User langs use `register!`, which goes through EDN resolution and guards against overriding built-ins.

### register-builtin!

```clojure
(meme.registry/register-builtin! lang-name lang-map)
```

Register a built-in lang with a pre-resolved `lang-map`.  Called at ns-load time from each lang's own api namespace — the registry itself imports no langs to avoid a circular dependency:

```clojure
;; src/my_lang/api.cljc
(def lang-map
  {:extension ".mylang"
   :to-clj    to-clj
   #?@(:clj [:run  run-string
             :repl start])})

#?(:clj (registry/register-builtin! :my-lang lang-map))
```

Then the host app (e.g. `meme.cli`) explicitly `(:require [my-lang.api])` — the require side-effects the registration.  The lang is then available via `registry/resolve-lang` and `registry/resolve-by-extension`.

Tags the lang with `:builtin? true` metadata so `register!` can refuse user attempts to shadow it.

### register!

```clojure
(meme.registry/register! lang-name config)
```

Register a user lang. `lang-name` is a keyword. `config` is an EDN-style map — the same format as `.edn` lang files. Symbols are resolved via `requiring-resolve`, strings and keywords follow the same rules as `load-edn`. Pre-resolved functions are also accepted. Thread-safe — extension conflict checks are atomic.

Both `:extension` (string) and `:extensions` (vector) are accepted. Both are normalized to a single `:extensions` vector. The `:extension` key is removed after normalization.

```clojure
;; Single extension — prelude file path
(registry/register! :my-lang {:extension ".ml"
                              :run "path/to/prelude.mclj"})

;; Multiple extensions — pre-resolved run fn
(registry/register! :my-lang {:extensions [".ml" ".mlx"]
                              :run 'my-lang.run/run-string})

;; Mixed — both forms merged
(registry/register! :hybrid {:extension ".hyb"
                             :extensions [".hybx"]
                             :run 'my-lang.run/run-string})
;; Normalizes to :extensions [".hyb" ".hybx"]
```

### register-string-handler!

```clojure
(meme.registry/register-string-handler! command handler)
```

Install a handler for resolving string values in the given command slot. `handler` is `(fn [string-value] → command-fn)` and is called once per `register!`/`load-edn` for each string value in that slot.

The registry itself is lang-agnostic: it does not know how to interpret a string path. Meme installs its own `:run` handler at load time, which treats the string as a prelude `.mclj` file path:

```clojure
;; mclj-lang.api (JVM only)
(registry/register-string-handler! :run
  (fn [prelude-path]
    (fn [source opts]
      (run/run-string (slurp prelude-path) (dissoc opts :prelude :lang))
      (run/run-string source opts))))
```

A sibling lang with different string conventions would install its own handler (later registrations override earlier ones). Without a handler, string values for that command throw with a clear error.

### resolve-by-extension

```clojure
(meme.registry/resolve-by-extension path)
```

Given a file path, find the lang whose `:extensions` (plural, normalized) include a match for the file's suffix. Returns `[lang-name lang-map]` or `nil`. Covers both built-in and user-registered langs. Meme ships with `.mclj`, `.mcljc`, `.mcljj`, `.mcljs`.

### registered-langs

```clojure
(meme.registry/registered-langs)
```

List all registered **user** lang names (keywords). Excludes built-ins (mclj-lang).

### available-langs

```clojure
(meme.registry/available-langs)
```

List all registered lang names (keywords) — built-ins and user-registered combined. Useful for CLI enumeration or diagnostics.

