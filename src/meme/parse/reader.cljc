(ns meme.parse.reader
  "meme reader: recursive-descent parser.
   Transforms meme tokens into Clojure forms."
  (:require [clojure.string :as str]
            [meme.errors :as errors]
            [meme.forms :as forms]
            [meme.parse.resolve :as resolve]))

;; Sentinel for #_ discard. Contract:
;; - Returned by `parse-form-base` (via `:discard`) when the parsed form was a #_ discard
;; - `parse-form` passes it through (skips `parse-path-chain` for sentinels)
;; - Use `discard-sentinel?` to check — never use `identical?` directly
;; - MUST be filtered by every caller of `parse-form` that collects forms:
;;   1. `parse-forms-until` — filters in its accumulation loop
;;   2. `read-meme-string-from-tokens` — filters in its top-level loop
;;   3. Any new callsite of `parse-form` must handle this sentinel
;; - The `:open-anon-fn` handler rejects it (single-expression body cannot be discarded)
(def ^:private discard-sentinel #?(:clj (Object.) :cljs #js {}))

(defn- discard-sentinel?
  "Returns true if v is the discard sentinel. Use this instead of
   (identical? discard-sentinel v) to keep the contract grep-able."
  [v]
  (identical? discard-sentinel v))

;; ---------------------------------------------------------------------------
;; Parser state
;; ---------------------------------------------------------------------------

(def ^:private ^:const max-depth 512)

(defn- make-parser
  ([tokens] (make-parser tokens nil nil))
  ([tokens opts] (make-parser tokens opts nil))
  ([tokens opts source]
   {:tokens tokens :pos (volatile! 0) :depth (volatile! 0)
    :opts opts :source source :sq-depth (volatile! 0)}))

(defn- peof? [{:keys [tokens pos]}]
  (>= @pos (count tokens)))

(defn- ppeek
  ([p] (ppeek p 0))
  ([{:keys [tokens pos]} offset]
   (let [i (+ @pos offset)]
     (when (< i (count tokens)) (nth tokens i)))))

(defn- padvance! [{:keys [pos]}] (vswap! pos inc))

(defn- plast-loc
  "Location of the last consumed token, or {} if none."
  [{:keys [tokens pos]}]
  (let [i (dec @pos)]
    (if (and (>= i 0) (< i (count tokens)))
      (select-keys (nth tokens i) [:line :col])
      {})))

(defn- error-data
  "Merge source into error data map so meme-error can attach :source-context."
  [p data]
  (cond-> data
    (:source p) (assoc :source (:source p))))

(def ^:private token-name
  "Human-readable names for token types."
  {:close-paren  ")"
   :close-bracket "]"
   :close-brace  "}"
   :open-paren   "("
   :open-bracket "["
   :open-brace   "{"
   :open-set     "#{"
   :open-anon-fn "#("
   :symbol       "symbol"
   :keyword      "keyword"
   :number       "number"
   :string       "string"
   :char         "character"
   :regex        "regex"
   :deref        "@"
   :meta         "^"
   :quote        "'"
   :unquote      "~"
   :unquote-splicing "~@"
   :var-quote    "#'"
   :discard      "#_"
   :tagged-literal "tagged literal"
   :reader-cond-start "reader conditional"
   :namespaced-map-start "namespaced map prefix"
   :syntax-quote "syntax-quote"})

(def ^:private closer-name
  "Human-readable descriptions for closing delimiters."
  {:close-paren  "closing )"
   :close-bracket "closing ]"
   :close-brace  "closing }"})

(def ^:private closer-context
  "What structure each closer terminates."
  {:close-paren  "call"
   :close-bracket "vector"
   :close-brace  "map/set"})

(defn- describe-token [tok]
  (let [typ (:type tok)
        n (get token-name typ (name typ))]
    (if (#{:symbol :keyword :number :string :char :regex} typ)
      (str n " " (:value tok))
      n)))

(defn- tok-type? [tok typ]
  (and tok (= (:type tok) typ)))

(declare parse-form parse-form-base)

;; ---------------------------------------------------------------------------
;; Syntax-quote expansion — delegated to meme.parse.expander
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Splice result marker for #?@ inside collections
;; ---------------------------------------------------------------------------

(defn- splice-result
  "Wrap matched forms for #?@ splicing into surrounding collection.
   In Clojure, #?@(:clj [2 3]) inside [1 ... 4] splices to [1 2 3 4].
   The marker is detected by parse-forms-until which splices instead of conj'ing."
  [forms]
  (with-meta (vec forms) {::splice true}))

(defn- splice-result?
  "Is this form a splice marker from #?@ evaluation?"
  [form]
  (and (vector? form) (some? (meta form)) (::splice (meta form))))

;; ---------------------------------------------------------------------------
;; Collections
;; ---------------------------------------------------------------------------

(def ^:private closer-types
  "Set of token types that are closing delimiters."
  #{:close-paren :close-bracket :close-brace})

(defn- parse-forms-until
  ([p end-type] (parse-forms-until p end-type nil))
  ([p end-type open-loc]
   (let [end-pred #(= end-type (:type %))]
     (loop [forms []]
       (when (peof? p)
         (let [ctx (get closer-context end-type "expression")
               closer (get closer-name end-type (name end-type))]
           (errors/meme-error
            (str "Unclosed " ctx " — expected " closer " but reached end of input")
            (error-data p (cond-> (assoc (plast-loc p) :incomplete true)
                            open-loc (assoc :secondary [{:line (:line open-loc) :col (:col open-loc) :label "opened here"}])
                            open-loc (assoc :hint (str "Add " (get token-name end-type (name end-type)) " to close this " ctx)))))))
       (let [tok (ppeek p)]
         (cond
           ;; Correct closer — done
           (end-pred tok)
           (do (padvance! p) forms)

           ;; Wrong closer — mismatched delimiter
           (and (closer-types (:type tok)) (not (end-pred tok)))
           (let [expected (get token-name end-type (name end-type))
                 actual (get token-name (:type tok) (name (:type tok)))
                 ctx (get closer-context end-type "expression")]
             (errors/meme-error
              (str "Mismatched delimiter — expected " expected " to close " ctx " but got " actual)
              (error-data p (cond-> (select-keys tok [:line :col])
                              open-loc (assoc :secondary [{:line (:line open-loc) :col (:col open-loc) :label "opened here"}])
                              open-loc (assoc :hint (str "Replace " actual " with " expected " to close this " ctx))))))

           ;; Normal form — parse and accumulate
           :else
           (let [form (parse-form p)]
             (cond
               (discard-sentinel? form) (recur forms)
               (splice-result? form) (recur (into forms form))
               :else (recur (conj forms form))))))))))

(defn- parse-vector [p]
  (let [loc (select-keys (ppeek p) [:line :col])]
    (padvance! p) ; [
    (vec (parse-forms-until p :close-bracket loc))))

(defn- parse-map [p]
  (let [loc (select-keys (ppeek p) [:line :col])]
    (padvance! p) ; {
    (let [forms (parse-forms-until p :close-brace loc)]
      (when (odd? (count forms))
        (errors/meme-error (str "Map literal requires an even number of forms, but got " (count forms))
                           (error-data p (assoc loc :hint "Maps need key-value pairs — check for a missing key or value"))))
      (let [m (apply array-map forms)
            keys (take-nth 2 forms)]
        (when (not= (count m) (/ (count forms) 2))
          (let [dup (first (filter (fn [k] (> (count (filter #(= k %) keys)) 1)) keys))]
            (errors/meme-error (str "Duplicate key in map literal: " (pr-str dup))
                               (error-data p loc))))
        m))))

(defn- parse-set [p]
  (let [loc (select-keys (ppeek p) [:line :col])]
    (padvance! p) ; #{
    (let [forms (parse-forms-until p :close-brace loc)
          s (set forms)]
      (when (not= (count s) (count forms))
        (let [seen (volatile! #{})
              dup (first (filter (fn [x] (if (contains? @seen x) true (do (vswap! seen conj x) false))) forms))]
          (errors/meme-error (str "Duplicate element in set literal: " (pr-str dup))
                             (error-data p loc))))
      (with-meta s {:meme/order (vec forms)}))))

;; ---------------------------------------------------------------------------
;; Call: f(args...)
;; ---------------------------------------------------------------------------

(defn- parse-call-args [p]
  (let [loc (select-keys (ppeek p) [:line :col])]
    (padvance! p) ; (
    (parse-forms-until p :close-paren loc)))

;; ---------------------------------------------------------------------------
;; #() anonymous function — % param helpers
;; ---------------------------------------------------------------------------

;; find-percent-params, normalize-bare-percent, build-anon-fn-params
;; are in forms.cljc (shared with rewrite/rules.cljc)

;; ---------------------------------------------------------------------------
;; Main parse dispatch
;; ---------------------------------------------------------------------------

(defn- adjacent-open-paren?
  "Is the next token an ( with no preceding whitespace?
   Spacing is significant: f(x) is a call, f () is two separate forms."
  [p]
  (let [tok (ppeek p)]
    (and (tok-type? tok :open-paren)
         (not (:ws tok)))))

(defn- maybe-call
  "If next token is ( with no whitespace gap, parse call args and wrap."
  [p head]
  (if (adjacent-open-paren? p)
    (let [args (parse-call-args p)]
      (apply list head args))
    head))

(defn- parse-reader-cond-preserve
  "Parse #?(...) or #?@(...) in preserve mode — collect all branches,
   return a ReaderConditional object."
  [p loc splice?]
  (loop [pairs []]
    (cond
      (peof? p)
      (errors/meme-error "Unclosed reader conditional — expected ) but reached end of input"
                         (error-data p (cond-> (assoc loc :incomplete true)
                                         loc (assoc :secondary [{:line (:line loc) :col (:col loc) :label "opened here"}])
                                         loc (assoc :hint "Add ) to close this reader conditional"))))

      (tok-type? (ppeek p) :close-paren)
      (do (padvance! p)
          (forms/make-reader-conditional (apply list pairs) splice?))

      :else
      (let [key-tok (ppeek p)]
        (when-not (tok-type? key-tok :keyword)
          (errors/meme-error (str "Expected platform keyword in reader conditional, got " (describe-token key-tok))
                             (error-data p (select-keys key-tok [:line :col]))))
        (let [platform-key (keyword (subs (:value key-tok) 1))]
          (padvance! p)
          (let [form (parse-form p)]
            (when (discard-sentinel? form)
              (errors/meme-error
               (str "Reader conditional branch " platform-key " value was discarded by #_ — each branch requires a value")
               (error-data p (select-keys key-tok [:line :col]))))
            (recur (conj pairs platform-key form))))))))

(defn- parse-reader-cond-eval
  "Parse #?(...) or #?@(...) in evaluate mode — return matching platform's form."
  [p loc splice?]
  (let [platform #?(:clj :clj :cljs :cljs)]
    ;; Use discard-sentinel as the "no match" marker — nil can be a valid form value.
    (loop [matched discard-sentinel]
      (cond
        (peof? p)
        (errors/meme-error "Unclosed reader conditional — expected ) but reached end of input"
                           (error-data p (cond-> (assoc loc :incomplete true)
                                           loc (assoc :secondary [{:line (:line loc) :col (:col loc) :label "opened here"}])
                                           loc (assoc :hint "Add ) to close this reader conditional"))))

        (tok-type? (ppeek p) :close-paren)
        (do (padvance! p)
            (if (discard-sentinel? matched)
              ;; No match — consume any adjacent call args intended for the result.
              ;; e.g. #?(:cljs identity)(42) on JVM: discard (42) along with the head.
              (do (loop []
                    (when (adjacent-open-paren? p)
                      (parse-call-args p)
                      (recur)))
                  discard-sentinel)
              (if splice?
                (if (sequential? matched)
                  (splice-result matched)
                  (errors/meme-error
                   "Splicing reader conditional value must be a list or vector"
                   (error-data p loc)))
                (maybe-call p matched))))

        ;; Already matched — consume remaining forms permissively until ).
        ;; Matches Clojure's behavior: once a branch is selected, remaining
        ;; content is not validated for key-value pair structure. This handles
        ;; #_ read-through consuming a platform keyword as a branch value.
        (not (discard-sentinel? matched))
        (do (parse-form p)
            (recur matched))

        :else
        (let [key-tok (ppeek p)]
          (when-not (tok-type? key-tok :keyword)
            (errors/meme-error (str "Expected platform keyword in reader conditional, got " (describe-token key-tok))
                               (error-data p (select-keys key-tok [:line :col]))))
          (let [platform-key (keyword (subs (:value key-tok) 1))]
            (padvance! p)
            (when (or (peof? p) (tok-type? (ppeek p) :close-paren))
              (errors/meme-error
               (str "Missing value for " platform-key " in reader conditional")
               (error-data p (cond-> (select-keys key-tok [:line :col])
                               (peof? p) (assoc :incomplete true)))))
            (let [form (parse-form p)]
              (if (or (= platform-key platform) (= platform-key :default))
                (recur form)
                (recur matched)))))))))

(defn- parse-form-base
  "Parse a single meme form."
  [p]
  (let [tok (ppeek p)]
    (when-not tok
      (errors/meme-error "Unexpected end of input — expected a form" (error-data p (assoc (plast-loc p) :incomplete true))))
    (case (:type tok)
      :symbol
      (let [s (:value tok)]
        (padvance! p)
        (case s
          "nil" nil
          "true" true
          "false" false
          ;; all symbols — maybe call
          (maybe-call p (symbol s))))

      :keyword
      (let [v (:value tok)]
        (padvance! p)
        (if (str/starts-with? v "::")
          ;; Auto-resolve keywords — resolve at read time if resolver available,
          ;; otherwise defer to eval time via read-string wrapper
          (let [resolve-kw (:resolve-keyword (:opts p))]
            (maybe-call p (resolve/resolve-auto-keyword v (select-keys tok [:line :col]) resolve-kw)))
          (let [s (subs v 1)
                i (str/index-of s "/")]
            (maybe-call p (if (some? i)
                            (keyword (subs s 0 i) (subs s (inc i)))
                            (keyword s))))))

      :number
      (do (padvance! p)
          (resolve/resolve-number (:value tok) (select-keys tok [:line :col])))

      :string
      (do (padvance! p)
          (resolve/resolve-string (:value tok) (select-keys tok [:line :col])))

      :char
      (do (padvance! p)
          (resolve/resolve-char (:value tok) (select-keys tok [:line :col])))

      :regex
      (do (padvance! p)
          (resolve/resolve-regex (:value tok) (select-keys tok [:line :col])))

      :open-paren
      ;; () is the empty list. (content) without a head is an error.
      (let [loc (select-keys tok [:line :col])]
        (padvance! p)
        (if (tok-type? (ppeek p) :close-paren)
          (do (padvance! p) (list))
          (errors/meme-error
           "Bare parentheses not allowed — every (...) needs a head: symbol, keyword, or vector. Write f(x) not (f x). Use () for the empty list."
           (error-data p loc))))

      :open-bracket (maybe-call p (parse-vector p))
      :open-brace (maybe-call p (parse-map p))
      :open-set (maybe-call p (parse-set p))

      :deref
      (do (padvance! p)
          (let [inner (parse-form p)]
            (when (discard-sentinel? inner)
              (errors/meme-error "Deref target was discarded by #_ — nothing to dereference"
                                 (error-data p (select-keys tok [:line :col]))))
            (with-meta (list 'clojure.core/deref inner) {:meme/sugar true})))

      :meta
      (do (padvance! p)
          (let [m (parse-form p)
                _ (when (discard-sentinel? m)
                    (errors/meme-error "Metadata value was discarded by #_ — nothing to attach as metadata"
                                       (error-data p (select-keys tok [:line :col]))))
                target (parse-form p)
                _ (when (discard-sentinel? target)
                    (errors/meme-error "Metadata target was discarded by #_ — nothing to attach metadata to"
                                       (error-data p (select-keys tok [:line :col]))))
                entry (cond
                        (keyword? m) {m true}
                        (symbol? m)  {:tag m}
                        (map? m)     m
                        :else
                        (errors/meme-error
                         (str "Metadata must be a keyword, symbol, or map — got " (pr-str m))
                         (error-data p (select-keys tok [:line :col]))))
                chain (conj (or (:meme/meta-chain (meta target)) []) entry)]
            (vary-meta target merge entry {:meme/meta-chain chain})))

      :quote
      ;; ' quotes the next meme form. No S-expression escape hatch.
      ;; '() quotes the empty list, 'f(x) quotes the call (f x).
      (do (padvance! p)
          (let [inner (parse-form p)]
            (when (discard-sentinel? inner)
              (errors/meme-error "Quote target was discarded by #_ — nothing to quote"
                                 (error-data p (select-keys tok [:line :col]))))
            (with-meta (list 'quote inner) {:meme/sugar true})))

      :syntax-quote
      ;; ` — parse next form with meme rules, preserve as AST node.
      ;; Expansion to seq/concat/list happens at eval time, not read time.
      (do (padvance! p)
          (vswap! (:sq-depth p) inc)
          (let [form (try (parse-form p)
                          (finally (vswap! (:sq-depth p) dec)))]
            (when (discard-sentinel? form)
              (errors/meme-error "Syntax-quote target was discarded by #_ — nothing to syntax-quote"
                                 (error-data p (select-keys tok [:line :col]))))
            (forms/->MemeSyntaxQuote form)))

      :unquote
      (if (pos? @(:sq-depth p))
        (do (padvance! p)
            (let [inner (parse-form p)]
              (when (discard-sentinel? inner)
                (errors/meme-error "Unquote target was discarded by #_ — nothing to unquote"
                                   (error-data p (select-keys tok [:line :col]))))
              (with-meta (forms/->MemeUnquote inner)
                (select-keys tok [:line :col]))))
        (errors/meme-error "Unquote (~) outside syntax-quote — ~ only has meaning inside `"
                           (error-data p (select-keys tok [:line :col]))))

      :unquote-splicing
      (if (pos? @(:sq-depth p))
        (do (padvance! p)
            (let [inner (parse-form p)]
              (when (discard-sentinel? inner)
                (errors/meme-error "Unquote-splicing target was discarded by #_ — nothing to unquote-splice"
                                   (error-data p (select-keys tok [:line :col]))))
              (with-meta (forms/->MemeUnquoteSplicing inner)
                (select-keys tok [:line :col]))))
        (errors/meme-error "Unquote-splicing (~@) outside syntax-quote — ~@ only has meaning inside `"
                           (error-data p (select-keys tok [:line :col]))))

      :var-quote
      (do (padvance! p)
          (let [inner (parse-form p)]
            (when (discard-sentinel? inner)
              (errors/meme-error "Var-quote target was discarded by #_ — nothing to reference"
                                 (error-data p (select-keys tok [:line :col]))))
            (with-meta (list 'var inner) {:meme/sugar true})))

      :discard
      ;; #_ discards the next form and, if a non-boundary form follows,
      ;; returns it as this expression's value — so prefix operators
      ;; (@, ^, #', #()) transparently skip over #_-discarded forms.
      ;; At a boundary (EOF / closing delimiter), returns discard-sentinel
      ;; so callers that accumulate forms skip the gap.
      ;; #_ #_ chains work because each outer #_ discards the return
      ;; value of the inner #_ (itself a form or sentinel) and recurses.
      (do (padvance! p)
          (when (peof? p)
            (errors/meme-error "Missing form after #_ — expected a form to discard"
                               (error-data p (assoc (select-keys tok [:line :col]) :incomplete true))))
          (parse-form p) ; parse and discard
          (let [nxt (ppeek p)]
            (if (or (nil? nxt)
                    (#{:close-paren :close-bracket :close-brace} (:type nxt)))
              discard-sentinel
              (parse-form p))))

      :tagged-literal
      (let [tag (symbol (subs (:value tok) 1))]
        (padvance! p)
        (let [data (parse-form p)]
          (when (discard-sentinel? data)
            (errors/meme-error (str "Tagged literal #" tag " value was discarded by #_ — tagged literal requires a value")
                               (error-data p (select-keys tok [:line :col]))))
          (resolve/resolve-tagged-literal tag data (select-keys tok [:line :col]))))

      :namespaced-map-start
      ;; #:ns{...} — parse map natively, apply namespace prefix to bare keys
      (let [prefix (:value tok) ; e.g. "#:user" or "#::foo"
            ns-str (subs prefix 2) ; strip "#:"
            auto? (str/starts-with? ns-str ":")
            ns-name (if auto? (subs ns-str 1) ns-str)]
        (padvance! p)
        (when-not (tok-type? (ppeek p) :open-brace)
          (errors/meme-error (str "Expected { after " prefix)
                             (error-data p (select-keys tok [:line :col]))))
        (let [m (parse-map p) ; parse the {map} directly, no maybe-call
              apply-ns (fn [k]
                         (if (and (keyword? k) (nil? (namespace k)))
                           (keyword ns-name (name k))
                           k))
              nsed (into {} (map (fn [[k v]] [(apply-ns k) v]) m))
              tagged (vary-meta nsed assoc :meme/ns (if auto? (str ":" ns-name) ns-name))]
          (maybe-call p tagged)))

      :open-anon-fn
      ;; #() — parse body as meme, collect % params, emit (fn [params] body)
      (do (padvance! p)
          (let [body (parse-form p)
                _ (when (discard-sentinel? body)
                    (errors/meme-error "#() body was discarded — #() requires a non-discarded expression"
                                       (error-data p (select-keys tok [:line :col]))))
                nxt (ppeek p)]
            (cond
              (nil? nxt)
              (errors/meme-error "Unterminated #() — expected closing )"
                                 (error-data p (assoc (select-keys tok [:line :col]) :incomplete true)))

              (not (tok-type? nxt :close-paren))
              (errors/meme-error "#() body must be a single expression — use fn(args...) for multiple expressions"
                                 (error-data p (assoc (select-keys nxt [:line :col])
                                                      :secondary [{:line (:line tok) :col (:col tok) :label "#( opened here"}]))))
            (padvance! p)
            (let [params (forms/find-percent-params body)
                  _ (when (contains? params 0)
                      (errors/meme-error "%0 is not a valid parameter — use %1 or % for the first argument"
                                         (error-data p (select-keys tok [:line :col]))))
                  param-vec (forms/build-anon-fn-params params)
                  body' (forms/normalize-bare-percent body)]
              (with-meta (list 'fn param-vec body') {:meme/sugar true}))))

      :reader-cond-start
      ;; #?(...) or #?@(...) — parse natively.
      ;; With :read-cond :preserve, returns a ReaderConditional object.
      ;; Otherwise (default), evaluates and returns the matching platform's form.
      (let [prefix (:value tok)
            splice? (= prefix "#?@")]
        (padvance! p)
        (when-not (tok-type? (ppeek p) :open-paren)
          (errors/meme-error (str "Expected ( after " prefix)
                             (error-data p (select-keys tok [:line :col]))))
        (let [loc (select-keys (ppeek p) [:line :col])]
          (padvance! p) ; consume (
          (if (= :preserve (:read-cond (:opts p)))
            (parse-reader-cond-preserve p loc splice?)
            (parse-reader-cond-eval p loc splice?))))

      ;; default
      (errors/meme-error (str "Unexpected " (describe-token tok))
                         (error-data p (select-keys tok [:line :col]))))))

(defn- metadatable?
  "Can this value carry Clojure metadata?"
  [x]
  #?(:clj  (instance? clojure.lang.IObj x)
     :cljs (implements? IWithMeta x)))

(defn- attach-ws
  "Attach :ws metadata from a token to a form, if the form supports metadata."
  [form ws]
  (if (and ws (metadatable? form))
    (vary-meta form assoc :ws ws)
    form))

(defn- parse-call-chain
  "After parsing a form, check for chained call openers: f(x)(y) → ((f x) y).
   Handles arbitrary depth: f(x)(y)(z) → (((f x) y) z).
   Skipped for discard sentinels and splice results. Requires adjacent ( (no whitespace).
   Any value can be a call head — nil(x), true(x), false(x) produce (nil x), (true x), (false x).
   Chain length is capped at max-depth to prevent unbounded recursion."
  [p form]
  (loop [form form
         chain 0]
    (if (and (not (discard-sentinel? form))
             (not (splice-result? form))
             (adjacent-open-paren? p))
      (do
        (when (> chain max-depth)
          (errors/meme-error
           (str "Maximum call chain depth (" max-depth ") exceeded")
           (error-data p (select-keys (ppeek p) [:line :col]))))
        (let [args (parse-call-args p)]
          (recur (apply list form args) (inc chain))))
      form)))

(defn- parse-form
  "Parse a single meme form. Attaches :ws (leading whitespace/comments)
   from the token as metadata on the resulting form."
  [p]
  (let [ws (:ws (ppeek p))
        depth (vswap! (:depth p) inc)]
    (try
      (when (> depth max-depth)
        (errors/meme-error (str "Maximum nesting depth (" max-depth ") exceeded — input is too deeply nested")
                           (error-data p (merge {:depth depth} (when-let [tok (ppeek p)]
                                                                 (select-keys tok [:line :col]))))))
      (let [form (parse-form-base p)]
        (attach-ws (parse-call-chain p form) ws))
      (finally
        (vswap! (:depth p) dec)))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn read-meme-string-from-tokens
  "Parse pre-tokenized, pre-grouped tokens into Clojure forms.
   Used by the pipeline; most callers should use meme.core/meme->forms instead."
  ([tokens] (read-meme-string-from-tokens tokens nil nil))
  ([tokens opts] (read-meme-string-from-tokens tokens opts nil))
  ([tokens opts source]
   (let [p (make-parser tokens opts source)
         trailing (:trailing-ws (meta tokens))]
     (loop [forms []]
       (if (peof? p)
         (cond-> forms
           trailing (with-meta {:trailing-ws trailing}))
         (let [form (parse-form p)]
           (cond
             (discard-sentinel? form) (recur forms)
             (splice-result? form) (recur (into forms form))
             :else (recur (conj forms form)))))))))
