(ns meme-lang.resolve
  "Value resolution: converts raw token text to Clojure values.
   All resolution is native — no delegation to read-string."
  (:require [meme-lang.errors :as errors]
            [meme-lang.forms :as forms]
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
                       :cljs (let [n (if (re-matches #"[0-9a-fA-F]{4}" hex)
                                       (js/parseInt hex 16)
                                       js/NaN)]
                               (when (js/isNaN n) (throw (ex-info "NaN" {})))
                               n))
                    (catch #?(:clj Exception :cljs :default) _
                      (errors/meme-error (str "Invalid unicode escape \\u" hex) loc)))]
      ;; NOTE: Clojure accepts surrogate code points in strings ("\uD800").
      ;; We match Clojure's behavior — no rejection of surrogates.
      [(char code) end])))

(defn resolve-string
  "Resolve a quoted string token to a string value. Handles escape sequences natively.
   Wraps in MemeRaw when the string contains unicode escapes (\\uNNNN) that would
   be lost through pr-str roundtrip."
  [raw loc]
  (when (or (< (count raw) 2)
            (not= (.charAt ^String raw (dec (count raw))) \"))
    (errors/meme-error "Unterminated string literal" (assoc loc :incomplete true)))
  (let [inner (subs raw 1 (dec (count raw)))] ; strip surrounding quotes
    ;; Fast path: most strings have no escapes — skip char-by-char loop
    (if (not (str/includes? inner "\\"))
      inner
      ;; Slow path: has escapes, process char-by-char
      (let [len (count inner)
            has-unicode? (volatile! false)
            resolved
            (loop [i 0 sb #?(:clj (StringBuilder.) :cljs #js [])]
              (if (>= i len)
                #?(:clj (.toString sb) :cljs (.join sb ""))
                (let [ch (nth inner i)]
                  (if (= ch \\)
                    (if (>= (inc i) len)
                      (errors/meme-error "Unterminated escape sequence in string" loc)
                      (let [esc (nth inner (inc i))]
                        (if-let [replacement (get string-escapes esc)]
                          (recur (+ i 2) #?(:clj (.append sb replacement) :cljs (do (.push sb replacement) sb)))
                          (if (= esc \u)
                            (let [[ch' new-i] (parse-unicode-escape inner (inc i) loc)]
                              (vreset! has-unicode? true)
                              (recur new-i #?(:clj (.append sb ch') :cljs (do (.push sb ch') sb))))
                            ;; RT3-F11: octal escapes \0-\377 in strings (JVM only, matches Clojure)
                            #?(:clj
                               (let [esc-int (int esc)]
                                 (if (and (>= esc-int (int \0)) (<= esc-int (int \7)))
                                   ;; Read up to 3 octal digits
                                   (let [start (inc i)
                                         end (min (+ start 3) len)
                                         oct-end (loop [j (inc start)]
                                                   (if (and (< j end)
                                                            (let [c (int (nth inner j))]
                                                              (and (>= c (int \0)) (<= c (int \7)))))
                                                     (recur (inc j))
                                                     j))
                                         oct-str (subs inner start oct-end)
                                         code (Integer/parseInt oct-str 8)]
                                     (when (> code 0377)
                                       (errors/meme-error (str "Octal escape out of range: \\" oct-str) loc))
                                     (vreset! has-unicode? true)
                                     (recur oct-end (.append sb (char code))))
                                   (errors/meme-error (str "Unsupported escape sequence \\" esc " in string") loc)))
                               :cljs
                               (errors/meme-error (str "Unsupported escape sequence \\" esc " in string") loc))))))
                    (recur (inc i) #?(:clj (.append sb ch) :cljs (do (.push sb (str ch)) sb)))))))]
        (if @has-unicode?
          (forms/->MemeRaw resolved raw)
          resolved)))))

;; ---------------------------------------------------------------------------
;; Character resolution
;; ---------------------------------------------------------------------------

(def ^:private named-chars
  ;; JVM-only because CLJS has no char type (Phase 1e rejects chars on CLJS)
  ;; NOTE: Clojure's LispReader does NOT support \delete, \null, \nul — removed.
  {"newline" \newline "space" \space "tab" \tab "backspace" \backspace
   "formfeed" \formfeed "return" \return})

(defn resolve-char
  "Resolve a character literal token (e.g. \\a, \\newline, \\u0041) to a char value."
  [raw loc]
  ;; NOTE: On CLJS, char values are JS strings (single-char). This is a known
  ;; platform limitation — char literals read successfully but roundtrip as strings.
  ;; Documented in doc/PRD.md known limitations.
  (let [name-part (subs raw 1)] ; strip leading backslash
    (cond
      ;; RT3-F5: use nth instead of .charAt for portability (nth returns char on JVM,
      ;; single-char string on CLJS — both work for downstream comparisons).
      ;; Note: on CLJS, char values are JS strings — this is a known platform limitation.
      (= 1 (count name-part))
      (nth name-part 0)

      (contains? named-chars name-part)
      (get named-chars name-part)

      (and (str/starts-with? name-part "u") (= 5 (count name-part)))
      (let [hex (subs name-part 1)
            ;; Validate hex digits up front — on CLJS, parseInt with radix 16
            ;; silently accepts "00g1" as 0 (parses prefix, ignores the rest).
            _ (when-not (re-matches #"[0-9A-Fa-f]{4}" hex)
                (errors/meme-error (str "Invalid character literal: " raw) loc))
            code (try #?(:clj (Integer/parseInt hex 16)
                         :cljs (let [n (js/parseInt hex 16)]
                                 (when (js/isNaN n) (throw (ex-info "NaN" {})))
                                 n))
                      (catch #?(:clj Exception :cljs :default) _
                        (errors/meme-error (str "Invalid character literal: " raw) loc)))]
        ;; M10: reject surrogate range — same check as parse-unicode-escape for strings
        (when (and (>= code 0xD800) (<= code 0xDFFF))
          (errors/meme-error
            (str "Invalid unicode character \\u" hex
                 " — code point is in the surrogate range (U+D800..U+DFFF)")
            loc))
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

      ;; Fast path: plain decimal integer (most common number type)
      ;; Matches 42, -7, +123 but NOT 0x.., 07 (leading zero), 42N, 3.14, 1/2, etc.
      (re-matches #"[+-]?[1-9]\d*" raw)
      #?(:clj (try (Long/parseLong raw)
                   (catch NumberFormatException _
                     (clojure.lang.BigInt/fromBigInteger (java.math.BigInteger. ^String raw))))
         :cljs (let [n (js/parseInt raw 10)]
                 (when (> (js/Math.abs n) 9007199254740991)
                   (errors/meme-error
                    (str "Integer " raw " exceeds JavaScript safe integer range (Number.MAX_SAFE_INTEGER) — value would lose precision")
                    loc))
                 n))

      #?@(:clj
          [;; BigDecimal — but not radix (e.g. 36rABCM where M is digit 22)
           (and (str/ends-with? raw "M")
                (not (re-find #"^[+-]?\d{1,2}r" raw)))
           (BigDecimal. (subs raw 0 (dec (count raw))))

           ;; BigInt — strip N suffix, then detect base (octal, hex, radix, decimal)
           ;; Guard: not radix (e.g. 36rZZN where N is digit 23)
           (and (str/ends-with? raw "N")
                (not (re-find #"^[+-]?\d{1,2}r" raw)))
           (let [s (subs raw 0 (dec (count raw)))
                 s (cond-> s (str/starts-with? s "+") (subs 1))
                 negative? (str/starts-with? s "-")
                 body (cond-> s negative? (subs 1))]
             (clojure.lang.BigInt/fromBigInteger
               (let [bi (cond
                          ;; Hex: 0xFFN
                          (or (str/starts-with? body "0x") (str/starts-with? body "0X"))
                          (java.math.BigInteger. (subs body 2) 16)
                          ;; Radix: 8r77N
                          (re-matches #"\d{1,2}r[0-9a-zA-Z]+" body)
                          (let [idx (str/index-of body "r")]
                            (java.math.BigInteger. (subs body (inc idx))
                                                   (int (Integer/parseInt (subs body 0 idx)))))
                          ;; Octal: 0777N (starts with 0, >1 digit, all 0-7)
                          (and (str/starts-with? body "0")
                               (> (count body) 1)
                               (re-matches #"0[0-7]+" body))
                          (java.math.BigInteger. (subs body 1) 8)
                          ;; Decimal
                          :else
                          (java.math.BigInteger. body))]
                 (if negative? (.negate bi) bi))))

           ;; Ratio — use BigInteger to handle arbitrary-precision components
           ;; Strip leading + from numerator (BigInteger rejects it)
           ;; When ratio simplifies to an integer, produce Long (not BigInt) to match Clojure
           (str/includes? raw "/")
           (let [idx   (str/index-of raw "/")
                 num-s (subs raw 0 idx)
                 num-s (cond-> num-s (str/starts-with? num-s "+") (subs 1))
                 den-s (subs raw (inc idx))
                 _ (when (or (str/starts-with? den-s "+") (str/starts-with? den-s "-"))
                     (errors/meme-error (str "Invalid number: " raw) loc))
                 num   (java.math.BigInteger. ^String num-s)
                 den   (java.math.BigInteger. ^String den-s)
                 result (/ (clojure.lang.BigInt/fromBigInteger num)
                           (clojure.lang.BigInt/fromBigInteger den))]
             (cond
               (not (integer? result)) result
               (and (<= Long/MIN_VALUE result) (<= result Long/MAX_VALUE)) (long result)
               :else result))

           ;; Hex — wrap in MemeRaw to preserve notation
           (or (str/starts-with? raw "0x") (str/starts-with? raw "0X")
               (str/starts-with? raw "+0x") (str/starts-with? raw "+0X")
               (str/starts-with? raw "-0x") (str/starts-with? raw "-0X"))
           (let [negative? (str/starts-with? raw "-")
                 hex-str (subs raw (if (or (str/starts-with? raw "+") (str/starts-with? raw "-")) 3 2))
                 _ (when (empty? hex-str)
                     (errors/meme-error (str "Empty hex literal: " raw) loc))
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
             (forms/->MemeRaw val raw))

           ;; Invalid octal — starts with 0, not hex/float/valid-octal, contains 8 or 9.
           ;; Clojure rejects these (e.g. 08, 09, 0189). Must error before the plain integer fallback.
           (and (or (str/starts-with? raw "0") (str/starts-with? raw "-0") (str/starts-with? raw "+0"))
                (> (count raw) 1)
                (not (str/starts-with? raw "0x")) (not (str/starts-with? raw "0X"))
                (not (str/starts-with? raw "+0x")) (not (str/starts-with? raw "+0X"))
                (not (str/starts-with? raw "-0x")) (not (str/starts-with? raw "-0X"))
                (not (str/includes? raw "."))
                (not (str/includes? raw "e")) (not (str/includes? raw "E"))
                (re-matches #"[+-]?0\d+" raw))
           (errors/meme-error (str "Invalid number: " raw) loc)]

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
           (errors/meme-error "Octal literals are not supported in ClojureScript" loc)

           ;; Invalid octal — starts with 0, contains 8 or 9 (e.g. 08, 09)
           (and (or (str/starts-with? raw "0") (str/starts-with? raw "-0") (str/starts-with? raw "+0"))
                (> (count raw) 1)
                (not (str/starts-with? raw "0x")) (not (str/starts-with? raw "0X"))
                (not (str/starts-with? raw "+0x")) (not (str/starts-with? raw "+0X"))
                (not (str/starts-with? raw "-0x")) (not (str/starts-with? raw "-0X"))
                (not (str/includes? raw "."))
                (not (str/includes? raw "e")) (not (str/includes? raw "E"))
                (re-matches #"[+-]?0\d+" raw))
           (errors/meme-error (str "Invalid number: " raw) loc)])

      ;; Float (contains . or e/E) — wrap in MemeRaw when scientific notation
      (or (str/includes? raw ".") (str/includes? raw "e") (str/includes? raw "E"))
      (let [val #?(:clj (Double/parseDouble raw) :cljs (js/parseFloat raw))]
        (if (or (str/includes? raw "e") (str/includes? raw "E"))
          (forms/->MemeRaw val raw)
          val))

      ;; Plain integer — auto-promote to BigInt if too large for Long (JVM)
      ;; C7: on CLJS, error if beyond MAX_SAFE_INTEGER to prevent silent precision loss
      :else
      #?(:clj (try (Long/parseLong raw)
                   (catch NumberFormatException _
                     (clojure.lang.BigInt/fromBigInteger (java.math.BigInteger. ^String raw))))
         :cljs (let [n (js/parseInt raw 10)]
                 (when (> (js/Math.abs n) 9007199254740991)
                   (errors/meme-error
                    (str "Integer " raw " exceeds JavaScript safe integer range (Number.MAX_SAFE_INTEGER) — value would lose precision")
                    loc))
                 n)))
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
  (when (or (< (count raw) 3)
            (not= (.charAt ^String raw (dec (count raw))) \"))
    (errors/meme-error "Unterminated regex literal" (assoc loc :incomplete true)))
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
    ;; Defer resolution to eval time on all platforms. On CLJS this avoids
    ;; errors when :: keywords appear in non-matching reader-cond branches
    ;; (e.g. #?(:clj ::foo :cljs :bar) — ::foo must not error on CLJS).
    (forms/deferred-auto-keyword raw)))

;; ---------------------------------------------------------------------------
;; Tagged literals
;; ---------------------------------------------------------------------------

(defn resolve-tagged-literal
  "Resolve a tagged literal. JVM: produces TaggedLiteral. CLJS: error."
  [tag data loc]
  #?(:clj (tagged-literal tag data)
     :cljs (errors/meme-error
            (str "Tagged literals (#" tag ") are not supported in ClojureScript meme reader")
            loc)))
