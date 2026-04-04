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

Format Clojure forms as canonical (width-aware, multi-line) meme text. Uses indented parenthesized form for calls that exceed the line width. Preserves comments from `:ws` metadata (attached by the pipeline's scan stage). All platforms.

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

## meme-lang.printer

Low-level Doc tree builder. Most callers should use `formatter.flat` or `formatter.canon` instead.

### to-doc

```clojure
(meme-lang.printer/to-doc form mode)
```

Convert a Clojure form to a Wadler-Lindig Doc tree. `mode` is `:meme` (call notation) or `:clj` (standard Clojure with reader sugar). The Doc tree is passed to `meme.tools.render/layout` for final string output.

### extract-comments

```clojure
(meme-lang.printer/extract-comments ws)
```

Extract comment lines from a `:ws` metadata string. Returns a vector of trimmed comment strings, or nil.


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

Format a single Clojure form as canonical meme text. Width-aware — uses indented multi-line layout for forms that exceed the target width. Preserves comments from `:ws` metadata.

Options:
- `:width` — target line width (default: 80)

### format-forms

```clojure
(meme-lang.formatter.canon/format-forms forms)
(meme-lang.formatter.canon/format-forms forms opts)
```

Format a sequence of Clojure forms as canonical meme text, separated by blank lines. Preserves comments from `:ws` metadata, including trailing comments after the last form.


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

Read and eval a `.meme` file. Returns the last result. Uses `slurp` internally (JVM/Babashka only). Second argument follows same convention as `run-string`.

Automatically detects guest languages from file extension via `meme.registry/resolve-by-extension`. If a registered lang matches the extension, its `:run` function handles prelude and custom parser.

```clojure
(run-file "test/examples/tests/01_core_rules.meme")
```


## meme-lang.stages

Explicit pipeline composition. Each stage is a `ctx → ctx` function operating on a shared context map with keys `:source`, `:opts`, `:cst`, `:forms`.

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
| `meme to-clj <file\|dir>` | Convert `.meme` files to `.clj` |
| `meme to-meme <file\|dir>` | Convert `.clj`/`.cljc`/`.cljs` files to `.meme` |
| `meme format <file\|dir>` | Format `.meme` files via canonical formatter (in-place by default, `--stdout` to print, `--check` for CI) |
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

## meme-lang.errors

Error infrastructure used by the tokenizer and reader. Portable (.cljc).

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

All tokenizer and reader errors go through this function.

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


## meme.registry — User lang registration

In addition to built-in langs and EDN-loaded langs, user langs can be registered at runtime for extension-based auto-detection by `run-file` and the CLI.

### register!

```clojure
(meme.registry/register! lang-name config)
```

Register a user lang. `lang-name` is a keyword. `config` is an EDN-style map — the same format as `.edn` lang files. Symbols are resolved via `requiring-resolve`, strings and keywords follow the same rules as `load-edn`. Pre-resolved functions are also accepted.

```clojure
(registry/register! :prefix {:extension ".prefix"
                             :run "examples/languages/prefix/core.meme"
                             :format :meme})
```

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

### clear-user-langs!

```clojure
(meme.registry/clear-user-langs!)
```

Clear all registered user languages. For testing.

