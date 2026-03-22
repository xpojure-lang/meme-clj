(ns beme.alpha.roundtrip-test
  (:require [clojure.test :refer [deftest is testing]]
            [beme.alpha.parse.reader :as r]
            [beme.alpha.emit.printer :as p]))

(defn- roundtrip-forms
  "Parse mm string, get forms. Then print forms back to mm and re-parse.
   The re-parsed forms should equal the original forms."
  [mm-src]
  (let [forms1 (r/read-beme-string mm-src)
        mm-text (p/print-beme-string forms1)
        forms2 (r/read-beme-string mm-text)]
    [forms1 forms2 mm-text]))

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
    (is (= [\newline] (r/read-beme-string (p/print-beme-string [\newline])))))
  (testing "\\space roundtrips"
    (is (= [\space] (r/read-beme-string (p/print-beme-string [\space])))))
  (testing "\\tab roundtrips"
    (is (= [\tab] (r/read-beme-string (p/print-beme-string [\tab])))))
  (testing "\\return roundtrips"
    (is (= [\return] (r/read-beme-string (p/print-beme-string [\return])))))
  (testing "\\backspace roundtrips"
    (is (= [\backspace] (r/read-beme-string (p/print-beme-string [\backspace])))))
  (testing "\\formfeed roundtrips"
    (is (= [\formfeed] (r/read-beme-string (p/print-beme-string [\formfeed]))))))

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
    (is (= {:private true} (dissoc (meta (first f1)) :ws)))
    (is (= {:private true} (dissoc (meta (first f2)) :ws)))
    (is (re-find #"\^:private" printed))))

(deftest roundtrip-metadata-dynamic
  (let [[f1 f2 printed] (roundtrip-forms "^:dynamic *x*")]
    (is (= f1 f2))
    (is (= {:dynamic true} (dissoc (meta (first f1)) :ws)))
    (is (= {:dynamic true} (dissoc (meta (first f2)) :ws)))
    (is (re-find #"\^:dynamic" printed))))

(deftest roundtrip-metadata-type-tag
  (let [[f1 f2 printed] (roundtrip-forms "^String x")]
    (is (= f1 f2))
    (is (= {:tag 'String} (dissoc (meta (first f1)) :ws)))
    (is (= {:tag 'String} (dissoc (meta (first f2)) :ws)))
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
  (let [[f1 f2 _] (roundtrip-forms "ns(my.app (:require [clojure.string :as str]))")]
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
      (is (= f1 f2)))))

(deftest roundtrip-deftype
  (let [[f1 f2 _] (roundtrip-forms "deftype(Point [x y])")]
    (is (= f1 f2))))

(deftest roundtrip-reify
  ;; Correct beme syntax: method name is a call head, not a bare paren.
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
    (let [[f1 f2 mm] (roundtrip-forms "1.5M")]
      (is (= f1 f2))
      (is (= "1.5M" mm))))))

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
  (testing "Unicode symbol roundtrips"
    (let [[f1 f2 _] (roundtrip-forms "\u03b1(\u03b2 \u03b3)")]
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
;; begin/end delimiters
;; ---------------------------------------------------------------------------

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
      (is (= {:tag true} (dissoc (meta (first f1)) :ws)))
      (is (re-find #"\^:tag" printed)))))

(deftest roundtrip-metadata-on-map
  (testing "^:private {:a 1} roundtrips"
    (let [[f1 f2 _] (roundtrip-forms "^:private {:a 1}")]
      (is (= f1 f2))
      (is (= {:private true} (dissoc (meta (first f1)) :ws))))))

;; ---------------------------------------------------------------------------
;; Multi-arity named fn
;; ---------------------------------------------------------------------------

(deftest roundtrip-fn-named-multi-arity
  (testing "fn(name [x](inc(x)) [x y](+(x y))) roundtrips"
    (let [[f1 f2 _] (roundtrip-forms "fn(add [x](inc(x)) [x y](+(x y)))")]
      (is (= f1 f2)))))

;; ---------------------------------------------------------------------------
;; begin/end delimiters
;; ---------------------------------------------------------------------------

(deftest roundtrip-begin-end-basic
  (testing "begin/end parses to same forms as parens, roundtrips through printer"
    (let [[f1 f2 text] (roundtrip-forms "foo begin x y end")]
      (is (= f1 f2))
      (is (= "foo(x y)" text)))))

(deftest roundtrip-begin-end-nested
  (testing "nested begin/end roundtrips"
    (let [[f1 f2 _] (roundtrip-forms "foo begin bar begin x end end")]
      (is (= f1 f2)))))

(deftest roundtrip-begin-end-mixed
  (testing "begin/end mixed with parens roundtrips"
    (let [[f1 f2 _] (roundtrip-forms "foo begin bar(x) baz(y z) end")]
      (is (= f1 f2)))))

(deftest roundtrip-begin-end-defn
  (testing "real-world defn with begin/end roundtrips"
    (let [[f1 f2 _] (roundtrip-forms "defn begin greet [name] str(\"Hello \" name) end")]
      (is (= f1 f2)))))
