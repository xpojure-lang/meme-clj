# beme-clj — begin/end and M-expressions for Clojure

[![CI](https://github.com/beme-lang/beme-clj/actions/workflows/ci.yml/badge.svg)](https://github.com/beme-lang/beme-clj/actions/workflows/ci.yml)
[![Clojure](https://img.shields.io/badge/Clojure-JVM%20%7C%20Babashka%20%7C%20ClojureScript-blue?logo=clojure&logoColor=white)](https://clojure.org)
[![License](https://img.shields.io/github/license/beme-lang/beme-clj)](LICENSE)

```
;; conservative — parens, just move the head outside
defn(greet [name] println(str("Hello, " name "!")))

;; begin/end — word delimiters instead of parens
defn begin greet [name]
  println(str("Hello, " name "!"))
end

;; full beme — structure through indentation and begin/end
defn begin transform-accounts [accounts]
  let([
    active filter(:active accounts)
    balanced ->>(active
      map(fn([a] update(a :balance *(:balance(a) 1.05))))
      remove(fn([a] neg?(:balance(a)))))
  ]
    reduce(fn([acc {:keys [id balance]}]
      assoc(acc id {:balance balance :status :processed}))
    {} balanced))
end
```

Two rules. Everything else is Clojure.

**Rule 1** — head outside the parens: `f(x y)` => `(f x y)`

**Rule 2** (optional) — `begin`/`end` instead of parens: `f begin x y end` => `(f x y)`

## Installation

Add to `deps.edn`:

```clojure
io.github.beme-lang/beme-clj {:git/tag "v0.1.0" :git/sha "2a890a4"}
```

Or clone and use directly:

```bash
git clone https://github.com/beme-lang/beme-clj.git
cd beme-clj
```

## Getting Started

Run a `.beme` file:

```bash
$ bb beme-run hello.beme
Hello, world!
```

Interactive REPL:

```bash
$ bb beme
beme=> +(1 2)
3
beme=> map(inc [1 2 3])
(2 3 4)
```

Transpile beme to Clojure:

```bash
$ clj -Sdeps '{:deps {io.github.beme-lang/beme-clj {:git/tag "v0.1.0" :git/sha "2a890a4"}}}' \
      -e '(require (quote [beme.core :refer [beme->clj]])) (println (beme->clj "defn(greet [name] println(str(\"Hello, \" name)))"))'
(defn greet [name] (println (str "Hello, " name)))
```

```clojure
;; or from a REPL
(require '[beme.core :refer [beme->clj]])
(beme->clj "defn(greet [name] println(str(\"Hello, \" name)))")
;=> "(defn greet [name] (println (str \"Hello, \" name)))"
```

Transpile Clojure to beme:

```bash
$ clj -Sdeps '{:deps {io.github.beme-lang/beme-clj {:git/tag "v0.1.0" :git/sha "2a890a4"}}}' \
      -e '(require (quote [beme.core :refer [clj->beme]])) (println (clj->beme "(defn greet [name] (println (str \"Hello, \" name)))"))'
defn(greet [name] println(str("Hello, " name)))
```

```clojure
;; or from a REPL (JVM/Babashka only)
(require '[beme.core :refer [clj->beme]])
(clj->beme "(defn greet [name] (println (str \"Hello, \" name)))")
;=> "defn(greet [name] println(str(\"Hello, \" name)))"
```

Requires [Babashka](https://babashka.org) or [Clojure](https://clojure.org).

## Testing

```bash
bb test-beme           # Babashka example + fixture tests
clojure -X:test        # JVM unit tests
bb test-cljs           # ClojureScript tests (needs Node.js)
bb test-all            # All three suites
```

## Documentation

- [Language Reference](doc/language-reference.md) — complete syntax guide
- [API Reference](doc/api.md) — public functions
- [Design Decisions](doc/design-decisions.md) — rationale behind each choice
- [Product Requirements](doc/PRD.md) — requirements and known limitations

## Architecture

```
.beme file -> tokenizer -> grouper -> parser -> Clojure forms -> eval
```

Pure-function reader and printer (`.cljc`), portable across JVM, Babashka, and ClojureScript. No runtime dependency. beme is a reader, not a language.

## Why

M-expressions were McCarthy's original intended syntax for Lisp (1960).
S-expressions were meant to be internal representation only — but they stuck.
beme picks up where McCarthy left off: two rules that make nesting self-evident,
while preserving Clojure's semantics exactly.
