(ns meme.tools.clj.ast.lower
  "Lower an AST to plain Clojure forms.

   Produces eval-ready forms — no notation metadata. Sugar, comments,
   set source order, and namespaced-map prefixes live only in the AST
   tier; tooling that needs them should consume the AST directly via
   `m1clj-lang.api/m1clj->ast`. The form path is intentionally lossy.

   Resolution that cst-reader did at read time happens here:
   - `::keyword` resolution via `:resolve-keyword` opt
   - tagged literal resolution via host data-readers
   - anon-fn percent-param normalization
   - namespaced-map key qualification
   - duplicate-key / odd-count map / nested anon-fn validation"
  (:require [clojure.string :as str]
            [meme.tools.clj.ast.nodes :as nodes
             #?@(:cljs [:refer [CljSymbol CljAnonFn CljDiscard]])]
            [meme.tools.clj.errors :as errors]
            [meme.tools.clj.forms :as forms]
            [meme.tools.clj.resolve :as resolve])
  #?(:clj (:import [meme.tools.clj.ast.nodes
                    CljSymbol CljAnonFn CljDiscard])))

(defprotocol Lowerable
  (-lower [node opts] "Lower this AST node to a Clojure form."))

(defn ast->form
  "Lower a single AST node to a Clojure form."
  ([node] (ast->form node nil))
  ([node opts] (-lower node opts)))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- metadatable? [v]
  #?(:clj (instance? clojure.lang.IObj v)
     :cljs (satisfies? IWithMeta v)))

(defn- canonical-key [k]
  (if (forms/raw? k) (:value k) k))

(defn- first-duplicate [coll]
  (let [freqs (frequencies (map canonical-key coll))]
    (first (filter #(> (get freqs (canonical-key %)) 1) coll))))

(defn- discard-node? [n]
  (instance? CljDiscard n))

(defn- lower-children
  "Lower a vec of AST children to forms, filtering CljDiscard."
  [children opts]
  (mapv #(ast->form % opts) (remove discard-node? children)))

;; ---------------------------------------------------------------------------
;; Per-record lowering bodies — kept as plain defns so the dispatch stays
;; small. extend-protocol below wires them in.
;; ---------------------------------------------------------------------------

(defn- lower-symbol [n]
  (if (:ns n) (symbol (:ns n) (:name n)) (symbol (:name n))))

(defn- lower-keyword [n opts]
  (let [{:keys [name ns auto-resolve?]} n]
    (if auto-resolve?
      (let [raw (str "::" (when ns (str ns "/")) name)]
        (resolve/resolve-auto-keyword raw (:pos n) (:resolve-keyword opts)))
      (if ns (keyword ns name) (keyword name)))))

(defn- lower-number [n]
  (let [v (resolve/resolve-number (:raw n) (:pos n))]
    (if (forms/raw? v) v (:value n))))

(defn- lower-string [n] (:value n))

(defn- lower-char [n]
  (let [v (resolve/resolve-char (:raw n) (:pos n))]
    (if (forms/raw? v) v (:value n))))

(defn- lower-regex [n]
  (let [raw (str "#\"" (:pattern n) "\"")]
    (resolve/resolve-regex raw (:pos n))))

(defn- lower-nil [_] nil)

(defn- lower-bool [n] (:value n))

(defn- lower-list [n opts]
  (let [items (lower-children (:children n) opts)]
    (if (empty? items) (list) (apply list items))))

(defn- lower-vector [n opts]
  (vec (lower-children (:children n) opts)))

(defn- lower-map [n opts]
  (let [items (vec (mapcat (fn [pair] (lower-children pair opts)) (:pairs n)))]
    (when (odd? (count items))
      (errors/meme-error "Map must contain an even number of forms" (:pos n)))
    (let [ks (take-nth 2 items)]
      (when-let [dup (first-duplicate ks)]
        (errors/meme-error (str "Duplicate key: " (pr-str dup)) (:pos n))))
    (apply array-map items)))

(defn- lower-set [n opts]
  (let [items (lower-children (:children n) opts)]
    (when-let [dup (first-duplicate items)]
      (errors/meme-error (str "Duplicate key: " (pr-str dup)) (:pos n)))
    (set items)))

(defn- lower-quote [n opts]
  (list 'quote (ast->form (:form n) opts)))

(defn- lower-deref [n opts]
  (list 'clojure.core/deref (ast->form (:form n) opts)))

(defn- lower-var [n opts]
  (let [inner (ast->form (:form n) opts)]
    (when-not (symbol? inner)
      (errors/meme-error
        (str "#' (var-quote) requires a symbol — got " (pr-str inner))
        (:pos n)))
    (list 'var inner)))

(defn- lower-syntax-quote [n opts]
  (forms/->CljSyntaxQuote (ast->form (:form n) opts)))

(defn- lower-unquote [n opts]
  (with-meta (forms/->CljUnquote (ast->form (:form n) opts))
    (or (:pos n) {})))

(defn- lower-unquote-splicing [n opts]
  (with-meta (forms/->CljUnquoteSplicing (ast->form (:form n) opts))
    (or (:pos n) {})))

(defn- ast-symbol-type
  "If `node` is a CljSymbol naming a `%` placeholder, return its type
   (:bare, :rest, or the integer N). Otherwise nil."
  [node]
  (when (instance? CljSymbol node)
    (forms/percent-param-type
      (if (:ns node)
        (symbol (:ns node) (:name node))
        (symbol (:name node))))))

(defn- ast-invalid-percent
  "If `node` is a CljSymbol whose name looks like a `%` param but isn't
   a valid one (`%-1`, `%foo`, `%1a`, …), return the string. Otherwise nil."
  [node]
  (when (instance? CljSymbol node)
    (let [s (if (:ns node)
              (symbol (:ns node) (:name node))
              (symbol (:name node)))]
      (when (forms/invalid-percent-symbol? s) (:name node)))))

(defn- walk-anon-fn-body-ast
  "Recurse through the AST under an `#()` body, calling `visit` on every
   non-collection node. Stops at nested `CljAnonFn` boundaries — those
   percent params and bare `%`s belong to the inner `#()`, not us.

   `visit` is called for its side effects only; this helper does not
   rebuild the tree. Use the AST tier when scoping matters: the form
   tier (`walk-anon-fn-body`) uses `(fn …)` as a boundary, which can't
   distinguish a user-written `(fn …)` from a nested `#()` lowering."
  [visit body]
  ((fn walk [node]
     (cond
       (instance? CljAnonFn node) nil
       (satisfies? nodes/AstNode node)
       (do (visit node)
           (run! walk (nodes/children node)))
       :else (visit node)))
   body))

(defn- collect-ast-percent-params
  "Collect `%` types from an AST body — see `walk-anon-fn-body-ast` for
   scoping. Real-world repro of why the AST tier is needed:
   `#(reduce (fn [_ p] (parser %)) … xs)` (malli core.cljc) — the inner
   user-written `(fn …)`'s body's `%` belongs to the OUTER `#()`."
  [body]
  (let [result (volatile! #{})]
    (walk-anon-fn-body-ast
      (fn [node]
        (when-let [p (ast-symbol-type node)] (vswap! result conj p)))
      body)
    @result))

(defn- find-invalid-ast-percent
  "First invalid `%`-shaped symbol encountered in `body` (within the
   outer `#()`'s scope). Returns the string name or nil."
  [body]
  (let [result (volatile! nil)]
    (walk-anon-fn-body-ast
      (fn [node]
        (when (and (nil? @result) (ast-invalid-percent node))
          (vreset! result (ast-invalid-percent node))))
      body)
    @result))

(defn- normalize-bare-percent-ast
  "Rewrite the AST so every CljSymbol named `%` (in this `#()`'s scope)
   becomes `%1`. Returns a new AST with the same shape. Stops at nested
   `CljAnonFn` boundaries."
  [body]
  ((fn walk [node]
     (cond
       (instance? CljAnonFn node) node

       (and (instance? CljSymbol node)
            (= "%" (:name node))
            (nil? (:ns node)))
       (assoc node :name "%1")

       (satisfies? nodes/AstNode node)
       (let [cs (nodes/children node)]
         (if (seq cs)
           (nodes/rebuild node (mapv walk cs))
           node))

       :else node))
   body))

(defn- lower-anon-fn [n opts]
  (when-let [bad (find-invalid-ast-percent (:body n))]
    (errors/meme-error (str "Invalid % parameter: " bad) (:pos n)))
  (let [normalized-body (normalize-bare-percent-ast (:body n))
        params (collect-ast-percent-params normalized-body)
        fn-params (forms/build-anon-fn-params params)
        body-form (ast->form normalized-body opts)]
    (list 'fn fn-params body-form)))

(defn- lower-tagged [n opts]
  (let [tag (symbol (:tag n))
        form (ast->form (:form n) opts)]
    (resolve/resolve-tagged-literal tag form (:pos n))))

(defn- lower-reader-cond [n opts]
  (let [items (vec (mapcat (fn [pair] (lower-children pair opts)) (:pairs n)))]
    (forms/make-reader-conditional (apply list items) (:splicing? n))))

(defn- lower-meta [n opts]
  (let [target (ast->form (:target n) opts)
        chain (mapv #(ast->form % opts) (:chain n))]
    (when-not (metadatable? target)
      (errors/meme-error
        (str "Metadata cannot be applied to " (pr-str target))
        (:pos n)))
    (let [entries (mapv (fn [m]
                          (cond
                            (keyword? m) {m true}
                            (symbol? m) {:tag m}
                            (string? m) {:tag m}
                            (map? m)    m
                            :else
                            (errors/meme-error
                              (str "Metadata must be a keyword, symbol, string, or map — got "
                                   (pr-str m))
                              (:pos n))))
                        chain)
          merged-meta (reduce merge entries)]
      (vary-meta target merge merged-meta))))

(defn- lower-namespaced-map [n opts]
  (let [{:keys [ns inner]} n
        qual-ns ns
        items (vec (mapcat (fn [pair] (lower-children pair opts)) (:pairs inner)))
        _ (when (odd? (count items))
            (errors/meme-error "Namespaced map must contain even number of forms" (:pos n)))
        pairs (partition 2 items)
        qualify-key (fn [k]
                      (if (and (keyword? k) (nil? (namespace k))
                               (not (str/blank? qual-ns)))
                        (keyword qual-ns (clojure.core/name k))
                        k))
        qualified-ks (mapv (comp qualify-key first) pairs)
        _ (when-let [dup (first-duplicate qualified-ks)]
            (errors/meme-error (str "Duplicate key: " (pr-str dup)) (:pos n)))]
    (into (array-map)
          (map (fn [k [_ v]] [k v]) qualified-ks pairs))))

;; ---------------------------------------------------------------------------
;; Protocol wiring — single extend-protocol form using reader-conditional
;; class refs (FQN on JVM, ns-aliased on CLJS).
;; ---------------------------------------------------------------------------

(extend-protocol Lowerable
  #?(:clj meme.tools.clj.ast.nodes.CljSymbol :cljs nodes/CljSymbol)
  (-lower [n _] (lower-symbol n))

  #?(:clj meme.tools.clj.ast.nodes.CljKeyword :cljs nodes/CljKeyword)
  (-lower [n opts] (lower-keyword n opts))

  #?(:clj meme.tools.clj.ast.nodes.CljNumber :cljs nodes/CljNumber)
  (-lower [n _] (lower-number n))

  #?(:clj meme.tools.clj.ast.nodes.CljString :cljs nodes/CljString)
  (-lower [n _] (lower-string n))

  #?(:clj meme.tools.clj.ast.nodes.CljChar :cljs nodes/CljChar)
  (-lower [n _] (lower-char n))

  #?(:clj meme.tools.clj.ast.nodes.CljRegex :cljs nodes/CljRegex)
  (-lower [n _] (lower-regex n))

  #?(:clj meme.tools.clj.ast.nodes.CljNil :cljs nodes/CljNil)
  (-lower [n _] (lower-nil n))

  #?(:clj meme.tools.clj.ast.nodes.CljBool :cljs nodes/CljBool)
  (-lower [n _] (lower-bool n))

  #?(:clj meme.tools.clj.ast.nodes.CljList :cljs nodes/CljList)
  (-lower [n opts] (lower-list n opts))

  #?(:clj meme.tools.clj.ast.nodes.CljVector :cljs nodes/CljVector)
  (-lower [n opts] (lower-vector n opts))

  #?(:clj meme.tools.clj.ast.nodes.CljMap :cljs nodes/CljMap)
  (-lower [n opts] (lower-map n opts))

  #?(:clj meme.tools.clj.ast.nodes.CljSet :cljs nodes/CljSet)
  (-lower [n opts] (lower-set n opts))

  #?(:clj meme.tools.clj.ast.nodes.CljQuote :cljs nodes/CljQuote)
  (-lower [n opts] (lower-quote n opts))

  #?(:clj meme.tools.clj.ast.nodes.CljDeref :cljs nodes/CljDeref)
  (-lower [n opts] (lower-deref n opts))

  #?(:clj meme.tools.clj.ast.nodes.CljVar :cljs nodes/CljVar)
  (-lower [n opts] (lower-var n opts))

  #?(:clj meme.tools.clj.ast.nodes.CljSyntaxQuote :cljs nodes/CljSyntaxQuote)
  (-lower [n opts] (lower-syntax-quote n opts))

  #?(:clj meme.tools.clj.ast.nodes.CljUnquote :cljs nodes/CljUnquote)
  (-lower [n opts] (lower-unquote n opts))

  #?(:clj meme.tools.clj.ast.nodes.CljUnquoteSplicing :cljs nodes/CljUnquoteSplicing)
  (-lower [n opts] (lower-unquote-splicing n opts))

  #?(:clj meme.tools.clj.ast.nodes.CljAnonFn :cljs nodes/CljAnonFn)
  (-lower [n opts] (lower-anon-fn n opts))

  #?(:clj meme.tools.clj.ast.nodes.CljDiscard :cljs nodes/CljDiscard)
  (-lower [_ _] nil)

  #?(:clj meme.tools.clj.ast.nodes.CljTagged :cljs nodes/CljTagged)
  (-lower [n opts] (lower-tagged n opts))

  #?(:clj meme.tools.clj.ast.nodes.CljReaderCond :cljs nodes/CljReaderCond)
  (-lower [n opts] (lower-reader-cond n opts))

  #?(:clj meme.tools.clj.ast.nodes.CljMeta :cljs nodes/CljMeta)
  (-lower [n opts] (lower-meta n opts))

  #?(:clj meme.tools.clj.ast.nodes.CljNamespacedMap :cljs nodes/CljNamespacedMap)
  (-lower [n opts] (lower-namespaced-map n opts))

  ;; CljRoot is a multi-form container, not a form. ast->form on a root
  ;; is a category error; the caller wanted ast->forms.
  #?(:clj meme.tools.clj.ast.nodes.CljRoot :cljs nodes/CljRoot)
  (-lower [_ _]
    (throw (ex-info "ast->form on CljRoot — use ast->forms for the multi-form container"
                    {:type ::root-lowered-as-form}))))

;; ---------------------------------------------------------------------------
;; Top-level
;; ---------------------------------------------------------------------------

(defn ast->forms
  "Lower a CljRoot to a vector of Clojure forms.

  Mirrors `cst-reader/read-forms`: filters discard nodes at top level,
  attaches :trailing-ws metadata when the root carries trailing trivia."
  ([root] (ast->forms root nil))
  ([root opts]
   (let [children (:children root)
         non-discard (remove discard-node? children)
         forms (mapv #(ast->form % opts) non-discard)]
     (if-let [trailing (seq (:trailing-trivia root))]
       (let [ws (apply str (map :raw trailing))]
         (with-meta forms {:trailing-ws ws}))
       forms))))
