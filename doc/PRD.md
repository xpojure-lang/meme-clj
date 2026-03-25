# beme clojure — Product Requirements Document

## Problem

Clojure's S-expression syntax requires structural editing (paredit) to
manage parenthesis nesting reliably. Without it, writing and reading
deeply nested forms is error-prone: mismatched parentheses, wrong bracket
types, incorrect nesting depth. These are bookkeeping errors, not
semantic ones — imposed by syntax that demands manual bracket management.


## Solution

beme is a thin syntactic lens over Clojure. Two rules replace
S-expression nesting with readable, familiar syntax:

1. `f(x y)` — call. Head of a list written outside the parens (spacing irrelevant).
2. `f begin x y end` — textual call delimiters, equivalent to parentheses.

Everything else is unchanged from Clojure. beme is a reader, not a language.
It emits standard Clojure forms that run on Babashka, Clojure JVM, or
ClojureScript without modification.


## Goals

- **Human-readable Clojure.** The syntax should be immediately legible to
  anyone who knows Clojure, Python, Ruby, or JavaScript. No paredit, no
  training required.

- **Eliminate paren-matching errors.** The syntax makes it structurally
  impossible to produce the most common classes of S-expression errors.

- **Zero runtime cost.** beme is a compile-time (read-time) transformation.
  The output is standard Clojure forms. No runtime library, no overhead.

- **Full Clojure compatibility.** Every valid Clojure program has a beme
  equivalent. Every beme program produces valid Clojure forms. Data literals,
  destructuring, reader macros, metadata — all work unchanged.

- **Roundtrippable.** beme text → Clojure forms → beme text should produce
  equivalent output. This enables tooling: formatters, linters, editors.

- **Portable.** The reader and printer run on Clojure JVM, ClojureScript,
  and Babashka. Single codebase, `.cljc` files, no platform-specific code.


## Non-goals

- **Replacing Clojure syntax.** beme is an alternative surface syntax.
  Developers who prefer paredit and S-expressions should keep using them.

- **New semantics.** beme adds no language features. No new data types, no
  new evaluation rules, no new special forms. If it doesn't exist in
  Clojure, it doesn't exist in beme.

- **IDE integration.** Not in scope for v1. The REPL and file-based
  workflow are sufficient.

- **Performance optimization.** The reader should be fast enough for
  interactive use. It does not need to compete with Clojure's reader on
  throughput for large codebases.

- **Error recovery.** The reader fails fast on invalid input. Partial
  parsing and error recovery are future work.


## Target users

1. **Developers writing Clojure without paredit.** Terminal, basic text
   editors, web forms, notebooks. beme syntax eliminates the bookkeeping
   that structural editors normally handle.

2. **Anyone reading Clojure code.** beme is easier to scan than
   S-expressions — code review, diffs, logs, documentation.

3. **Agents generating Clojure.** What is good for humans is good for
   agents. beme reduces syntax errors on the structural dimension.


## Requirements

### Reader

| ID | Requirement | Status |
|----|-------------|--------|
| R1 | Parse `f(x y)` as `(f x y)` — head outside parens (spacing irrelevant) | Done |
| R5 | Parse `def(x 42)` as `(def x 42)` | Done |
| R6 | Parse `let([x 1] body)` — bindings in call form | Done |
| R7 | Parse `for([x xs] body)` — bindings in call form | Done |
| R8 | Parse `defn` — single arity, multi-arity, docstring | Done |
| R9 | Parse `fn` — anonymous functions | Done |
| R10 | Parse `if(cond then else)` as call form | Done |
| R13 | Parse `ns(...)` with `:require` and `:import` | Done |
| R15 | Parse all Clojure data literals unchanged | Done |
| R16 | Parse Clojure reader macros (`@`, `^`, `#'`, `#_`, `'`) | Done |
| R17 | Pass `#?()` reader conditionals through to Clojure's reader | Done |
| R18 | Parse `defprotocol(...)`, `defrecord(...)`, `defmulti(...)`, `defmethod(...)` | Done |
| R19 | Parse Java interop: `.method()`, `Class/static()`, `.-field()` | Done |
| R20 | Commas are whitespace | Done |
| R21 | Line/column tracking for error messages | Done |
| R22 | Portable `.cljc` — core reader/printer run on JVM, ClojureScript, Babashka | Done |
| R23 | Signed numbers: `-1` is number, `-(1 2)` is call to `-` | Done |
| R24 | `#:ns{...}` namespaced maps pass through to Clojure's reader | Done |
| R25 | `#()` uses beme syntax inside, `%` params → `fn` form | Done |
| R26 | `run-pipeline` exposes intermediate pipeline state for tooling | Done |
| R27 | `begin`/`end` as textual call delimiters — `f begin args end` equivalent to `f(args)` | Done |

### Printer

| ID | Requirement | Status |
|----|-------------|--------|
| P1 | Print `(f x y)` as `f(x y)` | Done |
| P5 | Print `(def x 42)` as `def(x 42)` | Done |
| P6 | Print `(let [x 1] ...)` as `let([x 1] ...)` | Done |
| P7 | Print `(for [x xs] ...)` as `for([x xs] ...)` | Done |
| P8 | Print `defn` with proper beme syntax | Done |
| P9 | Print `if` as call form | Done |
| P11 | Print all Clojure data literals | Done |
| P12 | Proper indentation | Done |
| P13 | Roundtrip: read then print produces re-parseable output | Done |

### REPL

| ID | Requirement | Status |
|----|-------------|--------|
| RE1 | Read beme input, eval as Clojure, print result | Done |
| RE2 | Multi-line input: wait for balanced brackets and parens | Done |
| RE3 | Run via `bb beme` | Done |


## Architecture

```
.beme text ──→ tokenizer ──→ grouper ──→ parser ──→ Clojure forms ──→ eval
               (scan)        (group)     (parse)          │
                                            │             ▼
                                         resolve   printer ──→ .beme text
```

The reader is a three-stage pipeline (composed by `beme.pipeline`):
1. **Scan** (`beme.tokenizer`) — character stream → flat token vector. Opaque
   regions emit marker tokens rather than capturing raw text directly.
2. **Group** (`beme.grouper`) — collapses marker tokens + balanced delimiters
   into single composite `-raw` tokens. Bracket matching is trivial because
   strings, chars, and comments are already individual tokens.
3. **Parse** (`beme.reader`) — recursive-descent parser, tokens → Clojure
   forms. Value resolution (numbers, strings, chars, regex, opaque forms) is
   delegated to `beme.resolve`. Volatile position counter for portability.
   No intermediate AST — forms are emitted as standard Clojure data.

The printer pattern-matches on form structure to reverse the transformation.
It detects special forms and produces their beme syntax equivalents.

`#` dispatch forms (`#?`, `#?@`, `#:ns{}`, tagged literals) and syntax-quote
(`` ` ``) are opaque — the tokenizer emits markers, the grouper captures
the balanced region, and `beme.resolve` delegates to Clojure's reader.


## Known limitations

- **Backtick is opaque.** Syntax-quote (`` ` ``) and its body are captured
  as raw text and passed to Clojure's reader. Macro templates use
  S-expression syntax inside backtick; beme syntax applies everywhere else.

- **ClojureScript: opaque forms have limited support.** Reader
  conditionals (`#?`, `#?@`), namespaced maps (`#:ns{}`), tagged
  literals (`#uuid`, `#inst`), char literals, and ratio literals are
  JVM/Babashka only. The core call rule and all standard forms work
  on ClojureScript.

- **Nesting depth limit.** The parser enforces a maximum nesting depth of
  512 levels. Exceeding this produces a clear error. This prevents stack
  overflow on recursive descent.


## Future work

- CLI for Clojure-to-beme conversion: `bb beme convert file.clj` (core function `clj->beme` exists in `beme.core`)
- Error recovery: partial parsing for editor integration. This would require
  the parser to accumulate errors into a vector rather than throwing, return
  partial ASTs with error nodes, and add try/catch wrappers in `parse-form`
  that advance to resynchronization points (closing delimiters or newlines).
  This is a significant architectural change and should be its own project.
- Syntax highlighting grammars (TextMate, Tree-sitter)
- nREPL middleware
