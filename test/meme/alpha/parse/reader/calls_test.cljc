(ns meme.alpha.parse.reader.calls-test
  "Parser tests for Clojure forms as calls.
   Everything is a call in meme — no special-form parsers.
   This file tests that all standard Clojure constructs parse correctly
   via the uniform f(args...) call syntax."
  (:require [clojure.test :refer [deftest is testing]]
            [meme.alpha.core :as core]))

;; ---------------------------------------------------------------------------
;; do, def, defn, defn-, defmacro
;; ---------------------------------------------------------------------------

(deftest parse-do-call
  (let [result (first (core/meme->forms "do(println(\"a\") println(\"b\"))"))]
    (is (= 'do (first result)))
    (is (= 3 (count result)))))

(deftest parse-def
  (is (= '[(def x 42)]
         (core/meme->forms "def(x 42)"))))

(deftest parse-def-with-expression
  (is (= '[(def x (+ 1 2))]
         (core/meme->forms "def(x +(1 2))"))))

(deftest parse-defn-single
  (let [result (first (core/meme->forms "defn(greet [name] println(str(\"Hello \" name)))"))]
    (is (= 'defn (first result)))
    (is (= 'greet (second result)))
    (is (= '[name] (nth result 2)))))

(deftest parse-defn-with-multi-body
  (let [result (first (core/meme->forms "defn(greet [name] println(\"hi\") :ok)"))]
    (is (= 'defn (first result)))
    (is (= 'greet (second result)))
    (is (= '[name] (nth result 2)))
    (is (= '(println "hi") (nth result 3)))
    (is (= :ok (nth result 4)))))

(deftest parse-defn-docstring
  (let [result (first (core/meme->forms "defn(greet \"Greets a person\" [name] println(str(\"Hello \" name)))"))]
    (is (= "Greets a person" (nth result 2)))
    (is (= '[name] (nth result 3)))))

(deftest parse-defn-multi-arity
  ;; Vector-as-head: [args](body) → ([args] body)
  (let [result (first (core/meme->forms
                        "defn(greet [name](str(\"Hello \" name \"!\")) [name punct](str(\"Hello \" name punct)))"))]
    (is (= 'defn (first result)))
    (is (= 'greet (second result)))
    (is (seq? (nth result 2)))
    (is (vector? (first (nth result 2))))))

(deftest parse-defn-multi-body-forms
  (let [result (first (core/meme->forms "defn(greet [name] println(str(\"Hello \" name)) name)"))]
    (is (= 'defn (first result)))
    (is (= 5 (count result)))
    (is (= 'name (nth result 4)))))

(deftest parse-defn-private
  (let [result (first (core/meme->forms "defn-(helper [x] +(x 1))"))]
    (is (= 'defn- (first result)))
    (is (= 'helper (second result)))))

(deftest parse-defmacro
  (let [result (first (core/meme->forms "defmacro(when-let [bindings body] list(if bindings body nil))"))]
    (is (= 'defmacro (first result)))
    (is (= 'when-let (second result)))))

;; ---------------------------------------------------------------------------
;; fn
;; ---------------------------------------------------------------------------

(deftest parse-fn-simple
  (let [result (first (core/meme->forms "fn([x] +(x 1))"))]
    (is (= 'fn (first result)))
    (is (= '[x] (second result)))
    (is (= '(+ x 1) (nth result 2)))))

(deftest parse-fn-two-args
  (let [result (first (core/meme->forms "fn([x y] *(x y))"))]
    (is (= 'fn (first result)))
    (is (= '[x y] (second result)))))

(deftest parse-fn-with-multi-body
  (let [result (first (core/meme->forms "fn([acc item] assoc(acc :id(item) :balance(item)))"))]
    (is (= 'fn (first result)))
    (is (= '[acc item] (second result)))
    (is (= '(assoc acc (:id item) (:balance item)) (nth result 2)))))

;; ---------------------------------------------------------------------------
;; let, loop, for, doseq
;; ---------------------------------------------------------------------------

(deftest parse-let
  (let [result (first (core/meme->forms "let([x 1, y +(x 1)] *(x y))"))]
    (is (= 'let (first result)))
    (is (= '[x 1 y (+ x 1)] (second result)))))

(deftest parse-let-with-destructuring
  (let [result (first (core/meme->forms "let([{:keys [id name]} person] println(id name))"))]
    (is (= 'let (first result)))
    (let [bindings (second result)]
      (is (map? (first bindings)))
      (is (= 'person (second bindings))))))

(deftest parse-loop
  (let [result (first (core/meme->forms "loop([i 0, acc []] if(>=(i 10) acc recur(inc(i) conj(acc i))))"))]
    (is (= 'loop (first result)))
    (let [bindings (second result)]
      (is (= 'i (nth bindings 0)))
      (is (= 0 (nth bindings 1)))
      (is (= 'acc (nth bindings 2)))
      (is (= [] (nth bindings 3))))))

(deftest parse-for
  (let [result (first (core/meme->forms "for([x xs, y ys, :when >(x y)] [x y])"))]
    (is (= 'for (first result)))
    (let [bindings (second result)]
      (is (= 'x (nth bindings 0)))
      (is (= 'xs (nth bindings 1)))
      (is (= 'y (nth bindings 2)))
      (is (= 'ys (nth bindings 3)))
      (is (= :when (nth bindings 4)))
      (is (= '(> x y) (nth bindings 5))))))

(deftest parse-doseq
  (let [result (first (core/meme->forms "doseq([x xs] println(x))"))]
    (is (= 'doseq (first result)))
    (is (= 'x (first (second result))))
    (is (= 'xs (second (second result))))))

;; ---------------------------------------------------------------------------
;; if, when, cond
;; ---------------------------------------------------------------------------

(deftest parse-if-call
  (is (= '[(if (> x 0) "positive" "negative")]
         (core/meme->forms "if(>(x 0) \"positive\" \"negative\")"))))

(deftest parse-if-single-branch
  (is (= '[(if (> x 0) "positive")]
         (core/meme->forms "if(>(x 0) \"positive\")"))))

(deftest parse-when-call
  (let [result (first (core/meme->forms "when(>(x 0) println(\"positive\") do-something(x))"))]
    (is (= 'when (first result)))
    (is (= '(> x 0) (second result)))
    (is (= '(println "positive") (nth result 2)))
    (is (= '(do-something x) (nth result 3)))))

(deftest parse-when-single
  (let [result (first (core/meme->forms "when(>(x 0) println(\"positive\"))"))]
    (is (= 'when (first result)))
    (is (= '(> x 0) (second result)))
    (is (= '(println "positive") (nth result 2)))))

(deftest parse-cond
  (let [result (first (core/meme->forms "cond(\n  >(x 0)   \"positive\"\n  ==(x 0)  \"zero\"\n  :else    \"negative\")"))]
    (is (= 'cond (first result)))
    (is (= 7 (count result)))))

;; ---------------------------------------------------------------------------
;; try/catch/finally
;; ---------------------------------------------------------------------------

(deftest parse-try-catch
  (let [result (first (core/meme->forms "try(dangerous-operation() catch(Exception e log/error(\"Failed:\" e) default-value))"))]
    (is (= 'try (first result)))
    (let [parts (rest result)]
      (is (some #(and (seq? %) (= 'catch (first %))) parts)))))

(deftest parse-try-catch-finally
  (let [result (first (core/meme->forms "try(dangerous() catch(Exception e handle(e)) finally(cleanup()))"))]
    (is (= 'try (first result)))
    (let [parts (rest result)]
      (is (some #(and (seq? %) (= 'catch (first %))) parts))
      (is (some #(and (seq? %) (= 'finally (first %))) parts)))))

(deftest parse-try-finally-only
  (let [result (first (core/meme->forms "try(dangerous() finally(cleanup()))"))]
    (is (= 'try (first result)))
    (let [parts (rest result)]
      (is (some #(and (seq? %) (= 'finally (first %))) parts)))))

;; ---------------------------------------------------------------------------
;; threading
;; ---------------------------------------------------------------------------

(deftest parse-thread-first
  (let [result (first (core/meme->forms "->(account update(:balance *(1.05)) assoc(:status :processed))"))]
    (is (= '-> (first result)))
    (is (= 'account (second result)))))

(deftest parse-thread-last
  (let [result (first (core/meme->forms "->>(accounts filter(:active) map(:balance) reduce(+))"))]
    (is (= '->> (first result)))
    (is (= 'accounts (second result)))))

(deftest parse-thread-last-multiline
  (let [result (first (core/meme->forms "->>(accounts\n  filter(:active)\n  map(fn([a] update(a :balance *(:balance(a) 1.05))))\n  remove(fn([a] neg?(:balance(a))))\n  reduce(+ 0))"))]
    (is (= '->> (first result)))
    (is (= 6 (count result)))))

;; ---------------------------------------------------------------------------
;; ns, defprotocol, defrecord, defmulti/defmethod, concurrency
;; ---------------------------------------------------------------------------

(deftest parse-ns-basic
  (let [result (first (core/meme->forms "ns(my.accounts :require([clojure.string :as str]))"))]
    (is (= 'ns (first result)))
    (is (= 'my.accounts (second result)))
    (let [require-clause (nth result 2)]
      (is (= :require (first require-clause))))))

(deftest parse-ns-with-import
  (let [result (first (core/meme->forms "ns(my.accounts :require([clojure.string :as str] [clojure.set :as set]) :import([java.util Date UUID]))"))]
    (is (= 'ns (first result)))
    (is (= 4 (count result)))
    (let [import-clause (nth result 3)]
      (is (= :import (first import-clause))))))

(deftest parse-ns-with-docstring
  (let [result (first (core/meme->forms "ns(my.accounts \"Account management.\" :require([clojure.string :as str]))"))]
    (is (= 'ns (first result)))
    (is (= 'my.accounts (second result)))
    (is (= "Account management." (nth result 2)))
    (let [require-clause (nth result 3)]
      (is (= :require (first require-clause))))))

(deftest parse-ns-with-refer
  (let [result (first (core/meme->forms "ns(my.accounts :require([my.db :refer [query connect]]))"))]
    (is (= 'ns (first result)))
    (let [require-clause (nth result 2)
          require-vec (second require-clause)]
      (is (vector? require-vec)))))

(deftest parse-defprotocol
  (let [result (first (core/meme->forms "defprotocol(Drawable draw([this canvas]) bounds([this]))"))]
    (is (= 'defprotocol (first result)))
    (is (= 'Drawable (second result)))
    (let [sigs (drop 2 result)]
      (is (= 2 (count sigs)))
      (is (= 'draw (first (first sigs))))
      (is (= 'bounds (first (second sigs)))))))

(deftest parse-defrecord
  (let [result (first (core/meme->forms "defrecord(Circle [center radius])"))]
    (is (= 'defrecord (first result)))
    (is (= 'Circle (second result)))
    (is (= '[center radius] (nth result 2)))))

(deftest parse-defrecord-with-impls
  (let [result (first (core/meme->forms
                        "defrecord(Circle [center radius] Shape area([this] *(Math/PI :radius(this) :radius(this))))"))]
    (is (= 'defrecord (first result)))
    (is (= 'Circle (second result)))
    (is (= '[center radius] (nth result 2)))
    (is (= 'Shape (nth result 3)))
    (let [method (nth result 4)]
      (is (= 'area (first method)))
      (is (= '[this] (second method))))))

(deftest parse-defmulti
  (let [result (first (core/meme->forms "defmulti(area :shape)"))]
    (is (= 'defmulti (first result)))
    (is (= 'area (second result)))
    (is (= :shape (nth result 2)))))

(deftest parse-defmethod
  (let [result (first (core/meme->forms "defmethod(area :circle [{:keys [radius]}] *(Math/PI radius radius))"))]
    (is (= 'defmethod (first result)))
    (is (= 'area (second result)))
    (is (= :circle (nth result 2)))
    (is (vector? (nth result 3)))))

(deftest parse-atom-def
  (is (= '[(def state (atom {:count 0}))]
         (core/meme->forms "def(state atom({:count 0}))"))))

(deftest parse-swap
  (let [result (first (core/meme->forms "swap!(state update(:count inc))"))]
    (is (= 'swap! (first result)))
    (is (= 'state (second result)))))

;; ---------------------------------------------------------------------------
;; Design principle: everything is a call — no special-form parsers.
;; ---------------------------------------------------------------------------

(deftest everything-is-a-call
  (testing "def is a call"
    (is (= '[(def x 42)] (core/meme->forms "def(x 42)"))))
  (testing "defn is a call"
    (is (= '[(defn f [x] (+ x 1))] (core/meme->forms "defn(f [x] +(x 1))"))))
  (testing "let is a call"
    (is (= '[(let [x 1] (+ x 2))] (core/meme->forms "let([x 1] +(x 2))"))))
  (testing "if is a call"
    (is (= '[(if true 1 2)] (core/meme->forms "if(true 1 2)"))))
  (testing "when is a call"
    (is (= '[(when true (println "yes"))] (core/meme->forms "when(true println(\"yes\"))"))))
  (testing "try/catch/finally are calls"
    (is (= '[(try (risky) (catch Exception e (handle e)) (finally (cleanup)))]
           (core/meme->forms "try(risky() catch(Exception e handle(e)) finally(cleanup()))"))))
  (testing "do is a call"
    (is (= '[(do (a) (b))] (core/meme->forms "do(a() b())"))))
  (testing "ns is a call"
    (is (= '[(ns foo (:require [bar]))] (core/meme->forms "ns(foo :require([bar]))"))))
  (testing "defmulti is a call"
    (is (= '[(defmulti area :shape)] (core/meme->forms "defmulti(area :shape)"))))
  (testing "defmethod is a call"
    (is (= '[(defmethod area :circle [this] (:radius this))]
           (core/meme->forms "defmethod(area :circle [this] :radius(this))"))))
  (testing "defprotocol is a call"
    (is (= '[(defprotocol Drawable (draw [this canvas]))]
           (core/meme->forms "defprotocol(Drawable draw([this canvas]))"))))
  (testing "defrecord is a call"
    (is (= '[(defrecord Foo [x y])]
           (core/meme->forms "defrecord(Foo [x y])")))))

;; ---------------------------------------------------------------------------
;; do, catch, finally are regular symbols (not grammar keywords)
;; ---------------------------------------------------------------------------

(deftest parse-do-as-symbol
  (is (= '[do] (core/meme->forms "do"))))

(deftest parse-catch-as-symbol
  (is (= '[catch] (core/meme->forms "catch"))))

(deftest parse-finally-as-symbol
  (is (= '[finally] (core/meme->forms "finally"))))

;; ---------------------------------------------------------------------------
;; case, deftype, reify, fn (named, multi-arity), for with :while
;; ---------------------------------------------------------------------------

(deftest parse-case
  (testing "case with dispatch values and default"
    (is (= '[(case x 1 "one" 2 "two" "default")]
           (core/meme->forms "case(x 1 \"one\" 2 \"two\" \"default\")")))))

(deftest parse-deftype
  (testing "deftype with name and fields"
    (is (= '[(deftype Point [x y])]
           (core/meme->forms "deftype(Point [x y])")))))

(deftest parse-reify
  (testing "reify with interface and method"
    (is (= '[(reify Object (toString [this] "hello"))]
           (core/meme->forms "reify(Object toString([this] \"hello\"))")))))

(deftest parse-fn-named
  (testing "fn with name"
    (is (= '[(fn add [x] (+ x 1))]
           (core/meme->forms "fn(add [x] +(x 1))")))))

(deftest parse-fn-multi-arity
  (testing "fn with multiple arities using vector-as-head"
    (is (= '[(fn ([x] (inc x)) ([x y] (+ x y)))]
           (core/meme->forms "fn([x](inc(x)) [x y](+(x y)))")))))

(deftest parse-condp
  (testing "condp with predicate, expression, and clauses"
    (is (= '[(condp = x 1 "one" 2 "two" "default")]
           (core/meme->forms "condp(= x 1 \"one\" 2 \"two\" \"default\")"))))
  (testing "condp with custom predicate"
    (is (= '[(condp contains? #{1 2 3} #{1} "has one" "nope")]
           (core/meme->forms "condp(contains? #{1 2 3} #{1} \"has one\" \"nope\")")))))

(deftest parse-letfn
  (testing "letfn with function binding"
    (is (= '[(letfn [(double [x] (* x 2))] (double 5))]
           (core/meme->forms "letfn([double([x] *(x 2))] double(5))"))))
  (testing "letfn with multiple bindings"
    (is (= '[(letfn [(even? [n] (if (zero? n) true (odd? (dec n))))
                     (odd? [n] (if (zero? n) false (even? (dec n))))]
               (even? 4))]
           (core/meme->forms
            "letfn([even?([n] if(zero?(n) true odd?(dec(n)))) odd?([n] if(zero?(n) false even?(dec(n))))] even?(4))")))))

(deftest parse-for-while
  (testing "for with :while modifier"
    (let [result (first (core/meme->forms "for([x xs, :while <(x 10)] x)"))
          bindings (second result)]
      (is (= 'for (first result)))
      (is (= :while (nth bindings 2)))
      (is (= '(< x 10) (nth bindings 3))))))

(deftest parse-for-let
  (testing "for with :let modifier"
    (let [result (first (core/meme->forms "for([x [1 2 3], :let [y *(x 10)]] +(x y))"))
          bindings (second result)]
      (is (= 'for (first result)))
      (is (= :let (nth bindings 2)))
      (is (= '[y (* x 10)] (nth bindings 3))))))
