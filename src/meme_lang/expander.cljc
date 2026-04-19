(ns meme-lang.expander
  "Syntax-quote expansion: MemeSyntaxQuote AST nodes → plain Clojure forms.
   Called by runtime paths (run, repl) before eval. Not needed for tooling
   (tooling works with AST nodes directly)."
  (:require [clojure.string :as str]
            [meme.tools.clj.errors :as errors]
            [meme-lang.forms :as forms]))

(declare expand-syntax-quotes)

;; ---------------------------------------------------------------------------
;; Symbol resolution for syntax-quote
;; ---------------------------------------------------------------------------

(defn- sq-resolve-symbol
  "Resolve a symbol for syntax-quote. Uses the resolver from opts
   if available, otherwise returns the symbol as-is.
   Note: without a resolver, symbols are not namespace-qualified — this
   differs from Clojure where `foo resolves to current-ns/foo."
  [sym opts]
  (if-let [resolver (:resolve-symbol opts)]
    (resolver sym)
    sym))

(def ^:private ^:dynamic *gensym-env* nil)

(defn- sq-gensym
  "Auto-gensym: foo# → foo__NNN__auto__. Same foo# in one syntax-quote
   resolves to the same gensym. Namespace-qualified symbols (ns/x#) are
   preserved literally — Clojure only auto-gensyms unqualified symbols."
  [sym]
  (let [n (name sym)]
    (if (and (str/ends-with? n "#")
             (nil? (namespace sym)))
      (let [base (subs n 0 (dec (count n)))]
        (if-let [existing (get @*gensym-env* sym)]
          existing
          (let [gs (symbol (str base "__" (gensym) "__auto__"))]
            (vswap! *gensym-env* assoc sym gs)
            gs)))
      sym)))

;; ---------------------------------------------------------------------------
;; expand-sq — core syntax-quote expansion
;; ---------------------------------------------------------------------------

(declare ^:private expand-sq)

(defn- expand-sq-meta
  "If a form has user-visible metadata, expand it into a map literal suitable
   for (with-meta expansion meta-map). Mirrors Clojure's SyntaxQuoteReader
   which emits (with-meta ...) for metadata-annotated collections."
  [form opts loc]
  (when-let [m (not-empty (forms/strip-internal-meta (meta form)))]
    (let [items (into [] (mapcat (fn [[k v]]
                                   [(list 'clojure.core/list (expand-sq k opts loc))
                                    (list 'clojure.core/list (expand-sq v opts loc))]))
                      m)]
      (list 'clojure.core/apply 'clojure.core/hash-map (cons 'clojure.core/concat items)))))

(defn- maybe-with-meta
  "Wrap expansion in (with-meta expansion meta-expansion) when user metadata is present."
  [expansion form opts loc]
  (if-let [meta-expr (expand-sq-meta form opts loc)]
    (list 'clojure.core/with-meta expansion meta-expr)
    expansion))

(defn- expand-sq
  "Walk a form parsed inside syntax-quote and produce the expansion.
   Mirrors Clojure's SyntaxQuoteReader behavior.
   Used by expand-syntax-quotes to expand MemeSyntaxQuote AST nodes at eval time."
  [form opts loc]
  (cond
    ;; Unquote: ~x → x (the value, not quoted)
    ;; RT6-F2: process inner form through expand-syntax-quotes so that
    ;; MemeRaw values (e.g. 0xFF → {:value 255 :raw "0xFF"}) are unwrapped
    ;; and nested MemeSyntaxQuote nodes (e.g. `~`x) are properly expanded.
    ;; Nested `~~x` (two unquotes that would overshoot the enclosing ``` ` ```
    ;; levels) is validated at the outer pipeline stage by checking for
    ;; leftover MemeUnquote / MemeUnquoteSplicing records in the final forms.
    (forms/unquote? form)
    (expand-syntax-quotes (:form form) (assoc opts :inside-sq true))

    ;; NOTE: ~@ rejection moved to bottom of cond — maps handle ~@ values via splice.

    ;; Symbol — gensym first, then resolve, then quote (matches Clojure order).
    ;; Gensym: foo# → foo__NNN__auto__ (only unqualified).
    ;; Resolve: sym → ns-qualified sym (via :resolve-symbol opt).
    ;; Auto-gensymed symbols skip resolution (Clojure's SyntaxQuoteReader
    ;; explicitly excludes them from namespace qualification).
    (symbol? form)
    (let [gs (sq-gensym form)
          resolved (if (str/ends-with? (name gs) "__auto__")
                     gs
                     (sq-resolve-symbol gs opts))
          quoted (list 'quote resolved)]
      (maybe-with-meta quoted form opts loc))

    ;; List — expand to (seq (concat ...))
    ;; mapv (eager) is critical: lazy map would defer gensym resolution
    ;; past the binding scope of nested syntax-quotes, causing inner x#
    ;; to resolve with the wrong *gensym-env*.
    (seq? form)
    (if (empty? form)
      (list 'clojure.core/list)
      (let [items (mapv (fn [item]
                          (cond
                            (forms/unquote-splicing? item)
                            (:form item)
                            (forms/unquote? item)
                            (list 'clojure.core/list (:form item))
                            :else
                            (list 'clojure.core/list (expand-sq item opts loc))))
                        form)
              expansion (list 'clojure.core/seq (cons 'clojure.core/concat items))]
          (maybe-with-meta expansion form opts loc)))

    ;; Vector — expand to (vec (concat ...))
    ;; RT3-F3: wrap in with-meta when user metadata is present
    (vector? form)
    (let [items (mapv (fn [item]
                        (cond
                          (forms/unquote-splicing? item)
                          (:form item)
                          (forms/unquote? item)
                          (list 'clojure.core/list (:form item))
                          :else
                          (list 'clojure.core/list (expand-sq item opts loc))))
                      form)
          expansion (list 'clojure.core/apply 'clojure.core/vector
                         (list 'clojure.core/seq (cons 'clojure.core/concat items)))]
      (maybe-with-meta expansion form opts loc))

    ;; MemeRaw — unwrap to plain value for expansion
    ;; Must be before map? because defrecords satisfy (map? x)
    (forms/raw? form) (:value form)

    ;; MemeAutoKeyword — pass through unchanged in syntax-quote context.
    ;; Must be before map? because defrecords satisfy (map? x).
    ;; Expansion to (read-string ...) happens in step-expand-syntax-quotes.
    (forms/deferred-auto-keyword? form)
    (forms/deferred-auto-keyword->form form)

    ;; Nested syntax-quote — expand inner, then quote the expansion.
    ;; Mirrors Clojure: ``x produces code that generates `x's expansion.
    ;; First expand the inner form with a fresh gensym-env, then treat
    ;; the result as a regular form to be quoted by the current level.
    ;; Must be before map? because defrecords satisfy (map? x)
    (forms/syntax-quote? form)
    (let [inner (binding [*gensym-env* (volatile! {})]
                  (expand-sq (:form form) opts loc))]
      (expand-sq inner opts loc))

    ;; MemeReaderConditional — pass through in syntax-quote context.
    ;; Must be before map? since CLJS defrecords satisfy map?.
    (forms/meme-reader-conditional? form)
    form

    ;; UnquoteSplicing outside a collection — error.
    ;; Must be before map? since defrecords (like MemeUnquoteSplicing) satisfy map?.
    (forms/unquote-splicing? form)
    (errors/meme-error "Unquote-splicing (~@) must be inside a collection in syntax-quote"
                       (let [form-loc (select-keys (meta form) [:line :col])]
                         (if (seq form-loc) form-loc loc)))

    ;; Map — expand to (apply hash-map (concat ...))
    ;; RT3-F3: wrap in with-meta when user metadata is present
    ;; Handles ~@ in value position: `{:a ~@xs} → (apply hash-map (concat (list :a) xs))
    (map? form)
    (let [items (into [] (mapcat (fn [[k v]]
                                   (cond
                                     ;; Both key and value are ~@ spliced
                                     (and (forms/unquote-splicing? k)
                                          (forms/unquote-splicing? v))
                                     [(:form k) (:form v)]
                                     (forms/unquote-splicing? v)
                                     [(list 'clojure.core/list (expand-sq k opts loc))
                                      (:form v)]
                                     (forms/unquote-splicing? k)
                                     [(:form k)
                                      (list 'clojure.core/list (expand-sq v opts loc))]
                                     :else
                                     [(list 'clojure.core/list (expand-sq k opts loc))
                                      (list 'clojure.core/list (expand-sq v opts loc))])))
                      form)
          expansion (list 'clojure.core/apply 'clojure.core/hash-map
                         (list 'clojure.core/seq (cons 'clojure.core/concat items)))]
      (maybe-with-meta expansion form opts loc))

    ;; Set — expand to (apply hash-set (concat ...))
    ;; RT3-F3: wrap in with-meta when user metadata is present
    ;; RT7: allow ~@ in sets (Clojure allows `#{~@xs})
    (set? form)
    (let [items (mapv (fn [item]
                        (cond
                          (forms/unquote-splicing? item)
                          (:form item)
                          :else
                          (list 'clojure.core/list (expand-sq item opts loc))))
                      form)
          expansion (list 'clojure.core/apply 'clojure.core/hash-set
                         (list 'clojure.core/seq (cons 'clojure.core/concat items)))]
      (maybe-with-meta expansion form opts loc))

    ;; nil, boolean, regex — explicit quote (matches Clojure's SyntaxQuoteReader)
    (or (nil? form) (true? form) (false? form)
        #?(:clj (instance? java.util.regex.Pattern form)))
    (list 'quote form)

    ;; Keyword, number, string, char — self-quoting
    :else form))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn expand-syntax-quotes
  "Walk a form tree and expand all AST nodes into plain Clojure forms.
   Expands MemeSyntaxQuote into seq/concat/list, unwraps MemeRaw to plain values.
   Called by runtime paths (run, repl) before eval."
  ([form] (expand-syntax-quotes form nil))
  ([form opts]
   (cond
     (forms/raw? form)
     (:value form)

     ;; MemeAutoKeyword — expand to (read-string "::foo") when :expand-auto-keywords
     ;; is set in opts (eval path), otherwise pass through (text-to-text path).
     ;; Must be before map? because defrecords satisfy (map? x).
     (forms/deferred-auto-keyword? form)
     (if (:expand-auto-keywords opts)
       (forms/deferred-auto-keyword->form form)
       form)

     (forms/syntax-quote? form)
     (let [loc (select-keys (meta form) [:line :col])
           expanded (binding [*gensym-env* (volatile! {})]
                      (expand-sq (:form form) opts loc))
           user-meta (not-empty (forms/strip-internal-meta (meta form)))]
       (if user-meta
         (with-meta expanded user-meta)
         expanded))

     (forms/unquote? form)
     (if (:inside-sq opts)
       (forms/->MemeUnquote (expand-syntax-quotes (:form form) opts))
       (errors/meme-error "Unquote (~) not in syntax-quote" {}))

     (forms/unquote-splicing? form)
     (if (:inside-sq opts)
       (forms/->MemeUnquoteSplicing (expand-syntax-quotes (:form form) opts))
       (errors/meme-error "Unquote-splicing (~@) not in syntax-quote" {}))

     ;; MemeReaderConditional (CLJS defrecord) — recurse into :form while
     ;; preserving the record type. Must be before map? since defrecords satisfy map?.
     (forms/meme-reader-conditional? form)
     (forms/make-reader-conditional
       (apply list (map #(expand-syntax-quotes % opts) (forms/rc-form form)))
       (forms/rc-splicing? form))

     (seq? form)
     (with-meta (apply list (map #(expand-syntax-quotes % opts) form)) (meta form))

     (vector? form)
     (with-meta (mapv #(expand-syntax-quotes % opts) form) (meta form))

     (map? form)
     (with-meta (into {} (map (fn [[k v]] [(expand-syntax-quotes k opts)
                                           (expand-syntax-quotes v opts)]) form))
       (meta form))

     (set? form)
     (with-meta (set (map #(expand-syntax-quotes % opts) form)) (meta form))

     :else form)))

(defn- check-no-leftover-unquotes!
  "Walk an expanded form and error if any MemeUnquote or MemeUnquoteSplicing
   records survived expansion. They indicate `~`/`~@` with no matching
   enclosing ``` ` ``` — e.g. bare `~~x` at top level. Runs after expansion so
   the valid `` ``~~x `` → x case (unquotes balanced by nested syntax-quotes)
   still works."
  [form]
  (cond
    (forms/unquote? form)
    (errors/meme-error
      "Unquote (~) has no matching enclosing syntax-quote"
      (select-keys (meta form) [:line :col]))
    (forms/unquote-splicing? form)
    (errors/meme-error
      "Unquote-splicing (~@) has no matching enclosing syntax-quote"
      (select-keys (meta form) [:line :col]))
    (seq? form)    (run! check-no-leftover-unquotes! form)
    (vector? form) (run! check-no-leftover-unquotes! form)
    (map? form)    (run! (fn [[k v]]
                           (check-no-leftover-unquotes! k)
                           (check-no-leftover-unquotes! v))
                         form)
    (set? form)    (run! check-no-leftover-unquotes! form)
    :else nil))

(defn expand-forms
  "Expand all syntax-quote nodes in a vector of forms. For eval pipelines."
  ([forms] (expand-forms forms nil))
  ([forms opts]
   (let [expanded (mapv #(expand-syntax-quotes % opts) forms)]
     (run! check-no-leftover-unquotes! expanded)
     expanded)))
