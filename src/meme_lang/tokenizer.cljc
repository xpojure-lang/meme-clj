(ns meme-lang.tokenizer
  "Backward-compatible tokenizer API.

   Delegates to the unified Pratt parser engine for scanning. Produces a flat
   token vector with the same partition invariant as the original tokenizer:
   (= source (apply str (map :raw tokens))).

   The actual scanning logic lives in meme-lang.grammar as
   character-dispatched scanlets."
  (:require [meme.tools.parser :as pratt]
            [meme-lang.grammar :as grammar]))

;; ---------------------------------------------------------------------------
;; CST → flat token extraction
;; ---------------------------------------------------------------------------

(defn- add-token [tokens tok]
  (if-let [trivia (:trivia/before tok)]
    (let [tokens (reduce conj! tokens trivia)]
      (conj! tokens (dissoc tok :trivia/before)))
    (conj! tokens tok)))

(defn- collect-tokens!
  "Walk a CST node and collect all tokens into a transient vector."
  [tokens node]
  (case (:node node)
    :atom
    (add-token tokens (:token node))

    :call
    (let [tokens (collect-tokens! tokens (:head node))
          tokens (add-token tokens (:open node))
          tokens (reduce collect-tokens! tokens (:args node))]
      (if-let [close (:close node)]
        (add-token tokens close)
        tokens))

    (:vector :map :set :anon-fn)
    (let [tokens (add-token tokens (:open node))
          tokens (reduce collect-tokens! tokens (:children node))]
      (if-let [close (:close node)]
        (add-token tokens close)
        tokens))

    (:quote :deref :syntax-quote :unquote :unquote-splicing :var-quote :discard :tagged)
    (let [tokens (add-token tokens (:token node))]
      (collect-tokens! tokens (:form node)))

    :meta
    (let [tokens (add-token tokens (:token node))
          tokens (collect-tokens! tokens (:meta node))]
      (collect-tokens! tokens (:target node)))

    (:namespaced-map :reader-cond)
    (let [tokens (add-token tokens (:ns node))]
      (if-let [open (:open node)]
        (let [tokens (add-token tokens open)
              tokens (reduce collect-tokens! tokens (:children node))]
          (if-let [close (:close node)]
            (add-token tokens close)
            tokens))
        tokens))

    (:list)
    (let [tokens (add-token tokens (:open node))]
      (if-let [close (:close node)]
        (add-token tokens close)
        tokens))

    :error
    (if-let [tok (:token node)]
      (add-token tokens tok)
      (if-let [open (:open node)]
        (let [tokens (add-token tokens open)
              tokens (reduce collect-tokens! tokens (or (:children node) []))]
          (if-let [close (:close node)]
            (add-token tokens close)
            tokens))
        tokens))

    ;; Default — try to extract what we can
    (if-let [tok (:token node)]
      (add-token tokens tok)
      tokens)))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn tokenize
  "Tokenize source string into a flat vector of tokens.
   Structural invariant: (= source (apply str (map :raw tokens))).
   Never throws — invalid input produces :invalid tokens."
  [source]
  (if (zero? (count source))
    []
    (let [spec grammar/grammar
          cst (pratt/parse source spec)
          trailing (:trivia/after (meta cst))
          tokens (reduce collect-tokens! (transient []) cst)
          tokens (if trailing (reduce conj! tokens trailing) tokens)]
      (persistent! tokens))))
