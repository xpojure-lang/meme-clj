(ns meme.alpha.scan.tokenizer
  "meme tokenizer: character scanning and token production.
   Transforms meme source text into a flat vector of typed tokens."
  (:require [meme.alpha.errors :as errors]
            [meme.alpha.scan.source :as source]))

;; ---------------------------------------------------------------------------
;; Character predicates
;; ---------------------------------------------------------------------------

(defn- whitespace? [ch]
  (and ch (or (= ch \space) (= ch \tab) (= ch \newline) (= ch \return) (= ch \,))))

(defn- char-code [ch]
  #?(:clj (int ch) :cljs (.charCodeAt ch 0)))

;; Named character-code constants — replace magic ASCII numbers
(def ^:private code-0 (char-code \0))
(def ^:private code-7 (char-code \7))
(def ^:private code-9 (char-code \9))
(def ^:private code-A (char-code \A))
(def ^:private code-F (char-code \F))
(def ^:private code-Z (char-code \Z))
(def ^:private code-a (char-code \a))
(def ^:private code-f (char-code \f))
(def ^:private code-z (char-code \z))

(defn- digit? [ch]
  (and ch (let [c (char-code ch)] (and (>= c code-0) (<= c code-9)))))

(defn- hex-digit? [ch]
  (and ch (let [c (char-code ch)]
            (or (and (>= c code-0) (<= c code-9))
                (and (>= c code-A) (<= c code-F))
                (and (>= c code-a) (<= c code-f))))))

(defn- octal-digit? [ch]
  (and ch (let [c (char-code ch)] (and (>= c code-0) (<= c code-7)))))

(defn- symbol-start? [ch]
  (and ch
       (not (whitespace? ch))
       (not (digit? ch))
       (not (#{\( \) \[ \] \{ \} \" \; \@ \^ \` \~ \\} ch))))

(defn- symbol-char? [ch]
  (and ch
       (not (whitespace? ch))
       (not (#{\( \) \[ \] \{ \} \" \; \@ \^ \` \~ \\} ch))))

;; ---------------------------------------------------------------------------
;; Scanner (character-level)
;; ---------------------------------------------------------------------------

(defn- make-scanner [s]
  {:src s :len (count s) :pos (volatile! 0) :line (volatile! 1) :col (volatile! 1)})

(defn- seof? [{:keys [pos len]}] (>= @pos len))

(defn- speek
  ([sc] (speek sc 0))
  ([{:keys [src pos len]} offset]
   (let [i (+ @pos offset)]
     (when (< i len) (nth src i)))))

(defn- sadvance!
  "Advance scanner by one character, returning the consumed char.
   Callers MUST check (seof? sc) before calling — no bounds check is performed."
  [{:keys [pos line col src]}]
  (let [ch (nth src @pos)]
    (if (= ch \newline)
      (do (vswap! line inc) (vreset! col 1))
      (vswap! col inc))
    (vswap! pos inc)
    ch))

(defn- sloc [{:keys [line col pos]}] {:line @line :col @col :offset @pos})

;; Portable string builder — StringBuilder on JVM/Babashka, JS array on ClojureScript
(defn- make-sb
  ([] #?(:clj (StringBuilder.) :cljs #js []))
  ([s] #?(:clj (StringBuilder. ^String s) :cljs (let [a #js []] (.push a s) a))))

(defn- sb-append! [sb x]
  #?(:clj (.append ^StringBuilder sb x) :cljs (.push sb (str x)))
  sb)

(defn- sb-str [sb]
  #?(:clj (str sb) :cljs (.join sb "")))

(defn- read-while [sc pred]
  (let [sb (make-sb)]
    (loop []
      (when-not (seof? sc)
        (let [ch (speek sc)]
          (when (pred ch)
            (sadvance! sc)
            (sb-append! sb ch)
            (recur)))))
    (sb-str sb)))

;; ---------------------------------------------------------------------------
;; Tokenizer helpers
;; ---------------------------------------------------------------------------

(defn- consume-string-body!
  "Consume a string body from scanner into sb, handling escape sequences.
   Expects the opening quote already consumed. Reads until closing quote.
   error-msg is used if EOF is reached before the closing quote."
  [sc sb loc error-msg]
  (loop []
    (when (seof? sc)
      (errors/meme-error error-msg (assoc loc :incomplete true)))
    (let [ch (sadvance! sc)]
      (sb-append! sb ch)
      (cond
        (= ch \") nil
        (= ch \\) (do (when-not (seof? sc) (sb-append! sb (sadvance! sc)))
                      (recur))
        :else (recur)))))

(defn- read-string-literal [sc]
  (let [loc (sloc sc)
        _ (sadvance! sc)
        sb (make-sb "\"")]
    (consume-string-body! sc sb loc "Unterminated string — missing closing \"")
    (sb-str sb)))

(defn- read-regex-body [sc]
  (let [loc (sloc sc)
        _ (sadvance! sc) ; opening "
        sb (make-sb "#\"")]
    (consume-string-body! sc sb loc "Unterminated regex — missing closing \"")
    (sb-str sb)))

(defn- letter? [ch]
  (and ch
       (let [c (char-code ch)]
         (or (and (>= c code-a) (<= c code-z))
             (and (>= c code-A) (<= c code-Z))))))

(defn- read-char-extra
  "After the first char of a character literal, consume additional chars
   for named chars (\\newline), unicode escapes (\\uXXXX), or octal (\\oXXX)."
  [sc sb ch loc]
  (cond
    ;; \uXXXX — Unicode hex escape: exactly 4 hex digits required
    (= ch \u)
    (let [consumed (loop [n 0]
                     (if (and (< n 4) (not (seof? sc)) (hex-digit? (speek sc)))
                       (do (sb-append! sb (sadvance! sc)) (recur (inc n)))
                       n))]
      (when (< consumed 4)
        (if (and (pos? consumed) (seof? sc))
          (errors/meme-error
            (str "Incomplete unicode escape: expected 4 hex digits after \\u, got " consumed)
            (assoc loc :incomplete true))
          (when (and (not (seof? sc))
                     (not (whitespace? (speek sc)))
                     (not (#{\( \) \[ \] \{ \} \;} (speek sc))))
            (errors/meme-error
              (str "Invalid unicode escape: expected 4 hex digits after \\u, got " consumed)
              loc)))))
    ;; \oXXX — octal escape: up to 3 octal digits required, at least 1
    (= ch \o)
    (let [consumed (loop [n 0]
                     (if (and (< n 3) (not (seof? sc)) (octal-digit? (speek sc)))
                       (do (sb-append! sb (sadvance! sc)) (recur (inc n)))
                       n))]
      (when (and (zero? consumed) (not (seof? sc))
                 (not (whitespace? (speek sc)))
                 (not (#{\( \) \[ \] \{ \} \;} (speek sc))))
        (errors/meme-error
          "Invalid octal escape: expected octal digits after \\o"
          loc)))
    ;; named chars: \newline, \space, etc.
    (letter? ch)
    (loop []
      (when (and (not (seof? sc)) (letter? (speek sc)))
        (sb-append! sb (sadvance! sc))
        (recur)))))

(defn- read-char-literal [sc]
  (let [loc (sloc sc)]
    (sadvance! sc) ; backslash
    (let [sb (make-sb "\\")]
      (if (seof? sc)
        (errors/meme-error "Unterminated character literal — expected a character after \\"
                           (assoc loc :incomplete true))
        (let [ch (sadvance! sc)]
          (sb-append! sb ch)
          (read-char-extra sc sb ch loc)))
      (sb-str sb))))

(defn- read-number [sc]
  (let [sb (make-sb)]
    (loop []
      (when-not (seof? sc)
        (let [ch (speek sc)]
          (when (or (digit? ch)
                     (letter? ch)
                     (#{\. \/ \+ \-} ch))
            (sadvance! sc)
            (sb-append! sb ch)
            (recur)))))
    (sb-str sb)))

(defn- read-symbol-str
  "Read a symbol string."
  [sc]
  (let [sb (make-sb)]
    (loop [saw-slash false]
      (when-not (seof? sc)
        (let [ch (speek sc)]
          (cond
            ;; / in symbol (namespace qualifier) — allow once
            (and (= ch \/) (not saw-slash))
            (do (sadvance! sc) (sb-append! sb ch) (recur true))

            ;; second / terminates the symbol — stop before consuming it
            (= ch \/) nil

            (symbol-char? ch)
            (do (sadvance! sc) (sb-append! sb ch) (recur saw-slash))

            :else nil))))
    (sb-str sb)))

;; ---------------------------------------------------------------------------
;; Tokenizer
;; ---------------------------------------------------------------------------

(defn- tok
  ([type value loc]
   {:type type :value value :line (:line loc) :col (:col loc)
    :offset (:offset loc)})
  ([type value loc end-loc]
   {:type type :value value :line (:line loc) :col (:col loc)
    :end-line (:line end-loc) :end-col (:col end-loc)
    :offset (:offset loc) :end-offset (:offset end-loc)}))

(defn- tok-at
  "Create a token with end position from current scanner state.
   Call this AFTER consuming the token's characters.
   :end-col is exclusive (one past the last character) — sloc returns
   the scanner's next position after consumption."
  [sc type value start-loc]
  (tok type value start-loc (sloc sc)))

(defn tokenize
  "Tokenize meme source string into a vector of tokens."
  [s]
  (let [sc (make-scanner s)
        tokens (transient [])]
    (loop []
      (if (seof? sc)
        (persistent! tokens)
        (let [loc (sloc sc)
              ch (speek sc)]
          (cond
            (whitespace? ch)
            (do (sadvance! sc) (recur))

            (= ch \;)
            (do (read-while sc #(not= % \newline)) (recur))

            (= ch \")
            (do (conj! tokens (tok-at sc :string (read-string-literal sc) loc)) (recur))

            (= ch \\)
            (do (conj! tokens (tok-at sc :char (read-char-literal sc) loc)) (recur))

            ;; # dispatch
            (= ch \#)
            (let [nxt (speek sc 1)]
              (cond
                (= nxt \{) (do (sadvance! sc) (sadvance! sc)
                               (conj! tokens (tok-at sc :open-set "#{" loc)) (recur))
                (= nxt \() (do (sadvance! sc) (sadvance! sc) ; consume #(
                               (conj! tokens (tok-at sc :open-anon-fn "#(" loc))
                               (recur))
                (= nxt \") (do (sadvance! sc)
                               (conj! tokens (tok-at sc :regex (read-regex-body sc) loc)) (recur))
                (= nxt \') (do (sadvance! sc) (sadvance! sc)
                               (conj! tokens (tok-at sc :var-quote "#'" loc)) (recur))
                (= nxt \_) (do (sadvance! sc) (sadvance! sc)
                               (conj! tokens (tok-at sc :discard "#_" loc)) (recur))
                (= nxt \?) (do (sadvance! sc) (sadvance! sc) ; consume #?
                               (let [splice? (and (not (seof? sc)) (= (speek sc) \@))
                                     _ (when splice? (sadvance! sc))
                                     prefix (if splice? "#?@" "#?")]
                                 (conj! tokens (tok-at sc :reader-cond-start prefix loc)))
                               (recur))
                (= nxt \:) (do (sadvance! sc) (sadvance! sc)
                               (let [ns-name (read-symbol-str sc)
                                     prefix (str "#:" ns-name)]
                                 (conj! tokens (tok-at sc :namespaced-map-start prefix loc))
                                 (recur)))
                ;; ##Inf, ##-Inf, ##NaN — symbolic numeric values
                (= nxt \#) (do (sadvance! sc) (sadvance! sc)
                               (let [name (read-symbol-str sc)]
                                 (conj! tokens (tok-at sc :number (str "##" name) loc))
                                 (recur)))
                ;; B8/B9: # followed by non-tag char — clear error instead of empty tagged literal
                :else (if (nil? nxt)
                        (do (sadvance! sc)
                            (errors/meme-error "Unexpected # at end of input — expected a dispatch form like #{}, #\"\", #', #_, or a tagged literal" loc))
                        (if (symbol-start? nxt)
                          (do (sadvance! sc)
                              (let [tag (read-symbol-str sc)]
                                (conj! tokens (tok-at sc :tagged-literal (str "#" tag) loc))
                                (recur)))
                          (do (sadvance! sc)
                              (errors/meme-error (str "Invalid dispatch: #" nxt " — # must be followed by {, \", ', _, ?, :, or a tag name") loc))))))

            (= ch \@) (do (sadvance! sc) (conj! tokens (tok-at sc :deref "@" loc)) (recur))
            (= ch \^) (do (sadvance! sc) (conj! tokens (tok-at sc :meta "^" loc)) (recur))
            (= ch \') (do (sadvance! sc) (conj! tokens (tok-at sc :quote "'" loc)) (recur))
            ;; ` — syntax-quote prefix (like ' for quote)
            (= ch \`) (do (sadvance! sc)
                          (if (seof? sc)
                            (errors/meme-error "Unexpected end of input after ` — expected a form to syntax-quote"
                                               (assoc loc :incomplete true))
                            (conj! tokens (tok-at sc :syntax-quote "`" loc)))
                          (recur))
            (= ch \~) (do (sadvance! sc)
                          (if (and (not (seof? sc)) (= (speek sc) \@))
                            (do (sadvance! sc) (conj! tokens (tok-at sc :unquote-splicing "~@" loc)))
                            (conj! tokens (tok-at sc :unquote "~" loc)))
                          (recur))

            (= ch \() (do (sadvance! sc) (conj! tokens (tok-at sc :open-paren "(" loc)) (recur))
            (= ch \)) (do (sadvance! sc) (conj! tokens (tok-at sc :close-paren ")" loc)) (recur))
            (= ch \[) (do (sadvance! sc) (conj! tokens (tok-at sc :open-bracket "[" loc)) (recur))
            (= ch \]) (do (sadvance! sc) (conj! tokens (tok-at sc :close-bracket "]" loc)) (recur))
            (= ch \{) (do (sadvance! sc) (conj! tokens (tok-at sc :open-brace "{" loc)) (recur))
            (= ch \}) (do (sadvance! sc) (conj! tokens (tok-at sc :close-brace "}" loc)) (recur))

            ;; keyword
            (= ch \:)
            (do (sadvance! sc)
                (let [auto? (and (not (seof? sc)) (= (speek sc) \:))
                      _ (when auto? (sadvance! sc))
                      kw-name (read-symbol-str sc)
                      value (str (if auto? "::" ":") kw-name)]
                  (conj! tokens (tok-at sc :keyword value loc))
                  (recur)))

            ;; number (unsigned)
            (digit? ch)
            (do (conj! tokens (tok-at sc :number (read-number sc) loc)) (recur))

            ;; signed number: sign immediately followed by digit (no space) = negative number.
            ;; sign followed by ( or space = symbol/operator (falls through to symbol branch).
            ;; Whitespace is already consumed above, so (speek sc 1) is the adjacent character.
            (and (or (= ch \-) (= ch \+))
                 (digit? (speek sc 1)))
            (let [sign (sadvance! sc)
                  num (read-number sc)]
              (conj! tokens (tok-at sc :number (str sign num) loc))
              (recur))

            ;; symbol (includes operators like +, -, ->, ->>, >=, etc.)
            (symbol-start? ch)
            (let [sym (read-symbol-str sc)]
              (conj! tokens (tok-at sc :symbol sym loc))
              (recur))

            :else
            (errors/meme-error (str "Unexpected character: " ch) loc)))))))

;; ---------------------------------------------------------------------------
;; Whitespace attachment
;; ---------------------------------------------------------------------------

(def ^:private line-col->offset source/line-col->offset)

(defn attach-whitespace
  "Attach leading whitespace/comments to each token as :ws.
   Computes the gap between consecutive tokens in the source string.
   Trailing whitespace (after last token) is stored as :trailing-ws
   metadata on the returned vector."
  [tokens source]
  (if (empty? tokens)
    (if (seq source)
      (with-meta [] {:trailing-ws source})
      [])
    (let [n (count tokens)
          src-len (count source)]
      (loop [i 0 prev-end 0 out (transient [])]
        (if (>= i n)
          (let [result (persistent! out)
                trailing (when (< prev-end src-len) (subs source prev-end))]
            (if trailing
              (with-meta result {:trailing-ws trailing})
              result))
          ;; Use :offset/:end-offset directly (O(1)) when available,
          ;; fall back to line-col->offset for tokens without offsets.
          (let [tok (nth tokens i)
                tok-start (or (:offset tok)
                              (line-col->offset source (:line tok) (:col tok)))
                ws (when (< prev-end tok-start) (subs source prev-end tok-start))
                tok' (if ws (assoc tok :ws ws) tok)
                tok-end (or (:end-offset tok)
                            (if (and (:end-line tok) (:end-col tok))
                              (line-col->offset source (:end-line tok) (:end-col tok))
                              (+ tok-start (count (:value tok)))))]
            (recur (inc i) tok-end (conj! out tok'))))))))
