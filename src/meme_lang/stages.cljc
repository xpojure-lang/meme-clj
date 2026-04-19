(ns meme-lang.stages
  "Composable pipeline stages for the lossless reader.

   Tooling pipeline: step-parse → step-read
   Eval pipeline:    step-parse → step-read → step-evaluate-reader-conditionals → step-expand-syntax-quotes

   Each stage is a ctx → ctx function operating on a shared context map:

   | Key          | Type           | Written by  | Read by                |
   |--------------|----------------|-------------|------------------------|
   | :source      | String         | caller      | parse                  |
   | :opts        | Map or nil     | caller      | parse, read, eval-rc   |
   | :cst         | Vector         | parse       | read, (tooling)        |
   | :forms       | Vector         | read        | caller, eval-rc, expand|

   The table above is mirrored as machine-readable data in `stage-contracts`.
   Each stage calls `check-contract!` at entry, so miscomposed pipelines
   (e.g. calling step-read before step-parse) throw a clear pipeline-error
   with the missing key(s) and the actual ctx-keys present, instead of
   surfacing deep-inside NPEs.

   Stages are independent.  Compose in any order respecting dependencies.
   Skip step-read for tooling that works with CST directly."
  (:require [clojure.string :as str]
            [meme-lang.grammar :as grammar]
            [meme-lang.cst-reader :as cst-reader]
            [meme-lang.errors :as errors]
            [meme-lang.expander :as expander]
            [meme-lang.forms :as forms]
            [meme.tools.parser :as pratt]))

;; ---------------------------------------------------------------------------
;; Stage contracts — machine-readable required/produced ctx keys
;; ---------------------------------------------------------------------------

(def stage-contracts
  "Machine-readable pipeline contract.  Each entry is
   `{:requires #{ctx-keys} :produces #{ctx-keys}}`.

   `:requires` is enforced at runtime by `check-contract!`; `:produces`
   is documentation — if a stage fails to produce what it claims, the
   next stage's `:requires` check catches the mistake, so post-condition
   runtime checks would be redundant."
  {:step-parse                         {:requires #{:source} :produces #{:cst}}
   :step-read                          {:requires #{:cst}    :produces #{:forms}}
   :step-evaluate-reader-conditionals  {:requires #{:forms}  :produces #{:forms}}
   :step-expand-syntax-quotes          {:requires #{:forms}  :produces #{:forms}}})

(defn- check-contract!
  "Throw a clear pipeline-error if `ctx` is missing any of `stage-name`'s
   required keys.  Keeps miscomposition errors near the actual mistake."
  [stage-name ctx]
  (when-let [required (get-in stage-contracts [stage-name :requires])]
    (let [missing (remove #(contains? ctx %) required)]
      (when (seq missing)
        (throw (ex-info
                 (str "Pipeline stage " stage-name " missing required ctx key(s): "
                      (str/join ", " (map pr-str missing))
                      ". Did you skip an earlier stage?")
                 {:type :meme-lang/pipeline-error
                  :stage stage-name
                  :missing (vec missing)
                  :ctx-keys (vec (keys ctx))}))))))

;; ---------------------------------------------------------------------------
;; Pipeline stages
;; ---------------------------------------------------------------------------

(defn step-parse
  "Parse source string into CST using the unified Pratt parser.
   Uses meme grammar by default, or (:grammar opts) if provided.
   Reads :source, writes :cst."
  [ctx]
  (check-contract! :step-parse ctx)
  (let [source (:source ctx)]
    (when-not (string? source)
      (throw (ex-info (str "Pipeline :source must be a string, got "
                           (if (nil? source) "nil"
                               #?(:clj (.getName (class source))
                                  :cljs (pr-str (type source)))))
                      {:type :meme-lang/pipeline-error :stage :step-parse})))
    (let [spec (or (get-in ctx [:opts :grammar]) grammar/grammar)]
      (assoc ctx :cst (pratt/parse source spec)))))

(defn step-read
  "Lower CST to Clojure forms via the CST reader.
   Reads :cst, writes :forms."
  [ctx]
  (check-contract! :step-read ctx)
  (assoc ctx :forms (cst-reader/read-forms (:cst ctx) (:opts ctx))))

;; ---------------------------------------------------------------------------
;; step-evaluate-reader-conditionals — pick the platform branch of #?, splice #?@
;; ---------------------------------------------------------------------------
;;
;; The reader unconditionally produces MemeReaderConditional records (preserve
;; semantics). This stage materializes the platform branch: #? is replaced by
;; the matched form (or removed); #?@ splices its matched sequence into the
;; parent collection. Composed by eval paths; skipped by tooling paths.
;;
;; Native Clojure evaluates `#?` at reader time, *before* syntax-quote is
;; processed. We match that order by running this step before
;; `step-expand-syntax-quotes`. The walker therefore recurses into
;; MemeSyntaxQuote / MemeUnquote / MemeUnquoteSplicing interiors so that
;; `` `#?(:clj x :cljs y) `` collapses to `` `x `` on JVM just as Clojure would.
;; ---------------------------------------------------------------------------

(declare ^:private walk-rc)

(defn- eval-rc
  "Evaluate a MemeReaderConditional for `platform`.
   Returns a vector of 0-or-more forms (splicing produces many, no-match zero).
   Follows Clojure's precedence: named platform key first, then :default."
  [rc platform]
  (let [branches (seq (forms/rc-form rc))
        splicing? (forms/rc-splicing? rc)]
    (when (odd? (count branches))
      (errors/meme-error "Reader conditional must contain an even number of forms"
                         (select-keys (meta rc) [:line :col])))
    (let [pairs (partition 2 branches)
          matched (or (some (fn [[k v]] (when (= k platform) v)) pairs)
                      (some (fn [[k v]] (when (= k :default) v)) pairs))
          has-match? (or (some (fn [[k _]] (= k platform)) pairs)
                         (some (fn [[k _]] (= k :default)) pairs))]
      (cond
        (not has-match?) []
        splicing?
        (if (sequential? matched)
          (vec (mapcat #(walk-rc % platform) matched))
          (errors/meme-error
            "Splicing reader conditional (#?@) matched a non-sequential value"
            (select-keys (meta rc) [:line :col])))
        :else (walk-rc matched platform)))))

(defn- walk-rc
  "Walk a form, evaluating any MemeReaderConditional records for `platform`.
   Returns a vector of 0-or-more forms (splicing support).
   Recurses into all collections and into MemeSyntaxQuote / MemeUnquote /
   MemeUnquoteSplicing interiors so that #? inside ` is evaluated just like
   native Clojure does at reader time."
  [form platform]
  (cond
    (forms/meme-reader-conditional? form)
    (eval-rc form platform)

    ;; AST wrappers — recurse into :form; each expects a single result.
    (forms/syntax-quote? form)
    (let [inner (walk-rc (:form form) platform)]
      (case (count inner)
        0 []
        1 [(with-meta (forms/->MemeSyntaxQuote (first inner)) (meta form))]
        (errors/meme-error
          "Splicing reader conditional produced multiple forms inside syntax-quote"
          (select-keys (meta form) [:line :col]))))

    (forms/unquote? form)
    (let [inner (walk-rc (:form form) platform)]
      (case (count inner)
        0 []
        1 [(with-meta (forms/->MemeUnquote (first inner)) (meta form))]
        (errors/meme-error
          "Splicing reader conditional produced multiple forms inside unquote"
          (select-keys (meta form) [:line :col]))))

    (forms/unquote-splicing? form)
    (let [inner (walk-rc (:form form) platform)]
      (case (count inner)
        0 []
        1 [(with-meta (forms/->MemeUnquoteSplicing (first inner)) (meta form))]
        (errors/meme-error
          "Splicing reader conditional produced multiple forms inside unquote-splicing"
          (select-keys (meta form) [:line :col]))))

    ;; Atomic wrappers — leaves, do not recurse.
    (forms/raw? form) [form]
    (forms/deferred-auto-keyword? form) [form]

    ;; Collections — rebuild via mapcat so splicing flows naturally.
    (seq? form)
    [(with-meta (apply list (mapcat #(walk-rc % platform) form)) (meta form))]

    (vector? form)
    [(with-meta (vec (mapcat #(walk-rc % platform) form)) (meta form))]

    (map? form)
    (let [flat (mapcat identity form)
          walked (vec (mapcat #(walk-rc % platform) flat))]
      (when (odd? (count walked))
        (errors/meme-error
          "Reader conditional in map produced odd-count key-value sequence"
          (select-keys (meta form) [:line :col])))
      [(with-meta (apply array-map walked) (meta form))])

    (set? form)
    [(with-meta (set (mapcat #(walk-rc % platform) form)) (meta form))]

    #?@(:clj [(tagged-literal? form)
              [(tagged-literal (.-tag ^clojure.lang.TaggedLiteral form)
                               (let [inner (walk-rc (.-form ^clojure.lang.TaggedLiteral form) platform)]
                                 (case (count inner)
                                   0 nil
                                   1 (first inner)
                                   (errors/meme-error
                                     "Splicing reader conditional produced multiple forms inside tagged literal"
                                     {}))))]])

    ;; Atom — return unchanged.
    :else [form]))

(def ^:private current-platform
  "Compile-time platform tag used as the default :platform opt."
  #?(:clj :clj :cljs :cljs))

(defn step-evaluate-reader-conditionals
  "Evaluate MemeReaderConditional records in :forms for the target platform.

   Replaces each #? with its matched branch value (or removes it if no branch
   matches). Splices each #?@ match into its containing collection.
   Recurses into syntax-quote / unquote / unquote-splicing interiors, matching
   native Clojure's order where the reader evaluates #? before ` is processed.

   opts (from ctx's :opts):
     :platform — :clj, :cljs, or any platform keyword. Default: current
                 compile-time platform. Pass explicitly to materialize forms
                 for a different target (e.g. generating .cljs output on JVM).

   Reads :forms, writes :forms."
  [ctx]
  (check-contract! :step-evaluate-reader-conditionals ctx)
  (let [platform (or (get-in ctx [:opts :platform]) current-platform)]
    (assoc ctx :forms (into [] (mapcat #(walk-rc % platform)) (:forms ctx)))))

(defn step-expand-syntax-quotes
  "Expand syntax-quote AST nodes, unwrap MemeRaw values, and convert
   MemeAutoKeyword records to eval-able forms in :forms.
   Produces plain Clojure forms ready for eval."
  [ctx]
  (check-contract! :step-expand-syntax-quotes ctx)
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
    (let [lf (str/index-of source "\n")
          cr (str/index-of source "\r")
          nl (cond
               (and lf cr) (min lf cr)
               lf lf
               cr cr
               :else nil)]
      (if nl
        (let [next (inc nl)]
          ;; Skip full \r\n pair when present
          (if (and (= (.charAt ^String source nl) \return)
                   (< next (count source))
                   (= (.charAt ^String source next) \newline))
            (subs source (inc next))
            (subs source next)))
        ""))
    source))

(defn run
  "Run the full pipeline: source → CST → forms."
  ([source] (run source nil))
  ([source opts]
   (-> {:source (strip-shebang source) :opts opts}
       step-parse
       step-read)))
