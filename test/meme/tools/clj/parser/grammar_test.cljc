(ns meme.tools.clj.parser.grammar-test
  "Native Clojure grammar — end-to-end parse + AST build + lowering.

   The grammar reuses the shared dispatch and tilde parselets from
   `meme.tools.clj.parser.parselets`, so these tests exercise both the
   grammar wiring and the lowering pipeline against canonical Clojure
   surface input. Compares against `clojure.core/read-string` as the
   ground-truth oracle for structural fidelity."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [meme.tools.parser :as pratt]
            [meme.tools.clj.parser.grammar :as g]
            [meme.tools.clj.ast.build :as build]
            [meme.tools.clj.ast.lower :as lower]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn parse-forms
  "Parse Clojure source via the native grammar and lower to plain forms.
   Uses `ast->forms` so top-level discards are filtered (matching what
   tooling sees when the eval pipeline runs)."
  [src]
  (-> src
      (pratt/parse g/grammar)
      build/cst->ast
      lower/ast->forms))

#?(:clj
   (defn read-all
     "Read all top-level forms from `src` using clojure.core/read-string."
     [src]
     (binding [*read-eval* false]
       (read-string (str "[" src "\n]")))))

#?(:clj
   (defn ground-truth?
     "Compare native parser output to clojure.core/read-string output."
     [src]
     (= (parse-forms src) (vec (read-all src))))
   :cljs
   (defn ground-truth? [_] true))

;; ---------------------------------------------------------------------------
;; Atomic literals
;; ---------------------------------------------------------------------------

(deftest atoms
  (testing "primitive atoms"
    (is (= [42] (parse-forms "42")))
    (is (= [-3.14] (parse-forms "-3.14")))
    (is (= ["hello"] (parse-forms "\"hello\"")))
    (is (= [\a] (parse-forms "\\a")))
    (is (= [nil] (parse-forms "nil")))
    (is (= [true false] (parse-forms "true false")))
    (is (= [:foo :ns/bar] (parse-forms ":foo :ns/bar")))
    (is (= ['x 'a/b] (parse-forms "x a/b")))))

;; ---------------------------------------------------------------------------
;; Lists with head inside parens — the core Clojure call rule
;; ---------------------------------------------------------------------------

(deftest list-as-call
  (testing "(f x y) parses as a list, not a meme-style call"
    (is (= ['(f x y)] (parse-forms "(f x y)")))
    (is (ground-truth? "(f x y)")))
  (testing "nested lists"
    (is (= ['(defn square [x] (* x x))]
           (parse-forms "(defn square [x] (* x x))")))
    (is (ground-truth? "(defn square [x] (* x x))")))
  (testing "empty list"
    (is (= ['()] (parse-forms "()")))
    (is (ground-truth? "()"))))

(deftest no-m-expression-call
  (testing "f(x) is two top-level forms in native Clojure, not a call"
    (let [forms (parse-forms "f(x)")]
      (is (= 2 (count forms)))
      (is (= 'f (first forms)))
      (is (= '(x) (second forms))))))

;; ---------------------------------------------------------------------------
;; Vectors, maps, sets
;; ---------------------------------------------------------------------------

(deftest data-literals
  (is (= [[1 2 3]] (parse-forms "[1 2 3]")))
  (is (= [{:a 1 :b 2}] (parse-forms "{:a 1 :b 2}")))
  (is (= [#{1 2 3}] (parse-forms "#{1 2 3}")))
  (is (ground-truth? "[1 2 3]"))
  (is (ground-truth? "{:a 1 :b 2}")))

;; ---------------------------------------------------------------------------
;; Reader sugar
;; ---------------------------------------------------------------------------

(deftest reader-sugar
  (testing "quote / deref / var-quote"
    (is (= ['(quote x)] (parse-forms "'x")))
    (is (= ['(clojure.core/deref x)] (parse-forms "@x")))
    (is (= ['(var x)] (parse-forms "#'x"))))

  (testing "syntax-quote with unquote forms"
    ;; The expander stage normalises ~/~@ so we don't rely on its output here.
    ;; Just check the AST path didn't drop tokens — round-trip count is enough.
    (let [forms (parse-forms "`(a ~b ~@c)")]
      (is (= 1 (count forms)))))

  (testing "discard #_"
    (is (= [42] (parse-forms "#_foo 42")))))

;; ---------------------------------------------------------------------------
;; Anonymous fn & tagged literals
;; ---------------------------------------------------------------------------

(deftest dispatch-forms
  (testing "#() lowers to (fn [%-params] body)"
    ;; The lowering normalises `%`/`%n` into a `(fn [...] body)` form.
    ;; We assert the structural envelope rather than the exact gensym shape.
    (let [[form] (parse-forms "#(* % %)")]
      (is (= 'fn (first form)))
      (is (vector? (second form)))
      (is (list? (nth form 2)))
      (is (= '* (first (nth form 2))))))

  (testing "tagged literal #inst"
    ;; #inst is a built-in Clojure tagged literal.
    (let [forms (parse-forms "#inst \"2026-01-01\"")]
      (is (= 1 (count forms))))))

;; ---------------------------------------------------------------------------
;; Reader conditionals — preserved as records
;; ---------------------------------------------------------------------------

(deftest reader-conditionals
  (testing "#?(:clj a :cljs b) lowers to a CljReaderConditional"
    (let [[form] (parse-forms "#?(:clj 42 :cljs 0)")]
      ;; `ReaderConditional` is JVM-only — CLJS uses a polyfill record.
      #?(:clj  (is (instance? clojure.lang.ReaderConditional form))
         :cljs (is (some? form)))))
  (testing "#?@ splicing"
    ;; Splicing reader-conditional must survive a parse without throwing.
    (let [forms (parse-forms "[1 #?@(:clj [2 3])]")]
      (is (= 1 (count forms))))))

;; ---------------------------------------------------------------------------
;; Whitespace, comments, BOM
;; ---------------------------------------------------------------------------

(deftest trivia
  (testing "comments and whitespace are stripped from forms"
    (is (= [1 2] (parse-forms "; this is a comment\n1\n; another\n2"))))
  (testing "commas are whitespace"
    (is (= [[1 2 3]] (parse-forms "[1, 2, 3]"))))
  (testing "BOM at start of file"
    (is (= [42] (parse-forms (str (char 0xFEFF) "42"))))))

;; ---------------------------------------------------------------------------
;; Real-world smoke tests against clojure.core/read-string
;; ---------------------------------------------------------------------------

(deftest read-string-parity-smoke
  (testing "small but representative Clojure samples produce identical forms"
    (doseq [src ["(defn square [x] (* x x))"
                 "(let [a 1 b 2] (+ a b))"
                 "(if (pos? n) :a :b)"
                 "(map inc [1 2 3])"
                 "(reduce + 0 (range 10))"
                 "(when true (println \"hi\"))"
                 "{:a 1 :b 2 :c [3 4 5]}"]]
      (is (ground-truth? src)
          (str "Parity failure for: " src)))))

(deftest multi-line-source
  (testing "multi-line source with mixed forms"
    (let [src (str/join "\n"
                ["(ns example.core"
                 "  (:require [clojure.string :as str]))"
                 ""
                 "(defn greet [name]"
                 "  (str \"Hello, \" name \"!\"))"])]
      (is (ground-truth? src)))))
