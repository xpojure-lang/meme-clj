(ns beme.alpha.parse.reader
  "beme reader: recursive-descent parser.
   Transforms beme tokens into Clojure forms."
  (:require [clojure.string :as str]
            [beme.alpha.errors :as errors]
            [beme.alpha.parse.resolve :as resolve]))

;; Sentinel for #_ discard. Contract:
;; - Returned by `parse-form-base` (via `:discard`) when the parsed form was a #_ discard
;; - `parse-form` passes it through (skips `parse-path-chain` for sentinels)
;; - Use `discard-sentinel?` to check — never use `identical?` directly
;; - MUST be filtered by every caller of `parse-form` that collects forms:
;;   1. `parse-forms-until` — filters in its accumulation loop
;;   2. `read-beme-string-from-tokens` — filters in its top-level loop
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
  ([tokens] {:tokens tokens :pos (volatile! 0) :depth (volatile! 0) :clj-mode (volatile! false)})
  ([tokens opts] {:tokens tokens :pos (volatile! 0) :depth (volatile! 0) :opts opts :clj-mode (volatile! false)}))

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
   :reader-cond-raw "reader conditional"
   :reader-cond-start "reader conditional"
   :namespaced-map-raw "namespaced map"
   :namespaced-map-start "namespaced map prefix"
   :syntax-quote-raw "syntax-quote"
   :syntax-quote-start "syntax-quote"
   :close-end    "end"})

(def ^:private closer-name
  "Human-readable descriptions for closing delimiters."
  {:close-paren  "closing )"
   :close-bracket "closing ]"
   :close-brace  "closing }"
   :close-end    "end"})

(def ^:private closer-context
  "What structure each closer terminates."
  {:close-paren  "call"
   :close-bracket "vector"
   :close-brace  "map/set"
   :close-end    "call"})

(defn- begin-symbol? [tok]
  (and (= :symbol (:type tok)) (= "begin" (:value tok))))

(defn- end-symbol? [tok]
  (and (= :symbol (:type tok)) (= "end" (:value tok))))

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
;; Collections
;; ---------------------------------------------------------------------------

(defn- parse-forms-until
  ([p end-type] (parse-forms-until p end-type nil))
  ([p end-type open-loc]
   (let [end-pred (if (= end-type :close-end)
                    end-symbol?
                    #(= end-type (:type %)))]
     (loop [forms []]
       (when (peof? p)
         (let [ctx (get closer-context end-type "expression")
               closer (get closer-name end-type (name end-type))]
           (errors/beme-error
             (str "Unclosed " ctx " — expected " closer " but reached end of input")
             (cond-> (assoc (plast-loc p) :incomplete true)
               open-loc (assoc :secondary [{:line (:line open-loc) :col (:col open-loc) :label "opened here"}])
               open-loc (assoc :hint (str "Add " (get token-name end-type (name end-type)) " to close this " ctx))))))
       (let [tok (ppeek p)]
         (if (end-pred tok)
           (do (padvance! p) forms)
           (let [form (parse-form p)]
             (if (discard-sentinel? form)
               (recur forms)
               (recur (conj forms form))))))))))

(defn- parse-vector [p]
  (let [loc (select-keys (ppeek p) [:line :col])]
    (padvance! p) ; [
    (vec (parse-forms-until p :close-bracket loc))))

(defn- parse-map [p]
  (let [loc (select-keys (ppeek p) [:line :col])]
    (padvance! p) ; {
    (let [forms (parse-forms-until p :close-brace loc)]
      (when (odd? (count forms))
        (errors/beme-error (str "Map literal requires an even number of forms, but got " (count forms))
                           (assoc loc :hint "Maps need key-value pairs — check for a missing key or value")))
      (let [m (apply array-map forms)]
        (when (not= (count m) (/ (count forms) 2))
          (errors/beme-error "Duplicate key in map literal"
                             loc))
        m))))

(defn- parse-set [p]
  (let [loc (select-keys (ppeek p) [:line :col])]
    (padvance! p) ; #{
    (let [forms (parse-forms-until p :close-brace loc)
          s (set forms)]
      (when (not= (count s) (count forms))
        (errors/beme-error "Duplicate element in set literal"
                           loc))
      s)))

;; ---------------------------------------------------------------------------
;; Call: f(args...) or f begin args... end
;; ---------------------------------------------------------------------------

(defn- call-opener? [tok]
  (or (tok-type? tok :open-paren)
      (begin-symbol? tok)))

(defn- parse-call-args [p]
  (let [tok (ppeek p)
        begin? (begin-symbol? tok)
        closer-type (if begin? :close-end :close-paren)
        loc (select-keys tok [:line :col])]
    (padvance! p) ; ( or begin
    (parse-forms-until p closer-type loc)))


;; ---------------------------------------------------------------------------
;; #() anonymous function — % param helpers
;; ---------------------------------------------------------------------------

(defn- char-code* [ch]
  #?(:clj (int ch) :cljs (.charCodeAt ch 0)))

(def ^:private code-0* (char-code* \0))
(def ^:private code-9* (char-code* \9))

(defn- percent-param-type
  "If sym is a % parameter symbol, return its type: :bare, :rest, or the integer N."
  [sym]
  (when (symbol? sym)
    (let [n (name sym)]
      (cond
        (= n "%") :bare
        (= n "%&") :rest
        (and (str/starts-with? n "%")
             (> (count n) 1)
             (every? #(let [c (char-code* %)] (and (>= c code-0*) (<= c code-9*))) (seq (subs n 1))))
        (#?(:clj Long/parseLong :cljs #(js/parseInt % 10)) (subs n 1))
        :else nil))))

(defn- find-percent-params
  "Walk form collecting % param types. Skips nested (fn ...) bodies."
  [form]
  (cond
    (symbol? form)
    (if-let [p (percent-param-type form)] #{p} #{})

    (and (seq? form) (= 'fn (first form)))
    #{} ; don't recurse into nested fn / inner #()

    (seq? form)
    (reduce into #{} (map find-percent-params form))

    (vector? form)
    (reduce into #{} (map find-percent-params form))

    (map? form)
    (reduce into #{} (mapcat (fn [[k v]] [(find-percent-params k) (find-percent-params v)]) form))

    (set? form)
    (reduce into #{} (map find-percent-params form))

    #?@(:clj [(tagged-literal? form)
              (find-percent-params (.-form form))])

    :else #{}))

(defn- normalize-bare-percent
  "Replace bare % with %1 in form. Skips nested (fn ...) bodies."
  [form]
  (cond
    (and (symbol? form) (= "%" (name form))) (symbol "%1")

    (and (seq? form) (= 'fn (first form)))
    form ; don't recurse into nested fn

    (seq? form)
    (apply list (map normalize-bare-percent form))

    (vector? form)
    (mapv normalize-bare-percent form)

    (map? form)
    (into {} (map (fn [[k v]] [(normalize-bare-percent k) (normalize-bare-percent v)]) form))

    (set? form)
    (set (map normalize-bare-percent form))

    #?@(:clj [(tagged-literal? form)
              (tagged-literal (.-tag form) (normalize-bare-percent (.-form form)))])

    :else form))

(defn- build-anon-fn-params
  "Build [%1 %2 ...] or [%1 & %&] param vector from collected param types."
  [param-set]
  (let [has-bare? (contains? param-set :bare)
        has-rest? (contains? param-set :rest)
        nums (filter number? param-set)
        max-n (if (seq nums) (apply max nums) (if has-bare? 1 0))]
    (cond-> (mapv #(symbol (str "%" %)) (range 1 (inc max-n)))
      has-rest? (into ['& (symbol "%&")]))))


;; ---------------------------------------------------------------------------
;; Main parse dispatch
;; ---------------------------------------------------------------------------

(defn- maybe-call
  "If next token is ( or begin, parse call args and wrap — spacing is irrelevant.
   In clj-mode (inside quoted lists), never forms calls — returns head as-is."
  [p head]
  (if @(:clj-mode p)
    head
    (if (call-opener? (ppeek p))
      (let [args (parse-call-args p)]
        (apply list head args))
      head)))

(defn- parse-form-base
  "Parse a single beme form."
  [p]
  (let [tok (ppeek p)]
    (when-not tok
      (errors/beme-error "Unexpected end of input — expected a form" (assoc (plast-loc p) :incomplete true)))
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
      (if @(:clj-mode p)
        ;; In clj-mode (inside quoted lists), bare parens create lists
        (let [loc (select-keys tok [:line :col])]
          (padvance! p)
          (apply list (parse-forms-until p :close-paren loc)))
        (errors/beme-error
          "Bare parentheses not allowed — every (...) needs a head: symbol, keyword, or vector. Write f(x) not (f x)."
          (select-keys tok [:line :col])))

      :open-bracket (maybe-call p (parse-vector p))
      :open-brace (maybe-call p (parse-map p))
      :open-set (maybe-call p (parse-set p))

      :deref
      (do (padvance! p)
          (let [inner (parse-form p)]
            (when (discard-sentinel? inner)
              (errors/beme-error "Deref target was discarded by #_ — nothing to dereference"
                                (select-keys tok [:line :col])))
            (list 'clojure.core/deref inner)))

      :meta
      (do (padvance! p)
          (let [m (parse-form p)
                _ (when (discard-sentinel? m)
                    (errors/beme-error "Metadata value was discarded by #_ — nothing to attach as metadata"
                                      (select-keys tok [:line :col])))
                target (parse-form p)
                _ (when (discard-sentinel? target)
                    (errors/beme-error "Metadata target was discarded by #_ — nothing to attach metadata to"
                                      (select-keys tok [:line :col])))]
            (vary-meta target merge (cond
                                        (keyword? m) {m true}
                                        (symbol? m)  {:tag m}
                                        (map? m)     m
                                        :else
                                        (errors/beme-error
                                          (str "Metadata must be a keyword, symbol, or map — got " (pr-str m))
                                          (select-keys tok [:line :col]))))))

      :quote
      (do (padvance! p)
          (if (tok-type? (ppeek p) :open-paren)
            ;; '(...) — Clojure S-expression syntax inside quoted lists.
            ;; Activates clj-mode: bare parens create lists, symbols don't
            ;; trigger Rule 1 calls. This matches Clojure's quote semantics.
            (let [paren-loc (select-keys (ppeek p) [:line :col])
                  prev-mode @(:clj-mode p)]
              (padvance! p)
              (vreset! (:clj-mode p) true)
              (let [forms (try
                            (parse-forms-until p :close-paren paren-loc)
                            (finally
                              (vreset! (:clj-mode p) prev-mode)))]
                (list 'quote (apply list forms))))
            (let [inner (parse-form p)]
              (when (discard-sentinel? inner)
                (errors/beme-error "Quote target was discarded by #_ — nothing to quote"
                                  (select-keys tok [:line :col])))
              (list 'quote inner))))

      :syntax-quote-raw
      ;; ` forms are opaque — pass through to Clojure's reader
      (let [raw (:value tok)]
        (padvance! p)
        (maybe-call p (resolve/resolve-syntax-quote raw (select-keys tok [:line :col]))))

      :unquote
      (errors/beme-error "Unquote (~) outside syntax-quote — ~ only has meaning inside `"
                         (select-keys tok [:line :col]))

      :unquote-splicing
      (errors/beme-error "Unquote-splicing (~@) outside syntax-quote — ~@ only has meaning inside `"
                         (select-keys tok [:line :col]))

      :var-quote
      (do (padvance! p)
          (let [inner (parse-form p)]
            (when (discard-sentinel? inner)
              (errors/beme-error "Var-quote target was discarded by #_ — nothing to reference"
                                (select-keys tok [:line :col])))
            (list 'var inner)))

      :discard
      ;; #_ consumes two forms: the discarded one, then its replacement.
      ;; At a boundary (EOF / closing delimiter), returns discard-sentinel
      ;; so callers that accumulate forms can skip the gap.
      ;; Otherwise, returns the next real form — this is essential for
      ;; prefix operators (@, ^, #', #()) which call parse-form expecting
      ;; a value. It also makes #_ #_ chains work: each #_ in the chain
      ;; discards the form returned by the inner #_ and returns the next.
      (do (padvance! p)
          (when (peof? p)
            (errors/beme-error "Missing form after #_ — expected a form to discard"
                               (assoc (select-keys tok [:line :col]) :incomplete true)))
          (parse-form p) ; parse and discard
          (let [nxt (ppeek p)]
            (if (or (nil? nxt)
                    (#{:close-paren :close-bracket :close-brace} (:type nxt))
                    (end-symbol? nxt))
              discard-sentinel
              (parse-form p))))

      :tagged-literal
      (let [tag (symbol (subs (:value tok) 1))]
        (padvance! p)
        (let [data (parse-form p)]
          (when (discard-sentinel? data)
            (errors/beme-error (str "Tagged literal #" tag " value was discarded by #_ — tagged literal requires a value")
                               (select-keys tok [:line :col])))
          (resolve/resolve-tagged-literal tag data (select-keys tok [:line :col]))))

      :namespaced-map-raw
      ;; #:ns{} forms are opaque — pass through to Clojure's reader
      (let [raw (:value tok)]
        (padvance! p)
        (maybe-call p (resolve/resolve-namespaced-map raw (select-keys tok [:line :col]))))

      :open-anon-fn
      ;; #() — parse body as beme, collect % params, emit (fn [params] body)
      (do (padvance! p)
          (let [body (parse-form p)
                _ (when (discard-sentinel? body)
                    (errors/beme-error "#() body was discarded — #() requires a non-discarded expression"
                                      (select-keys tok [:line :col])))
                nxt (ppeek p)]
            (cond
              (nil? nxt)
              (errors/beme-error "Unterminated #() — expected closing )"
                                (assoc (select-keys tok [:line :col]) :incomplete true))

              (not (tok-type? nxt :close-paren))
              (errors/beme-error "#() body must be a single expression — use fn(args...) for multiple expressions"
                                (select-keys nxt [:line :col])))
            (padvance! p)
            (let [params (find-percent-params body)
                  _ (when (contains? params 0)
                      (errors/beme-error "%0 is not a valid parameter — use %1 or % for the first argument"
                                         (select-keys tok [:line :col])))
                  param-vec (build-anon-fn-params params)
                  body' (normalize-bare-percent body)]
              (list 'fn param-vec body'))))

      :reader-cond-raw
      ;; #? forms are opaque — pass through to Clojure's reader
      (let [raw (:value tok)]
        (padvance! p)
        (maybe-call p (resolve/resolve-reader-cond raw (select-keys tok [:line :col]))))

      ;; default
      (errors/beme-error (str "Unexpected " (describe-token tok))
                         (select-keys tok [:line :col])))))

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
   Skipped in clj-mode (inside quoted lists) and for discard sentinels."
  [p form]
  (if (and (not @(:clj-mode p))
           (not (discard-sentinel? form))
           (call-opener? (ppeek p)))
    (let [args (parse-call-args p)]
      (recur p (apply list form args)))
    form))

(defn- parse-form
  "Parse a single beme form. Attaches :ws (leading whitespace/comments)
   from the token as metadata on the resulting form."
  [p]
  (let [ws (:ws (ppeek p))
        depth (vswap! (:depth p) inc)]
    (try
      (when (> depth max-depth)
        (errors/beme-error (str "Maximum nesting depth (" max-depth ") exceeded — input is too deeply nested")
                           (merge {:depth depth} (when-let [tok (ppeek p)]
                                               (select-keys tok [:line :col])))))
      (let [form (parse-form-base p)]
        (attach-ws (parse-call-chain p form) ws))
      (finally
        (vswap! (:depth p) dec)))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn read-beme-string-from-tokens
  "Parse pre-tokenized, pre-grouped tokens into Clojure forms.
   Used by the pipeline; most callers should use beme.alpha.core/beme->forms instead."
  ([tokens] (read-beme-string-from-tokens tokens nil nil))
  ([tokens opts] (read-beme-string-from-tokens tokens opts nil))
  ([tokens opts _source]
   (let [p (make-parser tokens opts)
         trailing (:trailing-ws (meta tokens))]
     (loop [forms []]
       (if (peof? p)
         (cond-> forms
           trailing (with-meta {:trailing-ws trailing}))
         (let [form (parse-form p)]
           (if (discard-sentinel? form)
             (recur forms)
             (recur (conj forms form)))))))))
