# Form-shape

Form-shape is the middle layer in meme's three-layer formatter architecture:

| Layer | Owns | Module |
|---|---|---|
| **Notation** | Call-syntax rendering, delimiter placement, mode (m1clj vs clj) | `m1clj-lang.printer` |
| **Form-shape** | Decomposing special forms into named semantic slots | `m1clj-lang.form-shape` |
| **Style** | Opinions on layout *per slot name* | `m1clj-lang.formatter.canon/style` and any user/lang-provided alternative |

Form-shape is the **language-level semantic vocabulary**: it answers *what the parts of a form mean*, independent of how they're laid out. A lang owns its registry (decision: [lang sovereignty](design-decisions.md)); formatters consume it.

## Slot vocabulary

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

A single form generally emits a mix of these — e.g.
```clojure
(defn greet "says hi" [name] (println name))
;; decomposes to:
;; [[:name greet] [:doc "says hi"] [:params [name]] [:body (println name)]]
```

## Using form-shape

### Querying a decomposition

```clojure
(require '[m1clj-lang.form-shape :as fs])

(fs/decompose fs/registry 'defn '[foo [x] (+ x 1)])
;=> [[:name foo] [:params [x]] [:body (+ x 1)]]

(fs/decompose fs/registry 'my-unregistered-fn '[1 2 3])
;=> nil   ; no shape — formatters fall back to plain-call rendering
```

### Adding a user macro to the registry

```clojure
;; Reuse an existing decomposer — my-defn behaves like defn:
(def my-registry
  (assoc fs/registry 'my-defn (get fs/registry 'defn)))

(fs/decompose my-registry 'my-defn '[foo [x] (+ x 1)])
;=> [[:name foo] [:params [x]] [:body (+ x 1)]]
```

### Opt-in structural fallback

When a registry is wrapped with `with-structural-fallback`, unregistered heads with a recognizable shape are inferred automatically:

- `(HEAD name [params] body*)` → defn-like decomposition
- `(HEAD [bindings] body*)` → let-like decomposition

```clojure
(def reg (fs/with-structural-fallback fs/registry))

(fs/decompose reg 'my-defn '[foo [x] (+ x 1)])
;=> [[:name foo] [:params [x]] [:body (+ x 1)]]

(fs/decompose reg 'my-let '[[x 1] (+ x 1)])
;=> [[:bindings [x 1]] [:body (+ x 1)]]

;; No shape match — plain call, no inference
(fs/decompose reg 'my-fn '[1 2 3])
;=> nil
```

Only these two patterns are inferred because they're unambiguous — narrower rules would misfire on ordinary function calls. Register explicitly for other shapes.

### Passing a registry to the formatter

```clojure
(require '[m1clj-lang.formatter.canon :as canon])

(canon/format-form '(my-defn foo [x] body)
                   {:width 20
                    :form-shape (fs/with-structural-fallback fs/registry)})
;=> "my-defn( foo [x]
;  body
;)"
```

## The style map

A style opines on layout over *slot names*, not form names. The canonical style ships in `m1clj-lang.formatter.canon/style` and is minimal:

```clojure
{:head-line-slots
 #{:name :doc :params :dispatch-val :dispatch-fn :test :expr :bindings :as-name}

 :force-open-space-for
 #{:name}}
```

Keys the formatter understands:

- **`:head-line-slots`** — slot names that stay on the head line with the call head when the form breaks. Other slots go into the indented body.
- **`:force-open-space-for`** — slot names whose presence triggers `head( ` (space after open paren) even on flat output. For meme, this is the classic "`defn(` becomes `defn( `" rule; any form carrying a `:name` slot gets the treatment.
- **`:slot-renderers`** *(optional; not in the shipped `canon/style`)* — a map `{slot-name → (fn [value ctx] → Doc)}` supplied by callers to override printer defaults. Useful when a project wants a slot rendered differently, or a new custom slot needs display logic. Pass it via the formatter opts or merge it into a style copy.

### Slot renderers and defaults

The printer ships defaults for structural slots whose values aren't plain forms:

| Slot | Default renderer behavior |
|---|---|
| `:bindings` | Columnar `[k v\n k v]` layout via `binding-vector-doc` |
| `:clause` | `[test value]` rendered as `test value` joined by a space |

A style's `:slot-renderers` is layered **on top of** `m1clj-lang.printer/default-slot-renderers` via map merge, not replacing it wholesale — a style overriding only `:clause` still inherits the `:bindings` default. See the defaults map for the full built-in list.

## Built-in decomposers

The default `m1clj-lang.form-shape/registry` registers these Clojure forms:

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

## Future consumers

Form-shape is designed to serve tools beyond the canonical formatter. Some directions not yet built:

- **LSP semantic tokens** — color `:name` as definition, `:params` as parameter, `:test` as keyword-expression distinctly from `:body`.
- **Lint rules** — "docstring should be on its own line", "too many body forms for this operator", "missing `:default` in case", all phrased against slot structure rather than AST walks.
- **Refactoring operations** — "extract arity" reorders `:arity` slots; "convert single-arity to multi-arity" rewrites slot vector.
- **Doc generators** — extract `:doc` slots from `def*` forms.

The slot vocabulary is the shared contract these would sit on. Keeping it stable is the reason it's a first-class API rather than a private implementation detail.

## See also

- [`api.md`](api.md) — full reference for `m1clj-lang.form-shape` functions
- [`design-decisions.md`](design-decisions.md) — rationale for the three-layer split
- Source: [`src/m1clj_lang/form_shape.cljc`](../src/m1clj_lang/form_shape.cljc)
