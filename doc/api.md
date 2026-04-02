# meme API Reference

All namespaces live under `meme`. The API is pre-1.0 and may change.

## meme.core

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
(meme.core/meme->forms s)
(meme.core/meme->forms s opts)
```

Read a meme source string. Returns a vector of Clojure forms. All platforms.

Options:
- `:resolve-keyword` — function that resolves auto-resolve keyword strings (`"::foo"`) to keywords at read time. When absent on JVM/Babashka, `::` keywords are deferred to eval time via `(read-string "::foo")`. Required on CLJS (errors without it, since `cljs.reader` cannot resolve `::` in the correct namespace).
- `:read-cond` — `:preserve` to return `ReaderConditional` objects instead of evaluating reader conditionals. Default: evaluate `#?` for the current platform. Use `:preserve` for lossless `clj->meme->clj` roundtrips of `.cljc` files.
- `:resolve-symbol` — function that resolves symbols during syntax-quote expansion (e.g., `foo` → `my.ns/foo`). On JVM/Babashka, `run-string`/`run-file`/`start` inject a default that matches Clojure's `SyntaxQuoteReader` (see `meme.runtime.resolve/default-resolve-symbol`). When calling `meme->forms` directly, symbols in syntax-quote are left unqualified unless this option is provided. On CLJS, no default is available.

```clojure
(meme->forms "+(1 2 3)")
;=> [(+ 1 2 3)]

(meme->forms "def(x 42)\nprintln(x)")
;=> [(def x 42) (println x)]
```

**Note:** `meme->forms` may return internal record types for forms that preserve source notation: `MemeRaw` (for hex numbers, unicode escapes, etc.) wraps a `:value` and `:raw` text; `MemeAutoKeyword` (for `::` keywords) wraps a `:raw` string. These are unwrapped to plain values by `step-expand-syntax-quotes` (which `run-string`/`run-file` call before eval). If you need plain Clojure values from `meme->forms`, compose with `meme.stages/step-expand-syntax-quotes`.

#### forms->meme

```clojure
(meme.core/forms->meme forms)
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
(meme.core/forms->clj forms)
```

Print Clojure forms as a Clojure source string. All platforms.

```clojure
(forms->clj ['(def x 42) '(println x)])
;=> "(def x 42)\n\n(println x)"
```

#### clj->forms

```clojure
(meme.core/clj->forms clj-src)
```

Read a Clojure source string, return a vector of forms. JVM/Babashka only.

```clojure
(clj->forms "(defn f [x] (+ x 1))")
;=> [(defn f [x] (+ x 1))]
```

### Pretty-printing

#### format-meme

```clojure
(meme.core/format-meme forms)
(meme.core/format-meme forms opts)
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
(meme.core/meme->clj meme-src)
(meme.core/meme->clj meme-src opts)
```

Convert meme source string to Clojure source string. All platforms. Equivalent to `(forms->clj (meme->forms meme-src opts))`.

Options: same as `meme->forms` (`:resolve-keyword`, `:read-cond`).

```clojure
(meme->clj "println(\"hello\")")
;=> "(println \"hello\")"
```

#### clj->meme

```clojure
(meme.core/clj->meme clj-src)
```

Convert a Clojure source string to meme source string. JVM/Babashka only. Equivalent to `(forms->meme (clj->forms clj-src))`.

**Known limitation:** Clojure's reader expands reader sugar before meme sees the forms. `'(f x)` becomes `(quote (f x))` → `quote(f(x))` instead of `'f(x)`. Similarly, `@atom` → `clojure.core/deref(atom)`, and `#(+ % 1)` → `fn*([p1__N#] +(p1__N# 1))`. The `meme->clj->meme` roundtrip preserves semantics but not notation for these forms.

```clojure
(clj->meme "(defn f [x] (+ x 1))")
;=> "defn(f [x] +(x 1))"
```

### run-stages

```clojure
(meme.core/run-stages source)
(meme.core/run-stages source opts)
```

Run the full pipeline: source → scan → parse. Returns a context map with intermediate state. All platforms. Useful for tooling that needs access to raw tokens, tokens, or parsed forms.

```clojure
(run-stages "foo(1 2)")
;=> {:source "foo(1 2)"
;    :opts nil
;    :raw-tokens [...tokenizer output with :ws...]
;    :tokens [...same as :raw-tokens...]
;    :forms [(foo 1 2)]}
```

## meme.parse.reader

Low-level reader API.

### read-meme-string-from-tokens

```clojure
(meme.parse.reader/read-meme-string-from-tokens tokens)
(meme.parse.reader/read-meme-string-from-tokens tokens opts source)
```

Parse a token vector into Clojure forms. Used by `meme.stages/step-parse`. Most callers should use `meme.core/meme->forms` instead.

- `tokens` — a token vector (output of `meme.scan.tokenizer/tokenize`)
- `opts` — same options as `meme->forms` (e.g., `:resolve-keyword`)
- `source` — original source text for error context (optional)


## meme.emit.printer

Low-level Doc tree builder. Most callers should use `formatter.flat` or `formatter.canon` instead.

### to-doc

```clojure
(meme.emit.printer/to-doc form mode)
```

Convert a Clojure form to a Wadler-Lindig Doc tree. `mode` is `:meme` (call notation) or `:clj` (standard Clojure with reader sugar). The Doc tree is passed to `render/layout` for final string output.

### extract-comments

```clojure
(meme.emit.printer/extract-comments ws)
```

Extract comment lines from a `:ws` metadata string. Returns a vector of trimmed comment strings, or nil.


## meme.emit.formatter.flat

Flat (single-line) formatter. Composes printer + render at infinite width.

### format-form

```clojure
(meme.emit.formatter.flat/format-form form)
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
(meme.emit.formatter.flat/format-forms forms)
```

Format a sequence of Clojure forms as flat meme text, separated by blank lines.

### format-clj

```clojure
(meme.emit.formatter.flat/format-clj forms)
```

Format Clojure forms as Clojure text with reader sugar (`'quote`, `@deref`, `#'var`).


## meme.emit.formatter.canon

Canonical (width-aware) formatter. Composes printer + render at target width. Used by `meme format` CLI.

### format-form

```clojure
(meme.emit.formatter.canon/format-form form)
(meme.emit.formatter.canon/format-form form opts)
```

Format a single Clojure form as canonical meme text. Width-aware — uses indented multi-line layout for forms that exceed the target width. Preserves comments from `:ws` metadata.

Options:
- `:width` — target line width (default: 80)

### format-forms

```clojure
(meme.emit.formatter.canon/format-forms forms)
(meme.emit.formatter.canon/format-forms forms opts)
```

Format a sequence of Clojure forms as canonical meme text, separated by blank lines. Preserves comments from `:ws` metadata, including trailing comments after the last form.


## meme.runtime.repl

### input-state

```clojure
(meme.runtime.repl/input-state s)
(meme.runtime.repl/input-state s opts)
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
(meme.runtime.repl/start)
(meme.runtime.repl/start opts)
```

Start the meme REPL. Reads meme syntax, evaluates as Clojure, prints results.

Options:
- `:read-line` — custom line reader function (default: `read-line`, required on ClojureScript)
- `:eval` — custom eval function (default: `eval`, required on ClojureScript)
- `:resolve-keyword` — function to resolve `::` keywords at read time (default: `clojure.core/read-string` on JVM; required on CLJS for code that uses `::` keywords)
- `:prelude` — vector of forms to eval before the first user input (e.g., guest language standard library)

On JVM/Babashka, `:resolve-symbol` is automatically injected (matching Clojure's syntax-quote resolution) unless explicitly provided.

```
$ bb meme repl
meme REPL. Type meme expressions, balanced input to eval. Ctrl-D to exit.
user=> +(1 2)
3
user=> map(inc [1 2 3])
(2 3 4)
```

The prompt shows the current namespace (e.g., `user=>` on JVM/Babashka, `meme=>` on ClojureScript).


## meme.runtime.run

Run `.meme` files or meme source strings.

### run-string

```clojure
(meme.runtime.run/run-string s)
(meme.runtime.run/run-string s eval-fn)
(meme.runtime.run/run-string s opts)
```

Read meme source string, eval each form, return the last result. Strips leading `#!` shebang lines before parsing. The second argument can be an eval function (backward compatible) or an opts map.

Options (when passing a map):
- `:eval` — eval function (default: `eval`; required on CLJS)
- `:resolve-keyword` — function to resolve `::` keywords at read time (default: none — `::` keywords resolve at eval time in the file's declared namespace. Required on CLJS for code that uses `::` keywords)
- `:prelude` — vector of forms to eval before user code (e.g., guest language standard library)
- `:rewrite-rules` — vector of rewrite rules applied to forms after expansion (see `meme.rewrite`)
- `:rewrite-max-iters` — max rewrite iterations per form (default: 100)

On JVM/Babashka, `:resolve-symbol` is automatically injected (matching Clojure's syntax-quote resolution) unless explicitly provided.

```clojure
(run-string "def(x 42)\n+(x 1)")
;=> 43
```

### run-file

```clojure
(meme.runtime.run/run-file path)
(meme.runtime.run/run-file path eval-fn)
(meme.runtime.run/run-file path opts)
```

Read and eval a `.meme` file. Returns the last result. Uses `slurp` internally (JVM/Babashka only). Second argument follows same convention as `run-string`.

Automatically detects guest languages from file extension via `meme.lang/resolve-by-extension`. If a registered lang matches the extension, its `:run` function handles prelude, rules, and custom parser.

```clojure
(run-file "test/examples/tests/01_core_rules.meme")
```


## meme.stages

Explicit pipeline composition. Each stage is a `ctx → ctx` function operating on a shared context map with keys `:source`, `:opts`, `:raw-tokens`, `:tokens`, `:forms`.

### step-scan

```clojure
(meme.stages/step-scan ctx)
```

Tokenize source text into tokens with whitespace attachment. Reads `:source` from ctx, assocs both `:tokens` and `:raw-tokens` (identical; `:raw-tokens` retained for backward compatibility). Each token carries a `:ws` key with the leading whitespace and comments between it and the previous token. Trailing whitespace (after the last token) is stored as `:trailing-ws` metadata on the token vector. This is how the pretty-printer preserves comments.

### step-parse

```clojure
(meme.stages/step-parse ctx)
```

Parse tokens into Clojure forms. Reads `:tokens`, `:opts`, `:source` from ctx, assocs `:forms`.

### step-expand-syntax-quotes

```clojure
(meme.stages/step-expand-syntax-quotes ctx)
```

Expand syntax-quote AST nodes (`MemeSyntaxQuote`) into plain Clojure forms (`seq`/`concat`/`list`). Also unwraps `MemeRaw` values. Only needed before eval — tooling paths work with AST nodes directly.

Note: `run` intentionally omits this stage so tooling can access the unexpanded forms. Runtime paths (`run-string`, `run-file`) include `step-expand-syntax-quotes` in their pipeline.

### step-rewrite

```clojure
(meme.stages/step-rewrite ctx)
```

Apply rewrite rules to `:forms`. Rules come from `(get-in ctx [:opts :rewrite-rules])`. No-op if no rules are provided. Each form is rewritten independently, bottom-up to fixpoint (bounded by `:rewrite-max-iters`, default 100).

Used by `run-string` for guest language transforms. Not included in `stages/run` (tooling path).

### run

```clojure
(meme.stages/run source)
(meme.stages/run source opts)
```

Run the pipeline: `step-scan → step-parse`. Returns the complete context map. Does **not** include `step-expand-syntax-quotes` — forms contain AST nodes (`MemeSyntaxQuote`, `MemeRaw`) for tooling access. Call `step-expand-syntax-quotes` separately if you need eval-ready forms.

```clojure
(meme.stages/run "+(1 2)")
;=> {:source "+(1 2)", :opts nil,
;    :raw-tokens [...], :tokens [...], :forms [(+ 1 2)]}
```


## meme.stages.contract

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
meme.stages.contract/*validate*
```

Dynamic var. When bound to `true`, pipeline stages validate context maps at input and output against the specs above. Default: `false` (zero overhead).

```clojure
(binding [meme.stages.contract/*validate* true]
  (stages/run "+(1 2)"))
```

### validate!

```clojure
(meme.stages.contract/validate! stage phase ctx)
```

Check `ctx` against the contract for the given `stage` (`:scan`, `:parse`, `:expand`) and `phase` (`:input` or `:output`). Throws `ex-info` with `:stage`, `:phase`, and `:problems` in ex-data. No-op when `*validate*` is false.

### explain-context

```clojure
(meme.stages.contract/explain-context :scan :input {:source 42})
;=> "42 - failed: string? in: [:source] at: [:source] ..."
```

Return a human-readable explanation string, or `nil` if valid. Always runs (not gated by `*validate*`).

### valid?

```clojure
(meme.stages.contract/valid? :parse :output ctx) ;=> true/false
```

Check without throwing. Not gated by `*validate*`.

### Guest language usage

A guest parser replacing the `parse` stage must produce a context map conforming to `::ctx-after-parse`:

```clojure
(require '[meme.stages.contract :as contract])

(binding [contract/*validate* true]
  (let [ctx (-> {:source src :opts opts}
                stages/step-scan
                my-custom-parse)]
    ctx))
```


## meme.scan.source

Source-position utilities shared across pipeline stages. Defines how `(line, col)` maps to character offsets — used by the tokenizer's `attach-whitespace` pass.

### line-col->offset

```clojure
(meme.scan.source/line-col->offset source line col)
```

Convert 1-indexed line and column to a 0-indexed character offset in `source`. Used by `attach-whitespace` (tokenizer) to locate token positions in the original source string. Returns the source length if the position is past the end.


## meme.runtime.cli

Unified CLI for meme. Implemented in meme syntax (`cli.meme`), loaded by a `.clj` shim. JVM/Babashka only.

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

All file commands accept directories (processed recursively) and multiple paths. `to-clj`, `to-meme`, and `format` accept `--stdout` to print to stdout instead of writing files. Use `--lang` to select a lang backend (default: meme-classic).

Entry point: `-main` dispatches via `babashka.cli`. For Clojure JVM, use `-T:meme` (e.g., `clojure -T:meme run :file '"hello.meme"'`).


## meme.parse.resolve

Native value resolution. Converts raw token text to Clojure values — no `read-string` delegation. Consistent error wrapping and location info.

### Resolver functions

All resolvers take the raw token text and a `loc` map (`{:line N :col M}`) for error reporting:

```clojure
(meme.parse.resolve/resolve-number raw loc)        ;; "42" → 42
(meme.parse.resolve/resolve-string raw loc)        ;; "\"hi\"" → "hi"
(meme.parse.resolve/resolve-char raw loc)          ;; "\\newline" → \newline
(meme.parse.resolve/resolve-regex raw loc)         ;; "#\"\\d+\"" → #"\d+"
```

### resolve-auto-keyword

```clojure
(meme.parse.resolve/resolve-auto-keyword raw loc resolve-fn)
```

Resolve an auto-resolve keyword (`::foo`). If `resolve-fn` is provided, resolves at read time. Otherwise, defers to eval time via `(read-string "::foo")`.

### resolve-tagged-literal

```clojure
(meme.parse.resolve/resolve-tagged-literal tag data loc)
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
  (meme.core/meme->forms "foo(")
  (catch Exception e
    (ex-data e)))
;=> {:line 1, :col 4}
```

Error recovery is not supported — the reader stops at the first error. This is documented as future work in the PRD.

## meme.errors

Error infrastructure used by the tokenizer and reader. Portable (.cljc).

### source-context

```clojure
(meme.errors/source-context source line)
```

Extract the source line at a 1-indexed line number from `source` (a string). Returns the line text, or `nil` if out of range.

### meme-error

```clojure
(meme.errors/meme-error message opts)
```

Throw `ex-info` with a consistent error structure. `opts` is a map with:
- `:line`, `:col` — source location (appended to message as `(line N, col M)`)
- `:cause` — optional upstream exception
- `:incomplete` — when `true`, signals the REPL that more input may complete the form

All tokenizer and reader errors go through this function.

### format-error

```clojure
(meme.errors/format-error exception source)
```

Format an exception for display. Produces a multi-line string with:
1. The error message (prefixed with `"Error: "`)
2. The source line at the error location (with line-number gutter)
3. A caret (`^`) or span underline (`~~~`) pointing at the column(s) — uses `^` for single-column errors, `~` for multi-column spans when `:end-col` is present in ex-data
4. Secondary source locations with labels (when `:secondary` is present in ex-data — a sequence of `{:line :col :label}` maps)
5. A hint line (when `:hint` is present in ex-data)

If `source` is `nil`/blank or the exception lacks `:line`/`:col`, only the prefixed message is returned.


## meme.rewrite

Pattern matching and term rewriting engine. Used by `step-rewrite` for guest language transforms. Also usable directly for symbolic computation.

### Pattern matching

```clojure
(meme.rewrite/match-pattern pattern expr)
(meme.rewrite/match-pattern pattern expr bindings)
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
(meme.rewrite/substitute template bindings)
```

Replace pattern variables in `template` with values from `bindings`. Splice variables (`??x`) splice their seq into the parent list.

### Rules

```clojure
(meme.rewrite/make-rule name pattern replacement)
(meme.rewrite/make-rule name pattern replacement guard)
(meme.rewrite/rule pattern replacement)
(meme.rewrite/rule pattern replacement guard)
```

Create a rewrite rule. `guard` is an optional `(fn [bindings] bool)`.

```clojure
(meme.rewrite/apply-rule rule expr)   ;=> rewritten expr or nil
(meme.rewrite/apply-rules rules expr) ;=> first matching rule's result or nil
```

### Rewriting strategies

```clojure
(meme.rewrite/rewrite rules expr)
(meme.rewrite/rewrite rules expr max-iters)
```

Apply rules repeatedly (bottom-up) until fixpoint or `max-iters` (default 100). Returns the final expression.

```clojure
(meme.rewrite/rewrite-once rules expr)  ;=> [changed? result]
(meme.rewrite/rewrite-top rules expr)    ;=> top-level only, to fixpoint
```

### DSL macros (JVM/Babashka only)

```clojure
(meme.rewrite/defrule identity-plus (+ ?a 0) => ?a)
(meme.rewrite/defrule-guard pos-check ?x => :pos (fn [b] (pos? (b 'x))))
(meme.rewrite/ruleset
  (+ ?a 0) => ?a
  (* ?a 1) => ?a)
```


## meme.rewrite.rules

Predefined rule sets for S-expression ↔ M-expression transformations.

### s->m-rules

Rules that tag S-expression calls as `m-call` nodes. List patterns only match lists (not vectors).

### m->s-rules

Rules that convert `m-call` nodes back to S-expression lists.

### tree->s-rules

Rules that flatten tagged tree nodes to Clojure forms.

### transform-structures

```clojure
(meme.rewrite.rules/transform-structures form)
```

Walk a tree and convert structural tags to Clojure data/AST nodes.


## meme.rewrite.tree

Token vector → tagged tree builder for the rewrite-based lang.

### tokens->tree

```clojure
(meme.rewrite.tree/tokens->tree tokens)
```

Convert a flat token vector to a tagged tree. Returns a vector of top-level forms.

### build-tree

```clojure
(meme.rewrite.tree/build-tree tokens pos)
```

Build a tagged tree node from tokens starting at `pos`. Returns `[node new-pos]`.

### rewrite-parser

```clojure
(meme.rewrite.tree/rewrite-parser tokens opts source)
```

Parser that conforms to the stage contract: `(fn [tokens opts source] → forms)`. Uses the rewrite-based approach: tokens → tagged tree → rules → structures. Drop-in replacement for `meme.parse.reader/read-meme-string-from-tokens`.


## meme.rewrite.emit

Serializes m-call tagged trees to meme text.

### emit

```clojure
(meme.rewrite.emit/emit form)
```

Convert a form (with m-call tags) to meme text string.

### emit-forms

```clojure
(meme.rewrite.emit/emit-forms forms)
```

Emit a sequence of top-level forms as meme text, separated by newlines.


## meme.lang — User lang registration

In addition to built-in langs and EDN-loaded langs, user langs can be registered at runtime for extension-based auto-detection by `run-file` and the CLI.

### register!

```clojure
(meme.lang/register! lang-name config)
```

Register a user lang. `lang-name` is a keyword. `config` is an EDN-style map — the same format as `.edn` lang files. Symbols are resolved via `requiring-resolve`, strings and keywords follow the same rules as `load-edn`. Pre-resolved functions are also accepted.

```clojure
(lang/register! :calc {:extension ".calc"
                       :run "examples/languages/calc/core.meme"
                       :format :meme-classic})
```

### resolve-by-extension

```clojure
(meme.lang/resolve-by-extension path)
```

Given a file path, find the user lang whose `:extension` matches. Returns `[lang-name lang-map]` or `nil`.

### registered-langs

```clojure
(meme.lang/registered-langs)
```

List all registered user language names (keywords).

### clear-user-langs!

```clojure
(meme.lang/clear-user-langs!)
```

Clear all registered user languages. For testing.

