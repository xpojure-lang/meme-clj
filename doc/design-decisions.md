# beme Design Decisions

Decisions made during beme's design and implementation, with rationale.


## M-expressions: continuing McCarthy's original idea

M-expressions were proposed by McCarthy (1960) as the intended surface
syntax for Lisp. S-expressions were the internal representation — not
meant for humans to write directly. The human-friendly syntax was never
built; S-expressions stuck by accident.

beme picks up that thread for Clojure. Two rules:

1. The head of a list is written outside the parens: `f(x y)` → `(f x y)`.
2. `begin`/`end` as textual call delimiters: `f begin x y end` → `(f x y)`.

Everything else is Clojure.


## Three-stage pipeline (scan → group → parse)

The reader pipeline is split into three explicit stages:

1. **Scan** (`beme.alpha.scan.tokenizer`) — character scanning → flat token vector.
   Opaque regions emit marker tokens (`:reader-cond-start`, etc.) rather
   than capturing raw text inline.
2. **Group** (`beme.alpha.scan.grouper`) — collapses marker tokens + balanced delimiters
   into single composite `-raw` tokens.
3. **Parse** (`beme.alpha.parse.reader`) — recursive-descent parser → Clojure forms.

The original design captured opaque regions (reader conditionals, namespaced
maps, syntax-quote with brackets) inline during tokenization via
`read-balanced-raw`, which did character-level bracket matching. This had
bugs: char literals (`\)`) and strings containing delimiters could fool the
bracket counter, because it operated on raw characters rather than tokens.

The grouper solves this by operating on already-tokenized input where
bracket matching is trivial — strings, chars, and comments are individual
tokens, so `\)` inside a string is just a `:string` token, not a closing
paren. The three-stage split also makes each stage independently testable
and the pipeline extensible.

`beme.alpha.pipeline` composes the stages as `ctx → ctx` functions, threading a
context map with `:source`, `:raw-tokens`, `:tokens`, `:forms`. This makes
intermediate state visible to tooling via `beme.alpha.core/run-pipeline`.


## Centralized host reader delegation (beme.alpha.parse.resolve)

All `read-string` calls — for numbers, strings, chars, regex, syntax-quote,
namespaced maps, reader conditionals, auto-resolve keywords, and tagged
literals — are centralized in `beme.alpha.parse.resolve`. Previously these were
scattered across the parser with inconsistent error handling.

Centralization provides:
- A single `host-read` pattern that wraps exceptions with beme location info.
- Clean separation of platform asymmetries (JVM vs ClojureScript) from parser logic.
- The parser deals only with structural parsing; value interpretation is delegated.


## Custom tokenizer (not Clojure's reader)

Clojure's reader rejects `f(x y)` as invalid syntax. beme fundamentally
changes what constitutes a valid token sequence. A custom tokenizer is
unavoidable.


## No intermediate AST

beme is a thin syntactic transform. The output is Clojure forms — lists,
vectors, maps, symbols, keywords. These are the same data structures
Clojure's own reader produces. An intermediate AST would add complexity
without benefit.


## Volatile for parser state

The parser is recursive-descent with many mutually recursive functions.
Threading position state through every function signature and return value
adds noise. A `volatile!` position counter is the lightest shared-state
mechanism in `.cljc`, works on both JVM and ClojureScript. The same
pattern is used for the scanner's line/col tracking and for the portable
string builder (`make-sb`/`sb-append!`/`sb-str`) which wraps `StringBuilder`
on JVM and a JS array on ClojureScript.


## Everything is a call

All special forms use call syntax: `def(x 42)`, `defn(f [x] body)`,
`if(cond then else)`, `try(body catch(Exception e handler))`. There
are no bare forms. The only alternative delimiters are `begin`/`end`
(Rule 2), which are equivalent to parentheses.

This dramatically simplifies both the reader and the printer:
- The reader has no special-form parsers — all symbols go through the
  same `maybe-call` path.
- The printer has no special-form printers — all lists use the generic
  `head(args...)` format.
- `do`, `catch`, `finally` are regular symbols, not grammar keywords.


## Head-outside-parens call detection

A call is formed when a symbol, keyword, or vector precedes `(` —
spacing is irrelevant. `foo(x)` and `foo (x)` both produce `(foo x)`.

The rule is: the head of a list is written outside the parens. This
applies to symbols (`f(x)`), keywords (`:require([bar])`), and
vectors (`[x](body)` for multi-arity clauses like `([x] body)`).

Bare `(...)` without a preceding head is an error — the reader rejects it
with "Bare parentheses not allowed." The two exceptions are `'(...)` (quoted
list) and nested parens inside `'(...)`, where the reader switches to Clojure
S-expression mode so that `'(f (g x))` produces `(quote (f (g x)))`.
Outside these quote contexts, every `(...)` must have a head, making the
call rule uniform and eliminating ambiguity.


## Spacing irrelevance is required by begin/end

Spacing between a head and its opening delimiter is irrelevant — this
is not a stylistic preference but a structural requirement. Rule 2's
`begin`/`end` delimiters are textual tokens, so there is always at
least a space between the head and `begin`: `f begin x end`. If the
reader required adjacency (no whitespace before `(`), `begin`/`end`
could not work at all — the head would never be adjacent to the
delimiter.

The two rules are coupled: once textual delimiters exist, spacing
*must* be irrelevant for both `(` and `begin`. And since bare `()`
is rejected (every `(` requires a head), spacing irrelevance
introduces no ambiguity — there is no valid beme program where
`f (x)` could mean anything other than `(f x)`.


## `#` dispatch forms are opaque

Reader conditionals (`#?`, `#?@`), tagged literals (`#inst`, `#uuid`), and
other `#`-prefixed forms that don't need beme-internal parsing are captured
as raw text and passed to Clojure's `read-string`. This avoids reimplementing
Clojure's reader dispatch in beme.

Forms that need beme parsing inside their delimiters — `#{}` (sets), `#""`
(regex), `#'` (var quote), `#_` (discard) — are handled by beme's tokenizer
since their contents may contain beme syntax.

`#()` (anonymous fn shorthand) uses beme syntax inside — the call rule
applies normally. The body is a single beme expression; `%`, `%1`, `%2`, `%&`
are collected by the reader and used to build the `fn` parameter vector.
`#(inc(%))` produces `(fn [%1] (inc %1))`. Bare `%` is normalized to `%1`.

On ClojureScript, opaque forms have limited support: `cljs.reader` does not
handle `#?`, `#?@`, `#:ns{}`, or tagged literals at runtime (these are
resolved at compile time). Regex is handled separately — the parser
constructs `js/RegExp` directly instead of delegating to `read-string`.


## No quoting needed (usually)

In Clojure, `'(1 2 3)` is needed because `(1 2 3)` would try to call `1`.
In beme, `()` forms a call when a symbol, keyword, or vector precedes it.
`[]` is always data. There is no ambiguity, so you never need quote for
your own code. Use `list(1 2 3)` to construct a list.

However, some macro APIs expect quoted arguments (e.g. `list('if test then else)`).
Quote passes through for those cases. The printer preserves quote only when the
source Clojure had it — it never synthesizes quote.

## Quote uses Clojure syntax inside

Inside `'(...)`, the reader switches to Clojure S-expression mode: bare
parentheses create lists, and symbols do not trigger Rule 1 calls. This
means `'(f (g x))` produces `(quote (f (g x)))` — two elements, `f` and
the list `(g x)`.

This is essential for:
- **Macros**: `list('if test then else)` — the `'if` is a symbol quote
  (no change), but `'((f x) (g y))` now works for any list structure.
- **Data**: Quoted lists with non-callable heads like `'((1 2) (3 4))`
  are valid — no special cases or error paths.
- **Roundtrip**: The printer emits `'(...)` with S-expression syntax
  inside, and the reader reconstructs the same forms. Previously, quoted
  lists with non-callable-headed sublists could not be printed.

The mode is scoped: it activates on `'(` and deactivates at the matching
`)`. Quote on non-list forms (`'foo`, `'42`) is unchanged. Backtick
`` ` `` is unaffected — it was already opaque.


## Commas are whitespace

Same as Clojure. `f(a, b, c)` and `f(a b c)` are identical. Use
whichever style is clearer for the context.


## Backtick is opaque

Syntax-quote (`` ` ``) is an opaque boundary. The backtick and its body
are captured as raw text and passed to Clojure's reader. Macro templates
use S-expression syntax inside backtick — beme syntax applies everywhere
else.

This means macros work in `.beme` files:

```
defmacro(unless [test & body] `(if (not ~test) (do ~@body)))
```

The `defmacro` call uses beme syntax. The template inside `` ` `` is raw
Clojure, processed by Clojure's reader for namespace resolution, gensym
expansion, and unquote handling. This avoids reimplementing syntax-quote
while keeping macro definitions available in beme.


## Signed number heuristic

`-1` is a negative number. `-(1 2)` is a call to `-` with args `1 2`.

The rule: if a sign character (`-` or `+`) is immediately followed by a
digit, it is part of a number token. If followed by `(`, whitespace, or
anything else, it is a symbol. This is a one-character lookahead in the
tokenizer. No ambiguity — but the decision affects user expectations, so
it has a scar tissue test.


## Auto-resolve keywords

`::foo` resolution depends on context:

- **With `:resolve-keyword` option** (REPL, file runner): resolved at
  read time to `:actual.ns/foo`, matching Clojure's semantics. The
  caller provides the resolver function; the REPL and file runner pass
  `#(clojure.core/read-string %)` which resolves in the current `*ns*`.
- **Without option on JVM/Babashka** (tooling, bare `read-beme-string`):
  deferred to eval time via `(clojure.core/read-string "::foo")`. The
  printer detects this pattern and emits `::foo` for roundtripping.
- **Without option on CLJS**: errors. `cljs.reader/read-string` resolves
  `::` in the compiler's namespace, not the caller's, so deferred
  resolution would silently produce wrong results.

This avoids reimplementing Clojure's namespace resolution in the reader
while eliminating the semantic shift for all practical use cases.


## Delegation to host reader for primitives

Numbers, strings, character literals, and regex patterns are tokenized as
raw text by beme's tokenizer, then passed to `read-string` for the host
platform to produce actual values. beme never parses numeric formats (hex,
octal, ratios, BigDecimal), string escape sequences, or character names.

This avoids reimplementing Clojure's literal parsing and guarantees
identical behavior to the host platform. The one exception is regex on
ClojureScript, where `cljs.reader` doesn't handle `#"..."` — the parser
constructs `js/RegExp` directly from the pattern string.


## Platform tiers

The codebase is split into three platform tiers:

- **Core translation** (tokenizer, grouper, reader, resolve, printer,
  pipeline, core, errors) — portable `.cljc`, runs on JVM, Babashka,
  and ClojureScript. These are pure functions with no eval or I/O dependency.
- **Runtime** (repl, run) — `.cljc` but require `eval` and `read-line`/
  `slurp`, which are JVM/Babashka by default. ClojureScript callers can
  inject these via options.
- **Test infrastructure** (test-runner, dogfood-test) — `.clj`, JVM only.
  These use `java.io`, `PushbackReader`, `System/exit`.

This separation is honest about what's portable. The `.clj` extension
prevents the ClojureScript compiler from attempting to compile JVM-only
code.


## `#()` printer shorthand: zero-param and `%&`-only forms

The printer emits `#(body)` for `(fn [] body)` (zero params) and for
`(fn [%1 %2] body)` where all numbered `%N` params are used in the body.
Forms with surplus `%` params (declared but unused in body) fall through
to `fn(...)` syntax to avoid silently changing arity on roundtrip.

`(fn [& %&] body)` (rest-only, no numbered params) also falls through to
`fn([& %&] body)` because the `&` symbol in the param vector is not a
`%`-param, preventing the `#()` shorthand heuristic from matching. This is
intentional — the printer cannot distinguish `fn([& %&] ...)` from a
user-written named form.


## `maybe-call` on opaque forms

The reader applies `maybe-call` to syntax-quote, namespaced-map, and
reader-conditional forms. This means `` `expr(args) ``, `#:ns{...}(args)`,
and `#?(...)(args)` are valid call syntax, consistent with the Rule 1
principle that any form followed by `(` is a call. In practice these
are rarely meaningful, but the uniform behavior avoids special-casing.


## Nesting depth limit

The parser enforces `max-depth` of 512, checked in `parse-form` with a
volatile counter that increments on entry and decrements in `finally`.
This prevents stack overflow from deeply nested or malicious input.
512 is generous for any real program while staying well within JVM/CLJS
default stack sizes.


## Shared source-position contract (beme.alpha.scan.source)

The tokenizer records `(line, col)` on each token. The grouper later
needs to map those positions back to character offsets in the source
string — to extract raw text for opaque regions (reader conditionals,
namespaced maps, syntax-quote). If the two stages disagree on how
`(line, col)` translates to an offset, the extracted text is wrong:
off-by-one truncation, stray characters, or outright garbled output.

`beme.alpha.scan.source/line-col->offset` is the single definition both
stages share. The tokenizer uses it in `attach-whitespace`; the grouper
uses it in `extract-source-range`. Because it's one function in one
namespace, the mapping can't diverge. The alternative — each stage
carrying its own offset logic — was the source of a previous bug where
whitespace attachment and source extraction disagreed after a newline.


## Centralized error infrastructure (beme.alpha.errors)

All error throw sites go through `beme-error`, which constructs `ex-info`
with a consistent structure: `:line`, `:col` (1-indexed), optional
`:cause`, and optional `:source-context`. This gives every error —
whether from the tokenizer, grouper, reader, or resolver — a uniform
shape that callers can rely on.

The `:incomplete` flag in ex-data is the REPL continuation protocol.
When a tokenizer or reader error is caused by premature EOF (unclosed
delimiters, lone backtick, unterminated string), the error is thrown
with `{:incomplete true}`. The REPL's `input-state` function catches
these errors and returns `:incomplete` to signal that more input may
complete the form. This lets the same error infrastructure that reports
parse failures also power multi-line input handling.

`format-error` produces IDE-quality display: line-number gutter, span
underlines (`^` for single-column, `~~~` for multi-column via
`:end-col`), secondary source locations with labels, and hint text.
The secondary locations and hints are extension points for richer
diagnostics as the error system grows.


## Textual call delimiters (begin/end) — Rule 2

`begin` and `end` are alternative call delimiters, equivalent
to parentheses: `f begin x y end` parses as `(f x y)`, identical to
`f(x y)`. This is the second of beme's two syntactic rules.

The motivation is readability for multi-line blocks. In beme, everything
is a call — `defn`, `let`, `try`, `if` all use `head(args...)`. For
short expressions, parentheses are natural. For multi-line function
bodies or `try`/`catch` blocks, `begin`/`end` reads more like
natural-language block structure.

**`end` is reserved inside begin blocks.** The reader treats `end` as a
closing delimiter when inside a `begin` block. This means `end`
cannot appear as a data symbol in that context. Outside begin blocks,
`end` is a normal symbol. This is a deliberate trade-off: the reserved
word is uncommon in Clojure code, and the alternative (an escape
mechanism) would add complexity for a rare edge case.

**The printer always emits parentheses.** `begin`/`end` is reader
convenience — the canonical beme form uses `f(args...)`. This keeps the
printer simple and means there is one canonical output form. Code written
with `begin`/`end` round-trips semantically (same Clojure forms) but not
textually (the printer emits parens).
