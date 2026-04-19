(ns meme-lang.cst-reader
  "CST reader: walks CST nodes from the Pratt parser and produces Clojure forms.

   This is the lowering step: CST → Clojure forms. It mirrors the classic
   parser's output (same forms, same metadata, same AST node types) but
   reads from a lossless tree instead of a token stream.

   Pipeline: scanner → trivia-attacher → pratt-parser → **cst-reader**"
  (:require [clojure.string :as str]
            [meme.tools.clj.errors :as errors]
            [meme.tools.clj.forms :as forms]
            [meme.tools.clj.resolve :as resolve]))

;; ---------------------------------------------------------------------------
;; Top-level shebang sentinel
;; ---------------------------------------------------------------------------

(def ^:private no-match
  "Sentinel returned for top-level shebang atoms so that read-forms drops
   them from the forms vector. Historically used for reader-conditional
   no-match too; since the reader now always preserves #? as records, only
   shebang still produces this sentinel."
  ::no-match)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- tok-loc
  "Extract {:line :col} from a token."
  [tok]
  (select-keys tok [:line :col]))

(defn- node-loc
  "Extract location from a CST node (from its first token)."
  [node]
  (or (tok-loc (or (:token node) (:open node) (:ns node)))
      {:line 1 :col 1}))

(defn- ws-before
  "Get the whitespace string from trivia/before on a token."
  [tok]
  (when-let [trivia (:trivia/before tok)]
    (apply str (map :raw trivia))))

(defn- check-closed!
  "Throw with :incomplete true if a delimited node has no closing token.
   The REPL uses :incomplete to distinguish multi-line continuation from errors."
  [node ctx-name]
  (when-not (:close node)
    (errors/meme-error
      (str "Unclosed " ctx-name " — expected closing delimiter")
      (assoc (node-loc node) :incomplete true))))

;; ---------------------------------------------------------------------------
;; Forward declaration
;; ---------------------------------------------------------------------------

(declare read-node)

;; ---------------------------------------------------------------------------
;; Atom resolution
;; ---------------------------------------------------------------------------

(defn- read-atom
  "Resolve an atom CST node to a Clojure value."
  [node opts]
  (let [tok (:token node)
        raw (:raw tok)
        loc (tok-loc tok)]
    (case (:type tok)
      :symbol
      (case raw
        "nil" nil
        "true" true
        "false" false
        ;; Reject symbols starting with `/` except the bare `/` division
        ;; symbol. Matches Clojure: `/` is valid, but `//`, `//a`, `/foo`
        ;; are invalid. `foo//bar`, `clojure.core//` stay valid (not
        ;; caught here — they don't start with `/`).
        (do
          (when (and (str/starts-with? raw "/") (not= raw "/"))
            (errors/meme-error (str "Invalid token: " raw) loc))
          (symbol raw)))

      :keyword
      (if (str/starts-with? raw ":::")
        ;; Triple+ colons: always invalid
        (errors/meme-error (str "Invalid token: " raw) loc)
        (if (str/starts-with? raw "::")
          (let [body (subs raw 2)]
            (when (or (= body "") (str/starts-with? body "/") (str/ends-with? body "/"))
              (errors/meme-error (str "Invalid token: " raw) loc))
            (resolve/resolve-auto-keyword raw loc (:resolve-keyword opts)))
          (let [s (subs raw 1)]
            (cond
              ;; :/ — bare slash keyword, valid in Clojure as (keyword "/")
              (= s "/") (keyword "/")
              (or (= s "")                        ;; bare :
                  (str/starts-with? s "/")         ;; :/foo
                  (str/ends-with? s ":")           ;; :foo:
                  (str/ends-with? s "/")           ;; :foo/
                  (str/includes? s "::"))          ;; :a::b
              (errors/meme-error (str "Invalid token: " raw) loc)
              :else
              (let [i (str/index-of s "/")]
                (if (some? i)
                  (let [ns-part (subs s 0 i)
                        name-part (subs s (inc i))]
                    (when (or (= ns-part "") (= name-part ""))
                      (errors/meme-error (str "Invalid token: " raw) loc))
                    (keyword ns-part name-part))
                  (keyword s)))))))

      :number
      (resolve/resolve-number raw loc)

      :string
      (resolve/resolve-string raw loc)

      :char-literal
      (resolve/resolve-char raw loc)

      :regex
      (resolve/resolve-regex raw loc)

      ;; fallback
      (errors/meme-error (str "Unknown atom type: " (:type tok)) loc))))

;; ---------------------------------------------------------------------------
;; Collection reading
;; ---------------------------------------------------------------------------

(defn- splice-and-filter
  "Remove shebang sentinels from read children. Reader conditionals are
   preserved as records; no splicing happens at read time — the pipeline
   stage `step-evaluate-reader-conditionals` materializes them later."
  [items]
  (persistent!
    (reduce (fn [acc item]
              (if (identical? item no-match)
                acc
                (conj! acc item)))
            (transient []) items)))

(defn- read-children
  "Read a vector of CST child nodes into Clojure forms, filtering discards.
   Discard nodes already contain their target in :form — just skip them.
   Also splices #?@ results and filters no-match sentinels."
  [children opts]
  (splice-and-filter
    (into [] (comp (remove #(= :discard (:node %)))
                   (map #(read-node % opts)))
          children)))

(defn- metadatable?
  "Can metadata be attached to this value?"
  [v]
  #?(:clj (instance? clojure.lang.IObj v)
     :cljs (satisfies? IWithMeta v)))

(defn- read-children-with-ws
  "Read children, preserving :meme-lang/leading-trivia metadata from trivia on each form.
   Discard nodes already contain their target — just skip them.
   Also splices #?@ results and filters no-match sentinels."
  [children opts]
  (splice-and-filter
    (into []
          (comp (remove #(= :discard (:node %)))
                (map (fn [child]
                       (let [form (read-node child opts)
                             first-tok (or (:token child) (:open child) (:ns child))
                             ws (ws-before first-tok)]
                         (if (and ws (metadatable? form))
                           (vary-meta form assoc :meme-lang/leading-trivia ws)
                           form)))))
          children)))

;; ---------------------------------------------------------------------------
;; Node reading
;; ---------------------------------------------------------------------------

(defn read-node
  "Read a single CST node into a Clojure form."
  [node opts]
  (let [depth (or (::depth opts) 0)]
    (when (>= depth forms/max-parse-depth)
      (errors/meme-error "Maximum nesting depth exceeded" (node-loc node))))
  (let [opts (update opts ::depth (fnil inc 0))]
  (case (:node node)
    :atom
    (let [tok (:token node)]
      (if (= :shebang (:type tok))
        no-match  ;; shebang lines are informational, produce no form
        (let [form (read-atom node opts)
              ws (ws-before tok)]
          (if (and ws (metadatable? form))
            (vary-meta form assoc :meme-lang/leading-trivia ws)
            form))))

    :call
    (let [_ (check-closed! node "call")
          head (read-node (:head node) opts)
          args (read-children (:args node) opts)
          ws-open (ws-before (:open node))
          result (apply list head args)]
      (if ws-open
        (with-meta result (assoc (meta result) :meme-lang/leading-trivia ws-open))
        result))

    :list
    (list)

    :vector
    (let [_ (check-closed! node "vector")
          items (read-children-with-ws (:children node) opts)
          ws (ws-before (:open node))]
      (cond-> (vec items)
        ws (vary-meta assoc :meme-lang/leading-trivia ws)))

    :map
    (let [_ (check-closed! node "map")
          items (read-children (:children node) opts)
          ws (ws-before (:open node))]
      (when (odd? (count items))
        (errors/meme-error "Map must contain an even number of forms"
                           (node-loc node)))
      (let [ks (take-nth 2 items)]
        (when-let [dup (first (for [[k freq] (frequencies ks) :when (> freq 1)] k))]
          (errors/meme-error (str "Duplicate key: " (pr-str dup)) (node-loc node))))
      (cond-> (apply array-map items)
        ws (vary-meta assoc :meme-lang/leading-trivia ws)))

    :set
    (let [_ (check-closed! node "set")
          items (read-children (:children node) opts)
          ws (ws-before (:open node))]
      (when-let [dup (first (for [[v freq] (frequencies items) :when (> freq 1)] v))]
        (errors/meme-error (str "Duplicate key: " (pr-str dup)) (node-loc node)))
      (cond-> (set items)
        ws (vary-meta assoc :meme-lang/leading-trivia ws)
        true (vary-meta assoc :meme-lang/insertion-order (vec items))))

    :quote
    (let [form (read-node (:form node) opts)]
      (with-meta (list 'quote form) {:meme-lang/sugar true}))

    :deref
    (let [form (read-node (:form node) opts)]
      (with-meta (list 'clojure.core/deref form) {:meme-lang/sugar true}))

    :syntax-quote
    (let [form (read-node (:form node) opts)]
      (forms/->MemeSyntaxQuote form))

    :unquote
    (let [form (read-node (:form node) opts)]
      (with-meta (forms/->MemeUnquote form)
        (tok-loc (:token node))))

    :unquote-splicing
    (let [form (read-node (:form node) opts)]
      (with-meta (forms/->MemeUnquoteSplicing form)
        (tok-loc (:token node))))

    :meta
    (let [m (read-node (:meta node) opts)
          target (read-node (:target node) opts)
          entry (cond
                  (keyword? m) {m true}
                  (symbol? m)  {:tag m}
                  (string? m)  {:tag m}
                  (map? m)     m
                  :else
                  (errors/meme-error
                    (str "Metadata must be a keyword, symbol, string, or map — got " (pr-str m))
                    (node-loc node)))]
      (when-not (metadatable? target)
        (errors/meme-error
          (str "Metadata cannot be applied to " (pr-str target))
          (node-loc node)))
      (let [chain (conj (let [existing (:meme-lang/meta-chain (meta target))]
                          (if (vector? existing) existing []))
                        entry)]
        (vary-meta target merge entry {:meme-lang/meta-chain chain})))

    :var-quote
    (let [form (read-node (:form node) opts)]
      (when-not (symbol? form)
        (errors/meme-error
          (str "#' (var-quote) requires a symbol — got " (pr-str form))
          (node-loc node)))
      (with-meta (list 'var form) {:meme-lang/sugar true}))

    :discard
    ;; Discards at top level — read-forms filters them
    nil

    :tagged
    (let [tag-raw (:raw (:token node))
          tag-sym (symbol (subs tag-raw 1))  ;; strip leading #
          form (read-node (:form node) opts)]
      (resolve/resolve-tagged-literal tag-sym form (node-loc node)))

    :anon-fn
    (let [_ (check-closed! node "anonymous function")
          _ (when (::in-anon-fn opts)
              (errors/meme-error "Nested #() are not allowed" (node-loc node)))
          body (read-children (:children node) (assoc opts ::in-anon-fn true))]
      (when (empty? body)
        (errors/meme-error "#() requires a body" (node-loc node)))
      (let [body-form (if (= 1 (count body))
                        (first body)
                        (apply list 'do body))
            invalid (forms/find-invalid-percent-symbols body-form)
            _ (when (some? invalid)
                (errors/meme-error
                  (str "Invalid % parameter: " invalid)
                  (node-loc node)))
            normalized (forms/walk-anon-fn-body forms/normalize-bare-percent body-form)
            params (forms/find-percent-params normalized)
            fn-params (forms/build-anon-fn-params params)]
        (with-meta (list 'fn fn-params normalized) {:meme-lang/sugar true})))

    :namespaced-map
    (let [_ (check-closed! node "namespaced map")
          ns-raw (:raw (:ns node))
          auto-resolve? (str/starts-with? ns-raw "#::")
          ;; #:ns → "ns", #::alias → "::alias" (preserve prefix for roundtrip)
          ns-name (if auto-resolve? (subs ns-raw 3) (subs ns-raw 2))
          _ (when (and (str/blank? ns-name) (not auto-resolve?))
              (errors/meme-error "Namespaced map must specify a namespace"
                                 (node-loc node)))
          ;; #::{} (bare auto-resolve) → ns-str "::", qual-ns "" (defer to eval)
          ;; #::alias{} → ns-str "::alias", qual-ns "alias"
          ;; #:ns{} → ns-str "ns", qual-ns "ns"
          ns-str (if auto-resolve?
                   (if (str/blank? ns-name) "::" (str "::" ns-name))
                   ns-name)
          ;; For key qualification, use the bare namespace name (without :: prefix)
          ;; Blank qual-ns (bare #::{}) skips qualification — keys stay unqualified
          qual-ns ns-name
          items (read-children (:children node) opts)
          _ (when (odd? (count items))
              (errors/meme-error "Namespaced map must contain even number of forms"
                                 (node-loc node)))
          pairs (partition 2 items)
          resolved (into (array-map)
                         (map (fn [[k v]]
                                [(if (and (keyword? k) (nil? (namespace k))
                                          (not (str/blank? qual-ns)))
                                   (keyword qual-ns (name k))
                                   k)
                                 v])
                              pairs))]
      (with-meta resolved {:meme-lang/namespace-prefix ns-str}))

    :reader-cond
    ;; The reader always preserves #?/#?@ as MemeReaderConditional records.
    ;; Platform materialization happens in step-evaluate-reader-conditionals.
    ;; Odd-count validation also lives there — the reader emits the record
    ;; shape faithfully regardless of whether the branches are well-formed.
    (let [_ (check-closed! node "reader conditional")
          items (read-children (:children node) opts)
          splicing? (:splicing? node)]
      (forms/make-reader-conditional (apply list items) splicing?))

    :error
    (let [msg (or (:message node) "Parse error")
          loc (node-loc node)
          eof? (or (str/includes? msg "end of input")
                   (str/includes? msg "Unexpected end"))]
      (errors/meme-error msg (cond-> loc eof? (assoc :incomplete true))))

    ;; Unknown node type
    (errors/meme-error (str "Unknown CST node: " (:node node)) (node-loc node)))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn read-forms
  "Read a vector of CST nodes into a vector of Clojure forms.
   Filters out discard nodes and no-match sentinels at the top level,
   and splices #?@ results."
  ([cst] (read-forms cst nil))
  ([cst opts]
   (let [forms (splice-and-filter
                 (into []
                       (comp (remove #(= :discard (:node %)))
                             (map #(read-node % opts)))
                       cst))]
     (if-let [trailing (:trivia/after (meta cst))]
       (let [ws (apply str (map :raw trailing))]
         (with-meta forms {:trailing-ws ws}))
       forms))))
