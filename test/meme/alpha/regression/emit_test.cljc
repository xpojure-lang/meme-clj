(ns meme.alpha.regression.emit-test
  "Scar tissue: printer and pretty-printer regression tests.
   Every test here prevents a specific bug from recurring."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [meme.alpha.core :as core]
            [meme.alpha.emit.printer :as p]
            [meme.alpha.emit.pprint :as pprint]))

;; ---------------------------------------------------------------------------
;; Scar tissue: quoted lists print as '(...) not head(args).
;; Bug: printer's catch-all for non-symbol-headed lists emitted 1(2 3)
;; for (quote (1 2 3)), producing '1(2 3) instead of '(1 2 3).
;; ---------------------------------------------------------------------------

(deftest quoted-call-printer
  (testing "'f(x y) roundtrips"
    (let [form '(quote (f x y))
          printed (p/print-form form)
          read-back (first (core/meme->forms printed))]
      (is (= "'f(x y)" printed))
      (is (= form read-back))))
  (testing "'+(1 2) roundtrips"
    (let [form '(quote (+ 1 2))
          printed (p/print-form form)
          read-back (first (core/meme->forms printed))]
      (is (= "'+(1 2)" printed))
      (is (= form read-back))))
  (testing "quoted empty list"
    (is (= "'()" (p/print-form '(quote ()))))))

;; ---------------------------------------------------------------------------
;; Empty list prints as (): not "nil()".
;; Bug: print-form on empty list produced "nil()" which re-reads as (nil).
;; ---------------------------------------------------------------------------

(deftest empty-list-roundtrip
  (testing "empty list prints as ()"
    (is (= "()" (p/print-form ()))))
  (testing "printed empty list re-reads correctly"
    (is (= [(list)] (core/meme->forms "()")))))

;; ---------------------------------------------------------------------------
;; B4: #() printer drops surplus % params.
;; Bug: (fn [%1 %2] (inc %1)) printed as #(inc(%1)) — silent arity change.
;; ---------------------------------------------------------------------------

(deftest anon-fn-surplus-params-not-dropped
  (testing "fn with surplus % params prints as fn(...), not #()"
    (let [form '(fn [%1 %2] (inc %1))
          printed (p/print-form form)]
      (is (not (str/starts-with? printed "#("))
          "surplus params must not emit #() shorthand")
      (is (= form (first (core/meme->forms printed)))
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
      (is (= form (first (core/meme->forms printed)))
          "roundtrip must preserve zero arity")))
  (testing "zero-param fn without %N in body still uses #() shorthand"
    (let [form '(fn [] (rand))
          printed (p/print-form form)]
      (is (str/starts-with? printed "#(")
          "no % params in body — #() shorthand is safe"))))

;; ---------------------------------------------------------------------------
;; Bug: max-percent-n recursed into nested fn bodies, so
;; (fn [%1] (fn [%1] %1)) was printed as #(#(%1)) — the outer fn
;; incorrectly detected %1 from the inner fn's body and used #() shorthand.
;; Reader re-read this as (fn [] (fn [%1] %1)) — arity lost.
;; Fix: skip (fn ...) forms in max-percent-n, mirroring find-percent-params.
;; ---------------------------------------------------------------------------

(deftest anon-fn-nested-fn-not-counted
  (testing "(fn [%1] (fn [%1] %1)) — outer must not use #() shorthand"
    (let [form '(fn [%1] (fn [%1] %1))
          printed (p/print-form form)]
      (is (not (str/starts-with? printed "#("))
          "outer fn should not emit #() — its %1 is in inner fn scope")
      (is (= form (first (core/meme->forms printed)))
          "roundtrip must preserve outer arity")))
  (testing "(fn [%1] (inc %1)) — non-nested still uses #()"
    (is (= "#(inc(%1))" (p/print-form '(fn [%1] (inc %1)))))))

;; ---------------------------------------------------------------------------
;; Quote uses meme syntax — callable-headed quoted forms roundtrip.
;; Non-callable-headed lists (like (1 2 3)) are not representable in meme.
;; ---------------------------------------------------------------------------

(deftest quoted-call-form-roundtrip
  (testing "'f(g(x)) roundtrips — nested call inside quote"
    (let [form '(quote (f (g x)))
          printed (p/print-form form)
          reread (first (core/meme->forms printed))]
      (is (= "'f(g(x))" printed))
      (is (= form reread))))
  (testing "'a(b(c) d) roundtrips — nested callable sublists"
    (let [form '(quote (a (b c) d))
          printed (p/print-form form)
          read-back (first (core/meme->forms printed))]
      (is (= form read-back)))))

;; ---------------------------------------------------------------------------
;; Scar tissue: pprint head-line args must respect width.
;; Bug: pp-call-smart always printed head-line args flat.
;; ---------------------------------------------------------------------------

#?(:clj
(deftest pprint-head-line-args-respect-width
  (testing "long if-condition falls back to body when it exceeds width"
    (let [form '(if (and (> x 100) (< y 200) (not= z 0) (pos? w)) (body1) (body2))
          result (pprint/pprint-form form {:width 40})]
      (is (not (re-find #"if\(and" result))
          "long condition should not stay on head line")
      (doseq [line (str/split-lines result)]
        (is (<= (count line) 42)
            (str "line exceeds width: " (pr-str line))))))
  (testing "short if-condition stays on head line when multi-line needed"
    (let [form '(if (> x 0) (do-something-with x) (do-something-else y))
          result (pprint/pprint-form form {:width 40})]
      (is (re-find #"if\(>" result)
          "short condition should stay on head line")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: pprint map value column must use actual key width.
;; ---------------------------------------------------------------------------

#?(:clj
(deftest pprint-map-value-column-uses-actual-key-width
  (testing "map value indentation based on actual key width, not flat width"
    (let [form {:k '(some-long-function arg1 arg2 arg3 arg4 arg5)}
          result (pprint/pprint-form form {:width 40})
          lines (str/split-lines result)]
      (is (some? result))
      (is (> (count lines) 1) "should be multi-line")))))

;; ---------------------------------------------------------------------------
;; Bug: pp-map underestimated val-col for single-line keys.
;; val-col omitted inner-col, so values got more horizontal space than
;; they actually had, printing flat when they should break.
;; ---------------------------------------------------------------------------

#?(:clj
(deftest pprint-map-value-column-includes-indent
  (testing "map value breaks when key + indent + value exceed width"
    ;; Map is multi-line (flat > width 30). Inside, inner-col = 2.
    ;; Key :k is 2 chars. Correct val-col = 2 + 2 + 1 = 5.
    ;; Bug val-col was 2 + 1 = 3 (missing inner-col).
    ;; Value "fff(arg1 arg2 arg3 arg4 a)" is 26 chars.
    ;; At correct val-col 5: 5 + 26 = 31 > 30 → value breaks.
    ;; At bug val-col 3:     3 + 26 = 29 ≤ 30 → value stays flat (wrong).
    (let [form (sorted-map :k '(fff arg1 arg2 arg3 arg4 a) :z 1)
          result (pprint/pprint-form form {:width 30})
          lines (str/split-lines result)]
      (is (> (count lines) 3) "value should break to multi-line")
      (doseq [line lines]
        (is (<= (count line) 34)
            (str "line exceeds width: " (pr-str line))))))))

;; ---------------------------------------------------------------------------
;; Bug: pprint silently dropped metadata on multi-line call forms.
;; The flat path (printer/print-form) handled metadata, but pp dispatched
;; on (call? form) before checking metadata, so ^:private defn(...) lost
;; the ^:private prefix when the form exceeded width.
;; Fix: pp checks metadata before structural dispatch.
;; ---------------------------------------------------------------------------

(deftest pprint-metadata-on-multi-line-forms
  (testing "^:private preserved on multi-line call"
    (let [form (with-meta '(defn foo [x] x) {:private true})
          result (pprint/pprint-form form {:width 10})]
      (is (re-find #"^\^:private" result))))
  (testing "^:private preserved on flat call"
    (let [form (with-meta '(defn foo [x] x) {:private true})
          result (pprint/pprint-form form {:width 200})]
      (is (re-find #"^\^:private" result))))
  (testing "^String type tag preserved"
    (let [form (with-meta '(defn foo [x] x) {:tag 'String})
          result (pprint/pprint-form form {:width 10})]
      (is (re-find #"^\^String" result))))
  (testing "^{:doc ...} map metadata preserved"
    (let [form (with-meta '(defn foo [x] x) {:doc "hello"})
          result (pprint/pprint-form form {:width 10})]
      (is (re-find #"^\^\{:doc" result))))
  (testing "metadata on non-call forms (vector)"
    (let [form (with-meta [1 2 3] {:tag 'ints})
          result (pprint/pprint-form form {:width 10})]
      (is (re-find #"^\^ints" result)))))

;; ---------------------------------------------------------------------------
;; Bug: comment lines from :ws metadata emitted at column 0 inside nested
;; multi-line blocks. Second+ comment lines had no indent; original whitespace
;; was preserved instead of re-indented to the current pprint column.
;; ---------------------------------------------------------------------------

(deftest pprint-comment-indentation-in-nested-blocks
  (testing "single comment indented to match body"
    (let [baz (with-meta '(baz) {:ws "; single\n"})
          form (list 'foo '(bar) baz)
          result (pprint/pprint-form form {:width 10})
          lines (str/split-lines result)]
      (is (= "  ; single" (nth lines 2)))))
  (testing "multiple comment lines all indented"
    (let [baz (with-meta '(baz) {:ws "; line 1\n; line 2\n"})
          form (list 'foo '(bar) baz)
          result (pprint/pprint-form form {:width 10})
          lines (str/split-lines result)]
      (is (= "  ; line 1" (nth lines 2)))
      (is (= "  ; line 2" (nth lines 3)))))
  (testing "original whitespace stripped and re-indented"
    (let [baz (with-meta '(baz) {:ws "    ; deep\n"})
          form (list 'foo '(bar) baz)
          result (pprint/pprint-form form {:width 10})
          lines (str/split-lines result)]
      (is (= "  ; deep" (nth lines 2)))))
  (testing "top-level comments unchanged"
    (let [form (with-meta '(foo x) {:ws "; top\n"})
          result (pprint/pprint-form form)]
      (is (str/starts-with? result "; top\n")))))

;; ---------------------------------------------------------------------------
;; Bug: pprint rendered deferred :: keywords as clojure.core/read-string(...)
;; when the form didn't fit flat. pp's cond dispatched call? before checking
;; deferred-auto-keyword?, so (clojure.core/read-string "::foo") was treated
;; as a call. meme format would permanently corrupt :: keywords in .meme files.
;; Fix: check deferred-auto-keyword? before call? in pp, mirroring printer.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Bug: regex patterns containing " printed unescaped — #"a"b" instead of
;; #"a\"b". Tokenizer terminated at the first unescaped ", breaking roundtrip.
;; Fix: escape unescaped quotes in .pattern output.
;; ---------------------------------------------------------------------------

(deftest regex-quote-in-pattern-escaped
  (testing "regex from meme source with \\\" roundtrips"
    (let [forms (core/meme->forms "#\"a\\\"b\"")
          printed (p/print-meme-string forms)
          forms2 (core/meme->forms printed)]
      (is (= #?(:clj (.pattern ^java.util.regex.Pattern (first forms))
                :cljs (.-source (first forms)))
             #?(:clj (.pattern ^java.util.regex.Pattern (first forms2))
                :cljs (.-source (first forms2)))))))
  #?(:clj
  (testing "programmatic regex with bare \" produces parseable output"
    (let [r (re-pattern "a\"b")
          printed (p/print-form r)]
      (is (re-find #"^#\"" printed))
      (is (some? (core/meme->forms printed)))
      (is (= (re-find r "a\"b")
             (re-find (first (core/meme->forms printed)) "a\"b"))))))
  (testing "no double-escaping of already-escaped quotes"
    (let [forms (core/meme->forms "#\"x\\\"y\"")
          printed (p/print-meme-string forms)
          forms2 (core/meme->forms printed)]
      (is (= #?(:clj (.pattern ^java.util.regex.Pattern (first forms))
                :cljs (.-source (first forms)))
             #?(:clj (.pattern ^java.util.regex.Pattern (first forms2))
                :cljs (.-source (first forms2)))))))
  #?(:clj
  (testing "even backslashes before quote: \\\\ + \" not misidentified as escaped quote"
    (let [r (re-pattern "a\\\\\"b")
          printed (p/print-form r)
          forms (core/meme->forms printed)]
      (is (some? forms) "printed regex should parse without error")
      (is (= (re-find r "a\\\"b")
             (re-find (first forms) "a\\\"b"))
          "regex behavior should be preserved")))))

#?(:clj
(deftest pprint-deferred-auto-keyword-not-corrupted
  (testing "::foo preserved at narrow width"
    (is (= "::foo"
           (pprint/pprint-form '(clojure.core/read-string "::foo") {:width 4}))))
  (testing "::keyword preserved when nested deeply"
    (let [result (pprint/pprint-form
                   (list 'def 'x '(clojure.core/read-string "::long-keyword"))
                   {:width 20})]
      (is (re-find #"::long-keyword" result))
      (is (not (re-find #"clojure.core/read-string" result)))))
  (testing ":: keyword roundtrips through pprint"
    (let [src "def(x ::my-key)"
          forms (core/meme->forms src)
          pprinted (pprint/pprint-forms forms {:width 10})
          re-read (core/meme->forms pprinted)]
      (is (= forms re-read))))))

;; ---------------------------------------------------------------------------
;; Bug: pprint lost reader-sugar notation (@, #', ') when forms exceeded
;; width. pp-call-smart emitted clojure.core/deref(...) instead of
;; @inner, var(...) instead of #'sym, quote(...) instead of 'sym.
;; Fix: add explicit dispatch for deref, var, quote-non-seq before call?.
;; ---------------------------------------------------------------------------

#?(:clj
(deftest pprint-preserves-deref-sugar
  (testing "@atom preserved at narrow width"
    (let [form (list 'clojure.core/deref 'my-very-long-atom-name)
          result (pprint/pprint-form form {:width 10})]
      (is (str/starts-with? result "@"))
      (is (not (str/includes? result "clojure.core/deref")))
      (is (= (list form) (core/meme->forms result)))))
  (testing "@(complex expr) preserves sugar and recurses"
    (let [form (list 'clojure.core/deref (list 'reset! 'state 'val))
          result (pprint/pprint-form form {:width 10})]
      (is (str/starts-with? result "@"))
      (is (= (list form) (core/meme->forms result)))))))

#?(:clj
(deftest pprint-preserves-var-sugar
  (testing "#'sym preserved at narrow width"
    (let [form (list 'var 'some.ns/my-var)
          result (pprint/pprint-form form {:width 5})]
      (is (str/starts-with? result "#'"))
      (is (not (str/includes? result "var(")))
      (is (= (list form) (core/meme->forms result)))))))

#?(:clj
(deftest pprint-preserves-quote-sugar
  (testing "'sym preserved at narrow width"
    (let [form (list 'quote 'my-long-symbol-name)
          result (pprint/pprint-form form {:width 5})]
      (is (str/starts-with? result "'"))
      (is (not (str/includes? result "quote(")))
      (is (= (list form) (core/meme->forms result)))))))

;; ---------------------------------------------------------------------------
;; Bug: reader-conditional printer emitted S-expressions via pr-str.
;; Fix: walk inner forms with print-form to emit meme syntax.
;; ---------------------------------------------------------------------------

#?(:clj
(deftest reader-conditional-prints-meme-syntax
  (testing "reader conditional inner forms use meme syntax"
    (let [rc (read-string {:read-cond :preserve} "#?(:clj (+ 1 2) :cljs (- 3 4))")
          printed (p/print-form rc)]
      (is (= "#?(:clj +(1 2) :cljs -(3 4))" printed))))
  (testing "#?@ splicing variant"
    (let [rc (read-string {:read-cond :preserve} "#?@(:clj [1 2] :cljs [3 4])")
          printed (p/print-form rc)]
      (is (= "#?@(:clj [1 2] :cljs [3 4])" printed))))
  (testing "reader conditional with nested calls"
    (let [rc (read-string {:read-cond :preserve} "#?(:clj (defn foo [x] x))")
          printed (p/print-form rc)]
      (is (re-find #"defn\(" printed))))))

;; ---------------------------------------------------------------------------
;; Bug: nil/true/false as call heads produced unparseable meme output.
;; Fix: throw for non-callable heads.
;; ---------------------------------------------------------------------------

(deftest non-callable-head-throws
  (testing "nil as call head"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"not representable"
          (p/print-form (list nil 1 2)))))
  (testing "true as call head"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"not representable"
          (p/print-form (list true 1 2)))))
  (testing "false as call head"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
          #"not representable"
          (p/print-form (list false 1 2))))))
