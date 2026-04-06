(ns wlj-lang.cst-reader
  "CST → Clojure forms for wlj.")

(defn- resolve-number [raw]
  (if (or (.contains ^String raw ".") (.contains ^String raw "e") (.contains ^String raw "E"))
    (Double/parseDouble raw)
    (Long/parseLong raw)))

(defn- resolve-string [raw]
  (-> (subs raw 1 (dec (count raw)))
      (.replace "\\n" "\n") (.replace "\\t" "\t")
      (.replace "\\\\" "\\") (.replace "\\\"" "\"")))

(defn- resolve-identifier [raw]
  (case raw "True" true "False" false "Null" nil "true" true "false" false "nil" nil
    (symbol raw)))

(declare read-node)

(defn- read-infix [node op-sym]
  (list op-sym (read-node (:left node)) (read-node (:right node))))

(defn- flatten-compound
  "Flatten nested :compound nodes into a flat list of forms."
  [node]
  (if (= :compound (:node node))
    (let [left (flatten-compound (:left node))
          right (when (:right node) (flatten-compound (:right node)))]
      (concat left right))
    [(read-node node)]))

(defn read-node [node]
  (case (:node node)
    :atom
    (let [tok (:token node)]
      (case (:type tok)
        :number     (resolve-number (:raw tok))
        :string     (resolve-string (:raw tok))
        :identifier (resolve-identifier (:raw tok))
        (throw (ex-info (str "Unknown atom type: " (:type tok)) {:token tok}))))

    :add (read-infix node '+)
    :sub (read-infix node '-)
    :mul (read-infix node '*)
    :div (read-infix node '/)
    :pow (list 'Math/pow (read-node (:left node)) (read-node (:right node)))
    :eq  (read-infix node '=)
    :neq (read-infix node 'not=)
    :lt  (read-infix node '<)
    :gt  (read-infix node '>)
    :lte (read-infix node '<=)
    :gte (read-infix node '>=)
    :and (read-infix node 'and)
    :or  (read-infix node 'or)

    ;; expr // f → (f expr)
    :postfix-apply (list (read-node (:right node)) (read-node (:left node)))

    :unary-minus (list '- (read-node (:form node)))
    :unary-not   (list 'not (read-node (:form node)))

    :group (let [children (:children node)]
             (if (= 1 (count children))
               (read-node (first children))
               (cons 'do (map read-node children))))

    ;; {a, b, c} → [a b c]
    :list (mapv read-node (:children node))

    ;; f[x, y] → (f x y)
    :call (let [head (read-node (:head node))
                args (mapv read-node (:args node))]
            (apply list head args))

    ;; expr[[i]] → (nth expr i)
    :part (let [expr (read-node (:expr node))
                args (mapv read-node (:args node))]
            (if (= 1 (count args))
              (list 'nth expr (first args))
              (list 'get-in expr (vec args))))

    ;; a -> b → [a b]
    :rule [(read-node (:left node)) (read-node (:right node))]

    ;; <| entries |> → hash-map from rule pairs
    :association
    (let [entries (mapv read-node (:entries node))]
      (into {} (map (fn [e]
                      (if (vector? e) e
                        (throw (ex-info "Association entry must be a Rule (->)" {:entry e})))))
            entries))

    ;; x = val → (def x val)
    :assign (list 'def (read-node (:left node)) (read-node (:right node)))

    ;; a ; b ; c → (do a b c)
    :compound (let [forms (flatten-compound node)]
                (if (= 1 (count forms))
                  (first forms)
                  (cons 'do forms)))

    :error (throw (ex-info (or (:message node) "Parse error") {:node node}))
    (throw (ex-info (str "Unknown CST node: " (:node node)) {:node node}))))

(defn read-forms [cst _opts]
  (mapv read-node cst))
