(ns meme.tools.clj.expander-test
  "Unit tests for meme.tools.clj.expander: syntax-quote expansion and
   CljRaw unwrapping."
  (:require [clojure.test :refer [deftest is testing]]
            [mclj-lang.api :as lang]
            [meme.tools.clj.forms :as forms]
            [meme.tools.clj.expander :as expander]))

;; ---------------------------------------------------------------------------
;; expand-forms — no-op path (no AST nodes)
;; ---------------------------------------------------------------------------

(deftest expand-forms-passthrough
  (testing "plain forms pass through unchanged"
    (is (= [42] (expander/expand-forms [42])))
    (is (= ['foo] (expander/expand-forms ['foo])))
    (is (= ['(+ 1 2)] (expander/expand-forms ['(+ 1 2)])))
    (is (= [[:a :b]] (expander/expand-forms [[:a :b]])))
    (is (= [{:a 1}] (expander/expand-forms [{:a 1}])))))

;; ---------------------------------------------------------------------------
;; CljRaw unwrapping
;; ---------------------------------------------------------------------------

(deftest expand-forms-unwraps-raw
  (testing "CljRaw at top level is unwrapped to plain value"
    (is (= [255] (expander/expand-forms [(forms/->CljRaw 255 "0xFF")]))))
  (testing "CljRaw nested in a list is unwrapped"
    (let [form (list '+ (forms/->CljRaw 10 "0xA") 1)
          result (first (expander/expand-forms [form]))]
      (is (= 10 (second result))))))

;; ---------------------------------------------------------------------------
;; Syntax-quote expansion via meme->forms + expand
;; ---------------------------------------------------------------------------

(deftest expand-syntax-quote-symbol
  (testing "syntax-quote on a symbol produces (quote sym)"
    (let [forms (lang/meme->forms "`x")
          expanded (expander/expand-forms forms)]
      (is (= '(quote x) (first expanded))))))

(deftest expand-syntax-quote-list
  (testing "syntax-quote on a list produces seq/concat/list"
    (let [forms (lang/meme->forms "`foo(x y)")
          expanded (first (expander/expand-forms forms))]
      ;; Should be (seq (concat (list (quote foo)) (list (quote x)) (list (quote y))))
      (is (seq? expanded))
      (is (= 'clojure.core/seq (first expanded))))))

(deftest expand-syntax-quote-with-unquote
  (testing "unquote inside syntax-quote splices the value"
    (let [forms (lang/meme->forms "`foo(~x)")
          expanded (first (expander/expand-forms forms))]
      ;; The expansion should reference x directly (not quoted)
      ;; (seq (concat (list (quote foo)) (list x)))
      (is (seq? expanded))
      ;; Find the concat args and check that x appears unquoted
      (let [concat-form (second expanded)
            args (rest concat-form)]
        ;; First arg: (list (quote foo)) — quoted head
        (is (= '(clojure.core/list (quote foo)) (first args)))
        ;; Second arg: (list x) — unquoted
        (is (= '(clojure.core/list x) (second args)))))))

(deftest expand-syntax-quote-with-unquote-splicing
  (testing "unquote-splicing inside syntax-quote splices the collection"
    (let [forms (lang/meme->forms "`foo(~@xs)")
          expanded (first (expander/expand-forms forms))
          ;; (seq (concat (list (quote foo)) xs))
          concat-form (second expanded)]
      ;; First arg: (list (quote foo)) — quoted head
      (is (= '(clojure.core/list (quote foo)) (first (rest concat-form))))
      ;; Second arg: xs — spliced directly
      (is (= 'xs (second (rest concat-form)))))))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- collect-auto-gensyms
  "Walk a form tree and collect all symbols ending in __auto__."
  [form]
  (cond
    (symbol? form)
    (if (re-find #"__auto__$" (name form)) [form] [])

    (seq? form)
    (vec (mapcat collect-auto-gensyms form))

    (vector? form)
    (vec (mapcat collect-auto-gensyms form))

    (map? form)
    (vec (mapcat (fn [[k v]] (concat (collect-auto-gensyms k)
                                     (collect-auto-gensyms v))) form))

    (set? form)
    (vec (mapcat collect-auto-gensyms form))

    :else []))

;; ---------------------------------------------------------------------------
;; Gensym consistency
;; ---------------------------------------------------------------------------

(deftest expand-gensym-consistent
  (testing "x# resolves to the same gensym within one syntax-quote"
    (let [expanded (first (expander/expand-forms (lang/meme->forms "`let([x# 1] x#)")))
          gensyms (collect-auto-gensyms expanded)]
      ;; There should be exactly two occurrences of x__NNN__auto__
      (is (= 2 (count gensyms))
          "expected exactly two gensym occurrences")
      (is (= (first gensyms) (second gensyms))
          "both occurrences of x# must resolve to the same gensym")))
  (testing "multiple distinct gensyms within one syntax-quote are independent"
    (let [expanded (first (expander/expand-forms (lang/meme->forms "`let([x# 1 y# 2] +(x# y#))")))
          gensyms (collect-auto-gensyms expanded)
          distinct-syms (set gensyms)]
      ;; x# and y# should produce two different gensyms, each used twice
      (is (= 4 (count gensyms)) "expected four gensym occurrences (x# x2, y# x2)")
      (is (= 2 (count distinct-syms)) "expected two distinct gensym symbols"))))

;; ---------------------------------------------------------------------------
;; Nested syntax-quote
;; ---------------------------------------------------------------------------

(deftest nested-syntax-quote-expands
  (testing "nested backtick does not crash"
    (let [forms (lang/meme->forms "``x")
          expanded (expander/expand-forms forms)]
      (is (seq? (first expanded)) "nested syntax-quote should expand to a seq form")))
  (testing "nested backtick produces double-quoting (code that generates the inner expansion)"
    ;; ``x should produce (seq (concat (list (quote quote)) (list (quote x))))
    ;; which evaluates to (quote x), NOT just x.
    ;; This matches Clojure's behavior where nested backticks add quoting levels.
    (let [expanded (first (expander/expand-forms (lang/meme->forms "``x")))]
      ;; The expansion should be a seq/concat form, not a bare (quote x)
      (is (= 'clojure.core/seq (first expanded)))
      (let [concat-form (second expanded)
            args (rest concat-form)]
        ;; First arg should quote 'quote': (list (quote quote))
        (is (= '(clojure.core/list (quote quote)) (first args)))
        ;; Second arg should quote 'x': (list (quote x))
        (is (= '(clojure.core/list (quote x)) (second args))))))
  (testing "nested backtick on list produces code that generates inner expansion"
    (let [expanded (first (expander/expand-forms (lang/meme->forms "``foo(x)")))]
      ;; Should be a seq/concat form (outer quoting)
      (is (= 'clojure.core/seq (first expanded)))
      ;; The expansion should contain quoted references to clojure.core/seq, etc.
      ;; i.e. code that reconstructs the inner expansion
      (let [concat-form (second expanded)
            first-arg (second concat-form)]
        ;; First element should be (list (quote clojure.core/seq))
        (is (= '(clojure.core/list (quote clojure.core/seq)) first-arg))))))

;; ---------------------------------------------------------------------------
;; expand-syntax-quotes on individual forms
;; ---------------------------------------------------------------------------

(deftest expand-syntax-quotes-vector
  (testing "vectors inside syntax-quote are preserved as vectors"
    (let [forms (lang/meme->forms "`[a b]")
          expanded (first (expander/expand-forms forms))]
      ;; Should produce (apply vector (concat ...))
      (is (seq? expanded))
      (is (= 'clojure.core/apply (first expanded)))
      (is (= 'clojure.core/vector (second expanded))))))

(deftest expand-syntax-quotes-self-quoting
  (testing "keywords, numbers, strings are self-quoting inside syntax-quote"
    (let [forms (lang/meme->forms "`:foo")
          expanded (first (expander/expand-forms forms))]
      (is (= :foo expanded)))
    (let [forms (lang/meme->forms "`42")
          expanded (first (expander/expand-forms forms))]
      (is (= 42 expanded)))
    (let [forms (lang/meme->forms "`\"hello\"")
          expanded (first (expander/expand-forms forms))]
      (is (= "hello" expanded)))))

;; ---------------------------------------------------------------------------
;; Gensym scoping across backtick boundaries
;; ---------------------------------------------------------------------------

(deftest gensym-independence-across-backticks
  (testing "same x# in two separate backticks produces different gensyms"
    (let [exp1 (first (expander/expand-forms (lang/meme->forms "`x#")))
          exp2 (first (expander/expand-forms (lang/meme->forms "`x#")))
          ;; Each is (quote x__NNN__auto__)
          sym1 (second exp1)
          sym2 (second exp2)]
      (is (re-find #"__auto__$" (name sym1)))
      (is (re-find #"__auto__$" (name sym2)))
      (is (not= sym1 sym2)
          "gensyms from separate syntax-quotes must be independent"))))

(deftest gensym-independence-across-nesting-levels
  (testing "x# in outer backtick and x# in nested backtick get different gensyms"
    (let [expanded (first (expander/expand-forms (lang/meme->forms "`list(x# `x#)")))
          gensyms (collect-auto-gensyms expanded)]
      ;; The outer x# and the inner (nested) x# should be different symbols
      ;; because the nested backtick creates a fresh *gensym-env*
      (is (>= (count gensyms) 2)
          "expected at least two gensym occurrences (outer and inner)")
      (let [distinct-syms (set gensyms)]
        (is (= 2 (count distinct-syms))
            "outer and inner x# must resolve to different gensyms")))))

(deftest gensym-escape-with-unquote-quote
  (testing "~'x# prevents gensym — keeps the literal symbol x#"
    (let [expanded (first (expander/expand-forms (lang/meme->forms "`list(x# ~'x#)")))
          gensyms (collect-auto-gensyms expanded)]
      ;; Only the first x# should be gensym'd; ~'x# remains literal x#
      (is (= 1 (count gensyms))
          "only the unescaped x# should be gensym'd")
      ;; The expansion should contain a reference to the literal symbol x#
      ;; via the unquote path: (list (quote x#)) — not (list (quote x__NNN__auto__))
      (let [concat-form (second expanded)
            args (vec (rest concat-form))
            ;; arg 0: (list (quote list))
            ;; arg 1: (list (quote x__NNN__auto__)) — gensym'd
            ;; arg 2: (list (quote x#)) — literal, via ~'x# which is ~(quote x#)
            escaped-arg (nth args 2)
            escaped-sym (second (second escaped-arg))]
        (is (= 'x# escaped-sym)
            "~'x# should preserve the literal symbol x#")))))

;; ---------------------------------------------------------------------------
;; Nested syntax-quote: double-backtick with unquote cancellation
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest double-backtick-eval
     (testing "``x eval produces (quote x) — double quoting"
       (let [expanded (first (expander/expand-forms (lang/meme->forms "``x")))]
         (is (= '(quote x) (eval expanded))
             "eval of ``x should produce (quote x)")))
     (testing "``foo(x) eval produces inner expansion code, second eval produces (foo x)"
       (let [expanded (first (expander/expand-forms (lang/meme->forms "``foo(x)")))
             once (eval expanded)]
      ;; First eval yields the inner expansion code (seq/concat form)
         (is (seq? once) "first eval should yield a seq form")
      ;; Second eval of that code produces the actual (foo x) list
         (is (= '(foo x) (eval once))
             "double eval of ``foo(x) should produce (foo x)")))))

(deftest double-backtick-single-unquote
  (testing "``~x — one unquote cancels one backtick level"
    (let [expanded (first (expander/expand-forms (lang/meme->forms "``~x")))]
      ;; Result should be (quote x) — the ~ cancels one `, leaving one `
      (is (= '(quote x) expanded)
          "``~x should expand to (quote x)"))))

(deftest double-backtick-double-unquote
  (testing "``~~x — two unquotes cancel both backtick levels"
    (let [expanded (first (expander/expand-forms (lang/meme->forms "``~~x")))]
      ;; Result should be the bare symbol x
      (is (= 'x expanded)
          "``~~x should expand to the bare symbol x"))))

;; ---------------------------------------------------------------------------
;; Nested syntax-quote with gensyms: macro-writing-macro pattern
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest nested-backtick-gensym-scoping
     (testing "gensym in outer backtick is independent of gensym in inner backtick"
    ;; `list(x# `list(x#)) — the outer x# and inner x# should get different gensyms
    ;; because each backtick level has its own *gensym-env*
       (let [expanded (first (expander/expand-forms (lang/meme->forms "`list(x# `list(x#))")))
          ;; Eval the outer expansion to get the data form
             evaled (eval expanded)
          ;; evaled is (list OUTER-GENSYM INNER-CODE)
             outer-sym (second evaled)
             inner-code (nth (seq evaled) 2)
          ;; Eval the inner code to get (list INNER-GENSYM)
             inner-evaled (eval inner-code)
             inner-sym (second inner-evaled)]
         (is (re-find #"__auto__$" (name outer-sym))
             "outer x# should be a gensym")
         (is (re-find #"__auto__$" (name inner-sym))
             "inner x# should be a gensym")
         (is (not= outer-sym inner-sym)
             "outer and inner x# must be different gensyms")))))

;; ---------------------------------------------------------------------------
;; :resolve-symbol option
;; ---------------------------------------------------------------------------

(deftest expand-syntax-quote-with-resolve-symbol
  (testing "resolver namespace-qualifies symbols in syntax-quote"
    (let [resolver (fn [sym] (symbol "my.ns" (name sym)))
          expanded (first (expander/expand-forms
                           (lang/meme->forms "`foo(x)")
                           {:resolve-symbol resolver}))
          ;; (seq (concat (list (quote my.ns/foo)) (list (quote my.ns/x))))
          concat-form (second expanded)
          args (vec (rest concat-form))]
      (is (= '(clojure.core/list (quote my.ns/foo)) (nth args 0)))
      (is (= '(clojure.core/list (quote my.ns/x)) (nth args 1)))))
  (testing "without resolver, symbols are not namespace-qualified (documented deviation)"
    (let [expanded (first (expander/expand-forms (lang/meme->forms "`foo")))]
      (is (= '(quote foo) expanded)
          "without resolver, `foo stays as foo, not current-ns/foo")))
  #?(:clj
     (testing "nested syntax-quote with resolver matches Clojure behavior"
       ;; RT2-H2: ``x with resolver must produce same eval result as Clojure's ``x.
       ;; Without resolver, symbols are unqualified — this is a documented deviation.
       (require 'mclj-lang.run)
       (let [resolver (resolve 'mclj-lang.run/default-resolve-symbol)
             expanded (first (expander/expand-forms
                               (lang/meme->forms "``x")
                               {:resolve-symbol resolver}))
             meme-result (eval expanded)
             clj-result (eval (read-string "``x"))]
         (is (= meme-result clj-result)
             "``x with resolver should match Clojure's eval result"))))
  (testing "resolver does not affect gensyms on unqualified symbols"
    (let [resolver (fn [sym] (if (namespace sym) sym (symbol "my.ns" (name sym))))
          expanded (first (expander/expand-forms
                           (lang/meme->forms "`x#")
                           {:resolve-symbol resolver}))]
      ;; sq-resolve-symbol is called first, then sq-gensym.
      ;; x# is unqualified → gensym fires BEFORE resolver can qualify it.
      ;; Result: (quote x__NNN__auto__)
      (is (re-find #"__auto__$" (name (second expanded)))
          "unqualified x# should be gensym'd")))
  (testing "namespace-qualified x# is NOT gensym'd (matches Clojure)"
    (let [expanded (first (expander/expand-forms
                           (lang/meme->forms "`ns/x#")
                           {}))]
      ;; ns/x# has a namespace, so sq-gensym preserves it literally
      (is (= 'ns/x# (second expanded))
          "ns-qualified x# preserved literally"))))

;; ---------------------------------------------------------------------------
;; Scar tissue: CljReaderConditional must survive expand-syntax-quotes.
;; Bug: on CLJS, CljReaderConditional is a defrecord satisfying map?, so
;; expand-syntax-quotes destructured it into a plain map {form: ... :splicing ...}.
;; Fix: check clj-reader-conditional? before map? in both expand-sq and
;; expand-syntax-quotes.
;; ---------------------------------------------------------------------------

(deftest reader-conditional-survives-expansion
  (testing "reader conditional roundtrips through expander at top level"
    (let [forms (lang/meme->forms "#?(:clj 1 :cljs 2)")
          expanded (expander/expand-forms forms)]
      (is (= 1 (count expanded)))
      (is (forms/clj-reader-conditional? (first expanded))
          "reader conditional should survive expand-forms as its original type")))
  (testing "reader conditional inside a list survives expansion"
    (let [forms (lang/meme->forms "f(#?(:clj 1 :cljs 2))")
          expanded (expander/expand-forms forms)
          inner (second (first expanded))]
      (is (forms/clj-reader-conditional? inner)
          "nested reader conditional should survive expand-forms")))
  (testing "reader conditional inside syntax-quote survives expand-sq"
    (let [forms (lang/meme->forms "`#?(:clj x :cljs y)")
          expanded (expander/expand-forms forms)]
      ;; The expansion should not crash — reader conditional is passed through
      (is (some? expanded)))))

;; ---------------------------------------------------------------------------
;; Scar tissue: syntax-quote preserves metadata on collections (RT3-F3)
;; Previously: `^:foo [1 2] lost metadata — no with-meta emitted.
;; ---------------------------------------------------------------------------

(deftest syntax-quote-preserves-collection-metadata
  (testing "`^:foo [1 2] — metadata on vector preserved via with-meta"
    (let [forms (lang/meme->forms "`^:foo [1 2]")
          expanded (expander/expand-forms forms)
          result (first expanded)]
      ;; The expansion should wrap in (with-meta ... {:foo true})
      (is (seq? result) "expansion should be a list form")
      (is (= 'clojure.core/with-meta (first result))
          "should emit with-meta wrapper")))
  (testing "`[1 2] — no metadata, no with-meta"
    (let [forms (lang/meme->forms "`[1 2]")
          expanded (expander/expand-forms forms)
          result (first expanded)]
      (is (seq? result))
      (is (not= 'clojure.core/with-meta (first result))
          "should NOT emit with-meta when no user metadata"))))

;; ---------------------------------------------------------------------------
;; Scar tissue: ~@ in set literal rejected in syntax-quote (RT3-F16)
;; Previously: silently accepted and expanded.
;; ---------------------------------------------------------------------------

(deftest splice-in-set-allowed-in-syntax-quote
  (testing "`#{~@xs} — unquote-splice in set (matches Clojure)"
    (let [forms (lang/meme->forms "`#{~@xs}")
          expanded (first (expander/expand-forms forms))]
      (is (= 'clojure.core/apply (first expanded)))
      (is (= 'clojure.core/hash-set (second expanded))))))

;; ---------------------------------------------------------------------------
;; RT6-F2: Unquote in syntax-quote must unwrap CljRaw and expand nested SQ.
;; Bug: expand-sq returned (:form unquote) bare, leaving CljRaw records
;; ({:value N :raw "..."}) and CljSyntaxQuote records in the expansion.
;; Eval would see these as maps, not as values/expanded syntax-quotes.
;; Fix: process the inner form through expand-syntax-quotes.
;; ---------------------------------------------------------------------------

(deftest unquote-unwraps-meme-raw-in-syntax-quote
  #?(:clj
     (testing "`~0xFF — hex literal inside unquote must expand to the number"
       (let [forms (lang/meme->forms "`~0xFF")
             expanded (expander/expand-forms forms)
             result (first expanded)]
         ;; After expansion, the unquoted value should be the plain number 255,
         ;; not a CljRaw record {value: 255, raw: "0xFF"}
         (is (= 255 result) "unquoted hex literal should expand to plain number"))))
  #?(:clj
     (testing "`~0777 — octal literal inside unquote must expand to the number"
       (let [forms (lang/meme->forms "`~0777")
             expanded (expander/expand-forms forms)
             result (first expanded)]
         (is (= 511 result) "unquoted octal literal should expand to plain number")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: empty unquote-splice behavior.
;;
;; An external audit suggested that `(~@[]) expands to (nil) in meme.  That
;; claim was twice wrong:
;;   (a) meme cannot parse `(~@[])` — bare parens without a head are rejected
;;       by meme's core rule ("every (...) needs a head");
;;   (b) Clojure itself returns nil (not ()) for `(~@[]), so even if meme
;;       could parse it, matching Clojure's expansion would be the correct
;;       outcome.
;;
;; For shapes meme CAN express (vectors, sets, calls), empty splices
;; produce the expected empty/minimal collection.  Lock the matrix in
;; so a future expander refactor doesn't break Clojure-compatibility.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest empty-splice-expansion-matrix
     (doseq [[label src expected] [["vector with only empty splice"
                                    "`[~@[]]"
                                    []]
                                   ["vector mixing literal and empty splice"
                                    "`[a ~@[]]"
                                    ;; meme's syntax-quote without a resolver
                                    ;; keeps symbols unqualified — expected is
                                    ;; the quoted unqualified symbol, not `a
                                    ;; (which Clojure's reader would resolve).
                                    '[a]]
                                   ["set with empty splice"
                                    "`#{~@[]}"
                                    #{}]
                                   ["call-form with empty splice evaluates correctly"
                                    "`list(~@[])"
                                    '(list)]
                                   ["vector with non-empty splice"
                                    "`[~@[1 2 3]]"
                                    [1 2 3]]]]
       (testing label
         (let [forms (lang/meme->forms src)
               expanded (expander/expand-forms forms)
               value (eval (first expanded))]
           (is (= expected value)
               (str "`" src "` must evaluate to " (pr-str expected))))))))
