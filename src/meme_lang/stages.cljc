(ns meme-lang.stages
  "Composable pipeline stages for the lossless reader.

   Pipeline: step-parse → step-read

   Each stage is a ctx → ctx function operating on a shared context map:

   | Key          | Type           | Written by  | Read by          |
   |--------------|----------------|-------------|------------------|
   | :source      | String         | caller      | parse            |
   | :opts        | Map or nil     | caller      | parse, read      |
   | :cst         | Vector         | parse       | read, (tooling)  |
   | :forms       | Vector         | read        | caller           |

   Stages are independent. Compose in any order respecting dependencies.
   Skip step-read for tooling that works with CST directly."
  (:require [clojure.string :as str]
            [meme-lang.grammar :as grammar]
            [meme-lang.cst-reader :as cst-reader]
            [meme-lang.expander :as expander]
            [meme.tools.parser :as pratt]))

;; ---------------------------------------------------------------------------
;; Pipeline stages
;; ---------------------------------------------------------------------------

(defn step-parse
  "Parse source string into CST using the unified Pratt parser.
   Uses meme grammar by default, or (:grammar opts) if provided.
   Reads :source, writes :cst."
  [ctx]
  (let [source (:source ctx)]
    (when-not (string? source)
      (throw (ex-info (str "Pipeline :source must be a string, got "
                           (if (nil? source) "nil"
                               #?(:clj (.getName (class source))
                                  :cljs (pr-str (type source)))))
                      {:type :meme/pipeline-error :stage :parse})))
    (let [spec (or (get-in ctx [:opts :grammar]) grammar/grammar)]
      (assoc ctx :cst (pratt/parse source spec)))))

(defn step-read
  "Lower CST to Clojure forms via the CST reader.
   Reads :cst, writes :forms."
  [ctx]
  (assoc ctx :forms (cst-reader/read-forms (:cst ctx) (:opts ctx))))

(defn step-expand-syntax-quotes
  "Expand syntax-quote AST nodes, unwrap MemeRaw values, and convert
   MemeAutoKeyword records to eval-able forms in :forms.
   Produces plain Clojure forms ready for eval."
  [ctx]
  (when-not (:forms ctx)
    (throw (ex-info "Pipeline :forms missing — run read before expand"
                    {:type :meme/pipeline-error :stage :expand})))
  (let [opts (cond-> (or (:opts ctx) {})
               (not (contains? (:opts ctx) :expand-auto-keywords))
               (assoc :expand-auto-keywords true))]
    (assoc ctx :forms (expander/expand-forms (:forms ctx) opts))))

(defn expand-syntax-quotes
  "Expand syntax-quote AST nodes in a seq of forms. Convenience wrapper
   around step-expand-syntax-quotes for callers that don't need the
   full pipeline context map."
  [forms opts]
  (:forms (step-expand-syntax-quotes
            {:source "" :cst [] :forms (vec forms) :opts opts})))

;; ---------------------------------------------------------------------------
;; Convenience: full pipeline
;; ---------------------------------------------------------------------------

(defn strip-shebang
  "Strip a leading #! shebang line from source."
  [source]
  (if (and (string? source) (str/starts-with? source "#!"))
    (let [nl (str/index-of source "\n")]
      (if nl (subs source (inc nl)) ""))
    source))

(defn run
  "Run the full pipeline: source → CST → forms."
  ([source] (run source nil))
  ([source opts]
   (-> {:source (strip-shebang source) :opts opts}
       step-parse
       step-read)))
