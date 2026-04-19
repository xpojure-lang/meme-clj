(ns meme.generative-test
  "Property-based generative tests targeting bug-prone interaction surfaces.
   JVM-only (.clj) because test.check is not available on Babashka/CLJS.

   Organized by the bug patterns found in regression tests:
   1. Prefix/dispatch composition — stacked @, ', #', ^, #_ operators
   2. Chained calls — f(x)(y) list-headed call roundtrip
   3. #() anonymous functions — arity edge cases, nested fn, %& params
   4. Meme text-level — token boundaries, spacing, dispatch characters
   5. Error paths — parse errors carry :line/:col, unclosed = :incomplete
   6. Metadata on calls — ^:key on call forms survives roundtrip
   Plus retained: matrix coverage, recursive forms, discard transparency, syntax-quote."
  (:require [clojure.string :as str]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [meme-lang.api :as lang]
            [meme-lang.formatter.canon :as fmt-canon]
            [meme-lang.formatter.flat :as fmt-flat]))

;; ===========================================================================
;; Leaf generators
;; ===========================================================================

(def safe-symbol-chars "abcdefghijklmnopqrstuvwxyz")
(def symbol-suffix-chars "abcdefghijklmnopqrstuvwxyz0123456789-?!*")

(def reserved-symbols #{'fn 'quote 'var 'clojure.core/deref
                        (symbol "nil") (symbol "true") (symbol "false")})

;; Keywords the printer strips from metadata (compiler/reader-added keys)
(def reserved-meta-keywords #{:meme-lang/leading-trivia :line :column :file})

(def gen-simple-symbol
  (gen/let [first-char (gen/elements (seq safe-symbol-chars))
            rest-chars (gen/vector (gen/elements (seq symbol-suffix-chars)) 0 8)]
    (let [sym (symbol (apply str first-char rest-chars))]
      (if (reserved-symbols sym) (symbol (str first-char "x")) sym))))

(def gen-keyword
  (gen/such-that
   #(not (reserved-meta-keywords %))
   (gen/one-of
    [(gen/let [n (gen/not-empty (gen/vector (gen/elements (seq safe-symbol-chars)) 1 8))]
       (keyword (apply str n)))
     (gen/let [ns-chars (gen/not-empty (gen/vector (gen/elements (seq safe-symbol-chars)) 1 5))
               n-chars (gen/not-empty (gen/vector (gen/elements (seq safe-symbol-chars)) 1 5))]
       (keyword (apply str ns-chars) (apply str n-chars)))])))

(def gen-number
  (gen/frequency
   [[5 (gen/one-of [gen/small-integer
                    (gen/fmap long (gen/large-integer* {:min -1000000 :max 1000000}))])]
    [2 (gen/let [n gen/small-integer d (gen/choose 1 99)]
         (+ (double n) (/ d 100.0)))]
    [1 (gen/let [num (gen/such-that #(not= 0 %) gen/small-integer)
                 den (gen/such-that #(pos? %) (gen/fmap #(Math/abs (int %)) gen/small-integer) 100)]
         (/ num (max den 1)))]
    [1 (gen/fmap bigint (gen/large-integer* {:min -10000 :max 10000}))]
    [1 (gen/let [n gen/small-integer d (gen/choose 1 99)]
         (bigdec (+ n (/ d 100.0))))]
     ;; Edge-case numbers from regression bugs (##NaN excluded: NaN != NaN by IEEE 754)
    [1 (gen/elements [##Inf ##-Inf])]]))

(def gen-string
  (gen/one-of
   [(gen/fmap #(apply str %) (gen/vector (gen/elements (seq "abcdefghijklmnopqrstuvwxyz 0123456789!?.-_")) 0 20))
     ;; Strings with escape-producing characters (quotes, backslashes, newlines)
    (gen/fmap #(apply str %) (gen/vector (gen/elements [\a \b \c \space \" \\ \newline \tab \return]) 0 10))]))

(def gen-char
  (gen/elements [\a \b \c \d \e \f \g \h \i \j \k \l \m
                 \newline \tab \space \return \backspace \formfeed]))

(def gen-primitive
  (gen/one-of
   [gen-number gen-string gen-keyword gen-simple-symbol
    gen/boolean (gen/return nil) gen-char]))

;; ===========================================================================
;; Collection generators
;; ===========================================================================

(defn gen-vector-of [g] (gen/vector g 0 5))
(defn gen-map-of [g] (gen/let [kvs (gen/vector (gen/tuple gen-keyword g) 0 4)]
                       (apply array-map (mapcat identity kvs))))
(defn gen-set-of [g] (gen/fmap set (gen/vector g 0 4)))

;; ===========================================================================
;; Matrix generators (head × arg × arity)
;; ===========================================================================

(def head-generators
  [[:symbol  gen-simple-symbol]
   [:keyword gen-keyword]
   [:vector  (gen/vector gen-simple-symbol 1 3)]
   [:set     (gen/fmap set (gen/not-empty (gen/vector gen-keyword 1 3)))]
   [:map     (gen/let [k gen-keyword v gen-primitive]
               (array-map k v))]])

(defn arg-generators [gen-elem]
  [[:primitive gen-primitive]
   [:symbol    gen-simple-symbol]
   [:keyword   gen-keyword]
   [:vector    (gen/vector gen-elem 0 3)]
   [:map       (gen-map-of gen-elem)]
   [:set       (gen-set-of gen-elem)]
   [:call      (gen/let [h gen-simple-symbol as (gen/vector gen-elem 0 3)]
                 (apply list h as))]
   [:kw-call   (gen/let [k gen-keyword a gen-simple-symbol] (list k a))]
   [:kw-nested (gen/let [k1 gen-keyword k2 gen-keyword s gen-simple-symbol]
                 (list k2 (list k1 s)))]
   [:deref     (gen/fmap #(list 'clojure.core/deref %) gen-simple-symbol)]
   [:quote     (gen/fmap #(list 'quote %) gen-simple-symbol)]
   [:var       (gen/fmap #(list 'var %) gen-simple-symbol)]
   [:meta      (gen/let [kw gen-keyword
                         sym gen-simple-symbol]
                 (with-meta sym {kw true}))]])

(def arities [0 1 2 3])

(defn gen-matrix-cell [gen-head gen-arg arity]
  (gen/let [h gen-head
            args (gen/vector gen-arg arity)]
    (apply list h args)))

(def gen-flat-matrix
  (gen/one-of
   (for [[_hname gen-h] head-generators
         [_aname gen-a] (arg-generators gen-primitive)
         arity arities]
     (gen-matrix-cell gen-h gen-a arity))))

(def gen-nested-matrix
  (gen/one-of
   (for [[_hname gen-h] head-generators
         arity [1 2 3]]
     (gen-matrix-cell gen-h gen-flat-matrix arity))))

;; ===========================================================================
;; Recursive form generator (enhanced with prefixes + metadata on calls)
;; ===========================================================================

(def gen-form
  "Recursive generator mixing all head types, collections, prefixes, metadata."
  (gen/recursive-gen
   (fn [inner]
     (gen/one-of
      [(gen-vector-of inner)
       (gen-map-of inner)
       (gen-set-of inner)
         ;; All head types with recursive args
       (gen/let [h gen-simple-symbol as (gen/vector inner 0 4)]
         (apply list h as))
       (gen/let [k gen-keyword as (gen/vector inner 1 3)]
         (apply list k as))
       (gen/let [v (gen/vector gen-simple-symbol 1 3) as (gen/vector inner 1 3)]
         (apply list v as))
       (gen/let [s (gen/fmap set (gen/not-empty (gen/vector gen-keyword 1 3))) a inner]
         (list s a))
       (gen/let [m (gen/let [k gen-keyword v gen-primitive] (array-map k v)) a inner]
         (list m a))
         ;; Prefix forms
       (gen/fmap #(list 'clojure.core/deref %) gen-simple-symbol)
       (gen/fmap #(list 'quote %) gen-simple-symbol)
       (gen/fmap #(list 'var %) gen-simple-symbol)
         ;; Keyword-headed nested calls
       (gen/let [k gen-keyword s gen-simple-symbol] (list k s))
       (gen/let [k1 gen-keyword k2 gen-keyword s gen-simple-symbol]
         (list k2 (list k1 s)))
         ;; Metadata on symbols
       (gen/let [kw gen-keyword sym gen-simple-symbol]
         (with-meta sym {kw true}))
         ;; Metadata on calls (regression: canon formatter dropped metadata on multi-line forms)
       (gen/let [kw gen-keyword
                 h gen-simple-symbol
                 as (gen/vector gen-simple-symbol 1 3)]
         (with-meta (apply list h as) {kw true}))]))
   gen-primitive))

;; ===========================================================================
;; Composite prefix generator
;; Regression bugs: #_ inside ^meta, @#_foo, '#_foo, ^42 x, chained @@@x
;; ===========================================================================

(def gen-prefix-form
  "Generate forms with 1-3 stacked prefix operators."
  (gen/let [base (gen/one-of [gen-simple-symbol
                              (gen/let [h gen-simple-symbol a gen-simple-symbol]
                                (list h a))
                              (gen/vector gen-simple-symbol 1 3)])
            ops (gen/vector
                 (gen/elements [:deref :quote :var])
                 1 3)]
    (reduce (fn [form op]
              (case op
                :deref (list 'clojure.core/deref form)
                :quote (list 'quote form)
                :var   (if (symbol? form) (list 'var form) form)))
            base ops)))

(def gen-meta-prefix-form
  "Generate forms with metadata prefix: ^:kw or ^{:kw true}."
  (gen/let [kw gen-keyword
            target (gen/one-of [gen-simple-symbol
                                (gen/let [h gen-simple-symbol a gen-simple-symbol]
                                  (list h a))
                                (gen/vector gen-simple-symbol 1 3)])]
    (if (instance? clojure.lang.IObj target)
      (with-meta target {kw true})
      target)))

;; ===========================================================================
;; Chained call generator
;; Regression bug: f(x)(y) rejected as bare parens
;; ===========================================================================

(def gen-chained-call
  "Generate list-headed calls: ((f x) y), (((f x) y) z)."
  (gen/let [h gen-simple-symbol
            chain-depth (gen/choose 1 3)
            first-args (gen/vector gen-simple-symbol 1 2)]
    (let [inner (apply list h first-args)]
      (loop [form inner depth chain-depth]
        (if (zero? depth)
          form
          (recur (list form (symbol (str "arg" depth))) (dec depth)))))))

;; ===========================================================================
;; #() anonymous function generator (richer than original)
;; Regression bugs: arity mismatch, %0 rejected, nested fn, bare % normalization
;; ===========================================================================

(def gen-anon-fn-form
  "Generate (fn [%1 ... %N] body) forms exercising printer #() shorthand."
  (gen/one-of
   [;; Standard: all declared params used in body
    (gen/let [arity (gen/choose 1 5)
              body-head gen-simple-symbol]
      (let [params (mapv #(symbol (str "%" (inc %))) (range arity))
            body (apply list body-head params)]
        (list 'fn params body)))
     ;; Zero-arity: #(rand())
    (gen/let [body-head gen-simple-symbol]
      (list 'fn [] (list body-head)))
     ;; With %& rest param
    (gen/let [arity (gen/choose 1 2)
              body-head gen-simple-symbol]
      (let [pos-params (mapv #(symbol (str "%" (inc %))) (range arity))
            params (conj pos-params '& '%&)
            body (apply list body-head (conj (vec pos-params) '%&))]
        (list 'fn params body)))
     ;; Nested fn inside body (% in inner fn must not leak)
    (gen/let [outer-head gen-simple-symbol
              inner-head gen-simple-symbol]
      (list 'fn ['%1]
            (list outer-head '%1
                  (list 'fn ['x] (list inner-head 'x)))))]))

;; ===========================================================================
;; Meme text-level generators
;; Bugs live at token boundaries — form-level generators can't reach these
;; ===========================================================================

(def gen-meme-atom
  "Generate a single valid meme text atom (not a compound form)."
  (gen/one-of
   [(gen/fmap str gen-simple-symbol)
    (gen/fmap #(str ":" (name %)) gen-keyword)
    (gen/elements ["42" "-3" "0" "3.14" "-1.5" "1/3" "22/7"
                   "1N" "3.14M" "0xFF" "017"
                   "##Inf" "##-Inf"])
    (gen/elements ["\"hello\"" "\"\"" "\"a\\\"b\"" "\"a\\\\b\""
                   "\"a\\nb\"" "\"a\\tb\"" "\"\\u0041\""])
    (gen/elements ["\\a" "\\newline" "\\space" "\\tab" "\\return"
                   "\\u0041" "\\backspace" "\\formfeed"])
    (gen/elements ["#\"\\d+\"" "#\"[a-z]+\""])]))

(def gen-meme-call
  "Generate a valid meme call expression."
  (gen/let [head (gen/fmap str gen-simple-symbol)
            args (gen/vector gen-meme-atom 0 3)]
    (str head "(" (str/join " " args) ")")))

(def gen-meme-compound
  "Generate compound meme text with reader macros and dispatch forms."
  (gen/one-of
   [gen-meme-call
     ;; prefix + call: @atom, 'sym
    (gen/let [call gen-meme-call]
      (gen/let [prefix (gen/elements ["@" "'"])]
        (str prefix call)))
     ;; #'var — var-quote requires a symbol, not a call
    (gen/let [sym (gen/fmap str gen-simple-symbol)]
      (str "#'" sym))
     ;; metadata + form
    (gen/let [sym (gen/fmap str gen-simple-symbol)]
      (str "^:private " sym))
     ;; #_ discard + form
    (gen/let [discard gen-meme-atom form gen-meme-atom]
      (str "#_" discard " " form))
     ;; stacked #_ #_
    (gen/let [d1 gen-meme-atom d2 gen-meme-atom form gen-meme-atom]
      (str "#_ #_ " d1 " " d2 " " form))
     ;; collections
    (gen/let [elems (gen/vector gen-meme-atom 0 3)]
      (str "[" (str/join " " elems) "]"))
    (gen/let [keys  (gen/fmap #(into [] (distinct) %)
                              (gen/vector (gen/fmap #(str ":" (name %)) gen-keyword) 0 3))
              vals  (gen/vector gen-meme-atom (count keys))]
      (str "{" (str/join " " (interleave keys vals)) "}"))
     ;; set — distinct elements to avoid "Duplicate element in set literal"
    (gen/let [elems (gen/fmap #(into [] (distinct) %)
                              (gen/vector (gen/fmap str gen-simple-symbol) 0 3))]
      (str "#{" (str/join " " elems) "}"))
     ;; chained call: f(x)(y)
    (gen/let [head (gen/fmap str gen-simple-symbol)
              a1 gen-meme-atom
              a2 gen-meme-atom]
      (str head "(" a1 ")(" a2 ")"))]))

(def gen-meme-text
  "Generate a complete valid meme source string (one or more forms)."
  (gen/let [forms (gen/vector gen-meme-compound 1 3)]
    (str/join "\n" forms)))

;; ---------------------------------------------------------------------------
;; Invalid meme text generators (for error-path properties)
;; ---------------------------------------------------------------------------

(def gen-unclosed-meme
  "Generate meme text with unclosed delimiters — must produce :incomplete."
  (gen/one-of
   [(gen/let [h (gen/fmap str gen-simple-symbol)
              a gen-meme-atom]
      (str h "(" a))           ; unclosed call
    (gen/let [a gen-meme-atom]
      (str "[" a))             ; unclosed vector
    (gen/let [k (gen/fmap #(str ":" (name %)) gen-keyword)
              v gen-meme-atom]
      (str "{" k " " v))      ; unclosed map
    (gen/return "\"unterminated") ; unclosed string
    (gen/let [h (gen/fmap str gen-simple-symbol)]
      (str h "("))]))          ; empty unclosed call

(def gen-invalid-meme
  "Generate meme text that must fail with a meme error (not JVM exception)."
  (gen/one-of
   [(gen/let [args (gen/vector gen-meme-atom 1 3)]
      (str "(" (str/join " " args) ")"))  ; bare parens with content
    (gen/return "#3")                      ; # followed by digit
    (gen/return "#=(+ 1 2)")               ; read-eval
    (gen/return "#)")                       ; # followed by )
    (gen/let [h (gen/fmap str gen-simple-symbol)]
      (str h "([)"))                        ; mismatched brackets
    (gen/return "^42 x")                    ; invalid metadata type
    (gen/return "^[1 2] x")                ; invalid metadata type
    (gen/return "#'a(x)")                  ; var-quote on call
    (gen/return "^:foo 42")]))             ; invalid metadata type

;; ===========================================================================
;; Roundtrip helpers
;; ===========================================================================

(defn roundtrip-ok? [form]
  (try
    (let [printed (fmt-flat/format-forms [form])
          read-back (lang/meme->forms printed)]
      (= [form] read-back))
    (catch Exception e
      (println "Roundtrip failed for form:" (pr-str form))
      (println "Error:" (.getMessage e))
      false)))

(defn meta-roundtrip-ok?
  "Check roundtrip preserving metadata (ignoring :meme-lang/leading-trivia added by reader)."
  [form]
  (try
    (let [printed (fmt-flat/format-forms [form])
          read-back (first (lang/meme->forms printed))]
      (and (= form read-back)
           (= (meta form) (dissoc (meta read-back) :meme-lang/leading-trivia :meme-lang/meta-chain))))
    (catch Exception e
      (println "Meta roundtrip failed for form:" (pr-str form))
      (println "Error:" (.getMessage e))
      false)))

;; ===========================================================================
;; Property: matrix coverage (retained)
;; ===========================================================================

(defspec prop-matrix-flat 300
  (prop/for-all [form gen-flat-matrix]
                (roundtrip-ok? form)))

(defspec prop-matrix-nested 300
  (prop/for-all [form gen-nested-matrix]
                (roundtrip-ok? form)))

;; ===========================================================================
;; Property: recursive mixed forms (subsumes primitive/collection roundtrip)
;; ===========================================================================

(defspec prop-mixed-form-roundtrip 300
  (prop/for-all [form gen-form]
                (roundtrip-ok? form)))

;; ===========================================================================
;; Property: composite prefix operators
;; Regression: #_ inside ^meta, @#_foo, '#_foo, chained @@@x
;; ===========================================================================

(defspec prop-prefix-composition-roundtrip 200
  (prop/for-all [form gen-prefix-form]
                (roundtrip-ok? form)))

(defspec prop-meta-prefix-roundtrip 200
  (prop/for-all [form gen-meta-prefix-form]
                (meta-roundtrip-ok? form)))

;; NOTE: The experimental pipeline's #_ handling in prefix position differs.
;; @#_sym target → (deref nil) target (2 forms), not (deref target) (1 form).
;; The #_ consumes the next token and the prefix applies to nil.
(defspec prop-discard-in-prefix 200
  (prop/for-all [prefix-op (gen/elements ["@" "'"])
                 discard-form gen-simple-symbol
                 target gen-simple-symbol]
    ;; prefix #_discard target → prefix applies to nil, target is separate
                (try
                  (let [meme-str (str prefix-op "#_" discard-form " " target)
                        forms (lang/meme->forms meme-str)]
                    (= 2 (count forms)))
                  (catch Exception _ false))))

;; ===========================================================================
;; Property: chained calls
;; Regression: f(x)(y) rejected as bare parens
;; ===========================================================================

(defspec prop-chained-call-roundtrip 200
  (prop/for-all [form gen-chained-call]
                (roundtrip-ok? form)))

;; ===========================================================================
;; Property: #() anonymous functions
;; Regression: arity mismatch, %0 rejected, nested fn, bare % normalization
;; ===========================================================================

(defspec prop-anon-fn-roundtrip 200
  (prop/for-all [form gen-anon-fn-form]
                (try
                  (let [printed (fmt-flat/format-form form)
                        read-back (first (lang/meme->forms printed))]
                    (= form read-back))
                  (catch Exception e
                    (println "Anon fn roundtrip failed for:" (pr-str form))
                    (println "Error:" (.getMessage e))
                    false))))

;; ===========================================================================
;; Property: metadata on calls survives roundtrip
;; Regression: canon formatter dropped metadata on multi-line call forms
;; ===========================================================================

(defspec prop-metadata-on-calls-roundtrip 200
  (prop/for-all [form (gen/let [kw gen-keyword
                                h gen-simple-symbol
                                as (gen/vector gen-simple-symbol 1 4)]
                        (with-meta (apply list h as) {kw true}))]
                (meta-roundtrip-ok? form)))

;; ===========================================================================
;; Property: #_ discard transparency (retained)
;; ===========================================================================

(defspec prop-discard-transparent 200
  (prop/for-all [form gen-form
                 discard-form gen-primitive]
                (try
                  (let [printed (fmt-flat/format-forms [form])
                        discard-printed (str "#_" (fmt-flat/format-forms [discard-form]) " " printed)
                        read-normal (lang/meme->forms printed)
                        read-discard (lang/meme->forms discard-printed)]
                    (= read-normal read-discard))
                  (catch Exception _ false))))

;; ===========================================================================
;; Property: meme text-level parse and roundtrip
;; Bugs manifest at token boundaries — form-level generators can't reach these
;; ===========================================================================

(defspec prop-meme-text-parses 300
  (prop/for-all [meme-str gen-meme-text]
                (try
                  (lang/meme->forms meme-str)
                  true
                  (catch Exception _ false))))

(defspec prop-meme-text-roundtrip 300
  (prop/for-all [meme-str gen-meme-text]
                (try
                  (let [forms (lang/meme->forms meme-str)
                        printed (fmt-flat/format-forms forms)
                        re-read (lang/meme->forms printed)]
        ;; pr-str comparison: handles Pattern (no equals) and NaN (!= itself)
                    (= (pr-str forms) (pr-str re-read)))
                  (catch Exception _ false))))

;; ===========================================================================
;; Property: error paths carry :line/:col and never produce raw JVM exceptions
;; Regression: ClassCastException, NPE, StackOverflow instead of meme error
;; ===========================================================================

(defspec prop-errors-have-location 200
  (prop/for-all [meme-str gen-invalid-meme]
                (try
                  (lang/meme->forms meme-str)
                  false ;; should have thrown
                  (catch clojure.lang.ExceptionInfo e
                    (let [data (ex-data e)]
                      (and (integer? (:line data))
                           (integer? (:col data)))))
                  (catch Exception _
                    false)))) ;; raw JVM exception = fail

;; NOTE: The experimental tokenizer never throws for unterminated strings
;; (it truncates them). Only delimiter-unclosed inputs produce :incomplete.
(def gen-unclosed-delimiter-meme
  "Generate meme text with unclosed delimiters — must produce :incomplete."
  (gen/one-of
   [(gen/let [h (gen/fmap str gen-simple-symbol)
              a gen-meme-atom]
      (str h "(" a))           ; unclosed call
    (gen/let [a gen-meme-atom]
      (str "[" a))             ; unclosed vector
    (gen/let [k (gen/fmap #(str ":" (name %)) gen-keyword)
              v gen-meme-atom]
      (str "{" k " " v))      ; unclosed map
    (gen/let [h (gen/fmap str gen-simple-symbol)]
      (str h "("))]))          ; empty unclosed call

(defspec prop-unclosed-is-incomplete 100
  (prop/for-all [meme-str gen-unclosed-delimiter-meme]
                (try
                  (lang/meme->forms meme-str)
                  false ;; should have thrown
                  (catch clojure.lang.ExceptionInfo e
                    (:incomplete (ex-data e)))
                  (catch Exception _
                    false))))

;; ===========================================================================
;; Property: syntax-quote parses (retained — no roundtrip, Clojure expands)
;; ===========================================================================

(def gen-syntax-quote-meme
  (gen/let [name gen-simple-symbol
            args (gen/vector gen-simple-symbol 1 3)
            body-head gen-simple-symbol
            body-args (gen/vector gen-simple-symbol 0 3)]
    (str "defmacro(" name " [" (str/join " " (map str args)) "] "
         "`" body-head (when (seq body-args) (str "(" (str/join " " (map str body-args)) ")")) ")")))

(defspec prop-syntax-quote-parses 200
  (prop/for-all [meme-str gen-syntax-quote-meme]
                (try
                  (lang/meme->forms meme-str)
                  true
                  (catch Exception _ false))))

(def gen-syntax-quote-with-unquote
  (gen/let [name gen-simple-symbol
            param gen-simple-symbol
            body-head gen-simple-symbol]
    (str "defmacro(" name " [" param "] "
         "`" body-head "(~" param "))")))

(defspec prop-syntax-quote-with-unquote-parses 200
  (prop/for-all [meme-str gen-syntax-quote-with-unquote]
                (try
                  (lang/meme->forms meme-str)
                  true
                  (catch Exception _ false))))

;; ===========================================================================
;; Formatter idempotency: format(format(x)) == format(x)
;; ===========================================================================

(defspec prop-canon-formatter-idempotent 300
  (prop/for-all [form gen-form]
    (let [fmt1 (fmt-canon/format-forms [form] {:width 80})
          reparsed (lang/meme->forms fmt1)
          fmt2 (fmt-canon/format-forms reparsed {:width 80})]
      (= fmt1 fmt2))))

;; ===========================================================================
;; NEW: Char literal edge cases
;; Regression bugs: \u0041/\o101 split, \u00g1 invalid hex, bare \, control chars
;; ===========================================================================

(def gen-char-literal-text
  "Generate char literal text strings, including edge cases."
  (gen/one-of
   [;; Named chars
    (gen/elements ["\\a" "\\b" "\\z" "\\newline" "\\tab" "\\space"
                   "\\return" "\\backspace" "\\formfeed"])
    ;; Unicode escapes — valid
    (gen/let [cp (gen/choose 0x20 0x7E)]
      (format "\\u%04X" cp))
    ;; Octal escapes — valid (0-377)
    (gen/let [cp (gen/choose 0 255)]
      (format "\\o%03o" cp))]))

(def gen-invalid-char-literal-text
  "Generate char literal text that should produce an error.
   Only includes sequences that START a unicode/octal escape but have wrong digits."
  (gen/elements
   ["\\u00g1"      ; starts unicode escape, non-hex digit at position 3
    "\\u00"         ; starts unicode escape, truncated (only 2 hex digits)
    "\\u0"          ; starts unicode escape, truncated (only 1 hex digit)
    ]))

(defspec prop-char-literal-roundtrip 200
  (prop/for-all [char-text gen-char-literal-text]
    (try
      (let [forms (lang/meme->forms char-text)]
        (and (= 1 (count forms))
             (char? (let [f (first forms)]
                      (if (instance? meme.tools.clj.forms.MemeRaw f) (:value f) f)))))
      (catch Exception _ false))))

(defspec prop-invalid-char-literal-errors 100
  (prop/for-all [char-text gen-invalid-char-literal-text]
    (try
      (lang/meme->forms char-text)
      false  ;; should have thrown
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (and (integer? (:line data)) (integer? (:col data)))))
      (catch Exception _ false))))

;; ===========================================================================
;; NEW: Reader conditional & namespaced map generators
;; Regression bugs: bracket depth with chars/comments inside, #() inside #?
;; ===========================================================================

(def gen-reader-cond-text
  "Generate reader conditional text with various inner forms."
  (gen/let [clj-form gen-meme-atom
            cljs-form gen-meme-atom]
    (str "#?(:clj " clj-form " :cljs " cljs-form ")")))

(def gen-reader-cond-with-tricky-content
  "Reader conditionals with chars, strings, comments that contain delimiters."
  (gen/one-of
   [(gen/return "#?(:clj \\) :cljs \\x)")
    (gen/return "#?(:clj \")\" :cljs nil)")
    (gen/return "#?(:clj ; comment with )\n 1 :cljs 2)")
    (gen/let [clj-form gen-meme-call cljs-form gen-meme-call]
      (str "#?(:clj " clj-form " :cljs " cljs-form ")"))
    (gen/let [clj-form gen-meme-atom]
      (str "#?(:clj #(inc(%))" " :cljs " clj-form ")"))]))

(def gen-namespaced-map-text
  "Generate namespaced map text."
  (gen/let [ns-name (gen/fmap #(apply str %) (gen/vector (gen/elements (seq "abcdefg")) 1 5))
            keys (gen/fmap #(into [] (distinct) %)
                           (gen/vector (gen/fmap #(str ":" (name %)) gen-keyword) 1 3))
            vals (gen/vector gen-meme-atom (count keys))]
    (str "#:" ns-name "{" (str/join " " (interleave keys vals)) "}")))

(defspec prop-reader-cond-parses 200
  (prop/for-all [text gen-reader-cond-text]
    (try
      (lang/meme->forms text)
      true
      (catch Exception _ false))))

(defspec prop-reader-cond-tricky-parses 200
  (prop/for-all [text gen-reader-cond-with-tricky-content]
    (try
      (lang/meme->forms text)
      true
      (catch Exception _ false))))

(defspec prop-namespaced-map-roundtrip 200
  (prop/for-all [text gen-namespaced-map-text]
    (try
      (let [forms (lang/meme->forms text)
            printed (fmt-flat/format-forms forms)
            re-read (lang/meme->forms printed)]
        (= (pr-str forms) (pr-str re-read)))
      (catch Exception _ false))))

;; ===========================================================================
;; NEW: Signed number vs operator ambiguity
;; Regression bug: -1 (number) vs -(1 2) (operator call)
;; ===========================================================================

(def gen-signed-number-context
  "Generate text where signed numbers appear near call syntax."
  (gen/one-of
   [(gen/elements ["-1" "+1" "-3.14" "+3.14" "-1/2" "+1/2"
                   "-1N" "+1N" "-1.5M" "+1.5M"])
    ;; Operator call: -(1 2), +(x y)
    (gen/let [op (gen/elements ["-" "+"])
              args (gen/vector gen-meme-atom 1 3)]
      (str op "(" (str/join " " args) ")"))
    ;; Signed number in a vector
    (gen/let [n (gen/elements ["-1" "+2" "-3.14"])]
      (str "[" n " 42]"))
    ;; Signed number as call arg
    (gen/let [h (gen/fmap str gen-simple-symbol)
              n (gen/elements ["-1" "+2" "-3.14"])]
      (str h "(" n ")"))]))

(defspec prop-signed-number-parses 200
  (prop/for-all [text gen-signed-number-context]
    (try
      (lang/meme->forms text)
      true
      (catch Exception _ false))))

(defspec prop-signed-number-roundtrip 200
  (prop/for-all [text gen-signed-number-context]
    (try
      (let [forms (lang/meme->forms text)
            printed (fmt-flat/format-forms forms)
            re-read (lang/meme->forms printed)]
        (= (pr-str forms) (pr-str re-read)))
      (catch Exception _ false))))

;; ===========================================================================
;; NEW: Radix numbers
;; Regression bug: 36rZ split, bases 17-36 need letters G-Z
;; ===========================================================================

(def gen-radix-number-text
  "Generate valid radix number literals."
  (gen/one-of
   [(gen/elements ["2r1010" "2r0" "2r11111111"
                   "8r77" "8r0" "8r377"
                   "16rFF" "16r0" "16rABCD"
                   "36rZ" "36rHELLO" "36r0"])]))

(defspec prop-radix-number-roundtrip 100
  (prop/for-all [text gen-radix-number-text]
    (try
      (let [forms (lang/meme->forms text)
            printed (fmt-flat/format-forms forms)
            re-read (lang/meme->forms printed)]
        (= (pr-str forms) (pr-str re-read)))
      (catch Exception _ false))))

;; ===========================================================================
;; NEW: Canon formatter at narrow widths
;; Regression bugs: width constraints ignored, comment indentation, metadata
;; ===========================================================================

(defspec prop-canon-narrow-width-idempotent 200
  (prop/for-all [form gen-form
                 width (gen/choose 20 60)]
    (let [fmt1 (fmt-canon/format-forms [form] {:width width})
          reparsed (lang/meme->forms fmt1)
          fmt2 (fmt-canon/format-forms reparsed {:width width})]
      (= fmt1 fmt2))))

;; ===========================================================================
;; NEW: Arbitrary string no-crash (fuzzer-inspired)
;; Property: parser never throws raw JVM exceptions on any input
;; ===========================================================================

(def gen-meme-char-soup
  "Generate strings from syntax-significant characters."
  (gen/fmap
   #(apply str %)
   (gen/vector
    (gen/one-of
     [(gen/elements (seq "()[]{}#@^'`~:;,\"\\/ \t\n"))
      (gen/elements (seq "abcdefghijklmnopqrstuvwxyz"))
      (gen/elements (seq "0123456789"))
      (gen/elements (seq "+-*!?._<>=&%"))
      gen/char-ascii])
    0 50)))

(defspec prop-arbitrary-string-no-raw-exception 500
  (prop/for-all [s gen-meme-char-soup]
    (try
      (lang/meme->forms s)
      true
      (catch clojure.lang.ExceptionInfo _ true)  ;; meme errors OK
      (catch StackOverflowError _ true)           ;; depth limit OK
      (catch Exception _ false))))                ;; raw JVM exception = FAIL

;; ===========================================================================
;; NEW: Coverage gaps from red team round 3 analysis
;; ===========================================================================

;; --- Tagged literals ---

(def gen-tagged-literal-text
  "Generate tagged literal text (#inst, #uuid, custom)."
  (gen/one-of
   [(gen/return "#inst \"2024-01-01T00:00:00.000Z\"")
    (gen/return "#uuid \"550e8400-e29b-41d4-a716-446655440000\"")
    (gen/let [tag (gen/elements ["my/tag" "foo/bar" "x/y"])
              val gen-meme-atom]
      (str "#" tag " " val))]))

(defspec prop-tagged-literal-parses 200
  (prop/for-all [text gen-tagged-literal-text]
    (try
      (some? (lang/meme->forms text))
      (catch Exception _ false))))

;; --- Auto-alias namespaced maps (#::alias{}) ---

(def gen-alias-namespaced-map-text
  "Generate auto-alias namespaced map text (#::alias{})."
  (gen/let [alias (gen/fmap #(apply str %) (gen/vector (gen/elements (seq "abcdef")) 1 4))
            n (gen/choose 1 3)
            ks (gen/vector (gen/fmap #(str ":" (name %)) gen-keyword) n)
            vs (gen/vector gen-meme-atom n)]
    (let [pairs (interleave (distinct ks) vs)]
      (str "#::" alias "{" (str/join " " pairs) "}"))))

(defspec prop-alias-namespaced-map-roundtrip 200
  (prop/for-all [text gen-alias-namespaced-map-text]
    (try
      (let [forms (lang/meme->forms text)
            printed (fmt-flat/format-forms forms)
            re-read (lang/meme->forms printed)]
        (= (pr-str forms) (pr-str re-read)))
      (catch Exception _ false))))

;; --- Invalid keywords (error cases) ---

(def gen-invalid-keyword-text
  "Generate invalid keyword forms that should error."
  (gen/elements [":" ":foo:" ":::foo" ":a::b" ":foo/"]))

(defspec prop-invalid-keyword-errors 100
  (prop/for-all [text gen-invalid-keyword-text]
    (try
      (lang/meme->forms text)
      false  ;; should have thrown
      (catch clojure.lang.ExceptionInfo _ true)
      (catch Exception _ false))))

;; --- Unterminated regex (error cases) ---

(def gen-unterminated-regex-text
  "Generate unterminated regex literals that should error."
  (gen/let [body (gen/fmap #(apply str %) (gen/vector gen/char-alpha 1 10))]
    (str "#\"" body)))

(defspec prop-unterminated-regex-errors 100
  (prop/for-all [text gen-unterminated-regex-text]
    (try
      (lang/meme->forms text)
      false  ;; should have thrown
      (catch clojure.lang.ExceptionInfo _ true)
      (catch Exception _ false))))

;; --- Unterminated strings (error/incomplete cases) ---

(defspec prop-unterminated-string-errors 100
  (prop/for-all [body (gen/fmap #(apply str %) (gen/vector gen/char-alpha 1 10))]
    (let [text (str "\"" body)]
      (try
        (lang/meme->forms text)
        false  ;; should have thrown
        (catch clojure.lang.ExceptionInfo _ true)
        (catch Exception _ false)))))

;; --- Consecutive #_ discard semantics ---

(defspec prop-consecutive-discard-semantics 200
  (prop/for-all [n (gen/choose 1 4)]
    (try
      (let [syms (mapv #(str "s" %) (range (inc n)))
            discards (apply str (repeat n "#_ "))
            input (str discards (str/join " " syms))
            result (lang/meme->forms input)]
        ;; N discards should leave only the last form
        (= 1 (count result)))
      (catch Exception _ false))))

;; --- Nested syntax-quote ---

(def gen-nested-syntax-quote-text
  "Generate nested syntax-quote forms."
  (gen/one-of
   [(gen/return "``x")
    (gen/return "``~x")
    (gen/return "``~~x")
    (gen/return "`map(~f `if(~test ~body))")
    (gen/let [sym gen-simple-symbol]
      (str "``" (name sym)))]))

(defspec prop-nested-syntax-quote-parses 200
  (prop/for-all [text gen-nested-syntax-quote-text]
    (try
      (some? (lang/meme->forms text))
      (catch Exception _ false))))
