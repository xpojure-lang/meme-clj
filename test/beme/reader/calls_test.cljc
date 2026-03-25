(ns beme.reader.calls-test
  "Parser tests for Clojure forms as calls.
   Everything is a call in beme — no special-form parsers.
   This file tests that all standard Clojure constructs parse correctly
   via the uniform f(args...) call syntax."
  (:require [clojure.test :refer [deftest is testing]]
            [beme.reader :as r]))

;; ---------------------------------------------------------------------------
;; do, def, defn, defn-, defmacro
;; ---------------------------------------------------------------------------

(deftest parse-do-call
  (let [result (first (r/read-beme-string "do(println(\"a\") println(\"b\"))"))]
    (is (= 'do (first result)))
    (is (= 3 (count result)))))

(deftest parse-def
  (is (= '[(def x 42)]
         (r/read-beme-string "def(x 42)"))))

(deftest parse-def-with-expression
  (is (= '[(def x (+ 1 2))]
         (r/read-beme-string "def(x +(1 2))"))))

(deftest parse-defn-single
  (let [result (first (r/read-beme-string "defn(greet [name] println(str(\"Hello \" name)))"))]
    (is (= 'defn (first result)))
    (is (= 'greet (second result)))
    (is (= '[name] (nth result 2)))))

(deftest parse-defn-with-multi-body
  (let [result (first (r/read-beme-string "defn(greet [name] println(\"hi\") :ok)"))]
    (is (= 'defn (first result)))
    (is (= 'greet (second result)))
    (is (= '[name] (nth result 2)))
    (is (= '(println "hi") (nth result 3)))
    (is (= :ok (nth result 4)))))

(deftest parse-defn-docstring
  (let [result (first (r/read-beme-string "defn(greet \"Greets a person\" [name] println(str(\"Hello \" name)))"))]
    (is (= "Greets a person" (nth result 2)))
    (is (= '[name] (nth result 3)))))

(deftest parse-defn-multi-arity
  ;; Vector-as-head: [args](body) → ([args] body)
  (let [result (first (r/read-beme-string
                        "defn(greet [name](str(\"Hello \" name \"!\")) [name punct](str(\"Hello \" name punct)))"))]
    (is (= 'defn (first result)))
    (is (= 'greet (second result)))
    (is (seq? (nth result 2)))
    (is (vector? (first (nth result 2))))))

(deftest parse-defn-multi-body-forms
  (let [result (first (r/read-beme-string "defn(greet [name] println(str(\"Hello \" name)) name)"))]
    (is (= 'defn (first result)))
    (is (= 5 (count result)))
    (is (= 'name (nth result 4)))))

(deftest parse-defn-private
  (let [result (first (r/read-beme-string "defn-(helper [x] +(x 1))"))]
    (is (= 'defn- (first result)))
    (is (= 'helper (second result)))))

(deftest parse-defmacro
  (let [result (first (r/read-beme-string "defmacro(when-let [bindings body] list(if bindings body nil))"))]
    (is (= 'defmacro (first result)))
    (is (= 'when-let (second result)))))

;; ---------------------------------------------------------------------------
;; fn
;; ---------------------------------------------------------------------------

(deftest parse-fn-simple
  (let [result (first (r/read-beme-string "fn([x] +(x 1))"))]
    (is (= 'fn (first result)))
    (is (= '[x] (second result)))
    (is (= '(+ x 1) (nth result 2)))))

(deftest parse-fn-two-args
  (let [result (first (r/read-beme-string "fn([x y] *(x y))"))]
    (is (= 'fn (first result)))
    (is (= '[x y] (second result)))))

(deftest parse-fn-with-multi-body
  (let [result (first (r/read-beme-string "fn([acc item] assoc(acc :id(item) :balance(item)))"))]
    (is (= 'fn (first result)))
    (is (= '[acc item] (second result)))
    (is (= '(assoc acc (:id item) (:balance item)) (nth result 2)))))

;; ---------------------------------------------------------------------------
;; let, loop, for, doseq
;; ---------------------------------------------------------------------------

(deftest parse-let
  (let [result (first (r/read-beme-string "let([x 1, y +(x 1)] *(x y))"))]
    (is (= 'let (first result)))
    (is (= '[x 1 y (+ x 1)] (second result)))))

(deftest parse-let-with-destructuring
  (let [result (first (r/read-beme-string "let([{:keys [id name]} person] println(id name))"))]
    (is (= 'let (first result)))
    (let [bindings (second result)]
      (is (map? (first bindings)))
      (is (= 'person (second bindings))))))

(deftest parse-loop
  (let [result (first (r/read-beme-string "loop([i 0, acc []] if(>=(i 10) acc recur(inc(i) conj(acc i))))"))]
    (is (= 'loop (first result)))
    (let [bindings (second result)]
      (is (= 'i (nth bindings 0)))
      (is (= 0 (nth bindings 1)))
      (is (= 'acc (nth bindings 2)))
      (is (= [] (nth bindings 3))))))

(deftest parse-for
  (let [result (first (r/read-beme-string "for([x xs, y ys, :when >(x y)] [x y])"))]
    (is (= 'for (first result)))
    (let [bindings (second result)]
      (is (= 'x (nth bindings 0)))
      (is (= 'xs (nth bindings 1)))
      (is (= 'y (nth bindings 2)))
      (is (= 'ys (nth bindings 3)))
      (is (= :when (nth bindings 4)))
      (is (= '(> x y) (nth bindings 5))))))

(deftest parse-doseq
  (let [result (first (r/read-beme-string "doseq([x xs] println(x))"))]
    (is (= 'doseq (first result)))
    (is (= 'x (first (second result))))
    (is (= 'xs (second (second result))))))

;; ---------------------------------------------------------------------------
;; if, when, cond
;; ---------------------------------------------------------------------------

(deftest parse-if-call
  (is (= '[(if (> x 0) "positive" "negative")]
         (r/read-beme-string "if(>(x 0) \"positive\" \"negative\")"))))

(deftest parse-if-single-branch
  (is (= '[(if (> x 0) "positive")]
         (r/read-beme-string "if(>(x 0) \"positive\")"))))

(deftest parse-when-call
  (let [result (first (r/read-beme-string "when(>(x 0) println(\"positive\") do-something(x))"))]
    (is (= 'when (first result)))
    (is (= '(> x 0) (second result)))
    (is (= '(println "positive") (nth result 2)))
    (is (= '(do-something x) (nth result 3)))))

(deftest parse-when-single
  (let [result (first (r/read-beme-string "when(>(x 0) println(\"positive\"))"))]
    (is (= 'when (first result)))
    (is (= '(> x 0) (second result)))
    (is (= '(println "positive") (nth result 2)))))

(deftest parse-cond
  (let [result (first (r/read-beme-string "cond(\n  >(x 0)   \"positive\"\n  ==(x 0)  \"zero\"\n  :else    \"negative\")"))]
    (is (= 'cond (first result)))
    (is (= 7 (count result)))))

;; ---------------------------------------------------------------------------
;; try/catch/finally
;; ---------------------------------------------------------------------------

(deftest parse-try-catch
  (let [result (first (r/read-beme-string "try(dangerous-operation() catch(Exception e log/error(\"Failed:\" e) default-value))"))]
    (is (= 'try (first result)))
    (let [parts (rest result)]
      (is (some #(and (seq? %) (= 'catch (first %))) parts)))))

(deftest parse-try-catch-finally
  (let [result (first (r/read-beme-string "try(dangerous() catch(Exception e handle(e)) finally(cleanup()))"))]
    (is (= 'try (first result)))
    (let [parts (rest result)]
      (is (some #(and (seq? %) (= 'catch (first %))) parts))
      (is (some #(and (seq? %) (= 'finally (first %))) parts)))))

(deftest parse-try-finally-only
  (let [result (first (r/read-beme-string "try(dangerous() finally(cleanup()))"))]
    (is (= 'try (first result)))
    (let [parts (rest result)]
      (is (some #(and (seq? %) (= 'finally (first %))) parts)))))

;; ---------------------------------------------------------------------------
;; threading
;; ---------------------------------------------------------------------------

(deftest parse-thread-first
  (let [result (first (r/read-beme-string "->(account update(:balance *(1.05)) assoc(:status :processed))"))]
    (is (= '-> (first result)))
    (is (= 'account (second result)))))

(deftest parse-thread-last
  (let [result (first (r/read-beme-string "->>(accounts filter(:active) map(:balance) reduce(+))"))]
    (is (= '->> (first result)))
    (is (= 'accounts (second result)))))

(deftest parse-thread-last-multiline
  (let [result (first (r/read-beme-string "->>(accounts\n  filter(:active)\n  map(fn([a] update(a :balance *(:balance(a) 1.05))))\n  remove(fn([a] neg?(:balance(a))))\n  reduce(+ 0))"))]
    (is (= '->> (first result)))
    (is (= 6 (count result)))))

;; ---------------------------------------------------------------------------
;; ns, defprotocol, defrecord, defmulti/defmethod, concurrency
;; ---------------------------------------------------------------------------

(deftest parse-ns-basic
  (let [result (first (r/read-beme-string "ns(my.accounts :require([clojure.string :as str]))"))]
    (is (= 'ns (first result)))
    (is (= 'my.accounts (second result)))
    (let [require-clause (nth result 2)]
      (is (= :require (first require-clause))))))

(deftest parse-ns-with-import
  (let [result (first (r/read-beme-string "ns(my.accounts :require([clojure.string :as str] [clojure.set :as set]) :import([java.util Date UUID]))"))]
    (is (= 'ns (first result)))
    (is (= 4 (count result)))
    (let [import-clause (nth result 3)]
      (is (= :import (first import-clause))))))

(deftest parse-ns-with-docstring
  (let [result (first (r/read-beme-string "ns(my.accounts \"Account management.\" :require([clojure.string :as str]))"))]
    (is (= 'ns (first result)))
    (is (= 'my.accounts (second result)))
    (is (= "Account management." (nth result 2)))
    (let [require-clause (nth result 3)]
      (is (= :require (first require-clause))))))

(deftest parse-ns-with-refer
  (let [result (first (r/read-beme-string "ns(my.accounts :require([my.db :refer [query connect]]))"))]
    (is (= 'ns (first result)))
    (let [require-clause (nth result 2)
          require-vec (second require-clause)]
      (is (vector? require-vec)))))

(deftest parse-defprotocol
  (let [result (first (r/read-beme-string "defprotocol(Drawable draw([this canvas]) bounds([this]))"))]
    (is (= 'defprotocol (first result)))
    (is (= 'Drawable (second result)))
    (let [sigs (drop 2 result)]
      (is (= 2 (count sigs)))
      (is (= 'draw (first (first sigs))))
      (is (= 'bounds (first (second sigs)))))))

(deftest parse-defrecord
  (let [result (first (r/read-beme-string "defrecord(Circle [center radius])"))]
    (is (= 'defrecord (first result)))
    (is (= 'Circle (second result)))
    (is (= '[center radius] (nth result 2)))))

(deftest parse-defrecord-with-impls
  (let [result (first (r/read-beme-string
                        "defrecord(Circle [center radius] Shape area([this] *(Math/PI :radius(this) :radius(this))))"))]
    (is (= 'defrecord (first result)))
    (is (= 'Circle (second result)))
    (is (= '[center radius] (nth result 2)))
    (is (= 'Shape (nth result 3)))
    (let [method (nth result 4)]
      (is (= 'area (first method)))
      (is (= '[this] (second method))))))

(deftest parse-defmulti
  (let [result (first (r/read-beme-string "defmulti(area :shape)"))]
    (is (= 'defmulti (first result)))
    (is (= 'area (second result)))
    (is (= :shape (nth result 2)))))

(deftest parse-defmethod
  (let [result (first (r/read-beme-string "defmethod(area :circle [{:keys [radius]}] *(Math/PI radius radius))"))]
    (is (= 'defmethod (first result)))
    (is (= 'area (second result)))
    (is (= :circle (nth result 2)))
    (is (vector? (nth result 3)))))

(deftest parse-atom-def
  (is (= '[(def state (atom {:count 0}))]
         (r/read-beme-string "def(state atom({:count 0}))"))))

(deftest parse-swap
  (let [result (first (r/read-beme-string "swap!(state update(:count inc))"))]
    (is (= 'swap! (first result)))
    (is (= 'state (second result)))))

;; ---------------------------------------------------------------------------
;; Design principle: everything is a call — no special-form parsers.
;; ---------------------------------------------------------------------------

(deftest everything-is-a-call
  (testing "def is a call"
    (is (= '[(def x 42)] (r/read-beme-string "def(x 42)"))))
  (testing "defn is a call"
    (is (= '[(defn f [x] (+ x 1))] (r/read-beme-string "defn(f [x] +(x 1))"))))
  (testing "let is a call"
    (is (= '[(let [x 1] (+ x 2))] (r/read-beme-string "let([x 1] +(x 2))"))))
  (testing "if is a call"
    (is (= '[(if true 1 2)] (r/read-beme-string "if(true 1 2)"))))
  (testing "when is a call"
    (is (= '[(when true (println "yes"))] (r/read-beme-string "when(true println(\"yes\"))"))))
  (testing "try/catch/finally are calls"
    (is (= '[(try (risky) (catch Exception e (handle e)) (finally (cleanup)))]
           (r/read-beme-string "try(risky() catch(Exception e handle(e)) finally(cleanup()))"))))
  (testing "do is a call"
    (is (= '[(do (a) (b))] (r/read-beme-string "do(a() b())"))))
  (testing "ns is a call"
    (is (= '[(ns foo (:require [bar]))] (r/read-beme-string "ns(foo :require([bar]))"))))
  (testing "defmulti is a call"
    (is (= '[(defmulti area :shape)] (r/read-beme-string "defmulti(area :shape)"))))
  (testing "defmethod is a call"
    (is (= '[(defmethod area :circle [this] (:radius this))]
           (r/read-beme-string "defmethod(area :circle [this] :radius(this))"))))
  (testing "defprotocol is a call"
    (is (= '[(defprotocol Drawable (draw [this canvas]))]
           (r/read-beme-string "defprotocol(Drawable draw([this canvas]))"))))
  (testing "defrecord is a call"
    (is (= '[(defrecord Foo [x y])]
           (r/read-beme-string "defrecord(Foo [x y])")))))

;; ---------------------------------------------------------------------------
;; end, do, catch, finally are regular symbols (not grammar keywords)
;; ---------------------------------------------------------------------------

(deftest parse-end-as-symbol
  (is (= '[end] (r/read-beme-string "end"))))

(deftest parse-do-as-symbol
  (is (= '[do] (r/read-beme-string "do"))))

(deftest parse-catch-as-symbol
  (is (= '[catch] (r/read-beme-string "catch"))))

(deftest parse-finally-as-symbol
  (is (= '[finally] (r/read-beme-string "finally"))))

;; ---------------------------------------------------------------------------
;; case, deftype, reify, fn (named, multi-arity), for with :while
;; ---------------------------------------------------------------------------

(deftest parse-case
  (testing "case with dispatch values and default"
    (is (= '[(case x 1 "one" 2 "two" "default")]
           (r/read-beme-string "case(x 1 \"one\" 2 \"two\" \"default\")")))))

(deftest parse-deftype
  (testing "deftype with name and fields"
    (is (= '[(deftype Point [x y])]
           (r/read-beme-string "deftype(Point [x y])")))))

(deftest parse-reify
  (testing "reify with interface and method"
    (is (= '[(reify Object (toString [this] "hello"))]
           (r/read-beme-string "reify(Object toString([this] \"hello\"))")))))

(deftest parse-fn-named
  (testing "fn with name"
    (is (= '[(fn add [x] (+ x 1))]
           (r/read-beme-string "fn(add [x] +(x 1))")))))

(deftest parse-fn-multi-arity
  (testing "fn with multiple arities using vector-as-head"
    (is (= '[(fn ([x] (inc x)) ([x y] (+ x y)))]
           (r/read-beme-string "fn([x](inc(x)) [x y](+(x y)))")))))

(deftest parse-for-while
  (testing "for with :while modifier"
    (let [result (first (r/read-beme-string "for([x xs, :while <(x 10)] x)"))
          bindings (second result)]
      (is (= 'for (first result)))
      (is (= :while (nth bindings 2)))
      (is (= '(< x 10) (nth bindings 3))))))

(deftest parse-for-let
  (testing "for with :let modifier"
    (let [result (first (r/read-beme-string "for([x [1 2 3], :let [y *(x 10)]] +(x y))"))
          bindings (second result)]
      (is (= 'for (first result)))
      (is (= :let (nth bindings 2)))
      (is (= '[y (* x 10)] (nth bindings 3))))))
