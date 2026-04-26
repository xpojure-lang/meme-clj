(ns meme.tools.clj.ast.build
  "Build the AST tier from a CST.

   Walks the parser's CST and produces records from `meme.tools.clj.ast.nodes`
   that capture position, trivia, sugar-form, raw spelling, and namespace
   prefix as record fields. Reuses the existing atomic resolvers
   (`resolve/resolve-number`, `resolve-char`, etc.) and disassembles
   their results into AST node fields.

   Pipeline: scanner → trivia-attacher → pratt-parser → **cst→ast** → ast→form (eval-time)"
  (:require [clojure.string :as str]
            [meme.tools.clj.ast.nodes :as nodes]
            [meme.tools.clj.errors :as errors]
            [meme.tools.clj.forms :as forms]
            [meme.tools.clj.resolve :as resolve]))

;; ---------------------------------------------------------------------------
;; Sentinels — kept compatible with cst-reader so that read-forms helpers
;; like splice-and-filter can be reused during the migration.
;; ---------------------------------------------------------------------------

(def ^:private no-match ::no-match)

;; ---------------------------------------------------------------------------
;; Position / trivia extraction
;; ---------------------------------------------------------------------------

(defn- tok-pos [tok]
  (when tok (select-keys tok [:line :col])))

(defn- node-pos [node]
  (or (tok-pos (or (:token node) (:open node) (:ns node)))
      {:line 1 :col 1}))

(defn- trivia-from
  "Vec of trivia items from a token's :trivia/before. Empty when absent."
  [tok]
  (vec (:trivia/before tok)))

(defn- check-closed!
  [node ctx-name]
  (when-not (:close node)
    (errors/meme-error
      (str "Unclosed " ctx-name " — expected closing delimiter")
      (assoc (node-pos node) :incomplete true))))

;; ---------------------------------------------------------------------------
;; Forward declaration
;; ---------------------------------------------------------------------------

(declare cst->ast-node)

;; ---------------------------------------------------------------------------
;; Atomic resolution → AST node
;;
;; Each branch parses raw source text into a canonical AST node, using the
;; existing resolvers for the heavy lifting (parse rules, error messages,
;; CLJS asymmetries) and disassembling their outputs into structured fields.
;; ---------------------------------------------------------------------------

(defn- symbol-atom [tok pos trivia]
  (let [raw (:raw tok)]
    (case raw
      "nil"   (nodes/->CljNil pos trivia)
      "true"  (nodes/->CljBool true pos trivia)
      "false" (nodes/->CljBool false pos trivia)
      (do
        (when (and (str/starts-with? raw "/") (not= raw "/"))
          (errors/meme-error (str "Invalid token: " raw) pos))
        (let [s (symbol raw)]
          (nodes/->CljSymbol (name s) (namespace s) pos trivia))))))

(defn- keyword-atom
  "Parse a keyword token into a CljKeyword AST node.

  Captures source structure (name, namespace alias, auto-resolve flag);
  does NOT resolve the alias to a real namespace. Resolution happens at
  ast→form lowering time, where the `:resolve-keyword` opt may apply."
  [tok pos trivia]
  (let [raw (:raw tok)
        loc (tok-pos tok)]
    (when (str/starts-with? raw ":::")
      (errors/meme-error (str "Invalid token: " raw) loc))
    (if (str/starts-with? raw "::")
      (let [body (subs raw 2)]
        (when (or (= body "") (str/starts-with? body "/") (str/ends-with? body "/"))
          (errors/meme-error (str "Invalid token: " raw) loc))
        (let [slash (str/index-of body "/")]
          (if (some? slash)
            (nodes/->CljKeyword (subs body (inc slash)) (subs body 0 slash) true pos trivia)
            (nodes/->CljKeyword body nil true pos trivia))))
      (let [s (subs raw 1)]
        (cond
          (= s "/") (nodes/->CljKeyword "/" nil false pos trivia)
          (or (= s "")
              (str/starts-with? s "/")
              (str/ends-with? s ":")
              (str/ends-with? s "/")
              (str/includes? s "::"))
          (errors/meme-error (str "Invalid token: " raw) loc)
          :else
          (let [i (str/index-of s "/")]
            (if (some? i)
              (let [ns-part (subs s 0 i)
                    name-part (subs s (inc i))]
                (when (or (= ns-part "") (= name-part ""))
                  (errors/meme-error (str "Invalid token: " raw) loc))
                (nodes/->CljKeyword name-part ns-part false pos trivia))
              (nodes/->CljKeyword s nil false pos trivia))))))))

(defn- number-atom [tok pos trivia]
  (let [raw (:raw tok)
        loc (tok-pos tok)
        v (resolve/resolve-number raw loc)
        [value src] (if (forms/raw? v) [(:value v) (:raw v)] [v raw])]
    (nodes/->CljNumber value src pos trivia)))

(defn- string-atom [tok pos trivia]
  (let [raw (:raw tok)
        v (resolve/resolve-string raw (tok-pos tok))]
    (nodes/->CljString v raw pos trivia)))

(defn- char-atom [tok pos trivia]
  (let [raw (:raw tok)
        v (resolve/resolve-char raw (tok-pos tok))
        [value src] (if (forms/raw? v) [(:value v) (:raw v)] [v raw])]
    (nodes/->CljChar value src pos trivia)))

(defn- regex-atom [tok pos trivia]
  ;; The AST stores the pattern source (between #" and ") so the tier
  ;; stays portable. We still call resolve-regex at build time to validate
  ;; — it throws on unterminated / malformed regex (the parser produces
  ;; the token regardless of whether the closing " is present).
  (let [raw (:raw tok)]
    (resolve/resolve-regex raw (tok-pos tok))
    (let [pattern (subs raw 2 (dec (count raw)))]
      (nodes/->CljRegex pattern pos trivia))))

(defn- atom-node [node]
  (let [tok (:token node)
        pos (tok-pos tok)
        trivia (trivia-from tok)]
    (case (:type tok)
      :symbol       (symbol-atom tok pos trivia)
      :keyword      (keyword-atom tok pos trivia)
      :number       (number-atom tok pos trivia)
      :string       (string-atom tok pos trivia)
      :char-literal (char-atom tok pos trivia)
      :regex        (regex-atom tok pos trivia)
      :shebang      no-match
      (errors/meme-error (str "Unknown atom type: " (:type tok)) (tok-pos tok)))))

;; ---------------------------------------------------------------------------
;; Children walker
;; ---------------------------------------------------------------------------

(defn- splice-and-filter
  "Drop shebang sentinels from a vector of read children. Reader conds are
  preserved as records (no splicing at read time); discard nodes are kept
  in the AST so tooling sees them — eval-time lowering filters them out."
  [items]
  (persistent!
    (reduce (fn [acc item]
              (if (identical? item no-match)
                acc
                (conj! acc item)))
            (transient []) items)))

(defn- read-children [children opts]
  (splice-and-filter
    (mapv #(cst->ast-node % opts) children)))

;; ---------------------------------------------------------------------------
;; Main walker
;; ---------------------------------------------------------------------------

(defn cst->ast-node
  "Walk a single CST node, return the AST representation."
  [node opts]
  (let [depth (or (::depth opts) 0)]
    (when (>= depth forms/max-parse-depth)
      (errors/meme-error "Maximum nesting depth exceeded" (node-pos node))))
  (let [opts (update opts ::depth (fnil inc 0))
        pos  (node-pos node)
        ;; CST nodes themselves don't carry trivia — trivia rides on tokens.
        ;; For collection nodes we attach the trivia from the open delimiter.
        open-tok (:open node)
        token-tok (:token node)
        leading-tok (or token-tok open-tok (:ns node))
        trivia (trivia-from leading-tok)]
    (case (:node node)

      :atom
      (atom-node node)

      :call
      (do (check-closed! node "call")
          (let [head (cst->ast-node (:head node) opts)
                args (read-children (:args node) opts)]
            (nodes/->CljList (into [head] args) pos trivia [])))

      :list
      ;; Two parser-engine shapes feed this branch:
      ;;   • m1clj grammar's `()` empty case — no :children field, no head extraction
      ;;   • native-Clojure grammar's `(f x y)` — :children holds head + args
      ;; Both lower to a CljList. check-closed! tolerates the empty-paren node
      ;; from m1clj's nud-empty-or-error (which always emits :close).
      (do (check-closed! node "list")
          (nodes/->CljList
            (read-children (:children node) opts) pos trivia []))

      :vector
      (do (check-closed! node "vector")
          (nodes/->CljVector
            (read-children (:children node) opts) pos trivia []))

      :map
      (do (check-closed! node "map")
          (let [items (read-children (:children node) opts)]
            (when (odd? (count items))
              (errors/meme-error "Map must contain an even number of forms" pos))
            (nodes/->CljMap (vec (partition-all 2 items)) pos trivia [])))

      :set
      (do (check-closed! node "set")
          (nodes/->CljSet
            (read-children (:children node) opts) pos trivia []))

      :quote
      (nodes/->CljQuote (cst->ast-node (:form node) opts) pos trivia)

      :deref
      (nodes/->CljDeref (cst->ast-node (:form node) opts) pos trivia)

      :var-quote
      ;; The "requires a symbol" check happens at lowering time:
      ;; #'^:foo bar has a CljMeta inner whose lowered form IS a symbol.
      ;; Checking at AST level would over-reject the metadata wrapping.
      (nodes/->CljVar (cst->ast-node (:form node) opts) pos trivia)

      :syntax-quote
      (nodes/->CljSyntaxQuote (cst->ast-node (:form node) opts) pos trivia)

      :unquote
      (nodes/->CljUnquote (cst->ast-node (:form node) opts) pos trivia)

      :unquote-splicing
      (nodes/->CljUnquoteSplicing (cst->ast-node (:form node) opts) pos trivia)

      :discard
      (nodes/->CljDiscard (cst->ast-node (:form node) opts) pos trivia)

      :tagged
      (let [tag-raw (:raw (:token node))
            tag (subs tag-raw 1)]
        (nodes/->CljTagged tag (cst->ast-node (:form node) opts) pos trivia))

      :meta
      (let [m-ast (cst->ast-node (:meta node) opts)
            target (cst->ast-node (:target node) opts)
            chain (if (instance? meme.tools.clj.ast.nodes.CljMeta target)
                    (conj (vec (:chain target)) m-ast)
                    [m-ast])
            inner-target (if (instance? meme.tools.clj.ast.nodes.CljMeta target)
                           (:target target)
                           target)]
        (nodes/->CljMeta chain inner-target pos trivia))

      :anon-fn
      (do (check-closed! node "anonymous function")
          (when (::in-anon-fn opts)
            (errors/meme-error "Nested #() are not allowed" pos))
          (let [body-children (read-children (:children node)
                                             (assoc opts ::in-anon-fn true))
                ;; Discards inside `#()` are eliminated for the body
                ;; computation (matches cst-reader). Keeping them would
                ;; let `#(#_x)` parse as a no-op body, which Clojure rejects.
                effective (filterv #(not (instance?
                                           meme.tools.clj.ast.nodes.CljDiscard %))
                                   body-children)]
            (when (empty? effective)
              (errors/meme-error "#() requires a body" pos))
            (let [body (if (= 1 (count effective))
                         (first effective)
                         ;; Multi-form body — wrap in an implicit do-list.
                         (nodes/->CljList
                           (into [(nodes/->CljSymbol "do" nil pos [])] effective)
                           pos [] []))
                  ;; Arity is computed from %-symbols in the body during
                  ;; ast→form lowering. AST stores raw body for fidelity;
                  ;; arity is :unresolved at AST time.
                  arity :unresolved]
              (nodes/->CljAnonFn body arity pos trivia))))

      :namespaced-map
      (do (check-closed! node "namespaced map")
          (let [ns-raw (:raw (:ns node))
                auto-resolve? (str/starts-with? ns-raw "#::")
                ns-name (if auto-resolve? (subs ns-raw 3) (subs ns-raw 2))]
            (when (and (str/blank? ns-name) (not auto-resolve?))
              (errors/meme-error "Namespaced map must specify a namespace" pos))
            (let [items (read-children (:children node) opts)]
              (when (odd? (count items))
                (errors/meme-error "Namespaced map must contain even number of forms" pos))
              (let [inner-map (nodes/->CljMap
                                (vec (partition-all 2 items))
                                pos [] [])]
                (nodes/->CljNamespacedMap ns-name auto-resolve? inner-map pos trivia)))))

      :reader-cond
      (do (check-closed! node "reader conditional")
          (let [items (read-children (:children node) opts)
                pairs (vec (partition-all 2 items))]
            (nodes/->CljReaderCond pairs (boolean (:splicing? node)) pos trivia)))

      :error
      (let [msg (or (:message node) "Parse error")
            eof? (or (str/includes? msg "end of input")
                     (str/includes? msg "Unexpected end"))]
        (errors/meme-error msg (cond-> pos eof? (assoc :incomplete true))))

      (errors/meme-error (str "Unknown CST node: " (:node node)) pos))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn cst->ast
  "Top-level: walk a vector of CST nodes (the parser's output) and produce
  a CljRoot AST. Trailing trivia from the CST root metadata becomes
  `:trailing-trivia` on the root."
  ([cst] (cst->ast cst nil))
  ([cst opts]
   (let [children (splice-and-filter
                    (mapv #(cst->ast-node % opts) cst))
         trailing (vec (:trivia/after (meta cst)))]
     (nodes/->CljRoot children trailing nil))))
