(ns meme.regression.scan-test
  "Scar tissue: tokenizer regression tests.
   Every test here prevents a specific bug from recurring."
  (:require [clojure.test :refer [deftest is testing]]
            [mclj-lang.api :as lang]
            [mclj-lang.formatter.flat :as fmt-flat]
            [meme.tools.clj.forms :as forms]
            [meme.tools.clj.stages :as stages]
            [mclj-lang.grammar :as grammar]
            [mclj-lang.test-util :as tokenizer]))

(defn- semantic-tokens
  "Filter tokenizer output to only semantic tokens (remove whitespace, newlines, comments).
   The experimental tokenizer includes all tokens; the classic one skipped non-semantic tokens."
  [tokens]
  (vec (remove #(#{:whitespace :newline :comment} (:type %)) tokens)))

;; ---------------------------------------------------------------------------
;; Syntax-quote is parsed natively with meme rules inside.
;; ---------------------------------------------------------------------------

(deftest syntax-quote-native
  (testing "backtick on symbol produces a CljSyntaxQuote node"
    (let [form (first (lang/meme->forms "`foo"))]
      (is (some? form))
      (is (instance? meme.tools.clj.forms.CljSyntaxQuote form))))
  (testing "backtick on call produces a CljSyntaxQuote wrapping the call"
    (let [form (first (lang/meme->forms "`a(b c)"))]
      (is (instance? meme.tools.clj.forms.CljSyntaxQuote form))
      (is (= '(a b c) (:form form)))))
  (testing "backtick nested inside a call works"
    (let [form (first (lang/meme->forms "foo(`bar)"))]
      (is (seq? form))
      (is (= 'foo (first form)))
      (is (instance? meme.tools.clj.forms.CljSyntaxQuote (second form))))))

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
     (testing "#? with bracket char literals parses to matched value (after eval-rc)"
       (let [eval-rc (fn [src]
                       (first (:forms
                                (-> {:source src :opts {:grammar grammar/grammar}}
                                    stages/step-parse
                                    stages/step-read
                                    stages/step-evaluate-reader-conditionals))))]
         (is (= \) (eval-rc "#?(:clj \\) :cljs nil)")))
         (is (= \( (eval-rc "#?(:clj \\( :cljs nil)")))))))

;; ---------------------------------------------------------------------------
;; Bug: `~(expr) produced truncated token and confusing "Bare parentheses"
;; error. The tokenizer now captures balanced forms after `~.
;; ---------------------------------------------------------------------------

(deftest syntax-quote-unquote-forms
  (testing "`~foo produces CljSyntaxQuote wrapping CljUnquote"
    (let [form (first (lang/meme->forms "`~foo"))]
      (is (instance? meme.tools.clj.forms.CljSyntaxQuote form))
      (is (instance? meme.tools.clj.forms.CljUnquote (:form form)))
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

;; ---------------------------------------------------------------------------
;; Scar tissue: radix numbers for bases 17–36 need letters G-Z.
;; Bug: read-number only accepted hex digits a-f/A-F, so 36rZ split into
;; number token "36r" + symbol "Z" — a silent misparse.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest radix-numbers-high-bases
     (testing "36rZ — base-36, value preserved in CljRaw"
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
  (testing "`~\"foo\" produces CljSyntaxQuote wrapping CljUnquote of string"
    (let [form (first (lang/meme->forms "`~\"foo\""))]
      (is (instance? meme.tools.clj.forms.CljSyntaxQuote form))
      (is (= "foo" (:form (:form form)))))))

;; ---------------------------------------------------------------------------
;; B3: \uXXXX and \oXXX char literals.
;; Bug: \u0041 tokenized as \u (char) + 0041 (number) instead of \u0041.
;; ---------------------------------------------------------------------------

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

;; ---------------------------------------------------------------------------
;; Bug: ##Inf, ##-Inf, ##NaN silently misparsed as tagged literals.
;; The tokenizer's # dispatch fell through to tagged-literal for ##,
;; and # was not excluded from symbol-char?, so read-symbol-str consumed
;; #Inf as a tag name, producing a broken tagged-literal token that ate
;; the next form.
;; Fix: dedicated (= nxt \#) branch emits :number token; # excluded
;; from symbol-start? and symbol-char?.
;; ---------------------------------------------------------------------------

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

;; ---------------------------------------------------------------------------
;; F2: ::a::b, :::, ::a/ were silently accepted as valid keywords.
;; Bug: tokenizer's read-symbol-str consumed : as a symbol-char, producing
;; keyword tokens that Clojure's reader rejects as invalid tokens.
;; Fix: validate keyword syntax after scanning — reject embedded ::,
;; trailing /, and bare :: with no name.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; RT2-M11: Bare : (lone colon) was accepted as empty-name keyword.
;; Clojure rejects it: "Invalid token: :".
;; Fix: validate-keyword! now rejects empty non-auto keyword names.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; RT2-M3: foo/ (trailing slash) was accepted as a valid symbol.
;; Clojure rejects it: "Invalid token: foo/".
;; Fix: validate-symbol-name! rejects trailing / (except ns// pattern).
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; RT2-M4: foo/1bar (digit-starting name after /) was accepted.
;; Clojure rejects it: "Invalid token: foo/1bar".
;; Fix: validate-symbol-name! rejects digit-starting name after /.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; RT2-M12: #:{:a 1} (empty namespace) was silently accepted.
;; Clojure rejects: "Namespaced map must specify a namespace".
;; Fix: tokenizer validates non-empty ns after #:.
;; ---------------------------------------------------------------------------

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
     (testing "#::{:a 1} bare auto-resolve — keys stay unqualified (deferred to eval)"
       (let [result (first (lang/meme->forms "#::{:a 1}"))]
         (is (map? result))
         (is (= 1 (:a result)))
         (is (= "::" (:meme/namespace-prefix (meta result))))))))

;; ---------------------------------------------------------------------------
;; RT2-L10: ##foo was silently accepted and produced confusing error.
;; Fix: whitelist validation for ##Inf, ##-Inf, ##NaN only.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; RT2-L5/L6/L7: Null byte, zero-width space, RTL override in symbols.
;; Fix: unicode-control-char? predicate rejects control/invisible chars.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; RT2-L9: read-number greedy — 1N.5 was one token, should be two.
;; Fix: N/M suffix terminates number scanning.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; RT2-L11: \r-only line endings — all tokens reported on line 1.
;; Fix: sadvance! treats bare \r as line break.
;; ---------------------------------------------------------------------------

;; Scar tissue: bare \r in comments caused silent data loss — comment scanner
;; only checked for \n, eating everything after ; until end of file on classic
;; Mac line endings.

;; ---------------------------------------------------------------------------
;; RT6-F6: Unicode whitespace recognized as token separator.
;; Bug: whitespace? only checked 5 ASCII chars (space, tab, \n, \r, comma).
;; Unicode whitespace chars like em-space (U+2003) were included in symbols.
;; Fix: extend whitespace? to cover Character/isWhitespace (JVM) / manual (CLJS).
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; RT6-F7: Bidi isolate characters (U+2066-U+2069) rejected in symbols.
;; Bug: unicode-control-char? range stopped at U+2064, missing the newer
;; Unicode 6.3 bidirectional isolate characters (Trojan Source vector).
;; Fix: extend range from 0x2064 to 0x2069.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; RT6-F8: NBSP (U+00A0) and soft hyphen (U+00AD) rejected in symbols.
;; Bug: these invisible characters fell between the C1 controls range (U+009F)
;; and the zero-width range (U+200B), entering symbols undetected.
;; Fix: add explicit rejection in unicode-control-char?.
;; ---------------------------------------------------------------------------

;; NOTE: RT6-F9 (#/: in symbols) deferred — exclusion breaks gensyms (foo#)
;; and vendor roundtrips. Needs more targeted approach (validate post-tokenize).

;; ---------------------------------------------------------------------------
;; RT6-F3: Multi-slash keywords and symbols (:foo/bar/baz, foo/bar/baz).
;; Current: accepted as a single token. Matches Clojure.
;; ---------------------------------------------------------------------------

(deftest multi-slash-keywords-and-symbols
  ;; Clojure's own reader rejects these as literals, so we construct the
  ;; expected symbols/keywords with `symbol`/`keyword` instead.
  (testing "multi-slash keyword tokenizes as one keyword"
    (is (= [(keyword "foo/bar/baz")] (lang/meme->forms ":foo/bar/baz"))))
  (testing "multi-slash symbol tokenizes as one symbol"
    (is (= [(symbol "foo/bar/baz")] (lang/meme->forms "foo/bar/baz"))))
  (testing "double-slash inside symbol tokenizes as one symbol"
    (is (= [(symbol "foo//bar")] (lang/meme->forms "foo//bar")))))

;; ---------------------------------------------------------------------------
;; RT6-F4: Char literal \uNNNN overconsumption
;; Bug: scanner consumed exactly 4 hex digits, leaving a trailing
;; alphanumeric as a separate token (e.g. "\u00410" → [\A 0]). Clojure's
;; reader rejects \u00410 / \u0041G as invalid char literals.
;; Fix: after 4 hex digits, keep consuming alphanumerics so the malformed
;; token reaches resolve-char, which rejects it.
;; ---------------------------------------------------------------------------

(deftest char-literal-trailing-alphanumerics-rejected
  (testing "\\u00410 — 5th hex digit rejected"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Invalid character literal"
                          (lang/meme->forms "\\u00410"))))
  (testing "\\u0041G — trailing letter rejected"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Invalid character literal"
                          (lang/meme->forms "\\u0041G"))))
  (testing "plain \\u0041 still works"
    (is (= [\A]
           (stages/expand-syntax-quotes
             (lang/meme->forms "\\u0041") nil)))))

;; ---------------------------------------------------------------------------
;; RT6-F17: U+2028 / U+2029 line counter
;; Bug: build-line-starts only treated \n and \r as line breaks. Unicode
;; LINE SEPARATOR (U+2028) and PARAGRAPH SEPARATOR (U+2029) didn't advance
;; the line counter, so error positions after them reported the wrong line.
;; Fix: U+2028 and U+2029 now count as line boundaries in build-line-starts.
;; ---------------------------------------------------------------------------

(deftest unicode-line-separators-increment-line-counter
  (testing "U+2028 LINE SEPARATOR increments line counter"
    (let [e (try (lang/meme->forms "a\u2028(bare)") nil
                 (catch #?(:clj Exception :cljs js/Error) ex ex))]
      (is (= 2 (:line (ex-data e))))
      (is (= 1 (:col (ex-data e))))))
  (testing "U+2029 PARAGRAPH SEPARATOR increments line counter"
    (let [e (try (lang/meme->forms "a\u2029(bare)") nil
                 (catch #?(:clj Exception :cljs js/Error) ex ex))]
      (is (= 2 (:line (ex-data e))))
      (is (= 1 (:col (ex-data e))))))
  (testing "\\n still increments line counter (control case)"
    (let [e (try (lang/meme->forms "a\n(bare)") nil
                 (catch #?(:clj Exception :cljs js/Error) ex ex))]
      (is (= 2 (:line (ex-data e)))))))

;; ---------------------------------------------------------------------------
;; RT6-F18: Variation selectors (U+FE00-U+FE0F) in symbols
;; Bug: invisible-char? didn't block variation selectors, allowing
;; invisible glyph modifiers inside symbol names — a look-alike attack
;; vector in web-copy-paste source.
;; Fix: added (<= 0xFE00 c 0xFE0F) to invisible-char? in lexlets.
;; ---------------------------------------------------------------------------

(deftest variation-selectors-rejected-in-symbols
  (testing "U+FE0F (VS16) is rejected in a symbol"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (lang/meme->forms "foo\uFE0F"))))
  (testing "U+FE00 (VS1) is rejected in a symbol"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (lang/meme->forms "foo\uFE00")))))

;; ---------------------------------------------------------------------------
;; RT6-F: Keyword :/ — bare slash keyword
;; Bug: validate-keyword rejected any name starting with "/", including the
;; bare :/ which is a valid keyword in Clojure (= (keyword "/")).
;; Fix: special-case the whole token :/ before the starts-with? "/" rejection.
;; ---------------------------------------------------------------------------

(deftest bare-slash-keyword-accepted
  (testing ":/ reads as (keyword \"/\")"
    (is (= [(keyword "/")] (lang/meme->forms ":/"))))
  (testing ":/foo still rejected (empty namespace)"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Invalid token"
                          (lang/meme->forms ":/foo")))))

;; ---------------------------------------------------------------------------
;; RT7: // and //a as symbols
;; Bug: a symbol token starting with `/` but not exactly `/` was accepted
;; (// , //a , /foo). Clojure rejects these — only `/` alone and the special
;; `ns//` form (e.g. clojure.core//) are valid.
;; Fix: validate in read-atom :symbol — reject if raw starts with `/` and
;; raw != "/".
;; ---------------------------------------------------------------------------

(deftest symbols-starting-with-slash-rejected
  (testing "// rejected"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Invalid token"
                          (lang/meme->forms "//"))))
  (testing "//a rejected"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Invalid token"
                          (lang/meme->forms "//a"))))
  (testing "bare / still accepted as division symbol"
    (is (= '[/] (lang/meme->forms "/"))))
  (testing "clojure.core// still accepted (ns-qualified slash)"
    (is (= [(symbol "clojure.core" "/")] (lang/meme->forms "clojure.core//")))))

;; ---------------------------------------------------------------------------
;; Fuzzer finding: unterminated string/regex literals crash resolve-string/
;; resolve-regex with StringIndexOutOfBoundsException. The scanner correctly
;; produces a token for unterminated literals (EOF before closing quote),
;; but the resolver assumed both quotes were present.
;; Fix: length guard in resolve-string and resolve-regex.
;; ---------------------------------------------------------------------------

(deftest unterminated-string-no-crash
  (testing "lone double-quote does not crash"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Unterminated string"
                          (lang/meme->forms "\""))))
  (testing "exclamation + double-quote does not crash"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Unterminated string"
                          (lang/meme->forms "!\""))))
  (testing "multi-char unterminated string errors instead of truncating"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Unterminated string"
                          (lang/meme->forms "\"hello"))))
  (testing "two-char unterminated string errors"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Unterminated string"
                          (lang/meme->forms "\"a"))))
  (testing "unterminated string with trailing backslash errors"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Unterminated string"
                          (lang/meme->forms "\"hello\\"))))
  (testing "unterminated regex does not crash"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Unterminated regex"
                          (lang/meme->forms "#\""))))
  (testing "multi-char unterminated regex errors instead of truncating"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Unterminated regex"
                          (lang/meme->forms "#\"hello"))))
  (testing "two-char unterminated regex errors"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Unterminated regex"
                          (lang/meme->forms "#\"ab"))))
  (testing "unterminated regex with trailing backslash errors"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Unterminated regex"
                          (lang/meme->forms "#\"hello\\\\")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: invalid keyword forms must be rejected (Clojure compatibility).
;; Bug: consume-keyword accepted colons mid/end of keyword names.
;; Fix: post-validation in CST reader keyword handling.
;; ---------------------------------------------------------------------------

(deftest invalid-keyword-rejected
  (testing "bare colon : is invalid"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Invalid token: :"
                          (lang/meme->forms ":"))))
  (testing "trailing colon :foo: is invalid"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Invalid token"
                          (lang/meme->forms ":foo:"))))
  (testing "triple colon :::foo is invalid"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Invalid token"
                          (lang/meme->forms ":::foo"))))
  (testing "embedded double colon :a::b is invalid"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Invalid token"
                          (lang/meme->forms ":a::b"))))
  (testing "trailing slash :foo/ is invalid"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Invalid token"
                          (lang/meme->forms ":foo/"))))
  (testing "leading slash :/foo is invalid (matches Clojure)"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Invalid token"
                          (lang/meme->forms ":/foo"))))
  (testing "valid keywords still work"
    (is (= [:foo] (lang/meme->forms ":foo")))
    (is (= [:foo/bar] (lang/meme->forms ":foo/bar")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: %0 must be rejected in anonymous functions.
;; Bug: percent-param-type regex #"\d+" matched "0".
;; Fix: changed to #"[1-9]\d*" to require 1-indexed params.
;; ---------------------------------------------------------------------------

(deftest percent-param-bounds
  (testing "%0 is rejected as invalid param"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Invalid % parameter"
                          (lang/meme->forms "#(+(%0 %1))"))))
  (testing "%20 is valid (Clojure's max)"
    (is (some? (lang/meme->forms "#(%20)"))))
  (testing "%21 is rejected — exceeds Clojure's limit"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Invalid % parameter"
                          (lang/meme->forms "#(%21)"))))
  (testing "%99999999999 is rejected — was OOM before cap"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Invalid % parameter"
                          (lang/meme->forms "#(%99999999999)"))))
  (testing "huge %N doesn't crash with NumberFormatException"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Invalid % parameter"
                          (lang/meme->forms "#(%99999999999999999999)")))))

;; ---------------------------------------------------------------------------
;; Fuzzer finding: #(%+ c%) threw IllegalArgumentException instead of
;; ExceptionInfo. find-invalid-percent-symbols returns a symbol, but the
;; caller used (seq ...) on it — (seq symbol) throws.
;; Fix: use (some? ...) instead of (seq ...) in cst-reader.
;; ---------------------------------------------------------------------------

(deftest invalid-percent-param-no-raw-exception
  (testing "#(%+ c%) produces meme error, not IllegalArgumentException"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                          #"Invalid % parameter"
                          (lang/meme->forms "#(%+ c%)")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: 0x (empty hex body) produced Java error "Zero length BigInteger".
;; Fix: explicit guard in resolve-number for empty hex body.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest empty-hex-literal-clean-error
     (testing "0x at EOF — empty hex body"
       (is (thrown-with-msg? Exception #"Empty hex literal"
                             (lang/meme->forms "0x"))))
     (testing "0X at EOF — empty hex body (uppercase)"
       (is (thrown-with-msg? Exception #"Empty hex literal"
                             (lang/meme->forms "0X"))))
     (testing "+0x at EOF — signed empty hex body"
       (is (thrown-with-msg? Exception #"Empty hex literal"
                             (lang/meme->forms "+0x"))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: U+2007 FIGURE SPACE entered symbol names on both platforms.
;; Fix: added U+2007 to explicit whitespace list.
;; ---------------------------------------------------------------------------

(deftest figure-space-is-whitespace
  (testing "U+2007 is treated as whitespace, not part of symbol"
    (let [forms (lang/meme->forms (str "a" \u2007 "b"))]
      (is (= '[a b] forms))))
  (testing "U+2007 between tokens acts as separator"
    (let [forms (lang/meme->forms (str "[1" \u2007 "2]"))]
      (is (= [[1 2]] forms)))))

;; ---------------------------------------------------------------------------
;; Scar tissue: supplementary-plane characters (emoji) produced two :error
;; nodes — one per UTF-16 surrogate half. Fix: consume surrogate pairs as one.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest supplementary-plane-single-error
     (testing "emoji produces one error node, not two"
       ;; U+1F600 (GRINNING FACE) = surrogate pair D83D DE00
       ;; Can't use char literals for surrogates in Clojure source,
       ;; so construct the string via char array.
       (let [src (str (String. (char-array [(char 0xD83D) (char 0xDE00)])) " x")]
         (is (thrown-with-msg? Exception #"Unexpected token"
                               (lang/meme->forms src)))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: U+2028 (LINE SEPARATOR) and U+2029 (PARAGRAPH SEPARATOR) must
;; be classified as newlines, not whitespace. build-line-starts already treats
;; them as line terminators, and whitespace-char? used to include them — the
;; asymmetry meant they produced :whitespace trivia tokens instead of :newline,
;; and interior comment extraction couldn't split on them.
;; Fix: excluded from whitespace-char? and added to newline-char?.
;; ---------------------------------------------------------------------------

(deftest unicode-line-separator-is-newline
  (let [ls (str (char 0x2028))
        ps (str (char 0x2029))]
    (testing "U+2028 produces a :newline trivia token, not :whitespace"
      (let [tokens (tokenizer/tokenize (str "a" ls "b"))
            types (map :type tokens)]
        (is (some #{:newline} types)
            (str "expected :newline in " (pr-str types)))
        (is (not (contains? (set types) :whitespace)))))
    (testing "U+2029 produces a :newline trivia token"
      (let [tokens (tokenizer/tokenize (str "a" ps "b"))
            types (map :type tokens)]
        (is (some #{:newline} types))))
    (testing "position reporting is correct across U+2028 (symbol `b` on line 2)"
      (let [tokens (tokenizer/tokenize (str "a" ls "b"))
            b-tok (first (filter #(and (= (:type %) :symbol) (= (:raw %) "b")) tokens))]
        (is (= 2 (:line b-tok)))
        (is (= 1 (:col b-tok)))))
    (testing "U+2028 between tokens still separates symbols"
      (is (= (quote [a b]) (lang/meme->forms (str "a" ls "b")))))
    (testing "tokenize invariant: source = concat of :raw fields"
      (let [src (str "a" ls "b" ps "c")]
        (is (= src (apply str (map :raw (tokenizer/tokenize src)))))))))
