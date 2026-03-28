(ns beme.alpha.emit.printer
  "beme printer: Clojure forms → beme text."
  (:require [clojure.string :as str]
            [beme.alpha.forms :as forms]))

;; ---------------------------------------------------------------------------
;; Forward declaration
;; ---------------------------------------------------------------------------

(declare print-form)

;; When true, lists print in Clojure S-expression style: (f x y)
;; instead of beme call style: f(x y). Set by the quote handler
;; so that '(...) contains Clojure syntax. Also used by pprint.
(def ^:dynamic *clj-mode* false)

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
  "Print a single Clojure form as beme text."
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

    ;; empty list — in beme mode, print as '() (bare () is invalid beme);
    ;; in clj-mode (inside quoted lists), print as ()
    (and (seq? form) (empty? form))
    (if *clj-mode* "()" "'()")

    ;; sequences — calls and reader sugar
    (seq? form)
    (if *clj-mode*
      ;; In clj-mode (inside quoted lists), print as S-expressions
      (str "(" (str/join " " (map print-form form)) ")")
      (let [head (first form)]
        (cond
          (anon-fn-shorthand? form)
          (str "#(" (print-form (nth form 2)) ")")

          ;; @deref
          (= head 'clojure.core/deref) (str "@" (print-form (second form)))

          ;; 'quote — quoted lists use Clojure S-expression syntax inside.
          ;; Activates *clj-mode* so nested lists print as (f x) not f(x).
          (= head 'quote)
          (let [inner (second form)]
            (if (not (seq? inner))
              (str "'" (print-form inner))
              (binding [*clj-mode* true]
                (str "'(" (str/join " " (map print-form inner)) ")"))))

          ;; #'var
          (= head 'var) (str "#'" (print-form (second form)))

          ;; call: (f args...) → f(args...) when head is a symbol
          (symbol? head)
          (str (print-form head) "(" (print-args (rest form)) ")")

          ;; keyword-headed list: (:require [bar]) → :require([bar])
          (keyword? head)
          (str (print-form head) "(" (print-args (rest form)) ")")

          ;; vector-headed list: ([params] body) → [params](body)
          (vector? head)
          (str (print-form head) "(" (print-args (rest form)) ")")

          ;; set-headed list: (#{:a :b} x) → #{:a :b}(x)
          (set? head)
          (str (print-form head) "(" (print-args (rest form)) ")")

          ;; map-headed list: ({:a 1} :a) → {:a 1}(:a)
          (map? head)
          (str (print-form head) "(" (print-args (rest form)) ")")

          ;; any other head — reader conditionals, tagged literals, etc.
          ;; may be valid call heads via maybe-call on opaque forms
          :else
          (str (print-form head) "(" (print-args (rest form)) ")"))))

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

              ;; reader conditional — opaque passthrough
              (reader-conditional? form)
              (pr-str form)])

    ;; fallback
    :else (pr-str form)))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn print-beme-string
  "Print Clojure forms as beme text."
  [forms]
  (str/join "\n\n" (map print-form forms)))
