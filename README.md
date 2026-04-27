# meme-clj — a syntax-experimentation toolkit for Clojure

[![CI](https://github.com/xpojure-lang/meme-clj/actions/workflows/ci.yml/badge.svg)](https://github.com/xpojure-lang/meme-clj/actions/workflows/ci.yml)
[![Clojure](https://img.shields.io/badge/Clojure-JVM%20%7C%20Babashka%20%7C%20ClojureScript-blue?logo=clojure&logoColor=white)](https://clojure.org)
[![License](https://img.shields.io/github/license/xpojure-lang/meme-clj)](LICENSE)

**meme-clj** is a research programme exploring alternative surface syntaxes
for Clojure. The toolkit — parser engine, AST, stages, printer, formatter,
loader, registry, CLI — is reusable across guest languages. Specific
languages register on top of it.

**m1clj** is the first language built on the toolkit. M-expressions for
Clojure, in the spirit of McCarthy (1960). One rule:

`f(x y)` => `(f x y)` — head outside the parens, adjacent to `(`.

Everything else is Clojure: data literals, reader macros, destructuring,
metadata, syntax-quote — all preserved exactly. Programs run on Babashka,
Clojure JVM, or ClojureScript without modification.

`'` quotes the next m1clj form: `'f(x)` is `(quote (f x))`. `` ` `` uses
m1clj syntax inside for macro templates: `` `if(~test do(~@body)) ``.

```clojure
;; examples/stars.m1clj — bb meme run examples/stars.m1clj
require('[cheshire.core :as json])

defn( stars [owner repo]
  let( [url   str("https://api.github.com/repos/" owner "/" repo)
     resp  slurp(url)
     data  json/parse-string(resp true)
     count :stargazers_count(data)]
    println(str(owner "/" repo ": " count " ⭐"))
  )
)

stars("xpojure-lang" "meme-clj")
```

A second guest, `clj-lang`, registers the native S-expression Clojure surface
on the same toolkit — proving the parser, AST, and printer are
language-agnostic. More guests are expected; the plural is the point.

> **Naming.** `meme-clj` is the toolkit; `m1clj` is the language. The
> `meme` binary belongs to the toolkit. See [doc/glossary.md](doc/glossary.md)
> for the full vocabulary.

## Installation

Add to `deps.edn`:

<!-- Update version below when releasing — source of truth is src/meme/version.txt -->
```clojure
io.github.xpojure-lang/meme-clj {:mvn/version "5.0.0"}
```

Or clone and use directly:

```bash
git clone https://github.com/xpojure-lang/meme-clj.git
cd meme-clj
```

## Getting Started

Run a `.m1clj` file:

```bash
$ bb meme run hello.m1clj                                # Babashka
$ clojure -T:meme run :file '"hello.m1clj"'              # Clojure JVM
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

Convert between m1clj and Clojure:

```bash
$ bb meme to-clj hello.m1clj                             # .m1clj → Clojure
$ bb meme to-m1clj hello.clj                             # .clj → m1clj
$ bb meme to-clj hello.m1clj --stdout                    # print to stdout
```

Format `.m1clj` files (normalize syntax via canonical formatter):

```bash
$ bb meme format hello.m1clj                             # in-place
$ bb meme format src/                                   # directory, recursive
```

Macros work naturally — backtick uses m1clj syntax inside:

```clojure
;; define a macro
defmacro(unless [test & body]
  `if(not(~test) do(~@body)))

;; use it
unless(empty?(xs)
  println(first(xs)))
```

## Namespace Loading

`.m1clj` files participate in Clojure's normal namespace machinery — no build plugin, no AOT step, no annotations:

```clojure
;; src/myapp/core.m1clj exists on the classpath
require('[myapp.core :as core])
core/greet("world")
```

The loader intercepts `clojure.core/load` (JVM) and `clojure.core/load-file` (JVM + Babashka), so any file under a registered extension is found and run on first reference. When both `myapp/core.m1clj` and `myapp/core.clj` exist, `.m1clj` wins.

**Auto-installed.** `m1clj-lang.run/run-string`, `run-file`, and the REPL install the loader before evaluating user code — programmatic embeddings get `.m1clj` `require` for free, not just the CLI. Hosts that own their own `clojure.core/load` interception opt out with `:install-loader? false`.

**Lang-independent.** The loader is registry-driven: it dispatches on extension to whatever lang is registered. Sibling langs registered with `:extensions` and `:run` get the same `require`/`load-file` support without writing any loader code.

**Shadowing.** A `.m1clj` file wins over `.clj` at the same classpath path. The loader does not protect core namespaces — if you put `clojure/core.m1clj` on your classpath, it will be loaded. Keep your lang files under your own namespace.

**Babashka limitation.** Babashka's SCI does not dispatch `require` through `clojure.core/load`, so on Babashka `require` of `.m1clj` namespaces is not supported. `load-file` works on both platforms. For Babashka projects that need `require`, precompile to `.clj`:

```bash
$ bb meme transpile src/                                # output to target/m1clj/
$ bb meme transpile src/ --out out/                     # custom output directory
```

Requires [Babashka](https://babashka.org) or [Clojure](https://clojure.org).

## Editor Support

| Editor | Repository | Features |
|--------|-----------|----------|
| [Zed](https://zed.dev) | [zed-meme](https://github.com/xpojure-lang/zed-meme) | Syntax highlighting, brackets, indentation, symbol outline |
| [VS Code](https://code.visualstudio.com) | [vscode-meme](https://github.com/xpojure-lang/vscode-meme) | Syntax highlighting, brackets, indentation, folding |

Tree-sitter grammar: [tree-sitter-meme](https://github.com/xpojure-lang/tree-sitter-meme)

(The editor packages target `.m1clj`; their names follow the toolkit, not the
language.)

## Documentation

Grouped by who the doc is for:

**Orientation**
- [Glossary](doc/glossary.md) — programme / language / toolkit vocabulary

**Writing `.m1clj` code**
- [Language Reference](doc/language-reference.md) — complete m1clj syntax guide

**Embedding meme-clj in a Clojure project**
- [API Reference](doc/api.md) — public functions (`m1clj->forms`, `forms->m1clj`, `format-m1clj-forms`, run/repl helpers, registry)

**Extending the formatter or building a sibling lang**
- [Form-Shape Vocabulary](doc/form-shape.md) — slot names, decomposers, and the three-layer formatter model
- [Design Decisions](doc/design-decisions.md) — rationale behind each choice

**Project tracking**
- [Product Requirements](doc/PRD.md) — programme + m1clj requirements, known limitations
- [Changelog](CHANGELOG.md) — release history (pre-5.0 entries archived)

**Contributing**
- [Development](CLAUDE.md) — testing, architecture, conventions
