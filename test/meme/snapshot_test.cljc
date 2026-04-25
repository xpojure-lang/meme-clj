(ns meme.snapshot-test
  "Characterization tests: captures exact tokenizer and reader output
   for a broad set of inputs. Regression net for tokenizer and reader
   output — if any of these break, something changed observable behavior."
  (:require [clojure.test :refer [deftest is testing]]
            [mclj-lang.api :as lang]
            [meme.tools.clj.forms :as forms]
            [meme.tools.clj.stages :as stages]
            [mclj-lang.grammar :as grammar]
            [mclj-lang.test-util :as tokenizer]))

(defn- eval-rc-forms
  "Read src and run step-evaluate-reader-conditionals; return :forms."
  [src]
  (:forms (-> {:source src :opts {:grammar grammar/grammar}}
              stages/step-parse
              stages/step-read
              stages/step-evaluate-reader-conditionals)))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- tok-summary
  "Extract the identity-relevant fields from a token."
  [tok]
  (select-keys tok [:type :raw :line :col]))

(defn- tokens-for [s]
  (into [] (comp (remove #(#{:whitespace :newline :comment :bom :shebang} (:type %)))
                 (map tok-summary))
        (tokenizer/tokenize s)))

(defn- forms-for [s]
  (lang/mclj->forms s))

;; ---------------------------------------------------------------------------
;; Token snapshots — exact type/value/line/col for representative inputs
;; ---------------------------------------------------------------------------

(deftest token-snapshot-simple-call
  (is (= [{:type :symbol :raw "foo" :line 1 :col 1}
          {:type :open-paren :raw "(" :line 1 :col 4}
          {:type :symbol :raw "x" :line 1 :col 5}
          {:type :close-paren :raw ")" :line 1 :col 6}]
         (tokens-for "foo(x)"))))

(deftest token-snapshot-operator-call
  (is (= [{:type :symbol :raw "+" :line 1 :col 1}
          {:type :open-paren :raw "(" :line 1 :col 2}
          {:type :number :raw "1" :line 1 :col 3}
          {:type :number :raw "2" :line 1 :col 5}
          {:type :close-paren :raw ")" :line 1 :col 6}]
         (tokens-for "+(1 2)"))))

(deftest token-snapshot-keyword
  (is (= [{:type :keyword :raw ":active" :line 1 :col 1}]
         (tokens-for ":active"))))

(deftest token-snapshot-namespaced-keyword
  (is (= [{:type :keyword :raw ":foo/bar" :line 1 :col 1}]
         (tokens-for ":foo/bar"))))

(deftest token-snapshot-auto-resolve-keyword
  (is (= [{:type :keyword :raw "::local" :line 1 :col 1}]
         (tokens-for "::local"))))

(deftest token-snapshot-number-formats
  (is (= [{:type :number :raw "42" :line 1 :col 1}] (tokens-for "42")))
  (is (= [{:type :number :raw "3.14" :line 1 :col 1}] (tokens-for "3.14")))
  (is (= [{:type :number :raw "-1" :line 1 :col 1}] (tokens-for "-1")))
  (is (= [{:type :number :raw "0xFF" :line 1 :col 1}] (tokens-for "0xFF")))
  (is (= [{:type :number :raw "42N" :line 1 :col 1}] (tokens-for "42N")))
  (is (= [{:type :number :raw "1.5M" :line 1 :col 1}] (tokens-for "1.5M")))
  (is (= [{:type :number :raw "8r77" :line 1 :col 1}] (tokens-for "8r77"))))

(deftest token-snapshot-string
  (is (= [{:type :string :raw "\"hello\"" :line 1 :col 1}]
         (tokens-for "\"hello\"")))
  (is (= [{:type :string :raw "\"he\\\"llo\"" :line 1 :col 1}]
         (tokens-for "\"he\\\"llo\""))))

(deftest token-snapshot-char-literal
  (is (= [{:type :char-literal :raw "\\a" :line 1 :col 1}] (tokens-for "\\a")))
  (is (= [{:type :char-literal :raw "\\newline" :line 1 :col 1}] (tokens-for "\\newline")))
  (is (= [{:type :char-literal :raw "\\u0041" :line 1 :col 1}] (tokens-for "\\u0041")))
  (is (= [{:type :char-literal :raw "\\o101" :line 1 :col 1}] (tokens-for "\\o101"))))

(deftest token-snapshot-regex
  (is (= [{:type :regex :raw "#\"pattern\"" :line 1 :col 1}]
         (tokens-for "#\"pattern\""))))

(deftest token-snapshot-delimiters
  (is (= [{:type :open-paren :raw "(" :line 1 :col 1}
          {:type :close-paren :raw ")" :line 1 :col 2}]
         (tokens-for "()")))
  (is (= [{:type :open-bracket :raw "[" :line 1 :col 1}
          {:type :close-bracket :raw "]" :line 1 :col 2}]
         (tokens-for "[]")))
  (is (= [{:type :open-brace :raw "{" :line 1 :col 1}
          {:type :close-brace :raw "}" :line 1 :col 2}]
         (tokens-for "{}")))
  (is (= [{:type :open-set :raw "#{" :line 1 :col 1}
          {:type :close-brace :raw "}" :line 1 :col 3}]
         (tokens-for "#{}"))))

(deftest token-snapshot-prefix-operators
  (is (= [{:type :deref :raw "@" :line 1 :col 1}] (tokens-for "@")))
  (is (= [{:type :meta :raw "^" :line 1 :col 1}] (tokens-for "^")))
  (is (= [{:type :quote :raw "'" :line 1 :col 1}] (tokens-for "'")))
  (is (= [{:type :unquote :raw "~" :line 1 :col 1}
          {:type :symbol :raw "x" :line 1 :col 2}]
         (tokens-for "~x")))
  (is (= [{:type :unquote-splicing :raw "~@" :line 1 :col 1}
          {:type :symbol :raw "xs" :line 1 :col 3}]
         (tokens-for "~@xs"))))

(deftest token-snapshot-dispatch-forms
  (is (= [{:type :var-quote :raw "#'" :line 1 :col 1}
          {:type :symbol :raw "foo" :line 1 :col 3}]
         (tokens-for "#'foo")))
  (is (= [{:type :discard :raw "#_" :line 1 :col 1}
          {:type :symbol :raw "foo" :line 1 :col 3}]
         (tokens-for "#_foo")))
  (is (= [{:type :open-anon-fn :raw "#(" :line 1 :col 1}
          {:type :symbol :raw "inc" :line 1 :col 3}
          {:type :open-paren :raw "(" :line 1 :col 6}
          {:type :symbol :raw "%" :line 1 :col 7}
          {:type :close-paren :raw ")" :line 1 :col 8}
          {:type :close-paren :raw ")" :line 1 :col 9}]
         (tokens-for "#(inc(%))"))))

(deftest token-snapshot-tagged-literal
  (is (= [{:type :hashtag-symbol :raw "#inst" :line 1 :col 1}]
         (tokens-for "#inst"))))

(deftest token-snapshot-reader-cond
  (testing "reader cond starts with :reader-cond"
    (is (= :reader-cond (:type (first (tokens-for "#?(:clj 1)"))))))
  (testing "reader cond + eval-rc yields matching platform value"
    (is (= [#?(:clj 1 :cljs 2)] (eval-rc-forms "#?(:clj 1 :cljs 2)")))))

(deftest token-snapshot-namespaced-map
  (testing "namespaced map tokens start with :namespaced-map"
    (is (= :namespaced-map (:type (first (tokens-for "#:ns{:a 1}"))))))
  (testing "namespaced map parses to correct form"
    (is (= [{:ns/a 1}] (forms-for "#:ns{:a 1}")))))

(deftest token-snapshot-syntax-quote
  (testing "` emits :syntax-quote prefix token"
    (is (= :syntax-quote (:type (first (tokens-for "`foo"))))))
  (testing "`foo produces quoted symbol form"
    (is (some? (forms-for "`foo")))))

(deftest token-snapshot-multi-line
  (is (= [{:type :symbol :raw "foo" :line 1 :col 1}
          {:type :symbol :raw "bar" :line 2 :col 1}]
         (tokens-for "foo\nbar"))))

(deftest token-snapshot-commas-as-whitespace
  (is (= [{:type :symbol :raw "a" :line 1 :col 1}
          {:type :symbol :raw "b" :line 1 :col 3}
          {:type :symbol :raw "c" :line 1 :col 5}]
         (tokens-for "a,b,c"))))

(deftest token-snapshot-comment-stripped
  (is (= [{:type :symbol :raw "foo" :line 2 :col 1}]
         (tokens-for "; comment\nfoo"))))

(deftest token-snapshot-operators
  (is (= [{:type :symbol :raw "+" :line 1 :col 1}] (tokens-for "+")))
  (is (= [{:type :symbol :raw "-" :line 1 :col 1}] (tokens-for "-")))
  (is (= [{:type :symbol :raw "->" :line 1 :col 1}] (tokens-for "->")))
  (is (= [{:type :symbol :raw "->>" :line 1 :col 1}] (tokens-for "->>"))))

(deftest token-snapshot-signed-number-vs-operator
  (testing "-1 is a number token"
    (is (= [{:type :number :raw "-1" :line 1 :col 1}]
           (tokens-for "-1"))))
  (testing "-(1) is symbol then paren"
    (is (= [{:type :symbol :raw "-" :line 1 :col 1}
            {:type :open-paren :raw "(" :line 1 :col 2}
            {:type :number :raw "1" :line 1 :col 3}
            {:type :close-paren :raw ")" :line 1 :col 4}]
           (tokens-for "-(1)")))))

(deftest token-snapshot-constructor
  (is (= [{:type :symbol :raw "java.util.Date." :line 1 :col 1}
          {:type :open-paren :raw "(" :line 1 :col 16}
          {:type :close-paren :raw ")" :line 1 :col 17}]
         (tokens-for "java.util.Date.()"))))

(deftest token-snapshot-namespace-qualified-symbol
  (is (= [{:type :symbol :raw "str/upper-case" :line 1 :col 1}]
         (tokens-for "str/upper-case"))))

(deftest token-snapshot-empty-inputs
  (is (= [] (tokens-for "")))
  (is (= [] (tokens-for "   \t\n  ")))
  (is (= [] (tokens-for ",,,")))
  (is (= [] (tokens-for "; just a comment"))))

;; ---------------------------------------------------------------------------
;; Opaque form token snapshots — these are the critical contract for Phase 3
;; ---------------------------------------------------------------------------

(deftest reader-cond-with-tricky-content
  (testing "#? with comment containing ) — eval-rc picks matching branch"
    (is (= [#?(:clj 1 :cljs 1)] (eval-rc-forms "#?(:clj ; comment with )\n 1 :cljs 1)"))))
  (testing "#? with char literal \\) — eval-rc picks matching branch"
    (is (= [#?(:clj \) :cljs \x)] (eval-rc-forms "#?(:clj \\) :cljs \\x)"))))
  (testing "#? with string containing ) — eval-rc picks matching branch"
    (is (= [#?(:clj ")" :cljs nil)] (eval-rc-forms "#?(:clj \")\" :cljs nil)")))))

(deftest token-snapshot-namespaced-map-with-char
  (testing "#:ns{} with \\} char literal parses correctly"
    (is (= :namespaced-map (:type (first (tokens-for "#:user{:ch \\}}")))))
    (is (= [{:user/ch \}}] (forms-for "#:user{:ch \\}}")))))

(deftest token-snapshot-syntax-quote-unquote-form
  (testing "` followed by ~ produces :syntax-quote then :unquote tokens"
    (let [tokens (tokens-for "`~foo")]
      (is (= :syntax-quote (:type (first tokens))))
      (is (= :unquote (:type (second tokens)))))))

(deftest token-snapshot-complex-call
  (testing "defn with multi-arity"
    (is (= [{:type :symbol :raw "defn" :line 1 :col 1}
            {:type :open-paren :raw "(" :line 1 :col 5}
            {:type :symbol :raw "f" :line 1 :col 6}
            {:type :open-bracket :raw "[" :line 1 :col 8}
            {:type :symbol :raw "x" :line 1 :col 9}
            {:type :close-bracket :raw "]" :line 1 :col 10}
            {:type :open-paren :raw "(" :line 1 :col 11}
            {:type :symbol :raw "x" :line 1 :col 12}
            {:type :close-paren :raw ")" :line 1 :col 13}
            {:type :close-paren :raw ")" :line 1 :col 14}]
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

(deftest form-snapshot-spacing-significant
  (is (= '[(f x)] (forms-for "f(x)")))
  (testing "space prevents call — bare paren error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"Bare parentheses" (forms-for "f (x)"))))
  (testing "newline prevents call"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"Bare parentheses" (forms-for "f\n(x)"))))
  (testing "tab prevents call"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"Bare parentheses" (forms-for "f\t(x)")))))

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
  (is (= '[(quote ())] (forms-for "'()")))
  (is (= '[(quote (f x))] (forms-for "'f(x)"))))

(deftest form-snapshot-var-quote
  (is (= '[(var foo)] (forms-for "#'foo"))))

(deftest form-snapshot-discard
  (is (= '[(bar)] (forms-for "#_foo bar()")))
  (is (= [] (forms-for "#_foo")))
  (is (= [[1 3]] (forms-for "[1 #_2 3]"))))

(deftest form-snapshot-metadata
  (let [form (first (forms-for "^:private x"))]
    (is (= 'x form))
    (is (= {:private true} (dissoc (meta form) :meme/leading-trivia :meme/meta-chain)))))

(deftest form-snapshot-anon-fn
  (is (= '[(fn [%1] (inc %1))] (forms-for "#(inc(%))")))
  (is (= '[(fn [%1 %2] (+ %1 %2))] (forms-for "#(+(%1 %2))")))
  (is (= '[(fn [] (rand))] (forms-for "#(rand())")))
  (is (= '[(fn [& %&] (apply str %&))] (forms-for "#(apply(str %&))"))))

(deftest form-snapshot-auto-resolve-keyword
  #?(:clj
     (let [form (first (forms-for "::local"))]
       (is (forms/deferred-auto-keyword? form))
       (is (= "::local" (forms/deferred-auto-keyword-raw form))))
     :cljs
     (let [form (first (forms-for "::local"))]
       (is (forms/deferred-auto-keyword? form))
       (is (= "::local" (forms/deferred-auto-keyword-raw form))))))

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

(deftest form-snapshot-reader-conditional
  (testing "mclj->forms preserves the record; eval-rc yields platform value"
    (is (forms/clj-reader-conditional? (first (forms-for "#?(:clj 1 :cljs 2)"))))
    (is (= #?(:clj 1 :cljs 2) (first (eval-rc-forms "#?(:clj 1 :cljs 2)"))))))

#?(:clj
   (deftest form-snapshot-namespaced-map
     (let [form (first (forms-for "#:user{:name \"x\" :age 1}"))]
       (is (= "x" (:user/name form)))
       (is (= 1 (:user/age form))))))

(deftest form-snapshot-syntax-quote
  (is (some? (first (forms-for "`foo"))))
  (is (some? (first (forms-for "`a(b c)")))))

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

