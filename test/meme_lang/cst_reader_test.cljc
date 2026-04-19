(ns meme-lang.cst-reader-test
  "Isolated unit tests for meme-lang.cst-reader.

   These tests drive the CST reader directly via meme.tools.parser/parse →
   cst-reader/read-forms, bypassing the higher-level stages pipeline and the
   meme-lang.api wrappers. Integration-level behavior is covered elsewhere
   (stages_test, reader/*_test) — here we assert that read-forms produces the
   right Clojure values, preserves the right metadata, and raises errors with
   position info for malformed input."
  (:require [clojure.test :refer [deftest is testing]]
            [meme-lang.cst-reader :as cst-reader]
            [meme.tools.clj.forms :as forms]
            [meme-lang.grammar :as grammar]
            [meme.tools.parser :as pratt]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- read-src
  "Parse source and run cst-reader/read-forms on the resulting CST. Returns
   the vector of forms."
  ([src] (read-src src nil))
  ([src opts] (cst-reader/read-forms (pratt/parse src grammar/grammar) opts)))

(defn- read1
  "Read the single top-level form from src."
  ([src] (read1 src nil))
  ([src opts] (first (read-src src opts))))

(defn- ex-data-of
  "Try to read src; return ex-data of the thrown exception, or nil."
  [src]
  (try (read-src src) nil
       (catch #?(:clj Throwable :cljs :default) e
         (ex-data e))))

;; ---------------------------------------------------------------------------
;; Atoms: symbol, boolean, nil
;; ---------------------------------------------------------------------------

(deftest read-symbol
  (is (= 'foo (read1 "foo")))
  (is (= 'foo/bar (read1 "foo/bar")))
  (is (= '/ (read1 "/"))))

(deftest read-nil-true-false
  (is (nil? (read1 "nil")))
  (is (true? (read1 "true")))
  (is (false? (read1 "false"))))

(deftest read-invalid-slash-symbol
  (testing "symbols starting with / (other than bare /) are rejected with position"
    (let [d (ex-data-of "/foo")]
      (is (some? d))
      (is (= 1 (:line d)))
      (is (= 1 (:col d))))))

;; ---------------------------------------------------------------------------
;; Atoms: keywords
;; ---------------------------------------------------------------------------

(deftest read-keyword-simple
  (is (= :foo (read1 ":foo")))
  (is (= :foo/bar (read1 ":foo/bar")))
  (is (= (keyword "/") (read1 ":/"))))

(deftest read-keyword-malformed
  (testing "bare colon, trailing slash, double-colon inside — all raise with position"
    (doseq [src [":" ":foo/" "::"]]
      (let [d (ex-data-of src)]
        (is (some? d) (str "expected throw for " (pr-str src)))
        (is (integer? (:line d)))
        (is (integer? (:col d)))))))

(deftest read-auto-keyword-with-resolver
  (testing "::kw uses :resolve-keyword opt"
    (let [form (read1 "::foo" {:resolve-keyword (fn [_raw] :resolved/foo)})]
      (is (= :resolved/foo form)))))

;; ---------------------------------------------------------------------------
;; Atoms: numbers, strings, chars, regex
;; ---------------------------------------------------------------------------

(deftest read-numbers
  (is (= 42 (read1 "42")))
  (is (= -3 (read1 "-3")))
  (is (= 3.14 (read1 "3.14"))))

(deftest read-string-literal
  (is (= "hi" (read1 "\"hi\"")))
  (is (= "a\nb" (read1 "\"a\\nb\""))))

(deftest read-char
  (is (= \a (read1 "\\a")))
  (is (= \newline (read1 "\\newline"))))

#?(:clj
   (deftest read-regex
     ;; Regex equality is identity on JVM; compare pattern text.
     (is (= "abc" (str (read1 "#\"abc\""))))))

;; ---------------------------------------------------------------------------
;; Collections
;; ---------------------------------------------------------------------------

(deftest read-empty-list-and-call
  (testing "() reads as the empty list"
    (is (= '() (read1 "()"))))
  (testing "f() reads as (f)"
    (is (= '(f) (read1 "f()")))))

(deftest read-call
  (is (= '(+ 1 2) (read1 "+(1 2)")))
  (is (= '(foo bar baz) (read1 "foo(bar baz)"))))

(deftest read-vector
  (is (= [1 2 3] (read1 "[1 2 3]")))
  (is (= [] (read1 "[]")))
  (is (= '[foo "x" :k] (read1 "[foo \"x\" :k]"))))

(deftest read-map
  (is (= {:a 1 :b 2} (read1 "{:a 1 :b 2}")))
  (is (= {} (read1 "{}"))))

(deftest read-map-odd-count-error
  (let [d (ex-data-of "{:a}")]
    (is (some? d))
    (is (integer? (:line d)))))

(deftest read-map-duplicate-key-error
  (let [d (ex-data-of "{:a 1 :a 2}")]
    (is (some? d))))

(deftest read-set
  (is (= #{1 2 3} (read1 "#{1 2 3}")))
  (is (= #{} (read1 "#{}"))))

(deftest read-set-duplicate-error
  (let [d (ex-data-of "#{:a :a}")]
    (is (some? d))))

(deftest read-set-preserves-insertion-order-meta
  (let [s (read1 "#{3 1 2}")]
    (is (= [3 1 2] (:meme-lang/insertion-order (meta s))))))

;; ---------------------------------------------------------------------------
;; Reader-sugar forms: quote, deref, var-quote
;; ---------------------------------------------------------------------------

(deftest read-quote-produces-sugar-marker
  (let [form (read1 "'x")]
    (is (= '(quote x) form))
    (is (true? (:meme-lang/sugar (meta form))))))

(deftest read-quote-call-has-no-sugar-marker
  (testing "quote(x) reads to the same Clojure form but without :meme-lang/sugar"
    (let [form (read1 "quote(x)")]
      (is (= '(quote x) form))
      (is (not (:meme-lang/sugar (meta form)))))))

(deftest read-deref-sugar
  (let [form (read1 "@x")]
    (is (= '(clojure.core/deref x) form))
    (is (true? (:meme-lang/sugar (meta form))))))

(deftest read-var-quote-sugar
  (let [form (read1 "#'foo")]
    (is (= '(var foo) form))
    (is (true? (:meme-lang/sugar (meta form))))))

(deftest read-var-quote-non-symbol-error
  (let [d (ex-data-of "#'42")]
    (is (some? d))))

;; ---------------------------------------------------------------------------
;; Metadata
;; ---------------------------------------------------------------------------

(deftest read-keyword-meta
  (let [form (read1 "^:private foo")]
    (is (= 'foo form))
    (is (true? (:private (meta form))))))

(deftest read-symbol-tag-meta
  (let [form (read1 "^String x")]
    (is (= 'x form))
    (is (= 'String (:tag (meta form))))))

(deftest read-map-meta
  (let [form (read1 "^{:a 1} x")]
    (is (= 'x form))
    (is (= 1 (:a (meta form))))))

(deftest read-meta-chain-stacks
  (testing "multiple ^ annotations accumulate on :meme-lang/meta-chain"
    (let [form (read1 "^:a ^:b x")
          chain (:meme-lang/meta-chain (meta form))]
      (is (vector? chain))
      (is (= 2 (count chain))))))

(deftest read-meta-on-non-metadatable-error
  (let [d (ex-data-of "^:private 42")]
    (is (some? d))))

;; ---------------------------------------------------------------------------
;; Syntax-quote AST: records survive read-forms
;; ---------------------------------------------------------------------------

(deftest read-syntax-quote-preserves-record
  (testing "`foo produces a MemeSyntaxQuote node (not expanded)"
    (let [form (read1 "`foo")]
      (is (forms/syntax-quote? form))
      (is (= 'foo (:form form))))))

(deftest read-unquote-inside-syntax-quote
  (testing "`~x nests MemeUnquote inside MemeSyntaxQuote"
    (let [form (read1 "`~x")]
      (is (forms/syntax-quote? form))
      (is (forms/unquote? (:form form))))))

(deftest read-unquote-splicing-inside-syntax-quote
  (testing "`[~@xs] preserves MemeUnquoteSplicing inside the vector"
    (let [form (read1 "`[~@xs]")]
      (is (forms/syntax-quote? form))
      (let [inner (:form form)]
        (is (vector? inner))
        (is (some forms/unquote-splicing? inner))))))

;; ---------------------------------------------------------------------------
;; Reader conditionals: records, not materialized
;; ---------------------------------------------------------------------------

(deftest read-reader-conditional-preserves-record
  (testing "#? reads to a MemeReaderConditional — no platform materialization"
    (let [form (read1 "#?(:clj 1 :cljs 2)")]
      (is (forms/meme-reader-conditional? form))
      (is (false? (forms/rc-splicing? form)))
      (is (= '(:clj 1 :cljs 2) (forms/rc-form form))))))

(deftest read-splicing-reader-conditional-preserves-record
  (testing "#?@ carries :splicing? true"
    (let [form (first (read1 "[1 #?@(:clj [2 3])]"))]
      ;; The first elt is 1; the record is the second — but vectors read
      ;; verbatim, so read1 here returns the vector. Just grab index 1.
      (is (= 1 form)))
    (let [v (read1 "[1 #?@(:clj [2 3])]")]
      (is (vector? v))
      (is (forms/meme-reader-conditional? (nth v 1)))
      (is (true? (forms/rc-splicing? (nth v 1)))))))

;; ---------------------------------------------------------------------------
;; Anonymous functions
;; ---------------------------------------------------------------------------

(deftest read-anon-fn-single-body
  (let [form (read1 "#(+ %1 %2)")]
    (is (= 'fn (first form)))
    (is (vector? (nth form 1)))
    (is (= 2 (count (nth form 1))))
    (is (true? (:meme-lang/sugar (meta form))))))

(deftest read-anon-fn-rest-arg
  (let [form (read1 "#(apply + %&)")
        params (nth form 1)]
    (is (= '& (nth params (- (count params) 2))))))

(deftest read-anon-fn-empty-body-error
  (let [d (ex-data-of "#()")]
    (is (some? d))))

(deftest read-anon-fn-nested-error
  (let [d (ex-data-of "#(+ #(* %1 2) 1)")]
    (is (some? d))))

;; ---------------------------------------------------------------------------
;; Namespaced maps
;; ---------------------------------------------------------------------------

(deftest read-namespaced-map-qualifies-keys
  (let [form (read1 "#:user{:name \"ada\" :age 40}")]
    (is (map? form))
    (is (contains? form :user/name))
    (is (contains? form :user/age))
    (is (= "user" (:meme-lang/namespace-prefix (meta form))))))

(deftest read-auto-namespaced-map-preserves-prefix
  (testing "#::alias{...} preserves :: prefix in :meme-lang/namespace-prefix meta"
    (let [form (read1 "#::alias{:k 1}")]
      (is (map? form))
      (is (= "::alias" (:meme-lang/namespace-prefix (meta form)))))))

(deftest read-namespaced-map-odd-count-error
  (let [d (ex-data-of "#:user{:a}")]
    (is (some? d))))

;; ---------------------------------------------------------------------------
;; Discards and shebang
;; ---------------------------------------------------------------------------

(deftest read-discard-drops-form
  (is (= ['a] (read-src "#_ 1 a"))))

(deftest read-shebang-dropped
  (testing "a #! line at top level is filtered, not emitted"
    (is (= '[42] (read-src "#!/usr/bin/env meme\n42")))))

;; ---------------------------------------------------------------------------
;; Errors: position info and :incomplete tag
;; ---------------------------------------------------------------------------

(deftest read-unclosed-call-incomplete
  (let [d (ex-data-of "foo(")]
    (is (some? d))
    (is (true? (:incomplete d)))))

(deftest read-unclosed-vector-incomplete
  (let [d (ex-data-of "[1 2")]
    (is (some? d))
    (is (true? (:incomplete d)))))

(deftest read-error-carries-line-col
  (let [d (ex-data-of "/foo")]
    (is (= 1 (:line d)))
    (is (= 1 (:col d)))))

;; ---------------------------------------------------------------------------
;; Depth guard
;; ---------------------------------------------------------------------------

(deftest read-depth-guard
  (testing "absurdly-deep input raises instead of stack-overflowing"
    (let [src (str (apply str (repeat 2000 "[")) (apply str (repeat 2000 "]")))
          d (ex-data-of src)]
      (is (some? d))
      (is (integer? (:line d))))))

;; ---------------------------------------------------------------------------
;; Trailing whitespace surfaced via metadata on result vector
;; ---------------------------------------------------------------------------

(deftest read-trailing-whitespace-in-meta
  (let [result (read-src "42\n\n")]
    (is (= [42] (vec result)))
    (is (string? (:trailing-ws (meta result))))))
