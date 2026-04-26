(ns meme.tools.clj.ast.lower
  "Lower an AST to plain Clojure forms.

   Produces the same forms (with the same `:m1clj/*` metadata) that
   `meme.tools.clj.cst-reader/read-forms` produces today, so this is a
   drop-in equivalence: `(ast->forms (cst->ast cst))` ≡ `(read-forms cst)`.

   Resolution that cst-reader did at read time happens here:
   - `::keyword` resolution via `:resolve-keyword` opt
   - tagged literal resolution via host data-readers
   - anon-fn percent-param normalization
   - namespaced-map key qualification
   - duplicate-key / odd-count map / nested anon-fn validation"
  (:require [clojure.string :as str]
            [meme.tools.clj.ast.nodes :as nodes]
            [meme.tools.clj.errors :as errors]
            [meme.tools.clj.forms :as forms]
            [meme.tools.clj.resolve :as resolve]))

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

(defn- trivia-str [trivia]
  (when (seq trivia)
    (apply str (map :raw trivia))))

(defn- attach-trivia
  [form trivia]
  (let [ws (trivia-str trivia)]
    (if (and ws (metadatable? form))
      (vary-meta form assoc :m1clj/leading-trivia ws)
      form)))

(defn- canonical-key [k]
  (if (forms/raw? k) (:value k) k))

(defn- first-duplicate [coll]
  (let [freqs (frequencies (map canonical-key coll))]
    (first (filter #(> (get freqs (canonical-key %)) 1) coll))))

(defn- discard-node? [n]
  (instance? #?(:clj meme.tools.clj.ast.nodes.CljDiscard
                :cljs nodes/CljDiscard) n))

(defn- lower-children
  "Lower a vec of AST children to forms, filtering CljDiscard."
  [children opts]
  (mapv #(ast->form % opts) (remove discard-node? children)))

;; ---------------------------------------------------------------------------
;; Per-record lowering bodies — kept as plain defns so the dispatch stays
;; small. extend-protocol below wires them in.
;; ---------------------------------------------------------------------------

(defn- lower-symbol [n]
  (let [s (if (:ns n) (symbol (:ns n) (:name n)) (symbol (:name n)))]
    (attach-trivia s (:trivia n))))

(defn- lower-keyword [n opts]
  (let [{:keys [name ns auto-resolve?]} n]
    (cond
      (not auto-resolve?)
      (attach-trivia (if ns (keyword ns name) (keyword name)) (:trivia n))

      :else
      (let [raw (str "::" (when ns (str ns "/")) name)
            v (resolve/resolve-auto-keyword raw (:pos n) (:resolve-keyword opts))]
        (attach-trivia v (:trivia n))))))

(defn- lower-number [n]
  (let [v (resolve/resolve-number (:raw n) (:pos n))]
    (if (forms/raw? v)
      v
      (attach-trivia (:value n) (:trivia n)))))

(defn- lower-string [n]
  (attach-trivia (:value n) (:trivia n)))

(defn- lower-char [n]
  (let [v (resolve/resolve-char (:raw n) (:pos n))]
    (if (forms/raw? v)
      v
      (attach-trivia (:value n) (:trivia n)))))

(defn- lower-regex [n]
  (let [raw (str "#\"" (:pattern n) "\"")
        v (resolve/resolve-regex raw (:pos n))]
    (attach-trivia v (:trivia n))))

(defn- lower-nil [n]
  (attach-trivia nil (:trivia n)))

(defn- lower-bool [n]
  (attach-trivia (:value n) (:trivia n)))

(defn- lower-list [n opts]
  (let [items (lower-children (:children n) opts)]
    (if (empty? items)
      (list)
      (attach-trivia (apply list items) (:trivia n)))))

(defn- lower-vector [n opts]
  (let [items (lower-children (:children n) opts)]
    (attach-trivia (vec items) (:trivia n))))

(defn- lower-map [n opts]
  (let [items (vec (mapcat (fn [pair] (lower-children pair opts)) (:pairs n)))]
    (when (odd? (count items))
      (errors/meme-error "Map must contain an even number of forms" (:pos n)))
    (let [ks (take-nth 2 items)]
      (when-let [dup (first-duplicate ks)]
        (errors/meme-error (str "Duplicate key: " (pr-str dup)) (:pos n))))
    (attach-trivia (apply array-map items) (:trivia n))))

(defn- lower-set [n opts]
  (let [items (lower-children (:children n) opts)]
    (when-let [dup (first-duplicate items)]
      (errors/meme-error (str "Duplicate key: " (pr-str dup)) (:pos n)))
    (-> (set items)
        (attach-trivia (:trivia n))
        (vary-meta assoc :m1clj/insertion-order (vec items)))))

(defn- lower-quote [n opts]
  (with-meta (list 'quote (ast->form (:form n) opts))
    {:m1clj/sugar true}))

(defn- lower-deref [n opts]
  (with-meta (list 'clojure.core/deref (ast->form (:form n) opts))
    {:m1clj/sugar true}))

(defn- lower-var [n opts]
  (let [inner (ast->form (:form n) opts)]
    (when-not (symbol? inner)
      (errors/meme-error
        (str "#' (var-quote) requires a symbol — got " (pr-str inner))
        (:pos n)))
    (with-meta (list 'var inner) {:m1clj/sugar true})))

(defn- lower-syntax-quote [n opts]
  (forms/->CljSyntaxQuote (ast->form (:form n) opts)))

(defn- pos+trivia-meta
  "Combine :pos and :m1clj/leading-trivia (from :trivia) into one map.
  cst-reader attaches loc as base meta on Unquote*; read-children layers
  :m1clj/leading-trivia on top when there's leading whitespace."
  [n]
  (let [ws (trivia-str (:trivia n))]
    (cond-> (or (:pos n) {})
      ws (assoc :m1clj/leading-trivia ws))))

(defn- lower-unquote [n opts]
  (with-meta (forms/->CljUnquote (ast->form (:form n) opts))
    (pos+trivia-meta n)))

(defn- lower-unquote-splicing [n opts]
  (with-meta (forms/->CljUnquoteSplicing (ast->form (:form n) opts))
    (pos+trivia-meta n)))

(defn- lower-anon-fn [n opts]
  (let [body-form (ast->form (:body n) opts)
        invalid (forms/find-invalid-percent-symbols body-form)
        _ (when (some? invalid)
            (errors/meme-error
              (str "Invalid % parameter: " invalid)
              (:pos n)))
        normalized (forms/walk-anon-fn-body forms/normalize-bare-percent body-form)
        params (forms/find-percent-params normalized)
        fn-params (forms/build-anon-fn-params params)]
    (with-meta (list 'fn fn-params normalized) {:m1clj/sugar true})))

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
          merged-meta (reduce merge entries)
          existing-chain (or (:m1clj/meta-chain (meta target)) [])
          new-chain (into existing-chain entries)]
      (vary-meta target merge merged-meta {:m1clj/meta-chain new-chain}))))

(defn- lower-namespaced-map [n opts]
  (let [{:keys [ns auto-resolve? inner]} n
        ns-str (if auto-resolve?
                 (if (str/blank? ns) "::" (str "::" ns))
                 ns)
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
            (errors/meme-error (str "Duplicate key: " (pr-str dup)) (:pos n)))
        resolved (into (array-map)
                       (map (fn [k [_ v]] [k v]) qualified-ks pairs))]
    (with-meta resolved {:m1clj/namespace-prefix ns-str})))

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
  (-lower [n opts] (lower-namespaced-map n opts)))

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
