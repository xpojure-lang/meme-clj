(ns m1clj-lang.api
  "m1clj lang composition: lossless pipeline with Pratt parser and AST tier.

   Pipeline: source → strip-preamble → parser → cst→ast → ast→form
   The parser produces a lossless CST; cst→ast lifts it into the Clj* AST
   tier (position, trivia, and notation as fields); ast→form lowers AST
   to plain Clojure values for eval. Tooling consumers stop at the AST."
  (:require [meme.tools.clj.stages :as stages]
            [meme.tools.clj.ast.build :as ast-build]
            [meme.tools.clj.ast.lower :as ast-lower]
            [meme.tools.clj.parser.api :as clj-parser]
            [meme.tools.parser :as parser]
            [m1clj-lang.grammar :as grammar]
            [m1clj-lang.form-shape :as form-shape]
            [m1clj-lang.formatter.flat :as fmt-flat]
            [m1clj-lang.formatter.canon :as fmt-canon]
            [meme.tools.clj.expander :as expander]
            #?(:clj [m1clj-lang.run :as run])
            #?(:clj [m1clj-lang.repl :as repl])
            #?(:clj [meme.registry :as registry])))

;; ---------------------------------------------------------------------------
;; Lang API — delegates to composable stages
;; ---------------------------------------------------------------------------

(defn- guard-deprecated-opts!
  "Reject opts that were valid in pre-AST stages but no longer apply.
  Mirrors the guard previously living in `meme.tools.clj.stages/step-read`."
  [opts]
  (when (contains? opts :read-cond)
    (throw (ex-info
             (str "The :read-cond option is no longer supported. The m1clj "
                  "reader always preserves reader conditionals as "
                  "CljReaderConditional records. To evaluate them for a "
                  "platform, compose "
                  "meme.tools.clj.stages/step-evaluate-reader-conditionals "
                  "after reading, or use m1clj-lang.run/run-string / run-file.")
             {:type    :m1clj/deprecated-opt
              :opt     :read-cond
              :value   (:read-cond opts)}))))

(defn m1clj->ast
  "Read m1clj source string. Returns a `CljRoot` AST node whose `:children`
  vec contains one AST node per top-level form.

  The AST tier captures position, leading/close trivia, and notation
  (sugar form, namespace prefix, raw spelling) as record fields rather
  than metadata — survives any walker, even ones that don't know about
  m1clj's metadata vocabulary. See `meme.tools.clj.ast.nodes` for the
  25 node types.

  Tooling that needs round-trip fidelity, scoped refactoring, or
  position-attributed warnings should consume the AST directly.
  Callers that just want eval-ready Clojure forms should use
  `m1clj->forms`, which composes lowering on top.

  opts keys:
    :resolve-keyword  — fn applied at lowering for `::kw`; not used at
                        AST build time (the AST captures source structure)
    :grammar          — custom Pratt grammar spec (advanced)"
  ([s] (m1clj->ast s nil))
  ([s opts]
   {:pre [(string? s)]}
   (guard-deprecated-opts! opts)
   (let [src (stages/strip-source-preamble s)
         cst (parser/parse src (or (:grammar opts) grammar/grammar))]
     (ast-build/cst->ast cst opts))))

(defn m1clj->forms
  "Read m1clj source string. Returns a vector of Clojure forms.
   Composes `m1clj->ast` followed by AST → form lowering.

   Reader conditionals (`#?`, `#?@`) are always returned as
   `CljReaderConditional` records. To evaluate them for the current
   platform, compose `meme.tools.clj.stages/step-evaluate-reader-conditionals`
   after reading, or use `run-string` / `run-file` (which do so
   automatically). The `:read-cond` option is no longer accepted —
   passing it throws `:m1clj/deprecated-opt`.

   opts keys:
     :resolve-keyword  — fn to resolve auto-resolve keywords (::kw)
     :resolve-symbol   — fn to resolve symbols in syntax-quote expansion
     :grammar          — custom Pratt grammar spec (advanced)"
  ([s] (m1clj->forms s nil))
  ([s opts]
   {:pre [(string? s)]}
   (ast-lower/ast->forms (m1clj->ast s opts) opts)))

(defn forms->m1clj
  "Print Clojure forms as m1clj source string (single-line per form).
   Takes a SEQUENCE of forms (vector or seq), not a single form."
  [forms]
  {:pre [(sequential? forms)]}
  (fmt-flat/format-forms forms))

(defn format-m1clj-forms
  "Format Clojure forms as canonical m1clj source string (multi-line, indented).

   opts keys:
     :width  — target line width (int, default 80)"
  ([forms] (format-m1clj-forms forms nil))
  ([forms opts]
   {:pre [(sequential? forms)]}
   (fmt-canon/format-forms forms opts)))

(defn forms->clj
  "Print Clojure forms as Clojure source string. Plain forms carry no
  notation — sugar collapses (`(quote x)` still prints as `'x` because
  that is canonical Clojure), but `@x`, `#'x`, `#()`, set source order,
  and namespaced-map prefixes are all lost. For lossless m1clj→clj, use
  `m1clj->clj` which goes through the AST tier."
  [forms]
  (fmt-flat/format-clj (expander/expand-forms forms)))

(declare ^:private ast-root->children-with-trailing)

(defn m1clj->clj
  "Convert m1clj source to Clojure source string (lossless via AST).

   Reader conditionals are preserved as `#?(...)` rather than evaluated for
   the current platform — faithful for `.cljc` conversion. For the eval-time
   value, use `run-string` instead.

   opts: same as `m1clj->ast` (`:resolve-keyword`, `:grammar`)."
  ([meme-src] (m1clj->clj meme-src nil))
  ([meme-src opts]
   {:pre [(string? meme-src)]}
   (let [ast (m1clj->ast meme-src opts)
         children (ast-root->children-with-trailing ast)]
     (fmt-flat/format-clj children))))

(defn clj->ast
  "Read Clojure source string. Returns a `CljRoot` AST node whose
  `:children` vec contains one AST node per top-level form.

  Uses the native Clojure parser — works on JVM, Babashka, and
  ClojureScript without depending on host `read-string`. Reader
  conditionals (`#?`, `#?@`) are preserved as `CljReaderCond` AST
  records.

  See `m1clj->ast` for the AST shape; both routines produce the same
  `Clj*` node taxonomy. The only difference is which parser surface
  read the source.

  opts keys:
    :resolve-keyword  — fn applied at lowering for `::kw`
    :grammar          — alternate Pratt grammar spec (advanced)"
  ([clj-src] (clj->ast clj-src nil))
  ([clj-src opts]
   {:pre [(string? clj-src)]}
   (clj-parser/clj->ast clj-src opts)))

(defn clj->forms
  "Read Clojure source string, return a vector of plain Clojure forms.

  Composes `clj->ast` followed by AST → form lowering. Cross-platform —
  no longer depends on `clojure.core/read-string`. Reader conditionals
  are returned as `CljReaderConditional` records (use `step-evaluate-reader-conditionals`
  to materialise a platform branch).

  opts keys:
    :resolve-keyword  — fn to resolve auto-resolve keywords (`::kw`)
    :grammar          — alternate Pratt grammar spec"
  ([clj-src] (clj->forms clj-src nil))
  ([clj-src opts]
   {:pre [(string? clj-src)]}
   (clj-parser/clj->forms clj-src opts)))

(defn clj->m1clj
  "Convert Clojure source to m1clj source string (lossless via AST).

  Routes through the AST tier and the printer's default `:m1clj` mode
  — comments, namespaced-map prefixes, set source order, multi-tier
  metadata, and bare `%` notation all survive the conversion. Reader
  conditionals are preserved as `#?(...)` rather than evaluated.

  opts: same as `clj->ast` (`:resolve-keyword`, `:grammar`)."
  ([clj-src] (clj->m1clj clj-src nil))
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
  the formatter's join-with-trailing-comments expects from
  cst-reader/read-forms."
  [root]
  (let [children (filterv #(not (instance? meme.tools.clj.ast.nodes.CljDiscard %))
                          (:children root))
        trailing (seq (:trailing-trivia root))]
    (if trailing
      (with-meta children
        {:trailing-ws (apply str (map :raw trailing))})
      children)))

(defn format-m1clj
  "Format m1clj source text. Reads source, formats via canonical formatter.
  Source-driven path goes through AST without intermediate lowering — the
  printer dispatches on AST nodes directly, preserving notation that
  forms-with-metadata would have collapsed."
  [source opts]
  (let [ast (m1clj->ast source opts)
        children (ast-root->children-with-trailing ast)]
    (if (empty? children)
      source
      (case (:style opts)
        "flat" (fmt-flat/format-forms children)
        "clj"  (fmt-flat/format-clj children)
        (fmt-canon/format-forms children opts)))))

(defn ^:no-doc to-clj
  "CLI-dispatch adapter: m1clj source → Clojure text. Library callers should
   use `m1clj->clj` directly — it has the same lossless behavior."
  ([source] (m1clj->clj source))
  ([source opts] (m1clj->clj source opts)))

#?(:clj
   (defn ^:no-doc to-m1clj
     "CLI-dispatch adapter: Clojure source → m1clj. JVM only.
      Library callers should use `clj->m1clj` directly."
     ([source] (clj->m1clj source))
     ([source _opts] (to-m1clj source))))

(def lang-map
  "Command map for the m1clj lang.
   :form-shape is the lang-owned semantic vocabulary — tools (formatter,
   future LSP/lint) consume it to know how this lang decomposes its
   special forms."
  ;; .m1clj is the primary extension. .meme / .memec / .memej / .memejs are
  ;; soft-deprecated for one release: still recognized so existing files load,
  ;; but emit a one-time warning per process. Removal is planned in the next
  ;; major. See `meme.tools.clj.run` for the warning hook.
  {:extension ".m1clj"
   :extensions [".m1cljc" ".m1cljj" ".m1cljs"
                ".meme" ".memec" ".memej" ".memejs"]
   :format     format-m1clj
   :to-clj     to-clj
   :form-shape form-shape/registry
   #?@(:clj [:to-m1clj to-m1clj
              :run     (fn [source opts] (run/run-string source opts))
              :repl    (fn [opts] (repl/start opts))])})

;; Self-register as a built-in when this ns is loaded on JVM/Babashka.
;; The registry imports no langs; langs register themselves — this keeps
;; the registry pure infrastructure and avoids the old circular dep.
#?(:clj (registry/register-builtin! :m1clj lang-map))

;; Install m1clj's string handler for :run — a string value is a prelude
;; .m1clj path that runs before user source. Keeps this m1clj convention
;; inside the lang's own namespace rather than hardcoded in the registry.
#?(:clj
   (registry/register-string-handler! :run
     (fn [prelude-path]
       (fn [source opts]
         (run/run-string (slurp prelude-path) (dissoc opts :prelude :lang))
         (run/run-string source opts)))))
