(ns meme.tools.lexer
  "Generic scanlet builders for the Pratt parser engine.

   Scanlet builders wrap language-specific consume functions into parselets
   that the grammar spec can reference. They bridge the gap between
   'consume characters from source' and 'produce a CST node'.

   This namespace stays deliberately opinion-free: factories + cross-platform
   primitives only. Lexical conventions (identifier shapes, number grammars,
   string delimiters, whitespace and comment syntax) belong in the lang that
   wants them — see e.g. `m1clj-lang.lexlets` for Clojure-flavored
   conventions."
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
;; Cross-platform character primitives
;;
;; On JVM a character literal is a Character and `int` returns its code
;; point; on CLJS it's a String of length 1 and `int` returns 0 for
;; non-numeric strings, which made the old `(<= (int \0) (int ch) (int \9))`
;; check trivially true. `char-code` normalizes both platforms.
;; ---------------------------------------------------------------------------

(defn char-code
  "Character code point. Normalizes the JVM/CLJS asymmetry: on JVM `ch` is a
   Character and `int` returns the code point; on CLJS `ch` is a one-char
   String. The CLJS `(str ch)` coercion is defensive against callers passing
   a char-typed value in the JS world."
  [ch]
  #?(:clj  (int ^Character ch)
     :cljs (.charCodeAt (str ch) 0)))

(defn digit?
  "True if `ch` is an ASCII digit (0–9). Cross-platform (normalizes JVM
   Character vs CLJS single-char String via `char-code`)."
  [ch]
  (and ch (let [c (char-code ch)] (and (>= c 0x30) (<= c 0x39)))))
