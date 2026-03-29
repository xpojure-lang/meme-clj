# meme Design Decisions

Decisions made during meme's design and implementation, with rationale.


## M-expressions: continuing McCarthy's original idea

M-expressions were proposed by McCarthy (1960) as the intended surface
syntax for Lisp. S-expressions were the internal representation — not
meant for humans to write directly. The human-friendly syntax was never
built; S-expressions stuck by accident.

meme picks up that thread for Clojure. One rule:

The head of a list is written outside the parens: `f(x y)` → `(f x y)`.

Everything else is Clojure.


## Three-stage pipeline (scan → group → parse)

The reader pipeline is split into three explicit stages:

1. **Scan** (`meme.alpha.scan.tokenizer`) — character scanning → flat token vector.
   Compound forms (reader conditionals, namespaced maps, syntax-quote)
   emit marker tokens.
2. **Group** (`meme.alpha.scan.grouper`) — pass-through stage (all forms are
   now parsed natively; retained for pipeline symmetry).
3. **Parse** (`meme.alpha.parse.reader`) — recursive-descent parser → Clojure forms.
   No `read-string` delegation — all values resolved natively.

The three-stage split makes each stage independently testable and the
pipeline extensible. The grouper operates on already-tokenized input where
bracket matching is trivial — strings, chars, and comments are individual
tokens, so `\)` inside a string is just a `:string` token, not a closing
paren.

`meme.alpha.pipeline` composes the stages as `ctx → ctx` functions, threading a
context map with `:source`, `:raw-tokens`, `:tokens`, `:forms`. This makes
intermediate state visible to tooling via `meme.alpha.core/run-pipeline`.


## Centralized value resolution (meme.alpha.parse.resolve)

All value resolution — numbers, strings, chars, regex, auto-resolve
keywords, tagged literals — is centralized in `meme.alpha.parse.resolve`.
The parser deals only with structural parsing; value interpretation is
delegated to resolve.

The goal is zero `read-string` delegation: meme parses everything
natively, including forms that were previously opaque (syntax-quote,
reader conditionals, namespaced maps). Platform asymmetries (JVM vs
ClojureScript) are isolated in resolve.


## Custom tokenizer (not Clojure's reader)

Clojure's reader rejects `f(x y)` as invalid syntax. meme fundamentally
changes what constitutes a valid token sequence. A custom tokenizer is
unavoidable.


## No intermediate AST

meme is a thin syntactic transform. The output is Clojure forms — lists,
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
`if(cond then else)`, `try(body catch(Exception e handler))`. Every
`(...)` with content must have a head. `()` is the empty list.

This dramatically simplifies both the reader and the printer:
- The reader has no special-form parsers — all symbols go through the
  same `maybe-call` path.
- The printer has no special-form printers — all lists use the generic
  `head(args...)` format.
- `do`, `catch`, `finally` are regular symbols, not grammar keywords.

The rule applies uniformly to reader dispatch forms too: `#?(...)` has
`#?` as its head. `#(...)` has `#` as its head. These are not exceptions
to the rule — they are instances of it.


## Head-outside-parens call detection

A call is formed when a symbol, keyword, or vector **immediately**
precedes `(` with no whitespace. `foo(x)` produces `(foo x)`.

The rule is: the head of a list is written outside the parens, adjacent
to `(`. This applies to symbols (`f(x)`), keywords (`:require([bar])`),
and vectors (`[x](body)` for multi-arity clauses like `([x] body)`).

Bare `(...)` with content but no preceding head is an error — the reader
rejects it with "Bare parentheses not allowed." `()` (empty parens) is
the empty list — it is unambiguous and needs no head.


## Spacing significance

Spacing between a head and its opening `(` is significant — `f(x)` is
a call producing `(f x)`, but `f (x)` is two forms: the symbol `f`
followed by bare `(x)` which is an error. This makes `()` unambiguous
in all positions: `{:value ()}` is a map with two entries (`:value` and
the empty list), not `:value` calling `()`. Similarly, `[x ()]` is a
two-element vector, not `[(x)]`.

Previously, spacing was irrelevant (`f (x)` was also a call). This was
changed because it made `()` (the empty list) impossible to place after
any callable form in a container — the reader would always consume it
as a zero-arg call.


## `#` dispatch forms follow the rule

All `#`-prefixed forms are parsed natively by meme — no delegation to
`read-string`. The `#` dispatch character combines with the next
character(s) to form the head of an M-expression:

- `#?(...)` — reader conditional. `#?` is the head.
- `#?@(...)` — splicing reader conditional. `#?@` is the head.
- `#(...)` — anonymous fn. `#` is the head. Body is a single meme
  expression; `%`, `%1`, `%2`, `%&` are collected and used to build
  the `fn` parameter vector. `#(inc(%))` → `(fn [%1] (inc %1))`.
- `#{...}` — set literal. `#` dispatches with `{`.
- `#"..."` — regex literal.
- `#'x` — var quote. Prefix operator.
- `#_x` — discard. Prefix operator.
- `#inst "..."`, `#uuid "..."` — tagged literals.
- `#:ns{...}` — namespaced maps.

Reader conditionals parse only the matching platform's branch; non-matching
branches are skipped without parsing (they may contain platform-specific
syntax invalid on the current platform).

Syntax-quote (`` ` ``) is also parsed natively — its interior uses meme
syntax with `~` (unquote) and `~@` (unquote-splicing). Macro templates
are written in meme syntax: `` `if(~test do(~@body)) ``.


## Quote uses meme syntax

`'` is a prefix operator that quotes the next meme form. There is no
S-expression escape hatch — `'` does not switch parser modes:

- `'foo` → `(quote foo)` — quoted symbol
- `'[1 2 3]` → `(quote [1 2 3])` — quoted vector
- `'f(x y)` → `(quote (f x y))` — quoted call
- `'()` → `(quote ())` — quoted empty list

Lists are constructed by calls: `list(1 2 3)` → `(list 1 2 3)`. For
quoting code, use `quote`: `quote(+(1 2))` → `(quote (+ 1 2))`.


## Commas are whitespace

Same as Clojure. `f(a, b, c)` and `f(a b c)` are identical. Use
whichever style is clearer for the context.


## Syntax-quote uses meme syntax

Syntax-quote (`` ` ``) is parsed natively — its interior uses meme
call syntax, not S-expressions. `~` (unquote) and `~@` (unquote-splicing)
work as prefix operators inside syntax-quote.

```
defmacro(unless [test & body] `if(~test nil do(~@body)))
```

The meme reader handles symbol resolution, gensym expansion, and
unquote splicing — the same transformations Clojure's reader performs,
but applied to meme-parsed forms. No `read-string` delegation.


## Signed number heuristic

`-1` is a negative number. `-(1 2)` is a call to `-` with args `1 2`.

The rule: if a sign character (`-` or `+`) is immediately followed by a
digit, it is part of a number token. If followed by `(`, whitespace, or
anything else, it is a symbol. This is a one-character lookahead in the
tokenizer. No ambiguity — but the decision affects user expectations, so
it has a scar tissue test.


## Auto-resolve keywords

`::foo` is resolved natively by the meme reader:

- **With `:resolve-keyword` option** (REPL, file runner): resolved at
  read time to `:actual.ns/foo`, matching Clojure's semantics. The
  caller provides the resolver function.
- **Without option on JVM/Babashka** (tooling, bare `read-meme-string`):
  deferred to eval time. The printer detects the deferred form and
  emits `::foo` for roundtripping.
- **Without option on CLJS**: errors, since deferred resolution would
  silently produce wrong results.


## Native value resolution for primitives

Numbers, strings, character literals, and regex patterns are tokenized as
raw text by meme's tokenizer, then resolved natively by
`meme.alpha.parse.resolve`. The goal is zero delegation to `read-string` —
meme parses numeric formats (hex, octal, ratios, BigDecimal), string
escape sequences, and character names itself, guaranteeing identical
behavior to the host platform without depending on its reader.


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


## `maybe-call` on all forms

The reader applies `maybe-call` uniformly — any form followed by `(` is
a call. This means `` `expr(args) ``, `#:ns{...}(args)`, and
`#?(...)(args)` are valid call syntax. In practice these are rarely
meaningful, but the uniform behavior avoids special-casing.


## Nesting depth limit

The parser enforces `max-depth` of 512, checked in `parse-form` with a
volatile counter that increments on entry and decrements in `finally`.
This prevents stack overflow from deeply nested or malicious input.
512 is generous for any real program while staying well within JVM/CLJS
default stack sizes.


## Shared source-position contract (meme.alpha.scan.source)

The tokenizer records `(line, col)` on each token. The tokenizer also
needs to map those positions back to character offsets in the source
string for whitespace attachment. If position tracking is inconsistent,
whitespace metadata is wrong: off-by-one truncation, stray characters,
or outright garbled output.

`meme.alpha.scan.source/line-col->offset` is the single definition that
ensures the mapping is consistent. The tokenizer uses it in
`attach-whitespace`. Because it's one function in one namespace, the
mapping can't diverge. The alternative — each stage carrying its own
offset logic — was the source of a previous bug where whitespace
attachment disagreed after a newline.

Note: the grouper previously used `extract-source-range` to capture raw
text for opaque regions, but all forms are now parsed natively and the
grouper is a pass-through. The shared contract remains important for
the tokenizer's whitespace attachment.


## Centralized error infrastructure (meme.alpha.errors)

All error throw sites go through `meme-error`, which constructs `ex-info`
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


