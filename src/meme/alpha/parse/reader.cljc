(ns meme.alpha.parse.reader
  "meme reader: recursive-descent parser.
   Transforms meme tokens into Clojure forms."
  (:require [clojure.string :as str]
            [meme.alpha.errors :as errors]
            [meme.alpha.forms :as forms]
            [meme.alpha.parse.resolve :as resolve]))

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
;; Syntax-quote expansion
;; ---------------------------------------------------------------------------

;; Marker types for unquote/unquote-splicing inside syntax-quote.
;; These are recognized by expand-sq during the form walk.
(defrecord Unquote [form])
(defrecord UnquoteSplicing [form])

(defn- sq-resolve-symbol
  "Resolve a symbol for syntax-quote. Uses the resolver from parser opts
   if available, otherwise returns the symbol as-is (best effort)."
  [sym opts]
  (if-let [resolver (:resolve-symbol opts)]
    (resolver sym)
    ;; Without a resolver, qualify symbols that have no namespace
    ;; but look like they could be vars. Leave special forms alone.
    (if (and (nil? (namespace sym))
             (not (#{'if 'do 'let 'fn 'var 'quote 'loop 'recur 'throw 'try 'catch
                     'finally 'def 'new 'set! 'monitor-enter 'monitor-exit
                     'letfn 'case 'deftype 'reify} sym)))
      sym ; can't resolve without ns context — leave as-is
      sym)))

(def ^:private ^:dynamic *gensym-env* nil)

(defn- sq-gensym
  "Auto-gensym: foo# → foo__NNN__auto__. Same foo# in one syntax-quote
   resolves to the same gensym."
  [sym]
  (let [n (name sym)]
    (if (str/ends-with? n "#")
      (let [base (subs n 0 (dec (count n)))]
        (if-let [existing (get @*gensym-env* sym)]
          existing
          (let [gs (symbol (str base "__" (gensym) "__auto__"))]
            (vswap! *gensym-env* assoc sym gs)
            gs)))
      sym)))

(defn- expand-sq
  "Walk a form parsed inside syntax-quote and produce the expansion.
   Mirrors Clojure's SyntaxQuoteReader behavior."
  [form opts loc]
  (cond
    ;; Unquote: ~x → x (the value, not quoted)
    (instance? Unquote form)
    (:form form)

    ;; UnquoteSplicing at top level is an error (must be inside a collection)
    (instance? UnquoteSplicing form)
    (errors/meme-error "Unquote-splicing (~@) not in collection" loc)

    ;; Symbol — resolve and quote
    (symbol? form)
    (list 'quote (sq-gensym (sq-resolve-symbol form opts)))

    ;; List — expand to (seq (concat ...))
    (seq? form)
    (if (empty? form)
      (list 'clojure.core/list)
      (let [items (map (fn [item]
                         (cond
                           (instance? UnquoteSplicing item)
                           (:form item)
                           (instance? Unquote item)
                           (list 'clojure.core/list (:form item))
                           :else
                           (list 'clojure.core/list (expand-sq item opts loc))))
                       form)]
        (list 'clojure.core/seq (cons 'clojure.core/concat items))))

    ;; Vector — expand to (vec (concat ...))
    (vector? form)
    (let [items (map (fn [item]
                       (cond
                         (instance? UnquoteSplicing item)
                         (:form item)
                         (instance? Unquote item)
                         (list 'clojure.core/list (:form item))
                         :else
                         (list 'clojure.core/list (expand-sq item opts loc))))
                     form)]
      (list 'clojure.core/apply 'clojure.core/vector (cons 'clojure.core/concat items)))

    ;; Map — expand to (apply hash-map (concat ...))
    (map? form)
    (let [items (mapcat (fn [[k v]]
                          [(list 'clojure.core/list (expand-sq k opts loc))
                           (list 'clojure.core/list (expand-sq v opts loc))])
                        form)]
      (list 'clojure.core/apply 'clojure.core/hash-map (cons 'clojure.core/concat items)))

    ;; Set — expand to (apply hash-set (concat ...))
    (set? form)
    (let [items (map (fn [item]
                       (cond
                         (instance? UnquoteSplicing item)
                         (:form item)
                         :else
                         (list 'clojure.core/list (expand-sq item opts loc))))
                     form)]
      (list 'clojure.core/apply 'clojure.core/hash-set (cons 'clojure.core/concat items)))

    ;; Keyword, number, string, char, nil, boolean — self-quoting
    :else form))

;; ---------------------------------------------------------------------------
;; Collections
;; ---------------------------------------------------------------------------

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
        (errors/meme-error (str "Map literal requires an even number of forms, but got " (count forms))
                           (error-data p (assoc loc :hint "Maps need key-value pairs — check for a missing key or value"))))
      (let [m (apply array-map forms)]
        (when (not= (count m) (/ (count forms) 2))
          (errors/meme-error "Duplicate key in map literal"
                             (error-data p loc)))
        m))))

(defn- parse-set [p]
  (let [loc (select-keys (ppeek p) [:line :col])]
    (padvance! p) ; #{
    (let [forms (parse-forms-until p :close-brace loc)
          s (set forms)]
      (when (not= (count s) (count forms))
        (errors/meme-error "Duplicate element in set literal"
                           (error-data p loc)))
      s)))

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
      (errors/meme-error (str "Unclosed reader conditional — expected )")
                         (error-data p (assoc loc :incomplete true)))

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
            (recur (conj pairs platform-key form))))))))

(defn- parse-reader-cond-eval
  "Parse #?(...) or #?@(...) in evaluate mode — return matching platform's form."
  [p loc splice?]
  (let [platform #?(:clj :clj :cljs :cljs)]
    (loop [matched nil]
      (cond
        (peof? p)
        (errors/meme-error (str "Unclosed reader conditional — expected )")
                           (error-data p (assoc loc :incomplete true)))

        (tok-type? (ppeek p) :close-paren)
        (do (padvance! p)
            (if splice?
              (if matched matched (list))
              (maybe-call p (if matched matched (list)))))

        :else
        (let [key-tok (ppeek p)]
          (when-not (tok-type? key-tok :keyword)
            (errors/meme-error (str "Expected platform keyword in reader conditional, got " (describe-token key-tok))
                               (error-data p (select-keys key-tok [:line :col]))))
          (let [platform-key (keyword (subs (:value key-tok) 1))]
            (padvance! p)
            (let [form (parse-form p)]
              (if (and (nil? matched)
                       (or (= platform-key platform) (= platform-key :default)))
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
            (list 'clojure.core/deref inner)))

      :meta
      (do (padvance! p)
          (let [m (parse-form p)
                _ (when (discard-sentinel? m)
                    (errors/meme-error "Metadata value was discarded by #_ — nothing to attach as metadata"
                                      (error-data p (select-keys tok [:line :col]))))
                target (parse-form p)
                _ (when (discard-sentinel? target)
                    (errors/meme-error "Metadata target was discarded by #_ — nothing to attach metadata to"
                                      (error-data p (select-keys tok [:line :col]))))]
            (vary-meta target merge (cond
                                        (keyword? m) {m true}
                                        (symbol? m)  {:tag m}
                                        (map? m)     m
                                        :else
                                        (errors/meme-error
                                          (str "Metadata must be a keyword, symbol, or map — got " (pr-str m))
                                          (error-data p (select-keys tok [:line :col])))))))

      :quote
      ;; ' quotes the next meme form. No S-expression escape hatch.
      ;; '() quotes the empty list, 'f(x) quotes the call (f x).
      (do (padvance! p)
          (let [inner (parse-form p)]
            (when (discard-sentinel? inner)
              (errors/meme-error "Quote target was discarded by #_ — nothing to quote"
                                (error-data p (select-keys tok [:line :col]))))
            (list 'quote inner)))

      :syntax-quote
      ;; ` — parse next form with meme rules, then expand
      (do (padvance! p)
          (vswap! (:sq-depth p) inc)
          (let [form (try (parse-form p)
                          (finally (vswap! (:sq-depth p) dec)))]
            (when (discard-sentinel? form)
              (errors/meme-error "Syntax-quote target was discarded by #_ — nothing to syntax-quote"
                                (error-data p (select-keys tok [:line :col]))))
            (binding [*gensym-env* (volatile! {})]
              (maybe-call p (expand-sq form (:opts p) (select-keys tok [:line :col]))))))

      :unquote
      (if (pos? @(:sq-depth p))
        (do (padvance! p)
            (let [inner (parse-form p)]
              (when (discard-sentinel? inner)
                (errors/meme-error "Unquote target was discarded by #_"
                                  (error-data p (select-keys tok [:line :col]))))
              (->Unquote inner)))
        (errors/meme-error "Unquote (~) outside syntax-quote — ~ only has meaning inside `"
                           (error-data p (select-keys tok [:line :col]))))

      :unquote-splicing
      (if (pos? @(:sq-depth p))
        (do (padvance! p)
            (let [inner (parse-form p)]
              (when (discard-sentinel? inner)
                (errors/meme-error "Unquote-splicing target was discarded by #_"
                                  (error-data p (select-keys tok [:line :col]))))
              (->UnquoteSplicing inner)))
        (errors/meme-error "Unquote-splicing (~@) outside syntax-quote — ~@ only has meaning inside `"
                           (error-data p (select-keys tok [:line :col]))))

      :var-quote
      (do (padvance! p)
          (let [inner (parse-form p)]
            (when (discard-sentinel? inner)
              (errors/meme-error "Var-quote target was discarded by #_ — nothing to reference"
                                (error-data p (select-keys tok [:line :col]))))
            (list 'var inner)))

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
              nsed (into {} (map (fn [[k v]] [(apply-ns k) v]) m))]
          (maybe-call p nsed)))

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
                                (error-data p (select-keys nxt [:line :col]))))
            (padvance! p)
            (let [params (find-percent-params body)
                  _ (when (contains? params 0)
                      (errors/meme-error "%0 is not a valid parameter — use %1 or % for the first argument"
                                         (error-data p (select-keys tok [:line :col]))))
                  param-vec (build-anon-fn-params params)
                  body' (normalize-bare-percent body)]
              (list 'fn param-vec body'))))

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
   Skipped for discard sentinels. Requires adjacent ( (no whitespace)."
  [p form]
  (if (and (not (discard-sentinel? form))
           (adjacent-open-paren? p))
    (let [args (parse-call-args p)]
      (recur p (apply list form args)))
    form))

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
   Used by the pipeline; most callers should use meme.alpha.core/meme->forms instead."
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
           (if (discard-sentinel? form)
             (recur forms)
             (recur (conj forms form)))))))))
