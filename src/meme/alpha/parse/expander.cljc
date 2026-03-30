(ns meme.alpha.parse.expander
  "Syntax-quote expansion: MemeSyntaxQuote AST nodes → plain Clojure forms.
   Called by runtime paths (run, repl) before eval. Not needed for tooling
   (tooling works with AST nodes directly)."
  (:require [clojure.string :as str]
            [meme.alpha.errors :as errors]
            [meme.alpha.forms :as forms]))

;; ---------------------------------------------------------------------------
;; Symbol resolution for syntax-quote
;; ---------------------------------------------------------------------------

(defn- sq-resolve-symbol
  "Resolve a symbol for syntax-quote. Uses the resolver from opts
   if available, otherwise returns the symbol as-is (best effort)."
  [sym opts]
  (if-let [resolver (:resolve-symbol opts)]
    (resolver sym)
    ;; Without a resolver, qualify symbols that have no namespace
    ;; but look like they could be vars. Leave special forms alone.
    (if (and (nil? (namespace sym))
             (not (#{'if 'do 'let 'fn 'var 'quote 'loop 'recur 'throw 'try 'catch
                     'finally 'def 'new 'set! 'monitor-enter 'monitor-exit
                     'letfn 'case 'deftype 'reify} sym)))
      sym ; can't resolve without ns context — leave as-is
      sym)))

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

(defn expand-sq
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
    (errors/meme-error "Unquote-splicing (~@) not in collection" loc)

    ;; Symbol — resolve and quote
    (symbol? form)
    (list 'quote (sq-gensym (sq-resolve-symbol form opts)))

    ;; List — expand to (seq (concat ...))
    (seq? form)
    (if (empty? form)
      (list 'clojure.core/list)
      (let [items (map (fn [item]
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
    (let [items (map (fn [item]
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

    ;; Nested syntax-quote — expand inner backtick with fresh gensym-env
    ;; Must be before map? because defrecords satisfy (map? x)
    (forms/syntax-quote? form)
    (binding [*gensym-env* (volatile! {})]
      (expand-sq (:form form) opts loc))

    ;; Map — expand to (apply hash-map (concat ...))
    (map? form)
    (let [items (mapcat (fn [[k v]]
                          [(list 'clojure.core/list (expand-sq k opts loc))
                           (list 'clojure.core/list (expand-sq v opts loc))])
                        form)]
      (list 'clojure.core/apply 'clojure.core/hash-map (cons 'clojure.core/concat items)))

    ;; Set — expand to (apply hash-set (concat ...))
    (set? form)
    (let [items (map (fn [item]
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

     (forms/syntax-quote? form)
     (binding [*gensym-env* (volatile! {})]
       (expand-sq (:form form) opts nil))

     (forms/unquote? form)
     (forms/->MemeUnquote (expand-syntax-quotes (:form form) opts))

     (forms/unquote-splicing? form)
     (forms/->MemeUnquoteSplicing (expand-syntax-quotes (:form form) opts))

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
