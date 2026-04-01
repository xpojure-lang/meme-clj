# LANGBOOK — Language Maker Cookbook

How to build languages on the meme platform.


## What is a language

A language is three things:

1. **A prelude** — forms eval'd before user code. Your standard library.
2. **Rules** — rewrite rules applied to user code after parsing. Your compile-time transforms.
3. **A parser** — optionally, a custom `tokens → forms` function. Your syntax.

Most languages only need a prelude. Some add rules. Few need a custom parser. Start simple.


## Minimal language: prelude only

A language that shares meme syntax but has its own functions.

```
my-lang/
  core.meme     ← prelude (standard library)
  lang.edn      ← lang definition
```

**core.meme** — define what's available to every `.ml` file:

```
defn(greet [name] str("Hello, " name "!"))
defn(double [x] *(2 x))
defn(square [x] *(x x))
```

**lang.edn** — declare your language:

```edn
{:extension ".ml"
 :run "my-lang/core.meme"
 :format :meme-classic}
```

- `:extension` — file extension for auto-detection
- `:run` — string path to prelude file (eval'd before user code)
- `:format` — keyword to inherit formatting from a built-in lang

**app.ml** — user code, prelude loaded automatically:

```
println(greet("world"))
println(double(square(3)))
```

**Run it:**

```bash
# Via --lang flag pointing to EDN file
bb meme run app.ml --lang my-lang/lang.edn

# Or register at runtime and auto-detect from extension
bb -e '(require (quote [meme.lang :as lang])) (lang/register! :my-lang (lang/load-edn "my-lang/lang.edn"))'
bb meme run app.ml    # auto-detected from .ml extension
```


## Adding rules: compile-time transforms

Rules rewrite the user's code after parsing, before eval. They are pattern → replacement pairs.

**Patterns:**

| Syntax | Meaning |
|--------|---------|
| `?x` | Match any single form, bind to `x` |
| `??xs` | Match zero or more forms (splice), bind to `xs` |
| `_` | Match anything, don't bind |
| `(f ?x ?y)` | Match a list with head `f` |
| `[?a ?b]` | Match a vector |
| `{:k ?x}` | Match a map by key, bind values |
| `#{:a :b}` | Match a set by element presence |
| `42`, `:foo` | Match literal values |

`?x` appearing twice in a pattern enforces consistency — both must match the same value.

**Rules:**

```
require('[meme.rewrite :as rw])

; pattern → replacement
rw/rule(list('+ '?a 0) '?a)           ; (+ x 0) → x

; with guard — fires only when the guard returns true
rw/rule(list('+ '?a '?b) '?result
        fn([b] and(number?(get(b 'a))
                   number?(get(b 'b)))))
```

**Rules file** — eval'd, must return a vector of rules:

```
require('[meme.rewrite :as rw])

[rw/rule(list('+ '?a 0) '?a)
 rw/rule(list('+ 0 '?a) '?a)
 rw/rule(list('* '?a 1) '?a)
 rw/rule(list('* 1 '?a) '?a)
 rw/rule(list('* '?a 0) 0)
 rw/rule(list('* 0 '?a) 0)]
```

> **Important:** The rules file is eval'd with `run-string`, which returns only
> the **last form's result**. The last expression must evaluate to your rules
> vector. If the file ends with a side-effecting form (like `println` or `def`),
> the rules will be lost.

**Lang EDN with rules:**

```edn
{:extension ".calc"
 :run "calc/core.meme"
 :rules "calc/rules.meme"
 :format :meme-classic}
```

The `:rules` key accepts a string path to a `.meme` file that returns a rule vector. Rules are baked into the `:run` closure — they're injected as `:rewrite-rules` in opts before user code runs.

**What rules can do:**
- Algebraic simplification: `(+ x 0) → x`
- Desugaring: `(unless test body) → (if (not test) body)`
- Domain transforms: `(query :users {:age ?x}) → (sql "SELECT * FROM users WHERE age = ?" ?x)`
- Linting: match suspicious patterns, emit warnings


## How rewriting works

Rules apply **bottom-up** to **fixed point**:

1. Children are rewritten before parents (inner-first).
2. Rules try in vector order. First match wins at each node.
3. After one full pass, if anything changed, repeat.
4. Stop when no rule matches anywhere (fixed point).
5. Hard limit of 100 iterations (configurable) prevents infinite loops.

**A rule that doesn't terminate:**

```
rw/rule(list('a) list('b))
rw/rule(list('b) list('a))    ; oscillates: (a) → (b) → ... → iteration limit
```

The engine hits the iteration limit and throws. You don't get silent infinite loops.

**Making rules terminate:** Each rule should make the expression "simpler" — fewer nodes, lower in some ordering. The engine doesn't prove this; it relies on cycle detection and max iterations as safety nets.


## Splice variables

`??xs` matches zero or more elements and splices into replacements:

```
; Match: (call f a b c) → bindings: {f: f, args: [a b c]}
; Replace: (?f ??args) → (f a b c)
rw/rule(list('call '?f '??args) list('?f '??args))
```

Splice in the middle:

```
rw/rule(list('wrap '??items) list('do '??items 'done))
; (wrap a b c) → (do a b c done)
```


## Custom parser: new syntax

If meme syntax isn't right for your language, provide a parser function.

A parser has the signature:

```
(fn [tokens opts source] → forms-vector)
```

It receives meme's flat token vector (all atoms already tokenized with source positions) and returns a vector of Clojure forms. It can:

- Walk tokens with its own grammar
- Call back into meme's parser for embedded meme regions
- Produce any Clojure forms

**Lang EDN with a parser:**

```edn
{:extension ".mys"
 :run meme.runtime.run/run-string
 :parser my.ns/my-parser-fn}
```

The `:parser` key accepts a qualified symbol that resolves to a parser function. It's baked into the `:run` closure — injected as `:parser` in opts.

**The rewrite-based parser** is a ready-made alternative:

```edn
{:extension ".rwm"
 :run meme.runtime.run/run-string
 :parser meme.rewrite.tree/rewrite-parser}
```

This uses the rewrite engine instead of the recursive-descent parser. Same output, different implementation. Useful as a starting point for custom parsers — fork `tree.cljc` and modify.

Most languages don't need a custom parser. Meme syntax is general enough for most DSLs. Use a parser only when you genuinely need different lexical structure.


## The token vocabulary

Your custom parser receives meme's token vector. Every token has:

| Key | Type | Description |
|-----|------|-------------|
| `:type` | keyword | Token type (see below) |
| `:value` | string | Raw source text |
| `:line` | int | Line number (1-indexed) |
| `:col` | int | Column number (1-indexed) |
| `:ws` | string/nil | Whitespace before this token |

**Token types:**

| Type | Examples |
|------|----------|
| `:symbol` | `foo`, `+`, `defn`, `true`, `nil` |
| `:keyword` | `:foo`, `::bar` |
| `:number` | `42`, `3.14`, `0xFF` |
| `:string` | `"hello"` |
| `:char` | `\a`, `\newline` |
| `:regex` | `#"pattern"` |
| `:open-paren` / `:close-paren` | `(` / `)` |
| `:open-bracket` / `:close-bracket` | `[` / `]` |
| `:open-brace` / `:close-brace` | `{` / `}` |
| `:open-set` | `#{` |
| `:open-anon-fn` | `#(` |
| `:quote` | `'` |
| `:deref` | `@` |
| `:meta` | `^` |
| `:syntax-quote` | `` ` `` |
| `:unquote` | `~` |
| `:unquote-splicing` | `~@` |
| `:var-quote` | `#'` |
| `:discard` | `#_` |
| `:tagged-literal` | `#inst`, `#uuid` |
| `:reader-cond-start` | `#?`, `#?@` |
| `:namespaced-map-start` | `#:ns` |

You get all of this for free. Your parser only writes the grammar — matching brackets, deciding what's a call, structuring the tree. The tokenizer handled the hard part.


## Architecture

```
source text
  → tokenizer (shared, handles all of Clojure's lexical syntax)
  → parser (default meme, or your custom parser)
  → expander (syntax-quote → seq/concat/list)
  → rewriter (your rules, if any — bottom-up to fixed point)
  → Clojure forms
  → prelude eval (your standard library)
  → user code eval
```

Each stage is a `ctx → ctx` function. You can replace any stage. The contract between stages is a context map with `:source`, `:tokens`, `:forms`, `:opts`.


## Patterns for language design

**Pattern 1: Functions only.** Prelude defines functions. No rules, no custom parser. The simplest possible language — a library with its own file extension.

**Pattern 2: Functions + rules.** Prelude defines functions, rules add compile-time transforms. Use this for DSLs that want algebraic simplification, desugaring, or domain-specific optimizations.

**Pattern 3: Rules as semantics.** No prelude functions — the entire language is rules. User code is data that the rules rewrite into executable Clojure. This is the Wolfram model.

**Pattern 4: Custom syntax.** A parser that reads a different grammar but produces Clojure forms. The language has its own syntax but compiles to the same target. This is the Racket `#lang` model.


## Examples

See `examples/languages/` in this repo:

- **calc** — algebraic simplification. Prelude + rules. Demonstrates `simplify` function built from rewrite rules.
- **prefix** — traced functions. Prelude only. Demonstrates `trace` wrapper and `check` assertion helper.
- **superficie** — surface-level tooling. Prelude only.


## API reference

```clojure
;; --- Lang registration ---
(require '[meme.lang :as lang])

;; Load from EDN file
(lang/load-edn "my-lang/lang.edn")    ; → lang map

;; Register at runtime
(lang/register! :name {:extension ".ext"
                       :run "path/core.meme"
                       :rules "path/rules.meme"
                       :parser my.ns/parser-fn
                       :format :meme-classic})

(lang/resolve-lang :name)              ; → lang map
(lang/resolve-by-extension "file.ext") ; → [name lang-map] or nil
(lang/registered-langs)                ; → (:name ...)

;; --- Rewrite engine ---
(require '[meme.rewrite :as rw])

(rw/rule pattern replacement)              ; create a rule
(rw/rule pattern replacement guard-fn)     ; with guard
(rw/match-pattern pattern expr)            ; → bindings or nil
(rw/substitute template bindings)          ; → form
(rw/rewrite rules expr)                    ; bottom-up to fixed point
(rw/rewrite-once rules expr)              ; one pass, returns [changed? result]
(rw/rewrite-top rules expr)               ; top-level only

;; --- CLI ---
bb meme run <file> [--lang name-or-path.edn]
```


## Standalone rewrite examples

`examples/rewrite/` contains self-contained examples of the rewrite engine
outside the guest language system:

- `simplify.meme` — algebraic simplification rules (+ identity, * identity)
- `m-call.meme` — bidirectional S-expression ↔ M-expression conversion
- `guards.meme` — pattern matching with guard conditions
