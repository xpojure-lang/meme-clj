(ns beme.alpha.regression.reader-test
  "Scar tissue: parser/reader regression tests.
   Every test here prevents a specific bug from recurring."
  (:require [clojure.test :refer [deftest is testing]]
            [beme.alpha.parse.reader :as r]
            [beme.alpha.emit.printer :as p]))

;; ---------------------------------------------------------------------------
;; Scar tissue: auto-resolve keywords are opaque
;; ---------------------------------------------------------------------------

(deftest auto-resolve-keyword-is-opaque
  #?(:clj
     (testing "::foo emits a deferred read-string call on JVM"
       (let [form (first (r/read-beme-string "::local"))]
         (is (seq? form))
         (is (= 'clojure.core/read-string (first form)))
         (is (= "::local" (second form)))))
     :cljs
     (testing "::foo without :resolve-keyword errors on CLJS"
       (is (thrown-with-msg? js/Error #"resolve-keyword"
             (r/read-beme-string "::local")))))
  #?(:clj
     (testing "::foo in a map key"
       (let [form (first (r/read-beme-string "{::key 42}"))]
         (is (map? form))
         (let [[k v] (first form)]
           (is (seq? k))
           (is (= "::key" (second k)))
           (is (= 42 v))))))
  #?(:clj
     (testing "printer round-trips ::foo"
       (let [form (first (r/read-beme-string "::local"))
             printed (p/print-form form)]
         (is (= "::local" printed))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: ratio literals.
;; ---------------------------------------------------------------------------

#?(:clj
(deftest ratio-literals
  (testing "1/2 — ratio literal works"
    (is (= 1/2 (first (r/read-beme-string "1/2")))))
  (testing "3/4 — ratio literal works"
    (is (= 3/4 (first (r/read-beme-string "3/4")))))))

;; ---------------------------------------------------------------------------
;; #_ discard at end of stream or before closing delimiters.
;; ---------------------------------------------------------------------------

(deftest discard-bare-at-eof
  (testing "#_ at bare EOF gives targeted error, not generic"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"Missing form after #_"
          (r/read-beme-string "#_"))))
  (testing "#_ at bare EOF is :incomplete for REPL continuation"
    (let [e (try (r/read-beme-string "#_")
                 nil
                 (catch #?(:clj Exception :cljs js/Error) e e))]
      (is (:incomplete (ex-data e))))))

(deftest discard-at-end-of-stream
  (testing "#_foo with nothing after returns empty"
    (is (= [] (r/read-beme-string "#_foo"))))
  (testing "#_foo bar() still works"
    (is (= '[(bar)] (r/read-beme-string "#_foo bar()"))))
  (testing "#_ before closing bracket"
    (is (= [[1]] (r/read-beme-string "[1 #_2]"))))
  (testing "#_ in middle of collection"
    (is (= [[1 3]] (r/read-beme-string "[1 #_2 3]"))))
  (testing "nested #_ #_ discards two forms"
    (is (= '[(c)] (r/read-beme-string "#_ #_ a b c()"))))
  (testing "#_ before closing paren in list"
    (is (= '[(foo 1)] (r/read-beme-string "foo(1 #_2)"))))
  (testing "#_ only form in collection"
    (is (= [[]] (r/read-beme-string "[#_1]"))))
  (testing "Bug: #_ inside begin/end block — discard must recognize end as closer"
    (is (= '[(f 1)] (r/read-beme-string "f begin 1 #_2 end"))))
  (testing "Bug: #_ as only form in begin/end block"
    (is (= '[(f)] (r/read-beme-string "f begin #_1 end"))))
  (testing "Bug: multiple #_ before end in begin/end block"
    (is (= '[(f 3)] (r/read-beme-string "f begin #_1 #_2 3 end"))))
  (testing "Bug: #_ #_ double-discard inside begin/end"
    (is (= '[(f c)] (r/read-beme-string "f begin #_ #_ a b c end"))))
  (testing "#_ #_ in middle of begin/end body"
    (is (= '[(f x y)] (r/read-beme-string "f begin x #_ #_ a b y end"))))
  (testing "#_ #_ discards everything in begin/end body"
    (is (= '[(f)] (r/read-beme-string "f begin #_ #_ a b end")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: discard-sentinel must not leak into :meta or :tagged-literal.
;; ---------------------------------------------------------------------------

(deftest discard-sentinel-in-meta
  (testing "^:key #_foo throws — meta target discarded"
    (is (thrown? #?(:clj Exception :cljs js/Error) (r/read-beme-string "^:key #_foo"))))
  (testing "^#_foo bar throws — meta value discarded"
    (is (thrown? #?(:clj Exception :cljs js/Error) (r/read-beme-string "^#_foo bar"))))
  (testing "^:key foo still works when not discarded"
    (is (true? (:key (meta (first (r/read-beme-string "^:key foo"))))))))

#?(:clj
(deftest discard-sentinel-in-tagged-literal
  (testing "#mytag #_foo throws — tagged literal value discarded"
    (is (thrown? Exception (r/read-beme-string "#mytag #_foo"))))
  (testing "#mytag bar works when not discarded"
    (is (tagged-literal? (first (r/read-beme-string "#mytag bar")))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: % params inside tagged literals in #() must be found.
;; ---------------------------------------------------------------------------

#?(:clj
(deftest percent-params-in-tagged-literals
  (testing "#(#mytag %) finds percent param"
    (let [form (first (r/read-beme-string "#(#mytag %)"))]
      (is (= 'fn (first form)))
      (is (= '[%1] (second form)))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: js/parseInt without radix parses leading-zero as octal.
;; ---------------------------------------------------------------------------

(deftest percent-param-leading-zero-not-octal
  (testing "%08 param is decimal 8, not octal"
    (let [form (first (r/read-beme-string "#(+(%1 %08))"))]
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
                            (r/read-beme-string deep-input)))))
  (testing "50-level nesting succeeds within limit"
    (let [input (str (apply str (repeat 50 "[")) "x" (apply str (repeat 50 "]")))]
      (is (seq (r/read-beme-string input))))))

;; ---------------------------------------------------------------------------
;; Syntax safety: beme operators must occupy dead Clojure syntax.
;; ---------------------------------------------------------------------------

(deftest rule1-call-syntax-trade-off
  (testing "Rule 1: f(x) → (f x) — head outside parens is a call"
    (is (= '[(f x)] (r/read-beme-string "f(x)"))))
  (testing "bare symbol without parens is just a symbol"
    (is (= '[f] (r/read-beme-string "f"))))
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
    (is (= '[(f x)] (r/read-beme-string "f (x)"))))
  (testing "symbol with multiple spaces is a call"
    (is (= '[(f x)] (r/read-beme-string "f   (x)"))))
  (testing "symbol with tab is a call"
    (is (= '[(f x)] (r/read-beme-string "f\t(x)"))))
  (testing "symbol with newline is a call"
    (is (= '[(f x)] (r/read-beme-string "f\n(x)"))))
  (testing "keyword with space is a call"
    (is (= '(:k x) (first (r/read-beme-string ":k (x)")))))
  (testing "vector with space is a call (vector-as-head)"
    (is (= '([x] 1) (first (r/read-beme-string "[x] (1)"))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: bare (...) without a head is a parse error.
;; ---------------------------------------------------------------------------

(deftest bare-parens-are-error
  (testing "bare (1 2 3) at top level is an error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"[Bb]are parentheses"
                          (r/read-beme-string "(1 2 3)"))))
  (testing "bare () is an error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"[Bb]are parentheses"
                          (r/read-beme-string "()"))))
  (testing "bare (x y) at top level is an error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"[Bb]are parentheses"
                          (r/read-beme-string "(x y)")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: vector-as-head for multi-arity clauses.
;; ---------------------------------------------------------------------------

(deftest vector-as-head-multi-arity
  (testing "[x](body) produces a list with vector head"
    (is (= '([x] 1) (first (r/read-beme-string "[x](1)")))))
  (testing "multi-arity defn roundtrips"
    (let [beme "defn(foo [x](x) [x y](+(x y)))"
          forms (r/read-beme-string beme)
          printed (p/print-beme-string forms)
          forms2 (r/read-beme-string printed)]
      (is (= forms forms2))))
  (testing "vector-as-head with space"
    (is (= '([a b] (+ a b)) (first (r/read-beme-string "[a b] (+(a b))"))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: keyword-as-head for ns :require/:import clauses.
;; ---------------------------------------------------------------------------

(deftest keyword-as-head-ns-clauses
  (testing ":require([...]) produces keyword-headed list"
    (is (= '(:require [bar]) (first (r/read-beme-string ":require([bar])")))))
  (testing "ns with :require roundtrips"
    (let [beme "ns(foo :require([bar]))"
          forms (r/read-beme-string beme)
          printed (p/print-beme-string forms)
          forms2 (r/read-beme-string printed)]
      (is (= forms forms2)))))

;; ---------------------------------------------------------------------------
;; Scar tissue: set-as-head and map-as-head for callable data structures.
;; ---------------------------------------------------------------------------

(deftest set-and-map-as-head
  (testing "set-as-head: #{:a :b}(x) roundtrips"
    (let [form (list #{:a :b} 'x)
          printed (p/print-form form)
          read-back (first (r/read-beme-string printed))]
      (is (= form read-back))))
  (testing "map-as-head: {:a 1}(:a) roundtrips"
    (let [form (list {:a 1} :a)
          printed (p/print-form form)
          read-back (first (r/read-beme-string printed))]
      (is (= form read-back)))))

;; ---------------------------------------------------------------------------
;; Prefix operator depth limit bypass.
;; ---------------------------------------------------------------------------

(deftest prefix-operator-depth-limit
  (testing "deep @ chain hits depth limit"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"depth"
          (r/read-beme-string (str (apply str (repeat 600 "@")) "x")))))
  (testing "deep ' chain hits depth limit"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"depth"
          (r/read-beme-string (str (apply str (repeat 600 "'")) "x")))))
  (testing "deep #' chain hits depth limit"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"depth"
          (r/read-beme-string (str (apply str (repeat 600 "#'")) "foo")))))
  (testing "moderate depth succeeds"
    (is (some? (r/read-beme-string (str (apply str (repeat 50 "@")) "x"))))))

;; ---------------------------------------------------------------------------
;; Discard sentinel leak in prefix operators.
;; ---------------------------------------------------------------------------

(deftest discard-sentinel-in-prefix-operators
  (testing "@#_foo at EOF throws"
    (is (thrown? #?(:clj Exception :cljs js/Error)
          (r/read-beme-string "@#_foo"))))
  (testing "'#_foo at EOF throws"
    (is (thrown? #?(:clj Exception :cljs js/Error)
          (r/read-beme-string "'#_foo"))))
  (testing "#'#_foo at EOF throws"
    (is (thrown? #?(:clj Exception :cljs js/Error)
          (r/read-beme-string "#'#_foo"))))
  (testing "@#_foo bar applies deref to bar"
    (is (= '[(clojure.core/deref bar)]
           (r/read-beme-string "@#_foo bar"))))
  (testing "'#_foo bar quotes bar"
    (is (= '[(quote bar)]
           (r/read-beme-string "'#_foo bar"))))
  (testing "#'#_foo bar var-quotes bar"
    (is (= '[(var bar)]
           (r/read-beme-string "#'#_foo bar")))))

;; ---------------------------------------------------------------------------
;; Bug: "be" was previously reserved as a shorthand for "begin".
;; ---------------------------------------------------------------------------

(deftest be-is-a-normal-symbol
  (testing "be parses as a regular symbol, not a delimiter"
    (is (= '[be] (r/read-beme-string "be"))))
  (testing "be followed by parens is a call headed by be"
    (is (= '[(be x y)] (r/read-beme-string "be(x y)"))))
  (testing "be inside begin/end is a normal symbol"
    (is (= '[(foo be)] (r/read-beme-string "foo begin be end")))))

;; ---------------------------------------------------------------------------
;; Bug: ^42 x throws ClassCastException instead of beme error.
;; ---------------------------------------------------------------------------

(deftest invalid-metadata-type-error
  (testing "^42 x throws beme error, not ClassCastException"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"[Mm]etadata must be"
          (r/read-beme-string "^42 x"))))
  (testing "^\"str\" x throws beme error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"[Mm]etadata must be"
          (r/read-beme-string "^\"str\" x"))))
  (testing "^[1 2] x throws beme error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"[Mm]etadata must be"
          (r/read-beme-string "^[1 2] x"))))
  (testing "valid metadata still works"
    (is (= {:private true} (dissoc (meta (first (r/read-beme-string "^:private x"))) :ws)))
    (is (= {:tag 'String} (dissoc (meta (first (r/read-beme-string "^String x"))) :ws)))
    (is (= {:doc "hi"} (dissoc (meta (first (r/read-beme-string "^{:doc \"hi\"} x"))) :ws)))))

;; ---------------------------------------------------------------------------
;; Scar tissue: double discard inside #() anonymous function.
;; ---------------------------------------------------------------------------

(deftest double-discard-in-anon-fn
  (testing "#(#_ #_ a b c) — double discard skips a and b, c is the body"
    (is (= '[(fn [] c)] (r/read-beme-string "#(#_ #_ a b c)")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: bare % and numbered %N mixed in #() forms.
;; ---------------------------------------------------------------------------

(deftest mixed-bare-and-numbered-percent-params
  (testing "#(+(% %3)) — bare % normalized to %1, params [%1 %2 %3]"
    (let [form (first (r/read-beme-string "#(+(% %3))"))]
      (is (= 'fn (first form)))
      (is (= '[%1 %2 %3] (second form)))
      (is (= '(+ %1 %3) (nth form 2))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: mismatched bracket error includes location info.
;; ---------------------------------------------------------------------------

(deftest mismatched-bracket-error-message
  (testing "mismatched bracket error has descriptive message"
    (let [ex (try (r/read-beme-string "f([)")
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
          (r/read-beme-string "#{1 1}"))))
  (testing "#{1 2 3} is fine"
    (is (= #{1 2 3} (first (r/read-beme-string "#{1 2 3}"))))))

(deftest duplicate-map-key-error
  (testing "{:a 1 :a 2} throws duplicate error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"[Dd]uplicate"
          (r/read-beme-string "{:a 1 :a 2}"))))
  (testing "{:a 1 :b 2} is fine"
    (is (= {:a 1 :b 2} (first (r/read-beme-string "{:a 1 :b 2}"))))))
