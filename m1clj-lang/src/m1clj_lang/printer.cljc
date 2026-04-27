(ns m1clj-lang.printer
  "m1clj printer: Clj* AST or plain Clojure forms → Doc trees.
   Builds Wadler-Lindig Doc trees handling m1clj syntax (call notation,
   sugar, metadata, comments) and Clojure output mode.

   The lossless path is AST: the AST tier captures notation (sugar form,
   namespace prefix, raw spelling, leading trivia) as record fields, so
   `format-m1clj` reconstructs the user's original syntax. Plain forms
   (the `forms->m1clj` / `forms->clj` path) are printed structurally —
   sugar collapses, comments are dropped, and sets render in hash order.
   Tooling that wants round-trip fidelity must consume AST.

   The printer is parameterized by a *style map* that controls layout
   policy (head-line-args, definition-form spacing, pair grouping,
   binding layout). Formatters own style: canon passes a full style,
   flat passes nil for true pass-through. See `to-doc` for the public
   entry point."
  (:require [clojure.string :as str]
            [meme.tools.render :as render]
            [meme.tools.clj.values :as values]
            [meme.tools.clj.forms :as forms]
            [meme.tools.clj.ast.nodes :as nodes
             #?@(:cljs [:refer [CljSymbol CljKeyword CljNumber CljString
                                CljChar CljRegex CljNil CljBool
                                CljList CljVector CljMap CljSet
                                CljQuote CljDeref CljVar
                                CljSyntaxQuote CljUnquote CljUnquoteSplicing
                                CljAnonFn CljDiscard
                                CljTagged CljReaderCond CljMeta CljNamespacedMap]])]
            [meme.tools.clj.ast.lower :as ast-lower]
            [m1clj-lang.form-shape :as form-shape])
  #?(:clj (:import [meme.tools.clj.ast.nodes
                    CljSymbol CljKeyword CljNumber CljString
                    CljChar CljRegex CljNil CljBool
                    CljList CljVector CljMap CljSet
                    CljQuote CljDeref CljVar
                    CljSyntaxQuote CljUnquote CljUnquoteSplicing
                    CljAnonFn CljDiscard
                    CljTagged CljReaderCond CljMeta CljNamespacedMap])))

;; ---------------------------------------------------------------------------
;; Comment extraction
;;
;; Comments live on AST node `:trivia` in the lossless path; plain forms
;; carry no comment information. `join-with-trailing-comments` is reused by
;; canon for the trailing-comment hand-off from `format-m1clj`, which
;; threads trailing trivia from the AST root onto the children vec as
;; `:trailing-ws` metadata.
;; ---------------------------------------------------------------------------

(defn- comment-lines-from-ws
  "Pull trimmed comment lines out of a raw whitespace/comment string."
  [ws]
  (when ws
    (let [lines (str/split ws #"\r?\n|\r")]
      (not-empty (mapv str/triml (filterv #(re-find #"^\s*;" %) lines))))))

(defn join-with-trailing-comments
  "Join formatted form strings with blank lines. Appends trailing comments
   from :trailing-ws metadata on the forms sequence."
  [format-fn forms]
  (let [trailing-ws (:trailing-ws (meta forms))
        trailing-comments (when trailing-ws (comment-lines-from-ws trailing-ws))
        body (str/join "\n\n" (map format-fn forms))]
    (if trailing-comments
      (let [comment-str (str/join "\n" trailing-comments)]
        (if (str/blank? body)
          comment-str
          (str body "\n\n" comment-str)))
      body)))

(defn- ast-comments
  "Extract trimmed comment lines from an AST node's :trivia, or nil.
  Returns nil for non-AST values."
  [node]
  (when (and (some? node)
             #?(:clj  (instance? meme.tools.clj.ast.nodes.AstNode node)
                :cljs (satisfies? nodes/AstNode node))
             (seq (:trivia node)))
    (let [comments (filterv #(= :comment (:type %)) (:trivia node))]
      (when (seq comments)
        (mapv (fn [t] (str/triml (str/trim-newline (:raw t)))) comments)))))

(defn- comment-doc
  "Build a Doc that emits comment lines followed by a hardline.
   Each comment line is on its own line."
  [comments]
  (reduce (fn [acc c]
            (render/doc-cat acc (render/text c) render/hardline))
          nil
          comments))

;; ---------------------------------------------------------------------------
;; Style — layout policy owned by formatters, threaded through ctx
;; ---------------------------------------------------------------------------
;; The printer is notation; the formatter is layout.  Style maps opine on
;; semantic slot names (emitted by `m1clj-lang.form-shape/decompose`), not
;; on form names — so `defn`, `defn-`, `defmacro`, and any user macro that
;; decomposes to the same slots all get identical layout for free.
;;
;; Style keys:
;;   :head-line-slots       set of slot names that stay on the head line
;;   :force-open-space-for  set of slot names whose presence forces `head( `
;;                          (open-paren followed by space even when flat)
;;
;; A nil style means "no layout opinions" — all slots collapse into the
;; body and nothing is force-spaced: true pass-through (flat formatter).

(defn- ctx-style
  "Get the resolved style from a ctx, defaulting nil to an empty map."
  [ctx]
  (or (:style ctx) {}))

;; ---------------------------------------------------------------------------
;; Doc constants — avoid per-form allocation of common Doc nodes (P6)
;; ---------------------------------------------------------------------------

(def ^:private doc-open-paren  (render/text "("))
(def ^:private doc-close-paren (render/text ")"))
(def ^:private doc-space       (render/text " "))
(def ^:private doc-caret       (render/text "^"))
(def ^:private doc-at          (render/text "@"))
(def ^:private doc-quote       (render/text "'"))
(def ^:private doc-var-quote   (render/text "#'"))
(def ^:private doc-backtick    (render/text "`"))
(def ^:private doc-unquote     (render/text "~"))
(def ^:private doc-unquote-splicing (render/text "~@"))

;; ---------------------------------------------------------------------------
;; Sequence realization guard
;; ---------------------------------------------------------------------------

(def ^:private ^:const max-print-elements
  "Hard cap on elements realized from lazy sequences to prevent OOM.
   Overridden by *print-length* when bound."
  10000)

(defn- bounded-vec
  "Realize a sequence into a vector with a bounded element count.
   Uses *print-length* if bound, else max-print-elements.
   Returns [items truncated?]."
  [s]
  (let [limit (or *print-length* max-print-elements)
        items (into [] (take limit) s)
        ;; RT6-F1: use seq not (some? (first ...)) — (first (drop ...)) returns nil
        ;; both when the sequence is exhausted AND when the next element IS nil,
        ;; causing silent truncation loss for sequences with nil at limit+1.
        truncated? (boolean (seq (drop limit s)))]
    [items truncated?]))

;; ---------------------------------------------------------------------------
;; Doc helpers for building common structures
;; ---------------------------------------------------------------------------

(defn- intersperse
  "Interleave docs with separator, returning concatenated Doc."
  [sep docs]
  (when (seq docs)
    (reduce (fn [acc d] (render/doc-cat acc sep d)) (first docs) (rest docs))))

;; ---------------------------------------------------------------------------
;; Form → Doc — notation engine, parameterized by style from formatters
;; ---------------------------------------------------------------------------

;; Internal recursive entry point: (to-doc-inner form ctx).
;; Public entry point: (to-doc form mode style) builds ctx and delegates.
(declare to-doc-inner to-doc-form)

(defn- doc-flat-width
  "Compute the flat-rendered width of a Doc node."
  [doc]
  (count (render/layout doc ##Inf)))

(defn- columnar-pairs-doc
  "Format pairs with columnar alignment: keys padded to max key width.
   Padding only visible when the enclosing group breaks (flat = no padding).
   pairs is a seq of [key-form value-form] or [key-form] (odd tail)."
  [pairs ctx]
  (let [key-docs (mapv #(to-doc-inner (first %) ctx) pairs)
        key-widths (mapv doc-flat-width key-docs)
        max-key-w (if (seq key-widths) (apply max key-widths) 0)]
    (mapv (fn [pair key-doc key-w]
            (if (= 2 (count pair))
              (let [pad-n (- max-key-w key-w)
                    pad-doc (when (pos? pad-n)
                              (render/->DocIfBreak
                               (render/text (apply str (repeat pad-n \space)))
                               nil))
                    ;; Nest value at key column so multiline values indent correctly
                    val-indent (+ max-key-w 1)
                    val-doc (render/nest val-indent (to-doc-inner (second pair) ctx))]
                (render/doc-cat key-doc pad-doc doc-space val-doc))
              key-doc))
          pairs key-docs key-widths)))

(defn- binding-vector-doc
  "Format a binding vector with columnar pair-per-line layout."
  [children ctx]
  (if (empty? children)
    (render/text "[]")
    (let [pairs (partition-all 2 children)
          pair-docs (columnar-pairs-doc (vec pairs) ctx)]
      (render/group
       (render/doc-cat
        (render/text "[")
        (render/nest 1 (render/doc-cat (intersperse render/line pair-docs)))
        (render/text "]"))))))

(defn- emit-meta-prefix-doc
  "Compute metadata prefix as a Doc node: ^:key, ^Type, or ^{map}.
   L12: returns Doc (not string) so metadata maps participate in width-aware layout."
  [m ctx]
  (cond
    (and (= 1 (count m))
         (keyword? (key (first m)))
         (true? (val (first m))))
    (let [kw (key (first m))]
      (render/text (str "^" (pr-str kw))))
    (and (= 1 (count m))
         (contains? m :tag)
         (symbol? (:tag m)))
    (render/text (str "^" (:tag m)))
    :else
    (render/doc-cat doc-caret (to-doc-form m ctx))))

;; ---------------------------------------------------------------------------
;; Slot renderers — per-slot display logic, style-overridable
;; ---------------------------------------------------------------------------
;; Each renderer is (fn [value ctx] → Doc).  The defaults implement the
;; structural meaning of each slot (a :clause value is a pair; a :bindings
;; value is a columnar-laid-out binding vector).  A style may override any
;; slot via its :slot-renderers map — the printer merges style overrides
;; over these defaults, so partial overrides compose.

(defn- bindings-slot-renderer
  "Render a :bindings slot value as a columnar pair-per-line binding vector.
  Polymorphic: accepts a plain vector of forms or a CljVector AST node."
  [value ctx]
  (let [children (if (instance? CljVector value)
                   (:children value)
                   value)]
    (binding-vector-doc children ctx)))

(defn- clause-slot-renderer
  "Render a :clause slot value (a [test value] pair) as `test value`
   joined by a single space.  Doc algebra handles flat vs broken layout
   via the enclosing group."
  [value ctx]
  (let [[a b] value]
    (render/doc-cat (to-doc-inner a ctx) doc-space (to-doc-inner b ctx))))

(def default-slot-renderers
  "Default per-slot structural renderers, keyed by slot name (see
   `m1clj-lang.form-shape` for the vocabulary).  Each renderer is
   `(fn [value ctx] → Doc)`.  Styles extend or override this map via
   their `:slot-renderers` key; the printer merges overrides over these
   defaults, so a style that overrides only one slot keeps the rest."
  {:bindings bindings-slot-renderer
   :clause   clause-slot-renderer})

(defn- slot->doc
  "Render a form-shape slot entry.  Order of resolution:
     1. style's `:slot-renderers` override for this slot name
     2. the printer's `default-slot-renderers`
     3. plain recursive rendering via `to-doc-inner`"
  [[slot-name value] ctx]
  (let [overrides (get-in ctx [:style :slot-renderers])]
    (if-let [renderer (or (get overrides slot-name)
                          (get default-slot-renderers slot-name))]
      (renderer value ctx)
      (to-doc-inner value ctx))))

(defn- body-sequence-doc
  "Render a sequence of arg-docs as a parenthesized body with all args
   on separate lines when broken: `(` line0 d1 line d2 ... line0 `)`."
  [head-doc arg-docs]
  (if (empty? arg-docs)
    (render/group (render/doc-cat head-doc doc-open-paren doc-close-paren))
    (render/group
     (render/doc-cat
      head-doc doc-open-paren
      (render/nest 2 (render/doc-cat render/line0 (intersperse render/line arg-docs)))
      render/line0
      doc-close-paren))))

(defn- head-body-split-doc
  "Render head-line args + body args: `head( [ensure-space] h1 h2 nl b1 nl b2 nl0 )`.
   after-paren is `doc-space` to force open-space, or DocIfBreak for break-only."
  [head-doc head-docs body-docs after-paren]
  (render/group
   (render/doc-cat
    head-doc doc-open-paren after-paren
    (render/nest 2
                 (render/doc-cat
                  (render/group (intersperse render/line head-docs))
                  (reduce (fn [acc d] (render/doc-cat acc render/line d)) nil body-docs)))
    render/line0
    doc-close-paren)))

(defn- body-sequence-doc-clj
  "Clojure mode body sequence: `(head a b c)`. Closing paren hugs the
   last arg per Clojure convention (no line0 before `)`)."
  [head-doc arg-docs]
  (if (empty? arg-docs)
    (render/group (render/doc-cat doc-open-paren head-doc doc-close-paren))
    (render/group
     (render/doc-cat
      doc-open-paren head-doc
      (render/nest 2 (render/doc-cat render/line (intersperse render/line arg-docs)))
      doc-close-paren))))

(defn- head-body-split-doc-clj
  "Clojure mode head-line / body split: `(head h1 h2\\n  b1\\n  b2)`.
   Head-line slots stay inline with the head (forced space, not a
   breakable line) — `(let [bindings]\\n  body)` not `(let\\n  [bindings]\\n  body)`.
   Body slots break onto subsequent lines indented by 2. Closing paren
   hugs the last body arg per Clojure convention."
  [head-doc head-docs body-docs]
  (render/group
   (render/doc-cat
    doc-open-paren head-doc
    doc-space
    (render/nest 2
                 (render/doc-cat
                  (render/group (intersperse render/line head-docs))
                  (reduce (fn [acc d] (render/doc-cat acc render/line d)) nil body-docs)))
    doc-close-paren)))

(defn- call-doc
  "Build Doc for a call form.

   Slot decomposition is mode-independent: `form-shape/decompose` runs
   in both `:m1clj` and `:clj` mode and the resulting head/body slot
   split is the same. Only the *geometry* differs by mode — meme writes
   `head( body )` with optional after-paren spacing, Clojure writes
   `(head body)` with the closing paren hugging the last arg. The style
   key `:force-open-space-for` is meme-specific and is ignored under
   `:clj` mode."
  [head args ctx]
  (let [mode     (:mode ctx)
        head-doc (to-doc-inner head ctx)
        clj?     (= mode :clj)]
    (if-let [slots (form-shape/decompose (:form-shape ctx) head args)]
      ;; Slot-aware rendering
      (let [style        (ctx-style ctx)
            head-set     (:head-line-slots style #{})
            {head-slots  true
             body-slots  false} (group-by #(contains? head-set (first %)) slots)
            head-docs    (mapv #(slot->doc % ctx) head-slots)
            body-docs    (mapv #(slot->doc % ctx) body-slots)]
        (cond
          ;; Both head and body non-empty — split layout applies.
          (and (seq head-docs) (seq body-docs))
          (if clj?
            (head-body-split-doc-clj head-doc head-docs body-docs)
            (let [force-set    (:force-open-space-for style #{})
                  force-space? (boolean (some #(contains? force-set (first %)) head-slots))
                  after-paren  (if force-space?
                                 doc-space
                                 (render/->DocIfBreak doc-space nil))]
              (head-body-split-doc head-doc head-docs body-docs after-paren)))

          ;; Only one side has entries — single body sequence.
          ;; Preserves prior flat shape for forms like `defn(foo)`.
          :else
          (if clj?
            (body-sequence-doc-clj head-doc (into head-docs body-docs))
            (body-sequence-doc head-doc (into head-docs body-docs)))))
      ;; No form-shape registered — plain call, all args in body.
      (let [arg-docs (mapv #(to-doc-inner % ctx) args)]
        (if clj?
          (body-sequence-doc-clj head-doc arg-docs)
          (body-sequence-doc head-doc arg-docs))))))

(defn- collection-doc
  "Build Doc for a delimited collection: [elems], #{elems}, #(body).
   :inline? true puts first element right after open bracket with
   alignment indent (for vectors); false indents all elements (for sets, #())."
  ([open close children ctx] (collection-doc open close children ctx false))
  ([open close children ctx inline?]
   (if (empty? children)
     (render/text (str open close))
     (let [child-docs (mapv #(to-doc-inner % ctx) children)
           open-doc (render/text open)
           close-doc (render/text close)]
       (if inline?
         ;; Inline: [first-elem\n aligned-rest]
         (render/group
          (render/doc-cat
           open-doc
           (render/nest (count open)
                        (render/doc-cat (intersperse render/line child-docs)))
           close-doc))
         ;; Block: open\n  indented-elems\n close
         (render/group
          (render/doc-cat
           open-doc
           (render/nest 2 (render/doc-cat render/line0 (intersperse render/line child-docs)))
           render/line0
           close-doc)))))))

(defn- pairs-doc
  "Build Doc for key-value pairs: {k v ...}, #:ns{k v ...}, #?(k v ...).
   Keys are columnar-aligned when multi-line.
   First pair inline after open delimiter, rest aligned."
  [open close entries ctx]
  (if (empty? entries)
    (render/text (str open close))
    (let [pair-docs (columnar-pairs-doc (vec entries) ctx)
          open-doc (render/text open)]
      (render/group
       (render/doc-cat
        open-doc
        (render/nest (count open) (render/doc-cat (intersperse render/line pair-docs)))
        (render/text close))))))

(defn- effective-ast-children
  "Filter CljDiscard nodes from a children vec — discard nodes are notation,
  not data, and don't appear as positional children of their parent."
  [children]
  (filterv #(not (instance? CljDiscard %)) children))

(defn- to-doc-ast-node
  "Build a Doc for a Clj* AST record. Returns nil for non-AST input so the
  caller can fall through to the form-with-metadata branch."
  [node ctx]
  (let [mode (:mode ctx)]
    (cond
      ;; --- Atomic literals -------------------------------------------------

      (instance? CljSymbol node)
      (render/text (if (:ns node)
                     (str (:ns node) "/" (:name node))
                     (:name node)))

      (instance? CljKeyword node)
      (let [{:keys [name ns auto-resolve?]} node
            prefix (if auto-resolve? "::" ":")]
        (render/text (cond
                       (and ns name) (str prefix ns "/" name)
                       ns            (str prefix ns)
                       :else         (str prefix name))))

      (instance? CljNumber node)
      (render/text (:raw node))

      (instance? CljString node)
      ;; AST may not always carry :raw (programmatic construction); fall back
      ;; to pr-str to produce a valid string literal.
      (render/text (or (:raw node) (pr-str (:value node))))

      (instance? CljChar node)
      (render/text (:raw node))

      (instance? CljRegex node)
      (render/text (str "#\"" (:pattern node) "\""))

      (instance? CljNil node)  (render/text "nil")
      (instance? CljBool node) (render/text (str (:value node)))

      ;; --- Reader-macro / sugar nodes --------------------------------------

      (instance? CljQuote node)
      (render/doc-cat doc-quote (to-doc-inner (:form node) ctx))

      (instance? CljDeref node)
      (render/doc-cat doc-at (to-doc-inner (:form node) ctx))

      (instance? CljVar node)
      (render/doc-cat doc-var-quote (to-doc-inner (:form node) ctx))

      (instance? CljSyntaxQuote node)
      (render/doc-cat doc-backtick (to-doc-inner (:form node) ctx))

      (instance? CljUnquote node)
      ;; Suppress @-deref sugar inside ~ to prevent ~@ ambiguity:
      ;; CljUnquote{form: CljDeref{form: x}} would otherwise render as
      ;; `~@x`, which re-parses as unquote-splicing.  Fall back to the
      ;; explicit `clojure.core/deref(...)` call form.
      (let [inner (:form node)]
        (if (instance? CljDeref inner)
          (render/doc-cat
            doc-unquote
            (call-doc (nodes/->CljSymbol "deref" "clojure.core" nil [])
                      [(:form inner)]
                      ctx))
          (render/doc-cat doc-unquote (to-doc-inner inner ctx))))

      (instance? CljUnquoteSplicing node)
      (render/doc-cat doc-unquote-splicing (to-doc-inner (:form node) ctx))

      (instance? CljAnonFn node)
      ;; AST stores raw body (with `%` or `%1` as written) so no
      ;; bare-percent restoration is needed.
      (let [body (:body node)]
        (cond
          ;; :clj mode + non-list body: #(42) means (fn [] (42)) in Clojure,
          ;; which calls 42 — different semantics. Fall through to (fn ...).
          ;; Compute %-params from the lowered body so the synthetic fn has
          ;; the right arity.
          (and (= mode :clj) (not (instance? CljList body)))
          (let [body-form (ast-lower/ast->form body)
                params (forms/find-percent-params body-form)
                fn-params (forms/build-anon-fn-params params)
                fn-form (list 'fn fn-params body-form)]
            (to-doc-form fn-form ctx))
          ;; :clj mode + list body: unwrap to #(body...) to avoid double parens.
          (and (= mode :clj) (instance? CljList body))
          (collection-doc "#(" ")" (effective-ast-children (:children body)) ctx)
          ;; :m1clj mode: keep #(body) wrapping intact.
          :else
          (collection-doc "#(" ")" [body] ctx)))

      (instance? CljDiscard node)
      ;; #_ form — emit `#_` prefix + recurse. (Top-level discards filtered
      ;; by the parent collection / formatter; this branch handles cases
      ;; where a discard was deliberately preserved in the tree.)
      (render/doc-cat (render/text "#_") (to-doc-inner (:form node) ctx))

      ;; --- Compound nodes --------------------------------------------------

      (instance? CljMeta node)
      (let [chain (:chain node)
            target (:target node)
            prefix-docs (mapv (fn [m]
                                (let [m-form (cond
                                               (instance? CljKeyword m)
                                               {(if-let [n (:ns m)]
                                                  (keyword n (:name m))
                                                  (keyword (:name m))) true}
                                               (instance? CljSymbol m)
                                               {:tag (if (:ns m)
                                                       (symbol (:ns m) (:name m))
                                                       (symbol (:name m)))}
                                               (instance? CljString m)
                                               {:tag (:value m)}
                                               (instance? CljMap m)
                                               ;; Render the map's pairs as a meta map directly.
                                               m
                                               :else m)]
                                  (emit-meta-prefix-doc m-form ctx)))
                              (reverse chain))
            prefix-doc (reduce (fn [acc d] (render/doc-cat acc doc-space d))
                               (first prefix-docs)
                               (rest prefix-docs))]
        (render/doc-cat prefix-doc doc-space (to-doc-inner target ctx)))

      (instance? CljTagged node)
      (render/doc-cat (render/text (str "#" (:tag node) " "))
                      (to-doc-inner (:form node) ctx))

      (instance? CljReaderCond node)
      (let [prefix (if (:splicing? node) "#?@(" "#?(")
            pairs (:pairs node)]
        (pairs-doc prefix ")" (mapv vec pairs) ctx))

      (instance? CljNamespacedMap node)
      (let [{:keys [ns auto-resolve? inner]} node
            open (if auto-resolve?
                   (if (str/blank? ns) "#::{" (str "#::" ns "{"))
                   (str "#:" ns "{"))]
        (pairs-doc open "}" (mapv vec (:pairs inner)) ctx))

      ;; --- Collections -----------------------------------------------------

      (instance? CljList node)
      (let [children (effective-ast-children (:children node))]
        (cond
          (empty? children)
          (render/text "()")

          ;; Treat as a call: head = first child, args = rest.
          :else
          (let [head (first children)
                args (vec (rest children))]
            (call-doc head args ctx))))

      (instance? CljVector node)
      (collection-doc "[" "]" (effective-ast-children (:children node)) ctx true)

      (instance? CljMap node)
      (pairs-doc "{" "}" (mapv vec (:pairs node)) ctx)

      (instance? CljSet node)
      ;; CljSet `:children` is already source-ordered — no :insertion-order
      ;; metadata lookup needed.
      (collection-doc "#{" "}" (effective-ast-children (:children node)) ctx true)

      :else nil)))

(defn- to-doc-form
  "Convert a Clojure form (or Clj* AST node) to a Doc tree. AST input is
  dispatched first; falls through to a structural form path otherwise.
  Plain forms have no notation metadata — sugar collapses, sets render in
  hash order. ctx is {:mode :m1clj|:clj, :style style-map-or-nil}."
  [form ctx]
  (or (to-doc-ast-node form ctx)
      (cond
        ;; Metadata prefix — checked first, before structural checks.
        (and (some? form)
             #?(:clj (instance? clojure.lang.IObj form)
                :cljs (satisfies? IWithMeta form))
             (some? (meta form))
             (seq (forms/strip-internal-meta (meta form))))
        (let [user-meta (forms/strip-internal-meta (meta form))
              stripped (with-meta form nil)
              prefix-doc (emit-meta-prefix-doc user-meta ctx)]
          (render/doc-cat prefix-doc doc-space (to-doc-form stripped ctx)))

        ;; Raw value wrapper — emit original source text
        (forms/raw? form) (render/text (:raw form))

        ;; nil
        (nil? form) (render/text "nil")

        ;; boolean
        (boolean? form) (render/text (str form))

        ;; Deferred auto-resolve keywords
        (forms/deferred-auto-keyword? form)
        (render/text (forms/deferred-auto-keyword-raw form))

        ;; Empty list
        (and (seq? form) (empty? form))
        (render/text "()")

        ;; Sequences — print as a call.  All sugars (`'`, `@`, `#'`, `#()`)
        ;; are AST-only: the form path lost the user's notation choice when
        ;; we dropped the metadata vocabulary, so it falls back to the
        ;; explicit call form (matching Clojure's own `pr-str` behavior).
        (seq? form)
        (let [head (first form)
              [args truncated?] (bounded-vec (rest form))
              args (if truncated? (conj args (symbol "...")) args)]
          (call-doc head args ctx))

        ;; Syntax-quote / unquote / unquote-splicing AST nodes
        ;; Must be before map? (defrecords satisfy map?)
        (forms/syntax-quote? form)
        (render/doc-cat doc-backtick (to-doc-inner (:form form) ctx))

        (forms/unquote? form)
        (render/doc-cat doc-unquote (to-doc-inner (:form form) ctx))

        (forms/unquote-splicing? form)
        (render/doc-cat doc-unquote-splicing (to-doc-inner (:form form) ctx))

        ;; Reader conditional — must be before map?
        (forms/clj-reader-conditional? form)
        (let [prefix (if (forms/rc-splicing? form) "#?@(" "#?(")
              branches (forms/rc-form form)]
          (when (odd? (count branches))
            (throw (ex-info "Reader conditional has odd number of forms (missing value for last platform key)"
                            {:form form})))
          (pairs-doc prefix ")" (vec (partition 2 branches)) ctx))

        ;; Vector
        (vector? form)
        (collection-doc "[" "]" (vec form) ctx true)

        ;; Map — render structurally; namespaced-map prefixes live on AST.
        (map? form)
        (pairs-doc "{" "}" (vec form) ctx)

        ;; Set — hash order; source order lives on AST.
        (set? form)
        (collection-doc "#{" "}" (vec form) ctx true)

        ;; Symbol
        (symbol? form) (render/text (str form))

        ;; Keyword
        (keyword? form)
        (render/text (if (namespace form)
                       (str ":" (namespace form) "/" (name form))
                       (str ":" (name form))))

        ;; Tagged literals need Doc-tree recursion, so handle separately.
        #?@(:clj [(tagged-literal? form)
                  (let [^clojure.lang.TaggedLiteral tl form]
                    (render/doc-cat (render/text (str "#" (.-tag tl) " ")) (to-doc-inner (.-form tl) ctx)))])

        :else
        (if-let [s (values/emit-value-str form pr-str)]
          (render/text s)
          (render/text (pr-str form))))))

(defn- to-doc-inner
  "Internal recursive entry point: form/AST + ctx → Doc with comment attachment.
  Comments come from AST `:trivia`; plain forms carry no comment information."
  [form ctx]
  (let [doc (to-doc-form form ctx)
        comments (ast-comments form)]
    (if comments
      (render/doc-cat (comment-doc comments) doc)
      doc)))

(defn to-doc
  "Convert a Clojure form to a Doc tree, with comment attachment.
   Comments are always emitted — the hardline in comment-doc forces the
   enclosing group to break, so comments are never silently dropped.

   mode        :m1clj (default) or :clj.
   style       layout policy map (nil = pass-through, no opinions).
               Keyed by semantic slot names, not form names.
   form-shape  registry map (head-symbol → decomposer fn).  When nil,
               no form is decomposed — every call renders as a plain
               body sequence.  The lang owns this registry; formatters
               pass it in.

   Formatters own style — see canon and flat formatter modules."
  ([form] (to-doc form :m1clj))
  ([form mode] (to-doc form mode nil))
  ([form mode style] (to-doc form mode style nil))
  ([form mode style form-shape]
   (to-doc-inner form {:mode mode, :style style, :form-shape form-shape})))

(defn validate-format-input
  "Guard format-forms input: reject nil, strings, maps, and sets."
  [forms]
  (when (nil? forms)
    (throw (ex-info "format-forms expects a sequence of forms, not nil" {})))
  (when (string? forms)
    (throw (ex-info "format-forms expects a sequence of forms, not a string"
                    {:input (subs forms 0 (min 50 (count forms)))})))
  (when (or (map? forms) (set? forms))
    (throw (ex-info (str "format-forms expects a sequence of forms, not a "
                         #?(:clj (.getName (class forms)) :cljs (pr-str (type forms))))
                    {})))
  (when-not (sequential? forms)
    (throw (ex-info (str "format-forms expects a sequence of forms, not a "
                         #?(:clj (.getName (class forms)) :cljs (pr-str (type forms))))
                    {}))))
