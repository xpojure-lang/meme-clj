(ns beme.pipeline-snapshot-test
  "Characterization tests: captures exact tokenizer and reader output
   for a broad set of inputs. Used as a fast-fail regression net during
   the pipeline refactoring. If any of these break, the refactor changed
   observable behavior."
  (:require [clojure.test :refer [deftest is testing]]
            [beme.reader :as r]
            [beme.tokenizer :as tokenizer]
            [beme.grouper :as grouper]))

(defn- tokenize [s]
  (-> (tokenizer/tokenize s) (grouper/group-tokens s)))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- tok-summary
  "Extract the identity-relevant fields from a token."
  [tok]
  (select-keys tok [:type :value :line :col]))

(defn- tokens-for [s]
  (mapv tok-summary (tokenize s)))

(defn- forms-for [s]
  (r/read-beme-string s))

;; ---------------------------------------------------------------------------
;; Token snapshots — exact type/value/line/col for representative inputs
;; ---------------------------------------------------------------------------

(deftest token-snapshot-simple-call
  (is (= [{:type :symbol :value "foo" :line 1 :col 1}
           {:type :open-paren :value "(" :line 1 :col 4}
           {:type :symbol :value "x" :line 1 :col 5}
           {:type :close-paren :value ")" :line 1 :col 6}]
         (tokens-for "foo(x)"))))

(deftest token-snapshot-operator-call
  (is (= [{:type :symbol :value "+" :line 1 :col 1}
           {:type :open-paren :value "(" :line 1 :col 2}
           {:type :number :value "1" :line 1 :col 3}
           {:type :number :value "2" :line 1 :col 5}
           {:type :close-paren :value ")" :line 1 :col 6}]
         (tokens-for "+(1 2)"))))

(deftest token-snapshot-keyword
  (is (= [{:type :keyword :value ":active" :line 1 :col 1}]
         (tokens-for ":active"))))

(deftest token-snapshot-namespaced-keyword
  (is (= [{:type :keyword :value ":foo/bar" :line 1 :col 1}]
         (tokens-for ":foo/bar"))))

(deftest token-snapshot-auto-resolve-keyword
  (is (= [{:type :keyword :value "::local" :line 1 :col 1}]
         (tokens-for "::local"))))

(deftest token-snapshot-number-formats
  (is (= [{:type :number :value "42" :line 1 :col 1}] (tokens-for "42")))
  (is (= [{:type :number :value "3.14" :line 1 :col 1}] (tokens-for "3.14")))
  (is (= [{:type :number :value "-1" :line 1 :col 1}] (tokens-for "-1")))
  (is (= [{:type :number :value "0xFF" :line 1 :col 1}] (tokens-for "0xFF")))
  (is (= [{:type :number :value "42N" :line 1 :col 1}] (tokens-for "42N")))
  (is (= [{:type :number :value "1.5M" :line 1 :col 1}] (tokens-for "1.5M")))
  (is (= [{:type :number :value "8r77" :line 1 :col 1}] (tokens-for "8r77"))))

(deftest token-snapshot-string
  (is (= [{:type :string :value "\"hello\"" :line 1 :col 1}]
         (tokens-for "\"hello\"")))
  (is (= [{:type :string :value "\"he\\\"llo\"" :line 1 :col 1}]
         (tokens-for "\"he\\\"llo\""))))

(deftest token-snapshot-char-literal
  (is (= [{:type :char :value "\\a" :line 1 :col 1}] (tokens-for "\\a")))
  (is (= [{:type :char :value "\\newline" :line 1 :col 1}] (tokens-for "\\newline")))
  (is (= [{:type :char :value "\\u0041" :line 1 :col 1}] (tokens-for "\\u0041")))
  (is (= [{:type :char :value "\\o101" :line 1 :col 1}] (tokens-for "\\o101"))))

(deftest token-snapshot-regex
  (is (= [{:type :regex :value "#\"pattern\"" :line 1 :col 1}]
         (tokens-for "#\"pattern\""))))

(deftest token-snapshot-delimiters
  (is (= [{:type :open-paren :value "(" :line 1 :col 1}
           {:type :close-paren :value ")" :line 1 :col 2}]
         (tokens-for "()")))
  (is (= [{:type :open-bracket :value "[" :line 1 :col 1}
           {:type :close-bracket :value "]" :line 1 :col 2}]
         (tokens-for "[]")))
  (is (= [{:type :open-brace :value "{" :line 1 :col 1}
           {:type :close-brace :value "}" :line 1 :col 2}]
         (tokens-for "{}")))
  (is (= [{:type :open-set :value "#{" :line 1 :col 1}
           {:type :close-brace :value "}" :line 1 :col 3}]
         (tokens-for "#{}"))))

(deftest token-snapshot-prefix-operators
  (is (= [{:type :deref :value "@" :line 1 :col 1}] (tokens-for "@")))
  (is (= [{:type :meta :value "^" :line 1 :col 1}] (tokens-for "^")))
  (is (= [{:type :quote :value "'" :line 1 :col 1}] (tokens-for "'")))
  (is (= [{:type :unquote :value "~" :line 1 :col 1}
           {:type :symbol :value "x" :line 1 :col 2}]
         (tokens-for "~x")))
  (is (= [{:type :unquote-splicing :value "~@" :line 1 :col 1}
           {:type :symbol :value "xs" :line 1 :col 3}]
         (tokens-for "~@xs"))))

(deftest token-snapshot-dispatch-forms
  (is (= [{:type :var-quote :value "#'" :line 1 :col 1}
           {:type :symbol :value "foo" :line 1 :col 3}]
         (tokens-for "#'foo")))
  (is (= [{:type :discard :value "#_" :line 1 :col 1}
           {:type :symbol :value "foo" :line 1 :col 3}]
         (tokens-for "#_foo")))
  (is (= [{:type :open-anon-fn :value "#(" :line 1 :col 1}
           {:type :symbol :value "inc" :line 1 :col 3}
           {:type :open-paren :value "(" :line 1 :col 6}
           {:type :symbol :value "%" :line 1 :col 7}
           {:type :close-paren :value ")" :line 1 :col 8}
           {:type :close-paren :value ")" :line 1 :col 9}]
         (tokens-for "#(inc(%))"))))

(deftest token-snapshot-tagged-literal
  (is (= [{:type :tagged-literal :value "#inst" :line 1 :col 1}]
         (tokens-for "#inst"))))

(deftest token-snapshot-opaque-reader-cond
  (is (= [{:type :reader-cond-raw :value "#?(:clj 1)" :line 1 :col 1}]
         (tokens-for "#?(:clj 1)")))
  (is (= [{:type :reader-cond-raw :value "#?@(:clj [1])" :line 1 :col 1}]
         (tokens-for "#?@(:clj [1])"))))

(deftest token-snapshot-opaque-namespaced-map
  (is (= [{:type :namespaced-map-raw :value "#:ns{:a 1}" :line 1 :col 1}]
         (tokens-for "#:ns{:a 1}"))))

(deftest token-snapshot-syntax-quote
  (is (= [{:type :syntax-quote-raw :value "`foo" :line 1 :col 1}]
         (tokens-for "`foo")))
  (is (= [{:type :syntax-quote-raw :value "`(a b c)" :line 1 :col 1}]
         (tokens-for "`(a b c)"))))

(deftest token-snapshot-multi-line
  (is (= [{:type :symbol :value "foo" :line 1 :col 1}
           {:type :symbol :value "bar" :line 2 :col 1}]
         (tokens-for "foo\nbar"))))

(deftest token-snapshot-commas-as-whitespace
  (is (= [{:type :symbol :value "a" :line 1 :col 1}
           {:type :symbol :value "b" :line 1 :col 3}
           {:type :symbol :value "c" :line 1 :col 5}]
         (tokens-for "a,b,c"))))

(deftest token-snapshot-comment-stripped
  (is (= [{:type :symbol :value "foo" :line 2 :col 1}]
         (tokens-for "; comment\nfoo"))))

(deftest token-snapshot-operators
  (is (= [{:type :symbol :value "+" :line 1 :col 1}] (tokens-for "+")))
  (is (= [{:type :symbol :value "-" :line 1 :col 1}] (tokens-for "-")))
  (is (= [{:type :symbol :value "->" :line 1 :col 1}] (tokens-for "->")))
  (is (= [{:type :symbol :value "->>" :line 1 :col 1}] (tokens-for "->>"))))

(deftest token-snapshot-signed-number-vs-operator
  (testing "-1 is a number token"
    (is (= [{:type :number :value "-1" :line 1 :col 1}]
           (tokens-for "-1"))))
  (testing "-(1) is symbol then paren"
    (is (= [{:type :symbol :value "-" :line 1 :col 1}
             {:type :open-paren :value "(" :line 1 :col 2}
             {:type :number :value "1" :line 1 :col 3}
             {:type :close-paren :value ")" :line 1 :col 4}]
           (tokens-for "-(1)")))))

(deftest token-snapshot-constructor
  (is (= [{:type :symbol :value "java.util.Date." :line 1 :col 1}
           {:type :open-paren :value "(" :line 1 :col 16}
           {:type :close-paren :value ")" :line 1 :col 17}]
         (tokens-for "java.util.Date.()"))))

(deftest token-snapshot-namespace-qualified-symbol
  (is (= [{:type :symbol :value "str/upper-case" :line 1 :col 1}]
         (tokens-for "str/upper-case"))))

(deftest token-snapshot-empty-inputs
  (is (= [] (tokens-for "")))
  (is (= [] (tokens-for "   \t\n  ")))
  (is (= [] (tokens-for ",,,")))
  (is (= [] (tokens-for "; just a comment"))))

;; ---------------------------------------------------------------------------
;; Opaque form token snapshots — these are the critical contract for Phase 3
;; ---------------------------------------------------------------------------

(deftest token-snapshot-reader-cond-with-comment
  (testing "#? with comment containing )"
    (is (= [{:type :reader-cond-raw :value "#?(:clj ; comment with )\n 1)" :line 1 :col 1}]
           (tokens-for "#?(:clj ; comment with )\n 1)")))))

(deftest token-snapshot-reader-cond-with-char-literal
  (testing "#? with \\) char literal"
    (is (= [{:type :reader-cond-raw :value "#?(:clj \\) :cljs \\x)" :line 1 :col 1}]
           (tokens-for "#?(:clj \\) :cljs \\x)")))))

(deftest token-snapshot-reader-cond-with-string
  (testing "#? with string containing )"
    (is (= [{:type :reader-cond-raw :value "#?(:clj \")\" :cljs nil)" :line 1 :col 1}]
           (tokens-for "#?(:clj \")\" :cljs nil)")))))

(deftest token-snapshot-namespaced-map-with-char
  (testing "#:ns{} with \\} char literal"
    (is (= [{:type :namespaced-map-raw :value "#:user{:ch \\}}" :line 1 :col 1}]
           (tokens-for "#:user{:ch \\}}")))))

(deftest token-snapshot-syntax-quote-unquote-form
  (testing "`~(foo bar) is single token"
    (is (= [{:type :syntax-quote-raw :value "`~(foo bar)" :line 1 :col 1}]
           (tokens-for "`~(foo bar)"))))
  (testing "`~@(foo bar) is single token"
    (is (= [{:type :syntax-quote-raw :value "`~@(foo bar)" :line 1 :col 1}]
           (tokens-for "`~@(foo bar)"))))
  (testing "`~symbol is single token"
    (is (= [{:type :syntax-quote-raw :value "`~foo" :line 1 :col 1}]
           (tokens-for "`~foo"))))
  (testing "`~\"string\" is single token"
    (is (= [{:type :syntax-quote-raw :value "`~\"foo\"" :line 1 :col 1}]
           (tokens-for "`~\"foo\"")))))

(deftest token-snapshot-complex-call
  (testing "defn with multi-arity"
    (is (= [{:type :symbol :value "defn" :line 1 :col 1}
             {:type :open-paren :value "(" :line 1 :col 5}
             {:type :symbol :value "f" :line 1 :col 6}
             {:type :open-bracket :value "[" :line 1 :col 8}
             {:type :symbol :value "x" :line 1 :col 9}
             {:type :close-bracket :value "]" :line 1 :col 10}
             {:type :open-paren :value "(" :line 1 :col 11}
             {:type :symbol :value "x" :line 1 :col 12}
             {:type :close-paren :value ")" :line 1 :col 13}
             {:type :close-paren :value ")" :line 1 :col 14}]
           (tokens-for "defn(f [x](x))")))))

;; ---------------------------------------------------------------------------
;; Form snapshots — exact parsed output for representative inputs
;; ---------------------------------------------------------------------------

(deftest form-snapshot-simple-call
  (is (= '[(foo x)] (forms-for "foo(x)")))
  (is (= '[(+ 1 2)] (forms-for "+(1 2)")))
  (is (= '[(foo)] (forms-for "foo()"))))

(deftest form-snapshot-nested-call
  (is (= '[(a (b (c x)))] (forms-for "a(b(c(x)))"))))

(deftest form-snapshot-keyword-call
  (is (= '(:name x) (first (forms-for ":name(x)"))))
  (is (= '(:require [bar]) (first (forms-for ":require([bar])")))))

(deftest form-snapshot-vector-as-head
  (is (= '([x] 1) (first (forms-for "[x](1)")))))

(deftest form-snapshot-spacing-irrelevant
  (is (= '[(f x)] (forms-for "f(x)")))
  (is (= '[(f x)] (forms-for "f (x)")))
  (is (= '[(f x)] (forms-for "f\n(x)")))
  (is (= '[(f x)] (forms-for "f\t(x)"))))

(deftest form-snapshot-data-literals
  (is (= [[1 2 3]] (forms-for "[1 2 3]")))
  (is (= [#{1 2 3}] (forms-for "#{1 2 3}")))
  (is (= [] (forms-for "")))
  (is (= [nil] (forms-for "nil")))
  (is (= [true] (forms-for "true")))
  (is (= [false] (forms-for "false")))
  (is (= [-1] (forms-for "-1")))
  (is (= [42] (forms-for "42")))
  (is (= ["hello"] (forms-for "\"hello\""))))

(deftest form-snapshot-map
  (let [m (first (forms-for "{:a 1 :b 2}"))]
    (is (map? m))
    (is (= 1 (:a m)))
    (is (= 2 (:b m)))))

(deftest form-snapshot-def
  (is (= '[(def x 42)] (forms-for "def(x 42)"))))

(deftest form-snapshot-defn
  (is (= '[(defn f [x] (+ x 1))] (forms-for "defn(f [x] +(x 1))"))))

(deftest form-snapshot-let
  (is (= '[(let [x 1] (+ x 2))] (forms-for "let([x 1] +(x 2))"))))

(deftest form-snapshot-if
  (is (= '[(if true 1 2)] (forms-for "if(true 1 2)"))))

(deftest form-snapshot-try-catch
  (is (= '[(try (risky) (catch Exception e (handle e)))]
         (forms-for "try(risky() catch(Exception e handle(e)))"))))

(deftest form-snapshot-threading
  (is (= '[(-> x (foo 1) (bar 2))]
         (forms-for "->(x foo(1) bar(2))"))))

(deftest form-snapshot-deref
  (is (= '[(clojure.core/deref state)] (forms-for "@state"))))

(deftest form-snapshot-quote
  (is (= '[(quote foo)] (forms-for "'foo")))
  (is (= '[(quote (1 2 3))] (forms-for "'(1 2 3)"))))

(deftest form-snapshot-var-quote
  (is (= '[(var foo)] (forms-for "#'foo"))))

(deftest form-snapshot-discard
  (is (= '[(bar)] (forms-for "#_foo bar()")))
  (is (= [] (forms-for "#_foo")))
  (is (= [[1 3]] (forms-for "[1 #_2 3]"))))

(deftest form-snapshot-metadata
  (let [form (first (forms-for "^:private x"))]
    (is (= 'x form))
    (is (= {:private true} (meta form)))))

(deftest form-snapshot-anon-fn
  (is (= '[(fn [%1] (inc %1))] (forms-for "#(inc(%))")))
  (is (= '[(fn [%1 %2] (+ %1 %2))] (forms-for "#(+(%1 %2))")))
  (is (= '[(fn [] (rand))] (forms-for "#(rand())")))
  (is (= '[(fn [& %&] (apply str %&))] (forms-for "#(apply(str %&))"))))

(deftest form-snapshot-auto-resolve-keyword
  #?(:clj
     (is (= (list 'clojure.core/read-string "::local")
            (first (forms-for "::local"))))
     :cljs
     (is (thrown-with-msg? js/Error #"resolve-keyword"
           (forms-for "::local")))))

(deftest form-snapshot-char
  (is (= [\a] (forms-for "\\a")))
  (is (= [\newline] (forms-for "\\newline"))))

(deftest form-snapshot-interop
  (is (= '(.toUpperCase "hello") (first (forms-for ".toUpperCase(\"hello\")"))))
  (is (= '(Math/abs -1) (first (forms-for "Math/abs(-1)"))))
  (is (= '(.-x point) (first (forms-for ".-x(point)"))))
  (is (= 'Math/PI (first (forms-for "Math/PI")))))

(deftest form-snapshot-ns
  (let [form (first (forms-for "ns(foo :require([bar]))"))]
    (is (= 'ns (first form)))
    (is (= 'foo (second form)))
    (is (= :require (first (nth form 2))))))

(deftest form-snapshot-multi-form
  (is (= '[(def x 42) (println x)]
         (forms-for "def(x 42)\nprintln(x)"))))

(deftest form-snapshot-set-as-head
  (let [form (first (forms-for "#{:a :b}(x)"))]
    (is (set? (first form)))
    (is (= 'x (second form)))))

(deftest form-snapshot-map-as-head
  (let [form (first (forms-for "{:a 1}(:a)"))]
    (is (map? (first form)))
    (is (= :a (second form)))))

(deftest form-snapshot-empty-list-via-quote
  (is (= '[(quote ())] (forms-for "'()"))))

;; ---------------------------------------------------------------------------
;; JVM-only form snapshots
;; ---------------------------------------------------------------------------

#?(:clj
(deftest form-snapshot-tagged-literal
  (let [form (first (forms-for "#uuid \"550e8400-e29b-41d4-a716-446655440000\""))]
    (is (tagged-literal? form)))))

#?(:clj
(deftest form-snapshot-reader-conditional
  (let [form (first (forms-for "#?(:clj 1 :cljs 2)"))]
    (is (reader-conditional? form)))))

#?(:clj
(deftest form-snapshot-namespaced-map
  (let [form (first (forms-for "#:user{:name \"x\" :age 1}"))]
    (is (= "x" (:user/name form)))
    (is (= 1 (:user/age form))))))

#?(:clj
(deftest form-snapshot-syntax-quote
  (is (some? (first (forms-for "`foo"))))
  (is (some? (first (forms-for "`(a b c)"))))))

#?(:clj
(deftest form-snapshot-regex
  (let [form (first (forms-for "#\"pattern\""))]
    (is (instance? java.util.regex.Pattern form))
    (is (= "pattern" (.pattern ^java.util.regex.Pattern form))))))

#?(:clj
(deftest form-snapshot-ratio
  (is (= 1/2 (first (forms-for "1/2"))))))

#?(:clj
(deftest form-snapshot-bigint
  (is (= 42N (first (forms-for "42N"))))))

#?(:clj
(deftest form-snapshot-bigdecimal
  (is (= 1.5M (first (forms-for "1.5M"))))))

;; ---------------------------------------------------------------------------
;; begin/end textual call delimiters
;; ---------------------------------------------------------------------------

(deftest token-snapshot-begin-end
  (is (= [{:type :symbol :value "foo" :line 1 :col 1}
           {:type :symbol :value "begin" :line 1 :col 5}
           {:type :symbol :value "x" :line 1 :col 11}
           {:type :symbol :value "end" :line 1 :col 13}]
         (tokens-for "foo begin x end"))))

(deftest form-snapshot-begin-end
  (is (= '[(foo x)] (forms-for "foo begin x end")))
  (is (= '[(foo (bar x))] (forms-for "foo begin bar begin x end end"))))
