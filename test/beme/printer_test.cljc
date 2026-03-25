(ns beme.printer-test
  (:require [clojure.test :refer [deftest is testing]]
            [beme.printer :as p]))

;; ---------------------------------------------------------------------------
;; Rule 1: Generic call printing — everything is f(args...)
;; ---------------------------------------------------------------------------

(deftest print-simple-call
  (is (= "println(\"hello\")"
         (p/print-form '(println "hello")))))

(deftest print-operator-call
  (is (= "+(1 2 3)"
         (p/print-form '(+ 1 2 3)))))

(deftest print-nested-call
  (is (= "str(\"hi \" to-str(x))"
         (p/print-form '(str "hi " (to-str x))))))

(deftest print-zero-arg-call
  (is (= "foo()"
         (p/print-form '(foo)))))

(deftest print-keyword-as-arg
  (is (= "filter(:active accounts)"
         (p/print-form '(filter :active accounts)))))

;; ---------------------------------------------------------------------------
;; Keyword-headed calls
;; ---------------------------------------------------------------------------

(deftest print-keyword-access
  (is (= ":balance(account)"
         (p/print-form '(:balance account)))))

(deftest print-keyword-nested-access
  (is (= ":city(:address(account))"
         (p/print-form '(:city (:address account))))))

(deftest print-keyword-on-call-result
  (is (= ":name(get-user(id))"
         (p/print-form '(:name (get-user id))))))

;; ---------------------------------------------------------------------------
;; Special-form-like constructs — all generic call syntax
;; ---------------------------------------------------------------------------

(deftest print-def
  (is (= "def(x 42)"
         (p/print-form '(def x 42)))))

(deftest print-def-with-expr
  (is (= "def(x +(1 2))"
         (p/print-form '(def x (+ 1 2))))))

(deftest print-defn
  (is (= "defn(greet [name] str(\"Hello\" name))"
         (p/print-form '(defn greet [name] (str "Hello" name))))))

(deftest print-defn-with-docstring
  (is (= "defn(greet \"Greets a person\" [name] println(name))"
         (p/print-form '(defn greet "Greets a person" [name] (println name))))))

(deftest print-defn-multi-arity
  (is (= "defn(greet [name](greet(name \"!\")) [name punct](println(name punct)))"
         (p/print-form '(defn greet ([name] (greet name "!")) ([name punct] (println name punct)))))))

(deftest print-fn-simple
  (is (= "fn([x] +(x 1))"
         (p/print-form '(fn [x] (+ x 1))))))

(deftest print-fn-two-args
  (is (= "fn([x y] *(x y))"
         (p/print-form '(fn [x y] (* x y))))))

(deftest print-let
  (is (= "let([x 1] +(x 2))"
         (p/print-form '(let [x 1] (+ x 2))))))

(deftest print-if-inline
  (is (= "if(>(x 0) \"pos\" \"neg\")"
         (p/print-form '(if (> x 0) "pos" "neg")))))

(deftest print-if-single-branch
  (is (= "if(>(x 0) \"pos\")"
         (p/print-form '(if (> x 0) "pos")))))

(deftest print-when-simple
  (is (= "when(>(x 0) println(\"pos\"))"
         (p/print-form '(when (> x 0) (println "pos"))))))

(deftest print-do
  (is (= "do(a() b())"
         (p/print-form '(do (a) (b))))))

(deftest print-loop
  (is (= "loop([i 0 acc []] recur(inc(i) conj(acc i)))"
         (p/print-form '(loop [i 0 acc []] (recur (inc i) (conj acc i)))))))

(deftest print-for
  (is (= "for([x xs y ys] [x y])"
         (p/print-form '(for [x xs y ys] [x y])))))

(deftest print-for-with-when
  (is (= "for([x xs :when >(x 0)] x)"
         (p/print-form '(for [x xs :when (> x 0)] x)))))

(deftest print-try-catch
  (is (= "try(risky() catch(Exception e handle(e)))"
         (p/print-form '(try (risky) (catch Exception e (handle e)))))))

(deftest print-try-catch-finally
  (is (= "try(risky() catch(Exception e handle(e)) finally(cleanup()))"
         (p/print-form '(try (risky) (catch Exception e (handle e)) (finally (cleanup)))))))

(deftest print-ns
  (is (= "ns(foo :require([bar]))"
         (p/print-form '(ns foo (:require [bar]))))))

(deftest print-defprotocol
  (is (= "defprotocol(Foo draw([this]))"
         (p/print-form '(defprotocol Foo (draw [this]))))))

(deftest print-defmulti
  (is (= "defmulti(area :shape)"
         (p/print-form '(defmulti area :shape)))))

(deftest print-defmethod
  (is (= "defmethod(area :circle [{:keys [r]}] *(r r))"
         (p/print-form '(defmethod area :circle [{:keys [r]}] (* r r))))))

(deftest print-defrecord
  (is (= "defrecord(Foo [x y])"
         (p/print-form '(defrecord Foo [x y])))))

;; ---------------------------------------------------------------------------
;; Threading — generic call syntax
;; ---------------------------------------------------------------------------

(deftest print-thread-first
  (is (= "->(account update(:balance *(1.05)))"
         (p/print-form '(-> account (update :balance (* 1.05)))))))

(deftest print-thread-last
  (is (= "->>(accounts filter(:active) map(:balance) reduce(+))"
         (p/print-form '(->> accounts (filter :active) (map :balance) (reduce +))))))

;; ---------------------------------------------------------------------------
;; #() shorthand
;; ---------------------------------------------------------------------------

(deftest print-fn-shorthand
  (is (= "#(inc(%1))"
         (p/print-form '(fn [%1] (inc %1))))))

(deftest print-fn-shorthand-zero-params
  (is (= "#(rand())"
         (p/print-form '(fn [] (rand))))))

;; ---------------------------------------------------------------------------
;; Metadata
;; ---------------------------------------------------------------------------

(deftest print-metadata-keyword
  (is (= "^:private x" (p/print-form (with-meta 'x {:private true})))))

(deftest print-metadata-type-tag
  (is (= "^String x" (p/print-form (with-meta 'x {:tag 'String})))))

(deftest print-metadata-map
  (is (= "^{:doc \"hello\"} x" (p/print-form (with-meta 'x {:doc "hello"})))))

(deftest print-metadata-dynamic
  (is (= "^:dynamic *x*" (p/print-form (with-meta '*x* {:dynamic true})))))

;; ---------------------------------------------------------------------------
;; Reader macros: @deref, 'quote, #'var
;; ---------------------------------------------------------------------------

(deftest print-deref
  (is (= "@state" (p/print-form '(clojure.core/deref state)))))

(deftest print-var-quote
  (is (= "#'foo" (p/print-form '(var foo)))))

(deftest print-quote
  (is (= "'foo" (p/print-form '(quote foo)))))

;; ---------------------------------------------------------------------------
;; Primitives
;; ---------------------------------------------------------------------------

(deftest print-nil
  (is (= "nil" (p/print-form nil))))

(deftest print-true
  (is (= "true" (p/print-form true))))

(deftest print-false
  (is (= "false" (p/print-form false))))

(deftest print-keyword
  (is (= ":active" (p/print-form :active))))

(deftest print-namespaced-keyword
  (is (= ":foo/bar" (p/print-form :foo/bar))))

(deftest print-symbol
  (is (= "foo" (p/print-form 'foo))))

(deftest print-number-int
  (is (= "42" (p/print-form 42))))

(deftest print-number-float
  (is (= "3.14" (p/print-form 3.14))))

#?(:clj
(deftest print-number-bigdecimal
  (is (= "1.5M" (p/print-form 1.5M)))))

#?(:clj
(deftest print-number-bigint
  (is (= "42N" (p/print-form 42N)))))

(deftest print-string
  (is (= "\"hello\"" (p/print-form "hello"))))

#?(:clj
   (deftest print-char
     (is (= "\\a" (p/print-form \a)))))

(deftest print-regex
  (is (= "#\"pattern\"" (p/print-form #"pattern"))))

;; ---------------------------------------------------------------------------
;; Collections
;; ---------------------------------------------------------------------------

(deftest print-vector
  (is (= "[1 2 3]" (p/print-form [1 2 3]))))

(deftest print-empty-vector
  (is (= "[]" (p/print-form []))))

(deftest print-map-form
  (is (= "{:name \"Andriy\"}" (p/print-form {:name "Andriy"}))))

(deftest print-empty-map
  (is (= "{}" (p/print-form {}))))

(deftest print-set
  (let [result (p/print-form #{1 2 3})]
    (is (.startsWith result "#{"))))

(deftest print-empty-set
  (is (= "#{}" (p/print-form #{}))))

(deftest print-empty-list
  (is (= "'()" (p/print-form ()))))

;; ---------------------------------------------------------------------------
;; Interop
;; ---------------------------------------------------------------------------

(deftest print-method-call
  (is (= ".toUpperCase(\"hello\")"
         (p/print-form '(.toUpperCase "hello")))))

(deftest print-static-method
  (is (= "Math/abs(-1)"
         (p/print-form '(Math/abs -1)))))

(deftest print-field-access
  (is (= ".-x(point)"
         (p/print-form '(.-x point)))))

(deftest print-static-field
  (is (= "Math/PI" (p/print-form 'Math/PI))))

(deftest print-constructor
  (is (= "java.util.Date.()"
         (p/print-form '(java.util.Date.)))))

(deftest print-constructor-with-args
  (is (= "StringBuilder.(\"init\")"
         (p/print-form '(StringBuilder. "init")))))

(deftest print-zero-arg-static-method
  (is (= "System/currentTimeMillis()"
         (p/print-form '(System/currentTimeMillis)))))

(deftest print-multi-arg-method
  (is (= ".replace(\"hello\" \"l\" \"r\")"
         (p/print-form '(.replace "hello" "l" "r")))))

;; ---------------------------------------------------------------------------
;; Concurrency
;; ---------------------------------------------------------------------------

(deftest print-anon-fn-shorthand
  (testing "printer outputs #() for % params"
    (is (= "#(inc(%1))" (p/print-form '(fn [%1] (inc %1))))))
  (testing "#() zero params"
    (is (= "#(rand())" (p/print-form '(fn [] (rand))))))
  (testing "%& rest param falls through to fn form (no #() shorthand)"
    (is (= "fn([& %&] apply(str %&))" (p/print-form '(fn [& %&] (apply str %&))))))
  (testing "numbered + rest falls through to fn form"
    (is (= "fn([%1 & %&] +(%1 %&))" (p/print-form '(fn [%1 & %&] (+ %1 %&)))))))

#?(:clj
(deftest print-combined-metadata
  (testing "multiple metadata keys"
    (let [form (with-meta 'x {:private true :dynamic true})]
      (is (re-find #"\^" (p/print-form form)))))))

(deftest print-swap
  (is (= "swap!(state update(:count inc))"
         (p/print-form '(swap! state (update :count inc))))))

#?(:clj
(deftest print-tagged-literal
  (is (= "#inst \"2024-01-01\""
         (p/print-form (tagged-literal 'inst "2024-01-01"))))))

#?(:clj
(deftest print-reader-conditional
  (let [rc (clojure.core/read-string {:read-cond :preserve} "#?(:clj 1 :cljs 2)")]
    (is (re-find #"^#\?" (p/print-form rc))))))

;; ---------------------------------------------------------------------------
;; case, deftype, reify
;; ---------------------------------------------------------------------------

(deftest print-case
  (testing "case prints as call"
    (is (= "case(x 1 \"one\" 2 \"two\" \"default\")"
           (p/print-form '(case x 1 "one" 2 "two" "default"))))))

(deftest print-deftype
  (testing "deftype prints as call"
    (is (= "deftype(Point [x y])"
           (p/print-form '(deftype Point [x y]))))))

(deftest print-reify
  (testing "reify with method prints as call"
    (is (= "reify(Object toString([this] \"hello\"))"
           (p/print-form '(reify Object (toString [this] "hello")))))))

;; ---------------------------------------------------------------------------
;; begin/end as symbols (not delimiters) in printed output
;; ---------------------------------------------------------------------------

(deftest print-begin-end-as-arguments
  (testing "begin and end as symbol arguments print normally"
    (is (= "foo(begin end)" (p/print-form '(foo begin end))))))

(deftest print-begin-as-call-head
  (testing "begin as a call head prints with parens"
    (is (= "begin(x)" (p/print-form '(begin x))))))
