(ns meme.alpha.pipeline
  "Explicit pipeline composition: source → step-scan → step-parse → step-expand-syntax-quotes → forms.
   Each stage is a ctx → ctx function operating on a shared context map.

   Context map contract:

   | Key          | Type           | Written by    | Read by              |
   |--------------|----------------|---------------|----------------------|
   | :source      | String         | caller        | scan, parse          |
   | :opts        | Map or nil     | caller        | parse, expand        |
   | :raw-tokens  | Vector         | scan          | (tooling)            |
   | :tokens      | Vector         | scan          | parse                |
   | :forms       | Vector         | parse, expand | expand, caller       |

   :raw-tokens and :tokens are identical. Both keys are written by scan;
   :raw-tokens is retained so tooling that reads it continues to work.

   Stages are independent functions. Compose them in any order that respects
   the read/write dependencies above. Guest languages can:
   - Replace stages (e.g. a custom parser that reads :tokens, writes :forms)
   - Add stages (e.g. a rewrite stage that reads :forms, writes :forms)
   - Skip stages (e.g. skip expand for tooling that works with AST nodes)
   - Read intermediate state (e.g. :raw-tokens for syntax highlighting)

   See meme.alpha.pipeline.contract for formal clojure.spec definitions of
   the context map at each stage boundary. Enable runtime validation with:
     (binding [meme.alpha.pipeline.contract/*validate* true]
       (pipeline/run source))"
  (:require [meme.alpha.scan.tokenizer :as tokenizer]
            [meme.alpha.parse.reader :as reader]
            [meme.alpha.parse.expander :as expander]
            [meme.alpha.pipeline.contract :as contract]))

;; ---------------------------------------------------------------------------
;; Pipeline stages — each takes and returns a context map
;; ---------------------------------------------------------------------------

(defn step-scan
  "Tokenize source text into tokens.
   Attaches leading whitespace/comments to each token as :ws.
   Writes both :tokens (for parse) and :raw-tokens (backward compat / tooling)."
  [ctx]
  (contract/validate! :scan :input ctx)
  (let [source (:source ctx)]
    (when-not (string? source)
      (throw (ex-info (str "Pipeline :source must be a string, got " (if (nil? source) "nil" (type source)))
                      {})))
    (let [tokens (tokenizer/attach-whitespace (tokenizer/tokenize source) source)
          result (assoc ctx :raw-tokens tokens :tokens tokens)]
      (contract/validate! :scan :output result)
      result)))

(defn step-parse
  "Parse tokens into Clojure forms."
  [ctx]
  (contract/validate! :parse :input ctx)
  (when-not (:tokens ctx)
    (throw (ex-info "Pipeline :tokens missing — run scan before parse" {})))
  (let [result (assoc ctx :forms (reader/read-meme-string-from-tokens
                                   (:tokens ctx) (:opts ctx) (:source ctx)))]
    (contract/validate! :parse :output result)
    result))

(defn step-expand-syntax-quotes
  "Expand syntax-quote AST nodes and unwrap MemeRaw values in :forms.
   Produces plain Clojure forms ready for eval.
   Not needed for tooling that works with AST nodes directly."
  [ctx]
  (contract/validate! :expand :input ctx)
  (when-not (:forms ctx)
    (throw (ex-info "Pipeline :forms missing — run parse before expand" {})))
  (let [result (assoc ctx :forms (expander/expand-forms (:forms ctx) (:opts ctx)))]
    (contract/validate! :expand :output result)
    result))

;; ---------------------------------------------------------------------------
;; Pipeline composition
;; ---------------------------------------------------------------------------

(defn run
  "Run the reader pipeline: source → tokens → forms.
   Returns the context map with :source, :raw-tokens, :tokens, :forms.
   Does NOT expand syntax-quote nodes — call expand separately for eval,
   or use run-string which includes expansion.
   The scan stage attaches whitespace metadata (:ws) to tokens."
  ([source] (run source nil))
  ([source opts]
   (-> {:source source :opts opts}
       step-scan
       step-parse)))
