# meme-clj — begin/end and M-expressions for Clojure

[![CI](https://github.com/beme-lang/meme-clj/actions/workflows/ci.yml/badge.svg)](https://github.com/beme-lang/meme-clj/actions/workflows/ci.yml)
[![Clojure](https://img.shields.io/badge/Clojure-JVM%20%7C%20Babashka%20%7C%20ClojureScript-blue?logo=clojure&logoColor=white)](https://clojure.org)
[![License](https://img.shields.io/github/license/beme-lang/meme-clj)](LICENSE)

M-expressions were McCarthy's original intended syntax for Lisp (1960).
S-expressions were meant to be internal representation only — but they stuck.
meme picks up where McCarthy left off: two rules that make nesting self-evident,
while preserving Clojure's semantics exactly.

**Rule 1** — head outside the parens: `f(x y)` => `(f x y)`

**Rule 2** (optional) — `begin`/`end` instead of parens: `f begin x y end` => `(f x y)`

**Escape hatch** — `'(...)` and `` `(...) `` drop back to S-expression syntax inside: `'(f (g x))` is `(quote (f (g x)))`, not a call. When you need raw Clojure forms, just quote them.

Everything else is Clojure.

```clojure
;; examples/stars.meme — bb meme run examples/stars.meme
require('[cheshire.core :as json])

defn begin stars [owner repo]
  let begin
    [
      url   str("https://api.github.com/repos/" owner "/" repo)
      resp  slurp(url)
      data  json/parse-string(resp true)
      count :stargazers_count(data)
    ]

    println(str(owner "/" repo ": " count " ⭐"))
  end
end

stars("beme-lang" "meme-clj")
```

## Installation

Add to `deps.edn`:

```clojure
io.github.beme-lang/meme-clj {:mvn/version "0.4.0-alpha"}
```

Or clone and use directly:

```bash
git clone https://github.com/beme-lang/meme-clj.git
cd meme-clj
```

All namespaces live under `meme.alpha` to signal that the API is pre-1.0 and may change. When the API stabilizes, namespaces will move to `meme`.

## Getting Started

Run a `.meme` file:

```bash
$ bb meme run hello.meme                                # Babashka
$ clojure -T:meme run :file '"hello.meme"'              # Clojure JVM
Hello, world!
```

Interactive REPL:

```bash
$ bb meme repl                                          # Babashka
user=> +(1 2)
3
user=> map(inc [1 2 3])
(2 3 4)
```

Convert between meme and Clojure (direction detected from extension):

```bash
$ bb meme convert hello.meme                            # .meme → Clojure
$ bb meme convert hello.clj                             # .clj → meme
$ clojure -T:meme convert :file '"hello.meme"'          # Clojure JVM
```

Format `.meme` files (normalize syntax via pprint):

```bash
$ bb meme format hello.meme                             # in-place
$ bb meme format src/                                   # directory, recursive
```

Requires [Babashka](https://babashka.org) or [Clojure](https://clojure.org).

## Editor Support

| Editor | Repository | Features |
|--------|-----------|----------|
| [Zed](https://zed.dev) | [zed-meme](https://github.com/beme-lang/zed-meme) | Syntax highlighting, brackets, indentation, symbol outline |
| [VS Code](https://code.visualstudio.com) | [vscode-meme](https://github.com/beme-lang/vscode-meme) | Syntax highlighting, brackets, indentation, folding |

Tree-sitter grammar: [tree-sitter-meme](https://github.com/beme-lang/tree-sitter-meme)

## Documentation

- [Language Reference](doc/language-reference.md) — complete syntax guide
- [API Reference](doc/api.md) — public functions
- [Development](doc/development.md) — testing, architecture
- [Design Decisions](doc/design-decisions.md) — rationale behind each choice
- [Product Requirements](doc/PRD.md) — requirements and known limitations
