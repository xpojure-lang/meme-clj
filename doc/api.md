# beme API Reference

## beme.core

The public API for reading and printing beme syntax, organized in three tracks:

```
         text-to-form          form-to-text
beme str ──→ beme->forms ──→ forms ──→ forms->beme ──→ beme str
clj str  ──→ clj->forms  ──→ forms ──→ forms->clj  ──→ clj str

         text-to-text (compositions)
beme str ──→ beme->clj ──→ clj str
clj str  ──→ clj->beme ──→ beme str
```

### Text-to-form track

#### beme->forms

```clojure
(beme.core/beme->forms s)
(beme.core/beme->forms s opts)
```

Read a beme source string. Returns a vector of Clojure forms. All platforms.

Options:
- `:resolve-keyword` — function that resolves auto-resolve keyword strings (`"::foo"`) to keywords at read time. When absent on JVM/Babashka, `::` keywords are deferred to eval time via `(read-string "::foo")`. Required on CLJS (errors without it, since `cljs.reader` cannot resolve `::` in the correct namespace).

```clojure
(beme->forms "+(1 2 3)")
;=> [(+ 1 2 3)]

(beme->forms "def(x 42)\nprintln(x)")
;=> [(def x 42) (println x)]
```

#### forms->beme

```clojure
(beme.core/forms->beme forms)
```

Print a sequence of Clojure forms as beme text. All platforms.

```clojure
(forms->beme ['(+ 1 2 3)])
;=> "+(1 2 3)"
```

### Form-to-text track

#### forms->clj

```clojure
(beme.core/forms->clj forms)
```

Print Clojure forms as a Clojure source string. All platforms.

```clojure
(forms->clj ['(def x 42) '(println x)])
;=> "(def x 42)\n\n(println x)"
```

#### clj->forms

```clojure
(beme.core/clj->forms clj-src)
```

Read a Clojure source string, return a vector of forms. JVM/Babashka only.

```clojure
(clj->forms "(defn f [x] (+ x 1))")
;=> [(defn f [x] (+ x 1))]
```

### Text-to-text track

#### beme->clj

```clojure
(beme.core/beme->clj beme-src)
```

Convert beme source string to Clojure source string. All platforms. Equivalent to `(forms->clj (beme->forms beme-src))`.

```clojure
(beme->clj "println(\"hello\")")
;=> "(println \"hello\")"
```

#### clj->beme

```clojure
(beme.core/clj->beme clj-src)
```

Convert a Clojure source string to beme source string. JVM/Babashka only. Equivalent to `(forms->beme (clj->forms clj-src))`.

```clojure
(clj->beme "(defn f [x] (+ x 1))")
;=> "defn(f [x] +(x 1))"
```

### run-pipeline

```clojure
(beme.core/run-pipeline source)
(beme.core/run-pipeline source opts)
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

### Deprecated aliases

- `read-beme-string` — use `beme->forms`
- `print-beme-string` — use `forms->beme`
- `clj-string->beme` — use `clj->beme`


## beme.reader

Low-level reader API.

### read-beme-string

```clojure
(beme.reader/read-beme-string s)
(beme.reader/read-beme-string s opts)
```

Read beme source string and return a vector of Clojure forms.
The underlying implementation; `beme.core/beme->forms` delegates to this.

Options:
- `:resolve-keyword` — function that resolves auto-resolve keyword strings (`"::foo"`) to keywords at read time.

### read-beme-string-from-tokens

```clojure
(beme.reader/read-beme-string-from-tokens tokens)
(beme.reader/read-beme-string-from-tokens tokens opts source)
```

Parse pre-tokenized, pre-grouped tokens into Clojure forms. Used by `beme.pipeline/parse`. Most callers should use `read-beme-string` instead.

- `tokens` — a grouped token vector (output of `beme.grouper/group-tokens`)
- `opts` — same options as `read-beme-string` (e.g., `:resolve-keyword`)
- `source` — original source text for error context (optional)


## beme.printer

Low-level printer API.

### print-form

```clojure
(beme.printer/print-form form)
```

Print a single Clojure form as beme text.

```clojure
(print-form '(+ 1 2))
;=> "+(1 2)"

(print-form '(:balance account))
;=> ":balance(account)"

(print-form '(def x 42))
;=> "def(x 42)"
```

### print-beme-string

```clojure
(beme.printer/print-beme-string forms)
```

Print a sequence of Clojure forms as beme text, separated by blank lines.


## beme.repl

### input-state

```clojure
(beme.repl/input-state s)
```

Returns the parse state of a beme input string: `:complete` (parsed successfully), `:incomplete` (unclosed delimiter — keep reading), or `:invalid` (malformed, non-recoverable error). Used internally by the REPL for multi-line input handling; also useful for editor integration.

```clojure
(input-state "+(1 2)")      ;=> :complete
(input-state "f(")          ;=> :incomplete
(input-state "(bare parens)") ;=> :invalid
```

### start

```clojure
(beme.repl/start)
(beme.repl/start opts)
```

Start the beme REPL. Reads beme syntax, evaluates as Clojure, prints results.

Options:
- `:read-line` — custom line reader function (default: `read-line`, required on ClojureScript)
- `:eval` — custom eval function (default: `eval`, required on ClojureScript)
- `:resolve-keyword` — function to resolve `::` keywords at read time (default: `clojure.core/read-string` on JVM; required on CLJS for code that uses `::` keywords)

```
$ bb beme
beme REPL. Type beme expressions, balanced input to eval. Ctrl-D to exit.
user=> +(1 2)
3
user=> map(inc [1 2 3])
(2 3 4)
```

The prompt shows the current namespace (e.g., `user=>` on JVM/Babashka, `beme=>` on ClojureScript).


## beme.run

Run `.beme` files or beme source strings.

### run-string

```clojure
(beme.run/run-string s)
(beme.run/run-string s eval-fn)
(beme.run/run-string s opts)
```

Read beme source string, eval each form, return the last result. The second argument can be an eval function (backward compatible) or an opts map.

Options (when passing a map):
- `:eval` — eval function (default: `eval`; required on CLJS)
- `:resolve-keyword` — function to resolve `::` keywords at read time (default: `clojure.core/read-string` on JVM; required on CLJS for code that uses `::` keywords)

```clojure
(run-string "def(x 42)\n+(x 1)")
;=> 43
```

### run-file

```clojure
(beme.run/run-file path)
(beme.run/run-file path eval-fn)
(beme.run/run-file path opts)
```

Read and eval a `.beme` file. Returns the last result. Uses `slurp` internally (JVM/Babashka only). Second argument follows same convention as `run-string`.

```clojure
(run-file "examples/tests/01_core_rules.beme")
```


## beme.pipeline

Explicit pipeline composition. Each stage is a `ctx → ctx` function operating on a shared context map with keys `:source`, `:opts`, `:raw-tokens`, `:tokens`, `:forms`.

### scan

```clojure
(beme.pipeline/scan ctx)
```

Tokenize source text into flat tokens. Reads `:source` from ctx, assocs `:raw-tokens`.

### group

```clojure
(beme.pipeline/group ctx)
```

Collapse opaque regions (reader conditionals, namespaced maps, syntax-quote brackets) from flat tokens into composite tokens. Reads `:raw-tokens` and `:source` from ctx, assocs `:tokens`.

### parse

```clojure
(beme.pipeline/parse ctx)
```

Parse grouped tokens into Clojure forms. Reads `:tokens`, `:opts`, `:source` from ctx, assocs `:forms`.

### run

```clojure
(beme.pipeline/run source)
(beme.pipeline/run source opts)
```

Run the full pipeline: `scan → group → parse`. Returns the complete context map.

```clojure
(beme.pipeline/run "+(1 2)")
;=> {:source "+(1 2)", :opts nil,
;    :raw-tokens [...], :tokens [...], :forms [(+ 1 2)]}
```


## beme.grouper

Token grouping stage. Collapses opaque-region marker tokens into single composite tokens.

### group-tokens

```clojure
(beme.grouper/group-tokens tokens source)
```

Process a flat token vector, collapsing opaque regions into single tokens. Marker tokens (`:reader-cond-start`, `:namespaced-map-start`, `:syntax-quote-start`) followed by balanced delimiters are collapsed into the corresponding `-raw` composite tokens (`:reader-cond-raw`, `:namespaced-map-raw`, `:syntax-quote-raw`).

`source` is the original source text, used for reconstructing raw values via source-range extraction.


## beme.resolve

Value resolution. Converts raw token text to Clojure values. Centralizes all host reader delegation (`read-string` calls) with consistent error wrapping and location info.

### Resolver functions

All resolvers take the raw token text and a `loc` map (`{:line N :col M}`) for error reporting:

```clojure
(beme.resolve/resolve-number raw loc)        ;; "42" → 42
(beme.resolve/resolve-string raw loc)        ;; "\"hi\"" → "hi"
(beme.resolve/resolve-char raw loc)          ;; "\\newline" → \newline
(beme.resolve/resolve-regex raw loc)         ;; "#\"\\d+\"" → #"\d+"
(beme.resolve/resolve-syntax-quote raw loc)  ;; JVM: host read-string. CLJS: error.
(beme.resolve/resolve-namespaced-map raw loc);; JVM: host read-string. CLJS: error.
(beme.resolve/resolve-reader-cond raw loc)   ;; JVM: read with {:read-cond :preserve}. CLJS: error.
```

### resolve-auto-keyword

```clojure
(beme.resolve/resolve-auto-keyword raw loc resolve-fn)
```

Resolve an auto-resolve keyword (`::foo`). If `resolve-fn` is provided, resolves at read time. Otherwise, defers to eval time via `(read-string "::foo")`.

### resolve-tagged-literal

```clojure
(beme.resolve/resolve-tagged-literal tag data loc)
```

Resolve a tagged literal. JVM: produces a `TaggedLiteral` object via `clojure.core/tagged-literal`. CLJS: throws an error.


## Error Handling

The beme reader fails fast on invalid input. Parse errors are thrown as `ex-info` exceptions with `:line` and `:col` data when available.

Common errors:

| Error message | Cause |
|---------------|-------|
| `Expected :close-paren but got EOF` | Unclosed `(` |
| `Unquote (~) outside syntax-quote` | `~` only valid inside `` ` `` |

```clojure
(try
  (beme.core/beme->forms "foo(")
  (catch Exception e
    (ex-data e)))
;=> {:line 1, :col 4}
```

Error recovery is not supported — the reader stops at the first error. This is documented as future work in the PRD.

## beme.errors

Error infrastructure used by the tokenizer and reader. Portable (.cljc).

### source-context

```clojure
(beme.errors/source-context source line)
```

Extract the source line at a 1-indexed line number from `source` (a string). Returns the line text, or `nil` if out of range.

### beme-error

```clojure
(beme.errors/beme-error message opts)
```

Throw `ex-info` with a consistent error structure. `opts` is a map with:
- `:line`, `:col` — source location (appended to message as `(line N, col M)`)
- `:cause` — optional upstream exception
- `:incomplete` — when `true`, signals the REPL that more input may complete the form

All tokenizer and reader errors go through this function.

### format-error

```clojure
(beme.errors/format-error exception source)
```

Format an exception for display. Produces a multi-line string with:
1. The error message (prefixed with `"Error: "`)
2. The source line at the error location (with line-number gutter)
3. A caret (`^`) or span underline (`~~~`) pointing at the column(s) — uses `^` for single-column errors, `~` for multi-column spans when `:end-col` is present in ex-data
4. Secondary source locations with labels (when `:secondary` is present in ex-data — a sequence of `{:line :col :label}` maps)
5. A hint line (when `:hint` is present in ex-data)

If `source` is `nil`/blank or the exception lacks `:line`/`:col`, only the prefixed message is returned.
