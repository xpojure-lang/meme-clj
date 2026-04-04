(ns meme.tools.pratt.parser
  "Lossless Pratt parser with parselet functions.

   A parselet is a function that knows how to parse one syntactic construct.
   Nud parselets handle tokens in prefix position: (fn [p tok] → CST node).
   Led parselets handle tokens in infix/postfix position: (fn [p lhs tok] → CST node).

   The language spec maps token types to parselets:
     {:nud {token-type parselet-fn ...}
      :led [{:token-type t :bp n :when pred :fn parselet-fn} ...]}

   Parselet factories (nud-atom, nud-prefix, nud-delimited, etc.) generate
   common parselet patterns without boilerplate. Custom parselets are plain
   functions — no special protocol needed.")

;; ---------------------------------------------------------------------------
;; CST node constructor
;; ---------------------------------------------------------------------------

(defn cst
  "Create a CST node."
  [type fields]
  (assoc fields :node type))

;; ---------------------------------------------------------------------------
;; Parser state
;; ---------------------------------------------------------------------------

(defn- make-parser [tokens spec]
  {:tokens tokens :pos (volatile! 0) :spec spec})

(defn- ppeek [{:keys [tokens pos]}]
  (let [i @pos]
    (when (< i (count tokens)) (nth tokens i))))

(defn- padvance! [{:keys [tokens pos]}]
  (let [i @pos]
    (when (< i (count tokens))
      (vswap! pos inc)
      (nth tokens i))))

(defn- peof? [{:keys [tokens pos]}]
  (>= @pos (count tokens)))

(defn- expect! [p expected-type]
  (let [tok (ppeek p)]
    (when (and tok (= expected-type (:type tok)))
      (padvance! p))))

;; ---------------------------------------------------------------------------
;; Forward declaration
;; ---------------------------------------------------------------------------

(declare parse-expr)

;; ---------------------------------------------------------------------------
;; Generic parse helpers
;; ---------------------------------------------------------------------------

(defn parse-until
  "Parse expressions until a closing token type. Returns [nodes close-tok]."
  [p close-type]
  (loop [nodes []]
    (let [tok (ppeek p)]
      (cond
        (nil? tok)                   [nodes nil]
        (= close-type (:type tok))   [nodes (padvance! p)]
        :else (recur (conj nodes (parse-expr p 0)))))))

;; ---------------------------------------------------------------------------
;; Parselet factories — generate common nud/led functions
;; ---------------------------------------------------------------------------

(defn nud-atom
  "Factory: atom parselet (leaf node, no operands)."
  [node-type]
  (fn [_p tok]
    (cst node-type {:token tok})))

(defn nud-prefix
  "Factory: prefix parselet (consume 1 operand)."
  ([node-type] (nud-prefix node-type 0))
  ([node-type bp]
   (fn [p tok]
     (let [form (parse-expr p bp)]
       (cst node-type {:token tok :form form})))))

(defn nud-prefix-two
  "Factory: prefix parselet consuming 2 operands (e.g., metadata)."
  ([node-type first-key second-key] (nud-prefix-two node-type first-key second-key 0))
  ([node-type first-key second-key bp]
   (fn [p tok]
     (let [a (parse-expr p bp)
           b (parse-expr p bp)]
       (cst node-type {:token tok first-key a second-key b})))))

(defn nud-delimited
  "Factory: delimited parselet (consume children until close token)."
  [node-type close-type]
  (fn [p open-tok]
    (let [[children close-tok] (parse-until p close-type)]
      (cst node-type {:open open-tok :children children :close close-tok}))))

(defn nud-empty-or-error
  "Factory: empty-or-error parselet (empty list or bare parens error)."
  [empty-node-type close-type error-message]
  (fn [p open-tok]
    (let [close-tok (expect! p close-type)]
      (if close-tok
        (cst empty-node-type {:open open-tok :close close-tok})
        (let [[children close-tok] (parse-until p close-type)]
          (cst :error {:message error-message
                       :open open-tok :children children :close close-tok}))))))

(defn nud-prefixed-delimited
  "Factory: prefixed-delimited parselet (prefix token + delimited body).
   extra-fn: optional (fn [prefix-tok] → extra-fields-map)."
  ([node-type open-type close-type] (nud-prefixed-delimited node-type open-type close-type nil))
  ([node-type open-type close-type extra-fn]
   (fn [p ns-tok]
     (let [open-tok (expect! p open-type)
           [children close-tok] (if open-tok
                                  (parse-until p close-type)
                                  [[] nil])]
       (cst node-type (merge {:ns ns-tok :open open-tok
                              :children children :close close-tok}
                             (when extra-fn (extra-fn ns-tok))))))))

(defn led-call
  "Factory: call parselet (head + delimited args)."
  [node-type close-type]
  (fn [p lhs open-tok]
    (let [[args close-tok] (parse-until p close-type)]
      (cst node-type {:head lhs :open open-tok :args args :close close-tok}))))

(defn led-infix
  "Factory: binary infix parselet."
  ([node-type] (led-infix node-type nil))
  ([node-type right-bp]
   (fn [p lhs op-tok]
     (let [rhs (parse-expr p (or right-bp 0))]
       (cst node-type {:left lhs :token op-tok :right rhs})))))

;; ---------------------------------------------------------------------------
;; Core Pratt loop
;; ---------------------------------------------------------------------------

(defn- led-matches?
  "Does a led rule match the current token at the given binding power?"
  [p rule min-bp]
  (let [tok (ppeek p)]
    (and tok
         (= (:token-type rule) (:type tok))
         (> (:bp rule) min-bp)
         (if-let [pred (:when rule)]
           (pred tok)
           true))))

(defn parse-expr
  "Parse one expression at the given minimum binding power."
  [p min-bp]
  (let [tok (padvance! p)]
    (if (nil? tok)
      (cst :error {:message "Unexpected end of input"})
      (let [spec (:spec p)
            nud-fn (get-in spec [:nud (:type tok)])
            lhs (if nud-fn
                  (nud-fn p tok)
                  (cst :error {:token tok :message (str "Unexpected token: " (:type tok))}))]
        (loop [lhs lhs]
          (let [matching-led (first (filter #(led-matches? p % min-bp) (:led spec)))]
            (if matching-led
              (recur ((:fn matching-led) p lhs (padvance! p)))
              lhs)))))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn parse
  "Parse a trivia-attached token stream into a vector of CST nodes.
   spec: {:nud {token-type parselet-fn ...}
          :led [{:token-type t :bp n :when pred :fn parselet-fn} ...]}"
  [tokens spec]
  (let [p (make-parser tokens spec)]
    (loop [nodes []]
      (if (peof? p)
        (let [trailing (:trivia/after (meta tokens))]
          (if trailing
            (with-meta nodes {:trivia/after trailing})
            nodes))
        (recur (conj nodes (parse-expr p 0)))))))

