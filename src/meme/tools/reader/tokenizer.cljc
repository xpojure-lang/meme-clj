(ns meme.tools.reader.tokenizer
  "Byte-level exhaustive tokenizer.

   Structural invariant: the token stream is a partitioning of the input.
   (= input (apply str (map :raw tokens))) — always, for any input.

   Every byte belongs to exactly one token. No gaps, no overlaps, no throws.
   The token type enum is closed and covers all possible byte sequences.
   Invalid input gets :invalid tokens — errors surface at parse time."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Token type enum (closed, exhaustive)
;; ---------------------------------------------------------------------------

(def token-types
  "Complete set of token types. Every byte in any input maps to one of these."
  #{;; Content tokens
    :symbol :number :string :keyword :char-literal :regex
    ;; Structural delimiters
    :open-paren :close-paren :open-bracket :close-bracket
    :open-brace :close-brace
    ;; Dispatch compound openers
    :open-anon-fn :open-set :reader-cond :namespaced-map
    :var-quote :discard :hashtag-symbol
    ;; Prefix operators
    :quote :deref :meta :syntax-quote :unquote :unquote-splicing
    ;; Non-semantic tokens
    :whitespace :newline :comment :bom :shebang
    ;; Error
    :invalid})

;; ---------------------------------------------------------------------------
;; Character predicates
;; ---------------------------------------------------------------------------

(defn- char-code [ch]
  #?(:clj (int ch) :cljs (.charCodeAt (str ch) 0)))

(defn- whitespace-char? [ch]
  (and ch (let [c (char-code ch)]
            (or (= c 0x20)    ; space
                (= c 0x09)    ; tab
                (= c 0x0C)    ; form feed
                (= c 0x0B)    ; vertical tab
                (= c 0x2C)    ; comma
                #?(:clj (and (not= c 0x0A) (not= c 0x0D)
                             (Character/isWhitespace (char c)))
                   :cljs (or (<= 0x2000 c 0x200A)
                             (= c 0x1680) (= c 0x2028) (= c 0x2029)
                             (= c 0x205F) (= c 0x3000)))))))

(defn- newline-char? [ch]
  (and ch (let [c (char-code ch)] (or (= c 0x0A) (= c 0x0D)))))

(defn- digit? [ch]
  (and ch (let [c (char-code ch)] (and (>= c 0x30) (<= c 0x39)))))

(defn- invisible-char?
  "Unicode control chars, non-breaking spaces, zero-width chars, BOM — forbidden in symbols."
  [c]
  (or (<= 0x0000 c 0x001F) (= c 0x007F)
      (<= 0x0080 c 0x009F)
      (= c 0x00A0) (= c 0x00AD)
      (<= 0x200B c 0x200F) (<= 0x202A c 0x202E)
      (<= 0x2060 c 0x2069) (= c 0xFEFF)))

(defn- symbol-start? [ch]
  (and ch
       (not (whitespace-char? ch))
       (not (newline-char? ch))
       (not (digit? ch))
       (let [c (char-code ch)]
         (not (contains?
               #{0x28 0x29 0x5B 0x5D 0x7B 0x7D  ; ( ) [ ] { }
                 0x22 0x3B 0x40 0x5E 0x60 0x7E    ; " ; @ ^ ` ~
                 0x5C 0x23 0x3A 0x27}              ; \ # : '
               c)))
       (not (invisible-char? (char-code ch)))))

(defn- symbol-char? [ch]
  (and ch
       (not (whitespace-char? ch))
       (not (newline-char? ch))
       (let [c (char-code ch)]
         (not (contains?
               #{0x28 0x29 0x5B 0x5D 0x7B 0x7D
                 0x22 0x3B 0x40 0x5E 0x60 0x7E
                 0x5C}
               c)))
       (not (invisible-char? (char-code ch)))))

(defn- number-char? [ch]
  (and ch (let [c (char-code ch)]
            (or (and (>= c 0x30) (<= c 0x39))       ; 0-9
                (and (>= c 0x41) (<= c 0x5A))       ; A-Z
                (and (>= c 0x61) (<= c 0x7A))       ; a-z
                (= c 0x2E) (= c 0x2F)               ; . /
                (= c 0x2B) (= c 0x2D)               ; + -
                (= c 0x5F)))))                       ; _

;; ---------------------------------------------------------------------------
;; Scanner — immutable position tracking
;; ---------------------------------------------------------------------------

(defn- peek-at
  "Peek at character at offset i in source. Returns nil if out of bounds."
  [^String source ^long len ^long i]
  (when (< i len) (#?(:clj .charAt :cljs .charAt) source i)))

;; ---------------------------------------------------------------------------
;; Token consumers — each returns [end-offset] or advances to end of token
;; ---------------------------------------------------------------------------

(defn- consume-whitespace
  "Consume contiguous whitespace characters (not newlines). Returns end offset."
  [source len pos]
  (loop [i (inc pos)]
    (if (and (< i len) (whitespace-char? (peek-at source len i)))
      (recur (inc i))
      i)))

(defn- consume-newline
  "Consume a newline sequence: \\n, \\r\\n, or bare \\r. Returns end offset."
  [source len pos]
  (let [ch (peek-at source len pos)]
    (cond
      (= ch \newline) (inc pos)
      (= ch \return) (if (and (< (inc pos) len) (= (peek-at source len (inc pos)) \newline))
                       (+ pos 2)  ; \r\n
                       (inc pos)) ; bare \r
      :else (inc pos))))

(defn- consume-comment
  "Consume from ; through end of line (not including the newline). Returns end offset."
  [source len pos]
  (loop [i (inc pos)]
    (if (or (>= i len) (newline-char? (peek-at source len i)))
      i
      (recur (inc i)))))

(defn- consume-string
  "Consume from opening \" through closing \" (handling escapes). Returns end offset.
   If unterminated, consumes to EOF."
  [source len pos]
  (loop [i (inc pos)] ; skip opening "
    (cond
      (>= i len) i ; unterminated — consume to EOF
      (= (peek-at source len i) \\) (recur (min (+ i 2) len)) ; skip escape + next char
      (= (peek-at source len i) \") (inc i) ; closing "
      :else (recur (inc i)))))

(defn- consume-regex
  "Consume from opening \" (after #) through closing \". Returns end offset.
   If unterminated, consumes to EOF."
  [source len pos]
  ;; pos points to the " after #
  (consume-string source len pos))

(defn- consume-char-literal
  "Consume a character literal: \\x, \\newline, \\uXXXX, \\oNNN.
   Returns end offset. pos points to the \\."
  [source len pos]
  (if (>= (inc pos) len)
    (inc pos) ; bare \ at EOF
    (let [next-ch (peek-at source len (inc pos))]
      (cond
        ;; \uXXXX — consume up to 5 chars after backslash
        (= next-ch \u)
        (loop [i (+ pos 2) count 0]
          (if (and (< i len) (< count 4)
                   (let [c (char-code (peek-at source len i))]
                     (or (and (>= c 0x30) (<= c 0x39))
                         (and (>= c 0x41) (<= c 0x46))
                         (and (>= c 0x61) (<= c 0x66)))))
            (recur (inc i) (inc count))
            i))

        ;; \oNNN — consume up to 4 chars after backslash (o + 3 octal)
        (= next-ch \o)
        (loop [i (+ pos 2) count 0]
          (if (and (< i len) (< count 3)
                   (let [c (char-code (peek-at source len i))]
                     (and (>= c 0x30) (<= c 0x37))))
            (recur (inc i) (inc count))
            i))

        ;; Named chars: \newline, \space, etc. — consume letters
        (let [c (char-code next-ch)]
          (and (>= c 0x61) (<= c 0x7A))) ; a-z
        (loop [i (+ pos 2)]
          (if (and (< i len)
                   (let [c (char-code (peek-at source len i))]
                     (and (>= c 0x61) (<= c 0x7A))))
            (recur (inc i))
            i))

        ;; Single char: \a, \!, etc.
        :else (+ pos 2)))))

(defn- consume-keyword
  "Consume a keyword token. pos points to the first :. Returns end offset."
  [source len pos]
  (let [i (inc pos)] ; skip first :
    (if (and (< i len) (= (peek-at source len i) \:))
      ;; :: auto-resolve keyword
      (let [j (+ pos 2)]
        (loop [i j saw-slash false]
          (if (and (< i len) (symbol-char? (peek-at source len i)))
            (let [ch (peek-at source len i)]
              (if (and (= ch \/) (not saw-slash))
                (recur (inc i) true)
                (recur (inc i) saw-slash)))
            i)))
      ;; :regular keyword
      (loop [i i saw-slash false]
        (if (and (< i len) (symbol-char? (peek-at source len i)))
          (let [ch (peek-at source len i)]
            (if (and (= ch \/) (not saw-slash))
              (recur (inc i) true)
              (recur (inc i) saw-slash)))
          i)))))

(defn- consume-number
  "Consume a number token. Returns end offset."
  [source len pos]
  (loop [i (inc pos)]
    (if (and (< i len) (number-char? (peek-at source len i)))
      (recur (inc i))
      i)))

(defn- consume-symbol
  "Consume a symbol. Returns end offset."
  [source len pos]
  (loop [i (inc pos) saw-slash false]
    (if (and (< i len) (symbol-char? (peek-at source len i)))
      (let [ch (peek-at source len i)]
        (if (and (= ch \/) (not saw-slash))
          (recur (inc i) true)
          (recur (inc i) saw-slash)))
      i)))

(defn- consume-shebang
  "Consume #! through end of first line. Returns end offset."
  [source len pos]
  (loop [i (+ pos 2)]
    (if (or (>= i len) (newline-char? (peek-at source len i)))
      i
      (recur (inc i)))))

;; ---------------------------------------------------------------------------
;; Dispatch (#) handling
;; ---------------------------------------------------------------------------

(defn- consume-dispatch
  "Handle # dispatch. pos points to #. Returns [type end-offset]."
  [source len pos]
  (let [next-pos (inc pos)]
    (if (>= next-pos len)
      [:invalid (inc pos)] ; bare # at EOF
      (let [next-ch (peek-at source len next-pos)]
        (cond
          (= next-ch \() [:open-anon-fn (+ pos 2)]
          (= next-ch \{) [:open-set (+ pos 2)]
          (= next-ch \') [:var-quote (+ pos 2)]
          (= next-ch \_) [:discard (+ pos 2)]
          (= next-ch \") [:regex (consume-regex source len next-pos)]
          (= next-ch \!) (if (zero? pos)
                           [:shebang (consume-shebang source len pos)]
                           ;; #! mid-file — treat as tagged literal
                           [:invalid (+ pos 2)])
          (= next-ch \:) (let [end (consume-keyword source len next-pos)]
                           [:namespaced-map end])
          (= next-ch \?) (let [after-q (+ pos 2)]
                           (if (and (< after-q len) (= (peek-at source len after-q) \@))
                             [:reader-cond (+ pos 3)]   ; #?@
                             [:reader-cond (+ pos 2)])) ; #?
          (= next-ch \#) ;; ## symbolic value — consume as number-like
          (let [end (loop [i (+ pos 2)]
                      (if (and (< i len) (symbol-char? (peek-at source len i)))
                        (recur (inc i))
                        i))]
            [:number end])
          ;; Tagged literal: #tag — must start with symbol char
          (symbol-start? next-ch)
          [:hashtag-symbol (consume-symbol source len next-pos)]
          ;; Invalid dispatch
          :else [:invalid (+ pos 2)])))))

;; ---------------------------------------------------------------------------
;; Main tokenizer
;; ---------------------------------------------------------------------------

(defn tokenize
  "Tokenize source string into a vector of tokens.
   Structural invariant: (= source (apply str (map :raw tokens))).
   Never throws — invalid input produces :invalid tokens."
  [source]
  (let [len (count source)]
    (if (zero? len)
      []
      (loop [pos 0
             line 1
             col 1
             tokens (transient [])]
        (if (>= pos len)
          (persistent! tokens)
          (let [ch (peek-at source len pos)
                [type end-pos]
                (cond
                  ;; BOM at position 0
                  (and (zero? pos) (= ch \uFEFF))
                  [:bom (inc pos)]

                  ;; Newlines
                  (newline-char? ch)
                  [:newline (consume-newline source len pos)]

                  ;; Whitespace (not newlines)
                  (whitespace-char? ch)
                  [:whitespace (consume-whitespace source len pos)]

                  ;; Comment
                  (= ch \;)
                  [:comment (consume-comment source len pos)]

                  ;; String
                  (= ch \")
                  [:string (consume-string source len pos)]

                  ;; Single-char delimiters
                  (= ch \() [:open-paren (inc pos)]
                  (= ch \)) [:close-paren (inc pos)]
                  (= ch \[) [:open-bracket (inc pos)]
                  (= ch \]) [:close-bracket (inc pos)]
                  (= ch \{) [:open-brace (inc pos)]
                  (= ch \}) [:close-brace (inc pos)]

                  ;; Prefix operators
                  (= ch \') [:quote (inc pos)]
                  (= ch \@) [:deref (inc pos)]
                  (= ch \^) [:meta (inc pos)]
                  (= ch \`) [:syntax-quote (inc pos)]
                  (= ch \~) (if (and (< (inc pos) len)
                                     (= (peek-at source len (inc pos)) \@))
                              [:unquote-splicing (+ pos 2)]
                              [:unquote (inc pos)])

                  ;; Character literal
                  (= ch \\) [:char-literal (consume-char-literal source len pos)]

                  ;; Dispatch #
                  (= ch \#) (consume-dispatch source len pos)

                  ;; Keyword
                  (= ch \:) [:keyword (consume-keyword source len pos)]

                  ;; Number (digit, or sign followed by digit)
                  (digit? ch) [:number (consume-number source len pos)]
                  (and (or (= ch \+) (= ch \-))
                       (< (inc pos) len)
                       (digit? (peek-at source len (inc pos))))
                  [:number (consume-number source len pos)]

                  ;; Symbol
                  (symbol-start? ch) [:symbol (consume-symbol source len pos)]

                  ;; Invalid — single byte
                  :else [:invalid (inc pos)])

                raw (subs source pos end-pos)

                ;; Compute line/col for next position
                [next-line next-col]
                (loop [i pos l line c col]
                  (if (>= i end-pos)
                    [l c]
                    (let [ch (peek-at source len i)]
                      (cond
                        (= ch \newline) (recur (inc i) (inc l) 1)
                        (= ch \return)
                        (if (and (< (inc i) end-pos)
                                 (= (peek-at source len (inc i)) \newline))
                          (recur (+ i 2) (inc l) 1)
                          (recur (inc i) (inc l) 1))
                        :else (recur (inc i) l (inc c))))))]

            (recur end-pos next-line next-col
                   (conj! tokens {:type type
                                  :raw raw
                                  :offset pos
                                  :line line
                                  :col col}))))))))
