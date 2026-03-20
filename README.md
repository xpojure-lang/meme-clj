# beme-clj — begin/end delimiters and M-expressions for Clojure

[![CI](https://github.com/beme-lang/beme-clj/actions/workflows/ci.yml/badge.svg)](https://github.com/beme-lang/beme-clj/actions/workflows/ci.yml)
[![Clojure](https://img.shields.io/badge/Clojure-JVM%20%7C%20Babashka%20%7C%20ClojureScript-blue?logo=clojure&logoColor=white)](https://clojure.org)
[![License](https://img.shields.io/github/license/beme-lang/beme-clj)](LICENSE)

A thin syntactic lens over Clojure. Two rules replace S-expression
nesting with readable, familiar syntax — no paredit required.

beme is a reader, not a language. It emits standard Clojure forms.
Use it with Babashka, Clojure JVM, ClojureScript, or anything that
evaluates Clojure.

## Why

M-expressions were McCarthy's original intended syntax for Lisp (1960).
S-expressions were meant to be the internal representation only — but
they stuck, and the human-friendly surface syntax was never built.

Sixty years later, S-expressions still demand structural editing to
manage parenthesis nesting. Without paredit, writing and reading deeply
nested forms is error-prone. beme picks up where McCarthy left off:
two rules that make the syntax self-evident, while preserving
Clojure's semantics exactly.

## Two Rules

1. `f(x y)` — call (M-expression syntax)
2. `f begin x y end` — textual call delimiters

Everything else is unchanged from Clojure.

## Rule 1: Call

The head of a list is written outside the parens. Spacing is irrelevant.

```
println("hello")              => (println "hello")
println ("hello")             => (println "hello")   ; same thing
+(1 2 3)                      => (+ 1 2 3)
filter(:active accounts)      => (filter :active accounts)
map(inc [1 2 3])              => (map inc [1 2 3])
```

Nested:

```
str("hi " to-str(x))         => (str "hi " (to-str x))
```

Vectors can also be heads (for multi-arity clauses):

```
[x](+(x 1))                  => ([x] (+ x 1))
```

Commas are whitespace, same as Clojure:

```
assoc(m, :key, "value")      => (assoc m :key "value")
assoc(m :key "value")        => (assoc m :key "value")
```

Everything uses call syntax — including special forms:

```
def(x 42)                    => (def x 42)
defn(greet [name] str("Hello, " name "!"))
let([x 1] +(x 2))           => (let [x 1] (+ x 2))
if(>(x 0) "positive" "negative")
when(>(x 0) println("positive"))
try(risky() catch(Exception e println(e)))
```

## Rule 2: Begin/End

`begin` and `end` are textual call delimiters, equivalent to parentheses:

```
f begin x y end              => (f x y)
```

Useful for multi-line blocks where parentheses feel noisy:

```
defn begin greet [name]
  println(str("Hello " name))
end

try begin
  dangerous-operation()
  catch(Exception e handle(e))
end
```

The printer always emits parentheses — `begin`/`end` is reader
convenience. Code written with `begin`/`end` round-trips semantically
but not textually.

## Unchanged from Clojure

**Data literals:**

| Syntax | Type |
|--------|------|
| `[1 2 3]` | vector |
| `{:name "Andriy" :age 45}` | map |
| `#{1 2 3}` | set |
| `:keyword` | keyword |
| `"string"` | string |
| `#"regex"` | regex |

**Reader syntax:**

| Syntax | Meaning |
|--------|---------|
| `@` | deref |
| `^` | metadata |
| `#'` | var quote |
| `#_` | discard |
| `#inst` `#uuid` | tagged literals |
| `#?()` | reader conditionals |
| `#:ns{}` | namespaced maps |

Destructuring in binding positions.
Commas are whitespace.

## Binding

Top level:

```
def(x 42)
```

Local:

```
let([x 1, y +(x 1)]
  *(x y))
```

Destructuring:

```
let([{:keys [id name]} person]
  println(id name))
```

## Functions

```
defn(greet [name]
  println(str("Hello " name)))

defn(greet [name]
  println(str("Hello " name))
  name)

defn(greet "Greets a person" [name]
  println(str("Hello " name)))
```

Multi-arity:

```
defn(greet
  [name](greet(name "!"))
  [name punct](println(str("Hello " name punct))))
```

Anonymous:

```
fn([x] +(x 1))
fn([x y] *(x y))

fn([acc item]
  assoc(acc :id(item) :balance(item)))
```

## Threading

Threading macros are just calls. Rule 1.

```
->(account update(:balance *(1.05)) assoc(:status :processed))

->>(accounts filter(:active) map(:balance) reduce(+))
```

Multi-line:

```
->>(accounts
  filter(:active)
  map(fn([a] update(a :balance *(:balance(a) 1.05))))
  remove(fn([a] neg?(:balance(a))))
  reduce(+ 0))
```

## Control Flow

```
when(>(x 0)
  println("positive")
  do-something(x))

cond(
  >(x 0)   "positive"
  ==(x 0)  "zero"
  :else    "negative")

loop([i 0, acc []]
  if(>=(i 10)
    acc
    recur(inc(i) conj(acc i))))

for([x xs, y ys, :when >(x y)]
  [x y])
```

## Error Handling

```
try(
  dangerous-operation()
  catch(Exception e
    log/error("Failed:" e)
    default-value)
  finally(
    cleanup()))
```

## Java Interop

```
.toUpperCase("hello")         ; method call
Math/abs(-1)                  ; static method
java.util.Date.()             ; constructor
.-x(point)                    ; field access
```

## Namespace

```
ns(my.accounts
  :require(
    [clojure.string :as str]
    [clojure.set :as set]
    [my.db :refer [query connect]])
  :import(
    [java.util Date UUID]))
```

## Concurrency

```
def(state atom({:count 0}))
swap!(state update(:count inc))
@state
```

## Protocols and Records

```
defprotocol(Drawable
  draw([this canvas])
  bounds([this]))

defrecord(Circle [center radius])

defrecord(Circle [center radius]
  Drawable
  draw([this canvas] render-circle(canvas center radius))
  bounds([this]
    {:x -(:x(center) radius)
     :y -(:y(center) radius)}))
```

## Multimethods

```
defmulti(area :shape)

defmethod(area :circle [{:keys [radius]}]
  *(Math/PI radius radius))
```

## Macros

Macro definitions work in `.beme` files — backtick is opaque,
so macro templates use S-expression syntax inside `` ` ``.
Macro calls use beme syntax normally.

## No Quoting

In Clojure, quote exists because `()` is both a call and a list.
In beme, `()` is always a call. `[]` is always data. There is nothing
to quote. If you need a list: `list(1 2 3)`. Rule 1.

## REPL

```
beme=> +(1 2)
3
beme=> map(inc [1 2 3])
(2 3 4)
```

Multi-line: continues reading until input is balanced (parseable).

## Tooling

```bash
bb beme                # Start the REPL
bb beme-run file.beme  # Run a .beme file

bb test-beme           # Babashka example + fixture tests
clojure -X:test        # JVM unit tests (all namespaces)
bb test-cljs           # ClojureScript tests (Node.js, auto-cleans build artifacts)
bb test-all            # All three suites in sequence
```

Requires: Clojure or Babashka. ClojureScript tests also need Node.js.

## Architecture

```
.beme file -> beme reader -> Clojure forms -> Babashka
                                           -> Clojure JVM
                                           -> ClojureScript
                                           -> anything
```

The reader and printer are pure functions. No runtime dependency.
The core translation layer (reader, printer, core) is fully portable
across JVM, Babashka, and ClojureScript. The REPL and file runner
require eval and are JVM/Babashka by default.

- **File extension:** `.beme`
- **Target:** any Clojure evaluator
- **Implementation:** reader + printer, both pure functions (`.cljc`)

## Full Example

**beme:**

```
ns(my.accounts
  :require(
    [clojure.string :as str]))

defn(transform-accounts [accounts]
  let([
    active filter(:active accounts)
    balanced ->>(active
      map(fn([a] update(a :balance *(:balance(a) 1.05))))
      remove(fn([a] neg?(:balance(a)))))
  ]
    reduce(fn([acc {:keys [id balance]}]
      assoc(acc id {:balance balance
                    :status :processed
                    :updated-at inst-ms(java.util.Date.())}))
    {} balanced)))

defn(summarize [accounts]
  let([
    processed transform-accounts(accounts)
    total ->>(processed vals() map(:balance) reduce(+))
  ]
    println(str("Total: " total))
    processed))
```

**Equivalent Clojure:**

```clojure
(ns my.accounts
  (:require
    [clojure.string :as str]))

(defn transform-accounts [accounts]
  (let [active (filter :active accounts)
        balanced (->> active
                   (map (fn [a] (update a :balance (* (:balance a) 1.05))))
                   (remove (fn [a] (neg? (:balance a)))))]
    (reduce (fn [acc {:keys [id balance]}]
              (assoc acc id {:balance balance
                             :status :processed
                             :updated-at (inst-ms (java.util.Date.))}))
            {}
            balanced)))

(defn summarize [accounts]
  (let [processed (transform-accounts accounts)
        total (->> processed
                (vals)
                (map :balance)
                (reduce +))]
    (println (str "Total: " total))
    processed))
```
