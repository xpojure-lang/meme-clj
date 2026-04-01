(ns meme.alpha.stages
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

   See meme.alpha.stages.contract for formal clojure.spec definitions of
   the context map at each stage boundary. Enable runtime validation with:
     (binding [meme.alpha.stages.contract/*validate* true]
       (stages/run source))"
  (:require [meme.alpha.forms :as forms]
            [meme.alpha.scan.tokenizer :as tokenizer]
            [meme.alpha.parse.reader :as reader]
            [meme.alpha.parse.expander :as expander]
            [meme.alpha.rewrite :as rewrite]
            [meme.alpha.stages.contract :as contract]))

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
  "Parse tokens into Clojure forms.
   If :parser is set in :opts, uses that function instead of the default
   meme parser. A custom parser has the signature:
     (fn [tokens opts source] -> forms-vector)"
  [ctx]
  (contract/validate! :parse :input ctx)
  (when-not (:tokens ctx)
    (throw (ex-info "Pipeline :tokens missing — run scan before parse" {})))
  (let [parse-fn (or (get-in ctx [:opts :parser])
                     reader/read-meme-string-from-tokens)
        result (assoc ctx :forms (parse-fn (:tokens ctx) (:opts ctx) (:source ctx)))]
    (contract/validate! :parse :output result)
    result))

(defn- expand-auto-keywords
  "Walk a form tree and expand MemeAutoKeyword records into eval-able
   (clojure.core/read-string \"::foo\") list forms. Called after syntax-quote
   expansion so the printer never sees the list encoding."
  [form]
  (cond
    (forms/deferred-auto-keyword? form)
    (forms/deferred-auto-keyword->form form)

    (seq? form)
    (with-meta (apply list (map expand-auto-keywords form)) (meta form))

    (vector? form)
    (with-meta (mapv expand-auto-keywords form) (meta form))

    (map? form)
    (with-meta (into {} (map (fn [[k v]] [(expand-auto-keywords k) (expand-auto-keywords v)]) form))
      (meta form))

    (set? form)
    (with-meta (set (map expand-auto-keywords form)) (meta form))

    :else form))

(defn step-expand-syntax-quotes
  "Expand syntax-quote AST nodes, unwrap MemeRaw values, and convert
   MemeAutoKeyword records to eval-able (read-string ...) forms in :forms.
   Produces plain Clojure forms ready for eval.
   Not needed for tooling that works with AST nodes directly."
  [ctx]
  (contract/validate! :expand :input ctx)
  (when-not (:forms ctx)
    (throw (ex-info "Pipeline :forms missing — run parse before expand" {})))
  (let [expanded (expander/expand-forms (:forms ctx) (:opts ctx))
        result (assoc ctx :forms (mapv expand-auto-keywords expanded))]
    (contract/validate! :expand :output result)
    result))

(defn step-rewrite
  "Apply rewrite rules to :forms. Rules come from :rewrite-rules in :opts.
   No-op if no rules are provided. Each form is rewritten independently."
  [ctx]
  (if-let [rules (get-in ctx [:opts :rewrite-rules])]
    (let [max-iters (or (get-in ctx [:opts :rewrite-max-iters]) 100)]
      (assoc ctx :forms (mapv #(rewrite/rewrite rules % max-iters) (:forms ctx))))
    ctx))

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
