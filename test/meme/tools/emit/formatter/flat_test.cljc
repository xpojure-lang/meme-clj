(ns meme.tools.emit.formatter.flat-test
  (:require [clojure.test :refer [deftest is testing]]
            [meme.tools.emit.formatter.flat :as fmt-flat]))

;; ---------------------------------------------------------------------------
;; Call printing — everything is f(args...)
;; ---------------------------------------------------------------------------

(deftest print-simple-call
  (is (= "println(\"hello\")"
         (fmt-flat/format-form '(println "hello")))))

(deftest print-operator-call
  (is (= "+(1 2 3)"
         (fmt-flat/format-form '(+ 1 2 3)))))

(deftest print-nested-call
  (is (= "str(\"hi \" to-str(x))"
         (fmt-flat/format-form '(str "hi " (to-str x))))))

(deftest print-zero-arg-call
  (is (= "foo()"
         (fmt-flat/format-form '(foo)))))

(deftest print-keyword-as-arg
  (is (= "filter(:active accounts)"
         (fmt-flat/format-form '(filter :active accounts)))))

;; ---------------------------------------------------------------------------
;; Keyword-headed calls
;; ---------------------------------------------------------------------------

(deftest print-keyword-access
  (is (= ":balance(account)"
         (fmt-flat/format-form '(:balance account)))))

(deftest print-keyword-nested-access
  (is (= ":city(:address(account))"
         (fmt-flat/format-form '(:city (:address account))))))

(deftest print-keyword-on-call-result
  (is (= ":name(get-user(id))"
         (fmt-flat/format-form '(:name (get-user id))))))

;; ---------------------------------------------------------------------------
;; Special-form-like constructs — all generic call syntax
;; ---------------------------------------------------------------------------

(deftest print-def
  (is (= "def(x 42)"
         (fmt-flat/format-form '(def x 42)))))

(deftest print-def-with-expr
  (is (= "def(x +(1 2))"
         (fmt-flat/format-form '(def x (+ 1 2))))))

(deftest print-defn
  (is (= "defn(greet [name] str(\"Hello\" name))"
         (fmt-flat/format-form '(defn greet [name] (str "Hello" name))))))

(deftest print-defn-with-docstring
  (is (= "defn(greet \"Greets a person\" [name] println(name))"
         (fmt-flat/format-form '(defn greet "Greets a person" [name] (println name))))))

(deftest print-defn-multi-arity
  (is (= "defn(greet [name](greet(name \"!\")) [name punct](println(name punct)))"
         (fmt-flat/format-form '(defn greet ([name] (greet name "!")) ([name punct] (println name punct)))))))

(deftest print-fn-simple
  (is (= "fn([x] +(x 1))"
         (fmt-flat/format-form '(fn [x] (+ x 1))))))

(deftest print-fn-two-args
  (is (= "fn([x y] *(x y))"
         (fmt-flat/format-form '(fn [x y] (* x y))))))

(deftest print-let
  (is (= "let([x 1] +(x 2))"
         (fmt-flat/format-form '(let [x 1] (+ x 2))))))

(deftest print-if-inline
  (is (= "if(>(x 0) \"pos\" \"neg\")"
         (fmt-flat/format-form '(if (> x 0) "pos" "neg")))))

(deftest print-if-single-branch
  (is (= "if(>(x 0) \"pos\")"
         (fmt-flat/format-form '(if (> x 0) "pos")))))

(deftest print-when-simple
  (is (= "when(>(x 0) println(\"pos\"))"
         (fmt-flat/format-form '(when (> x 0) (println "pos"))))))

(deftest print-do
  (is (= "do(a() b())"
         (fmt-flat/format-form '(do (a) (b))))))

(deftest print-loop
  (is (= "loop([i 0 acc []] recur(inc(i) conj(acc i)))"
         (fmt-flat/format-form '(loop [i 0 acc []] (recur (inc i) (conj acc i)))))))

(deftest print-for
  (is (= "for([x xs y ys] [x y])"
         (fmt-flat/format-form '(for [x xs y ys] [x y])))))

(deftest print-for-with-when
  (is (= "for([x xs :when >(x 0)] x)"
         (fmt-flat/format-form '(for [x xs :when (> x 0)] x)))))

(deftest print-try-catch
  (is (= "try(risky() catch(Exception e handle(e)))"
         (fmt-flat/format-form '(try (risky) (catch Exception e (handle e)))))))

(deftest print-try-catch-finally
  (is (= "try(risky() catch(Exception e handle(e)) finally(cleanup()))"
         (fmt-flat/format-form '(try (risky) (catch Exception e (handle e)) (finally (cleanup)))))))

(deftest print-ns
  (is (= "ns(foo :require([bar]))"
         (fmt-flat/format-form '(ns foo (:require [bar]))))))

(deftest print-defprotocol
  (is (= "defprotocol(Foo draw([this]))"
         (fmt-flat/format-form '(defprotocol Foo (draw [this]))))))

(deftest print-defmulti
  (is (= "defmulti(area :shape)"
         (fmt-flat/format-form '(defmulti area :shape)))))

(deftest print-defmethod
  (is (= "defmethod(area :circle [{:keys [r]}] *(r r))"
         (fmt-flat/format-form '(defmethod area :circle [{:keys [r]}] (* r r))))))

(deftest print-defrecord
  (is (= "defrecord(Foo [x y])"
         (fmt-flat/format-form '(defrecord Foo [x y])))))

;; ---------------------------------------------------------------------------
;; Threading — generic call syntax
;; ---------------------------------------------------------------------------

(deftest print-thread-first
  (is (= "->(account update(:balance *(1.05)))"
         (fmt-flat/format-form '(-> account (update :balance (* 1.05)))))))

(deftest print-thread-last
  (is (= "->>(accounts filter(:active) map(:balance) reduce(+))"
         (fmt-flat/format-form '(->> accounts (filter :active) (map :balance) (reduce +))))))

;; ---------------------------------------------------------------------------
;; #() shorthand
;; ---------------------------------------------------------------------------

(deftest print-fn-shorthand
  (testing "sugar: #() when :meme/sugar tagged"
    (is (= "#(inc(%1))"
           (fmt-flat/format-form (with-meta '(fn [%1] (inc %1)) {:meme/sugar true})))))
  (testing "call form when not tagged"
    (is (= "fn([%1] inc(%1))"
           (fmt-flat/format-form '(fn [%1] (inc %1)))))))

(deftest print-fn-shorthand-zero-params
  (testing "sugar: #() when :meme/sugar tagged"
    (is (= "#(rand())"
           (fmt-flat/format-form (with-meta '(fn [] (rand)) {:meme/sugar true})))))
  (testing "call form when not tagged"
    (is (= "fn([] rand())"
           (fmt-flat/format-form '(fn [] (rand)))))))

;; ---------------------------------------------------------------------------
;; Metadata
;; ---------------------------------------------------------------------------

(deftest print-metadata-keyword
  (is (= "^:private x" (fmt-flat/format-form (with-meta 'x {:private true})))))

(deftest print-metadata-type-tag
  (is (= "^String x" (fmt-flat/format-form (with-meta 'x {:tag 'String})))))

(deftest print-metadata-map
  (is (= "^{:doc \"hello\"} x" (fmt-flat/format-form (with-meta 'x {:doc "hello"})))))

(deftest print-metadata-dynamic
  (is (= "^:dynamic *x*" (fmt-flat/format-form (with-meta '*x* {:dynamic true})))))

;; ---------------------------------------------------------------------------
;; Reader macros: @deref, 'quote, #'var
;; ---------------------------------------------------------------------------

(deftest print-deref
  (testing "sugar: @x when :meme/sugar tagged"
    (is (= "@state" (fmt-flat/format-form (with-meta '(clojure.core/deref state) {:meme/sugar true})))))
  (testing "call form when not tagged"
    (is (= "clojure.core/deref(state)" (fmt-flat/format-form '(clojure.core/deref state))))))

(deftest print-var-quote
  (testing "sugar: #'x when :meme/sugar tagged"
    (is (= "#'foo" (fmt-flat/format-form (with-meta '(var foo) {:meme/sugar true})))))
  (testing "call form when not tagged"
    (is (= "var(foo)" (fmt-flat/format-form '(var foo))))))

(deftest print-quote
  (testing "sugar: 'x when :meme/sugar tagged"
    (is (= "'foo" (fmt-flat/format-form (with-meta '(quote foo) {:meme/sugar true})))))
  (testing "call form when not tagged"
    (is (= "quote(foo)" (fmt-flat/format-form '(quote foo))))))

;; ---------------------------------------------------------------------------
;; Primitives
;; ---------------------------------------------------------------------------

(deftest print-nil
  (is (= "nil" (fmt-flat/format-form nil))))

(deftest print-true
  (is (= "true" (fmt-flat/format-form true))))

(deftest print-false
  (is (= "false" (fmt-flat/format-form false))))

(deftest print-keyword
  (is (= ":active" (fmt-flat/format-form :active))))

(deftest print-namespaced-keyword
  (is (= ":foo/bar" (fmt-flat/format-form :foo/bar))))

(deftest print-symbol
  (is (= "foo" (fmt-flat/format-form 'foo))))

(deftest print-number-int
  (is (= "42" (fmt-flat/format-form 42))))

(deftest print-number-float
  (is (= "3.14" (fmt-flat/format-form 3.14))))

#?(:clj
   (deftest print-number-bigdecimal
     (is (= "1.5M" (fmt-flat/format-form 1.5M)))))

#?(:clj
   (deftest print-number-bigint
     (is (= "42N" (fmt-flat/format-form 42N)))))

(deftest print-string
  (is (= "\"hello\"" (fmt-flat/format-form "hello"))))

#?(:clj
   (deftest print-char
     (is (= "\\a" (fmt-flat/format-form \a)))))

(deftest print-regex
  (is (= "#\"pattern\"" (fmt-flat/format-form #"pattern"))))

;; ---------------------------------------------------------------------------
;; Collections
;; ---------------------------------------------------------------------------

(deftest print-vector
  (is (= "[1 2 3]" (fmt-flat/format-form [1 2 3]))))

(deftest print-empty-vector
  (is (= "[]" (fmt-flat/format-form []))))

(deftest print-map-form
  (is (= "{:name \"Andriy\"}" (fmt-flat/format-form {:name "Andriy"}))))

(deftest print-empty-map
  (is (= "{}" (fmt-flat/format-form {}))))

(deftest print-set
  (let [result (fmt-flat/format-form #{1 2 3})]
    (is (.startsWith result "#{"))))

(deftest print-empty-set
  (is (= "#{}" (fmt-flat/format-form #{}))))

(deftest print-empty-list
  (is (= "()" (fmt-flat/format-form ()))))

;; ---------------------------------------------------------------------------
;; Interop
;; ---------------------------------------------------------------------------

(deftest print-method-call
  (is (= ".toUpperCase(\"hello\")"
         (fmt-flat/format-form '(.toUpperCase "hello")))))

(deftest print-static-method
  (is (= "Math/abs(-1)"
         (fmt-flat/format-form '(Math/abs -1)))))

(deftest print-field-access
  (is (= ".-x(point)"
         (fmt-flat/format-form '(.-x point)))))

(deftest print-static-field
  (is (= "Math/PI" (fmt-flat/format-form 'Math/PI))))

(deftest print-constructor
  (is (= "java.util.Date.()"
         (fmt-flat/format-form '(java.util.Date.)))))

(deftest print-constructor-with-args
  (is (= "StringBuilder.(\"init\")"
         (fmt-flat/format-form '(StringBuilder. "init")))))

(deftest print-zero-arg-static-method
  (is (= "System/currentTimeMillis()"
         (fmt-flat/format-form '(System/currentTimeMillis)))))

(deftest print-multi-arg-method
  (is (= ".replace(\"hello\" \"l\" \"r\")"
         (fmt-flat/format-form '(.replace "hello" "l" "r")))))

;; ---------------------------------------------------------------------------
;; Concurrency
;; ---------------------------------------------------------------------------

(deftest print-anon-fn-shorthand
  (testing "#() sugar when :meme/sugar tagged"
    (is (= "#(inc(%1))" (fmt-flat/format-form (with-meta '(fn [%1] (inc %1)) {:meme/sugar true})))))
  (testing "#() zero params when tagged"
    (is (= "#(rand())" (fmt-flat/format-form (with-meta '(fn [] (rand)) {:meme/sugar true})))))
  (testing "fn() when not tagged"
    (is (= "fn([%1] inc(%1))" (fmt-flat/format-form '(fn [%1] (inc %1))))))
  (testing "%& rest param falls through to fn form"
    (is (= "fn([& %&] apply(str %&))" (fmt-flat/format-form '(fn [& %&] (apply str %&))))))
  (testing "numbered + rest falls through to fn form"
    (is (= "fn([%1 & %&] +(%1 %&))" (fmt-flat/format-form '(fn [%1 & %&] (+ %1 %&)))))))

#?(:clj
   (deftest print-combined-metadata
     (testing "multiple metadata keys"
       (let [form (with-meta 'x {:private true :dynamic true})]
         (is (re-find #"\^" (fmt-flat/format-form form)))))))

(deftest print-swap
  (is (= "swap!(state update(:count inc))"
         (fmt-flat/format-form '(swap! state (update :count inc))))))

#?(:clj
   (deftest print-tagged-literal
     (is (= "#inst \"2024-01-01\""
            (fmt-flat/format-form (tagged-literal 'inst "2024-01-01"))))))

#?(:clj
   (deftest print-reader-conditional
     (let [rc (clojure.core/read-string {:read-cond :preserve} "#?(:clj 1 :cljs 2)")]
       (is (re-find #"^#\?" (fmt-flat/format-form rc))))))

;; ---------------------------------------------------------------------------
;; case, deftype, reify
;; ---------------------------------------------------------------------------

(deftest print-case
  (testing "case prints as call"
    (is (= "case(x 1 \"one\" 2 \"two\" \"default\")"
           (fmt-flat/format-form '(case x 1 "one" 2 "two" "default"))))))

(deftest print-deftype
  (testing "deftype prints as call"
    (is (= "deftype(Point [x y])"
           (fmt-flat/format-form '(deftype Point [x y]))))))

(deftest print-reify
  (testing "reify with method prints as call"
    (is (= "reify(Object toString([this] \"hello\"))"
           (fmt-flat/format-form '(reify Object (toString [this] "hello")))))))

;; ---------------------------------------------------------------------------
;; List-as-head: ((f x) y) → f(x)(y)
;; ---------------------------------------------------------------------------

(deftest print-list-as-head
  (testing "list result used as call head"
    (is (= "f(x)(y)" (fmt-flat/format-form '((f x) y)))))
  (testing "nested list-as-head"
    (is (= "f(x)(y)(z)" (fmt-flat/format-form '(((f x) y) z))))))
