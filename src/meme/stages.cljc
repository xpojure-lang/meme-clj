(ns meme.stages
  "Explicit stage composition: source → step-scan → step-parse → step-expand-syntax-quotes → forms.
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

   See meme.stages.contract for formal clojure.spec definitions of
   the context map at each stage boundary. Enable runtime validation with:
     (binding [meme.stages.contract/*validate* true]
       (stages/run source))"
  (:require [meme.scan.tokenizer :as tokenizer]
            [meme.parse.reader :as reader]
            [meme.parse.expander :as expander]
            [meme.rewrite :as rewrite]
            [meme.stages.contract :as contract]))

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
      (throw (ex-info (str "Pipeline :source must be a string, got "
                          (if (nil? source) "nil" #?(:clj (.getName (class source)) :cljs (pr-str (type source)))))
                      {:type :meme/pipeline-error :stage :scan})))
    (let [tokens (tokenizer/attach-whitespace (tokenizer/tokenize source) source)
          result (assoc ctx :raw-tokens tokens :tokens tokens)]
      (contract/validate! :scan :output result)
      result)))

(defn step-parse
  "Parse tokens into Clojure forms.
   If :parser is set in :opts, uses that function instead of the default
   meme parser. A custom parser has the signature:
     (fn [tokens opts source] -> forms-vector)"
  [ctx]
  (contract/validate! :parse :input ctx)
  (when-not (:tokens ctx)
    (throw (ex-info "Pipeline :tokens missing — run scan before parse" {:type :meme/pipeline-error :stage :parse})))
  (let [parse-fn (or (get-in ctx [:opts :parser])
                     reader/read-meme-string-from-tokens)
        result (assoc ctx :forms (parse-fn (:tokens ctx) (:opts ctx) (:source ctx)))]
    (contract/validate! :parse :output result)
    result))

(defn step-expand-syntax-quotes
  "Expand syntax-quote AST nodes, unwrap MemeRaw values, and convert
   MemeAutoKeyword records to eval-able (read-string ...) forms in :forms.
   Produces plain Clojure forms ready for eval. Single-pass — auto-keyword
   expansion is now handled inside expand-syntax-quotes.
   Not needed for tooling that works with AST nodes directly."
  [ctx]
  (contract/validate! :expand :input ctx)
  (when-not (:forms ctx)
    (throw (ex-info "Pipeline :forms missing — run parse before expand" {:type :meme/pipeline-error :stage :expand})))
  ;; RT3-F20: respect existing :expand-auto-keywords if explicitly set to false
  (let [opts (cond-> (or (:opts ctx) {})
               (not (contains? (:opts ctx) :expand-auto-keywords))
               (assoc :expand-auto-keywords true))
        result (assoc ctx :forms (expander/expand-forms (:forms ctx) opts))]
    (contract/validate! :expand :output result)
    result))

(defn step-rewrite
  "Apply rewrite rules to :forms. Rules come from :rewrite-rules in :opts.
   No-op if no rules are provided. Each form is rewritten independently."
  [ctx]
  ;; RT3-F19: validate input context
  (contract/validate! :rewrite :input ctx)
  ;; RT3-F29: guard missing :forms
  (when-not (:forms ctx)
    (throw (ex-info "Pipeline :forms missing — run parse before rewrite" {:type :meme/pipeline-error :stage :rewrite})))
  (if-let [rules (get-in ctx [:opts :rewrite-rules])]
    (let [max-iters (or (get-in ctx [:opts :rewrite-max-iters]) 100)
          result (assoc ctx :forms (mapv #(rewrite/rewrite rules % max-iters) (:forms ctx)))]
      (contract/validate! :rewrite :output result)
      result)
    ctx))

(defn expand-forms
  "Expand syntax-quote AST nodes in a seq of forms. Convenience wrapper
   around step-expand-syntax-quotes for callers that don't need the
   full pipeline context map."
  [forms opts]
  (:forms (step-expand-syntax-quotes
            {:source "" :raw-tokens [] :tokens [] :forms (vec forms) :opts opts})))

;; ---------------------------------------------------------------------------
;; Pipeline composition
;; ---------------------------------------------------------------------------

(defn run
  "Run the reader stages: source → tokens → forms.
   Returns the context map with :source, :raw-tokens, :tokens, :forms.
   Does NOT expand syntax-quote nodes — call expand separately for eval,
   or use run-string which includes expansion.
   The scan stage attaches whitespace metadata (:ws) to tokens."
  ([source] (run source nil))
  ([source opts]
   (-> {:source source :opts opts}
       step-scan
       step-parse)))
