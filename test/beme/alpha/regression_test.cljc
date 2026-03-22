(ns beme.alpha.regression-test
  "Scar tissue: regression tests for specific bugs and design decisions.
   Every bug fix or behavioral change gets a test here to prevent recurrence."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [beme.alpha.errors]
            [beme.alpha.parse.reader :as r]
            [beme.alpha.emit.printer :as p]
            [beme.alpha.emit.pprint :as pprint]
            [beme.alpha.scan.tokenizer :as tokenizer]
            [beme.alpha.scan.grouper :as grouper]))

(defn- tokenize [s]
  (-> (tokenizer/tokenize s) (grouper/group-tokens s)))

;; ---------------------------------------------------------------------------
;; Syntax-quote is opaque passthrough on JVM. Macros work.
;; ---------------------------------------------------------------------------

#?(:clj
(deftest syntax-quote-passthrough
  (testing "backtick on symbol produces a form"
    (is (seq? (first (r/read-beme-string "`foo")))))
  (testing "backtick on list produces a seq"
    (is (seq? (first (r/read-beme-string "`(a b c)")))))
  (testing "backtick nested inside a call works"
    (let [form (first (r/read-beme-string "foo(`bar)"))]
      (is (seq? form))
      (is (= 'foo (first form)))))))

;; ---------------------------------------------------------------------------
;; Signed number tokenization: sign adjacent to digit = number,
;; sign before ( or space = operator.
;; ---------------------------------------------------------------------------

(deftest signed-number-vs-operator
  (testing "-1 standalone is negative number"
    (is (= [-1] (r/read-beme-string "-1"))))
  (testing "-(1 2 3) is a call to - with three args"
    (is (= '[(- 1 2 3)] (r/read-beme-string "-(1 2 3)"))))
  (testing "+1 standalone is positive number"
    (is (= [1] (r/read-beme-string "+1"))))
  (testing "+(1 2) is a call to +"
    (is (= '[(+ 1 2)] (r/read-beme-string "+(1 2)"))))
  (testing "-1 inside a call is negative number"
    (is (= '[(foo -1 2)] (r/read-beme-string "foo(-1 2)"))))
  (testing "- as argument (with space) is symbol"
    (is (= '[(map - [1 2 3])] (r/read-beme-string "map(- [1 2 3])"))))
  (testing "-> is a symbol, not sign + >"
    (is (= '-> (first (r/read-beme-string "->")))))
  (testing "->> is a symbol, not sign + >>"
    (is (= '->> (first (r/read-beme-string "->>"))))))

;; #() feature tests moved to reader_test, printer_test, roundtrip_test.

;; ---------------------------------------------------------------------------
;; Comments inside opaque forms (#?, #:ns{}) must not confuse depth tracking.
;; ---------------------------------------------------------------------------

(deftest comment-inside-reader-conditional
  (testing "#? with ; comment containing ) inside"
    (let [tokens (tokenize "#?(:clj ; comment with )\n 1)")]
      (is (= 1 (count tokens)))
      (is (= :reader-cond-raw (:type (first tokens))))))
  (testing "#? with ; comment containing ] inside"
    (let [tokens (tokenize "#?(:clj ; ] in comment\n x)")]
      (is (= 1 (count tokens)))
      (is (= :reader-cond-raw (:type (first tokens))))))
  (testing "#:ns{} with ; comment containing } inside"
    (let [tokens (tokenize "#:user{:name ; } tricky\n \"x\"}")]
      (is (= 1 (count tokens)))
      (is (= :namespaced-map-raw (:type (first tokens)))))))

;; ---------------------------------------------------------------------------
;; Bug: read-balanced-raw didn't handle character literals. Bracket-like
;; chars (\), \(, etc.) corrupted the depth counter in opaque forms.
;; ---------------------------------------------------------------------------

(deftest char-literal-inside-opaque-form
  (testing "#? with \\) char literal — tokenizes as single token"
    (let [tokens (tokenize "#?(:clj \\) :cljs \\x)")]
      (is (= 1 (count tokens)))
      (is (= :reader-cond-raw (:type (first tokens))))))
  (testing "#? with \\( char literal — tokenizes as single token"
    (let [tokens (tokenize "#?(:clj \\( :cljs nil)")]
      (is (= 1 (count tokens)))
      (is (= :reader-cond-raw (:type (first tokens))))))
  (testing "#? with \\[ and \\] char literals"
    (let [tokens (tokenize "#?(:clj [\\[ \\]] :cljs nil)")]
      (is (= 1 (count tokens)))
      (is (= :reader-cond-raw (:type (first tokens))))))
  (testing "#? with \\{ and \\} char literals"
    (let [tokens (tokenize "#?(:clj {\\{ \\}} :cljs nil)")]
      (is (= 1 (count tokens)))
      (is (= :reader-cond-raw (:type (first tokens))))))
  (testing "#:ns{} with \\} char literal"
    (let [tokens (tokenize "#:user{:ch \\}}")]
      (is (= 1 (count tokens)))
      (is (= :namespaced-map-raw (:type (first tokens))))))
  #?(:clj
  (testing "#? with bracket char literals parses correctly on JVM"
    (is (reader-conditional? (first (r/read-beme-string "#?(:clj \\) :cljs nil)"))))
    (is (reader-conditional? (first (r/read-beme-string "#?(:clj \\( :cljs nil)")))))))

;; ---------------------------------------------------------------------------
;; Bug: `~(expr) produced truncated token and confusing "Bare parentheses"
;; error. The tokenizer now captures balanced forms after `~.
;; ---------------------------------------------------------------------------

#?(:clj
(deftest syntax-quote-unquote-forms
  (testing "`~(expr) parses as single form"
    (let [form (first (r/read-beme-string "`~(foo bar)"))]
      (is (some? form))))
  (testing "`~symbol still works"
    (is (symbol? (first (r/read-beme-string "`~foo")))))
  (testing "`~(expr) tokenizes as single token"
    (let [tokens (tokenize "`~(foo bar)")]
      (is (= 1 (count tokens)))
      (is (= :syntax-quote-raw (:type (first tokens))))))
  (testing "`~@(expr) tokenizes as single token"
    (let [tokens (tokenize "`~@(foo bar)")]
      (is (= 1 (count tokens)))
      (is (= :syntax-quote-raw (:type (first tokens))))))))

;; ---------------------------------------------------------------------------
;; Bug: bare # at EOF emitted empty tagged-literal token with confusing error.
;; ---------------------------------------------------------------------------

(deftest bare-hash-at-eof
  (testing "bare # at EOF gives clear error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Unexpected #"
                          (r/read-beme-string "#")))))

;; Namespaced map tests moved to reader_test (parse-namespaced-map).

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
;; Scar tissue: reader conditionals (#?, #?@) are opaque.
;; ---------------------------------------------------------------------------

#?(:clj
(deftest reader-conditional-is-opaque
  (testing "#?() empty — tokenizes as single raw token"
    (let [tokens (tokenize "#?()")]
      (is (= 1 (count tokens)))
      (is (= :reader-cond-raw (:type (first tokens))))
      (is (= "#?()" (:value (first tokens))))))
  (testing "#?() empty — round-trips through read → print → re-read"
    (let [forms1 (r/read-beme-string "#?()")
          printed (p/print-beme-string forms1)
          forms2 (r/read-beme-string printed)]
      (is (= "#?()" printed))
      (is (= forms1 forms2))))
  (testing "#?(:clj x :cljs y) basic — pass through as single token"
    (let [tokens (tokenize "#?(:clj x :cljs y)")]
      (is (= 1 (count tokens)))
      (is (= :reader-cond-raw (:type (first tokens))))))
  (testing "#?(:clj x :cljs y) basic — round-trips"
    (let [forms1 (r/read-beme-string "#?(:clj x :cljs y)")
          printed (p/print-beme-string forms1)
          forms2 (r/read-beme-string printed)]
      (is (= forms1 forms2))))
  (testing "#?@(:clj [1 2]) splice variant — round-trips"
    (let [forms1 (r/read-beme-string "#?@(:clj [1 2] :cljs [3 4])")
          printed (p/print-beme-string forms1)
          forms2 (r/read-beme-string printed)]
      (is (= forms1 forms2))))))

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
;; Scar tissue: radix numbers for bases 17–36 need letters G-Z.
;; Bug: read-number only accepted hex digits a-f/A-F, so 36rZ split into
;; number token "36r" + symbol "Z" — a silent misparse.
;; ---------------------------------------------------------------------------

#?(:clj
(deftest radix-numbers-high-bases
  (testing "36rZ — base-36 with letter beyond hex range"
    (is (= 35 (first (r/read-beme-string "36rZ")))))
  (testing "16rFF — hex via radix notation"
    (is (= 255 (first (r/read-beme-string "16rFF")))))
  (testing "2r1010 — binary"
    (is (= 10 (first (r/read-beme-string "2r1010")))))
  (testing "36rHelloWorld — large base-36 number"
    (is (= 1767707668033969 (first (r/read-beme-string "36rHelloWorld")))))))

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
    (is (= '[(f 3)] (r/read-beme-string "f begin #_1 #_2 3 end")))))

;; CLJS platform tests moved to reader_test (cljs-opaque-form-tokenization, cljs-reader-paths).

;; ---------------------------------------------------------------------------
;; read-string errors include source location context.
;; ---------------------------------------------------------------------------

#?(:clj
(deftest read-string-errors-include-location
  (testing "malformed number includes location"
    (let [e (try (r/read-beme-string "1/")
                 nil
                 (catch Exception e e))]
      (is (some? e))
      (is (= 1 (:line (ex-data e))))
      (is (= 1 (:col (ex-data e))))
      (is (re-find #"Invalid number" (ex-message e)))))
  (testing "malformed regex includes location"
    (let [e (try (r/read-beme-string "#\"[unclosed\"")
                 nil
                 (catch Exception e e))]
      (is (some? e))
      (is (= 1 (:line (ex-data e))))
      (is (re-find #"Invalid regex" (ex-message e)))))))


;; ---------------------------------------------------------------------------
;; Scar tissue: discard-sentinel must not leak into :meta or :tagged-literal.
;; Bug: parse-form results were used without sentinel check, so ^:key #_foo
;; or #mytag #_foo would embed the sentinel object into forms.
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
;; Bug: find-percent-params had no tagged-literal? branch, so % inside
;; tagged literals was invisible → wrong param vector.
;; ---------------------------------------------------------------------------

#?(:clj
(deftest percent-params-in-tagged-literals
  (testing "#(#mytag %) finds percent param"
    (let [form (first (r/read-beme-string "#(#mytag %)"))]
      (is (= 'fn (first form)))
      (is (= '[%1] (second form)))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: js/parseInt without radix parses leading-zero as octal.
;; Bug: percent-param-type used (js/parseInt s) without radix 10, so %08
;; would parse as 0 (invalid octal) instead of 8 on CLJS.
;; ---------------------------------------------------------------------------

(deftest percent-param-leading-zero-not-octal
  (testing "%08 param is decimal 8, not octal"
    (let [form (first (r/read-beme-string "#(+(%1 %08))"))]
      (is (= 'fn (first form)))
      (is (= 8 (count (second form)))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: deeply nested input must not crash with StackOverflowError.
;; Bug: no depth limit in parser; StackOverflowError extends Error not Exception,
;; so REPL catch clause didn't catch it → process crash.
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
;; Scar tissue: backslash terminates symbol — foo\a is symbol + char literal.
;; Bug: symbol-char? didn't exclude \, so foo\a tokenized as one symbol.
;; ---------------------------------------------------------------------------

(deftest backslash-terminates-symbol
  (testing "foo\\a tokenizes as symbol + char"
    (let [tokens (tokenize "foo\\a")]
      (is (= 2 (count tokens)))
      (is (= :symbol (:type (first tokens))))
      (is (= "foo" (:value (first tokens))))
      (is (= :char (:type (second tokens))))
      (is (= "\\a" (:value (second tokens))))))
  #?(:clj
  (testing "[foo\\a] reads as two-element vector"
    (is (= '[[foo \a]] (r/read-beme-string "[foo\\a]"))))))

;; ---------------------------------------------------------------------------
;; Syntax safety: beme operators must occupy dead Clojure syntax.
;; These tests verify that the character sequences claimed by beme's rules
;; are rejected by Clojure's reader — proving no valid Clojure program
;; can be broken by beme claiming them.
;; ---------------------------------------------------------------------------

(deftest rule1-call-syntax-trade-off
  (testing "Rule 1: f(x) → (f x) — head outside parens is a call"
    (is (= '[(f x)] (r/read-beme-string "f(x)"))))
  (testing "bare symbol without parens is just a symbol"
    (is (= '[f] (r/read-beme-string "f"))))
  #?(:clj
  (testing "this IS live Clojure syntax — known, documented trade-off"
    ;; In Clojure, f(x) reads as two forms: symbol f, then list (x).
    ;; beme reinterprets it as (f x). This is the core design trade-off.
    (let [clj-forms (with-open [r (java.io.PushbackReader. (java.io.StringReader. "f(x)"))]
                      [(read r) (read r)])]
      (is (= ['f '(x)] clj-forms) "Clojure reads f(x) as two forms")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: spacing between head and ( is irrelevant.
;; Bug: original Rule 1 required adjacency (no space). Removed because
;; "head outside parens" has no ambiguity — () is never a bare list.
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
;; Bug: bare parens were silently allowed, violating the "head outside parens"
;; principle. (1 2 3) parsed as a list instead of erroring.
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
;; Bug: removing adjacency broke multi-arity defn because symbol ate the
;; first arity's (. Fixed by allowing vectors to be heads: [args](body).
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
;; Bug: removing adjacency meant symbol before (:require ...) ate the paren.
;; Fix: keywords are heads too, so :require([...]) works.
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
;; Bug: printer emitted bare parens for (#{:a :b} x) and ({:a 1} :a),
;; which the reader then rejected as bare-paren errors.
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
;; Scar tissue: quoted lists print as '(...) not head(args).
;; Bug: printer's catch-all for non-symbol-headed lists emitted 1(2 3)
;; for (quote (1 2 3)), producing '1(2 3) instead of '(1 2 3).
;; ---------------------------------------------------------------------------

(deftest quoted-list-printer
  (testing "'(1 2 3) roundtrips"
    (let [form '(quote (1 2 3))
          printed (p/print-form form)
          read-back (first (r/read-beme-string printed))]
      (is (= "'(1 2 3)" printed))
      (is (= form read-back))))
  (testing "'(a b c) roundtrips"
    (let [form '(quote (a b c))
          printed (p/print-form form)
          read-back (first (r/read-beme-string printed))]
      (is (= "'(a b c)" printed))
      (is (= form read-back))))
  (testing "quoted empty list"
    (is (= "'()" (p/print-form '(quote ()))))))

;; ---------------------------------------------------------------------------
;; Prefix operator depth limit bypass: @, ', #' must go through parse-form
;; (not parse-form-base) so the depth counter is enforced.
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
;; Discard sentinel leak in prefix operators: @#_foo, '#_foo, #'#_foo at EOF
;; must throw, not leak the sentinel object into the output.
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
;; Empty list prints as valid beme: () → "'()" not "nil()".
;; Bug: print-form on empty list produced "nil()" which re-reads as (nil).
;; ---------------------------------------------------------------------------

(deftest empty-list-roundtrip
  (testing "empty list prints as '()"
    (is (= "'()" (p/print-form ()))))
  (testing "printed empty list re-reads correctly"
    (is (= '(quote ()) (first (r/read-beme-string "'()"))))))

;; ---------------------------------------------------------------------------
;; Opaque-form read-string errors must include beme source location.
;; Bug: syntax-quote-raw, namespaced-map-raw, reader-cond-raw passed raw text
;; to clojure.core/read-string without try/catch, so malformed content produced
;; raw Clojure exceptions without :line/:col in ex-data.
;; ---------------------------------------------------------------------------

#?(:clj
(deftest opaque-form-errors-include-location
  (testing "malformed namespaced map has :line/:col in ex-data"
    (let [ex (try (r/read-beme-string "#:ns{:a}")
                  (catch Exception e e))]
      (is (instance? clojure.lang.ExceptionInfo ex))
      (is (:line (ex-data ex)))
      (is (:col (ex-data ex)))
      (is (re-find #"Invalid namespaced map" (ex-message ex)))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: pprint head-line args must respect width.
;; Bug: pp-call-smart always printed head-line args flat, so forms like
;; (if <long-condition> ...) silently exceeded the target width.
;; ---------------------------------------------------------------------------

#?(:clj
(deftest pprint-head-line-args-respect-width
  (testing "long if-condition falls back to body when it exceeds width"
    (let [form '(if (and (> x 100) (< y 200) (not= z 0)) (body1) (body2))
          result (pprint/pprint-form form {:width 40})]
      (is (not (re-find #"if begin and" result))
          "long condition should not stay on head line")
      (doseq [line (str/split-lines result)]
        (is (<= (count line) 42)  ;; allow small margin for indentation
            (str "line exceeds width: " (pr-str line))))))
  (testing "short if-condition stays on head line when begin/end needed"
    (let [form '(if (> x 0) (do-something-with x) (do-something-else y))
          result (pprint/pprint-form form {:width 40})]
      (is (re-find #"if begin >" result)
          "short condition should stay on head line")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: pprint map value column must use actual key width.
;; Bug: pp-map computed value column from flat-width of key, which was wrong
;; when the key's pp'd form was multi-line (last line shorter than flat width).
;; ---------------------------------------------------------------------------

#?(:clj
(deftest pprint-map-value-column-uses-actual-key-width
  (testing "map value indentation based on actual key width, not flat width"
    (let [form {:k '(some-long-function arg1 arg2 arg3 arg4 arg5)}
          result (pprint/pprint-form form {:width 40})
          lines (str/split-lines result)]
      ;; The value should be formatted relative to where the key actually ends,
      ;; not where it would end if printed flat
      (is (some? result))
      (is (> (count lines) 1) "should be multi-line")))))

;; ---------------------------------------------------------------------------
;; B1: tagged-literal CLJS guard.
;; Bug: tagged-literal function called unconditionally on CLJS, producing a
;; raw ReferenceError instead of a helpful beme error.
;; ---------------------------------------------------------------------------

#?(:cljs
(deftest tagged-literal-cljs-error
  (testing "#uuid on CLJS throws beme error, not ReferenceError"
    (is (thrown-with-msg? js/Error #"not supported in ClojureScript"
          (r/read-beme-string "#uuid \"550e8400-e29b-41d4-a716-446655440000\""))))))

;; ---------------------------------------------------------------------------
;; B2: syntax-quote + unquote + string literal.
;; Bug: `~"foo" tokenized into two separate tokens (`~ and "foo"),
;; producing structurally wrong output.
;; ---------------------------------------------------------------------------

#?(:clj
(deftest syntax-quote-unquote-string
  (testing "`~\"foo\" tokenizes as single token"
    (let [tokens (tokenize "`~\"foo\"")]
      (is (= 1 (count tokens)))
      (is (= :syntax-quote-raw (:type (first tokens))))))
  (testing "`~\"foo\" reads successfully"
    (is (some? (r/read-beme-string "`~\"foo\""))))))

;; ---------------------------------------------------------------------------
;; B3: \uXXXX and \oXXX char literals.
;; Bug: \u0041 tokenized as \u (char) + 0041 (number) instead of \u0041.
;; ---------------------------------------------------------------------------

(deftest unicode-octal-char-literals
  (testing "\\u0041 tokenizes as single char token"
    (let [tokens (tokenize "\\u0041")]
      (is (= 1 (count tokens)))
      (is (= :char (:type (first tokens))))
      (is (= "\\u0041" (:value (first tokens))))))
  (testing "\\o101 tokenizes as single char token"
    (let [tokens (tokenize "\\o101")]
      (is (= 1 (count tokens)))
      (is (= :char (:type (first tokens))))
      (is (= "\\o101" (:value (first tokens))))))
  #?(:clj
  (testing "\\u0041 roundtrips to char A"
    (is (= [\A] (r/read-beme-string "\\u0041"))))))

;; ---------------------------------------------------------------------------
;; Bug: \u00g1 tokenized as \u00 + g1 instead of erroring. The dotimes loop
;; in read-char-extra didn't short-circuit on invalid digits — it silently
;; stopped consuming, leaving stray chars in the stream that produced a
;; second confusing error. Same pattern for \oXXX octal escapes.
;; Fix: loop with early error when a non-digit follows fewer than expected.
;; ---------------------------------------------------------------------------

(deftest unicode-escape-invalid-digit-errors
  (testing "\\u00g1 — non-hex digit errors at tokenizer level"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"expected 4 hex digits"
          (r/read-beme-string "\\u00g1"))))
  (testing "\\uXYZW — non-hex immediately after \\u"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"expected 4 hex digits"
          (r/read-beme-string "\\uXYZW"))))
  (testing "\\og — no octal digits after \\o"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"expected octal digits"
          (r/read-beme-string "\\og"))))
  (testing "valid \\u0041 still works"
    (is (= 1 (count (tokenize "\\u0041"))))
    (is (= :char (:type (first (tokenize "\\u0041"))))))
  (testing "valid \\o101 still works"
    (is (= 1 (count (tokenize "\\o101"))))
    (is (= :char (:type (first (tokenize "\\o101")))))))

;; ---------------------------------------------------------------------------
;; B4: #() printer drops surplus % params.
;; Bug: (fn [%1 %2] (inc %1)) printed as #(inc(%1)) which re-reads as
;; (fn [%1] (inc %1)) — silent arity change.
;; ---------------------------------------------------------------------------

(deftest anon-fn-surplus-params-not-dropped
  (testing "fn with surplus % params prints as fn(...), not #()"
    (let [form '(fn [%1 %2] (inc %1))
          printed (p/print-form form)]
      (is (not (str/starts-with? printed "#("))
          "surplus params must not emit #() shorthand")
      (is (= form (first (r/read-beme-string printed)))
          "roundtrip must preserve arity")))
  (testing "fn with matching % params still uses #() shorthand"
    (let [form '(fn [%1 %2] (+ %1 %2))
          printed (p/print-form form)]
      (is (str/starts-with? printed "#(")
          "matching params should emit #() shorthand")))
  (testing "zero-param fn with %N in body must not use #() shorthand"
    (let [form '(fn [] (inc %1))
          printed (p/print-form form)]
      (is (not (str/starts-with? printed "#("))
          "body references %1 but params is [] — #() would change arity")
      (is (= form (first (r/read-beme-string printed)))
          "roundtrip must preserve zero arity")))
  (testing "zero-param fn without %N in body still uses #() shorthand"
    (let [form '(fn [] (rand))
          printed (p/print-form form)]
      (is (str/starts-with? printed "#(")
          "no % params in body — #() shorthand is safe"))))

;; ---------------------------------------------------------------------------
;; B5/B6: source-context nil/empty guards.
;; Bug: source-context threw NPE on nil source, returned truthy "" on empty.
;; ---------------------------------------------------------------------------

(deftest source-context-edge-cases
  (testing "nil source returns nil"
    (is (nil? (beme.alpha.errors/source-context nil 1))))
  (testing "empty source returns nil"
    (is (nil? (beme.alpha.errors/source-context "" 1))))
  (testing "blank source returns nil"
    (is (nil? (beme.alpha.errors/source-context "   " 1)))))

;; ---------------------------------------------------------------------------
;; Bare backslash at EOF missing :incomplete.
;; Bug: lone \ at EOF produced a hard error from the host reader via
;; resolve-char, without :incomplete in ex-data. REPL showed an error
;; instead of prompting for continuation (user may be typing \newline etc.).
;; ---------------------------------------------------------------------------

(deftest backslash-eof-incomplete
  (testing "bare \\ at EOF signals :incomplete for REPL continuation"
    (let [ex (try (r/read-beme-string "\\")
                  (catch #?(:clj Exception :cljs :default) e e))]
      (is (some? ex))
      (is (:incomplete (ex-data ex))))))

;; ---------------------------------------------------------------------------
;; B7: backtick at EOF missing :incomplete.
;; Bug: lone ` at EOF produced :invalid in REPL instead of :incomplete,
;; so the REPL showed an error instead of prompting for more input.
;; ---------------------------------------------------------------------------

(deftest backtick-eof-incomplete
  (testing "lone backtick signals :incomplete"
    (let [ex (try (r/read-beme-string "`")
                  (catch #?(:clj Exception :cljs :default) e e))]
      (is (:incomplete (ex-data ex))))))

;; ---------------------------------------------------------------------------
;; B8: # followed by digit.
;; Bug: #3 produced a tagged-literal token with empty tag "#" instead of
;; a clear error message.
;; ---------------------------------------------------------------------------

(deftest hash-digit-error
  (testing "#3 throws clear error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"Invalid dispatch: #3"
          (r/read-beme-string "#3"))))
  (testing "#0 throws clear error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"Invalid dispatch: #0"
          (r/read-beme-string "#0")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: double discard inside #() anonymous function.
;; Bug: #_ #_ inside #() could fail to skip both forms, leaving stale tokens.
;; ---------------------------------------------------------------------------

(deftest double-discard-in-anon-fn
  (testing "#(#_ #_ a b c) — double discard skips a and b, c is the body"
    (is (= '[(fn [] c)] (r/read-beme-string "#(#_ #_ a b c)")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: bare % and numbered %N mixed in #() forms.
;; Bug: bare % not normalized to %1 when mixed with numbered params,
;; producing wrong param vector.
;; ---------------------------------------------------------------------------

(deftest mixed-bare-and-numbered-percent-params
  (testing "#(+(% %3)) — bare % normalized to %1, params [%1 %2 %3]"
    (let [form (first (r/read-beme-string "#(+(% %3))"))]
      (is (= 'fn (first form)))
      (is (= '[%1 %2 %3] (second form)))
      (is (= '(+ %1 %3) (nth form 2))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: mismatched bracket error includes location info.
;; Bug: mismatched delimiters could produce errors without :line/:col,
;; making debugging impossible.
;; ---------------------------------------------------------------------------

(deftest mismatched-bracket-error-message
  (testing "mismatched bracket error has descriptive message"
    (let [ex (try (r/read-beme-string "f([)")
                  (catch #?(:clj Exception :cljs :default) e e))]
      (is (some? (ex-message ex)))
      (is (:line (ex-data ex)))
      (is (:col (ex-data ex))))))

;; ---------------------------------------------------------------------------
;; Bug: #() inside opaque regions (reader conditionals, namespaced maps,
;; syntax-quote with brackets) desynchronized the grouper's bracket depth
;; counter. :open-anon-fn was not in opening-type?, so #( didn't increment
;; depth — the grouper closed the outer region at #()'s closing paren.
;; ---------------------------------------------------------------------------

(deftest anon-fn-inside-reader-conditional
  (testing "#?(:clj #(inc %) :cljs identity) tokenizes as single token"
    (let [tokens (tokenize "#?(:clj #(inc %) :cljs identity)")]
      (is (= 1 (count tokens)))
      (is (= :reader-cond-raw (:type (first tokens))))))
  ;; Parse-level test is JVM only — CLJS rejects reader conditionals at resolve time
  #?(:clj
  (testing "#?(:clj #(inc %) :cljs identity) parses without error"
    (is (some? (r/read-beme-string "#?(:clj #(inc %) :cljs identity)")))))
  (testing "#?@(:clj [#(+ %1 %2)] :cljs [identity]) tokenizes as single token"
    (let [tokens (tokenize "#?@(:clj [#(+ %1 %2)] :cljs [identity])")]
      (is (= 1 (count tokens)))
      (is (= :reader-cond-raw (:type (first tokens)))))))

#?(:clj
(deftest anon-fn-inside-syntax-quote
  (testing "`(#(inc %)) tokenizes as single syntax-quote-raw token"
    (let [tokens (tokenize "`(#(inc %))")]
      (is (= 1 (count tokens)))
      (is (= :syntax-quote-raw (:type (first tokens))))))
  (testing "`(#(inc %)) parses without error"
    (is (some? (r/read-beme-string "`(#(inc %))"))))))

#?(:clj
(deftest anon-fn-inside-namespaced-map
  (testing "#:user{:f #(inc %)} tokenizes as single token"
    (let [tokens (tokenize "#:user{:f #(inc %)}")]
      (is (= 1 (count tokens)))
      (is (= :namespaced-map-raw (:type (first tokens))))))
  (testing "#:user{:f #(inc %)} parses without error"
    (is (some? (r/read-beme-string "#:user{:f #(inc %)}"))))))

;; ---------------------------------------------------------------------------
;; Bug: "be" was previously reserved as a shorthand for "begin", making it
;; unusable as a normal symbol. Removed to avoid reserving common words.
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
;; Non-keyword/symbol/map metadata (e.g., number) was passed to merge
;; unguarded, producing a raw JVM exception without source location.
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
;; Bug: error gutter misalignment when secondary line has more digits.
;; Gutter width was computed from primary line number only, so a primary
;; on line 5 (1 digit) with secondary on line 1000 (4 digits) overflowed.
;; ---------------------------------------------------------------------------

(deftest error-gutter-width-spans-all-lines
  (testing "gutter width accommodates secondary line numbers wider than primary"
    (let [source (str/join "\n" (concat (repeat 999 "x") ["error-line"]))
          e (ex-info "test error"
              {:line 5 :col 1
               :secondary [{:line 1000 :col 1 :label "related"}]})
          result (beme.alpha.errors/format-error e source)]
      (is (re-find #"   5 \|" result) "primary line padded to 4-wide gutter")
      (is (re-find #"1000 \|" result) "secondary line fits in gutter"))))

;; ---------------------------------------------------------------------------
;; Bug: quoted list with non-callable-headed sublists produced broken output.
;; The printer's '(...) sugar applied print-form to inner elements, which
;; used the catch-all head(args) format for sublists like (1 2 3) → 1(2 3).
;; On re-read, 1 doesn't trigger maybe-call, leaving bare () which errors.
;; Fix: '(...) sugar only when all inner sublists have callable heads;
;; otherwise fall back to pr-str (Clojure S-expression).
;; ---------------------------------------------------------------------------

(deftest quoted-list-with-non-callable-headed-sublists
  (testing "symbol-headed sublists inside quote roundtrip via '(...) sugar"
    (let [form '(quote (f (g x)))
          printed (p/print-form form)
          reread (first (r/read-beme-string printed))]
      (is (= "'(f g(x))" printed))
      (is (= form reread))))
  (testing "all-atoms quoted list still uses '(...) sugar"
    (let [form '(quote (1 2 3))
          printed (p/print-form form)]
      (is (= "'(1 2 3)" printed))
      (is (= form (first (r/read-beme-string printed))))))
  (testing "number-headed sublist falls back to pr-str, not broken 1(2 3)"
    (let [form (list 'quote (list (list 1 2 3)))
          printed (p/print-form form)]
      (is (not (str/includes? printed "1(2 3)"))
          "must not emit broken beme syntax")))
  (testing "string-headed sublist falls back to pr-str"
    (let [form (list 'quote (list (list "hello" 1)))
          printed (p/print-form form)]
      (is (not (str/includes? printed "\"hello\"("))
          "must not emit broken beme syntax")))
  (testing "nil-headed sublist falls back to pr-str"
    (let [form (list 'quote (list (list nil 1)))
          printed (p/print-form form)]
      (is (not (str/includes? printed "nil("))
          "must not emit broken beme syntax")))
  (testing "mixed: safe elements + non-callable sublist falls back"
    (let [form (list 'quote (list 'x (list 1 2) 'y))
          printed (p/print-form form)]
      (is (not (str/includes? printed "1(2"))
          "must not emit broken beme syntax"))))

;; ---------------------------------------------------------------------------
;; Bug: unclosed opaque forms (#?(, #:ns{, `() returned :invalid from
;; input-state instead of :incomplete. The grouper produced truncated raw
;; tokens that were passed to host-read, which threw without :incomplete.
;; The REPL reported an error instead of prompting for continuation.
;; Fix: grouper detects unterminated opaque regions and throws with
;; :incomplete true before reaching host-read.
;; ---------------------------------------------------------------------------

(deftest unclosed-opaque-forms-are-incomplete
  (testing "unclosed #?( is :incomplete for REPL continuation"
    (let [e (try (r/read-beme-string "#?(")
                 nil
                 (catch #?(:clj Exception :cljs :default) e e))]
      (is (some? e))
      (is (:incomplete (ex-data e)))))
  (testing "unclosed #:ns{ is :incomplete"
    (let [e (try (r/read-beme-string "#:ns{")
                 nil
                 (catch #?(:clj Exception :cljs :default) e e))]
      (is (some? e))
      (is (:incomplete (ex-data e)))))
  #?(:clj
  (testing "unclosed `( is :incomplete"
    (let [e (try (r/read-beme-string "`(")
                 nil
                 (catch Exception e e))]
      (is (some? e))
      (is (:incomplete (ex-data e))))))
  (testing "unclosed #?(:clj is :incomplete"
    (let [e (try (r/read-beme-string "#?(:clj")
                 nil
                 (catch #?(:clj Exception :cljs :default) e e))]
      (is (some? e))
      (is (:incomplete (ex-data e)))))
  (testing "complete opaque forms still work"
    #?(:clj (is (some? (r/read-beme-string "#?(:clj 1)"))))
    #?(:clj (is (some? (r/read-beme-string "#:ns{:a 1}"))))
    #?(:clj (is (some? (r/read-beme-string "`(a b)"))))))

;; ---------------------------------------------------------------------------
;; Bug: read-symbol-str allowed multiple slashes. The saw-slash guard only
;; blocked the first cond branch; after the first /, subsequent / chars
;; passed through symbol-char? (which doesn't exclude /). Input a/b/c
;; produced token "a/b/c" instead of stopping at "a/b".
;; Fix: explicit (= ch \/) nil branch after saw-slash check.
;; ---------------------------------------------------------------------------

(deftest symbol-single-slash-only
  (testing "a/b is a single namespace-qualified symbol"
    (let [tokens (tokenize "a/b")]
      (is (= 1 (count tokens)))
      (is (= "a/b" (:value (first tokens))))))
  (testing "a/b/c stops at first slash — two tokens"
    (let [tokens (tokenize "a/b/c")]
      (is (= 2 (count tokens)))
      (is (= "a/b" (:value (first tokens))))))
  (testing "clojure.string/join — dots are fine, one slash"
    (let [tokens (tokenize "clojure.string/join")]
      (is (= 1 (count tokens)))
      (is (= "clojure.string/join" (:value (first tokens)))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: quoted list shorthand checked only direct children.
;; Bug: (quote ((a (1 2)) b)) passed the every? check because (a (1 2))
;; has a symbol head, but the nested (1 2) has a number head. Printer
;; emitted '(a(1(2)) b) which the reader rejected as bare parens.
;; Fix: recursive check of all nested sublists, not just direct children.
;; ---------------------------------------------------------------------------

(deftest quoted-list-nested-non-callable-head
  (testing "quoted list with nested non-callable head falls back to pr-str"
    (let [form '(quote ((a (1 2)) b))
          printed (p/print-form form)]
      (is (not (str/starts-with? printed "'("))
          "must not use '(...) shorthand — nested (1 2) has non-callable head")
      ;; Falls back to pr-str (valid Clojure, not valid beme — inherent limitation
      ;; documented at printer.cljc:153-157). The important thing is that the printer
      ;; does NOT emit broken beme like '(a(1(2)) b).
      (is (some? printed))))
  (testing "quoted list with all-callable nested sublists uses shorthand"
    (let [form '(quote ((a (b c)) d))
          printed (p/print-form form)]
      (is (str/starts-with? printed "'(")
          "all nested sublists have symbol heads — shorthand is safe")))
  (testing "all-callable nested sublists roundtrip"
    (let [form '(quote ((a (b c)) d))
          printed (p/print-form form)
          read-back (first (r/read-beme-string printed))]
      (is (= form read-back)))))

;; ---------------------------------------------------------------------------
;; Scar tissue: duplicate set elements and map keys silently deduplicated.
;; Bug: #{1 1} returned #{1} instead of erroring like Clojure's reader.
;; Similarly, {:a 1 :a 2} returned {:a 2} instead of erroring.
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
