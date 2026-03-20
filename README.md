# beme-clj — begin/end and M-expressions for Clojure

[![CI](https://github.com/beme-lang/beme-clj/actions/workflows/ci.yml/badge.svg)](https://github.com/beme-lang/beme-clj/actions/workflows/ci.yml)
[![Clojure](https://img.shields.io/badge/Clojure-JVM%20%7C%20Babashka%20%7C%20ClojureScript-blue?logo=clojure&logoColor=white)](https://clojure.org)
[![License](https://img.shields.io/github/license/beme-lang/beme-clj)](LICENSE)

```
;; beme                                     ;; Clojure

defn(greet [name]                           (defn greet [name]
  println(str("Hello, " name "!")))           (println (str "Hello, " name "!")))

defn begin greet [name]                     (defn greet [name]
  println(str("Hello, " name "!"))            (println (str "Hello, " name "!"))
  println("done")                             (println "done"))
end

->>(accounts                                (->> accounts
  filter(:active)                              (filter :active)
  map(:balance)                                (map :balance)
  reduce(+))                                   (reduce +))
```

Two rules. Everything else is Clojure.

**Rule 1** — head outside the parens: `f(x y)` => `(f x y)`

**Rule 2** (optional) — `begin`/`end` instead of parens: `f begin x y end` => `(f x y)`

## Getting Started

```bash
bb beme                # Start the REPL
bb beme-run file.beme  # Run a .beme file
```

```
beme=> +(1 2)
3
beme=> map(inc [1 2 3])
(2 3 4)
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
