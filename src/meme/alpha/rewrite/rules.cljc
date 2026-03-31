(ns meme.alpha.rewrite.rules
  "Rewrite rule sets for S→M and M→S transformations.
   Each direction is a vector of rules for meme.alpha.rewrite/rewrite."
  (:require [clojure.string :as str]
            [meme.alpha.rewrite :as rw]
            [meme.alpha.forms :as forms]))

;; ============================================================
;; S→M: Clojure forms → M-expression tagged tree
;; ============================================================

(def s->m-rules
  "Rules that tag S-expression calls as m-call nodes.
   List patterns only match lists (not vectors) — the engine distinguishes them."
  [(rw/rule '(?f ??args) '(m-call ?f ??args)
            (fn [bindings]
              (let [f (get bindings 'f)]
                (and (or (symbol? f) (keyword? f))
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
  "Walk a tree and convert structural tags to Clojure data/AST nodes."
  [form]
  (cond
    (and (seq? form) (seq form))
    (let [head (first form)
          children (mapv transform-structures (rest form))]
      (case head
        bracket    (vec children)
        brace      (apply hash-map children)
        set-lit    (set children)

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
              body (forms/normalize-bare-percent body)
              params (forms/find-percent-params body)
              param-vec (forms/build-anon-fn-params params)]
          (with-meta (list 'fn param-vec body) {:meme/sugar true}))

        meme/tagged
        #?(:clj  (tagged-literal (first children) (second children))
           :cljs (first children))

        meme/reader-cond
        (let [platform #?(:clj :clj :cljs :cljs)
              pairs (partition 2 children)
              matched (some (fn [[k v]] (when (or (= k platform) (= k :default)) v)) pairs)]
          (if matched matched (forms/make-reader-conditional (vec children) false)))

        meme/reader-cond-splicing
        (let [platform #?(:clj :clj :cljs :cljs)
              pairs (partition 2 children)
              matched (some (fn [[k v]] (when (or (= k platform) (= k :default)) v)) pairs)]
          (if matched matched (forms/make-reader-conditional (vec children) true)))

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

        ;; Default: recurse into list
        (apply list (transform-structures head) (seq children))))

    (vector? form) (mapv transform-structures form)
    (record? form) form
    (map? form) (into {} (map (fn [[k v]] [(transform-structures k)
                                            (transform-structures v)]) form))
    (set? form) (set (map transform-structures form))
    :else form))
