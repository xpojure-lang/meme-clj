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


## Reader stages

The reader has four core stages and one optional stage:

1. **step-scan** (`meme.tools.reader.tokenizer`) — exhaustive byte-level scanner → flat token vector. Never throws. Partition invariant: `(= input (apply str (map :raw tokens)))`.
2. **step-trivia** (`meme.tools.pratt.trivia`) — attaches trivia (whitespace, comments) to semantic tokens as structured `:trivia/before` metadata.
3. **step-parse** (`meme.tools.pratt.parser`) — data-driven Pratt parser → lossless CST. Grammar is a map of token types to parselet functions.
4. **step-read** (`meme.tools.reader.cst-reader`) — lowers CST to Clojure forms. No `read-string` delegation — all values resolved natively via `meme.tools.parse.resolve`.
5. **step-expand-syntax-quotes** (`meme.tools.parse.expander`) — syntax-quote AST nodes → plain Clojure forms. Only needed before eval, not for tooling.

The core `stages/run` calls stages 1–4, returning AST nodes for
tooling. `run-string` chains all five stages before eval.

The split makes each stage independently testable and the composition extensible.
The tokenizer handles all character-level concerns (strings, chars, comments
are individual tokens, so `\)` inside a string is just a `:string` token,
not a closing paren). The Pratt parser handles all structural concerns.

`meme.tools.reader.stages` composes the stages as `ctx → ctx` functions, threading a
context map with `:source`, `:tokens`, `:cst`, `:forms`.


## Centralized value resolution (meme.parse.resolve)

All value resolution — numbers, strings, chars, regex, auto-resolve
keywords, tagged literals — is centralized in `meme.parse.resolve`.
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
- The reader has no special-form parsers — all non-literal symbols go
  through the same `maybe-call` path (only `nil`, `true`, `false` are
  special-cased as literals before `maybe-call`).
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

Reader conditionals parse all branches but only return the matching
platform's value — non-matching branches are fully parsed then discarded.

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
`meme.parse.resolve`. The goal is zero delegation to `read-string` —
meme parses numeric formats (hex, octal, ratios, BigDecimal), string
escape sequences, and character names itself, guaranteeing identical
behavior to the host platform without depending on its reader.


## Platform tiers

The codebase is split into three platform tiers:

- **Core translation** (reader.tokenizer, reader.stages, reader.meme-grammar, reader.meme-parselets, reader.cst-reader, pratt.parser, pratt.trivia, parse.resolve, parse.expander, emit.printer, emit.render, emit.values, emit.formatter.flat, emit.formatter.canon, errors, forms, langs.meme) — portable `.cljc`, runs on JVM, Babashka, and ClojureScript. Pure functions with no eval or I/O dependency.
- **Runtime** (repl, run, registry, cli) — `.clj`, JVM/Babashka only. Require `eval` and `read-line`/`slurp`.
- **Test infrastructure** (test-runner, dogfood-test) — `.clj`, JVM only.
  These use `java.io`, `PushbackReader`, `System/exit`.

This separation is honest about what's portable. The `.clj` extension
prevents the ClojureScript compiler from attempting to compile JVM-only
code.


## `#()` printer shorthand

The printer emits `#(body)` when the form has `:meme/sugar true` metadata —
set by the reader when it parses `#(...)` source syntax. A user-written
`fn([%1] body)` lacks this metadata and prints back as `fn(...)`.

This is an instance of the syntactic transparency principle: the reader
tags the notation, the printer reconstructs it. No body inspection or
surplus-param heuristic is needed — the reader's `build-anon-fn-params`
already builds the correct parameter vector at read time from the `%`
params found in the body.


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


## Source-position tracking

The tokenizer records `(line, col)` on each token. Position tracking uses the **scanner line model** (only `\n` is a line break, `\r` occupies a column). This is handled internally within `meme.tools.reader.tokenizer`. The display line model (`str/split-lines` in `meme.tools.errors`) may diverge for CRLF sources — `format-error` bridges the gap by clamping carets.


## Centralized error infrastructure (meme.errors)

All error throw sites go through `meme-error`, which constructs `ex-info`
with a consistent structure: `:line`, `:col` (1-indexed), optional
`:cause`, and optional `:source-context`. This gives every error —
whether from the tokenizer, reader, or resolver — a uniform
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


## Syntactic transparency — sugar preservation

meme is a **syntactic lens**, not a compiler. The read→print path
must be transparent: if the user writes `'x` (sugar), it prints back as
`'x`; if they write `quote(x)` (explicit call), it prints back as
`quote(x)`. The same applies to `@`/`clojure.core/deref` and
`#'`/`var`.

**Principle:** Every piece of user syntax that has more than one
representation must be preserved through the stages. When the reader
collapses two notations into the same Clojure form, it must tag the form
with metadata recording which notation was used. The printer checks that
metadata to reconstruct the original syntax.

**Implementation:** The reader attaches `:meme/sugar true` metadata to
forms produced by sugar syntax (`'`, `@`, `#'`). The printer checks
this: sugar-tagged forms emit sugar; untagged forms emit the explicit
call. The `:meme/sugar` key is stripped from display metadata (alongside
`:line`, `:column`, `:file`, `:ws`) so it never appears in output.

**Why this matters:** Without this, the stages silently normalize
user code. `var(x)` becomes `#'x`. `quote(list)` becomes `'list`.
A syntactic lens that rewrites your code is not a lens — it's a
formatter with opinions. Every new syntax feature should be checked:
can two notations produce the same form? If yes, metadata must
distinguish them.

**Known remaining losses:**
- Commas: treated as whitespace, not preserved.
- `#_` discarded forms: gone by design.

**Previously fixed** (these were losses in earlier versions, now preserved
via metadata):
- Namespaced maps: preserved via `:meme/ns` metadata on the map.
- Chained metadata annotations: preserved via `:meme/meta-chain`.
- Set element ordering: preserved via `:meme/order` (insertion order).
- `#()` vs `fn()`: preserved via `:meme/sugar` (see above section).


## Lang backend

meme uses a single implementation of the meme↔Clojure translation, registered as `:meme` in the lang registry.

The pipeline combines a lossless Pratt parser (`meme.tools.pratt.parser` with `meme.tools.reader.meme-grammar`) and a Wadler-Lindig document printer (`meme.tools.emit.printer`). It preserves all metadata, sugar flags (`:meme/sugar`), whitespace annotations, and comment positions through roundtrips.

**Use for:** formatting, tooling integration, roundtrip-sensitive workflows.

## Known divergences from Clojure's reader

### Leading-dot floats (`.5`, `.5M`, `.5e1`)

Clojure reads `.5` as the float `0.5`. Meme tokenizes `.5` as a symbol because `.` is the Java interop prefix (`.method`). The tokenizer enters number mode only when the current character is a digit. This is an intentional divergence — in meme, `.foo` is always an interop method call, and leading-dot floats must be written with an explicit zero: `0.5`.

### Surrogate pairs in string Unicode escapes

Clojure accepts `"\uD800\uDC00"` (a valid UTF-16 surrogate pair encoding U+10000) and produces the supplementary character. Meme rejects each `\uXXXX` escape individually — if the code point falls in the surrogate range (U+D800..U+DFFF), it errors regardless of whether the next escape forms a valid pair. This is a defensive choice that prevents isolated surrogates from entering the output. Users can include supplementary characters directly as literal UTF-8 in source text.

### Map key ordering for maps with >8 entries

Clojure's `array-map` preserves insertion order for up to 8 entries. Beyond that, it promotes to `PersistentHashMap` which does not preserve order. Since the meme parser builds maps via `(apply array-map forms)`, maps with 9+ keys may have their key order shuffled in output. Sets preserve order via `:meme/order` metadata, but maps do not have an equivalent mechanism. This is a Clojure platform limitation, not a meme design choice.

### Comments on primitive map keys

Comments in meme source (`;; comment`) are attached as `:ws` metadata to the following form. However, primitive types (keywords, numbers, strings, booleans) do not implement `IMeta` in Clojure and cannot carry metadata. When a comment appears before a keyword map key (e.g., `{; comment\n :a 1}`), the comment is lost because `:a` cannot store metadata. This is a fundamental Clojure platform limitation. Comments before symbols, vectors, maps, and sets are preserved correctly.

### End-of-line comment repositioning

The meme formatter uses a "comment attaches to next form" model inherited from the tokenizer. An end-of-line comment like `foo(x ;; note\n y)` is attached to `y` (the next form), not to `x` (the preceding form). When formatted, the comment appears before `y` rather than after `x`. This preserves comment content but changes its visual position. This is an inherent consequence of the "attach to next token" architecture.

### Sorted collections lose ordering through roundtrip

`sorted-map` and `sorted-set` have no literal syntax in Clojure. When printed, they appear as regular maps/sets. Re-parsing produces `PersistentArrayMap`/`PersistentHashSet`, losing the sorted property. This matches Clojure's own limitation.

### `@f(x)` is `@(f x)`, not `(@f x)`

The `@` deref prefix applies to the next complete form. `@f(x)` parses as `(deref (f x))` — the deref wraps the call expression `f(x)`. This is correct and consistent with how all prefix operators work in meme: they bind to the next form, including any adjacent call arguments.

### Regex flags not preserved in printer output

The printer accesses the regex pattern via `.pattern` (JVM) or `.-source` (CLJS), which returns only the pattern body without flags. If a regex was constructed programmatically with flags (e.g., `re-pattern` with inline flags), the flags appear in the pattern string itself (e.g., `(?i)...`) and are preserved. External flag objects are not representable in `#"..."` literal syntax.

### Symbols with whitespace cannot roundtrip

Programmatically constructed symbols containing whitespace, parentheses, or other syntax-significant characters (e.g., `(symbol "foo bar")`) cannot be faithfully printed and re-parsed. Clojure has no escape syntax for symbols — `pr-str` returns the same as `str` for symbols. This means such symbols print as raw text which re-parses as different forms: `(symbol "foo\nbar")` → `foo\nbar` → two symbols `foo` and `bar`. This matches Clojure's own limitation and affects all Clojure-based syntax tools.

### Inline comments on primitive values lost during formatting

Comments are preserved through the pipeline via `:ws` metadata attached to parsed forms. However, Clojure's metadata system only works on types that implement `IMeta` — symbols, keywords, collections, and records. Primitive values (numbers, strings, booleans, characters, nil, and regex) cannot carry metadata. When a comment appears before a primitive value inside a form (e.g., `def(x ;; important\n  42)`), the comment is attached to the `42` token during scanning, but is irretrievably lost when the parser resolves the token to a `Long`.

Comments that **survive** formatting: those before symbols, keywords, collections, and calls. Comments that are **lost**: those before numbers, strings, booleans, characters, nil, and regex literals.

This is a known limitation shared by all Clojure-based formatting tools (cljfmt, zprint face similar challenges). A complete fix would require a concrete syntax tree (CST) that preserves all tokens including whitespace, which is a fundamentally different architecture than the current form-based pipeline.

Leading and trailing comments (before/after top-level forms) are always preserved, as they are attached to the forms vector or to forms that support metadata.

### U+00A0 NBSP in symbols

The non-breaking space character (U+00A0, NBSP) is treated as part of a symbol name, not as whitespace. This matches Clojure's own behavior — `(read-string (str "f" \u00A0 "g"))` produces a single symbol `f\u00A0g` in Clojure too. This means NBSP is an invisible character attack vector: a symbol containing NBSP looks identical to one without it, but they are different symbols. Users should be aware of this when working with copy-pasted code from web pages or formatted documents that may contain NBSP characters.


### Tokenizer: structural vs semantic validation

The tokenizer (`meme.tools.reader.tokenizer`) is a pure structural scanner — it partitions input into tokens without knowing Clojure's semantic rules. The pipeline stages (`meme.tools.reader.stages`) translate structural tokens into the semantic format the Pratt parser and CST reader expect.

This separation means semantic validation belongs in the pipeline stages, not the tokenizer:

- **Reserved dispatch chars** (`#<`, `#=`, `#%`): The tokenizer correctly classifies `#=foo` as `:tagged-literal` (structurally, it IS `#` + symbol). Whether `=` is a reserved dispatch character is a semantic rule enforced downstream.
- **Unterminated strings/regex**: The tokenizer produces a token with `:raw` = `"abc` (no close quote) — correct per its partition invariant. Validation that string/regex tokens are properly delimited happens in the pipeline.
- **`:invalid` token error messages**: The pipeline should carry `:raw` content from `:invalid` tokens into parser errors for actionable diagnostics.

This keeps the tokenizer's contract clean: never throws, always partitions, no semantic knowledge.


## Roadmap

### `.mcj` — McCarthy's original M-expression syntax

McCarthy (1960) defined M-expressions as the surface syntax for Lisp. meme implements M-expressions for Clojure with one rule: `f(x y)` → `(f x y)`. But meme's syntax is a Clojure-flavored dialect — it inherits Clojure's reader macros, dispatch forms, and data literal conventions.

McCarthy's original syntax was different: `car[x]` used square brackets for application, `[p₁ → e₁; … ; pₙ → eₙ]` for conditionals, `λ[[x]; e]` for lambda, `label[f; λ[[x]; …]]` for recursive binding. A faithful implementation of the original notation — as a guest language with file extension `.mcj` (McCarthy John, M-expression CloJure) — would honor the historical origin while demonstrating the guest language system.

The infrastructure exists: the data-driven Pratt parser takes a grammar spec, the trivia transformer is configurable, and the lang registry supports guest languages. A `.mcj` lang would supply its own grammar (square-bracket application, semicolon separators, arrow conditionals) and its own parselets, reusing the same scanner and parser engine.

### Generic CST reader

`meme.tools.reader.cst-reader` lowers meme's CST to Clojure forms. The pattern is general — every language that uses the Pratt parser needs CST → host-language lowering. The generic parts (tree walking, trivia extraction, discard filtering) could become `meme.tools.pratt.cst` with language-specific value resolution and node handlers plugged in. Currently there's only one consumer (meme), so the extraction is deferred until a second language (e.g., `.mcj`) needs it.

### Data-driven scanner

The tokenizer (`meme.tools.reader.tokenizer`) knows about `#` dispatch, `:` keywords, `'`/`@`/`^`/`` ` ``/`~` prefix characters. This is Clojure-family knowledge baked into the scanner. A truly generic scanner would take a character → token-type configuration map:

```clojure
{:dispatch    \#
 :keyword     \:
 :prefixes    #{\' \@ \^ \` \~}
 :delimiters  {\( :open-paren  \) :close-paren
               \[ :open-bracket \] :close-bracket
               \{ :open-brace  \} :close-brace}
 :string      \"
 :comment     \;
 :escape      \\}
```

This would allow the same scanner to tokenize languages with different sigils (e.g., `$` for variables, `@` for decorators, `//` for comments). The current scanner is fast and correct — this refactor trades simplicity for generality and should wait until a second language needs a different character vocabulary.

### Trivia configuration

The trivia transformer accepts a configurable set of token types to classify as trivia. The default is `#{:whitespace :newline}` — minimal noise. The parsing pipeline extends this with `:comment :bom :shebang`.

An open question: should the trivia configuration be part of the grammar spec rather than a separate pipeline parameter? If the grammar defines what tokens are syntactically significant, it implicitly defines what's trivia. Merging them would reduce the number of configuration points. Currently kept separate because trivia classification is a scanner-level concern (before parsing) while the grammar is a parser-level concern.
