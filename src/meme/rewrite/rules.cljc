(ns meme.rewrite.rules
  "Rewrite rule sets for S→M and M→S transformations.
   Each direction is a vector of rules for meme.rewrite/rewrite."
  (:require [clojure.string :as str]
            [meme.rewrite :as rw]
            [meme.forms :as forms]))

;; ============================================================
;; S→M: Clojure forms → M-expression tagged tree
;; ============================================================

(def s->m-rules
  "Rules that tag S-expression calls as m-call nodes.
   List patterns only match lists (not vectors) — the engine distinguishes them.
   M13: extended to accept nil, true, false as heads — meme spec says
   'any value can be a head': nil(1 2) → (nil 1 2), true(:a) → (true :a).
   Numbers and strings are NOT included because (42 x) in Clojure is data (not a call),
   and tagging them would break clj→meme→clj vendor roundtrips."
  [(rw/rule '(?f ??args) '(m-call ?f ??args)
            (fn [bindings]
              (let [f (get bindings 'f)]
                (and (or (symbol? f) (keyword? f) (nil? f)
                         (true? f) (false? f))
                     (not= f 'm-call)))))])

;; ============================================================
;; M→S: M-expression tagged tree → Clojure forms
;; ============================================================

(def m->s-rules
  "Rules that convert m-call nodes back to S-expression lists."
  [(rw/rule '(m-call ?f ??args) '(?f ??args))])

;; ============================================================
;; Helpers for anon-fn transformation
;; ============================================================

;; find-percent-params, normalize-bare-percent, build-anon-fn-params
;; are in forms.cljc (shared with parse/reader.cljc)

;; ============================================================
;; Tree→S: tagged tree (from tree-builder) → Clojure forms
;;
;; Two-phase: rules flatten calls/parens, then transform-structures
;; handles types the engine can't produce (vectors, maps, sets,
;; metadata, AST nodes).
;; ============================================================

(def tree->s-rules
  "Rules that flatten tagged tree nodes to Clojure forms."
  [(rw/rule '(m-call ?f ??args) '(?f ??args))
   (rw/rule '(paren ??items) '(??items))])

(defn transform-structures
  "Walk a tree and convert structural tags to Clojure data/AST nodes.
   With opts {:read-cond :preserve}, reader conditionals are preserved
   as ReaderConditional objects instead of being evaluated."
  ([form] (transform-structures form nil))
  ([form opts]
   (let [preserve? (= :preserve (:read-cond opts))
         recurse #(transform-structures % opts)]
     (cond
       (and (seq? form) (seq form))
       (let [head (first form)
             children (mapv recurse (rest form))]
         (case head
           bracket    (vec children)
           brace      (apply array-map children)
           set-lit    (with-meta (set children) {:meme/order (vec children)})

           meme/quote
           (with-meta (list 'quote (first children)) {:meme/sugar true})

           meme/deref
           (with-meta (list 'clojure.core/deref (first children)) {:meme/sugar true})

           meme/var
           (with-meta (list 'var (first children)) {:meme/sugar true})

           meme/meta
           (let [m (first children)
                 target (second children)
                 meta-map (cond
                            (keyword? m) {m true}
                            (symbol? m) {:tag m}
                            (map? m) m
                            :else {m true})]
             (vary-meta target merge meta-map))

           meme/syntax-quote   (forms/->MemeSyntaxQuote (first children))
           meme/unquote        (forms/->MemeUnquote (first children))
           meme/unquote-splicing (forms/->MemeUnquoteSplicing (first children))

           anon-fn
           (let [body (first children)
                 params-before (forms/find-percent-params body)
                 has-bare? (contains? params-before :bare)
                 body (forms/normalize-bare-percent body)
                 params (forms/find-percent-params body)
                 param-vec (forms/build-anon-fn-params params)]
             (with-meta (list 'fn param-vec body)
               (cond-> {:meme/sugar true}
                 has-bare? (assoc :meme/bare-percent true))))

           ;; RT3-F7: On CLJS, preserve tag+data as a map (CLJS has no TaggedLiteral type)
           meme/tagged
           #?(:clj  (tagged-literal (first children) (second children))
              :cljs {:tag (first children) :data (second children)})

           ;; RT3-F8: no-match returns nil (discard) instead of wrapping in RC
           ;; PT-F5: use reduce+sentinel to handle false/nil branch values correctly
           meme/reader-cond
           (if preserve?
             (forms/make-reader-conditional (apply list children) false)
             (let [platform #?(:clj :clj :cljs :cljs)
                   pairs (partition 2 children)
                   sentinel #?(:clj (Object.) :cljs #js {})
                   matched (reduce (fn [_ [k v]]
                                     (if (or (= k platform) (= k :default))
                                       (reduced v)
                                       sentinel))
                                   sentinel pairs)]
               (when-not (identical? matched sentinel) matched)))

           meme/reader-cond-splicing
           (if preserve?
             (forms/make-reader-conditional (apply list children) true)
             (let [platform #?(:clj :clj :cljs :cljs)
                   pairs (partition 2 children)
                   sentinel #?(:clj (Object.) :cljs #js {})
                   matched (reduce (fn [_ [k v]]
                                     (if (or (= k platform) (= k :default))
                                       (reduced v)
                                       sentinel))
                                   sentinel pairs)]
               (when-not (identical? matched sentinel) matched)))

           meme/ns-map
           (let [ns-sym (first children)
                 ns-str (let [s (name ns-sym)]
                          (if (str/starts-with? s "#:") (subs s 2) s))
                 kvs (rest children)
                 pairs (partition 2 kvs)]
             (with-meta
               (into {} (map (fn [[k v]]
                               [(if (and (keyword? k) (nil? (namespace k)))
                                  (keyword ns-str (name k))
                                  k)
                                v])
                             pairs))
               {:meme/ns ns-str}))

           ;; Default: if this looks like a structural tag we missed, throw.
           ;; Otherwise, recurse into a normal list form.
           (do (when (and (symbol? head)
                          (or (= "meme" (namespace head))
                              (contains? #{'bracket 'brace 'set-lit 'anon-fn 'paren 'm-call}
                                         head)))
                 (throw (ex-info (str "Unrecognized structural tag: " head)
                                 {:tag head :form form})))
               (apply list (recurse head) (seq children)))))

       (vector? form) (mapv recurse form)
       (record? form) form
       (map? form) (into {} (map (fn [[k v]] [(recurse k) (recurse v)]) form))
       (set? form) (set (map recurse form))
       :else form))))

(defn rewrite-inside-reader-conditionals
  "Walk a form tree and apply rewrite-fn to values inside ReaderConditional objects.
   The rewrite engine doesn't descend into ReaderConditionals (they're not sequential),
   so this is needed to e.g. apply S→M rules inside #?(:clj ...) branches."
  [rewrite-fn form]
  (cond
    (forms/meme-reader-conditional? form)
    (let [pairs (partition 2 (forms/rc-form form))
          rewritten (mapcat (fn [[k v]] [k (rewrite-fn v)]) pairs)]
      (forms/make-reader-conditional (apply list rewritten) (forms/rc-splicing? form)))

    (seq? form)
    (apply list (map #(rewrite-inside-reader-conditionals rewrite-fn %) form))

    (vector? form)
    (mapv #(rewrite-inside-reader-conditionals rewrite-fn %) form)

    (map? form)
    (into {} (map (fn [[k v]]
                    [(rewrite-inside-reader-conditionals rewrite-fn k)
                     (rewrite-inside-reader-conditionals rewrite-fn v)])
                  form))

    (set? form)
    (set (map #(rewrite-inside-reader-conditionals rewrite-fn %) form))

    :else form))
