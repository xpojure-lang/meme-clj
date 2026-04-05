# meme-clj — M-Expressions with Macro Expansion

[![CI](https://github.com/xpojure-lang/meme-clj/actions/workflows/ci.yml/badge.svg)](https://github.com/xpojure-lang/meme-clj/actions/workflows/ci.yml)
[![Clojure](https://img.shields.io/badge/Clojure-JVM%20%7C%20Babashka%20%7C%20ClojureScript-blue?logo=clojure&logoColor=white)](https://clojure.org)
[![License](https://img.shields.io/github/license/xpojure-lang/meme-clj)](LICENSE)

M-expressions were McCarthy's original intended syntax for Lisp (1960).
S-expressions were meant to be internal representation only — but they stuck.
meme picks up where McCarthy left off: one rule that makes nesting self-evident,
while preserving Clojure's semantics exactly.

**The rule** — head outside the parens: `f(x y)` => `(f x y)`

**Quote and backtick** — `'` quotes the next meme form: `'f(x)` is `(quote (f x))`. `` ` `` uses meme syntax inside for macro templates: `` `if(~test do(~@body)) ``

Everything else is Clojure.

```clojure
;; examples/stars.meme — bb meme run examples/stars.meme
require('[cheshire.core :as json])

defn(stars
  [owner repo]
  let(
    [
      url
      str("https://api.github.com/repos/" owner "/" repo)
      resp
      slurp(url)
      data
      json/parse-string(resp true)
      count
      :stargazers_count(data)
    ]
    println(str(owner "/" repo ": " count " ⭐"))))

stars("xpojure-lang" "meme-clj")
```

## Installation

Add to `deps.edn`:

<!-- Update version below when releasing — source of truth is src/meme/version.txt -->
```clojure
io.github.xpojure-lang/meme-clj {:mvn/version "3.3.3"}
```

Or clone and use directly:

```bash
git clone https://github.com/xpojure-lang/meme-clj.git
cd meme-clj
```

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

Convert between meme and Clojure:

```bash
$ bb meme to-clj hello.meme                             # .meme → Clojure
$ bb meme to-meme hello.clj                             # .clj → meme
$ bb meme to-clj hello.meme --stdout                    # print to stdout
```

Format `.meme` files (normalize syntax via canonical formatter):

```bash
$ bb meme format hello.meme                             # in-place
$ bb meme format src/                                   # directory, recursive
```

Macros work naturally — backtick uses meme syntax inside:

```clojure
;; define a macro
defmacro(unless [test & body]
  `if(not(~test) do(~@body)))

;; use it
unless(empty?(xs)
  println(first(xs)))
```

## Namespace Loading

On JVM Clojure, `.meme` files work transparently with `require`:

```clojure
;; src/myapp/core.meme — just works with require
(require '[myapp.core])
```

The loader intercepts `clojure.core/load` so `.meme` files on the classpath are found and compiled automatically — no build plugin needed. `.meme` takes precedence when both `.meme` and `.clj` exist. `load-file` also handles `.meme` on both JVM and Babashka.

Installed automatically by `run` and `repl` — no manual setup.

Alternatively, compile `.meme` to `.clj` for use without runtime patching:

```bash
$ bb meme compile src/                                  # output to target/classes/
$ bb meme compile src/ --out out/                       # custom output directory
```

Requires [Babashka](https://babashka.org) or [Clojure](https://clojure.org).

## Editor Support

| Editor | Repository | Features |
|--------|-----------|----------|
| [Zed](https://zed.dev) | [zed-meme](https://github.com/xpojure-lang/zed-meme) | Syntax highlighting, brackets, indentation, symbol outline |
| [VS Code](https://code.visualstudio.com) | [vscode-meme](https://github.com/xpojure-lang/vscode-meme) | Syntax highlighting, brackets, indentation, folding |

Tree-sitter grammar: [tree-sitter-meme](https://github.com/xpojure-lang/tree-sitter-meme)

## Documentation

- [Language Reference](doc/language-reference.md) — complete syntax guide
- [API Reference](doc/api.md) — public functions
- [Design Decisions](doc/design-decisions.md) — rationale behind each choice
- [Product Requirements](doc/PRD.md) — requirements and known limitations
- [Changelog](CHANGELOG.md) — release history
- [Development](CLAUDE.md) — testing, architecture
