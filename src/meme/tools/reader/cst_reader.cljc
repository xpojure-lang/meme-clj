(ns meme.tools.reader.cst-reader
  "CST reader: walks CST nodes from the Pratt parser and produces Clojure forms.

   This is the lowering step: CST → Clojure forms. It mirrors the classic
   parser's output (same forms, same metadata, same AST node types) but
   reads from a lossless tree instead of a token stream.

   Pipeline: scanner → trivia-attacher → pratt-parser → **cst-reader**"
  (:require [clojure.string :as str]
            [meme.tools.errors :as errors]
            [meme.tools.forms :as forms]
            [meme.tools.parse.resolve :as resolve]))

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
  "Get the :ws string from trivia/before on a token."
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
        (symbol raw))

      :keyword
      (if (str/starts-with? raw "::")
        (resolve/resolve-auto-keyword raw loc (:resolve-keyword opts))
        (let [s (subs raw 1)
              i (str/index-of s "/")]
          (if (some? i)
            (keyword (subs s 0 i) (subs s (inc i)))
            (keyword s))))

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

(defn- read-children
  "Read a vector of CST child nodes into Clojure forms, filtering discards.
   Discard nodes already contain their target in :form — just skip them."
  [children opts]
  (into [] (comp (remove #(= :discard (:node %)))
                 (map #(read-node % opts)))
        children))

(defn- metadatable?
  "Can metadata be attached to this value?"
  [v]
  #?(:clj (instance? clojure.lang.IObj v)
     :cljs (satisfies? IWithMeta v)))

(defn- read-children-with-ws
  "Read children, preserving :ws metadata from trivia on each form.
   Discard nodes already contain their target — just skip them."
  [children opts]
  (into []
        (comp (remove #(= :discard (:node %)))
              (map (fn [child]
                     (let [form (read-node child opts)
                           first-tok (or (:token child) (:open child) (:ns child))
                           ws (ws-before first-tok)]
                       (if (and ws (metadatable? form))
                         (vary-meta form assoc :ws ws)
                         form)))))
        children))

;; ---------------------------------------------------------------------------
;; Node reading
;; ---------------------------------------------------------------------------

(defn read-node
  "Read a single CST node into a Clojure form."
  [node opts]
  (case (:node node)
    :atom
    (let [form (read-atom node opts)
          first-tok (:token node)
          ws (ws-before first-tok)]
      (if (and ws (metadatable? form))
        (vary-meta form assoc :ws ws)
        form))

    :call
    (let [_ (check-closed! node "call")
          head (read-node (:head node) opts)
          args (read-children (:args node) opts)
          ws-open (ws-before (:open node))
          result (apply list head args)]
      (if ws-open
        (with-meta result (assoc (meta result) :ws ws-open))
        result))

    :list
    (list)

    :vector
    (let [_ (check-closed! node "vector")
          items (read-children-with-ws (:children node) opts)
          ws (ws-before (:open node))]
      (cond-> (vec items)
        ws (vary-meta assoc :ws ws)))

    :map
    (let [_ (check-closed! node "map")
          items (read-children (:children node) opts)
          ws (ws-before (:open node))]
      (when (odd? (count items))
        (errors/meme-error "Map must contain an even number of forms"
                           (node-loc node)))
      (cond-> (apply array-map items)
        ws (vary-meta assoc :ws ws)))

    :set
    (let [_ (check-closed! node "set")
          items (read-children (:children node) opts)
          ws (ws-before (:open node))]
      (cond-> (set items)
        ws (vary-meta assoc :ws ws)
        true (vary-meta assoc :meme/order (vec items))))

    :quote
    (let [form (read-node (:form node) opts)]
      (with-meta (list 'quote form) {:meme/sugar true}))

    :deref
    (let [form (read-node (:form node) opts)]
      (with-meta (list 'clojure.core/deref form) {:meme/sugar true}))

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
      (let [chain (conj (let [existing (:meme/meta-chain (meta target))]
                          (if (vector? existing) existing []))
                        entry)]
        (vary-meta target merge entry {:meme/meta-chain chain})))

    :var-quote
    (let [form (read-node (:form node) opts)]
      (when-not (symbol? form)
        (errors/meme-error
          (str "#' (var-quote) requires a symbol — got " (pr-str form))
          (node-loc node)))
      (with-meta (list 'var form) {:meme/sugar true}))

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
          body (read-children (:children node) opts)]
      (when (empty? body)
        (errors/meme-error "#() requires a body" (node-loc node)))
      (let [body-form (if (= 1 (count body))
                        (first body)
                        (apply list 'do body))
            invalid (forms/find-invalid-percent-symbols body-form)
            _ (when (seq invalid)
                (errors/meme-error
                  (str "Invalid % parameter: " (first invalid))
                  (node-loc node)))
            normalized (forms/walk-anon-fn-body forms/normalize-bare-percent body-form)
            params (forms/find-percent-params normalized)
            fn-params (forms/build-anon-fn-params params)]
        (with-meta (list 'fn fn-params normalized) {:meme/sugar true})))

    :namespaced-map
    (let [_ (check-closed! node "namespaced map")
          ns-raw (:raw (:ns node))
          ;; #:ns or #::ns — strip #: or #::
          ns-str (if (str/starts-with? ns-raw "#::")
                   (subs ns-raw 3)
                   (subs ns-raw 2))
          items (read-children (:children node) opts)
          _ (when (odd? (count items))
              (errors/meme-error "Namespaced map must contain even number of forms"
                                 (node-loc node)))
          pairs (partition 2 items)
          resolved (into (array-map)
                         (map (fn [[k v]]
                                [(if (and (keyword? k) (nil? (namespace k)))
                                   (keyword ns-str (name k))
                                   k)
                                 v])
                              pairs))]
      (with-meta resolved {:meme/ns ns-str}))

    :reader-cond
    (let [_ (check-closed! node "reader conditional")
          items (read-children (:children node) opts)
          splicing? (:splicing? node)
          read-cond-mode (or (:read-cond opts) :eval)]
      (case read-cond-mode
        :preserve
        (forms/make-reader-conditional (apply list items) splicing?)

        ;; :eval mode — match platform
        (let [platform #?(:clj :clj :cljs :cljs)
              pairs (partition 2 items)
              matched (some (fn [[k v]] (when (= k platform) v)) pairs)]
          (if splicing?
            ;; Return as splice marker
            (if matched
              (with-meta (vec matched) {:meme/splice true})
              nil)
            matched))))

    :error
    (let [msg (or (:message node) "Parse error")
          loc (node-loc node)
          eof? (or (str/includes? msg "end of input")
                   (str/includes? msg "Unexpected end"))]
      (errors/meme-error msg (cond-> loc eof? (assoc :incomplete true))))

    ;; Unknown node type
    (errors/meme-error (str "Unknown CST node: " (:node node)) (node-loc node))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn read-forms
  "Read a vector of CST nodes into a vector of Clojure forms.
   Filters out discard nodes at the top level."
  ([cst] (read-forms cst nil))
  ([cst opts]
   (let [forms (into []
                     (comp (remove #(= :discard (:node %)))
                           (map #(read-node % opts)))
                     cst)]
     (if-let [trailing (:trivia/after (meta cst))]
       (let [ws (apply str (map :raw trailing))]
         (with-meta forms {:trailing-ws ws}))
       forms))))
