# meme API Reference

All namespaces live under `meme.alpha` to signal that the API is pre-1.0 and may change. When the API stabilizes, namespaces will move to `meme`.

## meme.alpha.core

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
(meme.alpha.core/meme->forms s)
(meme.alpha.core/meme->forms s opts)
```

Read a meme source string. Returns a vector of Clojure forms. All platforms.

Options:
- `:resolve-keyword` — function that resolves auto-resolve keyword strings (`"::foo"`) to keywords at read time. When absent on JVM/Babashka, `::` keywords are deferred to eval time via `(read-string "::foo")`. Required on CLJS (errors without it, since `cljs.reader` cannot resolve `::` in the correct namespace).

```clojure
(meme->forms "+(1 2 3)")
;=> [(+ 1 2 3)]

(meme->forms "def(x 42)\nprintln(x)")
;=> [(def x 42) (println x)]
```

#### forms->meme

```clojure
(meme.alpha.core/forms->meme forms)
```

Print a sequence of Clojure forms as meme text. All platforms.

```clojure
(forms->meme ['(+ 1 2 3)])
;=> "+(1 2 3)"
```

### Form-to-text track

#### forms->clj

```clojure
(meme.alpha.core/forms->clj forms)
```

Print Clojure forms as a Clojure source string. All platforms.

```clojure
(forms->clj ['(def x 42) '(println x)])
;=> "(def x 42)\n\n(println x)"
```

#### clj->forms

```clojure
(meme.alpha.core/clj->forms clj-src)
```

Read a Clojure source string, return a vector of forms. JVM/Babashka only.

```clojure
(clj->forms "(defn f [x] (+ x 1))")
;=> [(defn f [x] (+ x 1))]
```

### Pretty-printing

#### pprint-meme

```clojure
(meme.alpha.core/pprint-meme forms)
(meme.alpha.core/pprint-meme forms opts)
```

Pretty-print Clojure forms as multi-line, indented meme text. Uses `begin`/`end` for forms that exceed the line width. Preserves comments from `:ws` metadata (attached by the pipeline's scan stage). All platforms.

Options:
- `:width` — target line width (default: 80)

```clojure
(pprint-meme ['(defn greet [name] (println (str "Hello " name)))])
;=> "defn begin greet [name]\n  println(str(\"Hello \" name))\nend"
```

### Text-to-text track

#### meme->clj

```clojure
(meme.alpha.core/meme->clj meme-src)
```

Convert meme source string to Clojure source string. All platforms. Equivalent to `(forms->clj (meme->forms meme-src))`.

```clojure
(meme->clj "println(\"hello\")")
;=> "(println \"hello\")"
```

#### clj->meme

```clojure
(meme.alpha.core/clj->meme clj-src)
```

Convert a Clojure source string to meme source string. JVM/Babashka only. Equivalent to `(forms->meme (clj->forms clj-src))`.

```clojure
(clj->meme "(defn f [x] (+ x 1))")
;=> "defn(f [x] +(x 1))"
```

### run-pipeline

```clojure
(meme.alpha.core/run-pipeline source)
(meme.alpha.core/run-pipeline source opts)
```

Run the full pipeline: source → scan → group → parse. Returns a context map with intermediate state. All platforms. Useful for tooling that needs access to raw tokens, grouped tokens, or parsed forms.

```clojure
(run-pipeline "foo(1 2)")
;=> {:source "foo(1 2)"
;    :opts nil
;    :raw-tokens [...tokenizer output...]
;    :tokens [...grouped tokens...]
;    :forms [(foo 1 2)]}
```

## meme.alpha.parse.reader

Low-level reader API.

### read-meme-string-from-tokens

```clojure
(meme.alpha.parse.reader/read-meme-string-from-tokens tokens)
(meme.alpha.parse.reader/read-meme-string-from-tokens tokens opts source)
```

Parse pre-tokenized, pre-grouped tokens into Clojure forms. Used by `meme.alpha.pipeline/parse`. Most callers should use `meme.alpha.core/meme->forms` instead.

- `tokens` — a grouped token vector (output of `meme.alpha.scan.grouper/group-tokens`)
- `opts` — same options as `meme->forms` (e.g., `:resolve-keyword`)
- `source` — original source text for error context (optional)


## meme.alpha.emit.printer

Low-level printer API.

### print-form

```clojure
(meme.alpha.emit.printer/print-form form)
```

Print a single Clojure form as meme text.

```clojure
(print-form '(+ 1 2))
;=> "+(1 2)"

(print-form '(:balance account))
;=> ":balance(account)"

(print-form '(def x 42))
;=> "def(x 42)"
```

### print-meme-string

```clojure
(meme.alpha.emit.printer/print-meme-string forms)
```

Print a sequence of Clojure forms as meme text, separated by blank lines.


## meme.alpha.emit.pprint

Low-level pretty-printer API.

### pprint-form

```clojure
(meme.alpha.emit.pprint/pprint-form form)
(meme.alpha.emit.pprint/pprint-form form opts)
```

Pretty-print a single Clojure form as meme text. Width-aware — uses `begin`/`end` for forms that don't fit on one line. Preserves comments from `:ws` metadata.

Options:
- `:width` — target line width (default: 80)

### pprint-forms

```clojure
(meme.alpha.emit.pprint/pprint-forms forms)
(meme.alpha.emit.pprint/pprint-forms forms opts)
```

Pretty-print a sequence of Clojure forms as meme text, separated by blank lines. Preserves comments from `:ws` metadata, including trailing comments after the last form.


## meme.alpha.runtime.repl

### input-state

```clojure
(meme.alpha.runtime.repl/input-state s)
```

Returns the parse state of a meme input string: `:complete` (parsed successfully), `:incomplete` (unclosed delimiter — keep reading), or `:invalid` (malformed, non-recoverable error). Used internally by the REPL for multi-line input handling; also useful for editor integration.

```clojure
(input-state "+(1 2)")      ;=> :complete
(input-state "f(")          ;=> :incomplete
(input-state "(bare parens)") ;=> :invalid
```

### start

```clojure
(meme.alpha.runtime.repl/start)
(meme.alpha.runtime.repl/start opts)
```

Start the meme REPL. Reads meme syntax, evaluates as Clojure, prints results.

Options:
- `:read-line` — custom line reader function (default: `read-line`, required on ClojureScript)
- `:eval` — custom eval function (default: `eval`, required on ClojureScript)
- `:resolve-keyword` — function to resolve `::` keywords at read time (default: `clojure.core/read-string` on JVM; required on CLJS for code that uses `::` keywords)

```
$ bb meme
meme REPL. Type meme expressions, balanced input to eval. Ctrl-D to exit.
user=> +(1 2)
3
user=> map(inc [1 2 3])
(2 3 4)
```

The prompt shows the current namespace (e.g., `user=>` on JVM/Babashka, `meme=>` on ClojureScript).


## meme.alpha.runtime.run

Run `.meme` files or meme source strings.

### run-string

```clojure
(meme.alpha.runtime.run/run-string s)
(meme.alpha.runtime.run/run-string s eval-fn)
(meme.alpha.runtime.run/run-string s opts)
```

Read meme source string, eval each form, return the last result. Strips leading `#!` shebang lines before parsing. The second argument can be an eval function (backward compatible) or an opts map.

Options (when passing a map):
- `:eval` — eval function (default: `eval`; required on CLJS)
- `:resolve-keyword` — function to resolve `::` keywords at read time (default: none — `::` keywords resolve at eval time in the file's declared namespace. Required on CLJS for code that uses `::` keywords)

```clojure
(run-string "def(x 42)\n+(x 1)")
;=> 43
```

### run-file

```clojure
(meme.alpha.runtime.run/run-file path)
(meme.alpha.runtime.run/run-file path eval-fn)
(meme.alpha.runtime.run/run-file path opts)
```

Read and eval a `.meme` file. Returns the last result. Uses `slurp` internally (JVM/Babashka only). Second argument follows same convention as `run-string`.

```clojure
(run-file "test/examples/tests/01_core_rules.meme")
```


## meme.alpha.pipeline

Explicit pipeline composition. Each stage is a `ctx → ctx` function operating on a shared context map with keys `:source`, `:opts`, `:raw-tokens`, `:tokens`, `:forms`.

### scan

```clojure
(meme.alpha.pipeline/scan ctx)
```

Tokenize source text into flat tokens with whitespace attachment. Reads `:source` from ctx, assocs `:raw-tokens`. Each token carries a `:ws` key with the leading whitespace and comments between it and the previous token. Trailing whitespace (after the last token) is stored as `:trailing-ws` metadata on the token vector. This is how the pretty-printer preserves comments.

### group

```clojure
(meme.alpha.pipeline/group ctx)
```

Collapse opaque regions (reader conditionals, namespaced maps, syntax-quote brackets) from flat tokens into composite tokens. Reads `:raw-tokens` and `:source` from ctx, assocs `:tokens`.

### parse

```clojure
(meme.alpha.pipeline/parse ctx)
```

Parse grouped tokens into Clojure forms. Reads `:tokens`, `:opts`, `:source` from ctx, assocs `:forms`.

### run

```clojure
(meme.alpha.pipeline/run source)
(meme.alpha.pipeline/run source opts)
```

Run the full pipeline: `scan → group → parse`. Returns the complete context map.

```clojure
(meme.alpha.pipeline/run "+(1 2)")
;=> {:source "+(1 2)", :opts nil,
;    :raw-tokens [...], :tokens [...], :forms [(+ 1 2)]}
```


## meme.alpha.scan.grouper

Token grouping stage. Collapses opaque-region marker tokens into single composite tokens.

### group-tokens

```clojure
(meme.alpha.scan.grouper/group-tokens tokens source)
```

Process a flat token vector, collapsing opaque regions into single tokens. Marker tokens (`:reader-cond-start`, `:namespaced-map-start`, `:syntax-quote-start`) followed by balanced delimiters are collapsed into the corresponding `-raw` composite tokens (`:reader-cond-raw`, `:namespaced-map-raw`, `:syntax-quote-raw`).

`source` is the original source text, used for reconstructing raw values via source-range extraction.


## meme.alpha.scan.source

Source-position utilities shared across pipeline stages. The tokenizer and grouper must agree on how `(line, col)` maps to character offsets — this namespace is that shared definition.

### line-col->offset

```clojure
(meme.alpha.scan.source/line-col->offset source line col)
```

Convert 1-indexed line and column to a 0-indexed character offset in `source`. Used by `attach-whitespace` (tokenizer) and `extract-source-range` (grouper) to locate token positions in the original source string. Returns the source length if the position is past the end.


## meme.alpha.runtime.cli

Unified CLI for meme. Implemented in meme syntax (`cli.meme`), loaded by a `.clj` shim. JVM/Babashka only.

### Commands

| Command | Description |
|---------|-------------|
| `meme run <file>` | Run a `.meme` file |
| `meme repl` | Start the meme REPL |
| `meme convert <file\|dir>` | Convert between `.meme` and `.clj` (direction detected from extension) |
| `meme format <file\|dir>` | Format `.meme` files via pprint (in-place by default, `--stdout` to print) |
| `meme version` | Print version |

All file commands accept directories (processed recursively) and multiple paths. `convert` and `format` accept `--stdout` to print to stdout instead of writing files.

Entry point: `-main` dispatches via `babashka.cli`. For Clojure JVM, use `-T:meme` (e.g., `clojure -T:meme run :file '"hello.meme"'`).


## meme.alpha.parse.resolve

Value resolution. Converts raw token text to Clojure values. Centralizes all host reader delegation (`read-string` calls) with consistent error wrapping and location info.

### Resolver functions

All resolvers take the raw token text and a `loc` map (`{:line N :col M}`) for error reporting:

```clojure
(meme.alpha.parse.resolve/resolve-number raw loc)        ;; "42" → 42
(meme.alpha.parse.resolve/resolve-string raw loc)        ;; "\"hi\"" → "hi"
(meme.alpha.parse.resolve/resolve-char raw loc)          ;; "\\newline" → \newline
(meme.alpha.parse.resolve/resolve-regex raw loc)         ;; "#\"\\d+\"" → #"\d+"
(meme.alpha.parse.resolve/resolve-syntax-quote raw loc)  ;; JVM: host read-string. CLJS: error.
(meme.alpha.parse.resolve/resolve-namespaced-map raw loc);; JVM: host read-string. CLJS: error.
(meme.alpha.parse.resolve/resolve-reader-cond raw loc)   ;; JVM: read with {:read-cond :preserve}. CLJS: error.
```

### resolve-auto-keyword

```clojure
(meme.alpha.parse.resolve/resolve-auto-keyword raw loc resolve-fn)
```

Resolve an auto-resolve keyword (`::foo`). If `resolve-fn` is provided, resolves at read time. Otherwise, defers to eval time via `(read-string "::foo")`.

### resolve-tagged-literal

```clojure
(meme.alpha.parse.resolve/resolve-tagged-literal tag data loc)
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
  (meme.alpha.core/meme->forms "foo(")
  (catch Exception e
    (ex-data e)))
;=> {:line 1, :col 4}
```

Error recovery is not supported — the reader stops at the first error. This is documented as future work in the PRD.

## meme.alpha.errors

Error infrastructure used by the tokenizer and reader. Portable (.cljc).

### source-context

```clojure
(meme.alpha.errors/source-context source line)
```

Extract the source line at a 1-indexed line number from `source` (a string). Returns the line text, or `nil` if out of range.

### meme-error

```clojure
(meme.alpha.errors/meme-error message opts)
```

Throw `ex-info` with a consistent error structure. `opts` is a map with:
- `:line`, `:col` — source location (appended to message as `(line N, col M)`)
- `:cause` — optional upstream exception
- `:incomplete` — when `true`, signals the REPL that more input may complete the form

All tokenizer and reader errors go through this function.

### format-error

```clojure
(meme.alpha.errors/format-error exception source)
```

Format an exception for display. Produces a multi-line string with:
1. The error message (prefixed with `"Error: "`)
2. The source line at the error location (with line-number gutter)
3. A caret (`^`) or span underline (`~~~`) pointing at the column(s) — uses `^` for single-column errors, `~` for multi-column spans when `:end-col` is present in ex-data
4. Secondary source locations with labels (when `:secondary` is present in ex-data — a sequence of `{:line :col :label}` maps)
5. A hint line (when `:hint` is present in ex-data)

If `source` is `nil`/blank or the exception lacks `:line`/`:col`, only the prefixed message is returned.
