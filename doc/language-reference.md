# beme Language Reference

Complete syntax reference for writing `.beme` code.


## Two Rules

### Rule 1: Call

Parentheses after a symbol, keyword, or vector form a call — spacing
is irrelevant. The preceding element becomes the head of a Clojure list.

```
f(x y)     →  (f x y)
+(1 2 3)   →  (+ 1 2 3)
```

**Spacing is irrelevant**: `foo(x)` and `foo (x)` are both calls — the head is outside the parens.
Keywords work too: `:active(m)` → `(:active m)` (keyword-as-function).
Vectors can be heads: `[x](body)` → `([x] body)` (used for multi-arity clauses).
Maps and sets can also be heads (they are functions in Clojure): `{:a 1}(:a)` → `({:a 1} :a)`, `#{:a :b}(x)` → `(#{:a :b} x)`.

### Spacing is irrelevant

The head of a list is written outside the parens. Spacing between the head and `(` does not matter:

```
foo(x)    ;; call: (foo x)
foo (x)   ;; also a call: (foo x)
```

### Rule 2: Textual delimiters (begin/end)

`begin` and `end` can replace parentheses as call delimiters:

```
f begin x y end     →  (f x y)
```

These are equivalent to `f(x y)` — the head is still outside the delimiters. Useful for multi-line blocks where parentheses feel noisy:

```
defn begin greet [name]
  println(str("Hello " name))
end

try begin
  dangerous-operation()
  catch(Exception e handle(e))
end
```

**`end` is reserved inside begin blocks.** Inside a `begin` block, the symbol `end` closes the block — it cannot appear as a data value. Outside begin blocks, `end` is a normal symbol.

**Printer emits parens.** The printer always outputs `f(args...)` — code written with `begin`/`end` round-trips semantically but not textually.


## Binding

### def

```
def(x 42)
def(state atom({:count 0}))
```

### let

```
let([x 1, y +(x 1)] *(x y))
```

Produces: `(let [x 1 y (+ x 1)] (* x y))`

Destructuring works:

```
let([{:keys [id name]} person] println(id name))
```


## Functions

### defn

Single arity:

```
defn(greet [name] println(str("Hello " name)))
```

Multiple body forms:

```
defn(greet [name] println(str("Hello " name)) name)
```

With docstring:

```
defn(greet "Greets a person" [name] println(str("Hello " name)))
```

Multi-arity:

```
defn(greet
  [name](greet(name "!"))
  [name punct](println(str("Hello " name punct))))
```

### fn

```
fn([x] +(x 1))
fn([x y] *(x y))
fn([acc item] assoc(acc :id(item) :balance(item)))
```

Multi-arity anonymous function (vector-as-head for each clause):

```
fn([x](+(x 1))
   [x y](+(x y)))
```

### defn- (private)

```
defn-(helper [x] +(x 1))
```

### defmacro

Macros work in `.beme` files. Syntax-quote (`` ` ``) is an opaque boundary —
the template inside backtick is raw Clojure, passed to Clojure's reader.

```
defmacro(my-log [tag expr] list('println tag expr))
defmacro(unless [test & body] `(if (not ~test) (do ~@body)))
```


## Control Flow

### if

```
if(>(x 0) "positive" "negative")
if(>(x 0) "positive")
```

### when

```
when(>(x 0) println("positive"))
when(>(x 0) println("positive") do-something(x))
```

### cond

```
cond(
  >(x 0)   "positive"
  ==(x 0)  "zero"
  :else    "negative")
```

### case

```
case(x
  1 "one"
  2 "two"
  "default")
```

### loop/recur

```
loop([i 0, acc []]
  if(>=(i 10)
    acc
    recur(inc(i) conj(acc i))))
```

### for

```
for([x xs, y ys, :when >(x y)] [x y])
```

Produces: `(for [x xs y ys :when (> x y)] [x y])`

Modifiers `:when`, `:while`, and `:let` are passed through:

```
for([x xs, :let [y *(x 10)], :while <(y 100)] y)
```

### doseq

Same binding syntax as `for`, but for side effects:

```
doseq([x items, :when active?(x)] println(x))
```


## Error Handling

```
try(
  dangerous-operation()
  catch(Exception e log/error("Failed:" e) default-value)
  finally(cleanup()))
```

`catch` and `finally` are regular calls inside `try`'s arguments.


## Threading

Threading macros are just calls:

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


## Java Interop

```
.toUpperCase("hello")           ;; method call
Math/abs(-1)                    ;; static method
java.util.Date.()               ;; constructor
.-x(point)                      ;; field access
```


## Concurrency

```
def(state atom({:count 0}))
swap!(state update(:count inc))
@state
```


## Protocols and Records

```
defprotocol(Drawable draw([this canvas]) bounds([this]))

defrecord(Circle [center radius])

defrecord(Circle [center radius]
  Drawable
  draw([this canvas] draw-circle(canvas this))
  bounds([this] get-bounds(this)))
```

## Types

```
deftype(Point [x y])

deftype(Point [x y]
  Drawable
  draw([this canvas] render(canvas .-x(this) .-y(this))))
```

## Reify

```
reify(Object toString([this] "hello"))
```

## Multimethods

```
defmulti(area :shape)

defmethod(area :circle [{:keys [radius]}] *(Math/PI radius radius))
```


## Unchanged from Clojure

All of these work exactly as in Clojure:

- Data literals: `[1 2 3]`, `{:a 1}`, `#{1 2 3}`, `:keyword`, `"string"`
- Reader macros: `@deref`, `^metadata`, `#'var`, `#_discard`, `'quote`
- Anonymous functions: `#(inc(%))` → `(fn [%1] (inc %1))`. Body must be a single expression. Uses beme syntax inside.
- Regex: `#"pattern"`
- Character literals: `\a`, `\newline`, `\space`
- Tagged literals: `#inst`, `#uuid`
- Auto-resolve keywords: `::foo` — in the REPL and file runner, resolved at read time (like Clojure). When using `beme->forms` directly without `:resolve-keyword`, deferred to eval time via `(read-string "::foo")`. On CLJS, `:resolve-keyword` is required (errors without it)
- Reader conditionals: `#?(:clj x :cljs y)` — passed through opaquely
- Namespaced maps: `#:ns{}`
- Destructuring in all binding positions
- Commas are whitespace
- Line comments: `; comment`
- Quoted lists: `'(x y z)` — the only case where bare `(...)` is allowed


## What's Different from Clojure

| Clojure | beme | Notes |
|---------|-----|-------|
| `(f x y)` | `f(x y)` | Parens follow the callable |
| `'(1 2 3)` | `'(1 2 3)` | Quote syntax is identical |


## Design Boundaries

- **Backtick is opaque.** Syntax-quote (`` ` ``) and its body are raw Clojure,
  passed to Clojure's reader. Macro templates use S-expressions inside backtick;
  beme syntax applies everywhere else.

- **`#()` uses beme syntax inside.** `#(inc(%))` is `(fn [%1] (inc %1))`.
  The call rule applies normally within `#()`. Use `%`, `%1`, `%2` for
  params.
