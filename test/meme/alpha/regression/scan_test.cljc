(ns meme.alpha.regression.scan-test
  "Scar tissue: tokenizer and grouper regression tests.
   Every test here prevents a specific bug from recurring."
  (:require [clojure.test :refer [deftest is testing]]
            [meme.alpha.core :as core]
            [meme.alpha.emit.printer :as p]
            [meme.alpha.pipeline :as pipeline]
            [meme.alpha.scan.tokenizer :as tokenizer]
            [meme.alpha.scan.grouper :as grouper]))

(defn- tokenize [s]
  (-> (tokenizer/tokenize s) (grouper/group-tokens s)))

;; ---------------------------------------------------------------------------
;; Syntax-quote is parsed natively with meme rules inside.
;; ---------------------------------------------------------------------------

(deftest syntax-quote-native
  (testing "backtick on symbol produces a quoted form"
    (is (some? (first (core/meme->forms "`foo")))))
  (testing "backtick on call produces expanded form"
    (is (seq? (first (core/meme->forms "`a(b c)")))))
  (testing "backtick nested inside a call works"
    (let [form (first (core/meme->forms "foo(`bar)"))]
      (is (seq? form))
      (is (= 'foo (first form))))))

;; ---------------------------------------------------------------------------
;; Signed number tokenization: sign adjacent to digit = number,
;; sign before ( or space = operator.
;; ---------------------------------------------------------------------------

(deftest signed-number-vs-operator
  (testing "-1 standalone is negative number"
    (is (= [-1] (core/meme->forms "-1"))))
  (testing "-(1 2 3) is a call to - with three args"
    (is (= '[(- 1 2 3)] (core/meme->forms "-(1 2 3)"))))
  (testing "+1 standalone is positive number"
    (is (= [1] (core/meme->forms "+1"))))
  (testing "+(1 2) is a call to +"
    (is (= '[(+ 1 2)] (core/meme->forms "+(1 2)"))))
  (testing "-1 inside a call is negative number"
    (is (= '[(foo -1 2)] (core/meme->forms "foo(-1 2)"))))
  (testing "- as argument (with space) is symbol"
    (is (= '[(map - [1 2 3])] (core/meme->forms "map(- [1 2 3])"))))
  (testing "-> is a symbol, not sign + >"
    (is (= '-> (first (core/meme->forms "->")))))
  (testing "->> is a symbol, not sign + >>"
    (is (= '->> (first (core/meme->forms "->>"))))))

;; ---------------------------------------------------------------------------
;; Comments inside opaque forms (#?, #:ns{}) must not confuse depth tracking.
;; ---------------------------------------------------------------------------

(deftest comment-inside-reader-conditional
  (testing "#? with ; comment containing ) inside"
    (let [tokens (tokenize "#?(:clj ; comment with )\n 1)")]
      (is (= :reader-cond-start (:type (first tokens))))))
  (testing "#? with ; comment containing ] inside"
    (let [tokens (tokenize "#?(:clj ; ] in comment\n x)")]
      (is (= :reader-cond-start (:type (first tokens))))))
  (testing "#:ns{} with ; comment containing } inside — parses correctly"
    (let [tokens (tokenize "#:user{:name ; } tricky\n \"x\"}")]
      (is (= :namespaced-map-start (:type (first tokens)))))))

;; ---------------------------------------------------------------------------
;; Bug: read-balanced-raw didn't handle character literals. Bracket-like
;; chars (\), \(, etc.) corrupted the depth counter in opaque forms.
;; ---------------------------------------------------------------------------

(deftest char-literal-inside-reader-conditional
  (testing "#? with \\) char literal — passes through correctly"
    (let [tokens (tokenize "#?(:clj \\) :cljs \\x)")]
      (is (= :reader-cond-start (:type (first tokens))))))
  (testing "#? with \\( char literal — passes through correctly"
    (let [tokens (tokenize "#?(:clj \\( :cljs nil)")]
      (is (= :reader-cond-start (:type (first tokens))))))
  (testing "#? with \\[ and \\] char literals"
    (let [tokens (tokenize "#?(:clj [\\[ \\]] :cljs nil)")]
      (is (= :reader-cond-start (:type (first tokens))))))
  (testing "#? with \\{ and \\} char literals"
    (let [tokens (tokenize "#?(:clj {\\{ \\}} :cljs nil)")]
      (is (= :reader-cond-start (:type (first tokens))))))
  (testing "#:ns{} with \\} char literal — parses correctly"
    (let [tokens (tokenize "#:user{:ch \\}}")]
      (is (= :namespaced-map-start (:type (first tokens))))))
  #?(:clj
  (testing "#? with bracket char literals parses to matched value"
    (is (= \) (first (core/meme->forms "#?(:clj \\) :cljs nil)"))))
    (is (= \( (first (core/meme->forms "#?(:clj \\( :cljs nil)")))))))

;; ---------------------------------------------------------------------------
;; Bug: `~(expr) produced truncated token and confusing "Bare parentheses"
;; error. The tokenizer now captures balanced forms after `~.
;; ---------------------------------------------------------------------------

(deftest syntax-quote-unquote-forms
  (testing "`~foo returns the symbol foo (unquoted)"
    (is (= 'foo (first (core/meme->forms "`~foo")))))
  (testing "` + ~ tokenize as separate prefix tokens"
    (let [tokens (tokenize "`~foo")]
      (is (= :syntax-quote (:type (first tokens))))
      (is (= :unquote (:type (second tokens)))))))

;; ---------------------------------------------------------------------------
;; Bug: bare # at EOF emitted empty tagged-literal token with confusing error.
;; ---------------------------------------------------------------------------

(deftest bare-hash-at-eof
  (testing "bare # at EOF gives clear error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Unexpected #"
                          (core/meme->forms "#")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: reader conditionals (#?, #?@) are opaque.
;; ---------------------------------------------------------------------------

(deftest reader-conditional-native-parsing
  (testing "#?(:clj x :cljs y) returns matching platform value"
    (is (= '[x] (core/meme->forms "#?(:clj x :cljs y)"))))
  (testing "#?(:default fallback) uses :default"
    (is (= '[fallback] (core/meme->forms "#?(:unknown x :default fallback)"))))
  (testing "#?() empty — returns empty list"
    (is (= [(list)] (core/meme->forms "#?()")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: radix numbers for bases 17–36 need letters G-Z.
;; Bug: read-number only accepted hex digits a-f/A-F, so 36rZ split into
;; number token "36r" + symbol "Z" — a silent misparse.
;; ---------------------------------------------------------------------------

#?(:clj
(deftest radix-numbers-high-bases
  (testing "36rZ — base-36 with letter beyond hex range"
    (is (= 35 (first (core/meme->forms "36rZ")))))
  (testing "16rFF — hex via radix notation"
    (is (= 255 (first (core/meme->forms "16rFF")))))
  (testing "2r1010 — binary"
    (is (= 10 (first (core/meme->forms "2r1010")))))
  (testing "36rHelloWorld — large base-36 number"
    (is (= 1767707668033969 (first (core/meme->forms "36rHelloWorld")))))))

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
    (is (= '[[foo \a]] (core/meme->forms "[foo\\a]"))))))

;; ---------------------------------------------------------------------------
;; B2: syntax-quote + unquote + string literal.
;; Bug: `~"foo" tokenized into two separate tokens (`~ and "foo"),
;; producing structurally wrong output.
;; ---------------------------------------------------------------------------

(deftest syntax-quote-unquote-string
  (testing "`~\"foo\" starts with :syntax-quote prefix"
    (let [tokens (tokenize "`~\"foo\"")]
      (is (= :syntax-quote (:type (first tokens))))))
  (testing "`~\"foo\" returns unquoted string"
    (is (= "foo" (first (core/meme->forms "`~\"foo\""))))))

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
    (is (= [\A] (core/meme->forms "\\u0041"))))))

;; ---------------------------------------------------------------------------
;; Bug: \u00g1 tokenized as \u00 + g1 instead of erroring.
;; Fix: loop with early error when a non-digit follows fewer than expected.
;; ---------------------------------------------------------------------------

(deftest unicode-escape-invalid-digit-errors
  (testing "\\u00g1 — non-hex digit errors at tokenizer level"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"expected 4 hex digits"
          (core/meme->forms "\\u00g1"))))
  (testing "\\uXYZW — non-hex immediately after \\u"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"expected 4 hex digits"
          (core/meme->forms "\\uXYZW"))))
  (testing "\\og — no octal digits after \\o"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"expected octal digits"
          (core/meme->forms "\\og"))))
  (testing "valid \\u0041 still works"
    (is (= 1 (count (tokenize "\\u0041"))))
    (is (= :char (:type (first (tokenize "\\u0041"))))))
  (testing "valid \\o101 still works"
    (is (= 1 (count (tokenize "\\o101"))))
    (is (= :char (:type (first (tokenize "\\o101")))))))

;; ---------------------------------------------------------------------------
;; Incomplete \uXX at EOF — malformed :char token instead of :incomplete.
;; Bug: \u followed by 1-3 hex digits at EOF produced a token that failed
;; at resolve time with :invalid, preventing REPL continuation.
;; ---------------------------------------------------------------------------

(deftest partial-unicode-escape-eof-incomplete
  (testing "\\u0 at EOF signals :incomplete"
    (let [ex (try (core/meme->forms "\\u0")
                  (catch #?(:clj Exception :cljs :default) e e))]
      (is (:incomplete (ex-data ex)))))
  (testing "\\u00 at EOF signals :incomplete"
    (let [ex (try (core/meme->forms "\\u00")
                  (catch #?(:clj Exception :cljs :default) e e))]
      (is (:incomplete (ex-data ex)))))
  (testing "\\u000 at EOF signals :incomplete"
    (let [ex (try (core/meme->forms "\\u000")
                  (catch #?(:clj Exception :cljs :default) e e))]
      (is (:incomplete (ex-data ex)))))
  (testing "\\u alone at EOF is complete (character u)"
    (is (= [\u] (core/meme->forms "\\u"))))
  (testing "\\u0041 is complete"
    (is (= [\A] (core/meme->forms "\\u0041"))))
  (testing "\\u00g1 is still :invalid (not :incomplete)"
    (let [ex (try (core/meme->forms "\\u00g1")
                  (catch #?(:clj Exception :cljs :default) e e))]
      (is (some? ex))
      (is (not (:incomplete (ex-data ex)))))))

;; ---------------------------------------------------------------------------
;; Bare backslash at EOF missing :incomplete.
;; Bug: lone \ at EOF produced a hard error without :incomplete in ex-data.
;; ---------------------------------------------------------------------------

(deftest backslash-eof-incomplete
  (testing "bare \\ at EOF signals :incomplete for REPL continuation"
    (let [ex (try (core/meme->forms "\\")
                  (catch #?(:clj Exception :cljs :default) e e))]
      (is (some? ex))
      (is (:incomplete (ex-data ex))))))

;; ---------------------------------------------------------------------------
;; B7: backtick at EOF missing :incomplete.
;; Bug: lone ` at EOF produced :invalid in REPL instead of :incomplete.
;; ---------------------------------------------------------------------------

(deftest backtick-eof-incomplete
  (testing "lone backtick signals :incomplete"
    (let [ex (try (core/meme->forms "`")
                  (catch #?(:clj Exception :cljs :default) e e))]
      (is (:incomplete (ex-data ex))))))

;; ---------------------------------------------------------------------------
;; B8: # followed by digit.
;; Bug: #3 produced a tagged-literal token with empty tag "#".
;; ---------------------------------------------------------------------------

(deftest hash-digit-error
  (testing "#3 throws clear error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"Invalid dispatch: #3"
          (core/meme->forms "#3"))))
  (testing "#0 throws clear error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"Invalid dispatch: #0"
          (core/meme->forms "#0")))))

;; ---------------------------------------------------------------------------
;; B9: # followed by non-symbol char (], ), ~, @, ^, \, ;, }, `).
;; Bug: read-symbol-str returned "", producing :tagged-literal "#" with
;; empty tag. Downstream errors were confusing (e.g. "Unexpected )" instead
;; of "Invalid dispatch: #)").
;; Fix: check symbol-start? before entering tagged-literal path.
;; ---------------------------------------------------------------------------

(deftest hash-non-symbol-char-error
  (doseq [[input desc] [["#)" "#)"] ["#]" "#]"] ["#~" "#~"]
                         ["#@" "#@"] ["#^" "#^"] ["#}" "#}"]
                         ["#`" "#`"] ["#;" "#;"]]]
    (testing (str desc " throws clear Invalid dispatch error")
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"Invalid dispatch"
            (core/meme->forms input))))))

;; ---------------------------------------------------------------------------
;; Bug: #() inside opaque regions desynchronized the grouper's bracket depth
;; counter. :open-anon-fn was not in opening-type?.
;; ---------------------------------------------------------------------------

(deftest anon-fn-inside-reader-conditional
  (testing "#?(:clj #(inc(%)) :cljs identity) starts with reader-cond-start"
    (let [tokens (tokenize "#?(:clj #(inc(%)) :cljs identity)")]
      (is (= :reader-cond-start (:type (first tokens))))))
  #?(:clj
  (testing "#?(:clj #(inc(%)) :cljs identity) parses without error"
    (is (some? (core/meme->forms "#?(:clj #(inc(%)) :cljs identity)")))))
  (testing "#?@(:clj [#(+(%1 %2))] :cljs [identity]) starts with reader-cond-start"
    (let [tokens (tokenize "#?@(:clj [#(+(%1 %2))] :cljs [identity])")]
      (is (= :reader-cond-start (:type (first tokens)))))))

(deftest anon-fn-inside-syntax-quote
  (testing "` prefix token appears"
    (let [tokens (tokenize "`#(inc(%))")]
      (is (= :syntax-quote (:type (first tokens))))))
  (testing "`#(inc(%)) parses without error"
    (is (some? (core/meme->forms "`#(inc(%))")))))

#?(:clj
(deftest anon-fn-inside-namespaced-map
  (testing "#:user{:f #(inc(%))} tokenizes with namespaced-map-start"
    (let [tokens (tokenize "#:user{:f #(inc(%))}")]
      (is (= :namespaced-map-start (:type (first tokens))))))
  (testing "#:user{:f #(inc(%))} parses without error"
    (is (some? (core/meme->forms "#:user{:f #(inc(%))}"))))))

;; ---------------------------------------------------------------------------
;; Bug: unclosed opaque forms returned :invalid instead of :incomplete.
;; Fix: grouper detects unterminated opaque regions.
;; ---------------------------------------------------------------------------

(deftest unclosed-opaque-forms-are-incomplete
  (testing "unclosed #?( is :incomplete for REPL continuation"
    (let [e (try (core/meme->forms "#?(")
                 nil
                 (catch #?(:clj Exception :cljs :default) e e))]
      (is (some? e))
      (is (:incomplete (ex-data e)))))
  (testing "unclosed #:ns{ is :incomplete"
    (let [e (try (core/meme->forms "#:ns{")
                 nil
                 (catch #?(:clj Exception :cljs :default) e e))]
      (is (some? e))
      (is (:incomplete (ex-data e)))))
  (testing "unclosed `( is an error"
    (is (thrown? #?(:clj Exception :cljs :default) (core/meme->forms "`("))))
  (testing "unclosed #?(:clj is :incomplete"
    (let [e (try (core/meme->forms "#?(:clj")
                 nil
                 (catch #?(:clj Exception :cljs :default) e e))]
      (is (some? e))
      (is (:incomplete (ex-data e)))))
  (testing "complete forms still work"
    (is (some? (core/meme->forms "#?(:clj 1)")))
    (is (some? (core/meme->forms "#:ns{:a 1}")))
    (is (some? (core/meme->forms "`a(b)")))))

;; ---------------------------------------------------------------------------
;; Bug: read-symbol-str allowed multiple slashes.
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
;; Bug: `\char (syntax-quote of character literal) silently broke.
;; read-symbol-str returns "" for \ because backslash is not a symbol-char.
;; Same bug for `"string" and `~\char.
;; Fix: explicit \\ and \" handling before the symbol catch-all.
;; ---------------------------------------------------------------------------

(deftest syntax-quote-char-literal
  (testing "`\\a starts with :syntax-quote prefix"
    (is (= :syntax-quote (:type (first (tokenize "`\\a"))))))
  (testing "`\\a parses without error"
    (is (some? (core/meme->forms "`\\a")))))

(deftest syntax-quote-string-literal
  (testing "`\"foo\" starts with :syntax-quote prefix"
    (is (= :syntax-quote (:type (first (tokenize "`\"foo\""))))))
  (testing "`\"foo\" parses without error"
    (is (some? (core/meme->forms "`\"foo\"")))))

(deftest syntax-quote-unquote-char-literal
  (testing "`~\\a starts with :syntax-quote prefix"
    (is (= :syntax-quote (:type (first (tokenize "`~\\a"))))))
  (testing "`~\\a parses to char value"
    (is (= \a (first (core/meme->forms "`~\\a"))))))

;; ---------------------------------------------------------------------------
;; Bug: ##Inf, ##-Inf, ##NaN silently misparsed as tagged literals.
;; The tokenizer's # dispatch fell through to tagged-literal for ##,
;; and # was not excluded from symbol-char?, so read-symbol-str consumed
;; #Inf as a tag name, producing a broken tagged-literal token that ate
;; the next form.
;; Fix: dedicated (= nxt \#) branch emits :number token; # excluded
;; from symbol-start? and symbol-char?.
;; ---------------------------------------------------------------------------

(deftest symbolic-value-tokenization
  (testing "##Inf tokenizes as :number"
    (let [tokens (tokenize "##Inf")]
      (is (= 1 (count tokens)))
      (is (= :number (:type (first tokens))))
      (is (= "##Inf" (:value (first tokens))))))
  (testing "##-Inf tokenizes as :number"
    (let [tokens (tokenize "##-Inf")]
      (is (= 1 (count tokens)))
      (is (= :number (:type (first tokens))))
      (is (= "##-Inf" (:value (first tokens))))))
  (testing "##NaN tokenizes as :number"
    (let [tokens (tokenize "##NaN")]
      (is (= 1 (count tokens)))
      (is (= :number (:type (first tokens))))
      (is (= "##NaN" (:value (first tokens))))))
  (testing "##Inf does not eat following form"
    (let [tokens (tokenize "##Inf 42")]
      (is (= 2 (count tokens)))
      (is (= "##Inf" (:value (first tokens))))
      (is (= "42" (:value (second tokens)))))))

#?(:clj
(deftest symbolic-value-parsing
  (testing "##Inf parses to positive infinity"
    (is (= ##Inf (first (core/meme->forms "##Inf")))))
  (testing "##-Inf parses to negative infinity"
    (is (= ##-Inf (first (core/meme->forms "##-Inf")))))
  (testing "##NaN parses to NaN"
    (is (Double/isNaN (first (core/meme->forms "##NaN")))))
  (testing "##Inf roundtrips through print → re-read"
    (let [forms (core/meme->forms "##Inf")
          printed (p/print-meme-string forms)
          re-read (core/meme->forms printed)]
      (is (= "##Inf" printed))
      (is (= forms re-read))))
  (testing "##-Inf roundtrips"
    (let [forms (core/meme->forms "##-Inf")
          printed (p/print-meme-string forms)]
      (is (= "##-Inf" printed))))
  (testing "##NaN prints as ##NaN"
    (is (= "##NaN" (p/print-meme-string (core/meme->forms "##NaN")))))))

;; ---------------------------------------------------------------------------
;; Bug: pipeline/group crashed with NPE when :source was absent from context.
;; The grouper needs :source for extract-source-range on opaque regions, but
;; the group stage only validated :raw-tokens, not :source.
;; Fix: added (string? (:source ctx)) guard to pipeline/group.
;; ---------------------------------------------------------------------------

(deftest group-requires-source
  (testing "group with nil :source throws clear pipeline error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"Pipeline :source"
          (pipeline/group {:raw-tokens []}))))
  (testing "group with non-string :source throws clear pipeline error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"Pipeline :source"
          (pipeline/group {:raw-tokens [] :source 42})))))
