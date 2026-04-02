(ns meme.stages.contract
  "Formal contract for the meme stage context map.

   Defines clojure.spec.alpha specs for the context at each stage boundary,
   a toggleable runtime validator, and explain functions for debugging.

   Composable ctx → ctx stages:

     scan → parse → expand

   Context map contract:

   | Key          | Type           | Written by    | Read by              |
   |--------------|----------------|---------------|----------------------|
   | :source      | String         | caller        | scan, parse          |
   | :opts        | Map or nil     | caller        | parse, expand        |
   | :raw-tokens  | Vector         | scan          | (tooling)            |
   | :tokens      | Vector         | scan          | parse                |
   | :forms       | Vector         | parse, expand | expand, caller       |

   Guest languages that replace stages (e.g. a custom parser that reads
   :tokens and writes :forms) must produce context maps conforming to
   the relevant stage-output spec. Enable runtime validation during
   development:

     (binding [contract/*validate* true]
       (stages/run source))"
  (:require [clojure.spec.alpha :as s]))

;; ---------------------------------------------------------------------------
;; Token specs
;; ---------------------------------------------------------------------------

(def ^:private token-types
  "All token types emitted by the meme tokenizer."
  #{:symbol :number :string :keyword :char :regex
    :open-paren :close-paren :open-bracket :close-bracket
    :open-brace :close-brace :open-anon-fn :open-set
    :meta :quote :deref :syntax-quote :unquote :unquote-splicing
    :var-quote :discard :reader-cond-start :namespaced-map-start
    :tagged-literal})

(s/def :meme.token/type token-types)
(s/def :meme.token/value string?)
(s/def :meme.token/line pos-int?)
(s/def :meme.token/col pos-int?)
(s/def :meme.token/offset nat-int?)
(s/def :meme.token/end-line pos-int?)
(s/def :meme.token/end-col pos-int?)
(s/def :meme.token/end-offset nat-int?)
(s/def :meme.token/ws string?)

(s/def ::token
  (s/keys :req-un [:meme.token/type :meme.token/value
                   :meme.token/line :meme.token/col :meme.token/offset]
          :opt-un [:meme.token/end-line :meme.token/end-col
                   :meme.token/end-offset :meme.token/ws]))

(s/def ::token-vector (s/coll-of ::token :kind vector?))

;; ---------------------------------------------------------------------------
;; Opts specs
;; ---------------------------------------------------------------------------

(s/def :meme.opts/resolve-keyword ifn?)
(s/def :meme.opts/read-cond #{:preserve})
(s/def :meme.opts/resolve-symbol ifn?)
(s/def :meme.opts/parser ifn?)
(s/def :meme.opts/rewrite-rules (s/coll-of ifn?))
(s/def :meme.opts/rewrite-max-iters pos-int?)
(s/def :meme.opts/prelude (s/coll-of any?))
(s/def :meme.opts/eval ifn?)
(s/def :meme.opts/lang keyword?)

(s/def ::opts
  (s/nilable
   (s/keys :opt-un [:meme.opts/resolve-keyword
                    :meme.opts/read-cond
                    :meme.opts/resolve-symbol
                    :meme.opts/parser
                    :meme.opts/rewrite-rules
                    :meme.opts/rewrite-max-iters
                    :meme.opts/prelude
                    :meme.opts/eval
                    :meme.opts/lang])))

;; ---------------------------------------------------------------------------
;; Context map specs — per stage boundary
;; ---------------------------------------------------------------------------

(s/def ::source string?)
(s/def ::raw-tokens ::token-vector)
(s/def ::tokens ::token-vector)
(s/def ::forms (s/coll-of any? :kind vector?))

(s/def ::ctx-input
  (s/keys :req-un [::source]
          :opt-un [::opts]))

(s/def ::ctx-after-scan
  (s/keys :req-un [::source ::raw-tokens ::tokens]
          :opt-un [::opts]))

(s/def ::ctx-after-parse
  (s/keys :req-un [::source ::raw-tokens ::tokens ::forms]
          :opt-un [::opts]))

(s/def ::ctx-after-expand ::ctx-after-parse)

;; RT3-F19: rewrite stage has the same context shape as expand
(s/def ::ctx-after-rewrite ::ctx-after-parse)

;; ---------------------------------------------------------------------------
;; Stage ↔ spec lookup
;; ---------------------------------------------------------------------------

(def stage-input-spec
  "Map from stage keyword to the spec its input context must satisfy."
  {:scan          ::ctx-input
   :parse         ::ctx-after-scan
   :expand        ::ctx-after-parse
   :rewrite       ::ctx-after-expand})

(def stage-output-spec
  "Map from stage keyword to the spec its output context must satisfy."
  {:scan          ::ctx-after-scan
   :parse         ::ctx-after-parse
   :expand        ::ctx-after-expand
   :rewrite       ::ctx-after-rewrite})

;; ---------------------------------------------------------------------------
;; Toggle
;; ---------------------------------------------------------------------------

(def ^:dynamic *validate*
  "When true, stages validate their input and output context maps
   against the formal contract. Default: false (zero overhead).
   Bind to true for development, testing, or debugging guest parsers.

   Example:
     (binding [contract/*validate* true]
       (stages/run source))"
  false)

;; ---------------------------------------------------------------------------
;; Validation and explain
;; ---------------------------------------------------------------------------

(defn- spec-for [stage phase]
  (get (if (= phase :input) stage-input-spec stage-output-spec) stage))

(defn valid?
  "Check if ctx satisfies the contract for the given stage and phase.
   Does not throw. Not gated by *validate*."
  [stage phase ctx]
  (let [spec (spec-for stage phase)]
    (if spec
      (s/valid? spec ctx)
      true)))

(defn explain-context
  "Return a human-readable explanation of why ctx fails the contract
   for the given stage and phase (:input or :output).
   Returns nil if ctx is valid. Not gated by *validate*.

   Useful for tooling, REPL debugging, and guest language development."
  [stage phase ctx]
  (let [spec (spec-for stage phase)]
    (when (and spec (not (s/valid? spec ctx)))
      (with-out-str (s/explain spec ctx)))))

(defn validate!
  "Validate ctx against the contract for the given stage and phase.
   Throws ex-info with `:stage`, `:phase`, and `:problems` when
   *validate* is true and ctx fails spec. No-op when *validate* is false.

   Called automatically by stages when *validate* is true.
   Can also be called directly by guest language parsers."
  [stage phase ctx]
  (when *validate*
    (let [spec (spec-for stage phase)]
      (when (and spec (not (s/valid? spec ctx)))
        (throw (ex-info
                (str "Pipeline contract violation at "
                     (name stage) " " (name phase) ":\n"
                     (with-out-str (s/explain spec ctx)))
                {:stage    stage
                 :phase    phase
                 :problems (::s/problems (s/explain-data spec ctx))}))))))
