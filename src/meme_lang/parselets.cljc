(ns meme-lang.parselets
  "Meme-specific parselets for the Pratt parser.

   Contains the compound parselets that handle meme's unique constructs:
   call adjacency detection, dispatch (#) sub-routing, tilde (~/@),
   and the M-expression call rule."
  (:require [meme.tools.parser :as pratt]
            [meme-lang.lexlets :as lex]))

;; ---------------------------------------------------------------------------
;; Predicates
;; ---------------------------------------------------------------------------

(defn adjacent?
  "Call predicate: ( is a call only when no trivia was accumulated since
   the last expression ended. Checks the engine's trivia accumulator."
  [engine]
  (empty? @(:trivia-acc engine)))

(defn sign-followed-by-digit?
  "True if ch is + or - and the next char in engine is a digit."
  [engine ch]
  (and (or (= ch \+) (= ch \-))
       (let [next-ch (pratt/peek-char engine 1)]
         (and next-ch (lex/digit? next-ch)))))

;; ---------------------------------------------------------------------------
;; Parselet extras
;; ---------------------------------------------------------------------------

(defn reader-cond-extra
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
;; Dispatch scanlet
;; ---------------------------------------------------------------------------

(defn dispatch-scanlet
  "# dispatch scanlet. Peeks at the next character and sub-dispatches."
  [engine]
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
                ((pratt/nud-delimited :anon-fn \) :close-paren) engine tok)))

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

          ;; #_
          (= next-ch \_)
          (do (pratt/advance! engine 2)
              (let [tok (pratt/make-token! engine :discard start)]
                ((pratt/nud-prefix :discard) engine tok)))

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
                (pratt/cst :error {:token tok :message "Unexpected token: :invalid"}))))))))

;; ---------------------------------------------------------------------------
;; Call scanlet
;; ---------------------------------------------------------------------------

(defn call-scanlet
  "Led call scanlet — the M-expression rule. Receives engine, lhs, and open-tok."
  [engine lhs open-tok]
  (let [[args close-tok] (pratt/parse-until engine \) :close-paren)]
    (pratt/cst :call {:head lhs :open open-tok :args args :close close-tok})))
