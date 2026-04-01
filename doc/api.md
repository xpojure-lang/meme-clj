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
- `:read-cond` — `:preserve` to return `ReaderConditional` objects instead of evaluating reader conditionals. Default: evaluate `#?` for the current platform. Use `:preserve` for lossless `clj->meme->clj` roundtrips of `.cljc` files.
- `:resolve-symbol` — function that resolves symbols during syntax-quote expansion (e.g., `foo` → `my.ns/foo`). On JVM/Babashka, `run-string`/`run-file`/`start` inject a default that matches Clojure's `SyntaxQuoteReader` (see `meme.alpha.runtime.resolve/default-resolve-symbol`). When calling `meme->forms` directly, symbols in syntax-quote are left unqualified unless this option is provided. On CLJS, no default is available.

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

#### format-meme

```clojure
(meme.alpha.core/format-meme forms)
(meme.alpha.core/format-meme forms opts)
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
(meme.alpha.core/meme->clj meme-src)
(meme.alpha.core/meme->clj meme-src opts)
```

Convert meme source string to Clojure source string. All platforms. Equivalent to `(forms->clj (meme->forms meme-src opts))`.

Options: same as `meme->forms` (`:resolve-keyword`, `:read-cond`).

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

Run the full pipeline: source → scan → parse. Returns a context map with intermediate state. All platforms. Useful for tooling that needs access to raw tokens, tokens, or parsed forms.

```clojure
(run-pipeline "foo(1 2)")
;=> {:source "foo(1 2)"
;    :opts nil
;    :raw-tokens [...tokenizer output with :ws...]
;    :tokens [...same as :raw-tokens...]
;    :forms [(foo 1 2)]}
```

## meme.alpha.parse.reader

Low-level reader API.

### read-meme-string-from-tokens

```clojure
(meme.alpha.parse.reader/read-meme-string-from-tokens tokens)
(meme.alpha.parse.reader/read-meme-string-from-tokens tokens opts source)
```

Parse a token vector into Clojure forms. Used by `meme.alpha.pipeline/step-parse`. Most callers should use `meme.alpha.core/meme->forms` instead.

- `tokens` — a token vector (output of `meme.alpha.scan.tokenizer/tokenize`)
- `opts` — same options as `meme->forms` (e.g., `:resolve-keyword`)
- `source` — original source text for error context (optional)


## meme.alpha.emit.printer

Low-level Doc tree builder. Most callers should use `formatter.flat` or `formatter.canon` instead.

### to-doc

```clojure
(meme.alpha.emit.printer/to-doc form mode)
```

Convert a Clojure form to a Wadler-Lindig Doc tree. `mode` is `:meme` (call notation) or `:clj` (standard Clojure with reader sugar). The Doc tree is passed to `render/layout` for final string output.

### extract-comments

```clojure
(meme.alpha.emit.printer/extract-comments ws)
```

Extract comment lines from a `:ws` metadata string. Returns a vector of trimmed comment strings, or nil.


## meme.alpha.emit.formatter.flat

Flat (single-line) formatter. Composes printer + render at infinite width.

### format-form

```clojure
(meme.alpha.emit.formatter.flat/format-form form)
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
(meme.alpha.emit.formatter.flat/format-forms forms)
```

Format a sequence of Clojure forms as flat meme text, separated by blank lines.

### format-clj

```clojure
(meme.alpha.emit.formatter.flat/format-clj forms)
```

Format Clojure forms as Clojure text with reader sugar (`'quote`, `@deref`, `#'var`).


## meme.alpha.emit.formatter.canon

Canonical (width-aware) formatter. Composes printer + render at target width. Used by `meme format` CLI.

### format-form

```clojure
(meme.alpha.emit.formatter.canon/format-form form)
(meme.alpha.emit.formatter.canon/format-form form opts)
```

Format a single Clojure form as canonical meme text. Width-aware — uses indented multi-line layout for forms that exceed the target width. Preserves comments from `:ws` metadata.

Options:
- `:width` — target line width (default: 80)

### format-forms

```clojure
(meme.alpha.emit.formatter.canon/format-forms forms)
(meme.alpha.emit.formatter.canon/format-forms forms opts)
```

Format a sequence of Clojure forms as canonical meme text, separated by blank lines. Preserves comments from `:ws` metadata, including trailing comments after the last form.


## meme.alpha.runtime.repl

### input-state

```clojure
(meme.alpha.runtime.repl/input-state s)
(meme.alpha.runtime.repl/input-state s opts)
```

Returns the parse state of a meme input string: `:complete` (parsed successfully), `:incomplete` (unclosed delimiter — keep reading), or `:invalid` (malformed, non-recoverable error). Used internally by the REPL for multi-line input handling; also useful for editor integration.

The optional `opts` map is forwarded to `pipeline/run` — useful for callers that need `::` keywords or custom parsers to be resolved during input validation.

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
- `:prelude` — vector of forms to eval before the first user input (e.g., guest language standard library)

On JVM/Babashka, `:resolve-symbol` is automatically injected (matching Clojure's syntax-quote resolution) unless explicitly provided.

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
- `:prelude` — vector of forms to eval before user code (e.g., guest language standard library)
- `:rewrite-rules` — vector of rewrite rules applied to forms after expansion (see `meme.alpha.rewrite`)
- `:rewrite-max-iters` — max rewrite iterations per form (default: 100)

On JVM/Babashka, `:resolve-symbol` is automatically injected (matching Clojure's syntax-quote resolution) unless explicitly provided.

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

Automatically detects guest languages from file extension via `meme.alpha.platform.registry/resolve-lang`. If a registered language matches the extension, its prelude, rules, and/or custom parser are merged into the run options.

```clojure
(run-file "test/examples/tests/01_core_rules.meme")
```


## meme.alpha.pipeline

Explicit pipeline composition. Each stage is a `ctx → ctx` function operating on a shared context map with keys `:source`, `:opts`, `:raw-tokens`, `:tokens`, `:forms`.

### step-scan

```clojure
(meme.alpha.pipeline/step-scan ctx)
```

Tokenize source text into tokens with whitespace attachment. Reads `:source` from ctx, assocs both `:tokens` and `:raw-tokens` (identical; `:raw-tokens` retained for backward compatibility). Each token carries a `:ws` key with the leading whitespace and comments between it and the previous token. Trailing whitespace (after the last token) is stored as `:trailing-ws` metadata on the token vector. This is how the pretty-printer preserves comments.

### step-parse

```clojure
(meme.alpha.pipeline/step-parse ctx)
```

Parse tokens into Clojure forms. Reads `:tokens`, `:opts`, `:source` from ctx, assocs `:forms`.

### step-expand-syntax-quotes

```clojure
(meme.alpha.pipeline/step-expand-syntax-quotes ctx)
```

Expand syntax-quote AST nodes (`MemeSyntaxQuote`) into plain Clojure forms (`seq`/`concat`/`list`). Also unwraps `MemeRaw` values. Only needed before eval — tooling paths work with AST nodes directly.

Note: `run` intentionally omits this stage so tooling can access the unexpanded forms. Runtime paths (`run-string`, `run-file`) include `step-expand-syntax-quotes` in their pipeline.

### step-rewrite

```clojure
(meme.alpha.pipeline/step-rewrite ctx)
```

Apply rewrite rules to `:forms`. Rules come from `(get-in ctx [:opts :rewrite-rules])`. No-op if no rules are provided. Each form is rewritten independently, bottom-up to fixpoint (bounded by `:rewrite-max-iters`, default 100).

Used by `run-string` for guest language transforms. Not included in `pipeline/run` (tooling path).

### run

```clojure
(meme.alpha.pipeline/run source)
(meme.alpha.pipeline/run source opts)
```

Run the pipeline: `step-scan → step-parse`. Returns the complete context map. Does **not** include `step-expand-syntax-quotes` — forms contain AST nodes (`MemeSyntaxQuote`, `MemeRaw`) for tooling access. Call `step-expand-syntax-quotes` separately if you need eval-ready forms.

```clojure
(meme.alpha.pipeline/run "+(1 2)")
;=> {:source "+(1 2)", :opts nil,
;    :raw-tokens [...], :tokens [...], :forms [(+ 1 2)]}
```


## meme.alpha.pipeline.contract

Formal contract for the pipeline context map. Provides `clojure.spec.alpha` specs for the context at each stage boundary, a toggleable runtime validator, and explain functions for debugging and tooling.

### Specs

| Spec | Describes |
|------|-----------|
| `::token` | A single token map: `:type`, `:value`, `:line`, `:col`, `:offset` (required); `:end-line`, `:end-col`, `:end-offset`, `:ws` (optional) |
| `::token-vector` | Vector of tokens |
| `::opts` | Reader options map (nilable): `:resolve-keyword`, `:read-cond`, `:resolve-symbol` |
| `::forms` | Vector of parsed forms (any Clojure value) |
| `::ctx-input` | Context as provided by the caller: `{:source string, :opts map?}` |
| `::ctx-after-scan` | Context after `scan`: adds `:raw-tokens` and `:tokens` |
| `::ctx-after-parse` | Context after `parse`: adds `:forms` |
| `::ctx-after-expand` | Context after `expand`: `:forms` replaced with expanded forms |

### \*validate\*

```clojure
meme.alpha.pipeline.contract/*validate*
```

Dynamic var. When bound to `true`, pipeline stages validate context maps at input and output against the specs above. Default: `false` (zero overhead).

```clojure
(binding [meme.alpha.pipeline.contract/*validate* true]
  (pipeline/run "+(1 2)"))
```

### validate!

```clojure
(meme.alpha.pipeline.contract/validate! stage phase ctx)
```

Check `ctx` against the contract for the given `stage` (`:scan`, `:parse`, `:expand`) and `phase` (`:input` or `:output`). Throws `ex-info` with `:stage`, `:phase`, and `:problems` in ex-data. No-op when `*validate*` is false.

### explain-context

```clojure
(meme.alpha.pipeline.contract/explain-context :scan :input {:source 42})
;=> "42 - failed: string? in: [:source] at: [:source] ..."
```

Return a human-readable explanation string, or `nil` if valid. Always runs (not gated by `*validate*`).

### valid?

```clojure
(meme.alpha.pipeline.contract/valid? :parse :output ctx) ;=> true/false
```

Check without throwing. Not gated by `*validate*`.

### Guest language usage

A guest parser replacing the `parse` stage must produce a context map conforming to `::ctx-after-parse`:

```clojure
(require '[meme.alpha.pipeline.contract :as contract])

(binding [contract/*validate* true]
  (let [ctx (-> {:source src :opts opts}
                pipeline/step-scan
                my-custom-parse)]
    ctx))
```


## meme.alpha.scan.source

Source-position utilities shared across pipeline stages. Defines how `(line, col)` maps to character offsets — used by the tokenizer's `attach-whitespace` pass.

### line-col->offset

```clojure
(meme.alpha.scan.source/line-col->offset source line col)
```

Convert 1-indexed line and column to a 0-indexed character offset in `source`. Used by `attach-whitespace` (tokenizer) to locate token positions in the original source string. Returns the source length if the position is past the end.


## meme.alpha.runtime.cli

Unified CLI for meme. Implemented in meme syntax (`cli.meme`), loaded by a `.clj` shim. JVM/Babashka only.

### Commands

| Command | Description |
|---------|-------------|
| `meme run <file>` | Run a `.meme` file |
| `meme repl` | Start the meme REPL |
| `meme convert <file\|dir>` | Convert between `.meme` and `.clj` (direction detected from extension) |
| `meme convert --lang meme-classic\|meme-rewrite\|meme-trs` | Select conversion lang (default: meme-classic) |
| `meme format <file\|dir>` | Format `.meme` files via canonical formatter (in-place by default, `--stdout` to print, `--check` for CI) |
| `meme inspect [--lang]` | Show lang info and supported commands |
| `meme version` | Print version |

All file commands accept directories (processed recursively) and multiple paths. `convert` and `format` accept `--stdout` to print to stdout instead of writing files.

Entry point: `-main` dispatches via `babashka.cli`. For Clojure JVM, use `-T:meme` (e.g., `clojure -T:meme run :file '"hello.meme"'`).


## meme.alpha.parse.resolve

Native value resolution. Converts raw token text to Clojure values — no `read-string` delegation. Consistent error wrapping and location info.

### Resolver functions

All resolvers take the raw token text and a `loc` map (`{:line N :col M}`) for error reporting:

```clojure
(meme.alpha.parse.resolve/resolve-number raw loc)        ;; "42" → 42
(meme.alpha.parse.resolve/resolve-string raw loc)        ;; "\"hi\"" → "hi"
(meme.alpha.parse.resolve/resolve-char raw loc)          ;; "\\newline" → \newline
(meme.alpha.parse.resolve/resolve-regex raw loc)         ;; "#\"\\d+\"" → #"\d+"
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


## meme.alpha.rewrite

Pattern matching and term rewriting engine. Used by `step-rewrite` for guest language transforms. Also usable directly for symbolic computation.

### Pattern matching

```clojure
(meme.alpha.rewrite/match-pattern pattern expr)
(meme.alpha.rewrite/match-pattern pattern expr bindings)
```

Match a pattern against an expression. Returns a bindings map `{symbol value}` on success, `nil` on failure.

Pattern syntax:
- `?x` — match any single value, bind to `x`
- `??x` — splice variable, match zero or more elements in a sequence
- `_` — wildcard, match anything, no binding
- Literal values — match themselves
- Lists/vectors — match structurally

```clojure
(match-pattern '?x 42)           ;=> {x 42}
(match-pattern '(f ?x) '(f 1))   ;=> {x 1}
(match-pattern '(+ ??xs) '(+ 1 2 3)) ;=> {xs (1 2 3)}
```

### substitute

```clojure
(meme.alpha.rewrite/substitute template bindings)
```

Replace pattern variables in `template` with values from `bindings`. Splice variables (`??x`) splice their seq into the parent list.

### Rules

```clojure
(meme.alpha.rewrite/make-rule name pattern replacement)
(meme.alpha.rewrite/make-rule name pattern replacement guard)
(meme.alpha.rewrite/rule pattern replacement)
(meme.alpha.rewrite/rule pattern replacement guard)
```

Create a rewrite rule. `guard` is an optional `(fn [bindings] bool)`.

```clojure
(meme.alpha.rewrite/apply-rule rule expr)   ;=> rewritten expr or nil
(meme.alpha.rewrite/apply-rules rules expr) ;=> first matching rule's result or nil
```

### Rewriting strategies

```clojure
(meme.alpha.rewrite/rewrite rules expr)
(meme.alpha.rewrite/rewrite rules expr max-iters)
```

Apply rules repeatedly (bottom-up) until fixpoint or `max-iters` (default 100). Returns the final expression.

```clojure
(meme.alpha.rewrite/rewrite-once rules expr)  ;=> [changed? result]
(meme.alpha.rewrite/rewrite-top rules expr)    ;=> top-level only, to fixpoint
```

### DSL macros (JVM/Babashka only)

```clojure
(meme.alpha.rewrite/defrule identity-plus (+ ?a 0) => ?a)
(meme.alpha.rewrite/defrule-guard pos-check ?x => :pos (fn [b] (pos? (b 'x))))
(meme.alpha.rewrite/ruleset
  (+ ?a 0) => ?a
  (* ?a 1) => ?a)
```


## meme.alpha.rewrite.rules

Predefined rule sets for S-expression ↔ M-expression transformations.

### s->m-rules

Rules that tag S-expression calls as `m-call` nodes. List patterns only match lists (not vectors).

### m->s-rules

Rules that convert `m-call` nodes back to S-expression lists.

### tree->s-rules

Rules that flatten tagged tree nodes to Clojure forms.

### transform-structures

```clojure
(meme.alpha.rewrite.rules/transform-structures form)
```

Walk a tree and convert structural tags to Clojure data/AST nodes.


## meme.alpha.rewrite.tree

Token vector → tagged tree builder for the rewrite-based pipeline.

### tokens->tree

```clojure
(meme.alpha.rewrite.tree/tokens->tree tokens)
```

Convert a flat token vector to a tagged tree. Returns a vector of top-level forms.

### build-tree

```clojure
(meme.alpha.rewrite.tree/build-tree tokens pos)
```

Build a tagged tree node from tokens starting at `pos`. Returns `[node new-pos]`.

### rewrite-parser

```clojure
(meme.alpha.rewrite.tree/rewrite-parser tokens opts source)
```

Parser that conforms to the pipeline contract: `(fn [tokens opts source] → forms)`. Uses the rewrite-based pipeline: tokens → tagged tree → rules → structures. Drop-in replacement for `meme.alpha.parse.reader/read-meme-string-from-tokens`.


## meme.alpha.rewrite.emit

Serializes m-call tagged trees to meme text.

### emit

```clojure
(meme.alpha.rewrite.emit/emit form)
```

Convert a form (with m-call tags) to meme text string.

### emit-forms

```clojure
(meme.alpha.rewrite.emit/emit-forms forms)
```

Emit a sequence of top-level forms as meme text, separated by newlines.


## meme.alpha.platform.registry

Guest language registration. Maps language names (keywords) to configurations. Used by `run-file` for automatic language dispatch based on file extension.

### register!

```clojure
(meme.alpha.platform.registry/register! lang-name config)
```

Register a guest language. `lang-name` is a keyword. Config keys:
- `:extension` — file extension (e.g. `".calc"`)
- `:prelude-file` — path to prelude `.meme` file (eval'd before user code)
- `:rules-file` — path to rules `.meme` file (eval'd, must return rule vector)
- `:prelude` — prelude forms (alternative to `:prelude-file`)
- `:rules` — rule vector (alternative to `:rules-file`)
- `:parser` — custom parser fn: `(fn [tokens opts source] forms-vector)`. If nil, uses the default meme parser.

### resolve-lang

```clojure
(meme.alpha.platform.registry/resolve-lang path)
```

Given a file path, determine the guest language from its extension. Returns the language name keyword, or `nil` for unrecognized extensions. `.meme` files return `nil` (default meme, no guest language).

### lang-config

```clojure
(meme.alpha.platform.registry/lang-config lang-name)
```

Get the config for a registered language. Returns `nil` if not found.

### registered-langs

```clojure
(meme.alpha.platform.registry/registered-langs)
```

List all registered language names (keywords).

### clear!

```clojure
(meme.alpha.platform.registry/clear!)
```

Clear all registered languages. For testing.

## meme.alpha.convert

Unified dispatch for three conversion langs: `:meme-classic`, `:meme-rewrite`, `:meme-trs`.
Legacy aliases `:classic`, `:rewrite`, `:ts-trs` are also accepted.

### meme->clj

```clojure
(meme.alpha.convert/meme->clj src)
(meme.alpha.convert/meme->clj src lang-name)
```

Convert meme source to Clojure source using the named lang. Default: `:meme-classic`.

- `:meme-classic` — recursive-descent parser + Wadler-Lindig printer (default)
- `:meme-rewrite` — tree builder + `meme.alpha.rewrite` rules
- `:meme-trs` — token-stream term rewriting

All platforms.

### clj->meme

```clojure
(meme.alpha.convert/clj->meme src)
(meme.alpha.convert/clj->meme src lang-name)
```

Convert Clojure source to meme source using the named lang. Default: `:meme-classic`. JVM/Babashka only.
