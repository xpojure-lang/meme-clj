(ns meme.alpha.roundtrip-test
  (:require [clojure.test :refer [deftest is testing]]
            [meme.alpha.core :as core]
            [meme.alpha.emit.printer :as p]
            [meme.alpha.forms :as forms]))

(defn- roundtrip-forms
  "Parse meme string, get forms. Then print forms back to meme and re-parse.
   The re-parsed forms should equal the original forms."
  [meme-src]
  (let [forms1 (core/meme->forms meme-src)
        meme-text (p/print-meme-string forms1)
        forms2 (core/meme->forms meme-text)]
    [forms1 forms2 meme-text]))

;; ---------------------------------------------------------------------------
;; Rule 1: Call
;; ---------------------------------------------------------------------------

(deftest roundtrip-simple-call
  (let [[f1 f2 _] (roundtrip-forms "println(\"hello\")")]
    (is (= f1 f2))))

(deftest roundtrip-nested-call
  (let [[f1 f2 _] (roundtrip-forms "str(\"hi \" to-str(x))")]
    (is (= f1 f2))))

(deftest roundtrip-operator-call
  (let [[f1 f2 _] (roundtrip-forms "+(1 2 3)")]
    (is (= f1 f2))))

(deftest roundtrip-zero-arg-call
  (let [[f1 f2 _] (roundtrip-forms "foo()")]
    (is (= f1 f2))))

(deftest roundtrip-keyword-call
  (let [[f1 f2 _] (roundtrip-forms "filter(:active accounts)")]
    (is (= f1 f2))))

;; ---------------------------------------------------------------------------
;; def
;; ---------------------------------------------------------------------------

(deftest roundtrip-def
  (let [[f1 f2 _] (roundtrip-forms "def(x 42)")]
    (is (= f1 f2))))

(deftest roundtrip-def-expr
  (let [[f1 f2 _] (roundtrip-forms "def(x +(1 2))")]
    (is (= f1 f2))))

;; ---------------------------------------------------------------------------
;; defn
;; ---------------------------------------------------------------------------

(deftest roundtrip-defn
  (let [[f1 f2 _] (roundtrip-forms "defn(greet [name] println(str(\"Hello \" name)))")]
    (is (= f1 f2))))

(deftest roundtrip-defn-multi-body
  (let [[f1 f2 _] (roundtrip-forms "defn(greet [name] println(\"hi\") :ok)")]
    (is (= f1 f2))))

;; ---------------------------------------------------------------------------
;; fn
;; ---------------------------------------------------------------------------

(deftest roundtrip-fn
  (let [[f1 f2 _] (roundtrip-forms "fn([x] +(x 1))")]
    (is (= f1 f2))))

(deftest roundtrip-fn-two-args
  (let [[f1 f2 _] (roundtrip-forms "fn([x y] *(x y))")]
    (is (= f1 f2))))

(deftest roundtrip-fn-multi-body
  (let [[f1 f2 _] (roundtrip-forms "fn([acc item] assoc(acc :id(item) :balance(item)))")]
    (is (= f1 f2))))

;; ---------------------------------------------------------------------------
;; let / loop
;; ---------------------------------------------------------------------------

(deftest roundtrip-let
  (let [[f1 f2 _] (roundtrip-forms "let([x 1, y +(x 1)] *(x y))")]
    (is (= f1 f2))))

(deftest roundtrip-loop
  (let [[f1 f2 _] (roundtrip-forms "loop([i 0, acc []] recur(inc(i) conj(acc i)))")]
    (is (= f1 f2))))

;; ---------------------------------------------------------------------------
;; for
;; ---------------------------------------------------------------------------

(deftest roundtrip-for
  (let [[f1 f2 _] (roundtrip-forms "for([x xs, y ys] [x y])")]
    (is (= f1 f2))))

(deftest roundtrip-for-with-when
  (let [[f1 f2 _] (roundtrip-forms "for([x [1 2 3] :when >(x 1)] x)")]
    (is (= f1 f2))))

;; ---------------------------------------------------------------------------
;; doseq
;; ---------------------------------------------------------------------------

(deftest roundtrip-doseq
  (let [[f1 f2 _] (roundtrip-forms "doseq([x xs] println(x))")]
    (is (= f1 f2))))

;; ---------------------------------------------------------------------------
;; if
;; ---------------------------------------------------------------------------

(deftest roundtrip-if-inline
  (let [[f1 f2 _] (roundtrip-forms "if(>(x 0) \"positive\" \"negative\")")]
    (is (= f1 f2))))

(deftest roundtrip-if-single-branch
  (let [[f1 f2 _] (roundtrip-forms "if(>(x 0) \"positive\")")]
    (is (= f1 f2))))

;; ---------------------------------------------------------------------------
;; when
;; ---------------------------------------------------------------------------

(deftest roundtrip-when
  (let [[f1 f2 _] (roundtrip-forms "when(>(x 0) println(\"positive\"))")]
    (is (= f1 f2))))

(deftest roundtrip-when-multi-body
  (let [[f1 f2 _] (roundtrip-forms "when(>(x 0) println(\"positive\") do-something(x))")]
    (is (= f1 f2))))

;; ---------------------------------------------------------------------------
;; do (call form)
;; ---------------------------------------------------------------------------

(deftest roundtrip-do-call
  (let [[f1 f2 _] (roundtrip-forms "do(println(\"a\") println(\"b\"))")]
    (is (= f1 f2))))

;; ---------------------------------------------------------------------------
;; try / catch / finally (call form)
;; ---------------------------------------------------------------------------

(deftest roundtrip-try-catch
  (let [[f1 f2 _] (roundtrip-forms "try(dangerous() catch(Exception e handle(e)))")]
    (is (= f1 f2))))

(deftest roundtrip-try-catch-finally
  (let [[f1 f2 _] (roundtrip-forms "try(dangerous() catch(Exception e handle(e)) finally(cleanup()))")]
    (is (= f1 f2))))

(deftest roundtrip-try-catch-multi-body
  (let [[f1 f2 _] (roundtrip-forms "try(risky() catch(Exception e log(e) recover()))")]
    (is (= f1 f2))))

(deftest roundtrip-try-finally-multi-body
  (let [[f1 f2 _] (roundtrip-forms "try(risky() finally(a() b()))")]
    (is (= f1 f2))))

;; ---------------------------------------------------------------------------
;; Data literals
;; ---------------------------------------------------------------------------

(deftest roundtrip-vector
  (let [[f1 f2 _] (roundtrip-forms "[1 2 3]")]
    (is (= f1 f2))))

(deftest roundtrip-empty-vector
  (let [[f1 f2 _] (roundtrip-forms "[]")]
    (is (= f1 f2))))

(deftest roundtrip-keyword
  (let [[f1 f2 _] (roundtrip-forms ":active")]
    (is (= f1 f2))))

(deftest roundtrip-string
  (let [[f1 f2 _] (roundtrip-forms "\"hello world\"")]
    (is (= f1 f2))))

(deftest roundtrip-number
  (let [[f1 f2 _] (roundtrip-forms "42")]
    (is (= f1 f2))))

(deftest roundtrip-nil
  (let [[f1 f2 _] (roundtrip-forms "nil")]
    (is (= f1 f2))))

(deftest roundtrip-boolean
  (let [[f1 f2 _] (roundtrip-forms "true")]
    (is (= f1 f2))))

(deftest roundtrip-negative-number
  (let [[f1 f2 _] (roundtrip-forms "-1")]
    (is (= f1 f2))))

(deftest roundtrip-char
  (let [[f1 f2 _] (roundtrip-forms "\\a")]
    (is (= f1 f2))))

(deftest roundtrip-named-chars
  (testing "\\newline roundtrips"
    (is (= [\newline] (core/meme->forms (p/print-meme-string [\newline])))))
  (testing "\\space roundtrips"
    (is (= [\space] (core/meme->forms (p/print-meme-string [\space])))))
  (testing "\\tab roundtrips"
    (is (= [\tab] (core/meme->forms (p/print-meme-string [\tab])))))
  (testing "\\return roundtrips"
    (is (= [\return] (core/meme->forms (p/print-meme-string [\return])))))
  (testing "\\backspace roundtrips"
    (is (= [\backspace] (core/meme->forms (p/print-meme-string [\backspace])))))
  (testing "\\formfeed roundtrips"
    (is (= [\formfeed] (core/meme->forms (p/print-meme-string [\formfeed]))))))

(deftest roundtrip-regex
  ;; Regex patterns don't support equality, so compare by pattern string
  (let [[f1 f2 _] (roundtrip-forms "#\"pattern\"")]
    (is (= #?(:clj (.pattern ^java.util.regex.Pattern (first f1))
              :cljs (.-source (first f1)))
           #?(:clj (.pattern ^java.util.regex.Pattern (first f2))
              :cljs (.-source (first f2)))))))

;; ---------------------------------------------------------------------------
;; Threading
;; ---------------------------------------------------------------------------

(deftest roundtrip-threading
  (let [[f1 f2 _] (roundtrip-forms "->>(accounts filter(:active) map(:balance) reduce(+))")]
    (is (= f1 f2))))

(deftest roundtrip-thread-first
  (let [[f1 f2 _] (roundtrip-forms "->(x inc str)")]
    (is (= f1 f2))))

;; ---------------------------------------------------------------------------
;; Reader macros
;; ---------------------------------------------------------------------------

(deftest roundtrip-deref
  (let [[f1 f2 _] (roundtrip-forms "@state")]
    (is (= f1 f2))))

(deftest roundtrip-var-quote
  (let [[f1 f2 _] (roundtrip-forms "#'foo")]
    (is (= f1 f2))))

(deftest roundtrip-quote
  (let [[f1 f2 _] (roundtrip-forms "'foo")]
    (is (= f1 f2))))

(deftest roundtrip-discard
  ;; #_ discards the next form, so #_foo bar() should roundtrip as just bar()
  (let [[f1 f2 _] (roundtrip-forms "#_foo bar()")]
    (is (= f1 f2))))

;; ---------------------------------------------------------------------------
;; Metadata
;; ---------------------------------------------------------------------------

(deftest roundtrip-metadata
  (let [[f1 f2 printed] (roundtrip-forms "^:private def(x 42)")]
    (is (= f1 f2))
    (is (= {:private true} (dissoc (meta (first f1)) :ws :meme/meta-chain)))
    (is (= {:private true} (dissoc (meta (first f2)) :ws :meme/meta-chain)))
    (is (re-find #"\^:private" printed))))

(deftest roundtrip-metadata-dynamic
  (let [[f1 f2 printed] (roundtrip-forms "^:dynamic *x*")]
    (is (= f1 f2))
    (is (= {:dynamic true} (dissoc (meta (first f1)) :ws :meme/meta-chain)))
    (is (= {:dynamic true} (dissoc (meta (first f2)) :ws :meme/meta-chain)))
    (is (re-find #"\^:dynamic" printed))))

(deftest roundtrip-metadata-type-tag
  (let [[f1 f2 printed] (roundtrip-forms "^String x")]
    (is (= f1 f2))
    (is (= {:tag 'String} (dissoc (meta (first f1)) :ws :meme/meta-chain)))
    (is (= {:tag 'String} (dissoc (meta (first f2)) :ws :meme/meta-chain)))
    (is (re-find #"\^String" printed))))

(deftest roundtrip-metadata-map
  (let [[f1 f2 printed] (roundtrip-forms "^{:doc \"hello\"} x")]
    (is (= f1 f2))
    (is (= "hello" (:doc (meta (first f1)))))
    (is (= "hello" (:doc (meta (first f2)))))
    (is (re-find #"\^\{" printed))))

;; ---------------------------------------------------------------------------
;; Interop
;; ---------------------------------------------------------------------------

(deftest roundtrip-method-call
  (let [[f1 f2 _] (roundtrip-forms ".toUpperCase(\"hello\")")]
    (is (= f1 f2))))

(deftest roundtrip-static-method
  (let [[f1 f2 _] (roundtrip-forms "Math/abs(-1)")]
    (is (= f1 f2))))

(deftest roundtrip-field-access
  (let [[f1 f2 _] (roundtrip-forms ".-x(point)")]
    (is (= f1 f2))))

(deftest roundtrip-constructor
  (let [[f1 f2 _] (roundtrip-forms "java.util.Date.()")]
    (is (= f1 f2))))

(deftest roundtrip-constructor-with-args
  (let [[f1 f2 _] (roundtrip-forms "StringBuilder.(\"init\")")]
    (is (= f1 f2))))

(deftest roundtrip-zero-arg-static-method
  (let [[f1 f2 _] (roundtrip-forms "System/currentTimeMillis()")]
    (is (= f1 f2))))

(deftest roundtrip-chained-interop
  (let [[f1 f2 _] (roundtrip-forms ".toUpperCase(.trim(\" hello \"))")]
    (is (= f1 f2))))

(deftest roundtrip-interop-with-keyword-access
  (let [[f1 f2 _] (roundtrip-forms ":name(.getData(obj))")]
    (is (= f1 f2))))

(deftest roundtrip-multi-arg-method
  (let [[f1 f2 _] (roundtrip-forms ".replace(\"hello\" \"l\" \"r\")")]
    (is (= f1 f2))))

;; ---------------------------------------------------------------------------
;; Concurrency
;; ---------------------------------------------------------------------------

(deftest roundtrip-swap
  (let [[f1 f2 _] (roundtrip-forms "swap!(state update(:count inc))")]
    (is (= f1 f2))))

;; ---------------------------------------------------------------------------
;; Complex forms
;; ---------------------------------------------------------------------------

(deftest roundtrip-cond
  (let [[f1 f2 _] (roundtrip-forms "cond(\n  >(x 0) \"positive\"\n  ==(x 0) \"zero\"\n  :else \"negative\")")]
    (is (= f1 f2))))

(deftest roundtrip-ns
  (let [[f1 f2 _] (roundtrip-forms "ns(my.app :require([clojure.string :as str]))")]
    (is (= f1 f2))))

(deftest roundtrip-ns-with-docstring
  (let [[f1 f2 _] (roundtrip-forms "ns(my.app \"App namespace.\" :require([clojure.string :as str]))")]
    (is (= f1 f2))))

(deftest roundtrip-defmulti
  (let [[f1 f2 _] (roundtrip-forms "defmulti(area :shape)")]
    (is (= f1 f2))))

(deftest roundtrip-defmethod
  (let [[f1 f2 _] (roundtrip-forms "defmethod(area :circle [{:keys [radius]}] *(Math/PI radius radius))")]
    (is (= f1 f2))))

(deftest roundtrip-defmethod-multi-body
  (let [[f1 f2 _] (roundtrip-forms "defmethod(area :circle [{:keys [radius]}] println(radius) *(Math/PI radius radius))")]
    (is (= f1 f2))))

(deftest roundtrip-defprotocol
  (let [[f1 f2 _] (roundtrip-forms "defprotocol(Drawable draw([this canvas]) bounds([this]))")]
    (is (= f1 f2))))

(deftest roundtrip-defrecord
  (let [[f1 f2 _] (roundtrip-forms "defrecord(Circle [center radius])")]
    (is (= f1 f2))))

(deftest roundtrip-defrecord-with-impls
  (let [[f1 f2 _] (roundtrip-forms "defrecord(Circle [center radius] Shape area([this] *(Math/PI :radius(this) :radius(this))))")]
    (is (= f1 f2))))

(deftest roundtrip-defn-private
  (let [[f1 f2 _] (roundtrip-forms "defn-(helper [x] +(x 1))")]
    (is (= f1 f2))))

;; ---------------------------------------------------------------------------
;; Named fn
;; ---------------------------------------------------------------------------

(deftest roundtrip-named-fn
  (let [[f1 f2 _] (roundtrip-forms "fn(add [x] +(x 1))")]
    (is (= f1 f2))))

;; ---------------------------------------------------------------------------
;; Multi-arity defn
;; ---------------------------------------------------------------------------

(deftest roundtrip-defn-multi-arity
  (let [[f1 f2 _] (roundtrip-forms "defn(foo [x](x) [x y](+(x y)))")]
    (is (= f1 f2))))

;; ---------------------------------------------------------------------------
;; defmacro
;; ---------------------------------------------------------------------------

(deftest roundtrip-defmacro
  (let [[f1 f2 _] (roundtrip-forms "defmacro(my-if [test then else] list('if test then else))")]
    (is (= f1 f2))))

;; ---------------------------------------------------------------------------
;; Deeply nested structures
;; ---------------------------------------------------------------------------

(deftest roundtrip-deeply-nested-let
  (let [[f1 f2 _] (roundtrip-forms
                     "let([a 1] let([b 2] let([c 3] let([d 4] +(a b c d)))))")]
    (is (= f1 f2))))

(deftest roundtrip-nested-fn-in-fn
  (let [[f1 f2 _] (roundtrip-forms
                     "defn(outer [x] let([f fn([y] +(x y))] f(10)))")]
    (is (= f1 f2))))

(deftest roundtrip-nested-loop-for
  (let [[f1 f2 _] (roundtrip-forms
                     "for([x [1 2 3]] for([y [4 5 6]] *(x y)))")]
    (is (= f1 f2))))

(deftest roundtrip-mixed-control-flow
  (let [[f1 f2 _] (roundtrip-forms
                     "defn(process [items] let([result for([item items] if(even?(item) *(item 2) item))] reduce(+ 0 result)))")]
    (is (= f1 f2))))

(deftest roundtrip-anon-fn-shorthand
  (testing "#(inc(%)) survives read → print → re-read"
    (let [[f1 f2 _] (roundtrip-forms "#(inc(%))")]
      (is (= f1 f2))))
  (testing "#() nested in call"
    (let [[f1 f2 _] (roundtrip-forms "map(#(inc(%)) [1 2 3])")]
      (is (= f1 f2))))
  (testing "#() zero params"
    (let [[f1 f2 _] (roundtrip-forms "#(rand())")]
      (is (= f1 f2))))
  (testing "#() with %& rest param roundtrips through fn form"
    (let [[f1 f2 _] (roundtrip-forms "#(apply(str %&))")]
      (is (= f1 f2)))))

(deftest roundtrip-string-escape-sequences
  (testing "string with escaped quote"
    (let [[f1 f2 _] (roundtrip-forms "\"foo\\\"bar\"")]
      (is (= f1 f2))))
  (testing "string with newline escape"
    (let [[f1 f2 _] (roundtrip-forms "\"a\\nb\"")]
      (is (= f1 f2))))
  (testing "string with tab and backslash"
    (let [[f1 f2 _] (roundtrip-forms "\"a\\tb\\\\c\"")]
      (is (= f1 f2))))
  (testing "string with carriage return escape"
    (let [[f1 f2 _] (roundtrip-forms "\"a\\rb\"")]
      (is (= f1 f2))))
  (testing "string with backspace escape"
    (let [[f1 f2 _] (roundtrip-forms "\"a\\bb\"")]
      (is (= f1 f2))))
  (testing "string with formfeed escape"
    (let [[f1 f2 _] (roundtrip-forms "\"a\\fb\"")]
      (is (= f1 f2))))
  (testing "string with unicode escape"
    (let [[f1 f2 _] (roundtrip-forms "\"\\u0041\"")]
      (is (= f1 f2))
      (is (= "A" (:value (first f1)))))))

(deftest roundtrip-deftype
  (let [[f1 f2 _] (roundtrip-forms "deftype(Point [x y])")]
    (is (= f1 f2))))

(deftest roundtrip-reify
  ;; Correct meme syntax: method name is a call head, not a bare paren.
  ;; "reify(Object (toString ...))" would parse wrong: Object eats (toString...) as a call.
  (let [[f1 f2 _] (roundtrip-forms "reify(Object toString([this] \"hello\"))")]
    (is (= f1 f2))
    (is (= '(reify Object (toString [this] "hello")) (first f1)))))

;; ---------------------------------------------------------------------------
;; Number format roundtrips — host reader normalizes some formats
;; ---------------------------------------------------------------------------

#?(:clj
(deftest roundtrip-number-formats
  (testing "ratio"
    (let [[f1 f2 _] (roundtrip-forms "1/2")]
      (is (= f1 f2))))
  (testing "BigInt"
    (let [[f1 f2 _] (roundtrip-forms "42N")]
      (is (= f1 f2))))
  (testing "BigDecimal"
    (let [[f1 f2 printed] (roundtrip-forms "1.5M")]
      (is (= f1 f2))
      (is (= "1.5M" printed))))))

;; ---------------------------------------------------------------------------
;; Deep nesting stress tests
;; ---------------------------------------------------------------------------

(deftest roundtrip-deep-vector-nesting
  (testing "100-level vector nesting roundtrips"
    (let [input (str (apply str (repeat 100 "[")) "x" (apply str (repeat 100 "]")))
          [f1 f2 _] (roundtrip-forms input)]
      (is (= f1 f2)))))

(deftest roundtrip-deep-call-nesting
  (testing "50-level call nesting roundtrips"
    (let [input (str (apply str (repeat 50 "f(")) "x" (apply str (repeat 50 ")")))
          [f1 f2 _] (roundtrip-forms input)]
      (is (= f1 f2)))))

;; ---------------------------------------------------------------------------
;; Unicode
;; ---------------------------------------------------------------------------

(deftest roundtrip-unicode-strings
  (testing "Unicode string roundtrips"
    (let [[f1 f2 _] (roundtrip-forms "println(\"Hello, \u4e16\u754c\")")]
      (is (= f1 f2))))
  (testing "Emoji in string roundtrips"
    (let [[f1 f2 _] (roundtrip-forms "str(\"test \ud83d\ude00\")")]
      (is (= f1 f2)))))

(deftest roundtrip-unicode-symbols
  (testing "Greek letters as call"
    (let [[f1 f2 _] (roundtrip-forms "\u03b1(\u03b2 \u03b3)")]
      (is (= f1 f2))))
  (testing "CJK characters as symbol"
    (let [[f1 f2 _] (roundtrip-forms "\u6570\u636e")]
      (is (= f1 f2))))
  (testing "CJK characters as call head"
    (let [[f1 f2 _] (roundtrip-forms "\u6570\u636e(x)")]
      (is (= f1 f2))))
  (testing "Arabic characters as symbol"
    (let [[f1 f2 _] (roundtrip-forms "\u0628\u064a\u0627\u0646\u0627\u062a")]
      (is (= f1 f2))))
  (testing "Emoji as symbol"
    (let [[f1 f2 _] (roundtrip-forms "\uD83C\uDF89")]
      (is (= f1 f2))))
  (testing "Emoji as call head"
    (let [[f1 f2 _] (roundtrip-forms "\uD83C\uDF89(x)")]
      (is (= f1 f2)))))

;; ---------------------------------------------------------------------------
;; case, fn multi-arity
;; ---------------------------------------------------------------------------

(deftest roundtrip-case
  (testing "case with dispatch values roundtrips"
    (let [[f1 f2 _] (roundtrip-forms "case(x 1 \"one\" 2 \"two\" \"default\")")]
      (is (= f1 f2)))))

(deftest roundtrip-fn-multi-arity
  (testing "fn with multiple arities roundtrips"
    (let [[f1 f2 _] (roundtrip-forms "fn([x](inc(x)) [x y](+(x y)))")]
      (is (= f1 f2)))))

;; ---------------------------------------------------------------------------
;; Chained calls — list-headed calls: ((f x) y) → f(x)(y)
;; ---------------------------------------------------------------------------

(deftest roundtrip-chained-call
  (testing "((f x) y) roundtrips via f(x)(y)"
    (let [[f1 f2 text] (roundtrip-forms "f(x)(y)")]
      (is (= f1 f2))
      (is (= "f(x)(y)" text))))
  (testing "triple chain (((f x) y) z) roundtrips"
    (let [[f1 f2 text] (roundtrip-forms "f(x)(y)(z)")]
      (is (= f1 f2))
      (is (= "f(x)(y)(z)" text))))
  (testing "((comp inc dec) 5) roundtrips"
    (let [[f1 f2 _] (roundtrip-forms "comp(inc dec)(5)")]
      (is (= f1 f2)))))

;; ---------------------------------------------------------------------------
;; Opaque forms (JVM only)
;; ---------------------------------------------------------------------------

#?(:clj
(deftest roundtrip-reader-conditional
  (testing "#?(:clj x :cljs y) roundtrips"
    (let [[f1 f2 _] (roundtrip-forms "#?(:clj 1 :cljs 2)")]
      (is (= f1 f2))))
  (testing "#?@(:clj [1 2]) roundtrips"
    (let [[f1 f2 _] (roundtrip-forms "#?@(:clj [1 2] :cljs [3 4])")]
      (is (= f1 f2))))))

#?(:clj
(deftest roundtrip-tagged-literal
  (testing "#uuid roundtrips"
    (let [[f1 f2 _] (roundtrip-forms "#uuid \"550e8400-e29b-41d4-a716-446655440000\"")]
      (is (= f1 f2))))
  (testing "#inst roundtrips"
    (let [[f1 f2 _] (roundtrip-forms "#inst \"2024-01-01T00:00:00.000Z\"")]
      (is (= f1 f2))))))

#?(:clj
(deftest roundtrip-auto-resolve-keyword
  (testing "::foo roundtrips through deferred read-string"
    (let [[f1 f2 text] (roundtrip-forms "::local")]
      (is (= f1 f2))
      (is (= "::local" text))))
  (testing "::ns/foo roundtrips"
    (let [[f1 f2 text] (roundtrip-forms "::foo/bar")]
      (is (= f1 f2))
      (is (= "::foo/bar" text))))))

;; ---------------------------------------------------------------------------
;; Metadata on collections
;; ---------------------------------------------------------------------------

(deftest roundtrip-metadata-on-vector
  (testing "^:tag [1 2 3] roundtrips"
    (let [[f1 f2 printed] (roundtrip-forms "^:tag [1 2 3]")]
      (is (= f1 f2))
      (is (= {:tag true} (dissoc (meta (first f1)) :ws :meme/meta-chain)))
      (is (re-find #"\^:tag" printed)))))

(deftest roundtrip-metadata-on-map
  (testing "^:private {:a 1} roundtrips"
    (let [[f1 f2 _] (roundtrip-forms "^:private {:a 1}")]
      (is (= f1 f2))
      (is (= {:private true} (dissoc (meta (first f1)) :ws :meme/meta-chain))))))

;; ---------------------------------------------------------------------------
;; Multi-arity named fn
;; ---------------------------------------------------------------------------

(deftest roundtrip-fn-named-multi-arity
  (testing "fn(name [x](inc(x)) [x y](+(x y))) roundtrips"
    (let [[f1 f2 _] (roundtrip-forms "fn(add [x](inc(x)) [x y](+(x y)))")]
      (is (= f1 f2)))))

;; ---------------------------------------------------------------------------
;; letfn, condp, deftype with implementations
;; ---------------------------------------------------------------------------

(deftest roundtrip-letfn
  (let [[f1 f2 _] (roundtrip-forms "letfn([double([x] *(x 2))] double(5))")]
    (is (= f1 f2))))

(deftest roundtrip-letfn-mutual
  (let [[f1 f2 _] (roundtrip-forms "letfn([even?([n] if(zero?(n) true odd?(dec(n)))) odd?([n] if(zero?(n) false even?(dec(n))))] even?(4))")]
    (is (= f1 f2))))

(deftest roundtrip-condp
  (let [[f1 f2 _] (roundtrip-forms "condp(= x 1 \"one\" 2 \"two\" \"default\")")]
    (is (= f1 f2))))

(deftest roundtrip-condp-custom-pred
  (let [[f1 f2 _] (roundtrip-forms "condp(contains? #{1 2 3} #{1} \"has one\" \"nope\")")]
    (is (= f1 f2))))

(deftest roundtrip-deftype-with-impls
  (let [[f1 f2 _] (roundtrip-forms "deftype(Point [x y] Object toString([this] str(\"(\" .-x(this) \",\" .-y(this) \")\")))")
        ;; deftype forms may contain generated class names, compare structure
        form1 (first f1)]
    (is (= 'deftype (first form1)))
    (is (= 'Point (second form1)))
    (is (= f1 f2))))

(deftest roundtrip-chained-metadata
  (testing "^:private ^:dynamic x roundtrips with merged metadata"
    (let [[f1 f2 _] (roundtrip-forms "^:private ^:dynamic x")]
      (is (= f1 f2))
      (is (true? (:private (meta (first f1)))))
      (is (true? (:dynamic (meta (first f1)))))))
  (testing "^:private ^:dynamic ^String x — triple chain"
    (let [[f1 f2 _] (roundtrip-forms "^:private ^:dynamic ^String x")]
      (is (= f1 f2))
      (is (true? (:private (meta (first f1)))))
      (is (true? (:dynamic (meta (first f1)))))
      (is (= 'String (:tag (meta (first f1))))))))

(deftest roundtrip-some-threading
  (testing "some-> roundtrips"
    (let [[f1 f2 _] (roundtrip-forms "some->(m :a :b)")]
      (is (= f1 f2))))
  (testing "some->> roundtrips"
    (let [[f1 f2 _] (roundtrip-forms "some->>(x str inc)")]
      (is (= f1 f2)))))

;; ---------------------------------------------------------------------------
;; :read-cond :preserve roundtrip
;; ---------------------------------------------------------------------------

(defn- roundtrip-preserve
  "Roundtrip with :read-cond :preserve — parse, print, re-parse."
  [meme-src]
  (let [forms1 (core/meme->forms meme-src {:read-cond :preserve})
        meme-text (p/print-meme-string forms1)
        forms2 (core/meme->forms meme-text {:read-cond :preserve})]
    [forms1 forms2 meme-text]))

(deftest roundtrip-reader-conditional-preserve
  (testing "#? with two branches"
    (let [[f1 f2 _] (roundtrip-preserve "#?(:clj 1 :cljs 2)")]
      (is (= f1 f2))
      (is (forms/meme-reader-conditional? (first f1)))))
  (testing "#? with :default"
    (let [[f1 f2 _] (roundtrip-preserve "#?(:clj 1 :default 0)")]
      (is (= f1 f2))))
  (testing "#? with call syntax inside branches"
    (let [[f1 f2 _] (roundtrip-preserve "#?(:clj inc(1) :cljs dec(2))")]
      (is (= f1 f2))))
  (testing "#?@ splicing"
    (let [[f1 f2 _] (roundtrip-preserve "#?@(:clj [1 2] :cljs [3 4])")]
      (is (= f1 f2))
      (is (true? (forms/rc-splicing? (first f1))))))
  (testing "#? nested"
    (let [[f1 f2 _] (roundtrip-preserve "#?(:clj #?(:clj 1 :cljs 2) :cljs 3)")]
      (is (= f1 f2))))
  (testing "#? inside a call"
    (let [[f1 f2 _] (roundtrip-preserve "let([x #?(:clj 1 :cljs 2)] x)")]
      (is (= f1 f2)))))

;; ---------------------------------------------------------------------------
;; Sugar syntax preservation
;; meme is a syntactic lens — the printer must preserve the user's syntax
;; choice between sugar ('x, @x, #'x) and explicit call (quote(x), etc.).
;; The reader tags sugar forms with :meme/sugar metadata so the printer
;; can reconstruct the original notation.
;; ---------------------------------------------------------------------------

(defn- roundtrip-syntax
  "Parse meme string, print back, and verify the printed text matches the input.
   Tests syntactic transparency — the lens should not alter the user's notation."
  [meme-src]
  (let [forms (core/meme->forms meme-src)
        printed (core/forms->meme forms)]
    printed))

(deftest roundtrip-quote-sugar-preserved
  (testing "'x sugar roundtrips as 'x, not quote(x)"
    (is (= "'foo" (roundtrip-syntax "'foo"))))
  (testing "'f(x) sugar roundtrips as 'f(x)"
    (is (= "'f(x)" (roundtrip-syntax "'f(x)"))))
  (testing "'(1 2 3) with number head roundtrips as sugar"
    (is (= "'1(2 3)" (roundtrip-syntax "'1(2 3)"))))
  (testing "quote(x) explicit call roundtrips as quote(x), not 'x"
    (is (= "quote(foo)" (roundtrip-syntax "quote(foo)"))))
  (testing "quote(f(x)) explicit call preserves call form"
    (is (= "quote(f(x))" (roundtrip-syntax "quote(f(x))")))))

(deftest roundtrip-deref-sugar-preserved
  (testing "@x sugar roundtrips as @x"
    (is (= "@foo" (roundtrip-syntax "@foo"))))
  (testing "@f(x) sugar roundtrips as @f(x)"
    (is (= "@f(x)" (roundtrip-syntax "@f(x)"))))
  (testing "clojure.core/deref(x) explicit call roundtrips as call"
    (is (= "clojure.core/deref(foo)" (roundtrip-syntax "clojure.core/deref(foo)")))))

(deftest roundtrip-var-sugar-preserved
  (testing "#'x sugar roundtrips as #'x"
    (is (= "#'foo" (roundtrip-syntax "#'foo"))))
  (testing "var(x) explicit call roundtrips as var(x)"
    (is (= "var(foo)" (roundtrip-syntax "var(foo)")))))

(deftest roundtrip-anon-fn-sugar-preserved
  (testing "#() sugar roundtrips as #()"
    (is (= "#(inc(%1))" (roundtrip-syntax "#(inc(%))"))))
  (testing "fn() explicit call roundtrips as fn()"
    (is (= "fn([%1] inc(%1))" (roundtrip-syntax "fn([%1] inc(%1))")))))

(deftest roundtrip-set-ordering-preserved
  (testing "set element order roundtrips"
    (is (= "#{3 1 2}" (roundtrip-syntax "#{3 1 2}"))))
  (testing "single-element set"
    (is (= "#{1}" (roundtrip-syntax "#{1}")))))

(deftest roundtrip-namespaced-map-preserved
  (testing "#:ns{} roundtrips"
    (is (= "#:user{:a 1 :b 2}" (roundtrip-syntax "#:user{:a 1 :b 2}")))))

(deftest roundtrip-metadata-chain-preserved
  (testing "^:a ^:b x preserves chain order"
    (is (= "^:a ^:b foo" (roundtrip-syntax "^:a ^:b foo"))))
  (testing "^:private ^String x preserves chain"
    (is (= "^:private ^String x" (roundtrip-syntax "^:private ^String x")))))

(deftest roundtrip-numeric-notation-preserved
  #?(:clj
  (testing "hex notation roundtrips"
    (is (= "0xFF" (roundtrip-syntax "0xFF")))))
  #?(:clj
  (testing "octal notation roundtrips"
    (is (= "010" (roundtrip-syntax "010")))))
  #?(:clj
  (testing "radix notation roundtrips"
    (is (= "2r1010" (roundtrip-syntax "2r1010")))))
  (testing "scientific notation roundtrips"
    (is (= "1e2" (roundtrip-syntax "1e2"))))
  (testing "standard notation unchanged"
    (is (= "42" (roundtrip-syntax "42")))
    (is (= "3.14" (roundtrip-syntax "3.14")))))

#?(:clj
(deftest roundtrip-char-escape-preserved
  (testing "unicode char escape roundtrips"
    (is (= "\\u0041" (roundtrip-syntax "\\u0041"))))
  (testing "octal char escape roundtrips"
    (is (= "\\o101" (roundtrip-syntax "\\o101"))))
  (testing "standard chars unchanged"
    (is (= "\\a" (roundtrip-syntax "\\a")))
    (is (= "\\newline" (roundtrip-syntax "\\newline"))))))

(deftest roundtrip-string-unicode-preserved
  (testing "string with unicode escape roundtrips"
    (is (= "\"hello \\u0041\"" (roundtrip-syntax "\"hello \\u0041\""))))
  (testing "standard string escapes unchanged"
    (is (= "\"hello\\nworld\"" (roundtrip-syntax "\"hello\\nworld\"")))))

(deftest roundtrip-syntax-quote-preserved
  (testing "backtick on symbol roundtrips"
    (is (= "`foo" (roundtrip-syntax "`foo"))))
  (testing "backtick on call roundtrips"
    (is (= "`if(~test ~body)" (roundtrip-syntax "`if(~test ~body)"))))
  (testing "backtick with unquote-splicing roundtrips"
    (is (= "`do(~@body)" (roundtrip-syntax "`do(~@body)"))))
  (testing "nested syntax-quote roundtrips"
    (is (= "`list('a `b)" (roundtrip-syntax "`list('a `b)"))))
  (testing "backtick on vector roundtrips"
    (is (= "`[~a ~b]" (roundtrip-syntax "`[~a ~b]")))))

(deftest roundtrip-sugar-in-clj-output
  (testing "meme 'x → clj 'x"
    (is (= "'foo" (core/meme->clj "'foo"))))
  (testing "meme quote(x) → clj (quote x)"
    (is (= "(quote foo)" (core/meme->clj "quote(foo)"))))
  (testing "meme @x → clj @x"
    (is (= "@foo" (core/meme->clj "@foo"))))
  (testing "meme clojure.core/deref(x) → clj (clojure.core/deref x)"
    (is (= "(clojure.core/deref foo)" (core/meme->clj "clojure.core/deref(foo)"))))
  (testing "meme #'x → clj #'x"
    (is (= "#'foo" (core/meme->clj "#'foo"))))
  (testing "meme var(x) → clj (var x)"
    (is (= "(var foo)" (core/meme->clj "var(foo)")))))
