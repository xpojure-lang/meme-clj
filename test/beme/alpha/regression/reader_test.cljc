(ns beme.alpha.regression.reader-test
  "Scar tissue: parser/reader regression tests.
   Every test here prevents a specific bug from recurring."
  (:require [clojure.test :refer [deftest is testing]]
            [beme.alpha.core :as core]
            [beme.alpha.emit.printer :as p]))

;; ---------------------------------------------------------------------------
;; Scar tissue: auto-resolve keywords are opaque
;; ---------------------------------------------------------------------------

(deftest auto-resolve-keyword-is-opaque
  #?(:clj
     (testing "::foo emits a deferred read-string call on JVM"
       (let [form (first (core/beme->forms "::local"))]
         (is (seq? form))
         (is (= 'clojure.core/read-string (first form)))
         (is (= "::local" (second form)))))
     :cljs
     (testing "::foo without :resolve-keyword errors on CLJS"
       (is (thrown-with-msg? js/Error #"resolve-keyword"
             (core/beme->forms "::local")))))
  #?(:clj
     (testing "::foo in a map key"
       (let [form (first (core/beme->forms "{::key 42}"))]
         (is (map? form))
         (let [[k v] (first form)]
           (is (seq? k))
           (is (= "::key" (second k)))
           (is (= 42 v))))))
  #?(:clj
     (testing "printer round-trips ::foo"
       (let [form (first (core/beme->forms "::local"))
             printed (p/print-form form)]
         (is (= "::local" printed))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: ratio literals.
;; ---------------------------------------------------------------------------

#?(:clj
(deftest ratio-literals
  (testing "1/2 — ratio literal works"
    (is (= 1/2 (first (core/beme->forms "1/2")))))
  (testing "3/4 — ratio literal works"
    (is (= 3/4 (first (core/beme->forms "3/4")))))))

;; ---------------------------------------------------------------------------
;; #_ discard at end of stream or before closing delimiters.
;; ---------------------------------------------------------------------------

(deftest discard-bare-at-eof
  (testing "#_ at bare EOF gives targeted error, not generic"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"Missing form after #_"
          (core/beme->forms "#_"))))
  (testing "#_ at bare EOF is :incomplete for REPL continuation"
    (let [e (try (core/beme->forms "#_")
                 nil
                 (catch #?(:clj Exception :cljs js/Error) e e))]
      (is (:incomplete (ex-data e))))))

(deftest discard-at-end-of-stream
  (testing "#_foo with nothing after returns empty"
    (is (= [] (core/beme->forms "#_foo"))))
  (testing "#_foo bar() still works"
    (is (= '[(bar)] (core/beme->forms "#_foo bar()"))))
  (testing "#_ before closing bracket"
    (is (= [[1]] (core/beme->forms "[1 #_2]"))))
  (testing "#_ in middle of collection"
    (is (= [[1 3]] (core/beme->forms "[1 #_2 3]"))))
  (testing "nested #_ #_ discards two forms"
    (is (= '[(c)] (core/beme->forms "#_ #_ a b c()"))))
  (testing "#_ before closing paren in list"
    (is (= '[(foo 1)] (core/beme->forms "foo(1 #_2)"))))
  (testing "#_ only form in collection"
    (is (= [[]] (core/beme->forms "[#_1]"))))
  (testing "Bug: #_ inside begin/end block — discard must recognize end as closer"
    (is (= '[(f 1)] (core/beme->forms "f begin 1 #_2 end"))))
  (testing "Bug: #_ as only form in begin/end block"
    (is (= '[(f)] (core/beme->forms "f begin #_1 end"))))
  (testing "Bug: multiple #_ before end in begin/end block"
    (is (= '[(f 3)] (core/beme->forms "f begin #_1 #_2 3 end"))))
  (testing "Bug: #_ #_ double-discard inside begin/end"
    (is (= '[(f c)] (core/beme->forms "f begin #_ #_ a b c end"))))
  (testing "#_ #_ in middle of begin/end body"
    (is (= '[(f x y)] (core/beme->forms "f begin x #_ #_ a b y end"))))
  (testing "#_ #_ discards everything in begin/end body"
    (is (= '[(f)] (core/beme->forms "f begin #_ #_ a b end")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: discard-sentinel must not leak into :meta or :tagged-literal.
;; ---------------------------------------------------------------------------

(deftest discard-sentinel-in-meta
  (testing "^:key #_foo throws — meta target discarded"
    (is (thrown? #?(:clj Exception :cljs js/Error) (core/beme->forms "^:key #_foo"))))
  (testing "^#_foo bar throws — meta value discarded"
    (is (thrown? #?(:clj Exception :cljs js/Error) (core/beme->forms "^#_foo bar"))))
  (testing "^:key foo still works when not discarded"
    (is (true? (:key (meta (first (core/beme->forms "^:key foo"))))))))

#?(:clj
(deftest discard-sentinel-in-tagged-literal
  (testing "#mytag #_foo throws — tagged literal value discarded"
    (is (thrown? Exception (core/beme->forms "#mytag #_foo"))))
  (testing "#mytag bar works when not discarded"
    (is (tagged-literal? (first (core/beme->forms "#mytag bar")))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: % params inside tagged literals in #() must be found.
;; ---------------------------------------------------------------------------

#?(:clj
(deftest percent-params-in-tagged-literals
  (testing "#(#mytag %) finds percent param"
    (let [form (first (core/beme->forms "#(#mytag %)"))]
      (is (= 'fn (first form)))
      (is (= '[%1] (second form)))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: js/parseInt without radix parses leading-zero as octal.
;; ---------------------------------------------------------------------------

(deftest percent-param-leading-zero-not-octal
  (testing "%08 param is decimal 8, not octal"
    (let [form (first (core/beme->forms "#(+(%1 %08))"))]
      (is (= 'fn (first form)))
      (is (= 8 (count (second form)))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: deeply nested input must not crash with StackOverflowError.
;; ---------------------------------------------------------------------------

(deftest recursion-depth-limit
  (testing "deeply nested input throws clean depth error"
    (let [deep-input (str (apply str (repeat 600 "f(")) "x" (apply str (repeat 600 ")")))]
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                            #"depth"
                            (core/beme->forms deep-input)))))
  (testing "50-level nesting succeeds within limit"
    (let [input (str (apply str (repeat 50 "[")) "x" (apply str (repeat 50 "]")))]
      (is (seq (core/beme->forms input))))))

;; ---------------------------------------------------------------------------
;; Syntax safety: beme operators must occupy dead Clojure syntax.
;; ---------------------------------------------------------------------------

(deftest rule1-call-syntax-trade-off
  (testing "Rule 1: f(x) → (f x) — head outside parens is a call"
    (is (= '[(f x)] (core/beme->forms "f(x)"))))
  (testing "bare symbol without parens is just a symbol"
    (is (= '[f] (core/beme->forms "f"))))
  #?(:clj
  (testing "this IS live Clojure syntax — known, documented trade-off"
    (let [clj-forms (with-open [r (java.io.PushbackReader. (java.io.StringReader. "f(x)"))]
                      [(read r) (read r)])]
      (is (= ['f '(x)] clj-forms) "Clojure reads f(x) as two forms")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: spacing between head and ( is irrelevant.
;; ---------------------------------------------------------------------------

(deftest spacing-irrelevant-for-calls
  (testing "symbol with space before paren is a call"
    (is (= '[(f x)] (core/beme->forms "f (x)"))))
  (testing "symbol with multiple spaces is a call"
    (is (= '[(f x)] (core/beme->forms "f   (x)"))))
  (testing "symbol with tab is a call"
    (is (= '[(f x)] (core/beme->forms "f\t(x)"))))
  (testing "symbol with newline is a call"
    (is (= '[(f x)] (core/beme->forms "f\n(x)"))))
  (testing "keyword with space is a call"
    (is (= '(:k x) (first (core/beme->forms ":k (x)")))))
  (testing "vector with space is a call (vector-as-head)"
    (is (= '([x] 1) (first (core/beme->forms "[x] (1)"))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: bare (...) without a head is a parse error.
;; ---------------------------------------------------------------------------

(deftest bare-parens-are-error
  (testing "bare (1 2 3) at top level is an error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"[Bb]are parentheses"
                          (core/beme->forms "(1 2 3)"))))
  (testing "bare () is an error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"[Bb]are parentheses"
                          (core/beme->forms "()"))))
  (testing "bare (x y) at top level is an error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"[Bb]are parentheses"
                          (core/beme->forms "(x y)")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: vector-as-head for multi-arity clauses.
;; ---------------------------------------------------------------------------

(deftest vector-as-head-multi-arity
  (testing "[x](body) produces a list with vector head"
    (is (= '([x] 1) (first (core/beme->forms "[x](1)")))))
  (testing "multi-arity defn roundtrips"
    (let [beme "defn(foo [x](x) [x y](+(x y)))"
          forms (core/beme->forms beme)
          printed (p/print-beme-string forms)
          forms2 (core/beme->forms printed)]
      (is (= forms forms2))))
  (testing "vector-as-head with space"
    (is (= '([a b] (+ a b)) (first (core/beme->forms "[a b] (+(a b))"))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: keyword-as-head for ns :require/:import clauses.
;; ---------------------------------------------------------------------------

(deftest keyword-as-head-ns-clauses
  (testing ":require([...]) produces keyword-headed list"
    (is (= '(:require [bar]) (first (core/beme->forms ":require([bar])")))))
  (testing "ns with :require roundtrips"
    (let [beme "ns(foo :require([bar]))"
          forms (core/beme->forms beme)
          printed (p/print-beme-string forms)
          forms2 (core/beme->forms printed)]
      (is (= forms forms2)))))

;; ---------------------------------------------------------------------------
;; Scar tissue: set-as-head and map-as-head for callable data structures.
;; ---------------------------------------------------------------------------

(deftest set-and-map-as-head
  (testing "set-as-head: #{:a :b}(x) roundtrips"
    (let [form (list #{:a :b} 'x)
          printed (p/print-form form)
          read-back (first (core/beme->forms printed))]
      (is (= form read-back))))
  (testing "map-as-head: {:a 1}(:a) roundtrips"
    (let [form (list {:a 1} :a)
          printed (p/print-form form)
          read-back (first (core/beme->forms printed))]
      (is (= form read-back)))))

;; ---------------------------------------------------------------------------
;; Prefix operator depth limit bypass.
;; ---------------------------------------------------------------------------

(deftest prefix-operator-depth-limit
  (testing "deep @ chain hits depth limit"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"depth"
          (core/beme->forms (str (apply str (repeat 600 "@")) "x")))))
  (testing "deep ' chain hits depth limit"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"depth"
          (core/beme->forms (str (apply str (repeat 600 "'")) "x")))))
  (testing "deep #' chain hits depth limit"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"depth"
          (core/beme->forms (str (apply str (repeat 600 "#'")) "foo")))))
  (testing "moderate depth succeeds"
    (is (some? (core/beme->forms (str (apply str (repeat 50 "@")) "x"))))))

;; ---------------------------------------------------------------------------
;; Discard sentinel leak in prefix operators.
;; ---------------------------------------------------------------------------

(deftest discard-sentinel-in-prefix-operators
  (testing "@#_foo at EOF throws"
    (is (thrown? #?(:clj Exception :cljs js/Error)
          (core/beme->forms "@#_foo"))))
  (testing "'#_foo at EOF throws"
    (is (thrown? #?(:clj Exception :cljs js/Error)
          (core/beme->forms "'#_foo"))))
  (testing "#'#_foo at EOF throws"
    (is (thrown? #?(:clj Exception :cljs js/Error)
          (core/beme->forms "#'#_foo"))))
  (testing "@#_foo bar applies deref to bar"
    (is (= '[(clojure.core/deref bar)]
           (core/beme->forms "@#_foo bar"))))
  (testing "'#_foo bar quotes bar"
    (is (= '[(quote bar)]
           (core/beme->forms "'#_foo bar"))))
  (testing "#'#_foo bar var-quotes bar"
    (is (= '[(var bar)]
           (core/beme->forms "#'#_foo bar")))))

;; ---------------------------------------------------------------------------
;; Bug: "be" was previously reserved as a shorthand for "begin".
;; ---------------------------------------------------------------------------

(deftest be-is-a-normal-symbol
  (testing "be parses as a regular symbol, not a delimiter"
    (is (= '[be] (core/beme->forms "be"))))
  (testing "be followed by parens is a call headed by be"
    (is (= '[(be x y)] (core/beme->forms "be(x y)"))))
  (testing "be inside begin/end is a normal symbol"
    (is (= '[(foo be)] (core/beme->forms "foo begin be end")))))

;; ---------------------------------------------------------------------------
;; Bug: ^42 x throws ClassCastException instead of beme error.
;; ---------------------------------------------------------------------------

(deftest invalid-metadata-type-error
  (testing "^42 x throws beme error, not ClassCastException"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"[Mm]etadata must be"
          (core/beme->forms "^42 x"))))
  (testing "^\"str\" x throws beme error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"[Mm]etadata must be"
          (core/beme->forms "^\"str\" x"))))
  (testing "^[1 2] x throws beme error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"[Mm]etadata must be"
          (core/beme->forms "^[1 2] x"))))
  (testing "valid metadata still works"
    (is (= {:private true} (dissoc (meta (first (core/beme->forms "^:private x"))) :ws)))
    (is (= {:tag 'String} (dissoc (meta (first (core/beme->forms "^String x"))) :ws)))
    (is (= {:doc "hi"} (dissoc (meta (first (core/beme->forms "^{:doc \"hi\"} x"))) :ws)))))

;; ---------------------------------------------------------------------------
;; Scar tissue: double discard inside #() anonymous function.
;; ---------------------------------------------------------------------------

(deftest double-discard-in-anon-fn
  (testing "#(#_ #_ a b c) — double discard skips a and b, c is the body"
    (is (= '[(fn [] c)] (core/beme->forms "#(#_ #_ a b c)")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: bare % and numbered %N mixed in #() forms.
;; ---------------------------------------------------------------------------

(deftest mixed-bare-and-numbered-percent-params
  (testing "#(+(% %3)) — bare % normalized to %1, params [%1 %2 %3]"
    (let [form (first (core/beme->forms "#(+(% %3))"))]
      (is (= 'fn (first form)))
      (is (= '[%1 %2 %3] (second form)))
      (is (= '(+ %1 %3) (nth form 2))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: mismatched bracket error includes location info.
;; ---------------------------------------------------------------------------

(deftest mismatched-bracket-error-message
  (testing "mismatched bracket error has descriptive message"
    (let [ex (try (core/beme->forms "f([)")
                  (catch #?(:clj Exception :cljs :default) e e))]
      (is (some? (ex-message ex)))
      (is (:line (ex-data ex)))
      (is (:col (ex-data ex))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: duplicate set elements and map keys silently deduplicated.
;; ---------------------------------------------------------------------------

(deftest duplicate-set-element-error
  (testing "#{1 1} throws duplicate error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"[Dd]uplicate"
          (core/beme->forms "#{1 1}"))))
  (testing "#{1 2 3} is fine"
    (is (= #{1 2 3} (first (core/beme->forms "#{1 2 3}"))))))

(deftest duplicate-map-key-error
  (testing "{:a 1 :a 2} throws duplicate error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"[Dd]uplicate"
          (core/beme->forms "{:a 1 :a 2}"))))
  (testing "{:a 1 :b 2} is fine"
    (is (= {:a 1 :b 2} (first (core/beme->forms "{:a 1 :b 2}"))))))
