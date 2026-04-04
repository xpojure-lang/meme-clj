(ns meme.tools.reader.stages
  "Composable pipeline stages for the lossless reader.

   Pipeline: step-scan → step-trivia → step-parse → step-read

   Each stage is a ctx → ctx function operating on a shared context map:

   | Key          | Type           | Written by  | Read by          |
   |--------------|----------------|-------------|------------------|
   | :source      | String         | caller      | scan             |
   | :opts        | Map or nil     | caller      | read             |
   | :raw-tokens  | Vector         | scan        | trivia, (tooling)|
   | :tokens      | Vector         | trivia      | parse            |
   | :cst         | Vector         | parse       | read, (tooling)  |
   | :forms       | Vector         | read        | caller           |

   Stages are independent. Compose in any order respecting dependencies.
   Skip step-read for tooling that works with CST directly.
   Skip step-trivia to get raw token stream without trivia attachment."
  (:require [clojure.string :as str]
            [meme.tools.reader.tokenizer :as scanner]
            [meme.tools.reader.meme-grammar :as grammar]
            [meme.tools.reader.cst-reader :as cst-reader]
            [meme.tools.parse.expander :as expander]
            [meme.tools.pratt.trivia :as trivia]
            [meme.tools.pratt.parser :as pratt]))

;; ---------------------------------------------------------------------------
;; Default trivia set for parsing
;; ---------------------------------------------------------------------------

(def parse-trivia
  "Trivia types for the parsing pipeline. Comments, BOM, and shebang are
   trivia for parsing but not for all consumers (formatters, LSP, docs)."
  (into trivia/default-trivia-types [:comment :bom :shebang]))

;; ---------------------------------------------------------------------------
;; Pipeline stages
;; ---------------------------------------------------------------------------

(defn step-scan
  "Scan source into raw tokens. Exhaustive, never throws.
   Writes :raw-tokens to ctx."
  [ctx]
  (let [source (:source ctx)]
    (when-not (string? source)
      (throw (ex-info (str "Pipeline :source must be a string, got "
                           (if (nil? source) "nil"
                               #?(:clj (.getName (class source))
                                  :cljs (pr-str (type source)))))
                      {:type :meme/pipeline-error :stage :scan})))
    (assoc ctx :raw-tokens (scanner/tokenize source))))

(defn step-trivia
  "Attach trivia to semantic tokens. Uses parse-trivia by default,
   or (:trivia-types opts) if provided.
   Reads :raw-tokens, writes :tokens."
  [ctx]
  (let [trivia-types (or (get-in ctx [:opts :trivia-types]) parse-trivia)]
    (assoc ctx :tokens (trivia/attach-trivia (:raw-tokens ctx) trivia-types))))

(defn step-parse
  "Parse trivia-attached tokens into CST using the Pratt parser.
   Uses meme grammar by default, or (:grammar opts) if provided.
   Reads :tokens, writes :cst."
  [ctx]
  (let [spec (or (get-in ctx [:opts :grammar]) grammar/grammar)]
    (assoc ctx :cst (pratt/parse (:tokens ctx) spec))))

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
            {:source "" :raw-tokens [] :tokens [] :cst [] :forms (vec forms) :opts opts})))

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
  "Run the full pipeline: source → tokens → CST → forms."
  ([source] (run source nil))
  ([source opts]
   (-> {:source (strip-shebang source) :opts opts}
       step-scan
       step-trivia
       step-parse
       step-read)))
