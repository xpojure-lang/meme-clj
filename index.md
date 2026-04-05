---
layout: default
title: meme-clj
---

# meme-clj

M-expressions were McCarthy's original intended syntax for Lisp (1960). S-expressions were meant to be internal representation only — but they stuck. meme picks up where McCarthy left off.

**The rule** — head outside the parens: `f(x y)` => `(f x y)`

Everything else is Clojure.

```clojure
;; greet.meme
defn(greet [name]
  str("Hello, " name "!"))

println(greet("world"))

;; data transforms — just Clojure, head-first
let([xs [1 2 3 4 5]]
  println(filter(odd? xs))
  println(map(inc xs)))
```

## Documentation

- [Language Reference](language-reference) — complete syntax guide
- [API Reference](api) — public functions and namespaces
- [Design Decisions](design-decisions) — rationale behind each choice
- [Product Requirements](PRD) — requirements and known limitations

## Links

- [GitHub](https://github.com/xpojure-lang/meme-clj)
- [Changelog](https://github.com/xpojure-lang/meme-clj/blob/main/CHANGELOG.md)
- [Clojars](https://clojars.org/io.github.xpojure-lang/meme-clj)

### Installation

```clojure
io.github.xpojure-lang/meme-clj {:mvn/version "3.3.3"}
```
