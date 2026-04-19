(ns meme-lang.printer
  "Meme printer: Clojure forms → Doc trees.
   Builds Wadler-Lindig Doc trees from Clojure forms, handling meme syntax
   (call notation, sugar, metadata, comments) and Clojure output mode.
   Delegates to render for Doc algebra and layout.

   The printer is parameterized by a *style map* that controls layout policy
   (head-line-args, definition-form spacing, pair grouping, binding layout).
   Formatters own style: canon passes a full style, flat passes nil for
   true pass-through.  See `to-doc` for the public entry point."
  (:require [clojure.string :as str]
            [meme.tools.render :as render]
            [meme.tools.clj.values :as values]
            [meme-lang.forms :as forms]
            [meme-lang.form-shape :as form-shape]))

;; ---------------------------------------------------------------------------
;; Comment extraction from :meme-lang/leading-trivia metadata
;; ---------------------------------------------------------------------------

(defn- extract-comments
  "Extract comment lines from a :meme-lang/leading-trivia metadata string.
   Returns a vector of trimmed comment strings, or nil."
  [ws]
  (when ws
    (let [lines (str/split ws #"\r?\n|\r")]
      (not-empty (mapv str/triml (filterv #(re-find #"^\s*;" %) lines))))))

(defn join-with-trailing-comments
  "Join formatted form strings with blank lines. Appends trailing comments
   from :trailing-ws metadata on the forms sequence."
  [format-fn forms]
  (let [trailing-ws (:trailing-ws (meta forms))
        trailing-comments (when trailing-ws (extract-comments trailing-ws))
        body (str/join "\n\n" (map format-fn forms))]
    ;; RT6-F16: empty body + trailing comments must not produce leading \n\n
    (if trailing-comments
      (let [comment-str (str/join "\n" trailing-comments)]
        (if (str/blank? body)
          comment-str
          (str body "\n\n" comment-str)))
      body)))

(defn- form-comments
  "Get comment lines from a form's :meme-lang/leading-trivia metadata, or nil."
  [form]
  (when (and (some? form)
             #?(:clj  (instance? clojure.lang.IMeta form)
                :cljs (satisfies? IMeta form))
             (meta form))
    (extract-comments (:meme-lang/leading-trivia (meta form)))))

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
;; semantic slot names (emitted by `meme-lang.form-shape/decompose`), not
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

(defn- anon-fn-shorthand?
  "Can (fn [params] body) be printed as #(body)?
   Only when :meme-lang/sugar tagged by reader AND params are %-style."
  [form]
  (and (:meme-lang/sugar (meta form))
       (seq? form)
       (= 'fn (first form))
       (= 3 (count form))
       (vector? (second form))
       ;; RT6-F21: verify params are %-style — programmatic misuse of
       ;; :meme-lang/sugar on non-% fns would silently change semantics
       (every? #(and (symbol? %) (str/starts-with? (name %) "%"))
               (second form))))

;; restore-bare-percent moved to meme-lang.forms (co-located with normalize-bare-percent)

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
        max-key-w (apply max key-widths)]
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
  "Render a :bindings slot value as a columnar pair-per-line binding vector."
  [value ctx]
  (binding-vector-doc value ctx))

(defn- clause-slot-renderer
  "Render a :clause slot value (a [test value] pair) as `test value`
   joined by a single space.  Doc algebra handles flat vs broken layout
   via the enclosing group."
  [value ctx]
  (let [[a b] value]
    (render/doc-cat (to-doc-inner a ctx) doc-space (to-doc-inner b ctx))))

(def default-slot-renderers
  "Default per-slot structural renderers, keyed by slot name (see
   `meme-lang.form-shape` for the vocabulary).  Each renderer is
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

(defn- call-doc-clj
  "Clojure mode: (head arg1 arg2) with no head-line split."
  [head-doc arg-docs]
  (if (empty? arg-docs)
    (render/group (render/doc-cat doc-open-paren head-doc doc-close-paren))
    (render/group
     (render/doc-cat
      doc-open-paren head-doc
      (render/nest 2 (render/doc-cat render/line (intersperse render/line arg-docs)))
      doc-close-paren))))

(defn- call-doc
  "Build Doc for a call form.

   Dispatches on mode (meme vs clj) and on whether form-shape provides a
   semantic decomposition for the head.  When a decomposition is present,
   style opines on which slot names stay on the head line; when absent
   (plain calls, user fns without a registered shape), all args render in
   the body."
  [head args ctx]
  (let [mode     (:mode ctx)
        head-doc (to-doc-inner head ctx)]
    (if (= mode :clj)
      (call-doc-clj head-doc (mapv #(to-doc-inner % ctx) args))
      (if-let [slots (form-shape/decompose (:form-shape ctx) head args)]
        ;; Slot-aware rendering
        (let [style        (ctx-style ctx)
              head-set     (:head-line-slots style #{})
              force-set    (:force-open-space-for style #{})
              {head-slots  true
               body-slots  false} (group-by #(contains? head-set (first %)) slots)
              head-docs    (mapv #(slot->doc % ctx) head-slots)
              body-docs    (mapv #(slot->doc % ctx) body-slots)
              force-space? (boolean (some #(contains? force-set (first %)) head-slots))
              after-paren  (if force-space?
                             doc-space
                             (render/->DocIfBreak doc-space nil))]
          (cond
            ;; Both head and body non-empty — split layout applies.
            (and (seq head-docs) (seq body-docs))
            (head-body-split-doc head-doc head-docs body-docs after-paren)

            ;; Only one side has entries — treat as a single body sequence.
            ;; Matches prior behavior: `defn(foo)` flat, not `defn( foo)`.
            :else
            (body-sequence-doc head-doc (into head-docs body-docs))))
        ;; No form-shape registered — plain call, all args in body.
        (body-sequence-doc head-doc (mapv #(to-doc-inner % ctx) args))))))

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

(defn- to-doc-form
  "Convert a Clojure form to a Doc tree. Handles metadata wrapping.
   ctx is {:mode :meme|:clj, :style style-map-or-nil}."
  [form ctx]
  (let [mode (:mode ctx)]
    (cond
      ;; Metadata prefix — checked first, before structural checks.
      (and (some? form)
           #?(:clj (instance? clojure.lang.IObj form)
              :cljs (satisfies? IWithMeta form))
           (some? (meta form))
           (seq (forms/strip-internal-meta (meta form))))
      (let [chain (:meme-lang/meta-chain (meta form))
            stripped (with-meta form (select-keys (meta form) forms/notation-meta-keys))
            prefix-docs (if chain
                          (mapv #(emit-meta-prefix-doc % ctx) (reverse chain))
                          [(emit-meta-prefix-doc (forms/strip-internal-meta (meta form)) ctx)])
            ;; L12: compose prefix Docs with spaces, then the form Doc
            prefix-doc (reduce (fn [acc d] (render/doc-cat acc doc-space d))
                               (first prefix-docs)
                               (rest prefix-docs))]
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

      ;; Anon-fn shorthand #()
      ;; In :clj mode, only use #() when the body is a list (call).
      ;; #(42) in Clojure means (fn [] (42)) — calling 42 — not (fn [] 42).
      ;; F7: when :meme-lang/bare-percent, restore % from %1 in body before printing.
      (and (anon-fn-shorthand? form)
           (or (not= mode :clj) (seq? (nth form 2))))
      (let [raw-body (nth form 2)
            body (if (:meme-lang/bare-percent (meta form))
                   (forms/restore-bare-percent raw-body)
                   raw-body)]
        (if (and (= mode :clj) (seq? body))
          ;; :clj mode: unwrap body list to avoid double parens.
          ;; (fn [%1] (+ %1 1)) → #(+ %1 1), not #((+ %1 1))
          (collection-doc "#(" ")" (seq body) ctx)
          (collection-doc "#(" ")" [body] ctx)))

      ;; Sequences — check sugar then call
      (seq? form)
      (let [head (first form)]
        (cond
          ;; @deref sugar
          (and (= head 'clojure.core/deref) (:meme-lang/sugar (meta form)))
          (render/doc-cat doc-at (to-doc-inner (second form) ctx))

          ;; 'quote sugar
          (and (= head 'quote) (:meme-lang/sugar (meta form)))
          (render/doc-cat doc-quote (to-doc-inner (second form) ctx))

          ;; #'var sugar
          (and (= head 'var) (:meme-lang/sugar (meta form)))
          (render/doc-cat doc-var-quote (to-doc-inner (second form) ctx))

          ;; Regular call — bounded realization for safety against infinite seqs
          :else
          (let [[args truncated?] (bounded-vec (rest form))
                args (if truncated?
                       (conj args (symbol "..."))
                       args)]
            (call-doc head args ctx))))

      ;; Syntax-quote / unquote / unquote-splicing AST nodes
      ;; Must be before map? (defrecords satisfy map?)
      (forms/syntax-quote? form)
      (render/doc-cat doc-backtick (to-doc-inner (:form form) ctx))

      (forms/unquote? form)
      (let [inner (:form form)
            ;; Suppress @deref sugar inside ~ to prevent ~@J ambiguity
            inner (if (and (seq? inner)
                           (= 'clojure.core/deref (first inner))
                           (:meme-lang/sugar (meta inner)))
                    (with-meta inner (dissoc (meta inner) :meme-lang/sugar))
                    inner)]
        (render/doc-cat doc-unquote (to-doc-inner inner ctx)))

      (forms/unquote-splicing? form)
      (render/doc-cat doc-unquote-splicing (to-doc-inner (:form form) ctx))

      ;; Reader conditional — must be before map?
      (forms/meme-reader-conditional? form)
      (let [prefix (if (forms/rc-splicing? form) "#?@(" "#?(")
            branches (forms/rc-form form)]
        ;; RT6-F19: guard odd-count — partition 2 silently drops the last element
        (when (odd? (count branches))
          (throw (ex-info "Reader conditional has odd number of forms (missing value for last platform key)"
                          {:form form})))
        (pairs-doc prefix ")" (vec (partition 2 branches)) ctx))

      ;; Vector
      (vector? form)
      (collection-doc "[" "]" (vec form) ctx true)

      ;; Map — reconstruct #:ns{} or #::alias{} when :meme-lang/namespace-prefix metadata present
      (map? form)
      (if-let [ns-str (:meme-lang/namespace-prefix (meta form))]
        (let [;; ns-str is "foo" for #:foo{}, "::foo" for #::foo{}
              actual-ns (if (str/starts-with? ns-str "::") (subs ns-str 2) ns-str)
              prefix (if (str/starts-with? ns-str "::")
                       (str "#" ns-str "{")    ;; "::foo" → "#::foo{"
                       (str "#:" ns-str "{"))
              strip-ns (fn [k]
                         (if (and (keyword? k)
                                  (= (namespace k) actual-ns))
                           (keyword (name k))
                           k))]
          (pairs-doc prefix "}" (mapv (fn [[k v]] [(strip-ns k) v]) form) ctx))
        (pairs-doc "{" "}" (vec form) ctx))

      ;; Set — use :meme-lang/insertion-order for insertion-order output, validated against actual contents
      (set? form)
      (let [order (:meme-lang/insertion-order (meta form))
            ;; Use :meme-lang/insertion-order when it matches set size (not stale), otherwise fall back
            elements (if (and order (= (count order) (count form)))
                       order
                       (vec form))]
        (collection-doc "#{" "}" elements ctx true))

      ;; Symbol
      (symbol? form) (render/text (str form))

      ;; Keyword
      (keyword? form)
      (render/text (if (namespace form)
                     (str ":" (namespace form) "/" (name form))
                     (str ":" (name form))))

      ;; String, regex, char, number, tagged literal — shared with rewrite emitter.
      ;; Tagged literals need Doc-tree recursion, so handle separately.
      #?@(:clj [(tagged-literal? form)
                (let [^clojure.lang.TaggedLiteral tl form]
                  (render/doc-cat (render/text (str "#" (.-tag tl) " ")) (to-doc-inner (.-form tl) ctx)))])

      :else
      (if-let [s (values/emit-value-str form pr-str)]
        (render/text s)
        (render/text (pr-str form))))))

(defn- to-doc-inner
  "Internal recursive entry point: form + ctx → Doc with comment attachment."
  [form ctx]
  (let [doc (to-doc-form form ctx)
        comments (form-comments form)]
    (if comments
      (render/doc-cat (comment-doc comments) doc)
      doc)))

(defn to-doc
  "Convert a Clojure form to a Doc tree, with comment attachment.
   Comments are always emitted — the hardline in comment-doc forces the
   enclosing group to break, so comments are never silently dropped.

   mode        :meme (default) or :clj.
   style       layout policy map (nil = pass-through, no opinions).
               Keyed by semantic slot names, not form names.
   form-shape  registry map (head-symbol → decomposer fn).  When nil,
               no form is decomposed — every call renders as a plain
               body sequence.  The lang owns this registry; formatters
               pass it in.

   Formatters own style — see canon and flat formatter modules."
  ([form] (to-doc form :meme))
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
