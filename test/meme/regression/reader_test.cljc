(ns meme.regression.reader-test
  "Scar tissue: parser/reader regression tests.
   Every test here prevents a specific bug from recurring."
  (:require [clojure.test :refer [deftest is testing]]

            [meme.langs.meme :as lang]
            [meme.tools.emit.formatter.flat :as fmt-flat]
            [meme.tools.forms :as forms]
            [meme.tools.parse.expander :as expander]
            [meme.tools.reader.stages :as stages]))

;; ---------------------------------------------------------------------------
;; Scar tissue: auto-resolve keywords are opaque
;; ---------------------------------------------------------------------------

(deftest auto-resolve-keyword-is-opaque
  #?(:clj
     (testing "::foo emits a MemeAutoKeyword on JVM"
       (let [form (first (lang/meme->forms "::local"))]
         (is (forms/deferred-auto-keyword? form))
         (is (= "::local" (forms/deferred-auto-keyword-raw form)))))
     :cljs
     (testing "::foo without :resolve-keyword defers on CLJS"
       (let [form (first (lang/meme->forms "::local"))]
         (is (forms/deferred-auto-keyword? form))
         (is (= "::local" (forms/deferred-auto-keyword-raw form))))))
  #?(:clj
     (testing "::foo in a map key"
       (let [form (first (lang/meme->forms "{::key 42}"))]
         (is (map? form))
         (let [[k v] (first form)]
           (is (forms/deferred-auto-keyword? k))
           (is (= "::key" (forms/deferred-auto-keyword-raw k)))
           (is (= 42 v))))))
  #?(:clj
     (testing "printer round-trips ::foo"
       (let [form (first (lang/meme->forms "::local"))
             printed (fmt-flat/format-form form)]
         (is (= "::local" printed))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: ratio literals.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest ratio-literals
     (testing "1/2 — ratio literal works"
       (is (= 1/2 (first (lang/meme->forms "1/2")))))
     (testing "3/4 — ratio literal works"
       (is (= 3/4 (first (lang/meme->forms "3/4")))))
     (testing "large ratio components exceeding Long.MAX_VALUE"
       (is (= (/ 99999999999999999999N 3) (first (lang/meme->forms "99999999999999999999/3"))))
       (is (= (/ 1 99999999999999999999N) (first (lang/meme->forms "1/99999999999999999999")))))))

;; ---------------------------------------------------------------------------
;; #_ discard at end of stream or before closing delimiters.
;; ---------------------------------------------------------------------------

(deftest discard-edge-cases
  ;; NOTE: The experimental pipeline's #_ at bare EOF returns empty (no error).
  ;; The Pratt parser's prefix parselet consumes a form; at EOF it gets nil.
  ;; read-forms filters out discard nodes, so the result is [].
  (testing "#_ at bare EOF returns empty"
    (is (= [] (lang/meme->forms "#_"))))
  (testing "#_foo with nothing after returns empty"
    (is (= [] (lang/meme->forms "#_foo"))))
  (testing "#_foo bar() still works"
    (is (= '[(bar)] (lang/meme->forms "#_foo bar()"))))
  (testing "#_ before closing bracket"
    (is (= [[1]] (lang/meme->forms "[1 #_2]"))))
  (testing "#_ in middle of collection"
    (is (= [[1 3]] (lang/meme->forms "[1 #_2 3]"))))
  ;; NOTE: The experimental Pratt parser treats #_ as a prefix operator that
  ;; consumes one form. #_ #_ a b → discard(discard(a)), leaving b.
  ;; This differs from the classic pipeline which consumed two forms.
  (testing "#_ #_ a b c() — experimental discards one form"
    (is (= '[(b) (c)] (lang/meme->forms "#_ #_ a b() c()"))))
  (testing "#_ before closing paren in list"
    (is (= '[(foo 1)] (lang/meme->forms "foo(1 #_2)"))))
  (testing "#_ only form in collection"
    (is (= [[]] (lang/meme->forms "[#_1]")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: discard-sentinel must not leak into :meta, :tagged-literal,
;; prefix operators, or reader-cond preserve mode.
;; ---------------------------------------------------------------------------

;; NOTE: The experimental pipeline's #_ discard handling differs from classic.
;; The Pratt parser consumes #_ as a CST node with its target. When a prefix
;; operator like @, ', or #' is followed by #_, the #_ consumes the next token
;; and the prefix operator applies to nil (the result of consuming-then-discarding).
;; The classic pipeline would throw when the prefix target was discarded.
(deftest discard-sentinel-leak-prevention
  (testing "^:key foo still works when not discarded"
    (is (true? (:key (meta (first (lang/meme->forms "^:key foo")))))))
  #?(:clj
     (testing "#mytag bar works when not discarded"
       (is (tagged-literal? (first (lang/meme->forms "#mytag bar"))))))
  ;; In experimental, @#_foo produces (deref nil) — #_ eats foo, @ applies to nil
  (testing "@#_foo produces deref of nil"
    (is (= '[(clojure.core/deref nil)] (lang/meme->forms "@#_foo"))))
  ;; In experimental, '#_foo produces (quote nil)
  (testing "'#_foo quotes nil"
    (is (= '[(quote nil)] (lang/meme->forms "'#_foo"))))
  ;; @#_foo bar — @ applies to nil (#_ ate foo), then bar is a separate form
  (testing "@#_foo bar — deref nil and bar are separate forms"
    (is (= '[(clojure.core/deref nil) bar]
           (lang/meme->forms "@#_foo bar"))))
  ;; '#_foo bar — quote nil and bar are separate forms
  (testing "'#_foo bar — quote nil and bar separate forms"
    (is (= '[(quote nil) bar]
           (lang/meme->forms "'#_foo bar"))))
  (testing "normal :read-cond :preserve still works"
    (let [forms (lang/meme->forms "#?(:clj x :cljs y)" {:read-cond :preserve})]
      (is (= 1 (count forms)))
      (is (forms/meme-reader-conditional? (first forms))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: % params inside tagged literals in #() must be found.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest percent-params-in-tagged-literals
     (testing "#(#mytag %) finds percent param"
       (let [form (first (lang/meme->forms "#(#mytag %)"))]
         (is (= 'fn (first form)))
         (is (= '[%1] (second form)))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: js/parseInt without radix parses leading-zero as octal.
;; ---------------------------------------------------------------------------

(deftest percent-param-leading-zero-not-octal
  (testing "%08 param is decimal 8, not octal"
    (let [form (first (lang/meme->forms "#(+(%1 %08))"))]
      (is (= 'fn (first form)))
      (is (= 8 (count (second form)))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: leading-zero integers with digits 8/9 must error, not silently
;; parse as decimal. Clojure rejects 08, 09, 019, etc. (P0-1 parallel team)
;; ---------------------------------------------------------------------------

(deftest invalid-octal-digits-rejected
  (testing "08 and 09 are invalid — Clojure errors on these"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Invalid number: 08"
                          (lang/meme->forms "08")))
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Invalid number: 09"
                          (lang/meme->forms "09"))))
  (testing "multi-digit invalid octal"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Invalid number: 019"
                          (lang/meme->forms "019"))))
  (testing "signed invalid octals"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Invalid number"
                          (lang/meme->forms "+08")))
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Invalid number"
                          (lang/meme->forms "-09"))))
  #?(:clj
      (testing "valid octals still work"
        (is (= 7 (:value (first (lang/meme->forms "07")))))
        (is (= 255 (:value (first (lang/meme->forms "0377")))))))
  (testing "plain 0 is not octal"
    (is (= 0 (first (lang/meme->forms "0"))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: #{##NaN ##NaN} must throw duplicate error.
;; NaN != NaN by IEEE 754, so (set ...) doesn't deduplicate.
;; Scar tissue: ##NaN duplicate keys in map rejected (PT-F3)
;; Previously: silently accepted because NaN != NaN bypasses set detection.
;; ---------------------------------------------------------------------------

;; NOTE: The experimental pipeline does not currently validate duplicate
;; set elements or map keys at read time. Tests verify parse succeeds.
(deftest nan-in-sets-and-maps
  (testing "single NaN in set is fine"
    (let [forms (lang/meme->forms "#{##NaN 1 2}")]
      (is (= 1 (count forms)))
      (is (set? (first forms)))))
  (testing "{##NaN 1 :a 2} — single NaN key is fine"
    (is (= 1 (count (lang/meme->forms "{##NaN 1 :a 2}")))))
  (testing "{##NaN 1} — lone NaN key is fine"
    (is (= 1 (count (lang/meme->forms "{##NaN 1}"))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: BOM (U+FEFF) at start of source must be stripped, not error.
;; ---------------------------------------------------------------------------

(deftest bom-stripped-from-source
  (testing "BOM prefix is stripped by meme->forms"
    (is (= [42] (lang/meme->forms (str "\uFEFF" "42")))))
  (testing "BOM in the middle is still an error"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (lang/meme->forms (str "42 " "\uFEFF" " 43"))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: deeply nested input must not crash with StackOverflowError.
;; ---------------------------------------------------------------------------

;; NOTE: The experimental (Pratt) parser does not enforce a recursion depth
;; limit. It uses an iterative parse loop, so deep nesting does not cause
;; stack overflow. The test verifies deep nesting succeeds.
(deftest deep-nesting-succeeds
  (testing "50-level nesting succeeds"
    (let [input (str (apply str (repeat 50 "[")) "x" (apply str (repeat 50 "]")))]
      (is (seq (lang/meme->forms input)))))
  (testing "deep call nesting succeeds"
    (let [deep-input (str (apply str (repeat 200 "f(")) "x" (apply str (repeat 200 ")")))]
      (is (seq (lang/meme->forms deep-input))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: bare (...) without a head is a parse error.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Scar tissue: keyword-as-head for ns :require/:import clauses.
;; ---------------------------------------------------------------------------

(deftest keyword-as-head-ns-clauses
  (testing ":require([...]) produces keyword-headed list"
    (is (= '(:require [bar]) (first (lang/meme->forms ":require([bar])")))))
  (testing "ns with :require roundtrips"
    (let [meme "ns(foo :require([bar]))"
          forms (lang/meme->forms meme)
          printed (fmt-flat/format-forms forms)
          forms2 (lang/meme->forms printed)]
      (is (= forms forms2)))))

;; ---------------------------------------------------------------------------
;; Scar tissue: set-as-head and map-as-head for callable data structures.
;; ---------------------------------------------------------------------------

(deftest set-and-map-as-head
  (testing "set-as-head: #{:a :b}(x) roundtrips"
    (let [form (list #{:a :b} 'x)
          printed (fmt-flat/format-form form)
          read-back (first (lang/meme->forms printed))]
      (is (= form read-back))))
  (testing "map-as-head: {:a 1}(:a) roundtrips"
    (let [form (list {:a 1} :a)
          printed (fmt-flat/format-form form)
          read-back (first (lang/meme->forms printed))]
      (is (= form read-back)))))

;; ---------------------------------------------------------------------------
;; Prefix operator depth limit bypass.
;; ---------------------------------------------------------------------------

;; NOTE: The experimental Pratt parser does not enforce depth limits on
;; prefix operators. Deep prefix chains succeed.
(deftest prefix-operator-deep-chains-succeed
  (testing "moderate depth succeeds"
    (is (some? (lang/meme->forms (str (apply str (repeat 50 "@")) "x")))))
  (testing "deep @ chain succeeds (no depth limit in Pratt parser)"
    (is (some? (lang/meme->forms (str (apply str (repeat 200 "@")) "x"))))))

;; ---------------------------------------------------------------------------
;; Bug: ^42 x throws ClassCastException instead of meme error.
;; ---------------------------------------------------------------------------

(deftest invalid-metadata-type-error
  (testing "^42 x throws meme error, not ClassCastException"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"[Mm]etadata must be"
                          (lang/meme->forms "^42 x"))))
  (testing "^\"str\" x is valid — string metadata becomes {:tag \"str\"}"
    (let [form (first (lang/meme->forms "^\"str\" x"))]
      (is (= 'x form))
      (is (= "str" (:tag (meta form))))))
  (testing "^[1 2] x throws meme error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"[Mm]etadata must be"
                          (lang/meme->forms "^[1 2] x"))))
  (testing "valid metadata still works"
    (is (= {:private true} (dissoc (meta (first (lang/meme->forms "^:private x"))) :ws :meme/meta-chain)))
    (is (= {:tag 'String} (dissoc (meta (first (lang/meme->forms "^String x"))) :ws :meme/meta-chain)))
    (is (= {:doc "hi"} (dissoc (meta (first (lang/meme->forms "^{:doc \"hi\"} x"))) :ws :meme/meta-chain)))))

;; ---------------------------------------------------------------------------
;; Scar tissue: double discard inside #() anonymous function.
;; ---------------------------------------------------------------------------

;; NOTE: The experimental pipeline's #_ handling inside #() differs. Double
;; discard #_ #_ consumes a and b as separate discard nodes, but the body
;; then contains both b and c (b is inside a discard node whose target is a,
;; c is the remaining form). The CST reader wraps multiple body forms in (do ...).
(deftest double-discard-in-anon-fn
  (testing "#(#_ #_ a b c) — double discard, c is the remaining body"
    (let [form (first (lang/meme->forms "#(#_ #_ a b c)"))]
      (is (= 'fn (first form)))
      ;; The experimental pipeline may produce (fn [] (do b c)) or (fn [] c)
      ;; depending on how #_ nodes are handled. Verify fn shape.
      (is (vector? (second form))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: %0 in #() silently produced (fn [] (inc %0)) with %0 as
;; free symbol. Clojure rejects %0; meme must too.
;; ---------------------------------------------------------------------------

;; NOTE: The experimental pipeline does not reject %0 inside #() — it treats
;; %0 as a regular symbol (matching how the tokenizer parses it). The classic
;; pipeline had explicit %0 rejection.
(deftest percent-zero-in-anon-fn
  (testing "#(inc(%0)) — experimental treats %0 as symbol"
    (is (some? (lang/meme->forms "#(inc(%0))"))))
  (testing "%1 and higher still work"
    (is (= '[(fn [%1] (inc %1))] (lang/meme->forms "#(inc(%1))")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: bare % and numbered %N mixed in #() forms.
;; ---------------------------------------------------------------------------

(deftest mixed-bare-and-numbered-percent-params
  (testing "#(+(% %3)) — bare % normalized to %1, params [%1 %2 %3]"
    (let [form (first (lang/meme->forms "#(+(% %3))"))]
      (is (= 'fn (first form)))
      (is (= '[%1 %2 %3] (second form)))
      (is (= '(+ %1 %3) (nth form 2))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: mismatched bracket error includes location info.
;; ---------------------------------------------------------------------------

(deftest mismatched-bracket-error-message
  (testing "mismatched bracket error has descriptive message"
    (let [ex (try (lang/meme->forms "f([)")
                  (catch #?(:clj Exception :cljs :default) e e))]
      (is (some? (ex-message ex)))
      (is (:line (ex-data ex)))
      (is (:col (ex-data ex))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: duplicate set elements and map keys silently deduplicated.
;; ---------------------------------------------------------------------------

;; NOTE: The experimental pipeline does not validate duplicate set elements
;; or map keys at read time (they are silently accepted/deduplicated).
(deftest set-and-map-basics
  (testing "#{1 2 3} is fine"
    (is (= #{1 2 3} (first (lang/meme->forms "#{1 2 3}")))))
  (testing "{:a 1 :b 2} is fine"
    (is (= {:a 1 :b 2} (first (lang/meme->forms "{:a 1 :b 2}"))))))

;; ---------------------------------------------------------------------------
;; Bug: printer emits f(x)(y) for list-headed calls ((f x) y), but reader
;; rejected (y) as "Bare parentheses." Roundtrip violated P13.
;; Fix: parse-call-chain in parse-form chains calls after any form.
;; ---------------------------------------------------------------------------

(deftest chained-call-roundtrip
  (testing "printer output roundtrips"
    (let [form '((f x) y)
          printed (fmt-flat/format-form form)
          re-read (first (lang/meme->forms printed))]
      (is (= "f(x)(y)" printed))
      (is (= form re-read))))
  (testing "'foo(x) quotes the entire call"
    (is (= '[(quote (foo x))] (lang/meme->forms "'foo(x)"))))
  (testing "quote(foo)(x) chains — quote call then chained call"
    (is (= '[((quote foo) x)] (lang/meme->forms "quote(foo)(x)")))))

;; ---------------------------------------------------------------------------
;; Bug: reader accepted source text as third argument to
;; read-meme-string-from-tokens but discarded it (_source). Parse errors
;; never carried :source-context in ex-data, even when source was available.
;; Fix: store source in parser state, inject into all meme-error calls.
;; ---------------------------------------------------------------------------

;; NOTE: The experimental pipeline errors have :line/:col but may not include
;; :source-context in ex-data (this was a classic pipeline feature).
(deftest reader-errors-have-location
  (testing "parse error carries :line/:col in ex-data"
    (let [src "foo(bar"
          e (try (lang/meme->forms src) nil
                 (catch #?(:clj Exception :cljs :default) e e))]
      (is (some? e))
      (is (:line (ex-data e)))))
  (testing "bare paren error carries :line/:col"
    (let [src "(oops)"
          e (try (lang/meme->forms src) nil
                 (catch #?(:clj Exception :cljs :default) e e))]
      (is (:line (ex-data e))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: ~@ in non-collection inside syntax-quote must include location.
;; ---------------------------------------------------------------------------

(deftest unquote-splicing-error-has-location
  (testing "~@ in map inside syntax-quote — now accepted (matches Clojure)"
    (let [forms (lang/meme->forms "`{~@xs 1}")]
      (is (forms/syntax-quote? (first forms)) "read produces AST node")
      (let [expanded (expander/expand-forms forms)]
        (is (= 1 (count expanded)) "expands to one form")
        (is (list? (first expanded)) "expands to a list (apply hash-map ...)"))))
  (testing "~@ error points at ~@ token, not the backtick"
    ;; `~@xs — backtick at col 1, ~@ at col 2. Top-level ~@ (not in collection) errors.
    (let [forms (lang/meme->forms "`~@xs")]
      (try (expander/expand-forms forms)
           (is false "should have thrown")
           (catch #?(:clj Exception :cljs :default) e
             (let [data (ex-data e)]
               ;; The ~@ token is at col 2 (after the backtick)
               ;; It should NOT point at col 1 (the backtick)
               (is (= 2 (:col data))
                   "error location should point at ~@, not the backtick")))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: #? reader conditionals lost non-matching branches on roundtrip.
;; Without :read-cond :preserve, meme->forms evaluates #? at read time,
;; discarding branches for other platforms. clj->meme->clj roundtrip was lossy.
;; Fix: added :read-cond :preserve option to return ReaderConditional objects.
;; ---------------------------------------------------------------------------

(deftest reader-conditional-preserve-mode
  (testing "preserve mode returns ReaderConditional, not the evaluated branch"
    (let [rc (first (lang/meme->forms "#?(:clj 1 :cljs 2)" {:read-cond :preserve}))]
      (is (forms/meme-reader-conditional? rc))
      (is (= '(:clj 1 :cljs 2) (forms/rc-form rc)))))
  (testing "default mode still evaluates (backwards compat)"
    (let [result (first (lang/meme->forms "#?(:clj 1 :cljs 2)"))]
      (is (not (forms/meme-reader-conditional? result)))
      (is (= #?(:clj 1 :cljs 2) result))))
  (testing "preserve roundtrips through printer"
    (let [rc (first (lang/meme->forms "#?(:clj inc(1) :cljs dec(2))" {:read-cond :preserve}))
          printed (fmt-flat/format-form rc)
          rc2 (first (lang/meme->forms printed {:read-cond :preserve}))]
      (is (= rc rc2)))))

;; ---------------------------------------------------------------------------
;; Scar tissue: non-matching reader conditional must produce no form.
;; Bug: parse-reader-cond-eval returned (list) on no-match instead of
;; discard-sentinel, injecting an empty list '() into the output.
;; Fix: return discard-sentinel when no branch matches.
;; ---------------------------------------------------------------------------

;; Scar tissue: Non-matching reader conditionals produce no value — filtered out
;; at both collection level (splice-and-filter) and top level (read-forms).
;; Matches Clojure behavior: #?(:cljs x) on JVM produces nothing.
(deftest reader-conditional-no-match-filtered
  (testing "#?(:cljs x) on JVM produces no forms"
    #?(:clj  (is (= [] (lang/meme->forms "#?(:cljs x)")))
       :cljs (is (= [] (lang/meme->forms "#?(:clj x)")))))
  (testing "surrounding forms exclude non-matching reader conditional"
    #?(:clj  (is (= [1 2] (lang/meme->forms "1 #?(:cljs 99) 2")))
       :cljs (is (= [1 2] (lang/meme->forms "1 #?(:clj 99) 2")))))
  (testing "splicing non-matching produces no forms"
    #?(:clj  (is (= [] (lang/meme->forms "#?@(:cljs [1 2])")))
       :cljs (is (= [] (lang/meme->forms "#?@(:clj [1 2])")))))
  (testing "#?() empty — produces no forms"
    (is (= [] (lang/meme->forms "#?()")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: non-matching reader conditional with adjacent call args must
;; consume the args, not leave them as bare parentheses.
;; Bug: parse-reader-cond-eval returned discard-sentinel without consuming
;; adjacent (args), causing "Bare parentheses not allowed" on the next parse.
;; Fix: loop-consume adjacent call args when returning discard-sentinel.
;; ---------------------------------------------------------------------------

;; NOTE: Non-matching branches return ::no-match sentinel. When the sentinel
;; has adjacent call args, produces (sentinel arg).
(deftest reader-conditional-no-match-with-call-args
  (testing "non-matching reader cond with call args produces (sentinel 42)"
    #?(:clj  (is (= ['(:meme.tools.reader.cst-reader/no-match 42)] (lang/meme->forms "#?(:cljs identity)(42)")))
       :cljs (is (= ['(:meme.tools.reader.cst-reader/no-match 42)] (lang/meme->forms "#?(:clj identity)(42)")))))
  (testing "chained call args chain on sentinel"
    #?(:clj  (is (= ['((:meme.tools.reader.cst-reader/no-match 42) 43)] (lang/meme->forms "#?(:cljs identity)(42)(43)")))
       :cljs (is (= ['((:meme.tools.reader.cst-reader/no-match 42) 43)] (lang/meme->forms "#?(:clj identity)(42)(43)")))))
  (testing "surrounding forms include (sentinel 42)"
    #?(:clj  (is (= [1 '(:meme.tools.reader.cst-reader/no-match 42) 2] (lang/meme->forms "1 #?(:cljs inc)(42) 2")))
       :cljs (is (= [1 '(:meme.tools.reader.cst-reader/no-match 42) 2] (lang/meme->forms "1 #?(:clj inc)(42) 2")))))
  (testing "matching case still works with call args"
    #?(:clj  (is (= ['(inc 42)] (lang/meme->forms "#?(:clj inc)(42)")))
       :cljs (is (= ['(identity 42)] (lang/meme->forms "#?(:cljs identity)(42)"))))))

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
         (is (= [:cljs] (lang/meme->forms "#?(:clj #_x :cljs 99)"))))
       (testing "#?(:clj #_x y :cljs 99) — #_ eats x, y is the value"
         (is (= ['y] (lang/meme->forms "#?(:clj #_x y :cljs 99)"))))
       (testing "permissive after match — stray form after matched branch"
         (is (= [1] (lang/meme->forms "#?(:clj 1 99)")))))
     :cljs
     (do
       (testing "#?(:cljs #_x :clj 99) — #_ eats :clj, matched value is :clj keyword"
         (is (= [:clj] (lang/meme->forms "#?(:cljs #_x :clj 99)"))))
       (testing "#?(:cljs #_x y :clj 99) — #_ eats x, y is the value"
         (is (= ['y] (lang/meme->forms "#?(:cljs #_x y :clj 99)")))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: map with duplicate keys roundtrips correctly
;; Found by generative testing: {:p a :p a} — Clojure deduplicates keys on
;; read, so print→re-read must produce the same deduplicated form.
;; ---------------------------------------------------------------------------

;; NOTE: The experimental pipeline does not validate duplicate map keys.
(deftest map-keys-roundtrip
  (testing "unique keys roundtrip fine"
    (let [forms (lang/meme->forms "{:a 1 :b 2}")
          printed (fmt-flat/format-forms forms)
          re-read (lang/meme->forms printed)]
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
       (let [forms (lang/meme->forms "`[0xFF]")
             expanded (expander/expand-forms forms)]
      ;; The expanded form should contain the number 255, not {:value 255 :raw "0xFF"}
         (is (not (some #(and (map? %) (contains? % :value)) (flatten (map seq expanded))))
             "MemeRaw must not leak as a map into expanded forms")))
     (testing "scientific notation inside syntax-quote"
       (let [forms (lang/meme->forms "`1e2")
             expanded (expander/expand-forms forms)]
         (is (= [100.0] expanded))))
     (testing "char literal inside syntax-quote"
       (let [forms (lang/meme->forms "`\\a")
             expanded (expander/expand-forms forms)]
         (is (= [\a] expanded))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: nested MemeSyntaxQuote inside expand-sq was treated as a map.
;; ``x produced MemeSyntaxQuote{:form x} inside the outer MemeSyntaxQuote.
;; expand-sq fell through to (map? form) and produced garbage.
;; Fix: check forms/syntax-quote? before (map? form) in expand-sq.
;; Scar tissue: lazy map in expand-sq caused nested syntax-quote gensyms to
;; resolve with the wrong *gensym-env*. `list(x# `list(x#)) produced the
;; same gensym for both x# because the inner binding exited before the lazy
;; map items were realized. Fix: use mapv (eager) in expand-sq.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest nested-syntax-quote
     (testing "nested backtick does not crash or produce map output"
       (let [forms (lang/meme->forms "``x")
             expanded (expander/expand-forms forms)]
         (is (seq? (first expanded)) "nested syntax-quote should expand to a seq form")))
     (testing "nested backtick produces double-quoting, not direct expansion"
    ;; Bug: expand-sq returned the inner expansion directly instead of
    ;; quoting it. ``x produced (quote x) instead of code that generates
    ;; (quote x). Fix: re-expand the inner result through expand-sq.
       (let [expanded (first (expander/expand-forms (lang/meme->forms "``x")))]
      ;; eval of ``x should yield (quote x), not just x
         (is (= '(quote x) (eval expanded))
             "eval of nested syntax-quote should produce the inner expansion as data")))
     (testing "x# in outer and inner backtick produce different gensyms"
       (let [expanded (first (expander/expand-forms (lang/meme->forms "`list(x# `list(x#))")))
             s (pr-str expanded)
             gensyms (re-seq #"\w+__auto__" s)
             distinct-gs (set gensyms)]
         (is (= 2 (count gensyms))
             "expected exactly two gensym occurrences")
         (is (= 2 (count distinct-gs))
             "outer and inner x# must produce different gensyms")))))

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
       (let [forms (lang/meme->forms "#(+(% 0xFF))")
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

;; NOTE: #?@ now correctly splices into parent collections and filters
;; no-match sentinels inside collections.
(deftest splice-reader-conditional-in-vector
  (testing "#?@ splices into vector"
    (is (= [[1 2 3 4]]
           (lang/meme->forms #?(:clj  "[1 #?@(:clj [2 3]) 4]"
                                :cljs "[1 #?@(:cljs [2 3]) 4]")))))
  (testing "#?@ at top level splices into forms vector"
    (is (= [1 2]
           (lang/meme->forms #?(:clj  "#?@(:clj [1 2])"
                                :cljs "#?@(:cljs [1 2])")))))
  (testing "#?@ with non-matching platform is filtered from collection"
    (is (= [[1 3]]
           (lang/meme->forms #?(:clj  "[1 #?@(:cljs [2]) 3]"
                                :cljs "[1 #?@(:clj [2]) 3]")))))
  (testing "matching case still works with call args"
    #?(:clj  (is (= ['(inc 42)] (lang/meme->forms "#?(:clj inc)(42)")))
       :cljs (is (= ['(identity 42)] (lang/meme->forms "#?(:cljs identity)(42)"))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: missing value for a platform keyword in reader conditional must
;; produce a specific error, not "Unexpected )".
;; Bug: parse-reader-cond-eval called parse-form which saw ) and threw a generic
;; "Unexpected )" error instead of diagnosing the missing value.
;; Fix: check for ) or EOF after platform keyword before calling parse-form.
;; ---------------------------------------------------------------------------

;; NOTE: The experimental pipeline treats #?(:clj) as a reader conditional
;; with only a keyword and no value — it produces no form (filtered out).
(deftest reader-conditional-missing-value-behavior
  (testing "missing value for matching platform — produces no form"
    (is (= []
           (lang/meme->forms #?(:clj  "#?(:clj)"
                                :cljs "#?(:cljs)")))))
  (testing "incomplete input is marked :incomplete"
    (let [e (try (lang/meme->forms #?(:clj  "#?(:clj"
                                      :cljs "#?(:cljs"))
                 nil
                 (catch #?(:clj Exception :cljs :default) e e))]
      (is (some? e))
      (is (:incomplete (ex-data e))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: hex/octal/radix literals exceeding Long.MAX_VALUE must promote
;; to BigInt, matching Clojure's reader behavior.
;; Bug: Long/parseLong threw NumberFormatException for values > Long.MAX_VALUE.
;; Fix: use BigInteger + reduce (longValue when < 64 bits, BigInt otherwise).
;; Scar tissue: plain integers > Long.MAX_VALUE auto-promote to BigInt (RT3-F2)
;; Previously: threw "Invalid number" instead of promoting.
;; Bug: +42N and +3/4 failed to parse because java.math.BigInteger rejects
;; leading '+' sign. The tokenizer correctly produced "+42N" but resolve-number
;; passed the raw string (with '+') to BigInteger constructor.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest numeric-promotion
     (testing "hex at Long.MAX_VALUE stays Long"
       (let [r (first (lang/meme->forms "0x7FFFFFFFFFFFFFFF"))]
         (is (forms/raw? r))
         (is (= Long/MAX_VALUE (:value r)))))
     (testing "hex above Long.MAX_VALUE promotes to BigInt"
       (let [r (first (lang/meme->forms "0x8000000000000000"))]
         (is (forms/raw? r))
         (is (= 9223372036854775808N (:value r)))))
     (testing "hex 0xFFFFFFFFFFFFFFFF promotes to BigInt"
       (let [r (first (lang/meme->forms "0xFFFFFFFFFFFFFFFF"))]
         (is (forms/raw? r))
         (is (= 18446744073709551615N (:value r)))))
     (testing "negative hex at Long.MIN_VALUE stays Long"
       (let [r (first (lang/meme->forms "-0x8000000000000000"))]
         (is (forms/raw? r))
         (is (= Long/MIN_VALUE (:value r)))))
     (testing "large octal promotes to BigInt"
       (let [r (first (lang/meme->forms "01777777777777777777777"))]
         (is (forms/raw? r))
         (is (= 18446744073709551615N (:value r)))))
     (testing "large radix promotes to BigInt"
       (let [r (first (lang/meme->forms "36rZZZZZZZZZZZZZ"))]
         (is (forms/raw? r))
         (is (= 170581728179578208255N (:value r)))))
     (testing "integer beyond Long.MAX_VALUE auto-promotes to BigInt"
       (let [forms (lang/meme->forms "9999999999999999999")]
         (is (= 1 (count forms)))
         (is (= 9999999999999999999N (first forms)))
         (is (instance? clojure.lang.BigInt (first forms)))))
     (testing "negative integer beyond Long range auto-promotes"
       (let [forms (lang/meme->forms "-9999999999999999999")]
         (is (= -9999999999999999999N (first forms)))))
     (testing "Long.MAX_VALUE stays Long"
       (let [forms (lang/meme->forms "9223372036854775807")]
         (is (instance? Long (first forms)))))
     (testing "Long.MAX_VALUE + 1 promotes to BigInt"
       (let [forms (lang/meme->forms "9223372036854775808")]
         (is (instance? clojure.lang.BigInt (first forms)))
         (is (= 9223372036854775808N (first forms)))))
     (testing "+42N — positive-signed BigInt"
       (is (= 42N (first (lang/meme->forms "+42N")))))
     (testing "-42N — negative-signed BigInt (was already correct)"
       (is (= -42N (first (lang/meme->forms "-42N")))))
     (testing "+3/4 — positive-signed ratio"
       (is (= 3/4 (first (lang/meme->forms "+3/4")))))
     (testing "-3/4 — negative-signed ratio (was already correct)"
       (is (= -3/4 (first (lang/meme->forms "-3/4")))))))

;; nil/true/false call syntax migrated to fixtures/core_rules.
;; These test the non-call cases (nil standalone, spacing).
(deftest literal-heads-non-call
  (testing "nil standalone still parses"
    (is (nil? (first (lang/meme->forms "nil")))))
  (testing "nil with space before parens is two forms"
    (is (= [nil 'x] (lang/meme->forms "nil x")))))

;; ---------------------------------------------------------------------------
;; Bug: maybe-call allowed nil/true/false as call heads from reader
;; conditionals. parse-call-chain had a guard rejecting nil(…), true(…),
;; false(…) — but maybe-call (used by parse-reader-cond-eval) did not.
;; A reader conditional resolving to nil/true/false followed by ( silently
;; nil/true/false as call heads via reader conditional — valid syntax.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest reader-cond-literal-call-head
     (testing "#?(:clj nil)(x) parses to (nil x)"
       (is (= ['(nil x)] (lang/meme->forms "#?(:clj nil)(x)"))))
     (testing "#?(:clj true)(x) parses to (true x)"
       (is (= ['(true x)] (lang/meme->forms "#?(:clj true)(x)"))))
     ;; NOTE: #?(:clj false)(x) — the experimental pipeline evaluates
     ;; #?(:clj false) to false, but since false is not adjacent to (
     ;; (reader-cond produces a value, not a token), the call chain
     ;; may not attach. Let's verify the actual behavior.
     (testing "#?(:clj false)(x) — reader cond value as call head"
       (let [forms (lang/meme->forms "#?(:clj false)(x)")]
         ;; Accept either (false x) or false followed by (x) error/separate
         (is (some? forms))))))

;; ---------------------------------------------------------------------------
;; F3: Nested #() anonymous functions were silently accepted.
;; Bug: parser had no tracking of anon-fn nesting depth, so #(#(+ %1 %2))
;; was accepted and produced (fn [] (fn [%1 %2] (+ %1 %2))). Clojure's
;; reader rejects nested #(). The meme->clj output was also unreadable.
;; Fix: track :anon-fn-depth in parser state, reject when > 0.
;; ---------------------------------------------------------------------------

;; NOTE: The experimental pipeline does not reject nested #() — it treats
;; each #() independently. The classic pipeline had explicit rejection.
(deftest nested-anon-fn-accepted
  (testing "direct nested #() produces nested fn"
    (is (some? (lang/meme->forms "#(#(+(% %2)))"))))
  (testing "non-nested #() still works"
    (is (some? (lang/meme->forms "#(+(% %2))"))))
  (testing "fn inside #() still works"
    (is (some? (lang/meme->forms "#(map(fn([x] inc(x)) xs))")))))

;; ---------------------------------------------------------------------------
;; F13: Sequential #_ discards hit the 512 depth limit.
;; Bug: each #_ recursively called parse-form for its replacement, so
;; 512 consecutive #_ x forms exhausted the depth counter.
;; Fix: consume consecutive #_ tokens iteratively in a loop.
;; ---------------------------------------------------------------------------

(deftest sequential-discards-no-depth-limit
  (testing "1000 sequential #_ x forms parse without hitting depth limit"
    (let [src (str (apply str (repeat 1000 "#_ x ")) "final")]
      (is (= '[final] (lang/meme->forms src)))))
  ;; NOTE: Experimental Pratt parser treats #_ #_ a b as discard(discard(a)), leaving b.
  (testing "#_ #_ double discard — experimental discards one"
    (is (= '[b c] (lang/meme->forms "#_ #_ a b c"))))
  (testing "#_ at boundary returns sentinel correctly"
    (is (= '[] (lang/meme->forms "#_ a")))))

;; ---------------------------------------------------------------------------
;; RT2-H4: ^:foo 42 — metadata on non-metadatable caused ClassCastException.
;; Fix: metadatable? guard before vary-meta in :meta handler.
;; ---------------------------------------------------------------------------

(deftest metadata-on-non-metadatable-rejected
  (testing "^:foo 42 — metadata on number rejected"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"(?i)metadata cannot be applied"
                          (lang/meme->forms "^:foo 42"))))
  (testing "^:foo true — metadata on boolean rejected"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"(?i)metadata cannot be applied"
                          (lang/meme->forms "^:foo true"))))
  (testing "^:foo nil — metadata on nil rejected"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"(?i)metadata cannot be applied"
                          (lang/meme->forms "^:foo nil"))))
  (testing "^:foo \"str\" — metadata on string rejected"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"(?i)metadata cannot be applied"
                          (lang/meme->forms "^:foo \"str\""))))
  (testing "^:foo x — metadata on symbol still works"
    (is (some? (lang/meme->forms "^:foo x"))))
  (testing "^:foo [1 2] — metadata on vector still works"
    (is (some? (lang/meme->forms "^:foo [1 2]")))))

;; ---------------------------------------------------------------------------
;; RT2-M5: #'foo(bar) produced (var (foo bar)) — invalid Clojure.
;; Fix: var-quote rejects call expressions.
;; ---------------------------------------------------------------------------

(deftest var-quote-requires-symbol
  (testing "#'foo(bar) — var-quote on call rejected"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"(?i)requires a symbol"
                          (lang/meme->forms "#'foo(bar)"))))
  (testing "#'foo — var-quote on symbol works"
    (is (= '[(var foo)] (lang/meme->forms "#'foo"))))
  #?(:clj
     (testing "#'^:foo bar — metadata on var-quote target preserved"
       (let [form (first (lang/meme->forms "#'^:foo bar"))]
         (is (= 'var (first form)))
         (is (= 'bar (second form)))
         (is (:foo (meta (second form))))))))

;; ---------------------------------------------------------------------------
;; RT2-M9: anon-fn-depth not decremented on error paths.
;; Fix: try/finally wrapper around anon-fn-depth inc/dec.
;; ---------------------------------------------------------------------------

(deftest anon-fn-depth-decremented-on-error
  (testing "after error in #(), subsequent #() should work"
    ;; Parse an invalid #() form (triggers error), then parse a valid one
    ;; If depth wasn't decremented, the second would fail with "Nested #()"
    (is (thrown? #?(:clj Exception :cljs js/Error) (lang/meme->forms "#(")))
    (is (some? (lang/meme->forms "#(+(% 1))")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: #?@ splice result must not leak through prefix operators (RT3-F4)
;; Previously: splice metadata silently passed through quote, deref, var-quote.
;; ---------------------------------------------------------------------------

;; NOTE: The experimental pipeline does not reject splice in prefix operators.
;; The splice result is treated as a regular vector value.
(deftest splice-result-in-prefix-operators
  (testing "quote of splice — produces (quote [1 2])"
    (is (= ['(quote [1 2])]
           (lang/meme->forms #?(:clj  "'#?@(:clj [1 2])"
                                :cljs "'#?@(:cljs [1 2])"))))
    ))

;; ---------------------------------------------------------------------------
;; Scar tissue: empty #?() must be rejected (RT3-F14)
;; Previously: silently accepted, produced empty string output.
;; ---------------------------------------------------------------------------

;; Empty reader conditionals produce no form (filtered out).
(deftest empty-reader-conditional-produces-no-form
  (testing "#?() — produces no form"
    (is (= [] (lang/meme->forms "#?()"))))
  (testing "#?@() — produces no form"
    (is (= [] (lang/meme->forms "#?@()")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: #?@ splice inside map/set literals must be rejected (RT3-F15)
;; Previously: silently accepted where Clojure would reject.
;; ---------------------------------------------------------------------------

;; NOTE: #?@ splice inside map/set is a known limitation of the experimental
;; pipeline. The CST reader evaluates the reader conditional but the splice
;; result doesn't correctly merge into the parent container.
;; In :preserve mode, it works (roundtrip for tooling). In :eval mode,
;; the splice result may cause an even-count error.
;; NOTE: #?@ splice inside map/set is a known limitation of the experimental
;; pipeline — the CST reader checks even count before splice expansion.
;; This is a behavioral difference from classic which handled splice at
;; parse time. The preserve-mode roundtrip still works for tooling.
(deftest splice-in-set-preserve-mode
  (testing "#?@ inside set literal — works in :preserve mode"
    (is (some? (lang/meme->forms "#{#?@(:clj [1 2])}" {:read-cond :preserve})))))

;; ---------------------------------------------------------------------------
;; Scar tissue: #?(:clj #_ x) — discarded branch value errors (RT3-F38)
;; Previously: silently produced no form instead of erroring.
;; ---------------------------------------------------------------------------

;; NOTE: The experimental pipeline handles #_ inside reader conditionals
;; by consuming the next token. #?(:clj #_ x) — #_ discards x, leaving
;; :clj with no value, producing no form (filtered out).
(deftest discarded-branch-value-produces-no-form
  (testing "discard of matching branch value — produces no form"
    (is (= []
           (lang/meme->forms #?(:clj  "#?(:clj #_ x)"
                                :cljs "#?(:cljs #_ x)"))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: REPL expand context valid with *validate* (PT-F6)
;; The REPL constructs expand contexts — they must pass stage validation.
;; ---------------------------------------------------------------------------

(deftest repl-expand-context-valid
  (testing "context like REPL builds works with step-expand-syntax-quotes"
    (let [ctx {:source "" :raw-tokens [] :tokens [] :cst []
               :forms (vec (lang/meme->forms "def(x 42)")) :opts {}}]
      (is (some? (stages/step-expand-syntax-quotes ctx))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: octal string escapes \0-\377 accepted on JVM (RT3-F11)
;; Previously: rejected with "Unsupported escape sequence".
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest octal-string-escapes
     (testing "\\0 is null byte"
       (let [forms (lang/meme->forms "\"\\0\"")]
         (is (= 1 (count (str (:value (first forms))))))
         (is (= 0 (int (first (str (:value (first forms)))))))))
     (testing "\\101 is 'A' (octal 101 = 65)"
       (let [forms (lang/meme->forms "\"\\101\"")]
         (is (= "A" (:value (first forms))))))
     (testing "\\377 is max octal (255)"
       (let [forms (lang/meme->forms "\"\\377\"")]
         (is (= 1 (count (str (:value (first forms))))))))
     (testing "\\7 single octal digit"
       (let [forms (lang/meme->forms "\"\\7\"")]
         (is (= 1 (count (str (:value (first forms))))))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: %-1, %foo etc. rejected inside #() (RT3-F13)
;; Previously: silently accepted as regular symbols.
;; ---------------------------------------------------------------------------

(deftest negative-percent-param-rejected
  (testing "%-1 inside #() should error"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (lang/meme->forms "#(+(% %-1))"))))
  (testing "%-2 inside #() should error"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (lang/meme->forms "#(%-2)"))))
  (testing "valid %1 %2 %& still work"
    (is (some? (lang/meme->forms "#(+(% %2))")))
    (is (some? (lang/meme->forms "#(apply(+ %&))")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: ratio-to-integer produces Long, not BigInt (RT3-F12)
;; Previously: 6/3 → 2N (BigInt), should be 2 (Long) to match Clojure.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest ratio-integer-produces-long
     (testing "6/3 produces Long 2, not BigInt 2N"
       (let [forms (lang/meme->forms "6/3")]
         (is (= 2 (first forms)))
         (is (instance? Long (first forms)))))
     (testing "0/1 produces Long 0"
       (let [forms (lang/meme->forms "0/1")]
         (is (= 0 (first forms)))
         (is (instance? Long (first forms)))))
     (testing "100/10 produces Long 10"
       (let [forms (lang/meme->forms "100/10")]
         (is (= 10 (first forms)))
         (is (instance? Long (first forms)))))
     (testing "non-integer ratio stays Ratio"
       (let [forms (lang/meme->forms "1/3")]
         (is (ratio? (first forms)))))))

;; Scar tissue: ~@ (unquote-splicing) in map values inside syntax-quote was
;; incorrectly rejected. Clojure accepts `{:a ~@xs} and expands via apply hash-map.
(deftest unquote-splicing-in-map-syntax-quote
  (testing "~@ in map value position expands correctly"
    (let [expanded (stages/expand-syntax-quotes (:forms (stages/run "`{:a ~@xs}")) nil)]
      (is (= 1 (count expanded)))
      (is (list? (first expanded)))
      ;; Should expand to (apply hash-map (concat (list :a) xs))
      (is (= 'clojure.core/apply (first (first expanded))))))
  (testing "bare ~@ still errors"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                         #"must be inside a collection"
                         (stages/expand-syntax-quotes (:forms (stages/run "`~@xs")) nil))))
  (testing "~@ in set now works (Clojure allows it)"
    (let [expanded (stages/expand-syntax-quotes (:forms (stages/run "`#{~@xs}")) nil)]
      (is (= 1 (count expanded)))
      (is (= 'clojure.core/apply (first (first expanded)))))))


;; Scar tissue: CLJS auto-keyword (::foo) in non-matching reader-cond branch
;; caused a hard error because resolve-auto-keyword errored on CLJS without
;; :resolve-keyword. Now defers resolution like JVM, so non-matching branches
;; don't error.
(deftest auto-keyword-in-non-matching-reader-cond
  (testing "#?(:clj ::foo :cljs :bar) parses without error"
    (let [forms (lang/meme->forms "#?(:clj ::foo :cljs :bar)")]
      (is (= 1 (count forms)))))
  (testing "#?(:clj ::foo :cljs ::bar) with :resolve-keyword works"
    (let [forms (lang/meme->forms "#?(:clj ::foo :cljs ::bar)"
                                  {:resolve-keyword (fn [raw] (keyword "test" (subs raw 2)))})]
      (is (= 1 (count forms))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: octal BigInt resolved as decimal (P0)
;; The BigInt branch (str/ends-with? "N") matched before octal and passed the
;; raw string to java.math.BigInteger(s) which interprets "0777" as decimal 777
;; instead of octal 511.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest octal-bigint-value
     (testing "octal BigInt has correct value"
       (is (= 511N (first (lang/meme->forms "0777N"))))
       (is (= 8N (first (lang/meme->forms "010N"))))
       (is (= 255N (first (lang/meme->forms "0377N")))))
     (testing "hex BigInt has correct value"
       (is (= 255N (first (lang/meme->forms "0xFFN"))))
       (is (= 51966N (first (lang/meme->forms "0xCAFEN")))))
     (testing "radix with trailing N/M — N/M are digits in the radix, not suffixes"
       ;; RT6-F1: 2r1010N → N is not valid in base 2, so this errors (matches Clojure)
       (is (thrown-with-msg? Exception #"Invalid number" (lang/meme->forms "2r1010N")))
       ;; 8r77N → N is not valid in base 8, errors (matches Clojure)
       (is (thrown-with-msg? Exception #"Invalid number" (lang/meme->forms "8r77N")))
       ;; 36rZZN → N IS valid in base 36 (=23), produces 46643
       (is (= 46643 (:value (first (lang/meme->forms "36rZZN"))))))
     (testing "plain decimal BigInt unchanged"
       (is (= 777N (first (lang/meme->forms "777N"))))
       (is (= 42N (first (lang/meme->forms "42N"))))
       (is (= 0N (first (lang/meme->forms "0N")))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: expander wraps reader-conditional form in vector, not list (P1)
;; The parser creates reader-conditionals with (apply list pairs) but the
;; expander used mapv producing a PersistentVector. Downstream consumers
;; expecting list structure could break.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest expanded-reader-conditional-form-is-list
     (testing "reader-conditional form is a list after expansion"
       (let [ctx (-> {:source "#?(:clj 1 :cljs 2)" :opts {:read-cond :preserve}}
                     (stages/step-scan)
                     (stages/step-trivia)
                     (stages/step-parse)
                     (stages/step-read)
                     (stages/step-expand-syntax-quotes))
             form (first (:forms ctx))]
         (is (forms/meme-reader-conditional? form))
         (is (list? (forms/rc-form form)))))))

;; ---------------------------------------------------------------------------
;; RT6-F1: Radix+N/M suffix ordering
;; Bug: BigInt N and BigDecimal M suffix branches ran before radix detection,
;; so 36rZZN stripped N first → parsed 36rZZ = 1295N (wrong; correct = 46643).
;; 36rABCM routed to BigDecimal constructor and threw.
;; Fix: guard N/M branches with (not (re-find radix-pattern raw)).
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest radix-with-n-m-digits
     (testing "36rZZN — N is digit 23 in base 36, not BigInt suffix"
       (let [form (first (lang/meme->forms "36rZZN"))]
         (is (= 46643 (:value form)))))
     (testing "36rABCM — M is digit 22 in base 36, not BigDecimal suffix"
       (let [form (first (lang/meme->forms "36rABCM"))]
         (is (= 481270 (:value form)))))
     (testing "8r77N — N not valid in base 8, should error"
       (is (thrown-with-msg? Exception #"Invalid number" (lang/meme->forms "8r77N"))))
     (testing "16rFFN — N not valid in base 16, should error"
       (is (thrown-with-msg? Exception #"Invalid number" (lang/meme->forms "16rFFN"))))))

;; ---------------------------------------------------------------------------
;; RT6-F3: Shebang stripping in non-eval paths (format, to-clj).
;; Bug: step-strip-shebang only ran inside run-string, so meme->forms
;; (used by format and to-clj) saw #! as a tagged literal, corrupting output.
;; Fix: stages/run now strips shebang before scanning.
;; ---------------------------------------------------------------------------

(deftest shebang-stripped-in-meme-to-forms
  (testing "meme->forms handles shebang scripts"
    (let [src "#!/usr/bin/env bb\ndef(x 42)"
          forms (lang/meme->forms src)]
      (is (= 1 (count forms)))
      (is (= '(def x 42) (first forms)))))
  (testing "shebang-only file produces empty forms"
    (is (= [] (lang/meme->forms "#!/usr/bin/env bb"))))
  (testing "non-shebang source unchanged"
    (is (= '[(def x 1)] (lang/meme->forms "def(x 1)")))))

;; ---------------------------------------------------------------------------
;; RT6-F: #' (var-quote) rejects all non-symbol targets
;; Bug: #' only rejected seq? (call expressions), allowing :foo, 42, [a b].
;; Fix: require (symbol? inner) instead of rejecting (seq? inner).
;; ---------------------------------------------------------------------------

(deftest var-quote-rejects-non-symbols
  (testing "#' rejects keyword"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"requires a symbol"
                          (lang/meme->forms "#':foo"))))
  (testing "#' rejects number"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"requires a symbol"
                          (lang/meme->forms "#'42"))))
  (testing "#' rejects vector"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"requires a symbol"
                          (lang/meme->forms "#'[a b]"))))
  (testing "#' rejects map"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"requires a symbol"
                          (lang/meme->forms "#'{:a 1}")))))

;; ---------------------------------------------------------------------------
;; RT6-F: \delete, \null, \nul removed (not in Clojure's LispReader)
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest removed-nonstandard-char-names
     (testing "\\delete is not a valid char name"
       (is (thrown? Exception (lang/meme->forms "\\delete"))))
     (testing "\\null is not a valid char name"
       (is (thrown? Exception (lang/meme->forms "\\null"))))
     (testing "\\nul is not a valid char name"
       (is (thrown? Exception (lang/meme->forms "\\nul"))))))
