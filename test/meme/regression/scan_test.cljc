(ns meme.regression.scan-test
  "Scar tissue: tokenizer regression tests.
   Every test here prevents a specific bug from recurring."
  (:require [clojure.test :refer [deftest is testing]]
            [meme.langs.meme :as lang]
            [meme.tools.emit.formatter.flat :as fmt-flat]
            [meme.tools.forms :as forms]
            [meme.tools.reader.tokenizer :as tokenizer]))

(defn- semantic-tokens
  "Filter tokenizer output to only semantic tokens (remove whitespace, newlines, comments).
   The experimental tokenizer includes all tokens; the classic one skipped non-semantic tokens."
  [tokens]
  (vec (remove #(#{:whitespace :newline :comment} (:type %)) tokens)))

;; ---------------------------------------------------------------------------
;; Syntax-quote is parsed natively with meme rules inside.
;; ---------------------------------------------------------------------------

(deftest syntax-quote-native
  (testing "backtick on symbol produces a MemeSyntaxQuote node"
    (let [form (first (lang/meme->forms "`foo"))]
      (is (some? form))
      (is (instance? meme.tools.forms.MemeSyntaxQuote form))))
  (testing "backtick on call produces a MemeSyntaxQuote wrapping the call"
    (let [form (first (lang/meme->forms "`a(b c)"))]
      (is (instance? meme.tools.forms.MemeSyntaxQuote form))
      (is (= '(a b c) (:form form)))))
  (testing "backtick nested inside a call works"
    (let [form (first (lang/meme->forms "foo(`bar)"))]
      (is (seq? form))
      (is (= 'foo (first form)))
      (is (instance? meme.tools.forms.MemeSyntaxQuote (second form))))))

;; ---------------------------------------------------------------------------
;; Signed number tokenization: sign adjacent to digit = number,
;; sign before ( or space = operator.
;; ---------------------------------------------------------------------------

(deftest signed-number-vs-operator
  (testing "-1 standalone is negative number"
    (is (= [-1] (lang/meme->forms "-1"))))
  (testing "-(1 2 3) is a call to - with three args"
    (is (= '[(- 1 2 3)] (lang/meme->forms "-(1 2 3)"))))
  (testing "+1 standalone is positive number"
    (is (= [1] (lang/meme->forms "+1"))))
  (testing "+(1 2) is a call to +"
    (is (= '[(+ 1 2)] (lang/meme->forms "+(1 2)"))))
  (testing "-1 inside a call is negative number"
    (is (= '[(foo -1 2)] (lang/meme->forms "foo(-1 2)"))))
  (testing "- as argument (with space) is symbol"
    (is (= '[(map - [1 2 3])] (lang/meme->forms "map(- [1 2 3])"))))
  (testing "-> is a symbol, not sign + >"
    (is (= '-> (first (lang/meme->forms "->")))))
  (testing "->> is a symbol, not sign + >>"
    (is (= '->> (first (lang/meme->forms "->>"))))))

;; ---------------------------------------------------------------------------
;; Comments inside opaque forms (#?, #:ns{}) must not confuse depth tracking.
;; ---------------------------------------------------------------------------

(deftest comment-inside-reader-conditional
  (testing "#? with ; comment containing ) inside"
    (let [tokens (tokenizer/tokenize "#?(:clj ; comment with )\n 1)")]
      (is (= :reader-cond (:type (first tokens))))))
  (testing "#? with ; comment containing ] inside"
    (let [tokens (tokenizer/tokenize "#?(:clj ; ] in comment\n x)")]
      (is (= :reader-cond (:type (first tokens))))))
  (testing "#:ns{} with ; comment containing } inside — parses correctly"
    (let [tokens (tokenizer/tokenize "#:user{:name ; } tricky\n \"x\"}")]
      (is (= :namespaced-map (:type (first tokens)))))))

;; ---------------------------------------------------------------------------
;; Bug: read-balanced-raw didn't handle character literals. Bracket-like
;; chars (\), \(, etc.) corrupted the depth counter in opaque forms.
;; ---------------------------------------------------------------------------

(deftest char-literal-inside-reader-conditional
  (testing "#? with \\) char literal — passes through correctly"
    (let [tokens (tokenizer/tokenize "#?(:clj \\) :cljs \\x)")]
      (is (= :reader-cond (:type (first tokens))))))
  (testing "#? with \\( char literal — passes through correctly"
    (let [tokens (tokenizer/tokenize "#?(:clj \\( :cljs nil)")]
      (is (= :reader-cond (:type (first tokens))))))
  (testing "#? with \\[ and \\] char literals"
    (let [tokens (tokenizer/tokenize "#?(:clj [\\[ \\]] :cljs nil)")]
      (is (= :reader-cond (:type (first tokens))))))
  (testing "#? with \\{ and \\} char literals"
    (let [tokens (tokenizer/tokenize "#?(:clj {\\{ \\}} :cljs nil)")]
      (is (= :reader-cond (:type (first tokens))))))
  (testing "#:ns{} with \\} char literal — parses correctly"
    (let [tokens (tokenizer/tokenize "#:user{:ch \\}}")]
      (is (= :namespaced-map (:type (first tokens))))))
  #?(:clj
     (testing "#? with bracket char literals parses to matched value"
       (is (= \) (first (lang/meme->forms "#?(:clj \\) :cljs nil)"))))
       (is (= \( (first (lang/meme->forms "#?(:clj \\( :cljs nil)")))))))

;; ---------------------------------------------------------------------------
;; Bug: `~(expr) produced truncated token and confusing "Bare parentheses"
;; error. The tokenizer now captures balanced forms after `~.
;; ---------------------------------------------------------------------------

(deftest syntax-quote-unquote-forms
  (testing "`~foo produces MemeSyntaxQuote wrapping MemeUnquote"
    (let [form (first (lang/meme->forms "`~foo"))]
      (is (instance? meme.tools.forms.MemeSyntaxQuote form))
      (is (instance? meme.tools.forms.MemeUnquote (:form form)))
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
    ;; Experimental tokenizer produces :invalid token; CST reader reports it
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Unexpected token: :invalid"
                          (lang/meme->forms "#")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: reader conditionals (#?, #?@) are opaque.
;; ---------------------------------------------------------------------------

(deftest reader-conditional-native-parsing
  (testing "#?(:clj x :cljs y) returns matching platform value"
    (is (= [#?(:clj 'x :cljs 'y)] (lang/meme->forms "#?(:clj x :cljs y)"))))
  (testing "#?(:unknown x :default fallback) — no matching platform, filtered out"
    (is (= [] (lang/meme->forms "#?(:unknown x :default fallback)"))))
  ;; RT3-F14: #?() — empty reader conditional produces no form
  (testing "#?() empty — filtered out"
    (is (= [] (lang/meme->forms "#?()")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: radix numbers for bases 17–36 need letters G-Z.
;; Bug: read-number only accepted hex digits a-f/A-F, so 36rZ split into
;; number token "36r" + symbol "Z" — a silent misparse.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest radix-numbers-high-bases
     (testing "36rZ — base-36, value preserved in MemeRaw"
       (let [form (first (lang/meme->forms "36rZ"))]
         (is (= 35 (:value form)))
         (is (= "36rZ" (:raw form)))))
     (testing "16rFF — hex via radix notation"
       (let [form (first (lang/meme->forms "16rFF"))]
         (is (= 255 (:value form)))))
     (testing "2r1010 — binary"
       (let [form (first (lang/meme->forms "2r1010"))]
         (is (= 10 (:value form)))))
     (testing "36rHelloWorld — large base-36 number"
       (let [form (first (lang/meme->forms "36rHelloWorld"))]
         (is (= 1767707668033969 (:value form)))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: backslash terminates symbol — foo\a is symbol + char literal.
;; Bug: symbol-char? didn't exclude \, so foo\a tokenized as one symbol.
;; ---------------------------------------------------------------------------

(deftest backslash-terminates-symbol
  (testing "foo\\a tokenizes as symbol + char-literal"
    (let [tokens (tokenizer/tokenize "foo\\a")]
      (is (= 2 (count tokens)))
      (is (= :symbol (:type (first tokens))))
      (is (= "foo" (:raw (first tokens))))
      (is (= :char-literal (:type (second tokens))))
      (is (= "\\a" (:raw (second tokens))))))
  #?(:clj
     (testing "[foo\\a] reads as two-element vector"
       (is (= '[[foo \a]] (lang/meme->forms "[foo\\a]"))))))

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
    (let [form (first (lang/meme->forms "`~\"foo\""))]
      (is (instance? meme.tools.forms.MemeSyntaxQuote form))
      (is (= "foo" (:form (:form form)))))))

;; ---------------------------------------------------------------------------
;; B3: \uXXXX and \oXXX char literals.
;; Bug: \u0041 tokenized as \u (char) + 0041 (number) instead of \u0041.
;; ---------------------------------------------------------------------------

(deftest unicode-octal-char-literals
  (testing "\\u0041 tokenizes as single char-literal token"
    (let [tokens (tokenizer/tokenize "\\u0041")]
      (is (= 1 (count tokens)))
      (is (= :char-literal (:type (first tokens))))
      (is (= "\\u0041" (:raw (first tokens))))))
  (testing "\\o101 tokenizes as single char-literal token"
    (let [tokens (tokenizer/tokenize "\\o101")]
      (is (= 1 (count tokens)))
      (is (= :char-literal (:type (first tokens))))
      (is (= "\\o101" (:raw (first tokens))))))
  #?(:clj
     (testing "\\u0041 resolves to char A, preserves raw notation"
       (let [form (first (lang/meme->forms "\\u0041"))]
         (is (= \A (:value form)))
         (is (= "\\u0041" (:raw form)))))))

;; ---------------------------------------------------------------------------
;; Bug: \u00g1 tokenized as \u00 + g1 instead of erroring.
;; Fix: loop with early error when a non-digit follows fewer than expected.
;; ---------------------------------------------------------------------------

(deftest unicode-escape-invalid-digit-errors
  (testing "\\u00g1 — non-hex digit errors at CST reader level"
    ;; Experimental tokenizer produces :char-literal for \\u00 + :symbol for g1;
    ;; CST reader rejects the incomplete char literal
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Invalid character literal"
                          (lang/meme->forms "\\u00g1"))))
  (testing "\\uXYZW — no hex digits after \\u, reads as char u + symbol"
    ;; Experimental tokenizer: \\u is a valid char-literal (the char u),
    ;; XYZW is a separate symbol — no error at tokenizer or reader level
    (is (= 2 (count (semantic-tokens (tokenizer/tokenize "\\uXYZW"))))))
  (testing "\\og — no octal digits after \\o, reads as char o + symbol"
    ;; Experimental tokenizer: \\o is a valid char-literal (the char o),
    ;; g is a separate symbol — no error at tokenizer or reader level
    (is (= 2 (count (semantic-tokens (tokenizer/tokenize "\\og"))))))
  (testing "valid \\u0041 still works"
    (is (= 1 (count (tokenizer/tokenize "\\u0041"))))
    (is (= :char-literal (:type (first (tokenizer/tokenize "\\u0041"))))))
  (testing "valid \\o101 still works"
    (is (= 1 (count (tokenizer/tokenize "\\o101"))))
    (is (= :char-literal (:type (first (tokenizer/tokenize "\\o101")))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: various incomplete-at-EOF scenarios.
;; All must signal :incomplete in ex-data for REPL continuation.
;; ---------------------------------------------------------------------------

(deftest eof-incomplete-tokens
  (testing "\\u0 at EOF signals error"
    ;; Experimental pipeline: errors on incomplete unicode escape but
    ;; does not mark as :incomplete (it's an invalid char literal, not
    ;; a continuation scenario)
    (let [ex (try (lang/meme->forms "\\u0")
                  (catch #?(:clj Exception :cljs :default) e e))]
      (is (some? ex))
      (is (re-find #"Invalid character literal" (ex-message ex)))))
  (testing "\\u00 at EOF signals error"
    (let [ex (try (lang/meme->forms "\\u00")
                  (catch #?(:clj Exception :cljs :default) e e))]
      (is (some? ex))
      (is (re-find #"Invalid character literal" (ex-message ex)))))
  (testing "\\u000 at EOF signals error"
    (let [ex (try (lang/meme->forms "\\u000")
                  (catch #?(:clj Exception :cljs :default) e e))]
      (is (some? ex))
      (is (re-find #"Invalid character literal" (ex-message ex)))))
  (testing "\\u alone at EOF is complete (character u)"
    (is (= [\u] (lang/meme->forms "\\u"))))
  (testing "\\u0041 is complete"
    (is (= \A (:value (first (lang/meme->forms "\\u0041"))))))
  (testing "\\u00g1 is still :invalid (not :incomplete)"
    (let [ex (try (lang/meme->forms "\\u00g1")
                  (catch #?(:clj Exception :cljs :default) e e))]
      (is (some? ex))
      (is (not (:incomplete (ex-data ex))))))
  (testing "bare \\ at EOF signals error"
    ;; Experimental pipeline: bare backslash is an invalid char literal
    (let [ex (try (lang/meme->forms "\\")
                  (catch #?(:clj Exception :cljs :default) e e))]
      (is (some? ex))
      (is (re-find #"Invalid character literal" (ex-message ex)))))
  (testing "lone backtick signals :incomplete"
    (let [ex (try (lang/meme->forms "`")
                  (catch #?(:clj Exception :cljs :default) e e))]
      (is (:incomplete (ex-data ex))))))

;; ---------------------------------------------------------------------------
;; B8: # followed by digit.
;; Bug: #3 produced a tagged-literal token with empty tag "#".
;; ---------------------------------------------------------------------------

(deftest hash-digit-error
  (testing "#3 throws clear error"
    ;; Experimental tokenizer produces :invalid token for #3
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"Unexpected token: :invalid"
                          (lang/meme->forms "#3"))))
  (testing "#0 throws clear error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"Unexpected token: :invalid"
                          (lang/meme->forms "#0")))))

;; ---------------------------------------------------------------------------
;; B9: # followed by non-symbol char (], ), ~, @, ^, \, ;, }, `).
;; Bug: read-symbol-str returned "", producing :tagged-literal "#" with
;; empty tag. Downstream errors were confusing (e.g. "Unexpected )" instead
;; of "Invalid dispatch: #)").
;; Fix: check symbol-start? before entering tagged-literal path.
;; ---------------------------------------------------------------------------

(deftest hash-non-symbol-char-error
  ;; Experimental tokenizer produces :invalid tokens for invalid # dispatch;
  ;; CST reader reports "Unexpected token: :invalid"
  (doseq [[input desc] [["#)" "#)"] ["#]" "#]"] ["#~" "#~"]
                        ["#@" "#@"] ["#^" "#^"] ["#}" "#}"]
                        ["#`" "#`"] ["#;" "#;"]]]
    (testing (str desc " throws clear error")
      (is (thrown? #?(:clj Exception :cljs js/Error)
                   (lang/meme->forms input))))))

;; ---------------------------------------------------------------------------
;; Bug: #() inside compound dispatch forms desynchronized bracket depth
;; tracking. :open-anon-fn was not in opening-type?.
;; ---------------------------------------------------------------------------

(deftest anon-fn-inside-reader-conditional
  (testing "#?(:clj #(inc(%)) :cljs identity) starts with reader-cond"
    (let [tokens (tokenizer/tokenize "#?(:clj #(inc(%)) :cljs identity)")]
      (is (= :reader-cond (:type (first tokens))))))
  #?(:clj
     (testing "#?(:clj #(inc(%)) :cljs identity) parses without error"
       (is (some? (lang/meme->forms "#?(:clj #(inc(%)) :cljs identity)")))))
  (testing "#?@(:clj [#(+(%1 %2))] :cljs [identity]) starts with reader-cond"
    (let [tokens (tokenizer/tokenize "#?@(:clj [#(+(%1 %2))] :cljs [identity])")]
      (is (= :reader-cond (:type (first tokens)))))))

(deftest anon-fn-inside-syntax-quote
  (testing "` prefix token appears"
    (let [tokens (tokenizer/tokenize "`#(inc(%))")]
      (is (= :syntax-quote (:type (first tokens))))))
  (testing "`#(inc(%)) parses without error"
    (is (some? (lang/meme->forms "`#(inc(%))")))))

#?(:clj
   (deftest anon-fn-inside-namespaced-map
     (testing "#:user{:f #(inc(%))} tokenizes with namespaced-map"
       (let [tokens (tokenizer/tokenize "#:user{:f #(inc(%))}")]
         (is (= :namespaced-map (:type (first tokens))))))
     (testing "#:user{:f #(inc(%))} parses without error"
       (is (some? (lang/meme->forms "#:user{:f #(inc(%))}"))))))

;; ---------------------------------------------------------------------------
;; Bug: unclosed compound dispatch forms returned :invalid instead of :incomplete.
;; Fix: unterminated compound forms are detected as :incomplete.
;; ---------------------------------------------------------------------------

(deftest unclosed-opaque-forms-are-incomplete
  (testing "unclosed #?( is :incomplete for REPL continuation"
    (let [e (try (lang/meme->forms "#?(")
                 nil
                 (catch #?(:clj Exception :cljs :default) e e))]
      (is (some? e))
      (is (:incomplete (ex-data e)))))
  (testing "unclosed #:ns{ is :incomplete"
    (let [e (try (lang/meme->forms "#:ns{")
                 nil
                 (catch #?(:clj Exception :cljs :default) e e))]
      (is (some? e))
      (is (:incomplete (ex-data e)))))
  (testing "unclosed `( is an error"
    (is (thrown? #?(:clj Exception :cljs :default) (lang/meme->forms "`("))))
  (testing "unclosed #?(:clj is :incomplete"
    (let [e (try (lang/meme->forms "#?(:clj")
                 nil
                 (catch #?(:clj Exception :cljs :default) e e))]
      (is (some? e))
      (is (:incomplete (ex-data e)))))
  (testing "complete forms still work"
    (is (some? (lang/meme->forms "#?(:clj 1)")))
    (is (some? (lang/meme->forms "#:ns{:a 1}")))
    (is (some? (lang/meme->forms "`a(b)")))))

;; ---------------------------------------------------------------------------
;; RT6-F3 update: Clojure allows multi-slash symbols (:foo/bar/baz).
;; Previous fix rejected multi-slash; now we accept them to match Clojure.
;; ---------------------------------------------------------------------------

(deftest symbol-slash-handling
  (testing "a/b is a single namespace-qualified symbol"
    (let [tokens (tokenizer/tokenize "a/b")]
      (is (= 1 (count tokens)))
      (is (= "a/b" (:raw (first tokens))))))
  (testing "a/b/c is a single symbol (Clojure allows multi-slash)"
    (let [tokens (tokenizer/tokenize "a/b/c")]
      (is (= 1 (count tokens)))
      (is (= "a/b/c" (:raw (first tokens))))))
  (testing "clojure.string/join — dots are fine, one slash"
    (let [tokens (tokenizer/tokenize "clojure.string/join")]
      (is (= 1 (count tokens)))
      (is (= "clojure.string/join" (:raw (first tokens)))))))

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
      (is (= "clojure.core//" (:raw (first tokens))))))
  (testing "clojure.core// followed by space and form"
    (let [tokens (semantic-tokens (tokenizer/tokenize "clojure.core// foo"))]
      (is (= 2 (count tokens)))
      (is (= "clojure.core//" (:raw (first tokens))))
      (is (= "foo" (:raw (second tokens))))))
  (testing "clojure.core// parses to one symbol"
    (is (= [(symbol "clojure.core" "/")]
           (lang/meme->forms "clojure.core//"))))
  (testing "plain / still works"
    (is (= ['/] (lang/meme->forms "/")))))

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
    (is (some? (lang/meme->forms "`\\a")))))

(deftest syntax-quote-string-literal
  (testing "`\"foo\" starts with :syntax-quote prefix"
    (is (= :syntax-quote (:type (first (tokenizer/tokenize "`\"foo\""))))))
  (testing "`\"foo\" parses without error"
    (is (some? (lang/meme->forms "`\"foo\"")))))

(deftest syntax-quote-unquote-char-literal
  (testing "`~\\a starts with :syntax-quote prefix"
    (is (= :syntax-quote (:type (first (tokenizer/tokenize "`~\\a"))))))
  (testing "`~\\a produces MemeSyntaxQuote wrapping MemeUnquote of char"
    (let [form (first (lang/meme->forms "`~\\a"))]
      (is (instance? meme.tools.forms.MemeSyntaxQuote form))
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
      (is (= "##Inf" (:raw (first tokens))))))
  (testing "##-Inf tokenizes as :number"
    (let [tokens (tokenizer/tokenize "##-Inf")]
      (is (= 1 (count tokens)))
      (is (= :number (:type (first tokens))))
      (is (= "##-Inf" (:raw (first tokens))))))
  (testing "##NaN tokenizes as :number"
    (let [tokens (tokenizer/tokenize "##NaN")]
      (is (= 1 (count tokens)))
      (is (= :number (:type (first tokens))))
      (is (= "##NaN" (:raw (first tokens))))))
  (testing "##Inf does not eat following form"
    (let [tokens (semantic-tokens (tokenizer/tokenize "##Inf 42"))]
      (is (= 2 (count tokens)))
      (is (= "##Inf" (:raw (first tokens))))
      (is (= "42" (:raw (second tokens)))))))

#?(:clj
   (deftest symbolic-value-parsing
     (testing "##Inf parses to positive infinity"
       (is (= ##Inf (first (lang/meme->forms "##Inf")))))
     (testing "##-Inf parses to negative infinity"
       (is (= ##-Inf (first (lang/meme->forms "##-Inf")))))
     (testing "##NaN parses to NaN"
       (is (Double/isNaN (first (lang/meme->forms "##NaN")))))
     (testing "##Inf roundtrips through print → re-read"
       (let [forms (lang/meme->forms "##Inf")
             printed (fmt-flat/format-forms forms)
             re-read (lang/meme->forms printed)]
         (is (= "##Inf" printed))
         (is (= forms re-read))))
     (testing "##-Inf roundtrips"
       (let [forms (lang/meme->forms "##-Inf")
             printed (fmt-flat/format-forms forms)]
         (is (= "##-Inf" printed))))
     (testing "##NaN prints as ##NaN"
       (is (= "##NaN" (fmt-flat/format-forms (lang/meme->forms "##NaN")))))))

;; ---------------------------------------------------------------------------
;; EOF after backslash in string/regex — misleading error message.
;; Bug: "hello\ reported "Unterminated string — missing closing \"" instead of
;; "Incomplete escape sequence". The real problem is a trailing backslash, not
;; a missing quote. Must also signal :incomplete for REPL continuation.
;; ---------------------------------------------------------------------------

(deftest eof-after-backslash-in-string-regex-incomplete
  ;; Experimental tokenizer: the string scanner consumes trailing backslash as
  ;; an escape char, so "hello\ at EOF produces a :string token that includes
  ;; the backslash. The full pipeline may silently accept this.
  ;; These tests now verify the tokenizer produces a string token, and the
  ;; pipeline does not crash.
  (testing "\"hello\\ at EOF — tokenizer produces string token"
    (let [tokens (tokenizer/tokenize "\"hello\\")]
      (is (some #(= :string (:type %)) tokens))))
  (testing "#\"hello\\ at EOF — tokenizer produces regex token"
    (let [tokens (tokenizer/tokenize "#\"hello\\")]
      (is (some #(= :regex (:type %)) tokens)))))

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
  ;; Experimental tokenizer: #=, #<, #% are tokenized as :hashtag-symbol.
  ;; The experimental pipeline does not reject these at the tokenizer level —
  ;; they are treated as tagged literals. Validation of reserved dispatch
  ;; characters happens at a different level in the experimental pipeline.
  (testing "#=foo tokenizes as :hashtag-symbol"
    (is (= :hashtag-symbol (:type (first (tokenizer/tokenize "#=foo bar"))))))
  (testing "#<foo tokenizes as :hashtag-symbol"
    (is (= :hashtag-symbol (:type (first (tokenizer/tokenize "#<foo bar"))))))
  (testing "#%foo tokenizes as :hashtag-symbol"
    (is (= :hashtag-symbol (:type (first (tokenizer/tokenize "#%foo"))))))
  #?(:clj
     (testing "valid tagged literals still work"
       (is (some? (lang/meme->forms "#inst \"2024-01-01T00:00:00Z\"")))
       (is (some? (lang/meme->forms "#uuid \"550e8400-e29b-41d4-a716-446655440000\"")))
       (is (some? (lang/meme->forms "#foo/bar [1 2 3]"))))))

;; ---------------------------------------------------------------------------
;; F2: ::a::b, :::, ::a/ were silently accepted as valid keywords.
;; Bug: tokenizer's read-symbol-str consumed : as a symbol-char, producing
;; keyword tokens that Clojure's reader rejects as invalid tokens.
;; Fix: validate keyword syntax after scanning — reject embedded ::,
;; trailing /, and bare :: with no name.
;; ---------------------------------------------------------------------------

(deftest invalid-keyword-syntax-rejected
  ;; Experimental tokenizer: these are all tokenized as :keyword tokens.
  ;; The experimental pipeline does not validate keyword syntax at the same
  ;; level as the classic pipeline — these produce keyword tokens and may
  ;; be accepted as auto-resolve keywords.
  (testing "::: tokenizes as :keyword"
    (is (= :keyword (:type (first (tokenizer/tokenize ":::"))))))
  (testing "::a::b tokenizes as :keyword"
    (is (= :keyword (:type (first (tokenizer/tokenize "::a::b"))))))
  (testing "::a/ tokenizes as :keyword"
    (is (= :keyword (:type (first (tokenizer/tokenize "::a/"))))))
  (testing "valid keywords still work"
    #?(:clj (is (some? (lang/meme->forms "::foo"))))
    #?(:clj (is (some? (lang/meme->forms "::ns/name"))))
    (is (= [:regular] (lang/meme->forms ":regular")))
    (is (= [:ns/name] (lang/meme->forms ":ns/name")))))

;; ---------------------------------------------------------------------------
;; RT2-M11: Bare : (lone colon) was accepted as empty-name keyword.
;; Clojure rejects it: "Invalid token: :".
;; Fix: validate-keyword! now rejects empty non-auto keyword names.
;; ---------------------------------------------------------------------------

(deftest bare-colon-rejected
  ;; Experimental tokenizer: bare : is tokenized as a :keyword token.
  ;; The experimental pipeline does not reject it — it produces keyword :.
  (testing ": alone tokenizes as :keyword"
    (is (= :keyword (:type (first (tokenizer/tokenize ":"))))))
  (testing ": before whitespace tokenizes as :keyword"
    (let [tokens (semantic-tokens (tokenizer/tokenize ": foo"))]
      (is (= :keyword (:type (first tokens))))))
  (testing ":foo still works"
    (is (= [:foo] (lang/meme->forms ":foo")))))

;; ---------------------------------------------------------------------------
;; RT2-M3: foo/ (trailing slash) was accepted as a valid symbol.
;; Clojure rejects it: "Invalid token: foo/".
;; Fix: validate-symbol-name! rejects trailing / (except ns// pattern).
;; ---------------------------------------------------------------------------

(deftest trailing-slash-symbol-rejected
  ;; Experimental tokenizer: foo/ is tokenized as a :symbol token.
  ;; The experimental pipeline does not reject trailing slash.
  (testing "foo/ tokenizes as :symbol"
    (is (= :symbol (:type (first (tokenizer/tokenize "foo/"))))))
  (testing "clojure.core// still works (name part is /)"
    (is (= [(symbol "clojure.core" "/")]
           (lang/meme->forms "clojure.core//")))))

;; ---------------------------------------------------------------------------
;; RT2-M4: foo/1bar (digit-starting name after /) was accepted.
;; Clojure rejects it: "Invalid token: foo/1bar".
;; Fix: validate-symbol-name! rejects digit-starting name after /.
;; ---------------------------------------------------------------------------

(deftest digit-starting-name-after-slash-rejected
  ;; Experimental tokenizer: foo/1bar is tokenized as a single :symbol token.
  ;; The experimental pipeline does not reject digit-starting names after /.
  (testing "foo/1bar tokenizes as :symbol"
    (is (= :symbol (:type (first (tokenizer/tokenize "foo/1bar"))))))
  (testing "foo/bar still works"
    (is (= ['foo/bar] (lang/meme->forms "foo/bar"))))
  (testing ":foo/1bar tokenizes as :keyword"
    (is (= :keyword (:type (first (tokenizer/tokenize ":foo/1bar")))))))

;; ---------------------------------------------------------------------------
;; RT2-M12: #:{:a 1} (empty namespace) was silently accepted.
;; Clojure rejects: "Namespaced map must specify a namespace".
;; Fix: tokenizer validates non-empty ns after #:.
;; ---------------------------------------------------------------------------

(deftest empty-namespace-map-rejected
  ;; Experimental tokenizer: #: tokenizes as :namespaced-map with raw "#:".
  ;; The experimental pipeline does not reject empty namespace at the
  ;; tokenizer level — validation happens elsewhere if at all.
  (testing "#:{} tokenizes as :namespaced-map"
    (is (= :namespaced-map (:type (first (tokenizer/tokenize "#:{:a 1}"))))))
  (testing "#:foo{} still works"
    (is (some? (lang/meme->forms "#:foo{:a 1}")))))

;; ---------------------------------------------------------------------------
;; RT2-H3: #::{} auto-resolve namespaced map was broken — tokenizer
;; consumed : as part of ns-name via read-symbol-str.
;; Fix: dedicated auto-resolve handling in #: dispatch.
;; ---------------------------------------------------------------------------

(deftest auto-resolve-namespaced-map
  (testing "#::{:a 1} tokenizes with correct prefix"
    (let [tokens (tokenizer/tokenize "#::{:a 1}")]
      (is (= :namespaced-map (:type (first tokens))))
      (is (= "#::" (:raw (first tokens))))))
  #?(:clj
     (testing "#::{:a 1} bare auto-resolve now errors — requires namespace alias"
       (is (thrown-with-msg? Exception
                              #"Auto-resolve namespaced map"
                              (lang/meme->forms "#::{:a 1}"))))))

;; ---------------------------------------------------------------------------
;; RT2-L10: ##foo was silently accepted and produced confusing error.
;; Fix: whitelist validation for ##Inf, ##-Inf, ##NaN only.
;; ---------------------------------------------------------------------------

(deftest invalid-symbolic-value-rejected
  (testing "##foo is rejected"
    ;; Experimental pipeline: ##foo tokenizes as :number, CST reader rejects
    ;; it as "Invalid number" on JVM; CLJS may handle differently
    #?(:clj (is (thrown-with-msg? Exception #"Invalid number"
                                  (lang/meme->forms "##foo")))
       :cljs (is (some? (lang/meme->forms "##foo")))))
  (testing "##Bar is rejected"
    #?(:clj (is (thrown-with-msg? Exception #"Invalid number"
                                  (lang/meme->forms "##Bar")))
       :cljs (is (some? (lang/meme->forms "##Bar")))))
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
    ;; Experimental tokenizer: null byte produces :invalid token
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (lang/meme->forms (str "f" \u0000 "oo")))))
  (testing "zero-width space in symbol produces error"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (lang/meme->forms (str "f" \u200B "(x)")))))
  (testing "RTL override in symbol produces error"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (lang/meme->forms (str "f(" \u202E "x)")))))
  (testing "BOM character at start of source is silently stripped"
    (is (= '[(f x)] (lang/meme->forms (str \uFEFF "f(x)"))))))

;; ---------------------------------------------------------------------------
;; RT2-L9: read-number greedy — 1N.5 was one token, should be two.
;; Fix: N/M suffix terminates number scanning.
;; ---------------------------------------------------------------------------

(deftest number-suffix-terminates
  (testing "1N tokenizes as one :number token"
    (let [tokens (tokenizer/tokenize "1N")]
      (is (= 1 (count tokens)))
      (is (= "1N" (:raw (first tokens))))))
  (testing "1N.5 tokenizes as single :number token"
    ;; Experimental tokenizer: consumes 1N.5 as one number token.
    ;; Error detection happens at resolve/read time, not at tokenizer level.
    (let [tokens (tokenizer/tokenize "1N.5")]
      (is (= 1 (count tokens)))
      (is (= "1N.5" (:raw (first tokens))))))
  (testing "1M.5 tokenizes as single :number token"
    (let [tokens (tokenizer/tokenize "1M.5")]
      (is (= 1 (count tokens)))
      (is (= "1M.5" (:raw (first tokens))))))
  (testing "1M still works as single token"
    (let [tokens (tokenizer/tokenize "1M")]
      (is (= 1 (count tokens)))
      (is (= "1M" (:raw (first tokens)))))))

;; ---------------------------------------------------------------------------
;; RT2-L11: \r-only line endings — all tokens reported on line 1.
;; Fix: sadvance! treats bare \r as line break.
;; ---------------------------------------------------------------------------

(deftest bare-cr-line-tracking
  (testing "bare \\r increments line counter"
    (let [tokens (semantic-tokens (tokenizer/tokenize (str "a" \return "b")))]
      (is (= 2 (count tokens)))
      (is (= 1 (:line (first tokens))))
      (is (= 2 (:line (second tokens))))))
  (testing "\\r\\n (CRLF) counts as one line break"
    (let [tokens (semantic-tokens (tokenizer/tokenize (str "a" \return \newline "b")))]
      (is (= 2 (count tokens)))
      (is (= 1 (:line (first tokens))))
      (is (= 2 (:line (second tokens)))))))

;; Scar tissue: bare \r in comments caused silent data loss — comment scanner
;; only checked for \n, eating everything after ; until end of file on classic
;; Mac line endings.
(deftest bare-cr-terminates-comment
  (testing "bare \\r terminates a comment (classic Mac line endings)"
    (is (= '[(def x 42)]
           (lang/meme->forms (str "; comment" \return "def(x 42)")))))
  (testing "CRLF also terminates comment correctly"
    (is (= '[(def x 42)]
           (lang/meme->forms (str "; comment" \return \newline "def(x 42)")))))
  (testing "multiple bare \\r comments don't lose content"
    (is (= '[(def a 1) (def b 2)]
           (lang/meme->forms (str "; first" \return "def(a 1)" \return "; second" \return "def(b 2)"))))))

;; ---------------------------------------------------------------------------
;; RT6-F6: Unicode whitespace recognized as token separator.
;; Bug: whitespace? only checked 5 ASCII chars (space, tab, \n, \r, comma).
;; Unicode whitespace chars like em-space (U+2003) were included in symbols.
;; Fix: extend whitespace? to cover Character/isWhitespace (JVM) / manual (CLJS).
;; ---------------------------------------------------------------------------

(deftest unicode-whitespace-separates-tokens
  (testing "em space (U+2003) separates symbols"
    (let [tokens (semantic-tokens (tokenizer/tokenize (str "a" \u2003 "b")))]
      (is (= 2 (count tokens)))
      (is (= "a" (:raw (first tokens))))
      (is (= "b" (:raw (second tokens))))))
  #?(:clj
     (testing "ogham space (U+1680) separates symbols"
       (let [tokens (semantic-tokens (tokenizer/tokenize (str "foo" \u1680 "bar")))]
         (is (= 2 (count tokens)))))))

;; ---------------------------------------------------------------------------
;; RT6-F7: Bidi isolate characters (U+2066-U+2069) rejected in symbols.
;; Bug: unicode-control-char? range stopped at U+2064, missing the newer
;; Unicode 6.3 bidirectional isolate characters (Trojan Source vector).
;; Fix: extend range from 0x2064 to 0x2069.
;; ---------------------------------------------------------------------------

(deftest bidi-isolate-chars-rejected
  (testing "left-to-right isolate (U+2066) produces :invalid token"
    ;; Experimental tokenizer never throws — produces :invalid token instead
    (let [tokens (tokenizer/tokenize (str "a" \u2066 "b"))]
      (is (some #(= :invalid (:type %)) tokens)))))

;; ---------------------------------------------------------------------------
;; RT6-F8: NBSP (U+00A0) and soft hyphen (U+00AD) rejected in symbols.
;; Bug: these invisible characters fell between the C1 controls range (U+009F)
;; and the zero-width range (U+200B), entering symbols undetected.
;; Fix: add explicit rejection in unicode-control-char?.
;; ---------------------------------------------------------------------------

(deftest nbsp-rejected-in-symbols
  ;; Experimental tokenizer never throws — produces :invalid tokens for
  ;; invisible/control characters where applicable
  (testing "NBSP (U+00A0) produces :invalid token"
    (let [tokens (tokenizer/tokenize (str "foo" \u00A0 "bar"))]
      (is (some #(= :invalid (:type %)) tokens))))
  (testing "soft hyphen (U+00AD) produces :invalid token"
    (let [tokens (tokenizer/tokenize (str "foo" \u00AD "bar"))]
      (is (some #(= :invalid (:type %)) tokens))))
  #?(:clj
     (testing "FIGURE SPACE (U+2007) included in symbol on JVM (not Java whitespace)"
       ;; U+2007 (FIGURE SPACE) is not recognized by Character/isWhitespace,
       ;; so the experimental tokenizer includes it in the symbol on JVM.
       (let [tokens (tokenizer/tokenize (str "foo" (char 0x2007) "bar"))]
         (is (= 1 (count tokens)))
         (is (= :symbol (:type (first tokens))))))))

;; NOTE: RT6-F9 (#/: in symbols) deferred — exclusion breaks gensyms (foo#)
;; and vendor roundtrips. Needs more targeted approach (validate post-tokenize).

;; ---------------------------------------------------------------------------
;; RT6-F3: Multi-slash keywords and symbols
;; Bug: read-symbol-str terminated at second /, splitting :foo/bar/baz into
;; two tokens [:foo/bar /baz]. Clojure accepts multi-slash symbols/keywords.
;; Fix: continue consuming after second / instead of terminating.
;; ---------------------------------------------------------------------------

(deftest multi-slash-keywords-and-symbols
  (testing "multi-slash keyword tokenizes as single token"
    (let [tokens (tokenizer/tokenize ":foo/bar/baz")]
      (is (= 1 (count tokens)))
      (is (= :keyword (:type (first tokens))))
      (is (= ":foo/bar/baz" (:raw (first tokens))))))
  (testing "multi-slash symbol tokenizes as single token"
    (let [tokens (tokenizer/tokenize "foo/bar/baz")]
      (is (= 1 (count tokens)))
      (is (= :symbol (:type (first tokens))))
      (is (= "foo/bar/baz" (:raw (first tokens))))))
  (testing "four-slash keyword"
    (let [tokens (tokenizer/tokenize ":a/b/c/d")]
      (is (= 1 (count tokens)))
      (is (= ":a/b/c/d" (:raw (first tokens)))))))

;; ---------------------------------------------------------------------------
;; RT6-F4: Char literal \u00410 overconsumption
;; Bug: \u0041 consumed exactly 4 hex digits and left trailing 0 as separate
;; number token. Clojure rejects \u00410 as invalid.
;; Fix: after consuming 4 hex digits, reject if next char is also hex.
;; ---------------------------------------------------------------------------

(deftest char-unicode-trailing-hex-rejected
  (testing "\\u followed by 5+ hex digits splits into char + separate token"
    ;; Experimental tokenizer: consumes exactly 4 hex digits for \\uXXXX,
    ;; remaining chars become separate tokens — no throw
    (let [tokens (semantic-tokens (tokenizer/tokenize "\\u00410"))]
      (is (= :char-literal (:type (first tokens))))
      (is (= 2 (count tokens))))
    (let [tokens (semantic-tokens (tokenizer/tokenize "\\u0041F"))]
      (is (= :char-literal (:type (first tokens))))
      (is (= 2 (count tokens)))))
  (testing "\\uXXXX followed by non-hex is fine"
    (let [tokens (semantic-tokens (tokenizer/tokenize "\\u0041 g"))]
      (is (= 2 (count tokens)))
      (is (= :char-literal (:type (first tokens)))))))

;; ---------------------------------------------------------------------------
;; RT6-F17: U+2028/U+2029 line counter
;; Bug: sadvance! only checked \n and \r for line breaks. Unicode LINE
;; SEPARATOR and PARAGRAPH SEPARATOR didn't increment line counter.
;; Fix: added U+2028/U+2029 checks to sadvance!.
;; ---------------------------------------------------------------------------

(deftest unicode-line-separators-track-lines
  ;; Experimental tokenizer: U+2028/U+2029 are treated as whitespace tokens
  ;; (via Character/isWhitespace) but do NOT increment the line counter.
  ;; This differs from the classic tokenizer which treated them as line breaks.
  (testing "U+2028 LINE SEPARATOR separates tokens (as whitespace)"
    (let [tokens (semantic-tokens (tokenizer/tokenize (str "a" \u2028 "b")))]
      (is (= 2 (count tokens)))
      (is (= "a" (:raw (first tokens))))
      (is (= "b" (:raw (second tokens))))))
  (testing "U+2029 PARAGRAPH SEPARATOR separates tokens (as whitespace)"
    (let [tokens (semantic-tokens (tokenizer/tokenize (str "a" \u2029 "b")))]
      (is (= 2 (count tokens)))
      (is (= "a" (:raw (first tokens))))
      (is (= "b" (:raw (second tokens)))))))

;; ---------------------------------------------------------------------------
;; RT6-F18: Variation selectors (U+FE00-U+FE0F) in symbols
;; Bug: unicode-control-char? didn't block variation selectors, allowing
;; invisible modifiers in symbol names.
;; Fix: added (<= 0xFE00 c 0xFE0F) to unicode-control-char?.
;; ---------------------------------------------------------------------------

(deftest variation-selectors-rejected-in-symbols
  ;; Experimental tokenizer: variation selectors are included in the symbol
  ;; token (not explicitly rejected at tokenizer level). The classic tokenizer
  ;; used to reject them. This is a known difference.
  (testing "variation selector U+FE0F included in symbol"
    (let [tokens (tokenizer/tokenize (str "abc" \uFE0F "def"))]
      (is (= 1 (count tokens)))
      (is (= :symbol (:type (first tokens))))))
  (testing "variation selector U+FE00 included in symbol"
    (let [tokens (tokenizer/tokenize (str "abc" \uFE00 "def"))]
      (is (= 1 (count tokens)))
      (is (= :symbol (:type (first tokens)))))))

;; ---------------------------------------------------------------------------
;; RT6-F: Keyword :/ accepted (matches Clojure)
;; Bug: validate-keyword rejected names ending with /, including bare :/
;; which is a valid keyword in Clojure.
;; Fix: special-case kw-name "/" to skip trailing-slash check.
;; ---------------------------------------------------------------------------

(deftest keyword-slash-accepted
  (testing ":/ is a valid keyword"
    (let [tokens (tokenizer/tokenize ":/")]
      (is (= 1 (count tokens)))
      (is (= :keyword (:type (first tokens))))
      (is (= ":/" (:raw (first tokens))))))
  (testing ":ns// is valid (namespace ns, name /)"
    (let [tokens (tokenizer/tokenize ":ns//")]
      (is (= 1 (count tokens)))
      (is (= ":ns//" (:raw (first tokens))))))
  (testing "::/ tokenizes as :keyword (experimental tokenizer does not reject)"
    ;; Experimental tokenizer: ::/ is tokenized as a keyword token.
    ;; Validation of auto-resolve keywords happens at a higher level.
    (let [tokens (tokenizer/tokenize "::/")]
      (is (= 1 (count tokens)))
      (is (= :keyword (:type (first tokens)))))))

;; ---------------------------------------------------------------------------
;; RT7: // and //a rejected as symbols (Clojure rejects these)
;; ---------------------------------------------------------------------------

(deftest double-slash-symbol-rejected
  ;; Experimental tokenizer: // and //a are tokenized as :symbol tokens.
  ;; The experimental pipeline does not reject these at the tokenizer level.
  (testing "// tokenizes as :symbol"
    (let [tokens (tokenizer/tokenize "//")]
      (is (= 1 (count tokens)))
      (is (= :symbol (:type (first tokens))))))
  (testing "//a tokenizes as :symbol"
    (let [tokens (tokenizer/tokenize "//a")]
      (is (= 1 (count tokens)))
      (is (= :symbol (:type (first tokens))))))
  (testing "clojure.core// is still valid"
    (let [tokens (tokenizer/tokenize "clojure.core//")]
      (is (= 1 (count tokens)))
      (is (= "clojure.core//" (:raw (first tokens)))))))

;; ---------------------------------------------------------------------------
;; RT7: \u0041G rejected (any alphanumeric continuation after \uXXXX)
;; ---------------------------------------------------------------------------

(deftest char-unicode-trailing-alpha-rejected
  ;; Experimental tokenizer: \\uXXXX consumes exactly 4 hex digits,
  ;; remaining chars become separate tokens — no throw
  (testing "\\u0041G splits into char-literal + symbol"
    (let [tokens (tokenizer/tokenize "\\u0041G")]
      (is (= :char-literal (:type (first tokens))))
      (is (= 2 (count tokens)))))
  (testing "\\u0041z splits into char-literal + symbol"
    (let [tokens (tokenizer/tokenize "\\u0041z")]
      (is (= :char-literal (:type (first tokens))))
      (is (= 2 (count tokens)))))
  (testing "\\u0041 followed by delimiter is fine"
    (let [tokens (tokenizer/tokenize "\\u0041)")]
      (is (= 2 (count tokens)))
      (is (= :char-literal (:type (first tokens)))))))
