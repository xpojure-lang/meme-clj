# meme-clj Design Decisions

Decisions made during the toolkit's and m1clj's design and
implementation, with rationale.

> **Naming.** "meme-clj" is the toolkit / programme; "m1clj" is the
> first language built on it. See the Glossary section in `CLAUDE.md`.


## M-expressions: continuing McCarthy's original idea

M-expressions were proposed by McCarthy (1960) as the intended surface
syntax for Lisp. S-expressions were the internal representation — not
meant for humans to write directly. The human-friendly syntax was never
built; S-expressions stuck by accident.

m1clj picks up that thread for Clojure. One rule:

The head of a list is written outside the parens: `f(x y)` → `(f x y)`.

Everything else is Clojure.


## Reader stages

The reader has two core stages; eval paths compose two additional stages:

1. **step-parse** (`meme.tools.parser` with `m1clj-lang.grammar`) — unified scanlet-parselet Pratt parser. Reads directly from a source string. Scanning (character dispatch), trivia classification, and structural parsing are all defined in the grammar spec. Produces a lossless CST. Grammar is a map of characters to scanlet/parselet functions.
2. **step-read** (`meme.tools.clj.cst-reader`) — lowers CST to Clojure forms. No `read-string` delegation — all values resolved natively via `meme.tools.clj.resolve`. Reader conditionals are preserved as `CljReaderConditional` records.
3. **step-evaluate-reader-conditionals** (`meme.tools.clj.stages`) — materializes `#?` / `#?@` for the target platform. Runs on eval paths only; tooling sees records.
4. **step-expand-syntax-quotes** (`meme.tools.clj.expander`) — syntax-quote AST nodes → plain Clojure forms. Only needed before eval.

The core `stages/run` calls stages 1–2, returning records for tooling. `run-string` chains 1–4 before eval.

The split makes each stage independently testable and the composition extensible.
The grammar's lexical scanlets handle all character-level concerns (strings, chars, comments
are individual tokens, so `\)` inside a string is just a `:string` token,
not a closing paren). The Pratt parser engine handles all structural concerns.

`meme.tools.clj.stages` composes the stages as `ctx → ctx` functions, threading a
context map with `:source`, `:cst`, `:forms`.


## Reader-conditional evaluation as a pipeline stage

An earlier iteration of the reader had a `:read-cond` flag with two
modes: `:eval` (default — pick the current platform's branch at read
time) and `:preserve` (return a `ReaderConditional` record). The flag
conflated two orthogonal questions — *is `#?` still a record?* and *are
these forms eval-ready?* — and created a visible asymmetry: a direct
`m1clj->forms` call on `.cljc`-like source silently dropped the
off-platform branch, while the `to-clj` CLI adapter (which hardcoded
`:preserve`) did not.

The fix moves platform materialization out of the reader entirely.
`step-read` unconditionally returns records; a new stage
`step-evaluate-reader-conditionals` materializes branches when needed.
Eval paths (`run-string`, `run-file`, REPL) compose the stage between
read and syntax-quote expansion — matching native Clojure's order, where
the reader evaluates `#?` before `` ` `` is processed. Tooling paths
(`m1clj->forms`, `m1clj->clj`, `format-m1clj`, `to-clj`) skip the stage and
see the full record, making conversion lossless by default.

The stage recurses into `CljSyntaxQuote`, `CljUnquote`, and
`CljUnquoteSplicing` interiors, so `` `#?(:clj x :cljs y) `` collapses
to `` `x `` on JVM, matching Clojure. It supports a `:platform` opt for
cross-platform materialization (generating `.cljs` output on JVM, for
example) and respects `:default` as a fallback — a gap the old `:eval`
branch never filled. Odd-count branch lists are validated at the stage,
not at read time.

Limitation: `#?@` inside a map literal still fails at read time because
the map requires an even number of children. This matches Clojure's
`:read-cond :preserve` behavior.


## Centralized value resolution (meme.tools.clj.resolve)

All value resolution — numbers, strings, chars, regex, auto-resolve
keywords, tagged literals — is centralized in `meme.tools.clj.resolve`.
The parser deals only with structural parsing; value interpretation is
delegated to resolve.

The goal is zero `read-string` delegation: the toolkit parses everything
natively, including forms that were previously opaque (syntax-quote,
reader conditionals, namespaced maps). Platform asymmetries (JVM vs
ClojureScript) are isolated in resolve.


## Custom tokenizer (not Clojure's reader)

Clojure's reader rejects `f(x y)` as invalid syntax. m1clj fundamentally
changes what constitutes a valid token sequence (and `clj-lang` reuses
the toolkit's parser engine for native Clojure source). A custom
tokenizer is unavoidable.


## AST tier (post-5.0)

The 5.0 codebase reaches plain Clojure forms via an intermediate **AST**
(`meme.tools.clj.ast.*`). The AST is the lossless representation:
position, leading trivia, and notation choice (`'x` vs `quote(x)`,
`@a` vs `clojure.core/deref(a)`, namespaced-map prefix, set source
order, …) live on record fields rather than form metadata, so they
survive walkers that don't know about the toolkit.

Earlier iterations carried this information on form metadata
(`:m1clj/leading-trivia`, `:m1clj/sugar`, etc.). That worked for the
single happy path but broke as soon as a third-party walker rebuilt the
form, since metadata flows are unreliable across `(into ...)`,
`(map identity ...)`, and similar. Moving the data into AST records
removes the fragility.

The form path remains structural: `m1clj->forms`, `forms->m1clj`,
`forms->clj` produce and consume plain Clojure forms with no notation
tagging. Tooling that needs round-trip fidelity (formatters,
refactorers, transpilers) consumes AST nodes instead via
`m1clj-lang.api/m1clj->ast` (or `clj->ast` for native Clojure source).


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

All `#`-prefixed forms are parsed natively by the toolkit — no
delegation to `read-string`. The `#` dispatch character combines with
the next character(s) to form the head of an M-expression:

- `#?(...)` — reader conditional. `#?` is the head.
- `#?@(...)` — splicing reader conditional. `#?@` is the head.
- `#(...)` — anonymous fn. `#` is the head. Body is a single m1clj
  expression; `%`, `%1`, `%2`, `%&` are collected and used to build
  the `fn` parameter vector. `#(inc(%))` → `(fn [%1] (inc %1))`.
- `#{...}` — set literal. `#` dispatches with `{`.
- `#"..."` — regex literal.
- `#'x` — var quote. Prefix operator.
- `#_x` — discard. Prefix operator.
- `#inst "..."`, `#uuid "..."` — tagged literals.
- `#:ns{...}` — namespaced maps.

Reader conditionals parse all branches and return a `CljReaderConditional`
record preserving every branch. The `step-evaluate-reader-conditionals`
pipeline stage materializes the platform branch for eval paths. See
"Reader-conditional evaluation as a pipeline stage" below for the
architectural rationale.

Syntax-quote (`` ` ``) is also parsed natively — its interior uses
m1clj syntax with `~` (unquote) and `~@` (unquote-splicing). Macro
templates are written in m1clj syntax: `` `if(~test do(~@body)) ``.


## Quote uses m1clj syntax

`'` is a prefix operator that quotes the next m1clj form. There is no
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


## Syntax-quote uses m1clj syntax

Syntax-quote (`` ` ``) is parsed natively — its interior uses m1clj
call syntax, not S-expressions. `~` (unquote) and `~@` (unquote-splicing)
work as prefix operators inside syntax-quote.

```
defmacro(unless [test & body] `if(~test nil do(~@body)))
```

The m1clj reader handles symbol resolution, gensym expansion, and
unquote splicing — the same transformations Clojure's reader performs,
but applied to m1clj-parsed forms. No `read-string` delegation.


## Signed number heuristic

`-1` is a negative number. `-(1 2)` is a call to `-` with args `1 2`.

The rule: if a sign character (`-` or `+`) is immediately followed by a
digit, it is part of a number token. If followed by `(`, whitespace, or
anything else, it is a symbol. This is a one-character lookahead in the
tokenizer. No ambiguity — but the decision affects user expectations, so
it has a scar tissue test.


## Auto-resolve keywords

`::foo` is resolved natively by the m1clj reader:

- **With `:resolve-keyword` option** (REPL, file runner): resolved at
  read time to `:actual.ns/foo`, matching Clojure's semantics. The
  caller provides the resolver function.
- **Without option on JVM/Babashka** (tooling, bare `m1clj->forms`):
  deferred to eval time. The printer detects the deferred form and
  emits `::foo` for roundtripping.
- **Without option on CLJS**: errors, since deferred resolution would
  silently produce wrong results.


## Native value resolution for primitives

Numbers, strings, character literals, and regex patterns are tokenized as
raw text by the toolkit's scanner, then resolved natively by
`meme.tools.clj.resolve`. The goal is zero delegation to `read-string` —
the toolkit parses numeric formats (hex, octal, ratios, BigDecimal), string
escape sequences, and character names itself, guaranteeing identical
behavior to the host platform without depending on its reader.


## Platform tiers

The codebase is split into four platform tiers:

- **Generic tools** (`meme.tools.{parser, lexer, render}`) — portable `.cljc`, runs on JVM, Babashka, and ClojureScript. Language-agnostic library surface: Pratt parser engine, scanlet builders, Wadler-Lindig Doc layout.
- **Clojure-surface commons** (`meme.tools.clj.{lex, errors, forms, resolve, expander, cst-reader, stages, values}`) — portable `.cljc`. Shared across any Clojure-flavored frontend: lexical conventions, Clojure atom resolution, CST reader, stages, the `Clj*` AST records.
- **Core translation** (`m1clj-lang.{api, grammar, lexlets, parselets, form-shape, printer, formatter.flat, formatter.canon}`, `m2clj-lang.{api, grammar, parselets, lexlets, form-shape, printer, formatter.flat, formatter.canon}`, `clj-lang.api`) — portable `.cljc`. Each guest's syntactic identity: grammar spec, scanlet/parselet glue, semantic decomposition, Doc-tree builder, formatters. Pure functions with no eval or I/O dependency. clj-lang is a thin shim that reuses the toolkit's native Clojure parser and m1clj's printer in `:clj` mode.
- **Runtime** (`meme.tools.{repl, run}`, `meme.tools.clj.{repl, run}`, `m1clj-lang.{repl, run}`, `m2clj-lang.{repl, run}`, `meme.{registry, loader, cli}`) — `.clj`, JVM/Babashka only. Require `eval` and `read-line`/`slurp`. The lang-level `run`/`repl` files are thin shims that inject grammar and delegate to `meme.tools.clj.{run, repl}`.
- **Test infrastructure** (test-runner, dogfood-test, vendor-roundtrip-test) — `.clj`, JVM only.
  These use `java.io`, `PushbackReader`, `System/exit`.

This separation is honest about what's portable. The `.clj` extension
prevents the ClojureScript compiler from attempting to compile JVM-only
code.


## `#()` printer shorthand

The printer emits `#(body)` when it sees a `CljAnonFn` AST node, and
`fn([params] body)` when it sees a `CljList` whose head is the symbol
`fn`. The notation choice rides on the AST node type itself, not on
form metadata — pre-AST drafts threaded a `:m1clj/sugar` form-metadata
key for this distinction; the AST cutover (phase A5) moved that
information onto record fields so it survives arbitrary form walkers.

This is an instance of the syntactic transparency principle: the parser
selects the AST node kind from the source surface, the printer
reconstructs the surface from the node kind. No body inspection or
surplus-param heuristic is needed — the AST builder's
`build-anon-fn-params` already records the parameter vector at parse
time from the `%` params found in the body.

The plain-form path (`m1clj->forms` followed by `forms->m1clj`) is
intentionally lossy here: a `CljAnonFn` lowers to `(fn* [...] body)`,
which prints back as `fn(...)`. Tooling that needs the original
notation must consume the AST tier directly via `m1clj->ast` or
`format-m1clj`.


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

The parser engine records `(line, col)` on each token. Position tracking uses the **scanner line model** (only `\n` is a line break, `\r` occupies a column). This is handled internally within `meme.tools.parser`. The display line model (`str/split-lines` in `meme.tools.clj.errors`) may diverge for CRLF sources — `format-error` bridges the gap by clamping carets.


## Centralized error infrastructure (meme.tools.clj.errors)

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

m1clj is a **syntactic lens**, not a compiler. The read→print path
must be transparent: if the user writes `'x` (sugar), it prints back as
`'x`; if they write `quote(x)` (explicit call), it prints back as
`quote(x)`. The same applies to `@`/`clojure.core/deref` and
`#'`/`var`.

**Principle:** Every piece of user syntax that has more than one
representation must be preserved through the pipeline. When two
notations would collapse into the same Clojure form, the AST must
record which notation was used.

**Implementation (current).** The AST tier (`meme.tools.clj.ast.*`)
records the choice on record fields: `CljQuote` vs `CljList(quote ...)`,
`CljDeref` vs `CljList(clojure.core/deref ...)`, `CljVar` vs the
explicit-call form, `CljAnonFn` vs `CljList(fn ...)`,
`CljNamespacedMap` vs a plain `CljMap`, `CljSet` carries source order.
The printer dispatches on the AST node type, so the original notation
survives the round-trip.

**Why this matters:** Without this, the pipeline silently normalizes
user code. `var(x)` becomes `#'x`. `quote(list)` becomes `'list`.
A syntactic lens that rewrites your code is not a lens — it's a
formatter with opinions. Every new syntax feature should be checked:
can two notations produce the same form? If yes, the AST must record
the distinction.

**Form path is intentionally lossy.** `m1clj->forms` and
`forms->m1clj` operate on plain Clojure data, with no notation tagging.
Tooling that needs lossless round-trip consumes the AST.

**Known remaining losses (AST path):**
- Commas: treated as whitespace, not preserved.
- `#_` discarded forms: gone by design.


## Three layers of formatting: notation, form-shape, style

The printer and formatter were originally one concern: "given a form, produce m1clj text." As style knobs accumulated (`head-line-args`, `definition-forms`, `pair-body-forms`, `binding-forms`, a hardcoded `defn` bump), the one-layer design started carrying three different kinds of knowledge at once. Pulling them apart made each one addressable on its own.

The three concerns, ranked by how often they change:

1. **Notation** — how the call syntax renders: parens, delimiter placement, `:m1clj` vs `:clj` mode. This is fixed per language and lives in `m1clj-lang.printer`. The printer knows *no form names and no slot semantics* beyond its fallback recursion.
2. **Form-shape** — what the parts of a special form *mean*. A registry keyed by head symbol maps to decomposers; each decomposer emits `[slot-name value]` pairs (e.g. `(defn foo [x] body)` → `[[:name foo] [:params [x]] [:body body]]`). This is language-level semantic vocabulary and lives in `m1clj-lang.form-shape`. **One per lang** — each lang carries its own registry.
3. **Style** — opinions *per slot name*, not per form. `:head-line-slots #{:name :params ...}` says "keep these slots with the call head on break," and `:force-open-space-for #{:name}` handles the `defn( ` convention. **N per lang** — canon and flat today, compact or project-local styles tomorrow.

The ratio is **N styles : 1 form-shape : 1 lang**. Style and form-shape live at different layers of composition — style is *formatter-owned*, form-shape is *lang-owned* — which is why they're distinct keys in the lang-map rather than folded into one.

### Why three rather than two

An earlier sketch had just notation + style. The trouble was that style ended up with two kinds of entries: numeric priorities (`{defn 1, defmethod 2}`) and form-name sets (`:pair-body-forms #{case cond condp}`). Those are *form-shape* knowledge wearing a style-map hat. A user macro `my-defn` couldn't get the same layout as `defn` without editing style — even though the user's opinion about layout hadn't changed.

Splitting form-shape out gives each axis a single, clean vocabulary. Style talks about slots; form-shape talks about decomposition. A new `my-defn` macro needs one registry entry and inherits every style opinion that mentions slots it produces.

### Worked examples

**The `defn` bump.** Before the split, the printer had this special case: if `head ∈ #{defn defn- defmacro}` and `(vector? (second args))`, bump the head-line count from 1 to 2. The rule was incomplete (a docstring arg pushed params to body, missing the intended layout) and unreachable for user macros. After the split, `decompose-defn-like` emits `[:name] [:doc?] [:params] [:body*]` slots, and canon's style declares `:head-line-slots` includes both `:name` and `:params`. The "bump" falls out of the decomposition; the docstring case now also renders correctly.

**Defmethod's graceful break.** `(defmethod area :circle [{:keys [radius]}] ...)` at narrow widths used to have name + dispatch-val on the head line and push `[params]` to body by fiat. Under the new model, all three signature slots sit on the head line when they fit, and break uniformly when they don't. No arbitrary third-arg demotion.

**User macro inheritance.** `(my-defn foo [x] body)` with a registered `{'my-defn (get registry 'defn)}` alias — or with `with-structural-fallback` wrapping the registry — renders with the full defn layout, including the open-paren space. Notation, form-shape, and style compose via plain map operations: no code change.

### Override seams

All four extension points compose via `assoc`/`merge`:

- **Swap a style** — pass different `:style` to `canon/format-form`.
- **Extend a registry** — `(assoc registry 'my-macro my-decomposer)`.
- **Enable inference** — `(with-structural-fallback registry)`.
- **Override one slot's rendering** — `{:slot-renderers {:clause my-fn}}` in style; merges over printer defaults.

See `m1clj-lang/CLAUDE.md` for the full vocabulary and consumer sketches (LSP, lint, refactor).

## Registry as pure infrastructure — inverted control for built-in langs

The registry (`meme.registry`) owns generic infrastructure: a keyword-to-lang-map index, extension dispatch, user-lang registration via EDN, safety guards.  It does *not* own the list of built-in langs.

Earlier the registry imported each built-in's `api` namespace directly (`m1clj-lang.api`) and called `register-builtin!` on each.  That had two costs:

1. A circular dependency.  The registry imported `m1clj-lang.api`; `m1clj-lang.api` transitively used `m1clj-lang.run`; `m1clj-lang.run` needed the registry back to dispatch by file extension.  The cycle was worked around with `requiring-resolve` calls in `m1clj-lang.run`, `m1clj-lang.repl`, `meme.cli`, and `meme.loader` — four invisible runtime dependencies that didn't show up in the static `:require` graph.

2. A hidden manifest.  The "lang registry" was secretly also the "list of built-in langs," which meant adding a lang required editing two namespaces: the new lang and the registry.  Worse, every CLI invocation (even `meme format`) paid for fully loading every built-in's namespace graph, because all were imported unconditionally at registry ns-load.

Both problems have the same fix: invert the control.

- The **registry** imports no langs.  It's pure data + generic operations.
- Each lang's **`api` namespace** calls `registry/register-builtin!` at the bottom of its file.  Loading the ns has a registration side-effect.
- The **app** (`meme.cli` in this project) is what decides which built-ins to ship by explicitly requiring their api namespaces:

```clojure
(ns meme.cli
  (:require [meme.registry :as registry]
            [meme.loader :as loader]
            [m1clj-lang.api]      ;; side-effect: registers :m1clj
            ;; additional built-ins added the same way
            ...))
```

The dependency graph becomes acyclic.  The registry is what it claims to be.  Adding a built-in is now one `:require` in the app plus one `register-builtin!` at the new lang's ns bottom — two lines, both at appropriate layers.

Why a side-effecting ns-load instead of, say, declarative metadata?  Because Clojure's ns-load is already the thing we want: when the app says "I want this lang available," it requires it.  Registration happens as part of that.  The alternative (a separate declarative manifest) would duplicate what `:require` already does.

User langs continue to go through `register!`, which validates EDN-style config, resolves symbols, and refuses to shadow built-ins.  The two registration paths serve different audiences: `register-builtin!` for langs that ship as code in the same process, `register!` for langs declared via EDN config.

## Pipeline contracts — declarative requirements, not spec

Each stage in `meme.tools.clj.stages` declares its required ctx keys in a public `stage-contracts` map:

```clojure
{:step-parse                        {:requires #{:source} :requires-opts #{:grammar} :produces #{:cst}}
 :step-read                         {:requires #{:cst}    :produces #{:forms}}
 :step-evaluate-reader-conditionals {:requires #{:forms}  :produces #{:forms}}
 :step-expand-syntax-quotes         {:requires #{:forms}  :produces #{:forms}}}
```

Stages validate their ctx against the contract at entry (via an internal `check-contract!`) and throw `:m1clj/pipeline-error` with the missing key(s) and the actual ctx keys present.  Miscomposed pipelines (e.g. calling `step-read` before `step-parse`) surface their mistake at the point of composition instead of raising a deep-inside NPE.

A heavier spec-based validation (PL8 in `PRD.md`) was tried and removed during an earlier refactor.  This replacement is deliberately lighter:

- **Only `:requires` is runtime-enforced.**  `:produces` is documentation — if a stage fails to produce what it claims, the next stage's `:requires` check catches it, so post-condition checks would be redundant.
- **Presence, not type.**  Type-specific checks stay inline in the stages that need them (e.g. `step-parse` still verifies `(string? source)` after the presence check).  Generalizing to types would re-introduce a schema language without clear payoff.
- **Data, not macros.**  `stage-contracts` is a plain map.  Custom stages for lang extensions can extend their own contracts in the same shape without adopting a DSL.

## Clojure-surface commons (`meme.tools.clj.*`)

Between the lang-agnostic `meme.tools.{parser, lexer, render}` and the m1clj-specific `m1clj-lang.*`, a middle tier `meme.tools.clj.*` carries Clojure-flavored concerns: symbol/keyword lexical conventions, numeric atom resolution, the `Clj*` AST records, CST-to-Clojure-forms lowering, the stages framework, the syntax-quote expander, value serialization, the native-Clojure parser, and the run/repl harnesses.

The commons were extracted from `m1clj-lang.*` once a second Clojure-flavored frontend (`clj-lang`) proved that none of these concerns are m1clj-specific. They describe what any frontend targeting Clojure-the-host needs to handle — surface syntax aside. Keeping them under `m1clj-lang` was a misnomer: `m1clj-lang.cst-reader` read CST nodes but emitted plain Clojure, not anything m1clj-shaped; `m1clj-lang.resolve` resolved *Clojure* atom text, not m1clj constructs.

The rubric is the sibling-lang test: if any Clojure-flavored frontend would need the function and would not need to specialize it, it belongs in `meme.tools.clj.*`. Grammar, parselets, form-shape, and the printer stay in `m1clj-lang.*` because that is where surface syntax diverges between langs.

The toolkit stays accessible to guest frontends that want the same commons without inheriting m1clj's surface: a `.mcj` lang (McCarthy's original brackets) or a user's own Clojure frontend requires only a grammar and a handful of parselets.

## `Clj*` AST records named for content, not surface

The records the AST tier emits (`CljSyntaxQuote`, `CljUnquote`, `CljUnquoteSplicing`, `CljRaw`, `CljAutoKeyword`, `CljReaderConditional`, etc.) wrap Clojure-semantic values — not m1clj-surface ones. They record *what the form means at the Clojure host level*, whether the caller wrote it in m1clj or any other frontend.

Earlier these were named `Meme*` (`MemeSyntaxQuote`, etc.). That name was misleading: an unquote inside a guest frontend's source file is not language-specific, it's a Clojure unquote. A guest Clojure-flavored frontend has no business importing a `Meme*` record to represent its own unquotes.

Predicates follow: the prior `meme-reader-conditional?` is now `clj-reader-conditional?`. The predicate answers "is this a Clojure reader conditional as represented in our AST?" — nothing about any specific guest.

## `:m1clj/*` ex-info vocabulary

The toolkit's runtime errors carry `:type` keys under the `:m1clj/`
namespace (`:m1clj/pipeline-error`, `:m1clj/deprecated-opt`,
`:m1clj/missing-grammar`). The namespace is historical — it predates the
language-vs-toolkit split — but external code already catches these keys,
so they are stable. New keys for toolkit-level errors should follow the
same prefix.

## Sibling langs are peers, not modes

A new Clojure-flavored frontend ships as a separate registered lang, not as a `--lang` flag on the toolkit. Each peer owns its own grammar, parselets, form-shape registry, and printer; all share the Clojure-surface commons (`meme.tools.clj.*`) and the generic tools (`meme.tools.{parser,lexer,render}`).

`clj-lang` is the first proof of the model: native S-expression Clojure surface as a peer of m1clj, sharing the parser engine, AST, and printer.

A mode flag would have been cheaper in files — but wrong in design. Sibling syntaxes differ in scanners (e.g. infix word operators), parselets (forms with no equivalent in another guest), form-shape registries (different special-form decompositions), and printers. A mode flag would be a euphemism for multiple languages in one file.

The peer structure makes the Clojure-surface commons load-bearing: anything that two Clojure-flavored frontends would share has to live there and be reusable. The shape `m1clj-lang.api`/`run`/`repl` codifies the contract for new frontends: grammar + parselets + form-shape registry + printer, plus a thin shim trio that injects the grammar/banner.

## CAS retry for `register!` atomicity

`meme.registry/register!` validates a new user-lang registration against the current registry state, then commits via `compare-and-set!` in a retry loop. Earlier shapes did validation inside the `swap!` updater; that pattern conflated two concerns: the validation *decision* (which requires reading the snapshot) and the *atomic commit* (which the swap provides).

The split matters under concurrency. Two threads racing to register the same extension had to both fail, not one succeed silently: the CAS loop revalidates on each snapshot, so the second thread sees the first commit and rejects. Throwing from inside `swap!` also had a quieter cost: `swap!` is documented to retry on CAS failure, which meant validation code was implicitly idempotent-or-die. The retry-at-call-site shape makes the atomicity guarantee the registry provides honest rather than accidental.

EDN resolution (`resolve-edn`, which reads the classpath for the lang's grammar) runs *once* outside the loop — it's pure and expensive, and the loop body must stay fast.

## Lang backend

m1clj uses a single implementation of the m1clj↔Clojure translation, registered as `:m1clj` in the lang registry.

The pipeline combines a unified scanlet-parselet Pratt parser (`meme.tools.parser` with `m1clj-lang.grammar`), the AST tier (`meme.tools.clj.ast.*`), and a Wadler-Lindig document printer (`m1clj-lang.printer`). The AST tier preserves trivia, position, and notation choice as record fields; the form path is structural.

**Use for:** formatting, tooling integration, roundtrip-sensitive workflows.

## Known divergences from Clojure's reader

### Leading-dot floats (`.5`, `.5M`, `.5e1`)

Clojure reads `.5` as the float `0.5`. m1clj tokenizes `.5` as a symbol because `.` is the Java interop prefix (`.method`). The tokenizer enters number mode only when the current character is a digit. This is an intentional divergence — in m1clj, `.foo` is always an interop method call, and leading-dot floats must be written with an explicit zero: `0.5`.

### Surrogate pairs in string Unicode escapes

Clojure accepts `"\uD800\uDC00"` (a valid UTF-16 surrogate pair encoding U+10000) and produces the supplementary character. m1clj rejects each `\uXXXX` escape individually — if the code point falls in the surrogate range (U+D800..U+DFFF), it errors regardless of whether the next escape forms a valid pair. This is a defensive choice that prevents isolated surrogates from entering the output. Users can include supplementary characters directly as literal UTF-8 in source text.

### Map key ordering for maps with >8 entries

Clojure's `array-map` preserves insertion order for up to 8 entries. Beyond that, it promotes to `PersistentHashMap` which does not preserve order. Since the form-path lowering builds maps via `(apply array-map forms)`, maps with 9+ keys may have their key order shuffled in plain-form output. The AST tier (`CljMap` with `:pairs`) preserves key order; AST-driven paths like `format-m1clj` are not affected. This is a Clojure platform limitation surfacing only in the lossy form path.

### Comments on primitive map keys

Comments preceding a primitive map key (keyword, number, string, boolean) are dropped on the form path because primitive types do not implement `IMeta` in Clojure and cannot carry leading-trivia metadata. **The AST tier preserves these comments on `:leading-trivia` record fields, so `format-m1clj` and other AST-driven tooling do not lose them.** Plain `m1clj->forms` consumers see the platform limitation; AST consumers do not.

### End-of-line comment repositioning

The m1clj formatter uses a "comment attaches to next form" model inherited from the tokenizer. An end-of-line comment like `foo(x ;; note\n y)` is attached to `y` (the next form), not to `x` (the preceding form). When formatted, the comment appears before `y` rather than after `x`. This preserves comment content but changes its visual position. This is an inherent consequence of the "attach to next token" architecture.

### Sorted collections lose ordering through roundtrip

`sorted-map` and `sorted-set` have no literal syntax in Clojure. When printed, they appear as regular maps/sets. Re-parsing produces `PersistentArrayMap`/`PersistentHashSet`, losing the sorted property. This matches Clojure's own limitation.

### `@f(x)` is `@(f x)`, not `(@f x)`

The `@` deref prefix applies to the next complete form. `@f(x)` parses as `(deref (f x))` — the deref wraps the call expression `f(x)`. This is correct and consistent with how all prefix operators work in m1clj: they bind to the next form, including any adjacent call arguments.

### Regex flags not preserved in printer output

The printer accesses the regex pattern via `.pattern` (JVM) or `.-source` (CLJS), which returns only the pattern body without flags. If a regex was constructed programmatically with flags (e.g., `re-pattern` with inline flags), the flags appear in the pattern string itself (e.g., `(?i)...`) and are preserved. External flag objects are not representable in `#"..."` literal syntax.

### Symbols with whitespace cannot roundtrip

Programmatically constructed symbols containing whitespace, parentheses, or other syntax-significant characters (e.g., `(symbol "foo bar")`) cannot be faithfully printed and re-parsed. Clojure has no escape syntax for symbols — `pr-str` returns the same as `str` for symbols. This means such symbols print as raw text which re-parses as different forms: `(symbol "foo\nbar")` → `foo\nbar` → two symbols `foo` and `bar`. This matches Clojure's own limitation and affects all Clojure-based syntax tools.

### Inline comments on primitive values — form path vs AST path

This is a tale of two paths.

**Form path (lossy).** `m1clj->forms` / `forms->m1clj` propagate trivia
on metadata; primitive values (numbers, strings, booleans, characters,
nil, regex) cannot carry metadata under Clojure's `IMeta` rules, so a
comment immediately before a primitive is dropped at lowering. Comments
that **survive** the form path: those before symbols, keywords,
collections, and calls. Comments that are **lost**: those before
numbers, strings, booleans, characters, nil, and regex literals.

**AST path (lossless).** The AST tier (`CljNumber`, `CljString`,
`CljChar`, `CljBool`, `CljNil`, `CljRegex`) carries `:trivia` on
record fields, so comments survive even when their target is a
primitive. `format-m1clj` and any tooling that consumes
`m1clj-lang.api/m1clj->ast` does not lose these.

The form path is intentionally structural; the AST path is intentionally
lossless. Pick the right tier for the job.

### Invisible characters in symbols

A set of Unicode control and glyph-modifier characters is rejected inside symbols by `meme.tools.clj.lex/invisible-char?`. They'd otherwise let an attacker (or an unlucky copy-paste) construct two symbols that render identically but compare as different values. The toolkit is stricter than Clojure here — Clojure's reader accepts most of these. The blocked ranges:

- C0 controls (U+0000–U+001F), DEL (U+007F), C1 controls (U+0080–U+009F).
- NBSP (U+00A0) and soft hyphen (U+00AD).
- Surrogates (U+D800–U+DFFF).
- Zero-width joiners and formatting chars: U+200B–U+200F, U+202A–U+202E, U+2060–U+2069.
- BOM (U+FEFF).
- Variation selectors VS1–VS16 (U+FE00–U+FE0F) — invisible glyph modifiers.

### Unicode line separators count as line breaks

U+2028 (LINE SEPARATOR) and U+2029 (PARAGRAPH SEPARATOR) are recognized as line terminators by `meme.tools.parser/build-line-starts`, alongside `\n`, `\r`, and `\r\n`. Without this, error positions after a U+2028/U+2029 would be reported on the preceding line.

### Slash in symbol and keyword tokens

- Bare `/` — valid symbol (division).
- `ns/name` — valid.
- `ns//` — valid (the special division-in-namespace case, matching Clojure's `clojure.core//`).
- Bare `:/` — valid, reads as `(keyword "/")`.
- Symbols starting with `/` that aren't just `/` (e.g. `//`, `//a`, `/foo`) — rejected at read time. Matches Clojure.
- Multi-slash `foo/bar/baz`, `:ns/a/b` — accepted. Clojure also accepts these; the first `/` is the ns/name separator and the rest becomes part of the name.

### Char literal `\uNNNN` tail validation

Exactly four hex digits follow `\u`. Any trailing alphanumeric character on a `\uNNNN` token is rejected as an invalid character literal: `\u00410`, `\u0041G`, `\u00g1` all error. A lone `\u` with no hex digits still reads as the char `u` (pre-existing m1clj behavior retained).


### Scanner: structural vs semantic validation

The scanner layer (lexical scanlets in `meme.tools.clj.lex`, wrapped by `m1clj-lang.lexlets` for m1clj's grammar) is a structural scanner — it partitions input into tokens without knowing Clojure's semantic rules. Semantic validation is split between the resolver (`meme.tools.clj.resolve`) and the CST reader (`meme.tools.clj.cst-reader`):

- **Reserved dispatch chars** (`#<`, `#=`, `#%`): The scanner classifies `#=foo` as `:tagged-literal` (structurally, it IS `#` + symbol). Whether `=` is a reserved dispatch character is a semantic rule enforced downstream.
- **Unterminated strings/regex**: The scanner returns the EOF position without signaling error, but the resolver (`resolve-string`, `resolve-regex`) detects the missing closing delimiter and throws with `:incomplete true` — enabling the REPL to prompt for continuation.
- **Invalid keywords** (bare `:`, trailing `:foo:`, triple `:::foo`): The scanner greedily consumes `symbol-char?` characters; the CST reader validates keyword structure and rejects malformed forms.
- **`:invalid` token error messages**: The pipeline carries `:raw` content from `:invalid` tokens into parser errors for actionable diagnostics.


## Roadmap

### `.mcj` — McCarthy's original M-expression syntax

McCarthy (1960) defined M-expressions as the surface syntax for Lisp. m1clj implements M-expressions for Clojure with one rule: `f(x y)` → `(f x y)`. But m1clj's syntax is a Clojure-flavored dialect — it inherits Clojure's reader macros, dispatch forms, and data literal conventions.

McCarthy's original syntax was different: `car[x]` used square brackets for application, `[p₁ → e₁; … ; pₙ → eₙ]` for conditionals, `λ[[x]; e]` for lambda, `label[f; λ[[x]; …]]` for recursive binding. A faithful implementation of the original notation — as a guest language with file extension `.mcj` (McCarthy John, M-expression CloJure) — would honor the historical origin while demonstrating the guest-language system.

The infrastructure exists: the unified Pratt parser takes a grammar spec, trivia classification is part of the grammar, and the lang registry supports guest languages. `clj-lang` already proves the multi-guest model. A `.mcj` lang would supply its own grammar (square-bracket application, semicolon separators, arrow conditionals) and its own scanlets/parselets, reusing the same parser engine (`meme.tools.parser`) and scanlet builders (`meme.tools.lexer`).

### Generic CST reader

`meme.tools.clj.cst-reader` lowers a Clojure-surface CST to Clojure forms. The pattern is general — every language that uses the Pratt parser needs CST → host-language lowering. The generic parts (tree walking, trivia extraction, discard filtering) could be extracted with language-specific value resolution and node handlers plugged in. The current shape is already shared between m1clj and `clj-lang`; further extraction would benefit a future non-Clojure host.

### Completed: Data-driven scanner (unified scanlet-parselet architecture)

The scanner is now fully data-driven. The unified scanlet-parselet Pratt parser (`meme.tools.parser`) defines scanning as part of the grammar spec. Character dispatch, trivia classification, and structural parsing are all configured via the grammar map. Clojure-surface consume functions live in `meme.tools.clj.lex` (shared across any Clojure-flavored frontend) and are wrapped into scanlets by the generic builders in `meme.tools.lexer`; `m1clj-lang.lexlets` is a thin shim that forwards to `meme.tools.clj.lex`. This replaces the previous separate tokenizer that had Clojure-family knowledge baked in.

The grammar spec shape:
```clojure
{:nud        {char → scanlet-fn}       ;; character dispatch
 :nud-pred   [[pred scanlet-fn] ...]   ;; predicate-based dispatch
 :trivia     {char → trivia-fn}        ;; trivia classification
 :trivia-pred [[pred trivia-fn] ...]
 :led        [{:char c :bp n ...} ...]} ;; postfix/infix rules
```

This allows the same parser engine to handle languages with different character vocabularies — each language supplies its own grammar spec.


## The call/data tension: lists, homoiconicity, and M-expressions

m1clj has one syntactic rule for non-empty lists: `head(args...)` → `(head args...)`. This rule does not distinguish between function calls and data lists. The distinction is fundamental to understanding m1clj's design and its relationship to Lisp's homoiconicity principle.

### The structural identity

In Clojure, `(+ 1 2)` and `(quote (1 2 3))` produce the same data structure: a list. The difference is purely contextual — the evaluator treats the first element as something to call. At **read time**, there is no distinction between code and data. This is homoiconicity: code is data, data is code.

m1clj inherits this property. `+(1 2)` and `1(2 3)` both produce lists. The reader performs a purely syntactic transform — it does not know or care whether the head is callable. `1(2 3)` reads as `(1 2 3)`, which is a valid Clojure form that will fail at eval time ("1 is not IFn"), just as `(1 2 3)` does in Clojure.

### What m1clj adds and what it doesn't

m1clj adds: visual distinction between the head and the arguments of a list. In `+(1 2)`, the `+` is visually separated from the `(1 2)`, making the call structure immediately apparent. This is McCarthy's original M-expression idea from 1960.

m1clj does NOT add: a separate notation for "data lists" vs "call lists." In S-expressions, both use `(...)`. In m1clj, both use `head(...)`. The syntactic surface changed, but the underlying identity of code and data is preserved.

### Quote as the escape hatch

Lisp's answer to "how do I write a list that isn't a call" has always been `quote`. This carries over to m1clj:

| Intent | Clojure | m1clj | Result |
|--------|---------|------|--------|
| Call + to 1 and 2 | `(+ 1 2)` | `+(1 2)` | `(+ 1 2)` |
| Data list of 1, 2, 3 | `'(1 2 3)` | `'1(2 3)` | `(quote (1 2 3))` |
| Construct list at runtime | `(list 1 2 3)` | `list(1 2 3)` | `(list 1 2 3)` |
| Empty list | `()` or `'()` | `()` | `()` |

Quote signals intent: `'1(2 3)` says "this is data." `1(2 3)` without quote says "call 1 with args 2 and 3" — which will fail at eval time, but is structurally valid.

### Consequences for the printer

The m1clj printer converts Clojure forms back to m1clj syntax. When it encounters a non-empty list `(f x y)`, it emits `f(x y)`. This is correct for all lists:

- `(+ 1 2)` → `+(1 2)` — looks like a call, is a call. Correct.
- `(1 2 3)` → `1(2 3)` — looks like a call to 1. Structurally correct; will error at eval.
- `(nil 1)` → `nil(1)` — looks like a call to nil. Valid m1clj; `nil` is a legal head.

The printer does not inject `quote` because it cannot know whether the list was intended as a call or as data — that information is not present in the Clojure form. A `(1 2 3)` produced by `(list 1 2 3)` at runtime is indistinguishable from one produced by reading `'(1 2 3)`. This is a consequence of homoiconicity, not a printer limitation.

When the list originates from m1clj source with `'1(2 3)`, the AST records the quote on a `CljQuote` node. The printer uses that to reproduce the quote sugar. Plain forms produced programmatically (no AST, no notation tag) print without sugar, which is correct given that they have no preserved intent.

### Implications for language designers using `meme.tools.*`

The generic parser engine (`meme.tools.parser`) and render engine (`meme.tools.render`) are language-agnostic. A language built on these tools inherits the call/data conflation unless it adds its own notation. Options:

1. **Accept the conflation** (m1clj's choice). Rely on quote for data lists. Minimal syntax, maximal consistency with Lisp's code-as-data principle.
2. **Add a data list notation** (e.g., `#list(1 2 3)` or `<1 2 3>`). Requires a new parselet in the grammar and corresponding printer logic. Breaks the "one rule" simplicity.
3. **Infer from context** (e.g., numeric heads → data list). Requires semantic knowledge at read time. Violates the principle that the reader is a pure syntactic transform.
4. **Use a different delimiter** (e.g., `f[x y]` for calls, `(1 2 3)` for data). Requires repurposing existing Clojure delimiters, creating incompatibility.

m1clj chose option 1 because it preserves three properties simultaneously: syntactic simplicity (one rule), Clojure compatibility (all forms roundtrip), and homoiconicity (code and data have the same structure). The cost is that the printer cannot express intent — only structure.

### Historical context

McCarthy's 1960 M-expressions (`f[x; y]`) were designed exclusively for function application. There was no M-expression notation for data lists because M-expressions were the "readable" layer over S-expressions — you could always drop down to S-expressions for data. This asymmetry was one reason M-expressions were never adopted: programmers preferred the uniformity of S-expressions.

m1clj takes a different approach: M-expression notation IS the S-expression, just rearranged. There is no "drop down" because there is nothing below — `f(x y)` and `(f x y)` are the same thing at different stages of the pipeline. This eliminates the M/S asymmetry, but it means the call/data tension is fundamental and cannot be resolved without adding syntax.
