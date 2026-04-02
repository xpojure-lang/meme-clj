(ns meme.parse.expander
  "Syntax-quote expansion: MemeSyntaxQuote AST nodes → plain Clojure forms.
   Called by runtime paths (run, repl) before eval. Not needed for tooling
   (tooling works with AST nodes directly)."
  (:require [clojure.string :as str]
            [meme.errors :as errors]
            [meme.forms :as forms]))

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
   resolves to the same gensym."
  [sym]
  (let [n (name sym)]
    (if (str/ends-with? n "#")
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

(defn- expand-sq
  "Walk a form parsed inside syntax-quote and produce the expansion.
   Mirrors Clojure's SyntaxQuoteReader behavior.
   Used by expand-syntax-quotes to expand MemeSyntaxQuote AST nodes at eval time."
  [form opts loc]
  (cond
    ;; Unquote: ~x → x (the value, not quoted)
    (forms/unquote? form)
    (:form form)

    ;; UnquoteSplicing at top level is an error (must be inside a collection)
    (forms/unquote-splicing? form)
    (errors/meme-error "Unquote-splicing (~@) not in collection"
                       (let [form-loc (select-keys (meta form) [:line :col])]
                         (if (seq form-loc) form-loc loc)))

    ;; Symbol — resolve and quote
    (symbol? form)
    (list 'quote (sq-gensym (sq-resolve-symbol form opts)))

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
                        form)]
        (list 'clojure.core/seq (cons 'clojure.core/concat items))))

    ;; Vector — expand to (vec (concat ...))
    (vector? form)
    (let [items (mapv (fn [item]
                        (cond
                          (forms/unquote-splicing? item)
                          (:form item)
                          (forms/unquote? item)
                          (list 'clojure.core/list (:form item))
                          :else
                          (list 'clojure.core/list (expand-sq item opts loc))))
                      form)]
      (list 'clojure.core/apply 'clojure.core/vector (cons 'clojure.core/concat items)))

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

    ;; Map — expand to (apply hash-map (concat ...))
    (map? form)
    (let [items (into [] (mapcat (fn [[k v]]
                                   [(list 'clojure.core/list (expand-sq k opts loc))
                                    (list 'clojure.core/list (expand-sq v opts loc))]))
                      form)]
      (list 'clojure.core/apply 'clojure.core/hash-map (cons 'clojure.core/concat items)))

    ;; Set — expand to (apply hash-set (concat ...))
    (set? form)
    (let [items (mapv (fn [item]
                        (cond
                          (forms/unquote-splicing? item)
                          (:form item)
                          :else
                          (list 'clojure.core/list (expand-sq item opts loc))))
                      form)]
      (list 'clojure.core/apply 'clojure.core/hash-set (cons 'clojure.core/concat items)))

    ;; Keyword, number, string, char, nil, boolean — self-quoting
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
     (binding [*gensym-env* (volatile! {})]
       (expand-sq (:form form) opts (select-keys (meta form) [:line :col])))

     (forms/unquote? form)
     (forms/->MemeUnquote (expand-syntax-quotes (:form form) opts))

     (forms/unquote-splicing? form)
     (forms/->MemeUnquoteSplicing (expand-syntax-quotes (:form form) opts))

     ;; MemeReaderConditional (CLJS defrecord) — recurse into :form while
     ;; preserving the record type. Must be before map? since defrecords satisfy map?.
     (forms/meme-reader-conditional? form)
     (forms/make-reader-conditional
       (mapv #(expand-syntax-quotes % opts) (forms/rc-form form))
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

(defn expand-forms
  "Expand all syntax-quote nodes in a vector of forms. For eval pipelines."
  ([forms] (expand-forms forms nil))
  ([forms opts]
   (mapv #(expand-syntax-quotes % opts) forms)))
