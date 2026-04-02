(ns meme.trs
  "Token-stream term rewriting system.

   Three stages:
   1. Nest: group balanced delimiters into nested vectors
   2. Rewrite: apply declarative rules on nested structure
   3. Flatten: unnest back to flat token vector for emission

   Rules are pure data — patterns and replacements that the engine
   interprets. No lambdas in rule definitions.

   Pattern language (matches consecutive sibling nodes):
     {:bind :name}                          — match any node, bind
     {:bind :name :pred fn}                 — match node satisfying fn, bind
     {:bind :name :paren-group true :adj true} — match adjacent paren group, bind

   Replacement language (produces sibling nodes):
     {:ref :name}                           — emit bound node
     {:ref :name :strip-ws true}            — emit bound node, strip :ws
     {:paren-group [...] :ws-from :name}    — build paren group from parts
     {:body-of :name}                       — emit inner children of bound group
     {:body-of :name :ensure-ws str}        — emit inner children, ensure :ws on first"
  (:require [meme.scan.tokenizer :as tokenizer]))

;; ============================================================
;; Delimiter classification
;; ============================================================

(def ^:private openers #{:open-paren :open-bracket :open-brace :open-set :open-anon-fn})
(def ^:private closers #{:close-paren :close-bracket :close-brace})

;; ============================================================
;; Stage 1: Nest — group balanced delimiters
;; ============================================================

(defn- nest-tokens
  "Group balanced delimiters into nested vectors.
   Each delimited group becomes [opener-tok ...children closer-tok].
   Children may themselves be nested groups or atom tokens.
   Throws on unbalanced delimiters."
  [tokens]
  (let [stack
        (loop [i 0
               stack [[]]]
          (if (>= i (count tokens))
            stack
            (let [tok (nth tokens i)
                  typ (:type tok)]
              (cond
                (openers typ)
                (recur (inc i) (conj stack [tok]))

                (closers typ)
                (do (when (< (count stack) 2)
                      (throw (ex-info (str "Unexpected closing delimiter at line "
                                           (:line tok) ", col " (:col tok))
                                      {:type :unbalanced-close :token tok})))
                    (let [current (conj (peek stack) tok)
                          parent-stack (pop stack)
                          parent (peek parent-stack)]
                      (recur (inc i) (conj (pop parent-stack) (conj parent current)))))

                :else
                (recur (inc i) (conj (pop stack) (conj (peek stack) tok)))))))]
    (when (> (count stack) 1)
      (let [unclosed (first (peek stack))]
        (throw (ex-info (str "Unclosed delimiter at line "
                             (:line unclosed) ", col " (:col unclosed))
                        {:type :unclosed :token unclosed}))))
    (peek stack)))

;; ============================================================
;; Whitespace helpers for nested nodes
;; ============================================================

(defn- node-ws
  "Get the :ws of a node (atom token or first token of a nested group)."
  [node]
  (if (vector? node)
    (:ws (first node))
    (:ws node)))

(defn- strip-ws
  "Remove :ws from a node."
  [node]
  (if (vector? node)
    (into [(dissoc (first node) :ws)] (rest node))
    (dissoc node :ws)))

(defn- set-ws
  "Set :ws on a node."
  [node ws]
  (if (vector? node)
    (into [(assoc (first node) :ws ws)] (rest node))
    (assoc node :ws ws)))

(defn- ensure-ws
  "If a node has no :ws, set it to the given value."
  [node ws]
  (if (node-ws node) node (set-ws node ws)))

;; ============================================================
;; Node predicates (used in rules via :pred)
;; ============================================================

(def ^:private prefix-types
  #{:quote :deref :meta :syntax-quote :unquote :unquote-splicing
    :var-quote :discard :tagged-literal :reader-cond-start
    :namespaced-map-start})

(defn- valid-head?
  "Can this node be the head of an M-expression call?"
  [node]
  (if (map? node)
    (not (or (contains? prefix-types (:type node))
             (openers (:type node))))
    true))

;; ============================================================
;; Pattern matching (data-driven)
;; ============================================================

(defn- paren-group?
  "Is this node a paren-delimited group?"
  [node]
  (and (vector? node) (map? (first node)) (= :open-paren (:type (first node)))))

(defn- match-element
  "Match a single pattern element against a node. Returns bindings map or nil."
  [pat node]
  (cond
    ;; Paren-group matcher with adjacency
    (:paren-group pat)
    (when (paren-group? node)
      (when (or (not (:adj pat))
                (nil? (node-ws node)))
        (if (:bind pat)
          {(:bind pat) node}
          {})))

    ;; General node matcher
    (:bind pat)
    (let [pred (:pred pat)]
      (when (or (nil? pred) (pred node))
        {(:bind pat) node}))

    ;; Bare :any
    (= pat :any)
    {}))

(defn- match-pattern
  "Match a pattern against consecutive children starting at index i.
   Returns {:width n :bindings {...}} or nil."
  [pattern children i]
  (let [n (count pattern)]
    (when (<= (+ i n) (count children))
      (loop [pi 0
             bindings {}]
        (if (>= pi n)
          {:width n :bindings bindings}
          (when-let [b (match-element (nth pattern pi) (nth children (+ i pi)))]
            (recur (inc pi) (merge bindings b))))))))

;; ============================================================
;; Replacement emission (data-driven)
;; ============================================================

(defn- group-body
  "Extract the inner children of a paren group (between opener and closer)."
  [group]
  (subvec group 1 (dec (count group))))

(defn- emit-element
  "Emit one replacement element given bindings. Returns a seq of nodes."
  [elem bindings]
  (cond
    ;; Reference: emit bound node
    (:ref elem)
    (let [node (get bindings (:ref elem))]
      (cond
        (:strip-ws elem) [(strip-ws node)]
        (:ensure-ws elem) [(ensure-ws node (:ensure-ws elem))]
        :else [node]))

    ;; Body-of: emit inner children of a bound paren group
    (:body-of elem)
    (let [body (group-body (get bindings (:body-of elem)))]
      (if (and (:ensure-ws elem) (seq body))
        (into [(ensure-ws (first body) (:ensure-ws elem))] (rest body))
        body))

    ;; Paren-group: build a new paren group from parts
    (:paren-group elem)
    (let [parts (:paren-group elem)
          inner-nodes (into [] (mapcat #(emit-element % bindings)) parts)
          ws (when-let [ws-var (:ws-from elem)]
               (node-ws (get bindings ws-var)))
          opener (if ws
                   {:type :open-paren :value "(" :ws ws}
                   {:type :open-paren :value "("})
          closer {:type :close-paren :value ")"}]
      [(into [opener] (conj inner-nodes closer))])))

(defn- emit-replacement
  "Produce replacement nodes from a replacement template and bindings."
  [replacement bindings]
  (into [] (mapcat #(emit-element % bindings)) replacement))

;; ============================================================
;; Rules (pure data)
;; ============================================================

(defn- rule
  "Create a rewrite rule from pattern and replacement data."
  [pattern replacement]
  {:pattern pattern :replacement replacement})

(def ^:private m-call-rule
  "M-expression call: valid head followed by adjacent paren-group.
   head(args...) → (head args...)
   [x](args...)  → ([x] args...)"
  (rule
    [{:bind :head :pred valid-head?}
     {:bind :group :paren-group true :adj true}]
    [{:paren-group [{:ref :head :strip-ws true}
                    {:body-of :group :ensure-ws " "}]
      :ws-from :head}]))

;; ============================================================
;; Rewrite engine
;; ============================================================

(def ^:private ^:const max-retries-per-position 100)
(def ^:private ^:const max-total-rewrites 10000)

(defn- rewrite-level
  "Rewrite one level of nested children.
   Recurses into sub-groups first (bottom-up), then scans left-to-right
   applying rules at this level. After a match, re-checks the same position
   to support chained calls: f(x)(y) → ((f x) y)."
  [rules children]
  ;; Recurse into sub-groups
  (let [children (mapv (fn [c]
                         (if (vector? c)
                           (let [opener (first c)
                                 closer (peek c)
                                 inner (subvec c 1 (dec (count c)))
                                 rewritten (rewrite-level rules inner)
                                 ;; H1: #() body unwrap — if inside an anon-fn group and
                                 ;; rewriting produced a single paren group as the body,
                                 ;; unwrap it to avoid #((f x)) → should be #(f x).
                                 rewritten (if (and (= :open-anon-fn (:type opener))
                                                    (= 1 (count rewritten))
                                                    (paren-group? (first rewritten)))
                                             (group-body (first rewritten))
                                             rewritten)]
                             (into [opener] (conj rewritten closer)))
                           c))
                       children)]
    ;; Scan left-to-right, apply first matching rule.
    ;; After a successful match, re-check the same position — the replacement
    ;; may produce a new head for a chained call (e.g., f(x)(y) → ((f x) y)).
    ;; Two-tier safety: per-position cap catches local loops,
    ;; global cap catches aggregate runaway across all positions.
    (loop [i 0
           children children
           retries 0
           total 0]
      (if (>= i (count children))
        children
        (let [match (some (fn [{:keys [pattern] :as r}]
                            (when-let [m (match-pattern pattern children i)]
                              (assoc m :rule r)))
                          rules)]
          (if match
            (do (when (>= retries max-retries-per-position)
                  (throw (ex-info "TRS rewrite loop did not terminate at position"
                                  {:position i :retries retries})))
                (when (>= total max-total-rewrites)
                  (throw (ex-info "TRS rewrite exceeded global safety limit"
                                  {:position i :total total})))
                (let [result (emit-replacement (:replacement (:rule match)) (:bindings match))
                      width (:width match)
                      before (subvec children 0 i)
                      after (subvec children (+ i width))
                      new-children (into [] cat [before result after])]
                  (recur i new-children (inc retries) (inc total))))
            (recur (inc i) children 0 total)))))))

;; ============================================================
;; Default rule set
;; ============================================================

(def ^:private default-rules
  "Default rule set for M-expression → S-expression rewriting."
  [m-call-rule])

;; ============================================================
;; Stage 3: Flatten — unnest back to flat token vector
;; ============================================================

(defn- flatten-nested
  "Flatten a nested token structure back to a flat token vector."
  [nodes]
  (into []
    (mapcat (fn [node]
              (if (vector? node)
                (flatten-nested node)
                [node]))
            nodes)))

;; ============================================================
;; Public API
;; ============================================================

(defn- rewrite-meme->sexp
  "Rewrite a meme token stream to S-expression token structure.
   Nest → rewrite → flatten."
  ([tokens] (rewrite-meme->sexp tokens default-rules))
  ([tokens rules]
   (->> tokens nest-tokens (#(rewrite-level rules %)) flatten-nested)))

(defn- tokens->text
  "Reconstruct source text from a token vector, preserving whitespace."
  [tokens]
  (apply str
    (mapcat (fn [tok]
              (if-let [ws (:ws tok)]
                [ws (:value tok)]
                [(:value tok)]))
            tokens)))

(defn- tokenize-and-rewrite
  "Tokenize meme source and rewrite to S-expression token structure."
  [source]
  (let [tokens (tokenizer/attach-whitespace (tokenizer/tokenize source) source)]
    (rewrite-meme->sexp tokens)))

(defn meme->clj-text
  "Convert meme source to Clojure source text via token-stream rewriting.
   Known differences from classic parser:
   - L1: syntax-quote (`) and unquote (~) pass through as tokens for Clojure's
     reader to process. Classic expands them to seq/concat/list forms.
   - L2: ~x outside syntax-quote is not rejected (no AST-level context tracking).
     Classic rejects this at parse time."
  [source]
  (tokens->text (tokenize-and-rewrite source)))
