(ns meme.regression.scan-test
  "Scar tissue: tokenizer regression tests.
   Every test here prevents a specific bug from recurring."
  (:require [clojure.test :refer [deftest is testing]]
            [meme.core :as core]
            [meme.emit.formatter.flat :as fmt-flat]
            [meme.forms :as forms]
            [meme.scan.tokenizer :as tokenizer]))

;; ---------------------------------------------------------------------------
;; Syntax-quote is parsed natively with meme rules inside.
;; ---------------------------------------------------------------------------

(deftest syntax-quote-native
  (testing "backtick on symbol produces a MemeSyntaxQuote node"
    (let [form (first (core/meme->forms "`foo"))]
      (is (some? form))
      (is (instance? meme.forms.MemeSyntaxQuote form))))
  (testing "backtick on call produces a MemeSyntaxQuote wrapping the call"
    (let [form (first (core/meme->forms "`a(b c)"))]
      (is (instance? meme.forms.MemeSyntaxQuote form))
      (is (= '(a b c) (:form form)))))
  (testing "backtick nested inside a call works"
    (let [form (first (core/meme->forms "foo(`bar)"))]
      (is (seq? form))
      (is (= 'foo (first form)))
      (is (instance? meme.forms.MemeSyntaxQuote (second form))))))

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
    (let [tokens (tokenizer/tokenize "#?(:clj ; comment with )\n 1)")]
      (is (= :reader-cond-start (:type (first tokens))))))
  (testing "#? with ; comment containing ] inside"
    (let [tokens (tokenizer/tokenize "#?(:clj ; ] in comment\n x)")]
      (is (= :reader-cond-start (:type (first tokens))))))
  (testing "#:ns{} with ; comment containing } inside — parses correctly"
    (let [tokens (tokenizer/tokenize "#:user{:name ; } tricky\n \"x\"}")]
      (is (= :namespaced-map-start (:type (first tokens)))))))

;; ---------------------------------------------------------------------------
;; Bug: read-balanced-raw didn't handle character literals. Bracket-like
;; chars (\), \(, etc.) corrupted the depth counter in opaque forms.
;; ---------------------------------------------------------------------------

(deftest char-literal-inside-reader-conditional
  (testing "#? with \\) char literal — passes through correctly"
    (let [tokens (tokenizer/tokenize "#?(:clj \\) :cljs \\x)")]
      (is (= :reader-cond-start (:type (first tokens))))))
  (testing "#? with \\( char literal — passes through correctly"
    (let [tokens (tokenizer/tokenize "#?(:clj \\( :cljs nil)")]
      (is (= :reader-cond-start (:type (first tokens))))))
  (testing "#? with \\[ and \\] char literals"
    (let [tokens (tokenizer/tokenize "#?(:clj [\\[ \\]] :cljs nil)")]
      (is (= :reader-cond-start (:type (first tokens))))))
  (testing "#? with \\{ and \\} char literals"
    (let [tokens (tokenizer/tokenize "#?(:clj {\\{ \\}} :cljs nil)")]
      (is (= :reader-cond-start (:type (first tokens))))))
  (testing "#:ns{} with \\} char literal — parses correctly"
    (let [tokens (tokenizer/tokenize "#:user{:ch \\}}")]
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
  (testing "`~foo produces MemeSyntaxQuote wrapping MemeUnquote"
    (let [form (first (core/meme->forms "`~foo"))]
      (is (instance? meme.forms.MemeSyntaxQuote form))
      (is (instance? meme.forms.MemeUnquote (:form form)))
      (is (= 'foo (:form (:form form))))))
  (testing "` + ~ tokenize as separate prefix tokens"
    (let [tokens (tokenizer/tokenize "`~foo")]
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
    (is (= [#?(:clj 'x :cljs 'y)] (core/meme->forms "#?(:clj x :cljs y)"))))
  (testing "#?(:default fallback) uses :default"
    (is (= '[fallback] (core/meme->forms "#?(:unknown x :default fallback)"))))
  ;; RT3-F14: #?() is now rejected (was silently accepted)
  (testing "#?() empty — now errors"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"[Rr]eader conditional requires"
                          (core/meme->forms "#?()")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: radix numbers for bases 17–36 need letters G-Z.
;; Bug: read-number only accepted hex digits a-f/A-F, so 36rZ split into
;; number token "36r" + symbol "Z" — a silent misparse.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest radix-numbers-high-bases
     (testing "36rZ — base-36, value preserved in MemeRaw"
       (let [form (first (core/meme->forms "36rZ"))]
         (is (= 35 (:value form)))
         (is (= "36rZ" (:raw form)))))
     (testing "16rFF — hex via radix notation"
       (let [form (first (core/meme->forms "16rFF"))]
         (is (= 255 (:value form)))))
     (testing "2r1010 — binary"
       (let [form (first (core/meme->forms "2r1010"))]
         (is (= 10 (:value form)))))
     (testing "36rHelloWorld — large base-36 number"
       (let [form (first (core/meme->forms "36rHelloWorld"))]
         (is (= 1767707668033969 (:value form)))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: backslash terminates symbol — foo\a is symbol + char literal.
;; Bug: symbol-char? didn't exclude \, so foo\a tokenized as one symbol.
;; ---------------------------------------------------------------------------

(deftest backslash-terminates-symbol
  (testing "foo\\a tokenizes as symbol + char"
    (let [tokens (tokenizer/tokenize "foo\\a")]
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
    (let [tokens (tokenizer/tokenize "`~\"foo\"")]
      (is (= :syntax-quote (:type (first tokens))))))
  (testing "`~\"foo\" produces MemeSyntaxQuote wrapping MemeUnquote of string"
    (let [form (first (core/meme->forms "`~\"foo\""))]
      (is (instance? meme.forms.MemeSyntaxQuote form))
      (is (= "foo" (:form (:form form)))))))

;; ---------------------------------------------------------------------------
;; B3: \uXXXX and \oXXX char literals.
;; Bug: \u0041 tokenized as \u (char) + 0041 (number) instead of \u0041.
;; ---------------------------------------------------------------------------

(deftest unicode-octal-char-literals
  (testing "\\u0041 tokenizes as single char token"
    (let [tokens (tokenizer/tokenize "\\u0041")]
      (is (= 1 (count tokens)))
      (is (= :char (:type (first tokens))))
      (is (= "\\u0041" (:value (first tokens))))))
  (testing "\\o101 tokenizes as single char token"
    (let [tokens (tokenizer/tokenize "\\o101")]
      (is (= 1 (count tokens)))
      (is (= :char (:type (first tokens))))
      (is (= "\\o101" (:value (first tokens))))))
  #?(:clj
     (testing "\\u0041 resolves to char A, preserves raw notation"
       (let [form (first (core/meme->forms "\\u0041"))]
         (is (= \A (:value form)))
         (is (= "\\u0041" (:raw form)))))))

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
    (is (= 1 (count (tokenizer/tokenize "\\u0041"))))
    (is (= :char (:type (first (tokenizer/tokenize "\\u0041"))))))
  (testing "valid \\o101 still works"
    (is (= 1 (count (tokenizer/tokenize "\\o101"))))
    (is (= :char (:type (first (tokenizer/tokenize "\\o101")))))))

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
    (is (= \A (:value (first (core/meme->forms "\\u0041"))))))
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
;; Bug: #() inside compound dispatch forms desynchronized bracket depth
;; tracking. :open-anon-fn was not in opening-type?.
;; ---------------------------------------------------------------------------

(deftest anon-fn-inside-reader-conditional
  (testing "#?(:clj #(inc(%)) :cljs identity) starts with reader-cond-start"
    (let [tokens (tokenizer/tokenize "#?(:clj #(inc(%)) :cljs identity)")]
      (is (= :reader-cond-start (:type (first tokens))))))
  #?(:clj
     (testing "#?(:clj #(inc(%)) :cljs identity) parses without error"
       (is (some? (core/meme->forms "#?(:clj #(inc(%)) :cljs identity)")))))
  (testing "#?@(:clj [#(+(%1 %2))] :cljs [identity]) starts with reader-cond-start"
    (let [tokens (tokenizer/tokenize "#?@(:clj [#(+(%1 %2))] :cljs [identity])")]
      (is (= :reader-cond-start (:type (first tokens)))))))

(deftest anon-fn-inside-syntax-quote
  (testing "` prefix token appears"
    (let [tokens (tokenizer/tokenize "`#(inc(%))")]
      (is (= :syntax-quote (:type (first tokens))))))
  (testing "`#(inc(%)) parses without error"
    (is (some? (core/meme->forms "`#(inc(%))")))))

#?(:clj
   (deftest anon-fn-inside-namespaced-map
     (testing "#:user{:f #(inc(%))} tokenizes with namespaced-map-start"
       (let [tokens (tokenizer/tokenize "#:user{:f #(inc(%))}")]
         (is (= :namespaced-map-start (:type (first tokens))))))
     (testing "#:user{:f #(inc(%))} parses without error"
       (is (some? (core/meme->forms "#:user{:f #(inc(%))}"))))))

;; ---------------------------------------------------------------------------
;; Bug: unclosed compound dispatch forms returned :invalid instead of :incomplete.
;; Fix: unterminated compound forms are detected as :incomplete.
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
    (let [tokens (tokenizer/tokenize "a/b")]
      (is (= 1 (count tokens)))
      (is (= "a/b" (:value (first tokens))))))
  (testing "a/b/c stops at first slash — two tokens"
    (let [tokens (tokenizer/tokenize "a/b/c")]
      (is (= 2 (count tokens)))
      (is (= "a/b" (:value (first tokens))))))
  (testing "clojure.string/join — dots are fine, one slash"
    (let [tokens (tokenizer/tokenize "clojure.string/join")]
      (is (= 1 (count tokens)))
      (is (= "clojure.string/join" (:value (first tokens)))))))

;; ---------------------------------------------------------------------------
;; Bug: clojure.core// was split into two tokens (clojure.core/ and /).
;; read-symbol-str stopped at the second / because it only allows one /
;; per symbol (namespace separator). But ns// is a valid symbol where
;; the name part is "/". Fix: after consuming the namespace /, check if
;; the next char is also / with no further symbol chars — if so, consume
;; it as the name.
;; ---------------------------------------------------------------------------

(deftest namespace-slash-symbol
  (testing "clojure.core// tokenizes as one symbol"
    (let [tokens (tokenizer/tokenize "clojure.core//")]
      (is (= 1 (count tokens)))
      (is (= :symbol (:type (first tokens))))
      (is (= "clojure.core//" (:value (first tokens))))))
  (testing "clojure.core// followed by space and form"
    (let [tokens (tokenizer/tokenize "clojure.core// foo")]
      (is (= 2 (count tokens)))
      (is (= "clojure.core//" (:value (first tokens))))
      (is (= "foo" (:value (second tokens))))))
  (testing "clojure.core// parses to one symbol"
    (is (= [(symbol "clojure.core" "/")]
           (core/meme->forms "clojure.core//"))))
  (testing "plain / still works"
    (is (= ['/] (core/meme->forms "/")))))

;; ---------------------------------------------------------------------------
;; Bug: `\char (syntax-quote of character literal) silently broke.
;; read-symbol-str returns "" for \ because backslash is not a symbol-char.
;; Same bug for `"string" and `~\char.
;; Fix: explicit \\ and \" handling before the symbol catch-all.
;; ---------------------------------------------------------------------------

(deftest syntax-quote-char-literal
  (testing "`\\a starts with :syntax-quote prefix"
    (is (= :syntax-quote (:type (first (tokenizer/tokenize "`\\a"))))))
  (testing "`\\a parses without error"
    (is (some? (core/meme->forms "`\\a")))))

(deftest syntax-quote-string-literal
  (testing "`\"foo\" starts with :syntax-quote prefix"
    (is (= :syntax-quote (:type (first (tokenizer/tokenize "`\"foo\""))))))
  (testing "`\"foo\" parses without error"
    (is (some? (core/meme->forms "`\"foo\"")))))

(deftest syntax-quote-unquote-char-literal
  (testing "`~\\a starts with :syntax-quote prefix"
    (is (= :syntax-quote (:type (first (tokenizer/tokenize "`~\\a"))))))
  (testing "`~\\a produces MemeSyntaxQuote wrapping MemeUnquote of char"
    (let [form (first (core/meme->forms "`~\\a"))]
      (is (instance? meme.forms.MemeSyntaxQuote form))
      (is (= \a (:form (:form form)))))))

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
    (let [tokens (tokenizer/tokenize "##Inf")]
      (is (= 1 (count tokens)))
      (is (= :number (:type (first tokens))))
      (is (= "##Inf" (:value (first tokens))))))
  (testing "##-Inf tokenizes as :number"
    (let [tokens (tokenizer/tokenize "##-Inf")]
      (is (= 1 (count tokens)))
      (is (= :number (:type (first tokens))))
      (is (= "##-Inf" (:value (first tokens))))))
  (testing "##NaN tokenizes as :number"
    (let [tokens (tokenizer/tokenize "##NaN")]
      (is (= 1 (count tokens)))
      (is (= :number (:type (first tokens))))
      (is (= "##NaN" (:value (first tokens))))))
  (testing "##Inf does not eat following form"
    (let [tokens (tokenizer/tokenize "##Inf 42")]
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
             printed (fmt-flat/format-forms forms)
             re-read (core/meme->forms printed)]
         (is (= "##Inf" printed))
         (is (= forms re-read))))
     (testing "##-Inf roundtrips"
       (let [forms (core/meme->forms "##-Inf")
             printed (fmt-flat/format-forms forms)]
         (is (= "##-Inf" printed))))
     (testing "##NaN prints as ##NaN"
       (is (= "##NaN" (fmt-flat/format-forms (core/meme->forms "##NaN")))))))

;; ---------------------------------------------------------------------------
;; EOF after backslash in string/regex — misleading error message.
;; Bug: "hello\ reported "Unterminated string — missing closing \"" instead of
;; "Incomplete escape sequence". The real problem is a trailing backslash, not
;; a missing quote. Must also signal :incomplete for REPL continuation.
;; ---------------------------------------------------------------------------

(deftest eof-after-backslash-in-string-incomplete
  (testing "\"hello\\ at EOF signals :incomplete with escape-specific message"
    (let [ex (try (core/meme->forms "\"hello\\")
                  (catch #?(:clj Exception :cljs :default) e e))]
      (is (:incomplete (ex-data ex)))
      (is (re-find #"(?i)escape" (ex-message ex))))))

(deftest eof-after-backslash-in-regex-incomplete
  (testing "#\"hello\\ at EOF signals :incomplete with escape-specific message"
    (let [ex (try (core/meme->forms "#\"hello\\")
                  (catch #?(:clj Exception :cljs :default) e e))]
      (is (:incomplete (ex-data ex)))
      (is (re-find #"(?i)escape" (ex-message ex))))))

;; ---------------------------------------------------------------------------
;; The group stage was collapsed into scan (it was a pass-through).
;; This scar tissue is retained as a note; the original bug is no longer
;; possible since scan validates :source and writes both :raw-tokens and :tokens.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; F1: #=, #<, #% were silently accepted as tagged literal tag prefixes.
;; Bug: tokenizer treated any symbol-start? char after # as a tag name,
;; including =, <, %. This produced Clojure output that Clojure's reader
;; misinterprets (#= triggers eval reader, #< is unreadable).
;; Fix: reject reserved Clojure dispatch characters before symbol-start? check.
;; ---------------------------------------------------------------------------

(deftest reserved-dispatch-chars-rejected
  (testing "#= is rejected"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Invalid dispatch: #="
                          (core/meme->forms "#=foo bar"))))
  (testing "#< is rejected"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Invalid dispatch: #<"
                          (core/meme->forms "#<foo bar"))))
  (testing "#% is rejected"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Invalid dispatch: #%"
                          (core/meme->forms "#%foo"))))
  #?(:clj
     (testing "valid tagged literals still work"
       (is (some? (core/meme->forms "#inst \"2024-01-01T00:00:00Z\"")))
       (is (some? (core/meme->forms "#uuid \"550e8400-e29b-41d4-a716-446655440000\"")))
       (is (some? (core/meme->forms "#foo/bar [1 2 3]"))))))

;; ---------------------------------------------------------------------------
;; F2: ::a::b, :::, ::a/ were silently accepted as valid keywords.
;; Bug: tokenizer's read-symbol-str consumed : as a symbol-char, producing
;; keyword tokens that Clojure's reader rejects as invalid tokens.
;; Fix: validate keyword syntax after scanning — reject embedded ::,
;; trailing /, and bare :: with no name.
;; ---------------------------------------------------------------------------

(deftest invalid-keyword-syntax-rejected
  (testing "::: (triple colon) is rejected"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"(?i)invalid keyword"
                          (core/meme->forms ":::"))))
  (testing "::a::b (double auto-resolve) is rejected"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"(?i)invalid keyword"
                          (core/meme->forms "::a::b"))))
  (testing "::a/ (trailing slash) is rejected"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"(?i)invalid keyword"
                          (core/meme->forms "::a/"))))
  (testing "valid keywords still work"
    #?(:clj (is (some? (core/meme->forms "::foo"))))
    #?(:clj (is (some? (core/meme->forms "::ns/name"))))
    (is (= [:regular] (core/meme->forms ":regular")))
    (is (= [:ns/name] (core/meme->forms ":ns/name")))))

;; ---------------------------------------------------------------------------
;; RT2-M11: Bare : (lone colon) was accepted as empty-name keyword.
;; Clojure rejects it: "Invalid token: :".
;; Fix: validate-keyword! now rejects empty non-auto keyword names.
;; ---------------------------------------------------------------------------

(deftest bare-colon-rejected
  (testing ": alone is rejected"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"(?i)invalid keyword"
                          (core/meme->forms ":"))))
  (testing ": before whitespace is rejected"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"(?i)invalid keyword"
                          (core/meme->forms ": foo"))))
  (testing ":foo still works"
    (is (= [:foo] (core/meme->forms ":foo")))))

;; ---------------------------------------------------------------------------
;; RT2-M3: foo/ (trailing slash) was accepted as a valid symbol.
;; Clojure rejects it: "Invalid token: foo/".
;; Fix: validate-symbol-name! rejects trailing / (except ns// pattern).
;; ---------------------------------------------------------------------------

(deftest trailing-slash-symbol-rejected
  (testing "foo/ is rejected"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"(?i)invalid symbol.*trailing /"
                          (core/meme->forms "foo/"))))
  (testing "clojure.core// still works (name part is /)"
    (is (= [(symbol "clojure.core" "/")]
           (core/meme->forms "clojure.core//")))))

;; ---------------------------------------------------------------------------
;; RT2-M4: foo/1bar (digit-starting name after /) was accepted.
;; Clojure rejects it: "Invalid token: foo/1bar".
;; Fix: validate-symbol-name! rejects digit-starting name after /.
;; ---------------------------------------------------------------------------

(deftest digit-starting-name-after-slash-rejected
  (testing "foo/1bar is rejected"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"(?i)invalid symbol.*digit"
                          (core/meme->forms "foo/1bar"))))
  (testing "foo/bar still works"
    (is (= ['foo/bar] (core/meme->forms "foo/bar"))))
  (testing ":foo/1bar keyword is also rejected"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"(?i)invalid symbol.*digit"
                          (core/meme->forms ":foo/1bar")))))

;; ---------------------------------------------------------------------------
;; RT2-M12: #:{:a 1} (empty namespace) was silently accepted.
;; Clojure rejects: "Namespaced map must specify a namespace".
;; Fix: tokenizer validates non-empty ns after #:.
;; ---------------------------------------------------------------------------

(deftest empty-namespace-map-rejected
  (testing "#:{} (empty ns) is rejected"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"(?i)namespace"
                          (core/meme->forms "#:{:a 1}"))))
  (testing "#:foo{} still works"
    (is (some? (core/meme->forms "#:foo{:a 1}")))))

;; ---------------------------------------------------------------------------
;; RT2-H3: #::{} auto-resolve namespaced map was broken — tokenizer
;; consumed : as part of ns-name via read-symbol-str.
;; Fix: dedicated auto-resolve handling in #: dispatch.
;; ---------------------------------------------------------------------------

(deftest auto-resolve-namespaced-map
  (testing "#::{:a 1} tokenizes with correct prefix"
    (let [tokens (tokenizer/tokenize "#::{:a 1}")]
      (is (= :namespaced-map-start (:type (first tokens))))
      (is (= "#::" (:value (first tokens))))))
  #?(:clj
     (testing "#::{:a 1} parses correctly"
       (is (some? (core/meme->forms "#::{:a 1}"))))))

;; ---------------------------------------------------------------------------
;; RT2-L10: ##foo was silently accepted and produced confusing error.
;; Fix: whitelist validation for ##Inf, ##-Inf, ##NaN only.
;; ---------------------------------------------------------------------------

(deftest invalid-symbolic-value-rejected
  (testing "##foo is rejected"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"(?i)invalid symbolic value"
                          (core/meme->forms "##foo"))))
  (testing "##Bar is rejected"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"(?i)invalid symbolic value"
                          (core/meme->forms "##Bar"))))
  (testing "valid symbolic values still work"
    (is (= 1 (count (tokenizer/tokenize "##Inf"))))
    (is (= 1 (count (tokenizer/tokenize "##-Inf"))))
    (is (= 1 (count (tokenizer/tokenize "##NaN"))))))

;; ---------------------------------------------------------------------------
;; RT2-L5/L6/L7: Null byte, zero-width space, RTL override in symbols.
;; Fix: unicode-control-char? predicate rejects control/invisible chars.
;; ---------------------------------------------------------------------------

(deftest unicode-control-chars-rejected
  (testing "null byte in source produces error (not silent whitespace)"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (core/meme->forms (str "f" \u0000 "oo")))))
  (testing "zero-width space in symbol produces error"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (core/meme->forms (str "f" \u200B "(x)")))))
  (testing "RTL override in symbol produces error"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (core/meme->forms (str "f(" \u202E "x)")))))
  (testing "BOM character at start of source is silently stripped"
    (is (= '[(f x)] (core/meme->forms (str \uFEFF "f(x)"))))))

;; ---------------------------------------------------------------------------
;; RT2-L9: read-number greedy — 1N.5 was one token, should be two.
;; Fix: N/M suffix terminates number scanning.
;; ---------------------------------------------------------------------------

(deftest number-suffix-terminates
  (testing "1N tokenizes as one :number token"
    (let [tokens (tokenizer/tokenize "1N")]
      (is (= 1 (count tokens)))
      (is (= "1N" (:value (first tokens))))))
  (testing "1N.5 tokenizes as two tokens (number + number)"
    (let [tokens (tokenizer/tokenize "1N.5")]
      (is (= 2 (count tokens)))
      (is (= "1N" (:value (first tokens))))))
  (testing "1M.5 tokenizes as two tokens"
    (let [tokens (tokenizer/tokenize "1M.5")]
      (is (= 2 (count tokens)))
      (is (= "1M" (:value (first tokens))))))
  (testing "1M still works as single token"
    (let [tokens (tokenizer/tokenize "1M")]
      (is (= 1 (count tokens)))
      (is (= "1M" (:value (first tokens)))))))

;; ---------------------------------------------------------------------------
;; RT2-L11: \r-only line endings — all tokens reported on line 1.
;; Fix: sadvance! treats bare \r as line break.
;; ---------------------------------------------------------------------------

(deftest bare-cr-line-tracking
  (testing "bare \\r increments line counter"
    (let [tokens (tokenizer/tokenize (str "a" \return "b"))]
      (is (= 2 (count tokens)))
      (is (= 1 (:line (first tokens))))
      (is (= 2 (:line (second tokens))))))
  (testing "\\r\\n (CRLF) counts as one line break"
    (let [tokens (tokenizer/tokenize (str "a" \return \newline "b"))]
      (is (= 2 (count tokens)))
      (is (= 1 (:line (first tokens))))
      (is (= 2 (:line (second tokens)))))))
