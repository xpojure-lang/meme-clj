(ns meme-lang.test-util
  "Shared .cljc test utilities for meme-lang tests."
  (:require [meme.tools.parser :as pratt]
            [meme-lang.grammar :as grammar]))

;; ---------------------------------------------------------------------------
;; CST → flat token extraction (for scanner-level tests)
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

    (:quote :deref :syntax-quote :unquote :unquote-splicing :var-quote :tagged)
    (let [tokens (add-token tokens (:token node))]
      (collect-tokens! tokens (:form node)))

    :discard
    ;; Discard nodes may carry extra #_ tokens and intermediate discarded forms
    ;; when consecutive #_s appear (e.g. `#_ #_ a b`). Walk all of them to
    ;; preserve the `source = (apply str (map :raw tokens))` invariant.
    (let [tokens (add-token tokens (:token node))
          tokens (reduce (fn [ts extra] (add-token ts extra))
                         tokens
                         (:extra-tokens node))
          tokens (reduce collect-tokens! tokens (:discarded-forms node))]
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

(defn tokenize
  "Parse source and extract a flat token vector from the CST.
   Structural invariant: (= source (apply str (map :raw tokens))).
   Used by scanner-level tests."
  [source]
  (if (zero? (count source))
    []
    (let [spec grammar/grammar
          cst (pratt/parse source spec)
          trailing (:trivia/after (meta cst))
          tokens (reduce collect-tokens! (transient []) cst)
          tokens (if trailing (reduce conj! tokens trailing) tokens)]
      (persistent! tokens))))
