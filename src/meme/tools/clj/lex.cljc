(ns meme.tools.clj.lex
  "Clojure-surface lexical conventions — character predicates, consume
   helpers, and trivia consumers shared across any Clojure-flavored frontend.

   These encode Clojure-specific decisions that aren't part of the
   generic parser toolkit (`meme.tools.lexer`): comma-as-whitespace,
   invisible-char safety in identifiers, `::` auto-resolve keywords,
   `\\uXXXX` / `\\oNNN` / named char literals, `#` and `\\` as
   non-symbol chars, UTF-16 surrogate rejection in atoms, etc.

   Languages with non-Clojure lexical conventions should define their own
   lexlets rather than depend on this namespace."
  (:require [meme.tools.parser :as pratt]
            [meme.tools.lexer :as lexer]))

;; ---------------------------------------------------------------------------
;; Character predicates
;; ---------------------------------------------------------------------------

(def digit? lexer/digit?)

(defn whitespace-char? [ch]
  (and ch (let [c (lexer/char-code ch)]
            (or (= c 0x20)    ; space
                (= c 0x09)    ; tab
                (= c 0x0C)    ; form feed
                (= c 0x0B)    ; vertical tab
                (= c 0x2C)    ; comma
                (= c 0x2007)  ; figure space (Zs — visually indistinguishable from space)
                #?(:clj (and (not= c 0x0A) (not= c 0x0D)
                             (not= c 0x2028) (not= c 0x2029)
                             (Character/isWhitespace (char c)))
                   :cljs (or (<= 0x2000 c 0x2006)
                             (<= 0x2008 c 0x200A)
                             (= c 0x1680)
                             (= c 0x205F) (= c 0x3000)))))))

(defn newline-char? [ch]
  (and ch (let [c (lexer/char-code ch)]
            (or (= c 0x0A) (= c 0x0D)
                (= c 0x2028) (= c 0x2029)))))

(defn- invisible-char?
  [c]
  (or (<= 0x0000 c 0x001F) (= c 0x007F)
      (<= 0x0080 c 0x009F)
      (= c 0x00A0) (= c 0x00AD)
      (<= 0xD800 c 0xDFFF)
      (<= 0x200B c 0x200F) (<= 0x202A c 0x202E)
      (<= 0x2060 c 0x2069) (= c 0xFEFF)
      ;; Variation selectors (VS1-VS16) — invisible glyph modifiers that
      ;; can be used to craft look-alike symbols. Reject in identifiers.
      (<= 0xFE00 c 0xFE0F)))

(defn symbol-start? [ch]
  (and ch
       (not (whitespace-char? ch))
       (not (newline-char? ch))
       (not (digit? ch))
       (let [c (lexer/char-code ch)]
         (not (contains?
               #{0x28 0x29 0x5B 0x5D 0x7B 0x7D
                 0x22 0x3B 0x40 0x5E 0x60 0x7E
                 0x5C 0x23 0x3A 0x27}
               c)))
       (not (invisible-char? (lexer/char-code ch)))))

(defn symbol-char? [ch]
  (and ch
       (not (whitespace-char? ch))
       (not (newline-char? ch))
       (let [c (lexer/char-code ch)]
         (not (contains?
               #{0x28 0x29 0x5B 0x5D 0x7B 0x7D
                 0x22 0x3B 0x40 0x5E 0x60 0x7E
                 0x5C}
               c)))
       (not (invisible-char? (lexer/char-code ch)))))

(defn- number-char? [ch]
  (and ch (let [c (lexer/char-code ch)]
            (or (and (>= c 0x30) (<= c 0x39))
                (and (>= c 0x41) (<= c 0x5A))
                (and (>= c 0x61) (<= c 0x7A))
                (= c 0x2E) (= c 0x2F)
                (= c 0x2B) (= c 0x2D)
                (= c 0x5F)))))

;; ---------------------------------------------------------------------------
;; Consume helpers — pure (source, len, pos) → end-pos functions
;; ---------------------------------------------------------------------------

(defn- consume-whitespace [^String source ^long len ^long pos]
  (loop [i (inc pos)]
    (if (and (< i len) (whitespace-char? (.charAt source i)))
      (recur (inc i))
      i)))

(defn- consume-newline [^String source ^long len ^long pos]
  (let [ch (.charAt source pos)]
    (cond
      (= ch \newline) (inc pos)
      (= ch \return) (if (and (< (inc pos) len) (= (.charAt source (inc pos)) \newline))
                       (+ pos 2)
                       (inc pos))
      :else (inc pos))))

(defn- consume-comment [^String source ^long len ^long pos]
  (loop [i (inc pos)]
    (if (or (>= i len) (newline-char? (.charAt source i)))
      i
      (recur (inc i)))))

(defn consume-string [^String source ^long len ^long pos]
  (loop [i (inc pos)]
    (cond
      (>= i len) i
      (= (.charAt source i) \\) (recur (min (+ i 2) len))
      (= (.charAt source i) \") (inc i)
      :else (recur (inc i)))))

(defn consume-char-literal [^String source ^long len ^long pos]
  (if (>= (inc pos) len)
    (inc pos)
    (let [next-ch (.charAt source (inc pos))]
      (cond
        (= next-ch \u)
        ;; Consume up to 4 hex digits. If at least one hex digit was
        ;; consumed AND trailing alphanumerics follow, keep consuming so
        ;; the malformed literal reaches resolve-char (which rejects
        ;; \u00410 / \u0041G / \u00g1 as "Invalid character literal").
        ;; If NO hex digits at all (e.g. \uXYZW), leave behavior as before
        ;; — the \u char alone + separate symbol — so pre-existing tokens
        ;; are unaffected.
        (let [[hex-end cnt] (loop [i (+ pos 2) cnt 0]
                              (if (and (< i len) (< cnt 4)
                                       (let [c (lexer/char-code (.charAt source i))]
                                         (or (and (>= c 0x30) (<= c 0x39))
                                             (and (>= c 0x41) (<= c 0x46))
                                             (and (>= c 0x61) (<= c 0x66)))))
                                (recur (inc i) (inc cnt))
                                [i cnt]))]
          (if (pos? cnt)
            (loop [i hex-end]
              (if (and (< i len)
                       (let [c (lexer/char-code (.charAt source i))]
                         (or (and (>= c 0x30) (<= c 0x39))
                             (and (>= c 0x41) (<= c 0x5A))
                             (and (>= c 0x61) (<= c 0x7A)))))
                (recur (inc i))
                i))
            hex-end))

        (= next-ch \o)
        (loop [i (+ pos 2) cnt 0]
          (if (and (< i len) (< cnt 3)
                   (let [c (lexer/char-code (.charAt source i))]
                     (and (>= c 0x30) (<= c 0x37))))
            (recur (inc i) (inc cnt))
            i))

        (let [c (lexer/char-code next-ch)]
          (and (>= c 0x61) (<= c 0x7A)))
        (loop [i (+ pos 2)]
          (if (and (< i len)
                   (let [c (lexer/char-code (.charAt source i))]
                     (and (>= c 0x61) (<= c 0x7A))))
            (recur (inc i))
            i))

        :else (+ pos 2)))))

(defn consume-keyword [^String source ^long len ^long pos]
  (let [i (inc pos)]
    (if (and (< i len) (= (.charAt source i) \:))
      (let [j (+ pos 2)]
        (loop [i j saw-slash false]
          (if (and (< i len) (symbol-char? (.charAt source i)))
            (let [ch (.charAt source i)]
              (if (and (= ch \/) (not saw-slash))
                (recur (inc i) true)
                (recur (inc i) saw-slash)))
            i)))
      (loop [i i saw-slash false]
        (if (and (< i len) (symbol-char? (.charAt source i)))
          (let [ch (.charAt source i)]
            (if (and (= ch \/) (not saw-slash))
              (recur (inc i) true)
              (recur (inc i) saw-slash)))
          i)))))

(defn consume-number [^String source ^long len ^long pos]
  (loop [i (inc pos)]
    (if (and (< i len) (number-char? (.charAt source i)))
      (recur (inc i))
      i)))

(defn consume-symbol [^String source ^long len ^long pos]
  (loop [i (inc pos) saw-slash false]
    (if (and (< i len) (symbol-char? (.charAt source i)))
      (let [ch (.charAt source i)]
        (if (and (= ch \/) (not saw-slash))
          (recur (inc i) true)
          (recur (inc i) saw-slash)))
      i)))

;; ---------------------------------------------------------------------------
;; Trivia consumers — return trivia token maps
;; ---------------------------------------------------------------------------

(defn ws-consumer [engine]
  (let [start (pratt/cursor engine)
        source (pratt/source-str engine)
        len (pratt/source-len engine)
        end (consume-whitespace source len start)]
    (pratt/set-pos! engine end)
    (pratt/make-trivia-token! engine :whitespace start)))

(defn newline-consumer [engine]
  (let [start (pratt/cursor engine)
        source (pratt/source-str engine)
        len (pratt/source-len engine)
        end (consume-newline source len start)]
    (pratt/set-pos! engine end)
    (pratt/make-trivia-token! engine :newline start)))

(defn comment-consumer [engine]
  (let [start (pratt/cursor engine)
        source (pratt/source-str engine)
        len (pratt/source-len engine)
        end (consume-comment source len start)]
    (pratt/set-pos! engine end)
    (pratt/make-trivia-token! engine :comment start)))

(defn bom-consumer
  "BOM consumer. Only matches at position 0. Returns nil if BOM is not
   at position 0, which causes skip-trivia! to stop (no match)."
  [engine]
  (let [start (pratt/cursor engine)]
    (when (zero? start)
      (pratt/advance! engine 1)
      (pratt/make-trivia-token! engine :bom start))))
