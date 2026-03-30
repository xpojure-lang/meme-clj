(ns meme.alpha.regression.reader-test
  "Scar tissue: parser/reader regression tests.
   Every test here prevents a specific bug from recurring."
  (:require [clojure.test :refer [deftest is testing]]
            [meme.alpha.core :as core]
            [meme.alpha.emit.printer :as p]
            [meme.alpha.forms :as forms]
            [meme.alpha.parse.expander :as expander]
            [meme.alpha.parse.reader :as reader]))

;; ---------------------------------------------------------------------------
;; Scar tissue: auto-resolve keywords are opaque
;; ---------------------------------------------------------------------------

(deftest auto-resolve-keyword-is-opaque
  #?(:clj
     (testing "::foo emits a deferred read-string call on JVM"
       (let [form (first (core/meme->forms "::local"))]
         (is (seq? form))
         (is (= 'clojure.core/read-string (first form)))
         (is (= "::local" (second form)))))
     :cljs
     (testing "::foo without :resolve-keyword errors on CLJS"
       (is (thrown-with-msg? js/Error #"resolve-keyword"
             (core/meme->forms "::local")))))
  #?(:clj
     (testing "::foo in a map key"
       (let [form (first (core/meme->forms "{::key 42}"))]
         (is (map? form))
         (let [[k v] (first form)]
           (is (seq? k))
           (is (= "::key" (second k)))
           (is (= 42 v))))))
  #?(:clj
     (testing "printer round-trips ::foo"
       (let [form (first (core/meme->forms "::local"))
             printed (p/print-form form)]
         (is (= "::local" printed))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: ratio literals.
;; ---------------------------------------------------------------------------

#?(:clj
(deftest ratio-literals
  (testing "1/2 — ratio literal works"
    (is (= 1/2 (first (core/meme->forms "1/2")))))
  (testing "3/4 — ratio literal works"
    (is (= 3/4 (first (core/meme->forms "3/4")))))))

;; ---------------------------------------------------------------------------
;; #_ discard at end of stream or before closing delimiters.
;; ---------------------------------------------------------------------------

(deftest discard-bare-at-eof
  (testing "#_ at bare EOF gives targeted error, not generic"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"Missing form after #_"
          (core/meme->forms "#_"))))
  (testing "#_ at bare EOF is :incomplete for REPL continuation"
    (let [e (try (core/meme->forms "#_")
                 nil
                 (catch #?(:clj Exception :cljs js/Error) e e))]
      (is (:incomplete (ex-data e))))))

(deftest discard-at-end-of-stream
  (testing "#_foo with nothing after returns empty"
    (is (= [] (core/meme->forms "#_foo"))))
  (testing "#_foo bar() still works"
    (is (= '[(bar)] (core/meme->forms "#_foo bar()"))))
  (testing "#_ before closing bracket"
    (is (= [[1]] (core/meme->forms "[1 #_2]"))))
  (testing "#_ in middle of collection"
    (is (= [[1 3]] (core/meme->forms "[1 #_2 3]"))))
  (testing "nested #_ #_ discards two forms"
    (is (= '[(c)] (core/meme->forms "#_ #_ a b c()"))))
  (testing "#_ before closing paren in list"
    (is (= '[(foo 1)] (core/meme->forms "foo(1 #_2)"))))
  (testing "#_ only form in collection"
    (is (= [[]] (core/meme->forms "[#_1]"))))
)

;; ---------------------------------------------------------------------------
;; Scar tissue: discard-sentinel must not leak into :meta or :tagged-literal.
;; ---------------------------------------------------------------------------

(deftest discard-sentinel-in-meta
  (testing "^:key #_foo throws — meta target discarded"
    (is (thrown? #?(:clj Exception :cljs js/Error) (core/meme->forms "^:key #_foo"))))
  (testing "^#_foo bar throws — meta value discarded"
    (is (thrown? #?(:clj Exception :cljs js/Error) (core/meme->forms "^#_foo bar"))))
  (testing "^:key foo still works when not discarded"
    (is (true? (:key (meta (first (core/meme->forms "^:key foo"))))))))

#?(:clj
(deftest discard-sentinel-in-tagged-literal
  (testing "#mytag #_foo throws — tagged literal value discarded"
    (is (thrown? Exception (core/meme->forms "#mytag #_foo"))))
  (testing "#mytag bar works when not discarded"
    (is (tagged-literal? (first (core/meme->forms "#mytag bar")))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: % params inside tagged literals in #() must be found.
;; ---------------------------------------------------------------------------

#?(:clj
(deftest percent-params-in-tagged-literals
  (testing "#(#mytag %) finds percent param"
    (let [form (first (core/meme->forms "#(#mytag %)"))]
      (is (= 'fn (first form)))
      (is (= '[%1] (second form)))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: js/parseInt without radix parses leading-zero as octal.
;; ---------------------------------------------------------------------------

(deftest percent-param-leading-zero-not-octal
  (testing "%08 param is decimal 8, not octal"
    (let [form (first (core/meme->forms "#(+(%1 %08))"))]
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
                            (core/meme->forms deep-input)))))
  (testing "50-level nesting succeeds within limit"
    (let [input (str (apply str (repeat 50 "[")) "x" (apply str (repeat 50 "]")))]
      (is (seq (core/meme->forms input))))))

;; ---------------------------------------------------------------------------
;; Syntax safety: meme operators must occupy dead Clojure syntax.
;; ---------------------------------------------------------------------------

(deftest rule1-call-syntax-trade-off
  (testing "Rule 1: f(x) → (f x) — head outside parens is a call"
    (is (= '[(f x)] (core/meme->forms "f(x)"))))
  (testing "bare symbol without parens is just a symbol"
    (is (= '[f] (core/meme->forms "f"))))
  #?(:clj
  (testing "this IS live Clojure syntax — known, documented trade-off"
    (let [clj-forms (with-open [r (java.io.PushbackReader. (java.io.StringReader. "f(x)"))]
                      [(read r) (read r)])]
      (is (= ['f '(x)] clj-forms) "Clojure reads f(x) as two forms")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: spacing between head and ( is significant (adjacency required).
;; ---------------------------------------------------------------------------

(deftest spacing-significant-for-calls
  (testing "adjacency required — f(x) is a call"
    (is (= '[(f x)] (core/meme->forms "f(x)"))))
  (testing "space prevents call — f (x) is bare paren error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"Bare parentheses"
          (core/meme->forms "f (x)"))))
  (testing "tab prevents call"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"Bare parentheses"
          (core/meme->forms "f\t(x)"))))
  (testing "newline prevents call"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"Bare parentheses"
          (core/meme->forms "f\n(x)"))))
  (testing "keyword adjacency required"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"Bare parentheses"
          (core/meme->forms ":k (x)"))))
  (testing "vector adjacency required"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"Bare parentheses"
          (core/meme->forms "[x] (1)")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: bare (...) without a head is a parse error.
;; ---------------------------------------------------------------------------

(deftest bare-parens-are-error
  (testing "bare (1 2 3) at top level is an error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"[Bb]are parentheses"
                          (core/meme->forms "(1 2 3)"))))
  (testing "() is the empty list"
    (is (= [(list)] (core/meme->forms "()"))))
  (testing "bare (x y) at top level is an error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"[Bb]are parentheses"
                          (core/meme->forms "(x y)")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: vector-as-head for multi-arity clauses.
;; ---------------------------------------------------------------------------

(deftest vector-as-head-multi-arity
  (testing "[x](body) produces a list with vector head"
    (is (= '([x] 1) (first (core/meme->forms "[x](1)")))))
  (testing "multi-arity defn roundtrips"
    (let [meme "defn(foo [x](x) [x y](+(x y)))"
          forms (core/meme->forms meme)
          printed (p/print-meme-string forms)
          forms2 (core/meme->forms printed)]
      (is (= forms forms2))))
  (testing "vector-as-head requires adjacency — space prevents call"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"Bare parentheses"
          (core/meme->forms "[a b] (+(a b))"))))
  (testing "vector-as-head adjacent works"
    (is (= '([a b] (+ a b)) (first (core/meme->forms "[a b](+(a b))"))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: keyword-as-head for ns :require/:import clauses.
;; ---------------------------------------------------------------------------

(deftest keyword-as-head-ns-clauses
  (testing ":require([...]) produces keyword-headed list"
    (is (= '(:require [bar]) (first (core/meme->forms ":require([bar])")))))
  (testing "ns with :require roundtrips"
    (let [meme "ns(foo :require([bar]))"
          forms (core/meme->forms meme)
          printed (p/print-meme-string forms)
          forms2 (core/meme->forms printed)]
      (is (= forms forms2)))))

;; ---------------------------------------------------------------------------
;; Scar tissue: set-as-head and map-as-head for callable data structures.
;; ---------------------------------------------------------------------------

(deftest set-and-map-as-head
  (testing "set-as-head: #{:a :b}(x) roundtrips"
    (let [form (list #{:a :b} 'x)
          printed (p/print-form form)
          read-back (first (core/meme->forms printed))]
      (is (= form read-back))))
  (testing "map-as-head: {:a 1}(:a) roundtrips"
    (let [form (list {:a 1} :a)
          printed (p/print-form form)
          read-back (first (core/meme->forms printed))]
      (is (= form read-back)))))

;; ---------------------------------------------------------------------------
;; Prefix operator depth limit bypass.
;; ---------------------------------------------------------------------------

(deftest prefix-operator-depth-limit
  (testing "deep @ chain hits depth limit"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"depth"
          (core/meme->forms (str (apply str (repeat 600 "@")) "x")))))
  (testing "deep ' chain hits depth limit"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"depth"
          (core/meme->forms (str (apply str (repeat 600 "'")) "x")))))
  (testing "deep #' chain hits depth limit"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"depth"
          (core/meme->forms (str (apply str (repeat 600 "#'")) "foo")))))
  (testing "moderate depth succeeds"
    (is (some? (core/meme->forms (str (apply str (repeat 50 "@")) "x"))))))

;; ---------------------------------------------------------------------------
;; Discard sentinel leak in prefix operators.
;; ---------------------------------------------------------------------------

(deftest discard-sentinel-in-prefix-operators
  (testing "@#_foo at EOF throws"
    (is (thrown? #?(:clj Exception :cljs js/Error)
          (core/meme->forms "@#_foo"))))
  (testing "'#_foo at EOF throws"
    (is (thrown? #?(:clj Exception :cljs js/Error)
          (core/meme->forms "'#_foo"))))
  (testing "#'#_foo at EOF throws"
    (is (thrown? #?(:clj Exception :cljs js/Error)
          (core/meme->forms "#'#_foo"))))
  (testing "@#_foo bar applies deref to bar"
    (is (= '[(clojure.core/deref bar)]
           (core/meme->forms "@#_foo bar"))))
  (testing "'#_foo bar quotes bar"
    (is (= '[(quote bar)]
           (core/meme->forms "'#_foo bar"))))
  (testing "#'#_foo bar var-quotes bar"
    (is (= '[(var bar)]
           (core/meme->forms "#'#_foo bar")))))

;; ---------------------------------------------------------------------------
;; "be" is a normal symbol (not reserved).
;; ---------------------------------------------------------------------------

(deftest be-is-a-normal-symbol
  (testing "be parses as a regular symbol"
    (is (= '[be] (core/meme->forms "be"))))
  (testing "be followed by parens is a call headed by be"
    (is (= '[(be x y)] (core/meme->forms "be(x y)")))))

;; ---------------------------------------------------------------------------
;; Bug: ^42 x throws ClassCastException instead of meme error.
;; ---------------------------------------------------------------------------

(deftest invalid-metadata-type-error
  (testing "^42 x throws meme error, not ClassCastException"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"[Mm]etadata must be"
          (core/meme->forms "^42 x"))))
  (testing "^\"str\" x throws meme error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"[Mm]etadata must be"
          (core/meme->forms "^\"str\" x"))))
  (testing "^[1 2] x throws meme error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"[Mm]etadata must be"
          (core/meme->forms "^[1 2] x"))))
  (testing "valid metadata still works"
    (is (= {:private true} (dissoc (meta (first (core/meme->forms "^:private x"))) :ws :meme/meta-chain)))
    (is (= {:tag 'String} (dissoc (meta (first (core/meme->forms "^String x"))) :ws :meme/meta-chain)))
    (is (= {:doc "hi"} (dissoc (meta (first (core/meme->forms "^{:doc \"hi\"} x"))) :ws :meme/meta-chain)))))

;; ---------------------------------------------------------------------------
;; Scar tissue: double discard inside #() anonymous function.
;; ---------------------------------------------------------------------------

(deftest double-discard-in-anon-fn
  (testing "#(#_ #_ a b c) — double discard skips a and b, c is the body"
    (is (= '[(fn [] c)] (core/meme->forms "#(#_ #_ a b c)")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: %0 in #() silently produced (fn [] (inc %0)) with %0 as
;; free symbol. Clojure rejects %0; meme must too.
;; ---------------------------------------------------------------------------

(deftest percent-zero-rejected-in-anon-fn
  (testing "#(inc(%0)) — %0 is not a valid param, must error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"%0 is not a valid parameter"
          (core/meme->forms "#(inc(%0))"))))
  (testing "#(+(%0 %1)) — %0 mixed with valid params, must error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"%0 is not a valid parameter"
          (core/meme->forms "#(+(%0 %1))"))))
  (testing "%1 and higher still work"
    (is (= '[(fn [%1] (inc %1))] (core/meme->forms "#(inc(%1))")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: bare % and numbered %N mixed in #() forms.
;; ---------------------------------------------------------------------------

(deftest mixed-bare-and-numbered-percent-params
  (testing "#(+(% %3)) — bare % normalized to %1, params [%1 %2 %3]"
    (let [form (first (core/meme->forms "#(+(% %3))"))]
      (is (= 'fn (first form)))
      (is (= '[%1 %2 %3] (second form)))
      (is (= '(+ %1 %3) (nth form 2))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: mismatched bracket error includes location info.
;; ---------------------------------------------------------------------------

(deftest mismatched-bracket-error-message
  (testing "mismatched bracket error has descriptive message"
    (let [ex (try (core/meme->forms "f([)")
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
          (core/meme->forms "#{1 1}"))))
  (testing "#{1 2 3} is fine"
    (is (= #{1 2 3} (first (core/meme->forms "#{1 2 3}"))))))

(deftest duplicate-map-key-error
  (testing "{:a 1 :a 2} throws duplicate error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"[Dd]uplicate"
          (core/meme->forms "{:a 1 :a 2}"))))
  (testing "{:a 1 :b 2} is fine"
    (is (= {:a 1 :b 2} (first (core/meme->forms "{:a 1 :b 2}"))))))

;; ---------------------------------------------------------------------------
;; Bug: printer emits f(x)(y) for list-headed calls ((f x) y), but reader
;; rejected (y) as "Bare parentheses." Roundtrip violated P13.
;; Fix: parse-call-chain in parse-form chains calls after any form.
;; ---------------------------------------------------------------------------

(deftest chained-call-roundtrip
  (testing "f(x)(y) → ((f x) y)"
    (is (= '[(( f x) y)] (core/meme->forms "f(x)(y)"))))
  (testing "f(x)(y)(z) → (((f x) y) z)"
    (is (= '[(((f x) y) z)] (core/meme->forms "f(x)(y)(z)"))))
  (testing "printer output roundtrips"
    (let [form '((f x) y)
          printed (p/print-form form)
          re-read (first (core/meme->forms printed))]
      (is (= "f(x)(y)" printed))
      (is (= form re-read))))
  (testing "'foo(x) quotes the entire call"
    (is (= '[(quote (foo x))] (core/meme->forms "'foo(x)"))))
  (testing "quote(foo)(x) chains — quote call then chained call"
    (is (= '[((quote foo) x)] (core/meme->forms "quote(foo)(x)")))))

;; ---------------------------------------------------------------------------
;; Bug: reader accepted source text as third argument to
;; read-meme-string-from-tokens but discarded it (_source). Parse errors
;; never carried :source-context in ex-data, even when source was available.
;; Fix: store source in parser state, inject into all meme-error calls.
;; ---------------------------------------------------------------------------

(deftest reader-errors-include-source-context
  (testing "parse error carries :source-context in ex-data"
    (let [src "foo(bar"
          e (try (core/meme->forms src) nil
                 (catch #?(:clj Exception :cljs :default) e e))]
      (is (some? e))
      (is (some? (:source-context (ex-data e))))))
  (testing "bare paren error carries :source-context"
    (let [src "(oops)"
          e (try (core/meme->forms src) nil
                 (catch #?(:clj Exception :cljs :default) e e))]
      (is (some? (:source-context (ex-data e)))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: ~@ in non-collection inside syntax-quote must include location.
;; ---------------------------------------------------------------------------

(deftest unquote-splicing-error-has-location
  (testing "~@ in map inside syntax-quote — error at expansion time"
    (let [forms (core/meme->forms "`{~@xs 1}")]
      (is (forms/syntax-quote? (first forms)) "read produces AST node")
      (try (reader/expand-forms forms)
           (is false "should have thrown")
           (catch #?(:clj Exception :cljs :default) e
             (is (re-find #"Unquote-splicing" (ex-message e))))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: #? reader conditionals lost non-matching branches on roundtrip.
;; Without :read-cond :preserve, meme->forms evaluates #? at read time,
;; discarding branches for other platforms. clj->meme->clj roundtrip was lossy.
;; Fix: added :read-cond :preserve option to return ReaderConditional objects.
;; ---------------------------------------------------------------------------

(deftest reader-conditional-preserve-mode
  (testing "preserve mode returns ReaderConditional, not the evaluated branch"
    (let [rc (first (core/meme->forms "#?(:clj 1 :cljs 2)" {:read-cond :preserve}))]
      (is (forms/meme-reader-conditional? rc))
      (is (= '(:clj 1 :cljs 2) (forms/rc-form rc)))))
  (testing "default mode still evaluates (backwards compat)"
    (let [result (first (core/meme->forms "#?(:clj 1 :cljs 2)"))]
      (is (not (forms/meme-reader-conditional? result)))
      (is (= #?(:clj 1 :cljs 2) result))))
  (testing "preserve roundtrips through printer"
    (let [rc (first (core/meme->forms "#?(:clj inc(1) :cljs dec(2))" {:read-cond :preserve}))
          printed (p/print-form rc)
          rc2 (first (core/meme->forms printed {:read-cond :preserve}))]
      (is (= rc rc2)))))

;; ---------------------------------------------------------------------------
;; Scar tissue: non-matching reader conditional must produce no form.
;; Bug: parse-reader-cond-eval returned (list) on no-match instead of
;; discard-sentinel, injecting an empty list '() into the output.
;; Fix: return discard-sentinel when no branch matches.
;; ---------------------------------------------------------------------------

(deftest reader-conditional-no-match-produces-no-form
  (testing "#?(:cljs x) on JVM produces no form"
    #?(:clj  (is (= [] (core/meme->forms "#?(:cljs x)")))
       :cljs (is (= [] (core/meme->forms "#?(:clj x)")))))
  (testing "surrounding forms preserved when reader conditional is skipped"
    #?(:clj  (is (= [1 2] (core/meme->forms "1 #?(:cljs 99) 2")))
       :cljs (is (= [1 2] (core/meme->forms "1 #?(:clj 99) 2")))))
  (testing "splicing non-matching produces no form"
    #?(:clj  (is (= [] (core/meme->forms "#?@(:cljs [1 2])")))
       :cljs (is (= [] (core/meme->forms "#?@(:clj [1 2])")))))
  (testing "#?() empty — no branches, no form"
    (is (= [] (core/meme->forms "#?()")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: non-matching reader conditional with adjacent call args must
;; consume the args, not leave them as bare parentheses.
;; Bug: parse-reader-cond-eval returned discard-sentinel without consuming
;; adjacent (args), causing "Bare parentheses not allowed" on the next parse.
;; Fix: loop-consume adjacent call args when returning discard-sentinel.
;; ---------------------------------------------------------------------------

(deftest reader-conditional-no-match-consumes-call-args
  (testing "non-matching reader cond with call args produces no form"
    #?(:clj  (is (= [] (core/meme->forms "#?(:cljs identity)(42)")))
       :cljs (is (= [] (core/meme->forms "#?(:clj identity)(42)")))))
  (testing "chained call args also consumed"
    #?(:clj  (is (= [] (core/meme->forms "#?(:cljs identity)(42)(43)")))
       :cljs (is (= [] (core/meme->forms "#?(:clj identity)(42)(43)")))))
  (testing "surrounding forms preserved"
    #?(:clj  (is (= [1 2] (core/meme->forms "1 #?(:cljs inc)(42) 2")))
       :cljs (is (= [1 2] (core/meme->forms "1 #?(:clj inc)(42) 2")))))
  (testing "matching case still works with call args"
    #?(:clj  (is (= ['(inc 42)] (core/meme->forms "#?(:clj inc)(42)")))
       :cljs (is (= ['(identity 42)] (core/meme->forms "#?(:cljs identity)(42)"))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: #_ inside reader conditional must not consume platform keyword.
;; Bug: #_ read-through consumed the next platform keyword as the branch value,
;; corrupting the pair structure. e.g. #?(:clj #_x :cljs 99) — #_ discards x,
;; reads :cljs as :clj's value, then 99 fails as "expected keyword".
;; Fix: once a branch is matched, remaining forms are consumed permissively
;; (matching Clojure reader behavior).
;; ---------------------------------------------------------------------------

(deftest discard-inside-reader-conditional
  #?(:clj
     (do
       (testing "#?(:clj #_x :cljs 99) — #_ eats :cljs, matched value is :cljs keyword"
         (is (= [:cljs] (core/meme->forms "#?(:clj #_x :cljs 99)"))))
       (testing "#?(:clj #_x y :cljs 99) — #_ eats x, y is the value"
         (is (= ['y] (core/meme->forms "#?(:clj #_x y :cljs 99)"))))
       (testing "permissive after match — stray form after matched branch"
         (is (= [1] (core/meme->forms "#?(:clj 1 99)")))))
     :cljs
     (do
       (testing "#?(:cljs #_x :clj 99) — #_ eats :clj, matched value is :clj keyword"
         (is (= [:clj] (core/meme->forms "#?(:cljs #_x :clj 99)"))))
       (testing "#?(:cljs #_x y :clj 99) — #_ eats x, y is the value"
         (is (= ['y] (core/meme->forms "#?(:cljs #_x y :clj 99)")))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: map with duplicate keys roundtrips correctly
;; Found by generative testing: {:p a :p a} — Clojure deduplicates keys on
;; read, so print→re-read must produce the same deduplicated form.
;; ---------------------------------------------------------------------------

(deftest duplicate-map-keys-rejected
  (testing "duplicate keyword keys are rejected"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"Duplicate key"
          (core/meme->forms "{:p a :p a}"))))
  (testing "duplicate symbol keys are rejected"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"Duplicate key"
          (core/meme->forms "{x 1 x 2}"))))
  (testing "unique keys roundtrip fine"
    (let [forms (core/meme->forms "{:a 1 :b 2}")
          printed (p/print-meme-string forms)
          re-read (core/meme->forms printed)]
      (is (= forms re-read)))))

;; ---------------------------------------------------------------------------
;; Scar tissue: MemeRaw inside syntax-quote was treated as a map by expand-sq.
;; defrecord instances satisfy (map? x), so MemeRaw{:value 255, :raw "0xFF"}
;; hit the map branch and produced (apply hash-map ...) instead of the plain
;; value. Fix: check forms/raw? before (map? form) in expand-sq.
;; ---------------------------------------------------------------------------

#?(:clj
(deftest meme-raw-in-syntax-quote-expands-correctly
  (testing "hex number inside syntax-quote expands to its value, not a map"
    (let [forms (core/meme->forms "`[0xFF]")
          expanded (expander/expand-forms forms)]
      ;; The expanded form should contain the number 255, not {:value 255 :raw "0xFF"}
      (is (not (some #(and (map? %) (contains? % :value)) (flatten (map seq expanded))))
          "MemeRaw must not leak as a map into expanded forms")))
  (testing "scientific notation inside syntax-quote"
    (let [forms (core/meme->forms "`1e2")
          expanded (expander/expand-forms forms)]
      (is (= [100.0] expanded))))
  (testing "char literal inside syntax-quote"
    (let [forms (core/meme->forms "`\\a")
          expanded (expander/expand-forms forms)]
      (is (= [\a] expanded))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: nested MemeSyntaxQuote inside expand-sq was treated as a map.
;; ``x produced MemeSyntaxQuote{:form x} inside the outer MemeSyntaxQuote.
;; expand-sq fell through to (map? form) and produced garbage.
;; Fix: check forms/syntax-quote? before (map? form) in expand-sq.
;; ---------------------------------------------------------------------------

#?(:clj
(deftest nested-syntax-quote-expands-without-error
  (testing "nested backtick does not crash or produce map output"
    (let [forms (core/meme->forms "``x")
          expanded (expander/expand-forms forms)]
      (is (seq? (first expanded)) "nested syntax-quote should expand to a seq form")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: MemeRaw inside #() body was corrupted by normalize-bare-percent.
;; normalize-bare-percent dispatches on (map? form) and uses (into {} ...) which
;; destroys the MemeRaw defrecord, replacing it with a plain map. This caused
;; ClassCastException at runtime when the anonymous function was called.
;; Fix: check forms/raw? before (map? form) in normalize-bare-percent and
;; find-percent-params.
;; ---------------------------------------------------------------------------

#?(:clj
(deftest meme-raw-in-anon-fn-survives-normalization
  (testing "hex literal inside #() preserves its value through expansion"
    (let [forms (core/meme->forms "#(+(% 0xFF))")
          expanded (expander/expand-forms forms)
          f (first expanded)]
      (is (seq? f) "should be (fn [%1] ...)")
      (is (= 'fn (first f)))
      ;; The 0xFF should resolve to 255, not to {:value 255 :raw "0xFF"}
      (is (some #(= 255 %) (flatten (map #(if (seq? %) (seq %) [%]) (rest f))))
          "0xFF must be resolved to 255, not leaked as a MemeRaw map")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: #?@(:clj [2 3]) inside a collection did not splice.
;; parse-reader-cond-eval returned the matched vector as a single form,
;; so [1 #?@(:clj [2 3]) 4] produced [1 [2 3] 4] instead of [1 2 3 4].
;; Fix: wrap splice matches with a splice-result marker, detect in
;; parse-forms-until to splice instead of conj.
;; ---------------------------------------------------------------------------

(deftest splice-reader-conditional-in-vector
  (testing "#?@ splices into surrounding vector"
    (is (= [[1 2 3 4]]
           (core/meme->forms #?(:clj  "[1 #?@(:clj [2 3]) 4]"
                                :cljs "[1 #?@(:cljs [2 3]) 4]")))
        "#?@ should splice elements into the vector"))
  (testing "#?@ splices into surrounding map (pairs)"
    (is (= [{:a 1 :b 2}]
           (core/meme->forms #?(:clj  "{#?@(:clj [:a 1 :b 2])}"
                                :cljs "{#?@(:cljs [:a 1 :b 2])}")))
        "#?@ should splice key-value pairs into the map"))
  (testing "#?@ at top level returns individual forms"
    (is (= [1 2]
           (core/meme->forms #?(:clj  "#?@(:clj [1 2])"
                                :cljs "#?@(:cljs [1 2])")))
        "#?@ at top level should return elements separately"))
  (testing "#?@ with non-matching platform returns nothing"
    (is (= [[1 3]]
           (core/meme->forms #?(:clj  "[1 #?@(:cljs [2]) 3]"
                                :cljs "[1 #?@(:clj [2]) 3]")))
        "non-matching #?@ should contribute no elements"))
  (testing "#?@ with non-sequential value errors"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"Splicing reader conditional value must be"
          (core/meme->forms #?(:clj  "[#?@(:clj 42)]"
                                :cljs "[#?@(:cljs 42)]"))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: hex/octal/radix literals exceeding Long.MAX_VALUE must promote
;; to BigInt, matching Clojure's reader behavior.
;; Bug: Long/parseLong threw NumberFormatException for values > Long.MAX_VALUE.
;; Fix: use BigInteger + reduce (longValue when < 64 bits, BigInt otherwise).
;; ---------------------------------------------------------------------------

#?(:clj
(deftest large-hex-octal-radix-promote-to-bigint
  (testing "hex at Long.MAX_VALUE stays Long"
    (let [r (first (core/meme->forms "0x7FFFFFFFFFFFFFFF"))]
      (is (forms/raw? r))
      (is (= Long/MAX_VALUE (:value r)))))
  (testing "hex above Long.MAX_VALUE promotes to BigInt"
    (let [r (first (core/meme->forms "0x8000000000000000"))]
      (is (forms/raw? r))
      (is (= 9223372036854775808N (:value r)))))
  (testing "hex 0xFFFFFFFFFFFFFFFF promotes to BigInt"
    (let [r (first (core/meme->forms "0xFFFFFFFFFFFFFFFF"))]
      (is (forms/raw? r))
      (is (= 18446744073709551615N (:value r)))))
  (testing "negative hex at Long.MIN_VALUE stays Long"
    (let [r (first (core/meme->forms "-0x8000000000000000"))]
      (is (forms/raw? r))
      (is (= Long/MIN_VALUE (:value r)))))
  (testing "large octal promotes to BigInt"
    (let [r (first (core/meme->forms "01777777777777777777777"))]
      (is (forms/raw? r))
      (is (= 18446744073709551615N (:value r)))))
  (testing "large radix promotes to BigInt"
    (let [r (first (core/meme->forms "36rZZZZZZZZZZZZZ"))]
      (is (forms/raw? r))
      (is (= 170581728179578208255N (:value r)))))))
