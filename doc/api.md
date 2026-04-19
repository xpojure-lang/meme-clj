# meme API Reference

Namespaces are organized in three layers: `meme.tools.*` (generic), `meme-lang.*` (language), `meme.*` (CLI).

## meme-lang.api

The public API for reading and printing meme syntax, organized in three tracks:

```
         text-to-form          form-to-text
meme str ──→ meme->forms ──→ forms ──→ forms->meme ──→ meme str
clj str  ──→ clj->forms  ──→ forms ──→ forms->clj  ──→ clj str

         text-to-text (compositions)
meme str ──→ meme->clj ──→ clj str
clj str  ──→ clj->meme ──→ meme str
```

### Text-to-form track

#### meme->forms

```clojure
(meme-lang.api/meme->forms s)
(meme-lang.api/meme->forms s opts)
```

Read a meme source string. Returns a vector of Clojure forms. All platforms.

Options:
- `:resolve-keyword` — function that resolves auto-resolve keyword strings (`"::foo"`) to keywords at read time. When absent on JVM/Babashka, `::` keywords are deferred to eval time via `(read-string "::foo")`. Required on CLJS (errors without it, since `cljs.reader` cannot resolve `::` in the correct namespace).
- `:read-cond` — `:preserve` to return `ReaderConditional` objects instead of evaluating reader conditionals. Default: evaluate `#?` for the current platform. Use `:preserve` for lossless `clj->meme->clj` roundtrips of `.cljc` files.
- `:resolve-symbol` — function that resolves symbols during syntax-quote expansion (e.g., `foo` → `my.ns/foo`). On JVM/Babashka, `run-string`/`run-file`/`start` inject a default that matches Clojure's `SyntaxQuoteReader` (inlined in `meme-lang.run`). When calling `meme->forms` directly, symbols in syntax-quote are left unqualified unless this option is provided. On CLJS, no default is available.

```clojure
(meme->forms "+(1 2 3)")
;=> [(+ 1 2 3)]

(meme->forms "def(x 42)\nprintln(x)")
;=> [(def x 42) (println x)]
```

**Note:** `meme->forms` may return internal record types for forms that preserve source notation: `MemeRaw` (for hex numbers, unicode escapes, etc.) wraps a `:value` and `:raw` text; `MemeAutoKeyword` (for `::` keywords) wraps a `:raw` string. These are unwrapped to plain values by `step-expand-syntax-quotes` (which `run-string`/`run-file` call before eval). If you need plain Clojure values from `meme->forms`, compose with `meme-lang.stages/step-expand-syntax-quotes`.

#### forms->meme

```clojure
(meme-lang.api/forms->meme forms)
```

Print a sequence of Clojure forms as meme text. All platforms.

**Note:** Reference types (atoms, refs, agents) print as `#object[...]` which cannot be round-tripped. This matches Clojure's own behavior.

```clojure
(forms->meme ['(+ 1 2 3)])
;=> "+(1 2 3)"
```

### Form-to-text track

#### forms->clj

```clojure
(meme-lang.api/forms->clj forms)
```

Print Clojure forms as a Clojure source string. All platforms.

```clojure
(forms->clj ['(def x 42) '(println x)])
;=> "(def x 42)\n\n(println x)"
```

#### clj->forms

```clojure
(meme-lang.api/clj->forms clj-src)
```

Read a Clojure source string, return a vector of forms. JVM/Babashka only.

```clojure
(clj->forms "(defn f [x] (+ x 1))")
;=> [(defn f [x] (+ x 1))]
```

### Pretty-printing

#### format-meme-forms

```clojure
(meme-lang.api/format-meme-forms forms)
(meme-lang.api/format-meme-forms forms opts)
```

Format Clojure forms as canonical (width-aware, multi-line) meme text. Uses indented parenthesized form for calls that exceed the line width. Preserves comments from `:meme-lang/leading-trivia` metadata (attached by the pipeline's scan stage). All platforms.

Options:
- `:width` — target line width (default: 80)

```clojure
(format-meme ['(defn greet [name] (println (str "Hello " name)))])
;=> "defn(greet [name]\n  println(str(\"Hello \" name)))"
```

### Text-to-text track

#### meme->clj

```clojure
(meme-lang.api/meme->clj meme-src)
(meme-lang.api/meme->clj meme-src opts)
```

Convert meme source string to Clojure source string. All platforms. Equivalent to `(forms->clj (meme->forms meme-src opts))`.

Options: same as `meme->forms` (`:resolve-keyword`, `:read-cond`).

```clojure
(meme->clj "println(\"hello\")")
;=> "(println \"hello\")"
```

#### clj->meme

```clojure
(meme-lang.api/clj->meme clj-src)
```

Convert a Clojure source string to meme source string. JVM/Babashka only. Equivalent to `(forms->meme (clj->forms clj-src))`.

**Known limitation:** Clojure's reader expands reader sugar before meme sees the forms. `'(f x)` becomes `(quote (f x))` → `quote(f(x))` instead of `'f(x)`. Similarly, `@atom` → `clojure.core/deref(atom)`, and `#(+ % 1)` → `fn*([p1__N#] +(p1__N# 1))`. The `meme->clj->meme` roundtrip preserves semantics but not notation for these forms.

```clojure
(clj->meme "(defn f [x] (+ x 1))")
;=> "defn(f [x] +(x 1))"
```

## meme-lang.form-shape

Semantic decomposition of Clojure special forms into named slots. See [doc/form-shape.md](form-shape.md) for the slot vocabulary, extension patterns, and consumer sketches. The three slot layers — notation (printer), form-shape (this namespace), style (formatter) — are independently composable.

### registry

```clojure
meme-lang.form-shape/registry
```

The built-in registry of decomposers, a plain map `{head-symbol → (fn [args-vec] → slots)}`. Extend with `assoc`, compose with `merge`, or wrap with `with-structural-fallback`. Consumed by `lang-map`'s `:form-shape` key and threaded through formatters to the printer.

### decompose

```clojure
(meme-lang.form-shape/decompose registry head args)
```

Look up `head` in `registry` and apply its decomposer to `args`. Returns a vector of `[slot-name value]` pairs in source order, or `nil` if no decomposer matches (and no structural fallback is installed). A nil `registry` yields nil for every head — every form renders as a plain call.

When the registry carries a `::fallback-fn` in its metadata (see `with-structural-fallback`), a missed lookup consults the fallback; this lets user macros inherit layout from structural shape alone.

### with-structural-fallback

```clojure
(meme-lang.form-shape/with-structural-fallback registry)
```

Return a registry (same entries) that uses structural inference when no entry matches a given head. Two patterns are recognized:

- `(HEAD name [params] body*)` — inferred as defn-like
- `(HEAD [bindings] body*)` — inferred as let-like

A user macro matching either pattern inherits canonical layout without being registered. Other shapes return nil (plain-call rendering).

Opt-in: the built-in `registry` has no fallback by default, so existing code doesn't silently change layout for user macros.


## meme-lang.printer

Low-level Doc tree builder. Most callers should use `formatter.flat` or `formatter.canon` instead.

### to-doc

```clojure
(meme-lang.printer/to-doc form)
(meme-lang.printer/to-doc form mode)
(meme-lang.printer/to-doc form mode style)
(meme-lang.printer/to-doc form mode style form-shape)
```

Convert a Clojure form to a Wadler-Lindig Doc tree. The Doc tree is passed to `meme.tools.render/layout` for final string output.

- `mode` — `:meme` (call notation, default) or `:clj` (standard Clojure with reader sugar).
- `style` — layout policy map (nil = pass-through). Keyed by semantic slot names from `meme-lang.form-shape` (`:name`, `:params`, `:bindings`, etc.), not by form names. See `meme-lang.formatter.canon/style` for the canonical policy.
- `form-shape` — registry map `{head-symbol → decomposer-fn}`. When nil, no special-form decomposition runs and every call renders as a plain body sequence. Callers normally pass `meme-lang.form-shape/registry`; the lang's `lang-map` exposes its registry under `:form-shape`.

## meme-lang.formatter.flat

Flat (single-line) formatter. Composes printer + render at infinite width.

### format-form

```clojure
(meme-lang.formatter.flat/format-form form)
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
(meme-lang.formatter.flat/format-forms forms)
```

Format a sequence of Clojure forms as flat meme text, separated by blank lines.

### format-clj

```clojure
(meme-lang.formatter.flat/format-clj forms)
```

Format Clojure forms as Clojure text with reader sugar (`'quote`, `@deref`, `#'var`).


## meme-lang.formatter.canon

Canonical (width-aware) formatter. Composes printer + render at target width. Used by `meme format` CLI.

### format-form

```clojure
(meme-lang.formatter.canon/format-form form)
(meme-lang.formatter.canon/format-form form opts)
```

Format a single Clojure form as canonical meme text. Width-aware — uses indented multi-line layout for forms that exceed the target width. Preserves comments from `:meme-lang/leading-trivia` metadata.

Options:
- `:width` — target line width (default: 80)
- `:form-shape` — form-shape registry (default: `meme-lang.form-shape/registry`). Override to add user-defined defining macros or to disable decomposition entirely (pass `nil` for plain-call layout). Wrap with `meme-lang.form-shape/with-structural-fallback` to enable structural inference for user macros that mirror `defn` (name + params vector) or `let` (leading bindings vector) shapes.
- `:style` — slot-keyed style map (default: `meme-lang.formatter.canon/style`). Override for project-level tweaks — e.g. a narrower `:head-line-slots` set, or a custom `:slot-renderers` map that replaces the printer defaults for `:bindings` or `:clause`. See [form-shape.md](form-shape.md) for the style map schema.

### format-forms

```clojure
(meme-lang.formatter.canon/format-forms forms)
(meme-lang.formatter.canon/format-forms forms opts)
```

Format a sequence of Clojure forms as canonical meme text, separated by blank lines. Preserves comments from `:meme-lang/leading-trivia` metadata, including trailing comments after the last form.


## meme-lang.repl

### input-state

```clojure
(meme-lang.repl/input-state s)
(meme-lang.repl/input-state s opts)
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
(meme-lang.repl/start)
(meme-lang.repl/start opts)
```

Start the meme REPL. Reads meme syntax, evaluates as Clojure, prints results.

Options:
- `:read-line` — custom line reader function (default: `read-line`, required on ClojureScript)
- `:eval` — custom eval function (default: `eval`, required on ClojureScript)
- `:resolve-keyword` — function to resolve `::` keywords at read time (default: `clojure.core/read-string` on JVM; required on CLJS for code that uses `::` keywords)
- `:prelude` — vector of forms to eval before the first user input (e.g., guest language standard library)

On JVM/Babashka, `:resolve-symbol` is automatically injected (matching Clojure's syntax-quote resolution, inlined in `meme-lang.run`) unless explicitly provided.

```
$ bb meme repl
meme REPL. Type meme expressions, balanced input to eval. Ctrl-D to exit.
user=> +(1 2)
3
user=> map(inc [1 2 3])
(2 3 4)
```

The prompt shows the current namespace (e.g., `user=>` on JVM/Babashka, `meme=>` on ClojureScript).


## meme-lang.run

Run `.meme` files or meme source strings.

### run-string

```clojure
(meme-lang.run/run-string s)
(meme-lang.run/run-string s eval-fn)
(meme-lang.run/run-string s opts)
```

Read meme source string, eval each form, return the last result. Strips leading `#!` shebang lines before parsing. The second argument can be an eval function (backward compatible) or an opts map.

Options (when passing a map):
- `:eval` — eval function (default: `eval`; required on CLJS)
- `:resolve-keyword` — function to resolve `::` keywords at read time (default: none — `::` keywords resolve at eval time in the file's declared namespace. Required on CLJS for code that uses `::` keywords)
- `:prelude` — vector of forms to eval before user code (e.g., guest language standard library)

On JVM/Babashka, `:resolve-symbol` is automatically injected (matching Clojure's syntax-quote resolution, inlined in `meme-lang.run`) unless explicitly provided.

```clojure
(run-string "def(x 42)\n+(x 1)")
;=> 43
```

### run-file

```clojure
(meme-lang.run/run-file path)
(meme-lang.run/run-file path eval-fn)
(meme-lang.run/run-file path opts)
```

Read and eval a `.meme` file. Returns the last result. Uses `slurp` internally (JVM/Babashka only). Second argument follows the same convention as `run-string`.

By default runs the file as meme and installs `meme.loader` so `require`/`load-file` of `.meme` namespaces work from within. Optional opts:

- `:install-loader?` — default `true`. Pass `false` to skip installing the loader (for hosts that own their own `clojure.core/load` interception).
- `:resolve-lang-for-path` — `(fn [path opts] → run-fn-or-nil)` for extension-based lang dispatch. When non-nil return value handles the file; otherwise falls through to meme. The CLI wires this to `meme.registry`.

```clojure
;; Default — always runs as meme
(run-file "test/examples/tests/01_core_rules.meme")

;; With registry-driven dispatch
(run-file "app.calc"
          {:resolve-lang-for-path
           (fn [p _] (when-let [[_n l] (registry/resolve-by-extension p)]
                      (:run l)))})
```


## meme-lang.stages

Explicit pipeline composition. Each stage is a `ctx → ctx` function operating on a shared context map with keys `:source`, `:opts`, `:cst`, `:forms`.

Each stage validates its required keys at entry against `stage-contracts` — miscomposed pipelines (e.g. calling `step-read` before `step-parse`) throw a clear `:meme-lang/pipeline-error` with the missing key(s) and the actual ctx keys present, instead of surfacing a deep-inside NPE.

### stage-contracts

```clojure
meme-lang.stages/stage-contracts
;=> {:step-parse                 {:requires #{:source} :produces #{:cst}}
;    :step-read                  {:requires #{:cst}    :produces #{:forms}}
;    :step-expand-syntax-quotes  {:requires #{:forms}  :produces #{:forms}}}
```

Machine-readable pipeline contract.  Tools that compose custom stages can extend their own contracts in the same shape.

### step-parse

```clojure
(meme-lang.stages/step-parse ctx)
```

Parse source string into a lossless CST via the unified Pratt parser. Scanning (character dispatch, trivia) and parsing (structure) are handled in a single pass. Uses meme grammar by default, or `(:grammar opts)` if provided. Reads `:source`, assocs `:cst`.

### step-read

```clojure
(meme-lang.stages/step-read ctx)
```

Lower CST to Clojure forms. Reads `:cst`, `:opts`, assocs `:forms`.

### step-expand-syntax-quotes

```clojure
(meme-lang.stages/step-expand-syntax-quotes ctx)
```

Expand syntax-quote AST nodes (`MemeSyntaxQuote`) into plain Clojure forms (`seq`/`concat`/`list`). Also unwraps `MemeRaw` values. Only needed before eval — tooling paths work with AST nodes directly.

### run

```clojure
(meme-lang.stages/run source)
(meme-lang.stages/run source opts)
```

Run the pipeline: `step-parse → step-read`. Returns the complete context map. Does **not** include `step-expand-syntax-quotes` — forms contain AST nodes (`MemeSyntaxQuote`, `MemeRaw`) for tooling access. Call `step-expand-syntax-quotes` separately if you need eval-ready forms.

```clojure
(meme-lang.stages/run "+(1 2)")
;=> {:source "+(1 2)", :opts nil,
;    :cst [...], :forms [(+ 1 2)]}
```


## meme.cli

Unified CLI for meme. JVM/Babashka only.

### Commands

| Command | Description |
|---------|-------------|
| `meme run <file>` | Run a `.meme` file |
| `meme repl` | Start the meme REPL |
| `meme to-clj <file\|dir>` | Convert `.meme` files to `.clj` (in-place) |
| `meme to-meme <file\|dir>` | Convert `.clj`/`.cljc`/`.cljs` files to `.meme` |
| `meme format <file\|dir>` | Format `.meme` files via canonical formatter (in-place by default, `--stdout` to print, `--check` for CI) |
| `meme compile <dir\|file...>` | Compile `.meme` to `.clj` in a separate output directory (`--out target/classes`). Output preserves relative paths — add the output dir to `:paths` in deps.edn for standard `require` without runtime patching. |
| `meme inspect [--lang]` | Show lang info and supported commands |
| `meme version` | Print version |

All file commands accept directories (processed recursively) and multiple paths. `to-clj`, `to-meme`, and `format` accept `--stdout` to print to stdout instead of writing files. Use `--lang` to select a lang backend (default: meme).

Entry point: `-main` dispatches via `babashka.cli`. For Clojure JVM, use `-T:meme` (e.g., `clojure -T:meme run :file '"hello.meme"'`).


## meme-lang.resolve

Native value resolution. Converts raw token text to Clojure values — no `read-string` delegation. Consistent error wrapping and location info.

### Resolver functions

All resolvers take the raw token text and a `loc` map (`{:line N :col M}`) for error reporting:

```clojure
(meme-lang.resolve/resolve-number raw loc)        ;; "42" → 42
(meme-lang.resolve/resolve-string raw loc)        ;; "\"hi\"" → "hi"
(meme-lang.resolve/resolve-char raw loc)          ;; "\\newline" → \newline
(meme-lang.resolve/resolve-regex raw loc)         ;; "#\"\\d+\"" → #"\d+"
```

### resolve-auto-keyword

```clojure
(meme-lang.resolve/resolve-auto-keyword raw loc resolve-fn)
```

Resolve an auto-resolve keyword (`::foo`). If `resolve-fn` is provided, resolves at read time. Otherwise, defers to eval time via `(read-string "::foo")`.

### resolve-tagged-literal

```clojure
(meme-lang.resolve/resolve-tagged-literal tag data loc)
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
  (meme-lang.api/meme->forms "foo(")
  (catch Exception e
    (ex-data e)))
;=> {:line 1, :col 4}
```

Error recovery is not supported — the reader stops at the first error. This is documented as future work in the PRD.

## meme.loader

Namespace loader for `.meme` files. Intercepts `clojure.core/load` and `clojure.core/load-file` to handle `.meme` files transparently. JVM/Babashka only.

### install! / uninstall!

```clojure
(meme.loader/install!)    ;; => :installed
(meme.loader/uninstall!)  ;; => :uninstalled
```

`install!` is idempotent — safe to call multiple times.

### What gets intercepted

| Function | Effect | JVM | Babashka |
|----------|--------|-----|----------|
| `require` | `(require 'my.ns)` searches for `my/ns.meme` on the classpath | Yes | No (SCI bypasses `clojure.core/load`) |
| `load-file` | `(load-file "path/to/file.meme")` runs through the meme pipeline | Yes | Yes |

**Automatic installation:** `meme-lang.run/run-string`, `run-file`, and `meme-lang.repl/start` install the loader by default. The CLI (`bb meme run`, `bb meme repl`) goes through these, so the loader is up before any user code runs. Opt out with `:install-loader? false` (e.g. when embedding meme in a host that owns its own `clojure.core/load` interception).

**Precedence:** When both `my/ns.meme` and `my/ns.clj` exist on the classpath, `.meme` takes priority.

**Babashka limitation:** Babashka's SCI interpreter does not dispatch `require` through `clojure.core/load`, so `require` of `.meme` namespaces is JVM-only. `load-file` works on both platforms. For Babashka projects that need `require`, use `meme compile` to precompile `.meme` to `.clj`.

## meme.config

Project-local formatter configuration.  Reads `.meme-format.edn` (walking up from a starting directory) and translates it into opts for `meme-lang.formatter.canon/format-form`.  Consumed by `meme format` CLI; programmatic callers can use it directly.  JVM/Babashka only.

See [form-shape.md](form-shape.md) for the config schema and worked examples.

### resolve-project-opts

```clojure
(meme.config/resolve-project-opts)              ;; starts from CWD
(meme.config/resolve-project-opts start-dir)    ;; starts from given dir
```

Locate `.meme-format.edn` by walking up from `start-dir` (or CWD), read and validate it, and return the derived opts map ready to pass to `canon/format-form`.  Returns `{}` if no config is found.

The returned map contains `:width` / `:form-shape` / `:style` as appropriate, plus a `::source` key holding the config file path (for diagnostics).  Config errors throw `ex-info` with `::config-error true` in ex-data — callers can catch and report them.

### find-config-file / read-config-file / validate-config / config->opts

Lower-level pieces if you want to drive config loading yourself.  `find-config-file` returns the `File` or nil; `read-config-file` parses and validates an EDN file; `validate-config` checks an already-parsed map; `config->opts` converts a validated config to formatter opts.

## meme-lang.errors

Error infrastructure used by the scanner, reader, and resolver. Portable (.cljc).

### source-context

```clojure
(meme-lang.errors/source-context source line)
```

Extract the source line at a 1-indexed line number from `source` (a string). Returns the line text, or `nil` if out of range.

### meme-error

```clojure
(meme-lang.errors/meme-error message opts)
```

Throw `ex-info` with a consistent error structure. `opts` is a map with:
- `:line`, `:col` — source location (appended to message as `(line N, col M)`)
- `:cause` — optional upstream exception
- `:incomplete` — when `true`, signals the REPL that more input may complete the form

All scanner, reader, and resolver errors go through this function.

### format-error

```clojure
(meme-lang.errors/format-error exception source)
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
;; Single extension
(registry/register! :prefix {:extension ".prefix"
                             :run "examples/languages/prefix/core.meme"
                             :format :meme})

;; Multiple extensions
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

The registry itself is lang-agnostic: it does not know how to interpret a string path. Meme installs its own `:run` handler at load time, which treats the string as a prelude `.meme` file path:

```clojure
;; meme-lang.api (JVM only)
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

Given a file path, find the user lang whose `:extension` matches. Returns `[lang-name lang-map]` or `nil`.

### registered-langs

```clojure
(meme.registry/registered-langs)
```

List all registered user language names (keywords).

