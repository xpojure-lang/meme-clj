# meme platform roadmap

## Vision

meme as a pluggable language platform — not just a reader for Clojure,
but the FullForm substrate on which guest languages are built.

Inspired by Wolfram Language and Stratego/XT: one canonical expression
representation (`f(x y)` — head applied to arguments), with user-defined
rewrite rules and programmable traversal strategies layered on top.

**Key influences:**

- **Wolfram Language** — FullForm as universal representation, everything
  is `head[args...]`, evaluation as rule application, pattern matching as
  the core abstraction for defining semantics.
- **Stratego/XT** — separation of rewrite *rules* (what to transform)
  from *strategies* (where and how to apply them). Rules say "if you see
  this, produce that." Strategies say "traverse bottom-up," "try this
  then that," "apply until fixpoint." This separation is what makes term
  rewriting scalable — without it, rule sets become entangled with
  traversal logic.
- **Racket** — `#lang` mechanism for guest languages sharing a platform.
  Language-oriented programming as a design philosophy.

**meme's role in the platform:**

1. **A language** — `.meme` files that eval as Clojure (what exists today)
2. **A token vocabulary** — shared lexer for all guest languages
3. **A FullForm** — canonical representation any guest language can target or embed


## What exists (the lens)

- Token vocabulary: all Clojure atoms (strings, numbers, keywords, chars,
  symbols, regex, all dispatch forms), source positions, whitespace
  attachment, cross-platform (JVM/CLJS/bb)
- FullForm parser: `f(x y)` → `(f x y)`, recursive-descent, handles all
  Clojure reader features natively
- Printer + formatters: forms → meme text, roundtrip-correct, width-aware,
  comment-preserving (flat and canonical formatters compose printer + render)
- Pipeline: `scan → parse`, composable `ctx → ctx` stages,
  intermediate state exposed for tooling
- Error infrastructure: source context with line numbers and carets,
  secondary locations, hints
- Runtime: file runner, REPL, CLI (with the CLI itself written in meme)
- ClojureScript support: entire core pipeline is `.cljc`, runs in browser/Node


## What a guest language needs

A guest language is a syntax + semantics layer built on the meme platform.
Examples: a pattern-rewriting language, a dataflow DSL, a typed subset,
a notation system for a specific domain.

A guest language needs:

1. **A parser** — its own grammar, consuming meme's token stream, with the
   ability to delegate subtrees back to the meme parser for FullForm islands
2. **A core** — macros, functions, and rules auto-loaded before user code
3. **An evaluation strategy** — Clojure's eager eval, a rewrite loop,
   or something custom
4. **An identity** — name, file extension, how the platform dispatches to it


## Roadmap

### Phase 0: Prelude injection — DONE

**What:** A slot in `run-string` and `repl/start` for forms to eval before
user code.

**Why first:** Trivial to implement (a few lines), immediately useful even
without guest languages. Lets meme users create "batteries-included"
configurations. Unblocks Phase 2 — a guest language's core is just a
prelude.

**Shape:**
```clojure
;; run-string gains :prelude option
(run-string src {:prelude ['(require '[my.dsl :refer :all])]})

;; repl/start gains :prelude option
(repl/start {:prelude ['(require '[my.dsl :refer :all])]})
```

**Scope:** `runtime/run.cljc`, `runtime/repl.cljc`. Reader and pipeline
untouched.


### Phase 1: Language dispatch — DONE

**What:** Given a file or input, determine which guest language to use and
load its configuration.

**Mechanisms (not mutually exclusive):**
- File extension: `.pat` → pattern language, `.meme` → FullForm (default)
- First-line directive: `#lang pattern` (Racket-style)
- Explicit CLI flag: `meme run --lang pattern file.pat`
- Programmatic: `(run-string src {:lang :pattern})`

**Guest language registry:**
```clojure
{:name       "pattern"
 :extension  ".pat"
 :parser     nil          ;; nil = use meme's parser
 :prelude    [...]        ;; forms to eval before user code
 :eval       nil}         ;; nil = use Clojure's eval
```

Phase 0 + Phase 1 together already let people build guest languages that
share meme's syntax but have their own core. This is the "many semantics,
one syntax" model — useful before any parser pluggability exists.

**Scope:** Consolidated into `meme.lang` — `register!`, `resolve-by-extension`,
`load-edn`. Guest language dispatch integrated into `runtime/run.cljc` and `runtime/cli.meme`.


### Phase 2: Pluggable parser — DONE

**What:** Guest languages can provide their own `tokens → forms` parser,
while reusing meme's tokenizer and being able to delegate back to meme's
parser for FullForm regions.

**Why the tokenizer is the platform boundary:** The tokenizer is the
hardest, most tedious part of a reader — 408 lines handling platform
divergence (JVM/CLJS), CRLF, unicode escapes, every Clojure numeric
format, escape sequences, source positions. Guest languages get all of
this for free.

**Parser plugin contract:**
```clojure
;; A guest parser is a function:
;;   tokens × meme-parser × opts → forms
;;
;; - tokens: meme's token vector (with :ws, positions, types)
;; - meme-parser: the meme reader, available for FullForm delegation
;; - opts: reader opts (e.g. :read-cond)
(defn my-guest-parser [tokens meme-parse opts]
  ...)
```

**FullForm islands:** A guest parser can recognize a meme region (by
delimiter, directive, or convention) and call `meme-parse` on that
token subsequence. This is Model 2 + Model 3 from the design discussion
— shared tokenizer with pluggable parser, plus FullForm as escape hatch.
The two models compose naturally because the token vocabulary is shared.

**Pipeline change:** The pipeline becomes:
```
source → scan (shared) → [guest parser | meme parser] → forms → [prelude] → eval
```

The parser stage itself becomes dispatch-aware.

**Scope:** `stages.cljc` refactored to accept parser as parameter.
`parse/reader.cljc` exposes entry points for delegation. New namespace
`meme.platform.parser` for the plugin protocol.


### Phase 3: Rewrite infrastructure — PARTIALLY DONE

**What:** A pattern language, rule engine, and strategy combinator library
that guest languages can use to define semantics via term rewriting.

**Implementation status:**
- Pattern matching (`meme.rewrite/match-pattern`): **DONE** — `_`, `?x`, `??x` patterns, guard functions
- Rules + substitution (`defrule`, `ruleset`, `make-rule`): **DONE**
- Bottom-up rewriting (`rewrite`, `rewrite-once`, `rewrite-top`): **DONE** — with 100-iteration safety cap
- Token-stream rewriting (`meme.trs`): **DONE** — lightweight token-level S↔M conversion
- Strategy combinators (topdown, innermost, etc.): **NOT YET** — still future work

**Why this is the deep work:** This is what makes "build a language" feel
like "define some rules" rather than "write a compiler." It's the hardest
to design and the most consequential to get wrong.

**The key insight from Stratego:** Wolfram conflates rules with traversal —
attributes like `HoldFirst` and a fixed evaluation order bake strategy
into the rule system. Stratego separates them cleanly:

- **Rules** say *what* to transform: "if you see this pattern, produce that"
- **Strategies** say *where* and *how*: "traverse bottom-up," "try left
  then right," "repeat until fixpoint"

This separation is what makes rewriting scalable. Without it, every rule
implicitly encodes traversal assumptions, and rule sets become entangled.

**Components:**

**3a. Pattern language** — matching on meme form trees:
- `_` — wildcard (match anything, don't capture)
- `?x` — named capture (match anything, bind as `x`)
- `?x:Integer` — typed capture (match + type constraint)
- `??x` — sequence capture (match zero or more elements)
- `?x:when(pred)` — guarded capture (PatternTest)

Note: the exact surface syntax is open. The above uses conventions that
are expressible in meme FullForm without parser extensions — `?x` and
`??x` are valid Clojure symbols. An alternative is Wolfram-style `x_`
suffixes. Either way, the pattern *semantics* are the same.

**3b. Rewrite rules** — pure `pattern → template` transforms:
```
;; A rule is a value: pattern on the left, template on the right
rule(square(?x) -> *(?x ?x))

;; Rules can have guards
rule(abs(?x) -> ?x :when pos?(?x))
rule(abs(?x) -> -(?x) :when neg?(?x))

;; Multiple rules for the same head
rule(fib(0) -> 0)
rule(fib(1) -> 1)
rule(fib(?n) -> +(fib(-(?n 1)) fib(-(?n 2))))
```

Rules are data — they can be stored, composed, passed to strategies.
They don't know anything about traversal. A rule either matches the
current term and produces a replacement, or it fails.

**3c. Strategy combinators** — the Stratego-inspired core:

Primitive strategies:
- `id` — identity (succeed, change nothing)
- `fail` — always fail
- `rule` — apply a single rewrite rule (succeed or fail)

Sequential composition:
- `seq(s1 s2)` — apply s1, then s2 (fail if either fails)
- `choice(s1 s2)` — try s1; if it fails, try s2 (left-biased choice)
- `try(s)` — apply s; if it fails, succeed with identity (`choice(s id)`)

Traversal:
- `all(s)` — apply s to every immediate child (fail if any child fails)
- `some(s)` — apply s to every child, succeed if at least one succeeds
- `one(s)` — apply s to children left-to-right, succeed on first success

Derived traversals (built from primitives):
- `topdown(s)` — apply s to root, then recurse into children: `seq(s all(topdown(s)))`
- `bottomup(s)` — recurse into children, then apply s: `seq(all(bottomup(s)) s)`
- `innermost(s)` — bottom-up, repeat until fixpoint: `bottomup(try(seq(s innermost(s))))`
- `outermost(s)` — top-down, repeat until fixpoint

The power: guest languages define their evaluation order by composing
strategies, not by baking traversal into rules. A guest can say
"apply simplification rules bottom-up until fixpoint" or "apply
type-checking rules top-down once" — same rules, different strategies.

```
;; A guest language defines its evaluator as a strategy
def(simplify
  innermost(
    choice(
      rule(*(0 ?x) -> 0)
      rule(*(1 ?x) -> ?x)
      rule(+(?x 0) -> ?x))))

;; Apply it
simplify(*(1 +(a 0)))  ;; → a
```

**3d. Rule storage** — where rules live:

Option A: Clojure vars/namespaces — a rule set is a Clojure value (vector
of rules), lives in a var, composed with standard Clojure tooling.
Guest language's core is a namespace with rule-set vars, loaded via prelude.

Option B: Per-head dispatch table — like Wolfram's DownValues. When the
rewriter hits `(square x)`, it looks up rules registered for `square`.
More implicit, more Wolfram-like, harder to reason about composition.

Option A fits the "guest languages are Clojure libraries" principle
better. Rules are values. Strategies are functions. Composition is
explicit.

**3e. Evaluation control** — how rewriting relates to Clojure eval:

- **Macro layer:** Rules fire at read/macro-expansion time, output goes
  to Clojure eval. Guest language = custom macro expander.
- **Symbolic:** Forms stay as data. A rewrite loop replaces eval entirely.
  Clojure is just the host for the rewriter.
- **Hybrid:** Some heads are "transparent" (rewrite through to Clojure),
  some are "opaque" (stay symbolic). The strategy controls which.

The strategy combinator model supports all three — it's the *strategy*
that decides whether to call `eval` on the result or keep rewriting.

**Open questions:**
- Is the pattern language expressible in meme FullForm (symbols like `?x`),
  or does it benefit from its own token-level support (Phase 2)?
- Should strategies be Clojure functions (maximal flexibility) or a
  closed data DSL (analyzable, optimizable)?
- How does a guest language declare "these heads are mine, don't eval
  them as Clojure"? Is that a strategy concern or a separate mechanism?
- Can strategies be typed/checked? (A strategy that claims to be
  type-preserving, for instance.)

**Scope:** New namespaces: `meme.platform.pattern` (matching),
`meme.platform.rule` (rule construction and application),
`meme.platform.strategy` (combinators and traversal).
This is likely the first guest language built on the platform: a
term-rewriting language that uses Phases 0–2 and validates Phase 3.


### Phase 4: Guest language tooling

**What:** Platform-level support for the guest language development
experience.

- **Error messages** that understand guest syntax (not just meme tokens)
- **Pretty-printer** plugins — guest languages may have different
  formatting conventions
- **REPL** that auto-detects or is told which guest to load
- **Editor support** — file extension → syntax highlighting, with meme
  as the fallback
- **Convert/format** commands that work with guest languages


## Design principles

**Meme stays empty.** Meme is the FullForm. It has no opinions beyond
syntax. It does not grow a `meme.core`. Guest languages fill the semantic
space.

**The tokenizer is the platform boundary.** Guest languages get atom
parsing, source positions, error infrastructure, whitespace handling,
and cross-platform support for free. They write a parser, not a lexer.

**FullForm is always available.** Any guest language can embed meme
regions. Meme is both the foundation and the escape hatch. Like
Wolfram's FullForm, like Racket's base `#lang`.

**Guest languages are Clojure libraries.** A guest language is a jar
with a parser, a prelude, and optionally a custom eval. No special
build tooling. `deps.edn` dependency, file extension registration,
done.

**Rules and strategies are separate.** Following Stratego: rules define
*what* to rewrite, strategies define *where* and *how* to apply them.
Rules are values. Strategies are combinators. Guest languages compose
both, but never conflate them. This is what makes the rewrite system
scalable — rules don't encode traversal assumptions, so the same rule
set works with different evaluation strategies.

**Phases are independently useful.** Phase 0 is useful without Phase 1.
Phases 0+1 let people build "same syntax, different semantics" guest
languages without Phase 2. Phase 2 is useful without Phase 3. Each
phase delivers value and validates assumptions before the next begins.


## References

- McCarthy, J. (1960). *Recursive Functions of Symbolic Expressions and
  Their Computation by Machine, Part I.* — M-expressions as the original
  intended syntax for Lisp. meme continues this thread.
- Visser, E. (2004). *Program Transformation with Stratego/XT.* —
  Strategic term rewriting: separation of rules from strategies, the
  `all`/`some`/`one` traversal primitives, and strategy combinators.
  Core influence on Phase 3.
- Wolfram, S. *Wolfram Language Documentation: FullForm, Evaluation,
  Patterns, Rules.* — FullForm as canonical representation, pattern
  matching (`Blank`, `BlankSequence`), DownValues/UpValues, evaluation
  as rule application. Core influence on the platform vision.
- Felleisen, M. et al. *The Racket Manifesto* (2015). — Language-oriented
  programming, `#lang` for guest languages sharing a platform. Influence
  on language dispatch (Phase 1).
- Bravenboer, M., Kalleberg, K.T., Vermaas, R., Visser, E. (2008).
  *Stratego/XT 0.17. A language and toolset for program transformation.*
  Science of Computer Programming 72(1-2). — The mature Stratego system
  with concrete syntax, dynamic rules, and the full strategy library.
