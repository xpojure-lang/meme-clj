(ns meme.tools.lexer
  "Generic scanlet builders for the Pratt parser engine.

   Scanlet builders wrap language-specific consume functions into parselets
   that the grammar spec can reference. They bridge the gap between
   'consume characters from source' and 'produce a CST node'.

   Any language can use these builders with its own consume functions."
  (:require [meme.tools.parser :as parser]))

(defn atom-scanlet
  "Build a scanlet that consumes chars via consume-fn, makes a token, returns atom node.
   consume-fn: (fn [^String source ^long len ^long pos] → end-pos)"
  [token-type consume-fn]
  (fn [engine]
    (let [start (parser/cursor engine)
          source (parser/source-str engine)
          len (parser/source-len engine)
          end (consume-fn source len start)]
      (parser/set-pos! engine end)
      (let [tok (parser/make-token! engine token-type start)]
        (parser/cst :atom {:token tok})))))

(defn single-char-scanlet
  "Build a scanlet for a single-character token that delegates to a factory parselet.
   factory-parselet: (fn [engine tok] → CST node)"
  [token-type factory-parselet]
  (fn [engine]
    (let [start (parser/cursor engine)]
      (parser/advance! engine 1)
      (let [tok (parser/make-token! engine token-type start)]
        (factory-parselet engine tok)))))

(defn delimited-scanlet
  "Build a scanlet for an opening delimiter that parses children until close-char."
  [node-type token-type close-char close-type]
  (single-char-scanlet token-type (parser/nud-delimited node-type close-char close-type)))

;; ---------------------------------------------------------------------------
;; Common character predicates
;;
;; Cross-platform: on JVM a character literal is a Character and `int`
;; returns its code point; on CLJS it's a String of length 1 and `int`
;; returns NaN|0=0 for non-numeric strings, which made the old
;; `(<= (int \0) (int ch) (int \9))` check trivially true. `char-code`
;; normalizes both platforms to the character code point.
;; ---------------------------------------------------------------------------

(defn- char-code [ch]
  #?(:clj  (int ^Character ch)
     :cljs (.charCodeAt ^String ch 0)))

(defn digit? [ch]
  (and ch (let [c (char-code ch)] (and (>= c 0x30) (<= c 0x39)))))

(defn ident-start?
  "Default identifier start: [a-zA-Z_]. Override for language-specific rules."
  [ch]
  (and ch (let [c (char-code ch)]
            (or (and (>= c 0x61) (<= c 0x7A))   ; a-z
                (and (>= c 0x41) (<= c 0x5A))   ; A-Z
                (= c 0x5F)))))                   ; _

(defn ident-char? [ch]
  (or (ident-start? ch) (digit? ch)))

;; ---------------------------------------------------------------------------
;; Common consume functions — pure (String, long, long) → long
;; ---------------------------------------------------------------------------

(defn consume-number
  "Consume integer or float: digits [. digits] [e/E [+/-] digits]"
  [^String source ^long len ^long pos]
  (loop [i pos]
    (if (and (< i len) (digit? (.charAt source i)))
      (recur (inc i))
      (let [i (if (and (< i len) (= (.charAt source i) \.)
                       (< (inc i) len) (digit? (.charAt source (inc i))))
                (loop [j (+ i 2)]
                  (if (and (< j len) (digit? (.charAt source j)))
                    (recur (inc j)) j))
                i)]
        (if (and (< i len) (let [c (.charAt source i)] (or (= c \e) (= c \E))))
          (let [j (inc i)
                j (if (and (< j len) (let [c (.charAt source j)] (or (= c \+) (= c \-))))
                    (inc j) j)]
            (loop [k j]
              (if (and (< k len) (digit? (.charAt source k)))
                (recur (inc k)) k)))
          i)))))

(defn consume-identifier
  "Consume [a-zA-Z_][a-zA-Z0-9_]*"
  [^String source ^long len ^long pos]
  (loop [i (inc pos)]
    (if (and (< i len) (ident-char? (.charAt source i)))
      (recur (inc i)) i)))

(defn consume-string
  "Consume double-quoted string with backslash escapes."
  [^String source ^long len ^long pos]
  (loop [i (inc pos)]
    (cond
      (>= i len) i
      (= (.charAt source i) \\) (recur (+ i 2))
      (= (.charAt source i) \") (inc i)
      :else (recur (inc i)))))

;; ---------------------------------------------------------------------------
;; Common trivia consumers — (engine) → trivia-token
;; ---------------------------------------------------------------------------

(defn ws-consumer
  "Consume whitespace (space, tab). Override whitespace-pred for language-specific rules."
  ([engine] (ws-consumer engine (fn [ch] (or (= ch \space) (= ch \tab)))))
  ([engine whitespace-pred]
   (let [start (parser/cursor engine)
         source (parser/source-str engine)
         len (parser/source-len engine)]
     (loop [i start]
       (if (and (< i len) (whitespace-pred (.charAt source i)))
         (recur (inc i))
         (do (parser/set-pos! engine i)
             (parser/make-trivia-token! engine :whitespace start)))))))

(defn newline-consumer [engine]
  (let [start (parser/cursor engine)
        source (parser/source-str engine)
        len (parser/source-len engine)
        ch (.charAt source start)
        end (if (and (= ch \return) (< (inc start) len)
                     (= (.charAt source (inc start)) \newline))
              (+ start 2) (inc start))]
    (parser/set-pos! engine end)
    (parser/make-trivia-token! engine :newline start)))

(defn line-comment-consumer
  "Consume a line comment from current position to end of line.
   prefix-len: number of chars to skip (e.g. 2 for //, 1 for ;)."
  [engine prefix-len]
  (let [start (parser/cursor engine)
        source (parser/source-str engine)
        len (parser/source-len engine)]
    (parser/advance! engine prefix-len)
    (loop [i (+ start prefix-len)]
      (if (or (>= i len) (= (.charAt source i) \newline))
        (do (parser/set-pos! engine i)
            (parser/make-trivia-token! engine :comment start))
        (recur (inc i))))))

(defn block-comment-consumer
  "Consume a block comment (* ... *). Supports nesting."
  [engine]
  (let [start (parser/cursor engine)
        source (parser/source-str engine)
        len (parser/source-len engine)]
    (parser/advance! engine 2)
    (loop [i (+ start 2) depth 1]
      (cond
        (>= i len)
        (do (parser/set-pos! engine i)
            (parser/make-trivia-token! engine :comment start))
        (and (= (.charAt source i) \() (< (inc i) len) (= (.charAt source (inc i)) \*))
        (recur (+ i 2) (inc depth))
        (and (= (.charAt source i) \*) (< (inc i) len) (= (.charAt source (inc i)) \)))
        (if (= depth 1)
          (do (parser/set-pos! engine (+ i 2))
              (parser/make-trivia-token! engine :comment start))
          (recur (+ i 2) (dec depth)))
        :else (recur (inc i) depth)))))
