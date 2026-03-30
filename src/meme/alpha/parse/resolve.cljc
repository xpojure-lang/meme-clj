(ns meme.alpha.parse.resolve
  "Value resolution: converts raw token text to Clojure values.
   All resolution is native — no delegation to read-string."
  (:require [meme.alpha.errors :as errors]
            [meme.alpha.forms :as forms]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; String resolution — process escape sequences natively
;; ---------------------------------------------------------------------------

(def ^:private string-escapes
  {\n \newline \t \tab \r \return \\ \\ \" \" \b \backspace \f \formfeed})

(defn- parse-unicode-escape
  "Parse \\uNNNN from raw string starting at idx (pointing at 'u').
   Returns [char new-idx]."
  [raw idx loc]
  (let [end (min (+ idx 5) (count raw))
        consumed (- end idx 1)]
    (when (< consumed 4)
      (errors/meme-error (str "Invalid unicode escape in string — expected 4 hex digits after \\u, got " consumed)
                         loc))
    (let [hex (subs raw (inc idx) end)
          code (try #?(:clj (Integer/parseInt hex 16)
                       :cljs (let [n (js/parseInt hex 16)]
                               (when (js/isNaN n) (throw (ex-info "NaN" {})))
                               n))
                    (catch #?(:clj Exception :cljs :default) _
                      (errors/meme-error (str "Invalid unicode escape \\u" hex) loc)))]
      [(char code) end])))

(defn resolve-string
  "Resolve a quoted string token to a string value. Handles escape sequences natively.
   Wraps in MemeRaw when the string contains unicode escapes (\\uNNNN) that would
   be lost through pr-str roundtrip."
  [raw loc]
  (let [inner (subs raw 1 (dec (count raw))) ; strip surrounding quotes
        len (count inner)
        has-unicode? (volatile! false)
        resolved
        (loop [i 0 sb #?(:clj (StringBuilder.) :cljs #js [])]
          (if (>= i len)
            #?(:clj (.toString sb) :cljs (.join sb ""))
            (let [ch (.charAt inner i)]
              (if (= ch \\)
                (if (>= (inc i) len)
                  (errors/meme-error "Unterminated escape sequence in string" loc)
                  (let [esc (.charAt inner (inc i))]
                    (if-let [replacement (get string-escapes esc)]
                      (recur (+ i 2) #?(:clj (.append sb replacement) :cljs (do (.push sb replacement) sb)))
                      (if (= esc \u)
                        (let [[ch' new-i] (parse-unicode-escape inner (inc i) loc)]
                          (vreset! has-unicode? true)
                          (recur new-i #?(:clj (.append sb ch') :cljs (do (.push sb ch') sb))))
                        (errors/meme-error (str "Unsupported escape sequence \\" esc " in string") loc)))))
                (recur (inc i) #?(:clj (.append sb ch) :cljs (do (.push sb (str ch)) sb)))))))]
    (if @has-unicode?
      (forms/->MemeRaw resolved raw)
      resolved)))

;; ---------------------------------------------------------------------------
;; Character resolution
;; ---------------------------------------------------------------------------

(def ^:private named-chars
  {"newline" \newline "space" \space "tab" \tab "backspace" \backspace
   "formfeed" \formfeed "return" \return})

(defn resolve-char
  "Resolve a character literal token (e.g. \\a, \\newline, \\u0041) to a char value."
  [raw loc]
  (let [name-part (subs raw 1)] ; strip leading backslash
    (cond
      (= 1 (count name-part))
      (.charAt name-part 0)

      (contains? named-chars name-part)
      (get named-chars name-part)

      (and (str/starts-with? name-part "u") (= 5 (count name-part)))
      (let [hex (subs name-part 1)
            code (try #?(:clj (Integer/parseInt hex 16)
                         :cljs (let [n (js/parseInt hex 16)]
                                 (when (js/isNaN n) (throw (ex-info "NaN" {})))
                                 n))
                      (catch #?(:clj Exception :cljs :default) _
                        (errors/meme-error (str "Invalid unicode character \\u" hex) loc)))]
        (forms/->MemeRaw (char code) raw))

      #?@(:clj [(and (str/starts-with? name-part "o")
                      (<= 2 (count name-part) 4))
                 (let [oct (subs name-part 1)
                       code (try (Integer/parseInt oct 8)
                                 (catch Exception _
                                   (errors/meme-error (str "Invalid octal character \\" name-part) loc)))]
                   (when (> code 0377)
                     (errors/meme-error (str "Octal character out of range: \\" name-part) loc))
                   (forms/->MemeRaw (char code) raw))])

      :else
      (errors/meme-error (str "Invalid character literal: " raw) loc))))

;; ---------------------------------------------------------------------------
;; Number resolution
;; ---------------------------------------------------------------------------

(defn resolve-number
  "Resolve a number token to a numeric value. Handles integers, floats,
   hex (0x), octal (0), radix (NNr), ratios, BigInt (N), BigDecimal (M),
   and special values (##Inf, ##-Inf, ##NaN)."
  [raw loc]
  (try
    (cond
      ;; Special float values
      (= raw "##Inf")  #?(:clj Double/POSITIVE_INFINITY :cljs js/Infinity)
      (= raw "##-Inf") #?(:clj Double/NEGATIVE_INFINITY :cljs (- js/Infinity))
      (= raw "##NaN")  #?(:clj Double/NaN :cljs js/NaN)

      #?@(:clj
          [;; BigDecimal
           (str/ends-with? raw "M")
           (BigDecimal. (subs raw 0 (dec (count raw))))

           ;; BigInt — strip leading + (BigInteger rejects it)
           (str/ends-with? raw "N")
           (let [s (subs raw 0 (dec (count raw)))
                 s (cond-> s (str/starts-with? s "+") (subs 1))]
             (clojure.lang.BigInt/fromBigInteger (java.math.BigInteger. s)))

           ;; Ratio — use BigInteger to handle arbitrary-precision components
           ;; Strip leading + from numerator (BigInteger rejects it)
           (str/includes? raw "/")
           (let [idx   (str/index-of raw "/")
                 num-s (subs raw 0 idx)
                 num-s (cond-> num-s (str/starts-with? num-s "+") (subs 1))
                 num   (java.math.BigInteger. num-s)
                 den   (java.math.BigInteger. (subs raw (inc idx)))]
             (/ (clojure.lang.BigInt/fromBigInteger num)
                (clojure.lang.BigInt/fromBigInteger den)))

           ;; Hex — wrap in MemeRaw to preserve notation
           (or (str/starts-with? raw "0x") (str/starts-with? raw "0X")
               (str/starts-with? raw "+0x") (str/starts-with? raw "+0X")
               (str/starts-with? raw "-0x") (str/starts-with? raw "-0X"))
           (let [negative? (str/starts-with? raw "-")
                 hex-str (subs raw (if (or (str/starts-with? raw "+") (str/starts-with? raw "-")) 3 2))
                 bi (java.math.BigInteger. hex-str 16)
                 bi (if negative? (.negate bi) bi)
                 val (if (< (.bitLength bi) 64) (.longValue bi) (clojure.lang.BigInt/fromBigInteger bi))]
             (forms/->MemeRaw val raw))

           ;; Octal — wrap in MemeRaw to preserve notation
           (and (or (str/starts-with? raw "0") (str/starts-with? raw "-0") (str/starts-with? raw "+0"))
                (> (count raw) 1)
                (not (str/starts-with? raw "0x")) (not (str/starts-with? raw "0X"))
                (not (str/starts-with? raw "+0x")) (not (str/starts-with? raw "+0X"))
                (not (str/starts-with? raw "-0x")) (not (str/starts-with? raw "-0X"))
                (not (str/includes? raw "."))
                (not (str/includes? raw "e")) (not (str/includes? raw "E"))
                (re-matches #"[+-]?0[0-7]+" raw))
           (let [negative? (str/starts-with? raw "-")
                 oct-str (subs raw (if (or (str/starts-with? raw "+") (str/starts-with? raw "-")) 2 1))
                 bi (java.math.BigInteger. oct-str 8)
                 bi (if negative? (.negate bi) bi)
                 val (if (< (.bitLength bi) 64) (.longValue bi) (clojure.lang.BigInt/fromBigInteger bi))]
             (forms/->MemeRaw val raw))

           ;; Radix NNrDDDD — wrap in MemeRaw to preserve notation
           (re-matches #"[+-]?\d{1,2}r[0-9a-zA-Z]+" raw)
           (let [negative? (str/starts-with? raw "-")
                 s (cond-> raw (or (str/starts-with? raw "+") (str/starts-with? raw "-")) (subs 1))
                 idx (str/index-of s "r")
                 radix (Integer/parseInt (subs s 0 idx))
                 digits (subs s (inc idx))
                 bi (java.math.BigInteger. digits (int radix))
                 bi (if negative? (.negate bi) bi)
                 val (if (< (.bitLength bi) 64) (.longValue bi) (clojure.lang.BigInt/fromBigInteger bi))]
             (forms/->MemeRaw val raw))]

          :cljs
          [;; BigInt N suffix — not supported in CLJS
           (str/ends-with? raw "N")
           (errors/meme-error "BigInt literals (N suffix) are not supported in ClojureScript" loc)

           ;; BigDecimal M suffix — not supported in CLJS
           (str/ends-with? raw "M")
           (errors/meme-error "BigDecimal literals (M suffix) are not supported in ClojureScript" loc)

           ;; Ratio — not supported in CLJS
           (str/includes? raw "/")
           (errors/meme-error "Ratio literals are not supported in ClojureScript" loc)

           ;; Hex — not supported in CLJS
           (or (str/starts-with? raw "0x") (str/starts-with? raw "0X")
               (str/starts-with? raw "+0x") (str/starts-with? raw "+0X")
               (str/starts-with? raw "-0x") (str/starts-with? raw "-0X"))
           (errors/meme-error "Hex literals are not supported in ClojureScript" loc)

           ;; Radix NNrDDDD — not supported in CLJS
           (re-matches #"[+-]?\d{1,2}r[0-9a-zA-Z]+" raw)
           (errors/meme-error "Radix literals are not supported in ClojureScript" loc)

           ;; Octal (leading 0, not 0x, not just "0", not float) — not supported in CLJS
           (and (or (str/starts-with? raw "0") (str/starts-with? raw "-0") (str/starts-with? raw "+0"))
                (> (count raw) 1)
                (not (str/starts-with? raw "0x")) (not (str/starts-with? raw "0X"))
                (not (str/starts-with? raw "+0x")) (not (str/starts-with? raw "+0X"))
                (not (str/starts-with? raw "-0x")) (not (str/starts-with? raw "-0X"))
                (not (str/includes? raw "."))
                (not (str/includes? raw "e")) (not (str/includes? raw "E"))
                (re-matches #"[+-]?0[0-7]+" raw))
           (errors/meme-error "Octal literals are not supported in ClojureScript" loc)])

      ;; Float (contains . or e/E) — wrap in MemeRaw when scientific notation
      (or (str/includes? raw ".") (str/includes? raw "e") (str/includes? raw "E"))
      (let [val #?(:clj (Double/parseDouble raw) :cljs (js/parseFloat raw))]
        (if (or (str/includes? raw "e") (str/includes? raw "E"))
          (forms/->MemeRaw val raw)
          val))

      ;; Plain integer
      :else
      #?(:clj (Long/parseLong raw) :cljs (js/parseInt raw 10)))
    (catch #?(:clj Exception :cljs :default) e
      (if (instance? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e)
        (throw e) ; re-throw meme errors
        (errors/meme-error (str "Invalid number " raw " — " (#?(:clj ex-message :cljs .-message) e))
                           (assoc loc :cause e))))))

;; ---------------------------------------------------------------------------
;; Regex resolution
;; ---------------------------------------------------------------------------

(defn resolve-regex
  "Resolve a regex literal token to a regex value."
  [raw loc]
  (let [pattern (subs raw 2 (dec (count raw)))] ; strip #" and "
    (try #?(:clj (java.util.regex.Pattern/compile pattern)
            :cljs (js/RegExp. pattern))
         (catch #?(:clj Exception :cljs :default) e
           (errors/meme-error (str "Invalid regex " raw " — " (#?(:clj ex-message :cljs .-message) e))
                              (assoc loc :cause e))))))

;; ---------------------------------------------------------------------------
;; Auto-resolve keywords
;; ---------------------------------------------------------------------------

(defn resolve-auto-keyword
  "Resolve an auto-resolve keyword (::foo).
   If resolve-fn is provided, resolves at read time.
   Otherwise, defers to eval time via forms/deferred-auto-keyword."
  [raw loc resolve-fn]
  (if resolve-fn
    (try (resolve-fn raw)
         (catch #?(:clj Exception :cljs :default) e
           (let [cause-msg (#?(:clj ex-message :cljs .-message) e)
                 detail (if cause-msg
                          (str "Failed to resolve keyword " raw " — " cause-msg)
                          (str "Failed to resolve keyword: " raw))]
             (errors/meme-error detail (assoc loc :cause e)))))
    #?(:clj (forms/deferred-auto-keyword raw)
       :cljs (errors/meme-error
               (str "Auto-resolve keywords (" raw ") require the :resolve-keyword option in ClojureScript")
               (assoc loc :hint "Pass :resolve-keyword (fn [kw] ...) in the opts map to meme->forms")))))

;; ---------------------------------------------------------------------------
;; Tagged literals
;; ---------------------------------------------------------------------------

(defn resolve-tagged-literal
  "Resolve a tagged literal. JVM: produces TaggedLiteral. CLJS: error."
  [tag data loc]
  #?(:clj (tagged-literal tag data)
     :cljs (errors/meme-error
             (str "Tagged literals (#" tag ") are not supported in ClojureScript meme reader.")
             loc)))
