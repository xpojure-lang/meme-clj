# beme-clj — begin/end and M-expressions for Clojure

[![CI](https://github.com/beme-lang/beme-clj/actions/workflows/ci.yml/badge.svg)](https://github.com/beme-lang/beme-clj/actions/workflows/ci.yml)
[![Clojure](https://img.shields.io/badge/Clojure-JVM%20%7C%20Babashka%20%7C%20ClojureScript-blue?logo=clojure&logoColor=white)](https://clojure.org)
[![License](https://img.shields.io/github/license/beme-lang/beme-clj)](LICENSE)

```
;; full — begin/end for structure, parens for one-liners
defn begin stars [owner repo]

  let begin
    [
      url  str("https://api.github.com/repos/" owner "/" repo)
      resp slurp(url)
      data json/read-str(resp :key-fn keyword)
      count :stargazers_count(data)
    ]

    println(str(owner "/" repo ": " count " ⭐"))
  end
end
```

## Why

M-expressions were McCarthy's original intended syntax for Lisp (1960).
S-expressions were meant to be internal representation only — but they stuck.
beme picks up where McCarthy left off: two rules that make nesting self-evident,
while preserving Clojure's semantics exactly.

**Rule 1** — head outside the parens: `f(x y)` => `(f x y)`

**Rule 2** (optional) — `begin`/`end` instead of parens: `f begin x y end` => `(f x y)`

Everything else is Clojure.

## Installation

Add to `deps.edn`:

```clojure
io.github.beme-lang/beme-clj {:git/tag "v0.3.0" :git/sha "a3767c4"}
```

Or clone and use directly:

```bash
git clone https://github.com/beme-lang/beme-clj.git
cd beme-clj
```

All namespaces live under `beme.alpha` to signal that the API is pre-1.0 and may change. When the API stabilizes, namespaces will move to `beme`.

## Getting Started

Run a `.beme` file:

```bash
$ bb beme run hello.beme                                # Babashka
$ clojure -T:beme run :file '"hello.beme"'              # Clojure JVM
Hello, world!
```

Interactive REPL:

```bash
$ bb beme repl                                          # Babashka
beme=> +(1 2)
3
beme=> map(inc [1 2 3])
(2 3 4)
```

Convert between beme and Clojure (direction detected from extension):

```bash
$ bb beme convert hello.beme                            # .beme → Clojure
$ bb beme convert hello.clj                             # .clj → beme
$ clojure -T:beme convert :file '"hello.beme"'          # Clojure JVM
```

Requires [Babashka](https://babashka.org) or [Clojure](https://clojure.org).

## Documentation

- [Language Reference](doc/language-reference.md) — complete syntax guide
- [API Reference](doc/api.md) — public functions
- [Development](doc/development.md) — testing, architecture
- [Design Decisions](doc/design-decisions.md) — rationale behind each choice
- [Product Requirements](doc/PRD.md) — requirements and known limitations
