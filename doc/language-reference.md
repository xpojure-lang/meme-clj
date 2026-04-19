# meme Language Reference

Complete syntax reference for writing `.meme` code. For the sibling Clojure-flavored frontend `implojure-lang` (file extensions `.implj`/`.impljc`/`.impljs`, infix-heavy syntax with `|name|>` pipes), see its own grammar — meme and implojure share the `meme.tools.clj.*` infrastructure but are distinct languages with different surface rules.


## The Rule

Parentheses **immediately** after any form create a call.
The preceding element becomes the head of a Clojure list.

```
f(x y)     →  (f x y)
+(1 2 3)   →  (+ 1 2 3)
```

**Adjacency required**: `foo(x)` is a call — the head is outside the parens. `foo (x)` is NOT a call — the space makes them separate forms.

The rule is purely syntactic — any value can be a head:
- Symbols: `f(x)` → `(f x)`
- Keywords: `:active(m)` → `(:active m)`
- Vectors: `[x](body)` → `([x] body)` (multi-arity clauses)
- Maps/sets: `{:a 1}(:a)` → `({:a 1} :a)`
- Literals: `nil(1 2)` → `(nil 1 2)`, `true(:a)` → `(true :a)`

### Adjacency required

The head of a list is written outside the parens, directly adjacent to `(`. Whitespace prevents call formation:

```
foo(x)    ;; call: (foo x)
foo ()    ;; NOT a call — symbol foo, then empty list ()
```

This makes `()` (the empty list) unambiguous in all positions:

```
{:value ()}    ;; map with two entries: :value and ()
[x ()]         ;; vector with two elements: x and ()
```

## Binding

### def

```
def(x 42)
def(state atom({:count 0}))
```

### let

```
let([x 1, y +(x 1)] 
  *(x y)
)
```

Produces: `(let [x 1 y (+ x 1)] (* x y))`

Destructuring works:

```
let(
  [{:keys [id name]} person] 
  
  println(id name)
)
```


## Functions

### defn

Single arity:

```
defn(greet [name] 
  println(str("Hello " name))
)
```

Multiple body forms:

```
defn(greet [name] 
  println(str("Hello " name)) 
  name
)
```

With docstring:

```
defn(greet 
  "Greets a person" 
  [name] 
  
  println(str("Hello " name))
)
```

Multi-arity:

```
defn(greet
  [name](
    greet(name "!")
  )
  [name punct](
    println(str("Hello " name punct))
  )
)
```

### fn

```
fn([x] 
  +(x 1)
)

fn([x y] 
  *(x y)
)

fn([acc item] 
  assoc(acc :id(item) :balance(item))
)
```

Multi-arity anonymous function (vector-as-head for each clause):

```
fn(
  [x](
    +(x 1)
  )
  [x y](
    +(x y)
  )
)
```

### defn- (private)

```
defn-(helper [x] +(x 1))
```

### defmacro

Macros work in `.meme` files. Syntax-quote (`` ` ``) is parsed natively —
meme call syntax applies inside backtick. `~` (unquote) and `~@` (unquote-splicing)
work as prefix operators.

```
defmacro(my-log [tag expr] list('println tag expr))

defmacro(unless [test & body] 
  `if(~test nil do(~@body))
)
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
  "default"
)
```

### loop/recur

```
loop([i 0, acc []]
  if(>=(i 10)
    acc
    recur(inc(i) conj(acc i))
  )
)
```

### for

```
for([x xs, y ys, :when >(x y)] 
  [x y]
)
```

Produces: `(for [x xs y ys :when (> x y)] [x y])`

Modifiers `:when`, `:while`, and `:let` are passed through:

```
for([ x xs, 
      :let [y *(x 10)], 
      :while <(y 100)] 
y)
```

### doseq

Same binding syntax as `for`, but for side effects:

```
doseq([x items, :when active?(x)] 
  println(x)
)
```


## Error Handling

```
try(
  dangerous-operation()
  
  catch(Exception e log/error("Failed:" e) default-value)
  
  finally(cleanup())
)
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
  remove(
    fn([a] neg?(:balance(a)))
  )
  reduce(+ 0)
)
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
defprotocol(Drawable 
  draw([this canvas]) 
  bounds([this])
)

defrecord(Circle [center radius])

defrecord(Circle [center radius]
  Drawable
  draw([this canvas] 
    draw-circle(canvas this)
  )
  bounds([this] 
    get-bounds(this)
  )
)
```

## Types

```
deftype(Point [x y])

deftype(Point [x y]
  Drawable
  draw([this canvas] 
    render(canvas .-x(this) .-y(this))
  )
)
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
- Anonymous functions: `#(inc(%))` → `(fn [%1] (inc %1))`, `#(rand())` → `(fn [] (rand))`. Body must be a single expression. Uses meme syntax inside.
- Regex: `#"pattern"`
- Character literals: `\a`, `\newline`, `\space`
- Tagged literals: `#inst`, `#uuid`
- Auto-resolve keywords: `::foo` — in the file runner, deferred to eval time so `::foo` resolves in the file's declared namespace (not the caller's). In the REPL, resolved at read time (like Clojure). When using `meme->forms` directly without `:resolve-keyword`, deferred to eval time via `(read-string "::foo")`. On CLJS, `:resolve-keyword` is required (errors without it)
- Reader conditionals: `#?(:clj x :cljs y)` and splicing `#?@(:clj [x y] :cljs [z])` — parsed natively. Tooling paths (`meme->forms`, `meme->clj`, `format-meme-forms`) preserve them as opaque `CljReaderConditional` records so `.cljc` sources roundtrip losslessly. Eval paths (`run-string`, `run-file`, REPL) materialize the matching platform branch automatically via the `step-evaluate-reader-conditionals` stage before eval. Meme call syntax applies inside: `#?(:clj println("jvm") :cljs js/console.log("browser"))`
- Namespaced maps: `#:ns{}`
- Destructuring in all binding positions
- Commas are whitespace
- Line comments: `; comment`
- Quote: `'x` quotes the next meme form. `'f(x y)` produces `(quote (f x y))`. `'()` is `(quote ())`. `'nil(1 2)` produces `(quote (nil 1 2))`. Note: `'(1 2)` is an error — bare parentheses need a head


## What's Different from Clojure

| Clojure | meme | Notes |
|---------|-----|-------|
| `(f x y)` | `f(x y)` | Parens follow the callable |
| `'(f x)` | `'f(x)` | Quote applies to the next meme form |


## Design Boundaries

- **Quote applies to the next meme form.** `'f(x)` produces `(quote (f x))`.
  `'foo`, `'42`, `':kw`, `'()` all work. Any value can be a head inside quote:
  `'nil(1 2)` produces `(quote (nil 1 2))`. Bare parentheses without a head
  remain an error: `'(1 2)` fails — write `'list(1 2)` or `list(1 2)` instead.

- **Backtick uses meme syntax inside.** Syntax-quote (`` ` ``) is parsed natively.
  `~` (unquote) and `~@` (unquote-splicing) work as prefix operators.
  Example: `` `if(~test do(~@body)) ``

- **`#()` uses meme syntax inside.** `#(inc(%))` is `(fn [%1] (inc %1))`.
  The call rule applies normally within `#()`. Use `%`, `%1`, `%2` for
  params.


## Guest Languages

meme serves as a platform for guest languages. A guest language can define:
1. **A prelude** — forms eval'd before user code (standard library)
2. **A custom parser** — optionally replacing the meme parser entirely

Guest languages are defined as EDN files and registered via `meme.registry`.
They are dispatched by file extension.

### Extension registration

A lang registers one or more file extensions. Both `:extension` (string) and `:extensions` (vector) are accepted; both are normalized to a single `:extensions` vector:

```clojure
;; EDN file — single extension
{:extension ".ml"
 :run "path/to/prelude.meme"
 :format :meme}

;; Runtime — multiple extensions
(registry/register! :my-lang {:extensions [".ml" ".mlx"]
                              :run 'my-lang.run/run-string})
```

The CLI auto-detects the lang from file extension: `meme run app.ml` resolves to the `:my-lang` lang. `run-file` does the same. When multiple extensions are registered, all are recognized.

### Namespace loader

After `install!` (called automatically by `run-string`, `run-file`, the REPL, and the CLI), `require` and `load-file` handle `.meme` files transparently:

```
;; In the meme REPL or a running .meme file:
require('[my-lib.core :as lib])   ; finds my_lib/core.meme on classpath (JVM only)
load-file("examples/demo.meme")   ; loads by filesystem path (JVM + Babashka)
```

Files with registered lang extensions take precedence over `.clj` when both exist. Core namespaces (`clojure.*`, `java.*`, etc.) are protected by a denylist and cannot be shadowed.

**Babashka limitation:** `require` of `.meme` namespaces is JVM-only (Babashka's SCI bypasses `clojure.core/load`). `load-file` works on both platforms.

### Precompilation

For environments where runtime loading isn't available (Babashka `require`, nREPL, CI), transpile `.meme` to `.clj`:

```bash
bb meme transpile src/                       # default: target/meme
bb meme transpile src/ --out target/classes  # or override
```

`compile` is accepted as an alias. Add the output directory to `:paths` in `deps.edn` or `bb.edn`. Standard `require` then works without any runtime patching.

### Building to JVM bytecode

One-shot:

```bash
bb meme build src/                         # default: transpile-staged in target/meme,
                                           #          bytecode in target/classes
bb meme build src/ --out out/classes       # custom output
```

`meme build` transpiles then spawns `clojure` to AOT-compile each namespace. It stops at `.class` files — JAR packaging stays in whatever build layer you already own.

For more control (different staging layout, no intermediate `.clj`, integration with an existing `build.clj`), skip the CLI and compose directly:

**Via transpile + tools.build** (zero meme runtime dep in your build.clj):

```clojure
;; build.clj
(require '[clojure.tools.build.api :as b])

(defn compile-clj [_]
  ;; Shell out to meme transpile, then AOT the result.
  (b/process {:command-args ["bb" "meme" "transpile" "src"]})
  (b/compile-clj {:basis     (b/create-basis {:project "deps.edn"})
                  :src-dirs  ["target/meme"]
                  :class-dir "target/classes"}))
```

**Directly via the meme loader** (no intermediate `.clj` files on disk):

```clojure
;; build.clj
(require '[meme.loader :as loader]
         '[clojure.tools.build.api :as b])

(defn compile-clj [_]
  (loader/install!)
  (b/compile-clj {:basis     (b/create-basis {:project "deps.edn"})
                  :src-dirs  ["src"]
                  :class-dir "target/classes"
                  :ns-compile '[my.ns]}))  ; list .meme namespaces explicitly
```

Both produce identical bytecode. The first leaves a readable `.clj` staging dir (useful for debugging); the second skips it and requires meme on the build classpath. Pick whichever fits your pipeline.
