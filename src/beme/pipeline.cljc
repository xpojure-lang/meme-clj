(ns beme.pipeline
  "Explicit pipeline composition: source → scan → group → parse → resolve → forms.
   Each stage is a ctx -> ctx function operating on a shared context map."
  (:require [beme.tokenizer :as tokenizer]
            [beme.grouper :as grouper]
            [beme.reader :as reader]))

;; ---------------------------------------------------------------------------
;; Pipeline stages — each takes and returns a context map
;; ---------------------------------------------------------------------------

(defn scan
  "Tokenize source text into flat tokens (no structural grouping).
   Attaches leading whitespace/comments to each token as :ws."
  [ctx]
  (let [source (:source ctx)
        tokens (tokenizer/tokenize source)]
    (assoc ctx :raw-tokens (tokenizer/attach-whitespace tokens source))))

(defn group
  "Collapse opaque regions (reader conditionals, namespaced maps,
   syntax-quote brackets) from flat tokens into composite tokens."
  [ctx]
  (assoc ctx :tokens (grouper/group-tokens (:raw-tokens ctx) (:source ctx))))

(defn parse
  "Parse grouped tokens into Clojure forms."
  [ctx]
  (assoc ctx :forms (reader/read-beme-string-from-tokens
                      (:tokens ctx) (:opts ctx) (:source ctx))))

;; ---------------------------------------------------------------------------
;; Pipeline composition
;; ---------------------------------------------------------------------------

(defn run
  "Run the full pipeline: source → tokens → grouped → forms.
   Returns the context map with :source, :raw-tokens, :tokens, :forms.
   Unlike beme.core/beme->forms, the pipeline attaches whitespace metadata
   (:ws) to tokens via the scan stage — present on both :raw-tokens and
   :tokens (the grouper preserves :ws on pass-through tokens)."
  ([source] (run source nil))
  ([source opts]
   (-> {:source source :opts opts}
       scan
       group
       parse)))
