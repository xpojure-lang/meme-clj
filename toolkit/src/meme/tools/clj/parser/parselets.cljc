(ns meme.tools.clj.parser.parselets
  "Compound parselets shared by any Clojure-flavored grammar.

   These cover the Clojure-surface dispatch sublanguage (`#`-prefixed
   forms, tilde unquoting, sign-prefixed numerics) — pieces that look
   the same whether the host grammar uses S-expression or M-expression
   call syntax. Lang-specific call rules (e.g. m1clj's adjacency-based
   call) live next to that lang."
  (:require [meme.tools.parser :as pratt]
            [meme.tools.clj.lex :as lex]))

;; ---------------------------------------------------------------------------
;; Predicates
;; ---------------------------------------------------------------------------

(defn sign-followed-by-digit?
  "True if ch is + or - and the next char in engine is a digit."
  [engine ch]
  (and (or (= ch \+) (= ch \-))
       (let [next-ch (pratt/peek-char engine 1)]
         (and next-ch (lex/digit? next-ch)))))

;; ---------------------------------------------------------------------------
;; Parselet extras
;; ---------------------------------------------------------------------------

(defn- reader-cond-extra
  "Extract :splicing? from a reader-cond token (#? vs #?@)."
  [tok]
  (let [raw (:raw tok)]
    {:splicing? (and (>= (count raw) 3) (= "#?@" (subs raw 0 3)))}))

;; ---------------------------------------------------------------------------
;; Tilde scanlet
;; ---------------------------------------------------------------------------

(defn tilde-scanlet
  "~ or ~@ scanlet."
  [engine]
  (let [start (pratt/cursor engine)]
    (if (and (not (pratt/eof? engine))
             (< (inc (pratt/cursor engine)) (pratt/source-len engine))
             (= (pratt/peek-char engine 1) \@))
      ;; ~@
      (do (pratt/advance! engine 2)
          (let [tok (pratt/make-token! engine :unquote-splicing start)]
            ((pratt/nud-prefix :unquote-splicing) engine tok)))
      ;; ~
      (do (pratt/advance! engine 1)
          (let [tok (pratt/make-token! engine :unquote start)]
            ((pratt/nud-prefix :unquote) engine tok))))))

;; ---------------------------------------------------------------------------
;; Anon-fn nud variants
;;
;; `#(...)` means different things across Clojure-flavored grammars:
;;   • Meme parses contents as expressions, so a 1-elt body case is the
;;     normal shape (meme has no implicit list — `#(*(% %))` is 1 child).
;;   • Native Clojure parses contents as an implicit list call: the
;;     parens of `#(...)` ARE the parens of the body call, so `#(* % %)`
;;     means body = `(* % %)`.
;; ---------------------------------------------------------------------------

(defn- anon-fn-nud-list-body
  "Native-Clojure `#(...)`: wrap parsed children in an implicit `:list` CST so
   the AST builder's 1-child path treats them as a single body call."
  [engine open-tok]
  (let [[children close-tok] (pratt/parse-until engine \) :close-paren)
        body-list (pratt/cst :list {:open open-tok
                                    :children children
                                    :close close-tok})]
    (pratt/cst :anon-fn {:open open-tok
                         :children [body-list]
                         :close close-tok})))

(defn- anon-fn-nud-for [mode]
  (case mode
    :list        anon-fn-nud-list-body
    :expressions (pratt/nud-delimited :anon-fn \) :close-paren)
    (pratt/nud-delimited :anon-fn \) :close-paren)))

;; ---------------------------------------------------------------------------
;; Dispatch scanlet — `#`-prefixed forms
;; ---------------------------------------------------------------------------

(defn make-dispatch-scanlet
  "Build a `#`-dispatch scanlet parameterised by `:anon-fn-body`.
   See `anon-fn-nud-list-body` above for `:expressions` vs `:list` semantics.
   Defaults to `:expressions` (meme behaviour)."
  [{:keys [anon-fn-body] :or {anon-fn-body :expressions}}]
  (let [anon-fn-nud (anon-fn-nud-for anon-fn-body)]
    (fn dispatch [engine]
      (let [start (pratt/cursor engine)
            source (pratt/source-str engine)
            len (pratt/source-len engine)
            next-pos (inc (pratt/cursor engine))]
        (if (>= next-pos len)
          ;; bare # at EOF
          (do (pratt/advance! engine 1)
              (let [tok (pratt/make-token! engine :invalid start)]
                (pratt/cst :error {:token tok :message "Unexpected token: :invalid"})))
          (let [next-ch (pratt/peek-char engine 1)]
            (cond
              ;; #(
              (= next-ch \()
              (do (pratt/advance! engine 2)
                  (let [tok (pratt/make-token! engine :open-anon-fn start)]
                    (anon-fn-nud engine tok)))

              ;; #{
              (= next-ch \{)
              (do (pratt/advance! engine 2)
                  (let [tok (pratt/make-token! engine :open-set start)]
                    ((pratt/nud-delimited :set \} :close-brace) engine tok)))

              ;; #'
              (= next-ch \')
              (do (pratt/advance! engine 2)
                  (let [tok (pratt/make-token! engine :var-quote start)]
                    ((pratt/nud-prefix :var-quote) engine tok)))

              ;; #_ — discard. Consecutive #_ tokens discard N forms (Clojure semantics).
              ;; Intermediate #_ tokens and their discarded forms are retained on the
              ;; CST node so trivia between discards is not lost (CST losslessness).
              (= next-ch \_)
              (do (pratt/advance! engine 2)
                  (let [tok (pratt/make-token! engine :discard start)]
                    (loop [extra-tokens []]
                      (pratt/skip-trivia! engine)
                      (if (and (not (pratt/eof? engine))
                               (= (pratt/peek-char engine) \#)
                               (let [nxt (pratt/peek-char engine 1)]
                                 (and nxt (= nxt \_))))
                        (let [discard-start (pratt/cursor engine)
                              _ (pratt/advance! engine 2)
                              extra (pratt/make-token! engine :discard discard-start)]
                          (recur (conj extra-tokens extra)))
                        (let [discarded-forms (vec (repeatedly (count extra-tokens)
                                                     #(pratt/parse-expr engine 0)))
                              form (pratt/parse-expr engine 0)]
                          (pratt/cst :discard {:token tok
                                               :extra-tokens extra-tokens
                                               :discarded-forms discarded-forms
                                               :form form}))))))

              ;; #"
              (= next-ch \")
              (let [end (lex/consume-string source len next-pos)]
                (pratt/set-pos! engine end)
                (let [tok (pratt/make-token! engine :regex start)]
                  (pratt/cst :atom {:token tok})))

              ;; #! — shebang at pos 0, invalid mid-file
              (= next-ch \!)
              (if (zero? start)
                (let [end (loop [i (+ start 2)]
                            (if (or (>= i len) (lex/newline-char? (.charAt source i)))
                              i
                              (recur (inc i))))]
                  (pratt/set-pos! engine end)
                  (let [tok (pratt/make-token! engine :shebang start)]
                    (pratt/cst :atom {:token tok})))
                (do (pratt/advance! engine 2)
                    (let [tok (pratt/make-token! engine :invalid start)]
                      (pratt/cst :error {:token tok :message "Unexpected token: :invalid"}))))

              ;; #:
              (= next-ch \:)
              (let [end (lex/consume-keyword source len next-pos)]
                (pratt/set-pos! engine end)
                (let [tok (pratt/make-token! engine :namespaced-map start)]
                  ((pratt/nud-prefixed-delimited :namespaced-map \{ :open-brace \} :close-brace)
                   engine tok)))

              ;; #? or #?@
              (= next-ch \?)
              (let [after-q (+ start 2)]
                (if (and (< after-q len) (= (.charAt source after-q) \@))
                  (do (pratt/set-pos! engine (+ start 3))
                      (let [tok (pratt/make-token! engine :reader-cond start)]
                        ((pratt/nud-prefixed-delimited :reader-cond \( :open-paren \) :close-paren
                                                      reader-cond-extra)
                         engine tok)))
                  (do (pratt/set-pos! engine (+ start 2))
                      (let [tok (pratt/make-token! engine :reader-cond start)]
                        ((pratt/nud-prefixed-delimited :reader-cond \( :open-paren \) :close-paren
                                                      reader-cond-extra)
                         engine tok)))))

              ;; #= — Clojure reader's read-eval form. Not a real tagged
              ;; literal; meme rejects it because we don't support eval at
              ;; read time. (m1clj's grammar catches it incidentally via
              ;; the bare-parens-no-head rule; native Clojure needs an
              ;; explicit reject so `#=(+ 1 2)` doesn't quietly become a
              ;; `#=`-tagged form.)
              (= next-ch \=)
              (do (pratt/advance! engine 2)
                  (let [tok (pratt/make-token! engine :invalid start)]
                    (pratt/cst :error
                               {:token tok
                                :message "#= read-eval is not supported"})))

              ;; ## symbolic value (##Inf, ##NaN, etc.)
              (= next-ch \#)
              (let [end (loop [i (+ start 2)]
                          (if (and (< i len) (lex/symbol-char? (.charAt source i)))
                            (recur (inc i))
                            i))]
                (pratt/set-pos! engine end)
                (let [tok (pratt/make-token! engine :number start)]
                  (pratt/cst :atom {:token tok})))

              ;; Tagged literal: #tag
              (lex/symbol-start? next-ch)
              (let [end (lex/consume-symbol source len next-pos)]
                (pratt/set-pos! engine end)
                (let [tok (pratt/make-token! engine :hashtag-symbol start)]
                  ((pratt/nud-prefix :tagged) engine tok)))

              ;; Invalid dispatch
              :else
              (do (pratt/advance! engine 2)
                  (let [tok (pratt/make-token! engine :invalid start)]
                    (pratt/cst :error {:token tok :message "Unexpected token: :invalid"}))))))))))

;; Default dispatch-scanlet — meme-style anon-fn (expressions body).
;; Native-Clojure grammar should use `(make-dispatch-scanlet {:anon-fn-body :list})`.
(def dispatch-scanlet (make-dispatch-scanlet {}))
