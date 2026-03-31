(ns meme.alpha.rewrite.tree
  "Token vector → tagged tree for the rewrite-based M→S pipeline.
   Produces a tree with explicit m-call nodes for adjacency-based calls,
   structural tags (bracket, brace, set-lit, anon-fn), and prefix markers
   (meme/quote, meme/deref, etc.).

   This is the bridge between the shared tokenizer and the rewrite engine.
   The existing parser (meme.alpha.parse.reader) does tree-building and
   M→S transformation in one pass. This module separates them: build a
   raw tagged tree here, then let rewrite rules handle the rest."
  (:require [clojure.string :as str]
            [meme.alpha.errors :as errors]
            [meme.alpha.forms :as forms]
            [meme.alpha.parse.resolve :as resolve]
            [meme.alpha.rewrite :as rewrite]
            [meme.alpha.rewrite.rules :as rules]))

(def ^:private discard-sentinel ::discarded)
(def ^:private ^:const max-depth 512)
(def ^:private ^:dynamic *depth* nil)

;; ============================================================
;; Token stream helpers
;; ============================================================

(defn- tok-type [tokens pos]
  (when (< pos (count tokens))
    (:type (nth tokens pos))))

(defn- adjacent?
  "Is the token at pos adjacent to the previous token (no whitespace)?"
  [tokens pos]
  (and (< pos (count tokens))
       (nil? (:ws (nth tokens pos)))))

;; ============================================================
;; Recursive tree builder
;; ============================================================

(declare build-tree)

(defn- token-loc [token]
  (select-keys token [:line :col]))

(defn- resolve-atom
  "Convert a token value string to a Clojure value."
  [token]
  (let [loc (token-loc token)]
    (case (:type token)
      :symbol (case (:value token)
                "true" true
                "false" false
                "nil" nil
                (symbol (:value token)))
      :keyword (let [raw (:value token)]
                 (if (str/starts-with? raw "::")
                   ;; Auto-resolve keyword — use deferred encoding like the parser
                   (forms/deferred-auto-keyword raw)
                   (keyword (subs raw 1))))
      :number (resolve/resolve-number (:value token) loc)
      :string (resolve/resolve-string (:value token) loc)
      :char (resolve/resolve-char (:value token) loc)
      :regex (resolve/resolve-regex (:value token) loc)
      (symbol (:value token)))))

(defn- build-collection
  "Parse tokens from pos until close-type. Returns [items new-pos]."
  [tokens pos close-type]
  (loop [pos pos
         items []]
    (cond
      (>= pos (count tokens))
      (let [start-tok (when (pos? pos) (nth tokens (dec pos)))]
        (errors/meme-error (str "Unclosed " (name close-type))
                           (merge {:incomplete true}
                                  (when start-tok (select-keys start-tok [:line :col])))))

      (= (tok-type tokens pos) close-type)
      [items (inc pos)]

      :else
      (let [[item new-pos] (build-tree tokens pos)]
        (if (= item discard-sentinel)
          (recur new-pos items)
          (recur new-pos (conj items item)))))))

(defn- build-call-or-atom
  "After building an atom, check if the next token is an adjacent open-paren.
   If so, this is a call: head(args...) → (m-call head args...)"
  [tokens pos head]
  (if (and (< pos (count tokens))
           (= :open-paren (tok-type tokens pos))
           (adjacent? tokens pos))
    ;; Call: head(args...)
    (let [[args new-pos] (build-collection tokens (inc pos) :close-paren)]
      ;; Check for chained calls: head(a)(b) → (m-call (m-call head a...) b...)
      (loop [result (apply list 'm-call head args)
             pos new-pos]
        (if (and (< pos (count tokens))
                 (= :open-paren (tok-type tokens pos))
                 (adjacent? tokens pos))
          (let [[args2 new-pos2] (build-collection tokens (inc pos) :close-paren)]
            (recur (apply list 'm-call result args2) new-pos2))
          [result pos])))
    ;; Not a call — just the atom
    [head pos]))

(defn build-tree
  "Build a tagged tree node from tokens starting at pos.
   Returns [node new-pos]. When *depth* is bound (via tokens->tree),
   tracks recursion depth and throws at max-depth (512)."
  [tokens pos]
  (when *depth*
    (let [d (vswap! *depth* inc)]
      (when (> d max-depth)
        (let [token (nth tokens pos)]
          (errors/meme-error (str "Maximum nesting depth (" max-depth ") exceeded — input is too deeply nested")
                             (select-keys token [:line :col]))))))
  (try
    (let [token (nth tokens pos)
          tt (:type token)]
      (case tt
        ;; Atoms — resolve value, then check for adjacent call
        (:symbol :keyword)
        (let [val (resolve-atom token)]
          (build-call-or-atom tokens (inc pos) val))

        (:number :string :char :regex)
        [(resolve-atom token) (inc pos)]

        ;; Vectors: [items...] → (bracket items...)
        ;; Also check for vector-as-head: [x](body) → m-call
        :open-bracket
        (let [[items new-pos] (build-collection tokens (inc pos) :close-bracket)
              vec-form (apply list 'bracket items)]
          (build-call-or-atom tokens new-pos vec-form))

        ;; Maps: {k v ...} → (brace k v ...)
        :open-brace
        (let [[items new-pos] (build-collection tokens (inc pos) :close-brace)]
          [(apply list 'brace items) new-pos])

        ;; Sets: #{items...} → (set-lit items...)
        :open-set
        (let [[items new-pos] (build-collection tokens (inc pos) :close-brace)]
          [(apply list 'set-lit items) new-pos])

        ;; Bare parens: (...) → (paren ...) or empty list
        :open-paren
        (let [[items new-pos] (build-collection tokens (inc pos) :close-paren)]
          (if (empty? items)
            ['() new-pos]
            [(apply list 'paren items) new-pos]))

        ;; #() anonymous function
        :open-anon-fn
        (let [[items new-pos] (build-collection tokens (inc pos) :close-paren)]
          [(apply list 'anon-fn items) new-pos])

        ;; Prefix markers
        :quote
        (let [[inner new-pos] (build-tree tokens (inc pos))]
          [(list 'meme/quote inner) new-pos])

        :deref
        (let [[inner new-pos] (build-tree tokens (inc pos))]
          [(list 'meme/deref inner) new-pos])

        :meta
        (let [[meta-val new-pos1] (build-tree tokens (inc pos))
              [target new-pos2] (build-tree tokens new-pos1)]
          [(list 'meme/meta meta-val target) new-pos2])

        :syntax-quote
        (let [[inner new-pos] (build-tree tokens (inc pos))]
          [(list 'meme/syntax-quote inner) new-pos])

        :unquote
        (let [[inner new-pos] (build-tree tokens (inc pos))]
          [(list 'meme/unquote inner) new-pos])

        :unquote-splicing
        (let [[inner new-pos] (build-tree tokens (inc pos))]
          [(list 'meme/unquote-splicing inner) new-pos])

        :var-quote
        (let [[inner new-pos] (build-tree tokens (inc pos))]
          [(list 'meme/var inner) new-pos])

        :discard
        (let [[_ new-pos] (build-tree tokens (inc pos))]
          ;; discard the form, try next
          (if (and (< new-pos (count tokens))
                   (not (#{:close-paren :close-bracket :close-brace}
                          (tok-type tokens new-pos))))
            (build-tree tokens new-pos)
            [discard-sentinel new-pos]))

        ;; Tagged literals: #tag form
        :tagged-literal
        (let [tag (symbol (subs (:value token) 1))
              [inner new-pos] (build-tree tokens (inc pos))]
          [(list 'meme/tagged tag inner) new-pos])

        ;; Reader conditionals and namespaced maps — pass through as markers
        :reader-cond-start
        (let [splicing? (= "#?@" (:value token))
              paren-pos (inc pos)
              _ (when (or (>= paren-pos (count tokens))
                          (not= :open-paren (tok-type tokens paren-pos)))
                  (errors/meme-error (str "Expected ( after " (:value token))
                                     (select-keys token [:line :col])))
              [items new-pos] (build-collection tokens (inc paren-pos) :close-paren)
              rc-form (apply list (if splicing? 'meme/reader-cond-splicing 'meme/reader-cond) items)]
          (build-call-or-atom tokens new-pos rc-form))

        :namespaced-map-start
        (let [ns-prefix (:value token)
              brace-pos (inc pos)
              _ (when (or (>= brace-pos (count tokens))
                          (not= :open-brace (tok-type tokens brace-pos)))
                  (errors/meme-error (str "Expected { after " ns-prefix)
                                     (select-keys token [:line :col])))
              [items new-pos] (build-collection tokens (inc brace-pos) :close-brace)]
          [(apply list 'meme/ns-map (symbol ns-prefix) items) new-pos])

        ;; Fallback
        (errors/meme-error (str "Unexpected token type: " tt)
                           (select-keys token [:line :col]))))
    (finally
      (when *depth* (vswap! *depth* dec)))))

(defn tokens->tree
  "Convert a flat token vector to a tagged tree.
   Returns a vector of top-level forms."
  [tokens]
  (binding [*depth* (volatile! 0)]
    (loop [pos 0
           forms []]
      (if (>= pos (count tokens))
        forms
        (let [[form new-pos] (build-tree tokens pos)]
          (recur new-pos (if (= form discard-sentinel) forms (conj forms form))))))))

(defn rewrite-parser
  "Parser that conforms to the pipeline contract: (fn [tokens opts source] → forms).
   Uses the rewrite-based pipeline: tokens → tagged tree → rules → structures.
   Drop-in replacement for meme.alpha.parse.reader/read-meme-string-from-tokens.
   Passes opts through to transform-structures (supports :read-cond :preserve)."
  [tokens opts _source]
  (let [tagged (tokens->tree tokens)]
    (mapv (comp #(rules/transform-structures % opts)
                #(rewrite/rewrite rules/tree->s-rules %))
          tagged)))
