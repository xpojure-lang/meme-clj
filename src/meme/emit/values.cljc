(ns meme.emit.values
  "Shared value → string serialization for the printer and rewrite emitter.
   Handles atomic Clojure values (strings, numbers, chars, regex, etc.)
   that both emit paths must render identically."
  (:require [clojure.string :as str]))

(defn emit-regex-str
  "Serialize a regex pattern to its source representation: #\"pattern\"."
  [form]
  (let [raw #?(:clj (.pattern ^java.util.regex.Pattern form) :cljs (.-source form))]
    (str "#\"" (str/replace raw #"\\.|\"" (fn [m] (if (= m "\"") "\\\"" m))) "\"")))

#?(:clj
   (defn emit-char-str
     "Serialize a character to its reader representation: \\newline, \\a, etc."
     [form]
     (let [named {(char 10) "newline" (char 13) "return" (char 9) "tab"
                  (char 32) "space" (char 8) "backspace" (char 12) "formfeed"}]
       (if-let [n (get named form)]
         (str \\ n)
         (let [cp (int form)]
           (if (or (< cp 32) (= cp 127))
             (format "\\u%04X" cp)
             (str \\ form)))))))

(defn emit-number-str
  "Serialize a number, preserving BigDecimal M, BigInt N, and symbolic values."
  [form]
  (cond
    #?@(:clj [(decimal? form) (str form "M")
              (instance? clojure.lang.BigInt form) (str form "N")
              (instance? java.math.BigInteger form) (str form "N")])
    (and (number? form)
         #?(:clj (Double/isNaN (double form))
            :cljs (js/isNaN form)))
    "##NaN"
    (and (number? form)
         #?(:clj (Double/isInfinite (double form))
            :cljs (and (not (js/isFinite form)) (not (js/isNaN form)))))
    (if (pos? (double form)) "##Inf" "##-Inf")
    :else (str form)))

(defn emit-value-str
  "Serialize an atomic value to its string representation.
   Returns the string, or nil if the form is not an atomic value
   (i.e. the caller should handle it with its own dispatch).
   emit-child-fn is called for recursive cases (tagged literal data)."
  [form emit-child-fn]
  (cond
    (nil? form) "nil"
    (boolean? form) (str form)
    (string? form) (pr-str form)
    #?@(:clj [(char? form) (emit-char-str form)])
    (instance? #?(:clj java.util.regex.Pattern :cljs js/RegExp) form)
    (emit-regex-str form)
    (number? form) (emit-number-str form)
    #?@(:clj [(tagged-literal? form)
              (str "#" (.-tag ^clojure.lang.TaggedLiteral form) " "
                   (emit-child-fn (.-form ^clojure.lang.TaggedLiteral form)))])
    :else nil))
