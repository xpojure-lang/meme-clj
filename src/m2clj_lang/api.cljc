(ns m2clj-lang.api
  "m2clj lang composition: lossless pipeline with Pratt parser and AST tier.

   m2clj is m1clj plus one rule: parens with no adjacency to a head are
   list literals (lower to (quote ...)) instead of parse errors. Calls
   still require adjacency, so call vs data is structural at the reader
   layer — visible without resolving symbols or knowing macro context.

   Pipeline: source → strip-preamble → parser → cst→ast → ast→form
   The parser produces a lossless CST; cst→ast lifts it into the Clj* AST
   tier (position, trivia, and notation as fields); ast→form lowers AST
   to plain Clojure values for eval. Tooling consumers stop at the AST."
  (:require [meme.tools.clj.stages :as stages]
            [meme.tools.clj.ast.build :as ast-build]
            [meme.tools.clj.ast.lower :as ast-lower]
            [meme.tools.clj.parser.api :as clj-parser]
            [meme.tools.parser :as parser]
            [m2clj-lang.grammar :as grammar]
            [m2clj-lang.form-shape :as form-shape]
            [m2clj-lang.formatter.flat :as fmt-flat]
            [m2clj-lang.formatter.canon :as fmt-canon]
            [meme.tools.clj.expander :as expander]
            #?(:clj [m2clj-lang.run :as run])
            #?(:clj [m2clj-lang.repl :as repl])
            #?(:clj [meme.registry :as registry])))

;; ---------------------------------------------------------------------------
;; Lang API — delegates to composable stages
;; ---------------------------------------------------------------------------

(defn- guard-deprecated-opts!
  "Reject opts that were valid in pre-AST stages but no longer apply."
  [opts]
  (when (contains? opts :read-cond)
    (throw (ex-info
             (str "The :read-cond option is no longer supported. The m2clj "
                  "reader always preserves reader conditionals as "
                  "CljReaderConditional records. To evaluate them for a "
                  "platform, compose "
                  "meme.tools.clj.stages/step-evaluate-reader-conditionals "
                  "after reading, or use m2clj-lang.run/run-string / run-file.")
             {:type    :m2clj/deprecated-opt
              :opt     :read-cond
              :value   (:read-cond opts)}))))

(defn m2clj->ast
  "Read m2clj source string. Returns a `CljRoot` AST node whose `:children`
  vec contains one AST node per top-level form.

  Bare-paren list literals `(x y z)` lift to `CljQuote{form: CljList,
  notation: :bare}` — semantically equivalent to tick-quoted `'(x y z)`,
  distinguished only by source notation.

  Tooling that needs round-trip fidelity, scoped refactoring, or
  position-attributed warnings should consume the AST directly.
  Callers that just want eval-ready Clojure forms should use
  `m2clj->forms`, which composes lowering on top.

  opts keys:
    :resolve-keyword  — fn applied at lowering for `::kw`; not used at
                        AST build time
    :grammar          — custom Pratt grammar spec (advanced)"
  ([s] (m2clj->ast s nil))
  ([s opts]
   {:pre [(string? s)]}
   (guard-deprecated-opts! opts)
   (let [src (stages/strip-source-preamble s)
         cst (parser/parse src (or (:grammar opts) grammar/grammar))]
     (ast-build/cst->ast cst opts))))

(defn m2clj->forms
  "Read m2clj source string. Returns a vector of Clojure forms.
   Composes `m2clj->ast` followed by AST → form lowering.

   Reader conditionals (`#?`, `#?@`) are always returned as
   `CljReaderConditional` records. To evaluate them for the current
   platform, compose `meme.tools.clj.stages/step-evaluate-reader-conditionals`
   after reading, or use `run-string` / `run-file` (which do so
   automatically).

   opts keys:
     :resolve-keyword  — fn to resolve auto-resolve keywords (::kw)
     :resolve-symbol   — fn to resolve symbols in syntax-quote expansion
     :grammar          — custom Pratt grammar spec (advanced)"
  ([s] (m2clj->forms s nil))
  ([s opts]
   {:pre [(string? s)]}
   (ast-lower/ast->forms (m2clj->ast s opts) opts)))

(defn forms->m2clj
  "Print Clojure forms as m2clj source string (single-line per form).
   Takes a SEQUENCE of forms (vector or seq), not a single form.

   Quoted lists `(quote (x y z))` render as bare-paren `(x y z)`; quoted
   symbols/keywords/numbers render as `'x`; quoted empty list renders as
   `'()`."
  [forms]
  {:pre [(sequential? forms)]}
  (fmt-flat/format-forms forms))

(defn format-m2clj-forms
  "Format Clojure forms as canonical m2clj source string (multi-line, indented).

   opts keys:
     :width  — target line width (int, default 80)"
  ([forms] (format-m2clj-forms forms nil))
  ([forms opts]
   {:pre [(sequential? forms)]}
   (fmt-canon/format-forms forms opts)))

(defn forms->clj
  "Print Clojure forms as Clojure source string. Plain forms carry no
  notation — sugar collapses (`(quote x)` renders as `'x` because
  that is canonical Clojure), but `@x`, `#'x`, `#()`, set source order,
  and namespaced-map prefixes are all lost. For lossless m2clj→clj, use
  `m2clj->clj` which goes through the AST tier."
  [forms]
  (fmt-flat/format-clj (expander/expand-forms forms)))

(declare ^:private ast-root->children-with-trailing)

(defn m2clj->clj
  "Convert m2clj source to Clojure source string (lossless via AST).

   Reader conditionals are preserved as `#?(...)` rather than evaluated for
   the current platform — faithful for `.cljc` conversion. For the eval-time
   value, use `run-string` instead.

   opts: same as `m2clj->ast` (`:resolve-keyword`, `:grammar`)."
  ([m2clj-src] (m2clj->clj m2clj-src nil))
  ([m2clj-src opts]
   {:pre [(string? m2clj-src)]}
   (let [ast (m2clj->ast m2clj-src opts)
         children (ast-root->children-with-trailing ast)]
     (fmt-flat/format-clj children))))

(defn clj->ast
  "Read Clojure source string. Returns a `CljRoot` AST node.

  Uses the native Clojure parser — works on JVM, Babashka, and
  ClojureScript without depending on host `read-string`.

  opts keys:
    :resolve-keyword  — fn applied at lowering for `::kw`
    :grammar          — alternate Pratt grammar spec (advanced)"
  ([clj-src] (clj->ast clj-src nil))
  ([clj-src opts]
   {:pre [(string? clj-src)]}
   (clj-parser/clj->ast clj-src opts)))

(defn clj->forms
  "Read Clojure source string, return a vector of plain Clojure forms.
   Composes `clj->ast` followed by AST → form lowering.

   opts keys:
     :resolve-keyword  — fn to resolve auto-resolve keywords (`::kw`)
     :grammar          — alternate Pratt grammar spec"
  ([clj-src] (clj->forms clj-src nil))
  ([clj-src opts]
   {:pre [(string? clj-src)]}
   (clj-parser/clj->forms clj-src opts)))

(defn clj->m2clj
  "Convert Clojure source to m2clj source string (lossless via AST).

  Routes through the AST tier and the printer's default `:m2clj` mode —
  comments, namespaced-map prefixes, set source order, multi-tier
  metadata, and bare `%` notation all survive the conversion. Quoted
  list literals `'(x y z)` become m2clj's bare-paren `(x y z)`. Reader
  conditionals are preserved as `#?(...)` rather than evaluated.

  opts: same as `clj->ast` (`:resolve-keyword`, `:grammar`)."
  ([clj-src] (clj->m2clj clj-src nil))
  ([clj-src opts]
   {:pre [(string? clj-src)]}
   (let [ast (clj->ast clj-src opts)
         children (ast-root->children-with-trailing ast)]
     (fmt-flat/format-forms children))))

;; ---------------------------------------------------------------------------
;; Lang commands (for CLI dispatch)
;; ---------------------------------------------------------------------------

(defn- ^:no-doc ast-root->children-with-trailing
  "Bridge: extract CljRoot's children as a vec, with its :trailing-trivia
  threaded as `:trailing-ws` metadata on the vec — matching the shape
  the formatter's join-with-trailing-comments expects."
  [root]
  (let [children (filterv #(not (instance? meme.tools.clj.ast.nodes.CljDiscard %))
                          (:children root))
        trailing (seq (:trailing-trivia root))]
    (if trailing
      (with-meta children
        {:trailing-ws (apply str (map :raw trailing))})
      children)))

(defn format-m2clj
  "Format m2clj source text. Reads source, formats via canonical formatter.
  Source-driven path goes through AST without intermediate lowering — the
  printer dispatches on AST nodes directly, preserving notation that
  forms-with-metadata would have collapsed."
  [source opts]
  (let [ast (m2clj->ast source opts)
        children (ast-root->children-with-trailing ast)]
    (if (empty? children)
      source
      (case (:style opts)
        "flat" (fmt-flat/format-forms children)
        "clj"  (fmt-flat/format-clj children)
        (fmt-canon/format-forms children opts)))))

(defn ^:no-doc to-clj
  "CLI-dispatch adapter: m2clj source → Clojure text. Library callers should
   use `m2clj->clj` directly — it has the same lossless behavior."
  ([source] (m2clj->clj source))
  ([source opts] (m2clj->clj source opts)))

#?(:clj
   (defn ^:no-doc to-m2clj
     "CLI-dispatch adapter: Clojure source → m2clj. JVM only.
      Library callers should use `clj->m2clj` directly."
     ([source] (clj->m2clj source))
     ([source _opts] (to-m2clj source))))

(def lang-map
  "Command map for the m2clj lang.
   :form-shape is the lang-owned semantic vocabulary — tools (formatter,
   future LSP/lint) consume it to know how this lang decomposes its
   special forms."
  {:extension  ".m2clj"
   :extensions [".m2cljc" ".m2cljj" ".m2cljs"]
   :format     format-m2clj
   :to-clj     to-clj
   :form-shape form-shape/registry
   #?@(:clj [:to-m2clj to-m2clj
              :run     (fn [source opts] (run/run-string source opts))
              :repl    (fn [opts] (repl/start opts))])})

;; Self-register as a built-in when this ns is loaded on JVM/Babashka.
#?(:clj (registry/register-builtin! :m2clj lang-map))

;; Install m2clj's string handler for :run — mirrors m1clj's convention:
;; a string value is a prelude .m2clj path that runs before user source.
;; register-string-handler! is first-wins, so when m1clj loads first
;; this is a no-op; the call is kept so m2clj is self-sufficient when
;; loaded standalone.
#?(:clj
   (registry/register-string-handler! :run
     (fn [prelude-path]
       (fn [source opts]
         (run/run-string (slurp prelude-path) (dissoc opts :prelude :lang))
         (run/run-string source opts)))))
