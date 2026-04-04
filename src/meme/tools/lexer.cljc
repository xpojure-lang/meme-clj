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
