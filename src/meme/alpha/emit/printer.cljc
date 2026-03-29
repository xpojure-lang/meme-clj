(ns meme.alpha.emit.printer
  "meme printer: Clojure forms → meme text."
  (:require [clojure.string :as str]
            [meme.alpha.forms :as forms]))

;; ---------------------------------------------------------------------------
;; Forward declaration
;; ---------------------------------------------------------------------------

(declare print-form)


;; ---------------------------------------------------------------------------
;; Print helpers
;; ---------------------------------------------------------------------------

(defn- print-args
  "Print a sequence of forms separated by spaces."
  [forms]
  (str/join " " (map print-form forms)))

(defn- percent-param?
  "Is sym a % parameter symbol (%1, %2, %&)?"
  [sym]
  (and (symbol? sym)
       (let [n (name sym)]
         (or (= n "%&")
             (and (str/starts-with? n "%")
                  (> (count n) 1)
                  (re-matches #"\d+" (subs n 1)))))))

(defn- max-percent-n
  "Find the max numbered %N param index referenced in a form body.
   Returns max N found (0 if none). Ignores %& (rest params).
   Skips nested (fn ...) bodies — their % params are scoped to the inner fn."
  [form]
  (cond
    (symbol? form)
    (let [n (name form)]
      (if (and (str/starts-with? n "%") (> (count n) 1)
               (re-matches #"\d+" (subs n 1)))
        #?(:clj (Long/parseLong (subs n 1))
           :cljs (js/parseInt (subs n 1) 10))
        0))
    (and (seq? form) (= 'fn (first form))) 0
    (seq? form) (reduce max 0 (map max-percent-n form))
    (vector? form) (reduce max 0 (map max-percent-n form))
    (map? form) (reduce max 0 (mapcat (fn [[k v]] [(max-percent-n k) (max-percent-n v)]) form))
    (set? form) (reduce max 0 (map max-percent-n form))
    #?@(:clj [(tagged-literal? form) (max-percent-n (.-form form))])
    :else 0))

;; ---------------------------------------------------------------------------
;; #() shorthand detection
;; ---------------------------------------------------------------------------

(defn- anon-fn-shorthand?
  "Can (fn [params] body) be printed as #(body)?
   True when: single-body, all params are %-style, and declared param
   count matches body usage (avoids silently changing arity)."
  [form]
  (and (seq? form)
       (= 'fn (first form))
       (= 3 (count form))
       (vector? (second form))
       (let [params (second form)]
         (and (every? percent-param? params)
              (let [declared (count (filter #(not= (name %) "%&") params))]
                (= declared (max-percent-n (nth form 2))))))))

;; ---------------------------------------------------------------------------
;; Main dispatch
;; ---------------------------------------------------------------------------

(defn print-form
  "Print a single Clojure form as meme text."
  [form]
  (cond
    ;; metadata prefix: ^:key, ^Type, or ^{map} — emit before the form
    ;; Filter out :line/:column/:file added by Clojure's compiler/reader
    (and (some? form)
         #?(:clj (instance? clojure.lang.IMeta form)
            :cljs (satisfies? IMeta form))
         (some? (meta form))
         (seq (dissoc (meta form) :line :column :file :ws)))
    (let [m (dissoc (meta form) :line :column :file :ws)
          stripped (with-meta form nil)
          prefix (cond
                   ;; single true-valued keyword: ^:key
                   (and (= 1 (count m))
                        (keyword? (key (first m)))
                        (true? (val (first m))))
                   (str "^" (print-form (key (first m))))
                   ;; single :tag with symbol value: ^Type
                   (and (= 1 (count m))
                        (contains? m :tag)
                        (symbol? (:tag m)))
                   (str "^" (print-form (:tag m)))
                   ;; general map
                   :else
                   (str "^" (print-form m)))]
      (str prefix " " (print-form stripped)))

    ;; nil
    (nil? form) "nil"

    ;; boolean
    (boolean? form) (str form)

    ;; Deferred auto-resolve keywords: (clojure.core/read-string "::foo") → ::foo
    (forms/deferred-auto-keyword? form)
    (forms/deferred-auto-keyword-raw form)

    ;; empty list
    (and (seq? form) (empty? form))
    "()"

    ;; Non-callable heads: nil, true, false are resolved as literals by the reader,
    ;; not as symbols — they cannot be call heads in meme syntax.
    (and (seq? form) (seq form) (contains? #{nil true false} (first form)))
    (throw (ex-info (str "Cannot print list with " (pr-str (first form))
                         " as head — not representable in meme syntax")
                    {:form form}))

    ;; sequences — calls and reader sugar
    (seq? form)
    (let [head (first form)]
      (cond
        (anon-fn-shorthand? form)
        (str "#(" (print-form (nth form 2)) ")")

        ;; @deref
        (= head 'clojure.core/deref) (str "@" (print-form (second form)))

        ;; 'quote — prefix sugar
        (= head 'quote)
        (str "'" (print-form (second form)))

        ;; #'var
        (= head 'var) (str "#'" (print-form (second form)))

        ;; call: head(args...) — uniform for all head types
        :else
        (str (print-form head) "(" (print-args (rest form)) ")")))

    ;; vector
    (vector? form)
    (str "[" (str/join " " (map print-form form)) "]")

    ;; map
    (map? form)
    (str "{"
         (str/join " " (map (fn [[k v]]
                              (str (print-form k) " " (print-form v)))
                            form))
         "}")

    ;; set
    (set? form)
    (str "#{" (str/join " " (map print-form form)) "}")

    ;; symbol
    (symbol? form) (str form)

    ;; keyword
    (keyword? form)
    (if (namespace form)
      (str ":" (namespace form) "/" (name form))
      (str ":" (name form)))

    ;; string
    (string? form) (pr-str form)

    ;; regex — escape bare quotes in the pattern.
    ;; Match escape sequences (\.) atomically so \\" is parsed as
    ;; (escaped-backslash)(bare-quote), not (backslash)(escaped-quote).
    (instance? #?(:clj java.util.regex.Pattern :cljs js/RegExp) form)
    (let [raw #?(:clj (.pattern ^java.util.regex.Pattern form) :cljs (.-source form))]
      (str "#\"" (str/replace raw #"\\.|\"" (fn [m] (if (= m "\"") "\\\"" m))) "\""))

    ;; char (JVM/Babashka only — ClojureScript has no char type)
    #?@(:clj [(char? form)
              (let [named {(char 10) "newline" (char 13) "return" (char 9) "tab"
                           (char 32) "space" (char 8) "backspace" (char 12) "formfeed"}]
                (if-let [n (get named form)]
                  (str \\ n)
                  (str \\ form)))])

    ;; number — preserve BigDecimal M and BigInt N suffixes, symbolic values
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
    (number? form) (str form)

    ;; tagged literal (JVM only — resolved at read time in ClojureScript)
    #?@(:clj [(tagged-literal? form)
              (str "#" (.-tag form) " " (print-form (.-form form)))

              ;; reader conditional — walk inner forms with meme syntax
              (reader-conditional? form)
              (let [prefix (if (.-splicing form) "#?@(" "#?(")
                    pairs (partition 2 (.-form form))
                    body (str/join " " (mapcat (fn [[k v]] [(print-form k) (print-form v)]) pairs))]
                (str prefix body ")"))])

    ;; fallback
    :else (pr-str form)))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn print-meme-string
  "Print Clojure forms as meme text."
  [forms]
  (str/join "\n\n" (map print-form forms)))
