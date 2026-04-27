# CLAUDE.md — m1clj-lang

Sovereign guest of the meme-clj toolkit. The first language built on it.

## What m1clj is

**M-expressions for Clojure**, in the spirit of McCarthy (1960). One rule:

`f(x y)` → `(f x y)` — the head of a list is written outside the parens, adjacent to `(`. Spacing is significant: `f(x)` is a call, `f ()` is two forms.

Everything else (data literals, reader syntax, destructuring, commas-as-whitespace, `#` dispatch, syntax-quote) is unchanged from Clojure. File extension: `.m1clj`. Registry key: `:m1clj`.

## Files

```
m1clj-lang/src/m1clj_lang/
├── api.cljc            Public API + lang-map composition. Self-registers as :m1clj.
├── grammar.cljc        Pratt grammar spec — char dispatch, scanlets, parselets.
├── parselets.cljc      m1clj-specific compound parselets: call adjacency + M-expr call rule.
├── lexlets.cljc        Thin shim → meme.tools.clj.lex (m1clj inherits Clojure lexical conventions).
├── form-shape.cljc     Special-form decomposition into named slots (:name, :params, :bindings, …).
├── printer.cljc        Wadler-Lindig Doc tree builder. :m1clj and :clj output modes.
├── formatter/
│   ├── flat.cljc       Single-line format (printer + render at infinite width).
│   └── canon.cljc      Canonical multi-line format. Used by `meme format`.
├── run.clj             Thin shim → meme.tools.clj.run with m1clj's grammar injected.
└── repl.clj            Thin shim → meme.tools.clj.repl with m1clj's banner injected.
```

The shared backbone (`meme.tools.*`, `meme.tools.clj.*`) is the toolkit; everything in this directory is what makes m1clj specifically itself.

## Public API surface

`m1clj-lang.api` exports:

- `m1clj->forms`, `m1clj->ast`, `m1clj->clj` — read m1clj source
- `forms->m1clj`, `format-m1clj-forms` — emit m1clj source from forms
- `clj->m1clj`, `clj->forms`, `clj->ast` — read native Clojure source through the toolkit
- `forms->clj` — emit native Clojure source

The `m1clj→clj` and `clj→m1clj` paths are **lossless** (route through the AST tier) — comments, namespaced-map prefixes, set source order, multi-tier metadata, and bare `%` notation all survive. `clj->forms` and `clj->m1clj` work on JVM, Babashka, and ClojureScript (no `read-string` dependency).

## Divergence from sibling guests

- **vs `m2clj-lang`** — m2clj is m1clj plus one extra rule: a paren without head adjacency (`(x y z)`) is a list literal that lowers to `(quote (x y z))` instead of being a parse error. m1clj rejects bare parens. The two lang trees are sovereign — even where source files look temporally identical (most of `form-shape.cljc`, `formatter/*`, `printer.cljc`'s non-quote branches), they are owned independently and may diverge. **Do not lift duplicated code into `meme.tools.clj.*`** unless it's genuinely toolkit-level.
- **vs `clj-lang`** — clj-lang is the native S-expression Clojure surface (no M-expression rule). It's a thin registration shim that reuses `meme.tools.clj.parser.*` for parsing and m1clj's printer in `:clj` mode for emitting.

## Tests

Per-lang tests live under `m1clj-lang/test/m1clj_lang/`:

- `reader/call_syntax_test.cljc` — head type × spacing × arity matrix
- `reader/calls_test.cljc` — every Clojure form rendered as a call (def, defn, fn, let, …)
- `reader/interop_test.cljc` — Java interop (`.method`, `Class/static`, `.-field`, constructors)
- `reader/data_literals_test.cljc` — vectors, maps, sets, keywords, numbers
- `reader/dispatch_test.cljc` — `@`, `^`, `'`, `#'`, `#_`, `#()`, regex, char, tagged literals, reader conditionals, namespaced maps
- `reader/errors_test.cljc` — error cases, rejected forms, error messages with locations
- `stages_test.cljc` — pipeline stages (parse → read, syntax-quote expansion)
- `form_shape_test.cljc` — per-form decomposer output, structural fallback, registry extension
- `printer_test.cljc` — printer-level seams (`:slot-renderers`, default renderers, unknown-slot fallback)
- `formatter/flat_test.cljc` — single-line `:m1clj`/`:clj` output, reader sugar
- `formatter/canon_test.cljc` — width-aware multi-line layout, comments
- `api_test.cljc` — public API surface
- `repl_test.clj`, `run_test.clj` — REPL infrastructure and file runner

Cross-lang regression and infrastructure tests live under `test/meme/`; see the root `CLAUDE.md` "Test file placement" table.

## Syntax quick reference (for writing `.m1clj` code)

- `symbol(args)` is a call — head adjacent to `(`
- `f (x)` is NOT a call — spacing is significant
- Vectors can be heads: `[x](body)` → `([x] body)` (multi-arity clauses)
- Everything uses call syntax: `def(x 42)`, `let([x 1] body)`, `if(cond then else)`
- `defn(name [args] body)` — single arity
- `defn(name [args](body) [args](body))` — multi-arity (vector-as-head)
- `fn([x] expr)` — anonymous function
- `try(body catch(Exception e handler))` — error handling
- `when(cond body)`, `cond(pairs...)`, `case(expr pairs...)` — control flow
- `for([x xs] body)`, `doseq([x items] body)` — comprehensions
- `ns(my.ns :require([...]))` — namespace declaration
- `defprotocol(Name method-sigs...)`, `defrecord(Name [fields])` — protocols and records
- `defmulti(name dispatch-fn)`, `defmethod(name dispatch-val [args] body)` — multimethods
- `::keyword` — auto-resolve keywords resolved natively
- Threading macros (`->`, `->>`) are just calls
- `()` is the empty list
- `'x` quotes the next form; `'f(x)` → `(quote (f x))` — m1clj syntax inside, no S-expression escape
- `` `if(~test ~body) `` — syntax-quote uses m1clj syntax inside
- `[]` is always data; use `list(1 2 3)` for list literals
- No opaque regions — everything parsed natively

See `doc/language-reference.md` for the full spec.

## Form-shape

Form-shape is the middle layer in m1clj's three-layer formatter architecture:

| Layer | Owns | Module |
|---|---|---|
| **Notation** | Call-syntax rendering, delimiter placement, mode (m1clj vs clj) | `m1clj-lang.printer` |
| **Form-shape** | Decomposing special forms into named semantic slots | `m1clj-lang.form-shape` |
| **Style** | Opinions on layout *per slot name* | `m1clj-lang.formatter.canon/style` and any user/lang-provided alternative |

Form-shape is the **language-level semantic vocabulary**: it answers *what the parts of a form mean*, independent of how they're laid out. A lang owns its registry (sovereignty); formatters consume it.

### Slot vocabulary

A form-shape decomposer returns a vector of `[slot-name value]` pairs in source order. The following slot names are the stable contract:

| Slot | Meaning | Example |
|---|---|---|
| `:name` | Identifier being defined or named locally | `foo` in `(defn foo ...)`, `e` in `(catch E e ...)` |
| `:doc` | Docstring | `"adds one"` in `(defn f "adds one" ...)` |
| `:params` | Parameter vector | `[x y]` in `(defn f [x y] ...)` |
| `:dispatch-val` | Multimethod dispatch value, catch class | `:circle` in `(defmethod area :circle ...)`; `Exception` in `(catch Exception e ...)` |
| `:dispatch-fn` | Dispatch function | `=` in `(condp = x ...)`; the fn in `(defmulti name dispatch-fn)` |
| `:test` | Conditional expression | `(> x 0)` in `(if (> x 0) ...)` |
| `:expr` | Target expression for case/condp/threading | `x` in `(case x ...)`; `coll` in `(-> coll ...)` |
| `:bindings` | `[k v ...]` binding vector | `[x 1 y 2]` in `(let [x 1 y 2] ...)` |
| `:as-name` | `as->` binding name | `y` in `(as-> x y ...)` |
| `:clause` | Test/value pair; value is a `[test value]` 2-tuple | `[:circle "c"]` in `(case x :circle "c" ...)` |
| `:default` | Case/condp default branch | `"other"` in `(case x 1 "one" "other")` |
| `:arity` | Complete single-arity form `([params] body+)` | `([x] ...)` in multi-arity `defn` |
| `:body` | Ordinary body expression | `(+ x 1)` in `(defn f [x] (+ x 1))` |

Example:

```clojure
(defn greet "says hi" [name] (println name))
;; decomposes to:
;; [[:name greet] [:doc "says hi"] [:params [name]] [:body (println name)]]
```

### Using form-shape

Querying a decomposition:

```clojure
(require '[m1clj-lang.form-shape :as fs])

(fs/decompose fs/registry 'defn '[foo [x] (+ x 1)])
;=> [[:name foo] [:params [x]] [:body (+ x 1)]]

(fs/decompose fs/registry 'my-unregistered-fn '[1 2 3])
;=> nil   ; no shape — formatters fall back to plain-call rendering
```

Adding a user macro to the registry:

```clojure
;; Reuse an existing decomposer — my-defn behaves like defn:
(def my-registry
  (assoc fs/registry 'my-defn (get fs/registry 'defn)))
```

Opt-in structural fallback: when a registry is wrapped with `with-structural-fallback`, unregistered heads with a recognizable shape are inferred automatically:

- `(HEAD name [params] body*)` → defn-like decomposition
- `(HEAD [bindings] body*)` → let-like decomposition

Only these two patterns are inferred — narrower rules would misfire on ordinary function calls.

Passing a registry to the formatter:

```clojure
(canon/format-form '(my-defn foo [x] body)
                   {:width 20
                    :form-shape (fs/with-structural-fallback fs/registry)})
```

### The style map

A style opines on layout over *slot names*, not form names. The canonical style ships in `m1clj-lang.formatter.canon/style` and is minimal:

```clojure
{:head-line-slots
 #{:name :doc :params :dispatch-val :dispatch-fn :test :expr :bindings :as-name}

 :force-open-space-for
 #{:name}}
```

Keys the formatter understands:

- **`:head-line-slots`** — slot names that stay on the head line with the call head when the form breaks. Other slots go into the indented body. Mode-independent: the same set drives both `:m1clj` and `:clj` output.
- **`:force-open-space-for`** — slot names whose presence triggers `head( ` (space after open paren) even on flat output. For m1clj, this is the classic "`defn(` becomes `defn( `" rule. **m1clj-only** — silently ignored under `:mode :clj`, which has no `head( ` convention.
- **`:slot-renderers`** *(optional; not in the shipped `canon/style`)* — a map `{slot-name → (fn [value ctx] → Doc)}` supplied by callers to override printer defaults. Layered **on top of** `m1clj-lang.printer/default-slot-renderers` via map merge — overriding only `:clause` still inherits the `:bindings` default.

Built-in slot renderers (in `m1clj-lang.printer/default-slot-renderers`):

| Slot | Default renderer behavior |
|---|---|
| `:bindings` | Columnar `[k v\n k v]` layout via `binding-vector-doc` |
| `:clause` | `[test value]` rendered as `test value` joined by a space |

### Built-in decomposers

The default `m1clj-lang.form-shape/registry` covers:

| Family | Members |
|---|---|
| `def*` | `def`, `def-`, `defonce`, `ns`, `defprotocol` |
| `defn*` | `defn`, `defn-`, `defmacro` |
| `defmulti` / `defmethod` | each its own shape |
| `defrecord` / `deftype` | shared shape |
| `deftest` / `testing` | each its own shape |
| `case` / `cond` / `condp` | pair-body shapes |
| `catch` | dispatch class + binding name |
| Threading | `->`, `->>`, `some->`, `some->>`, `cond->`, `cond->>` |
| `as->` | expr + as-name + body |
| `let` family | `let`, `loop`, `for`, `doseq`, `binding`, `with-open`, `with-local-vars`, `with-redefs`, `if-let`, `when-let`, `if-some`, `when-some` |
| `if` family | `if`, `if-not`, `when`, `when-not` |

### Future consumers

Form-shape is designed to serve tools beyond the canonical formatter. Slot vocabulary is the shared contract for:

- **LSP semantic tokens** — color `:name` as definition, `:params` as parameter, `:test` as keyword-expression distinctly from `:body`.
- **Lint rules** — "docstring should be on its own line", "missing `:default` in case", phrased against slot structure rather than AST walks.
- **Refactoring operations** — "extract arity" reorders `:arity` slots; "convert single-arity to multi-arity" rewrites the slot vector.
- **Doc generators** — extract `:doc` slots from `def*` forms.

Keeping the slot vocabulary stable is the reason it's a first-class API rather than a private implementation detail.
