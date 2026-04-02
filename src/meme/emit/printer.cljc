(ns meme.emit.printer
  "Meme printer: Clojure forms → Doc trees.
   Builds Wadler-Lindig Doc trees from Clojure forms, handling meme syntax
   (call notation, sugar, metadata, comments) and Clojure output mode.
   Delegates to render for Doc algebra and layout."
  (:require [clojure.string :as str]
            [meme.emit.render :as render]
            [meme.emit.values :as values]
            [meme.forms :as forms]))

;; ---------------------------------------------------------------------------
;; Comment extraction from :ws metadata
;; ---------------------------------------------------------------------------

(defn extract-comments
  "Extract comment lines from a :ws metadata string.
   Returns a vector of trimmed comment strings, or nil."
  [ws]
  (when ws
    (let [lines (str/split-lines ws)]
      (not-empty (mapv str/triml (filterv #(re-find #"^\s*;" %) lines))))))

(defn join-with-trailing-comments
  "Join formatted form strings with blank lines. Appends trailing comments
   from :trailing-ws metadata on the forms sequence."
  [format-fn forms]
  (let [trailing-ws (:trailing-ws (meta forms))
        trailing-comments (when trailing-ws (extract-comments trailing-ws))
        body (str/join "\n\n" (map format-fn forms))]
    (if trailing-comments
      (str body "\n\n" (str/join "\n" trailing-comments))
      body)))

(defn- form-comments
  "Get comment lines from a form's :ws metadata, or nil."
  [form]
  (when (and (some? form)
             #?(:clj  (instance? clojure.lang.IMeta form)
                :cljs (satisfies? IMeta form))
             (meta form))
    (extract-comments (:ws (meta form)))))

(defn- comment-doc
  "Build a Doc that emits comment lines followed by a hardline.
   Each comment line is on its own line."
  [comments]
  (reduce (fn [acc c]
            (render/cat acc (render/text c) render/hardline))
          nil
          comments))

;; ---------------------------------------------------------------------------
;; Notation helpers
;; ---------------------------------------------------------------------------

(def ^:private head-line-args
  "How many args to keep on the first line with the head.
   nil means no special treatment (default: all args in body)."
  {'def 1, 'def- 1,
   'defn 1, 'defn- 1, 'defmacro 1, 'defmulti 1, 'defmethod 2,
   'defprotocol 1, 'defrecord 1, 'deftype 1,
   'fn 0,
   'let 0, 'letfn 0, 'loop 0, 'binding 0, 'doseq 0, 'for 0,
   'if 1, 'if-not 1, 'if-let 0, 'if-some 0,
   'when 1, 'when-not 1, 'when-let 0, 'when-some 0, 'when-first 0,
   'cond 0, 'condp 2, 'case 1, 'cond-> 1, 'cond->> 1,
   'try 0, 'catch 2, 'finally 0,
   'do 0,
   'ns 1,
   '-> 1, '->> 1, 'some-> 1, 'some->> 1, 'as-> 2,
   'deftest 1, 'testing 1, 'is 0, 'are 0})

(defn- anon-fn-shorthand?
  "Can (fn [params] body) be printed as #(body)?
   Only when :meme/sugar tagged by reader."
  [form]
  (and (:meme/sugar (meta form))
       (seq? form)
       (= 'fn (first form))
       (= 3 (count form))
       (vector? (second form))))

(defn- restore-bare-percent
  "Replace %1 with % in a form tree. Used when :meme/bare-percent metadata
   indicates the user wrote bare % (normalized to %1 for eval correctness).
   M15/B2: recurses into maps, sets, and AST nodes, mirroring normalize-bare-percent.
   D53: skip nested (fn ...) bodies — matches normalize-bare-percent guard."
  [form]
  (cond
    (and (symbol? form) (= (name form) "%1") (nil? (namespace form))) (symbol "%")
    ;; D53: skip nested fn bodies — inner %1 belongs to the inner fn, not the outer #()
    (and (seq? form) (= 'fn (first form))) form
    (seq? form) (with-meta (apply list (map restore-bare-percent form)) (meta form))
    (vector? form) (with-meta (mapv restore-bare-percent form) (meta form))
    ;; AST node defrecords satisfy (map? x) — check before map?
    (forms/raw? form) form
    (forms/syntax-quote? form) (forms/->MemeSyntaxQuote (restore-bare-percent (:form form)))
    (forms/unquote? form) (forms/->MemeUnquote (restore-bare-percent (:form form)))
    (forms/unquote-splicing? form) (forms/->MemeUnquoteSplicing (restore-bare-percent (:form form)))
    (map? form) (with-meta
                  (into {} (map (fn [[k v]] [(restore-bare-percent k) (restore-bare-percent v)]) form))
                  (meta form))
    (set? form) (with-meta (set (map restore-bare-percent form)) (meta form))
    :else form))

;; ---------------------------------------------------------------------------
;; Doc constants — avoid per-form allocation of common Doc nodes (P6)
;; ---------------------------------------------------------------------------

(def ^:private doc-open-paren  (render/text "("))
(def ^:private doc-close-paren (render/text ")"))
(def ^:private doc-open-bracket  (render/text "["))
(def ^:private doc-close-bracket (render/text "]"))
(def ^:private doc-open-brace  (render/text "{"))
(def ^:private doc-close-brace (render/text "}"))
(def ^:private doc-open-set    (render/text "#{"))
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
        next-item (first (drop limit s))
        truncated? (some? next-item)]
    [items truncated?]))

;; ---------------------------------------------------------------------------
;; Doc helpers for building common structures
;; ---------------------------------------------------------------------------

(defn- intersperse
  "Interleave docs with separator, returning concatenated Doc."
  [sep docs]
  (when (seq docs)
    (reduce (fn [acc d] (render/cat acc sep d)) (first docs) (rest docs))))

;; ---------------------------------------------------------------------------
;; Form → Doc — single source of truth for notation AND formatting
;; ---------------------------------------------------------------------------

(declare to-doc to-doc-form)

(defn- emit-meta-prefix-doc
  "Compute metadata prefix as a Doc node: ^:key, ^Type, or ^{map}.
   L12: returns Doc (not string) so metadata maps participate in width-aware layout."
  [m mode]
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
    (render/cat doc-caret (to-doc-form m mode))))

(defn- call-doc
  "Build Doc for a call form. Handles head-line-args and meme/clj modes."
  [head args mode]
  (let [head-doc (to-doc head mode)
        arg-docs (mapv #(to-doc % mode) args)]
    (if (= mode :clj)
      ;; Clojure mode: (head arg1 arg2)
      (if (empty? arg-docs)
        (render/group (render/cat doc-open-paren head-doc doc-close-paren))
        (render/group
         (render/cat
          doc-open-paren head-doc
          (render/nest 2 (render/cat render/line (intersperse render/line arg-docs)))
          doc-close-paren)))
      ;; Meme mode: head(arg1 arg2) with head-line-args
      (let [n-head (get head-line-args head)]
          (cond
            ;; Zero args: head()
            (empty? arg-docs)
            (render/group (render/cat head-doc doc-open-paren doc-close-paren))

            ;; Head-line args: keep n args on head line, rest in body
            (and n-head (pos? n-head) (> (count arg-docs) n-head))
            (let [hl (subvec arg-docs 0 n-head)
                  body (subvec arg-docs n-head)]
              (render/group
               (render/cat
                head-doc doc-open-paren
                (render/nest 2
                             (render/cat
                              (render/group (render/cat render/line0 (intersperse render/line hl)))
                              (reduce (fn [acc d] (render/cat acc render/line d)) nil body)))
                doc-close-paren)))

            ;; Default: all args in body
            :else
            (render/group
             (render/cat
              head-doc doc-open-paren
              (render/nest 2 (render/cat render/line0 (intersperse render/line arg-docs)))
              doc-close-paren)))))))

(defn- collection-doc
  "Build Doc for a delimited collection: [elems], #{elems}, #(body)."
  [open close children mode]
  (if (empty? children)
    (render/text (str open close))
    (let [child-docs (mapv #(to-doc % mode) children)]
      (render/group
       (render/cat
        (render/text open)
        (render/nest 2 (render/cat render/line0 (intersperse render/line child-docs)))
        render/line0
        (render/text close))))))

(defn- pairs-doc
  "Build Doc for key-value pairs: {k v ...}, #:ns{k v ...}, #?(k v ...)."
  [open close entries mode]
  (if (empty? entries)
    (render/text (str open close))
    (let [pair-docs (mapv (fn [[k v]]
                            (render/cat (to-doc k mode) doc-space (to-doc v mode)))
                          entries)]
      (render/group
       (render/cat
        (render/text open)
        (render/nest 2 (render/cat render/line0 (intersperse render/line pair-docs)))
        render/line0
        (render/text close))))))

(defn- to-doc-form
  "Convert a Clojure form to a Doc tree. Handles metadata wrapping.
   mode is :meme (default) or :clj."
  [form mode]
  (cond
    ;; Metadata prefix — checked first, before structural checks.
    (and (some? form)
         #?(:clj (instance? clojure.lang.IMeta form)
            :cljs (satisfies? IMeta form))
         (some? (meta form))
         (seq (forms/strip-internal-meta (meta form))))
    (let [chain (:meme/meta-chain (meta form))
          stripped (with-meta form (select-keys (meta form) forms/notation-meta-keys))
          prefix-docs (if chain
                        (mapv #(emit-meta-prefix-doc % mode) (reverse chain))
                        [(emit-meta-prefix-doc (forms/strip-internal-meta (meta form)) mode)])
          ;; L12: compose prefix Docs with spaces, then the form Doc
          prefix-doc (reduce (fn [acc d] (render/cat acc doc-space d))
                             (first prefix-docs)
                             (rest prefix-docs))]
      (render/cat prefix-doc doc-space (to-doc-form stripped mode)))

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
    ;; F7: when :meme/bare-percent, restore % from %1 in body before printing.
    (and (anon-fn-shorthand? form)
         (or (not= mode :clj) (seq? (nth form 2))))
    (let [raw-body (nth form 2)
          body (if (:meme/bare-percent (meta form))
                 (restore-bare-percent raw-body)
                 raw-body)]
      (if (and (= mode :clj) (seq? body))
        ;; :clj mode: unwrap body list to avoid double parens.
        ;; (fn [%1] (+ %1 1)) → #(+ %1 1), not #((+ %1 1))
        (collection-doc "#(" ")" (seq body) mode)
        (collection-doc "#(" ")" [body] mode)))

    ;; Sequences — check sugar then call
    (seq? form)
    (let [head (first form)]
      (cond
        ;; @deref sugar
        (and (= head 'clojure.core/deref) (:meme/sugar (meta form)))
        (render/cat doc-at (to-doc (second form) mode))

        ;; 'quote sugar
        (and (= head 'quote) (:meme/sugar (meta form)))
        (render/cat doc-quote (to-doc (second form) mode))

        ;; #'var sugar
        (and (= head 'var) (:meme/sugar (meta form)))
        (render/cat doc-var-quote (to-doc (second form) mode))

        ;; Regular call — bounded realization for safety against infinite seqs
        :else
        (let [[args truncated?] (bounded-vec (rest form))
              args (if truncated?
                     (conj args (symbol "..."))
                     args)]
          (call-doc head args mode))))

    ;; Syntax-quote / unquote / unquote-splicing AST nodes
    ;; Must be before map? (defrecords satisfy map?)
    (forms/syntax-quote? form)
    (render/cat doc-backtick (to-doc (:form form) mode))

    (forms/unquote? form)
    (render/cat doc-unquote (to-doc (:form form) mode))

    (forms/unquote-splicing? form)
    (render/cat doc-unquote-splicing (to-doc (:form form) mode))

    ;; Reader conditional — must be before map?
    (forms/meme-reader-conditional? form)
    (let [prefix (if (forms/rc-splicing? form) "#?@(" "#?(")]
      (pairs-doc prefix ")" (vec (partition 2 (forms/rc-form form))) mode))

    ;; Vector
    (vector? form)
    (collection-doc "[" "]" (vec form) mode)

    ;; Map — reconstruct #:ns{} when :meme/ns metadata present
    (map? form)
    (if-let [ns-str (:meme/ns (meta form))]
      (let [strip-ns (fn [k]
                       (if (and (keyword? k)
                                (= (namespace k)
                                   (if (str/starts-with? ns-str ":") (subs ns-str 1) ns-str)))
                         (keyword (name k))
                         k))]
        (pairs-doc (str "#:" ns-str "{") "}" (mapv (fn [[k v]] [(strip-ns k) v]) form) mode))
      (pairs-doc "{" "}" (vec form) mode))

    ;; Set — use :meme/order for insertion-order output
    (set? form)
    (let [elements (or (:meme/order (meta form)) (seq form))]
      (collection-doc "#{" "}" (vec (or elements [])) mode))

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
                (render/cat (render/text (str "#" (.-tag tl) " ")) (to-doc (.-form tl) mode)))])

    :else
    (if-let [s (values/emit-value-str form pr-str)]
      (render/text s)
      (render/text (pr-str form)))))

(defn to-doc
  "Convert a Clojure form to a Doc tree, with comment attachment.
   Comments are always emitted — the hardline in comment-doc forces the
   enclosing group to break, so comments are never silently dropped.
   mode is :meme (default) or :clj."
  ([form] (to-doc form :meme))
  ([form mode]
   (let [doc (to-doc-form form mode)
         comments (form-comments form)]
     (if comments
       (render/cat (comment-doc comments) doc)
       doc))))
