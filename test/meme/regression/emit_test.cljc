(ns meme.regression.emit-test
  "Scar tissue: printer and formatter regression tests.
   Every test here prevents a specific bug from recurring."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [meme.lang :as lang]
            [meme.core :as core]
            [meme.emit.formatter.flat :as fmt-flat]
            [meme.emit.formatter.canon :as fmt-canon]
            [meme.forms :as forms]
            [meme.rewrite.emit :as remit]))

;; ---------------------------------------------------------------------------
;; Scar tissue: quoted lists print correctly in both sugar and call modes.
;; Sugar mode (:meme/sugar true) emits 'f(x y).
;; Call mode (no tag) emits quote(f(x y)).
;; Both roundtrip through the reader.
;; ---------------------------------------------------------------------------

(deftest quoted-call-printer
  (testing "'f(x y) sugar roundtrips"
    (let [form (with-meta '(quote (f x y)) {:meme/sugar true})
          printed (fmt-flat/format-form form)
          read-back (first (core/meme->forms printed))]
      (is (= "'f(x y)" printed))
      (is (= '(quote (f x y)) read-back))))
  (testing "quote(+(1 2)) call form roundtrips"
    (let [form '(quote (+ 1 2))
          printed (fmt-flat/format-form form)
          read-back (first (core/meme->forms printed))]
      (is (= "quote(+(1 2))" printed))
      (is (= form read-back))))
  (testing "quoted empty list — sugar"
    (is (= "'()" (fmt-flat/format-form (with-meta '(quote ()) {:meme/sugar true})))))
  (testing "quoted empty list — call form"
    (is (= "quote(())" (fmt-flat/format-form '(quote ()))))))

;; ---------------------------------------------------------------------------
;; Empty list prints as (): not "nil()".
;; Bug: print-form on empty list produced "nil()" which re-reads as (nil).
;; ---------------------------------------------------------------------------

(deftest empty-list-roundtrip
  (testing "empty list prints as ()"
    (is (= "()" (fmt-flat/format-form ()))))
  (testing "printed empty list re-reads correctly"
    (is (= [(list)] (core/meme->forms "()")))))

;; ---------------------------------------------------------------------------
;; B4: #() printer drops surplus % params.
;; Bug: (fn [%1 %2] (inc %1)) printed as #(inc(%1)) — silent arity change.
;; ---------------------------------------------------------------------------

(deftest anon-fn-surplus-params-not-dropped
  (testing "fn with surplus % params prints as fn(...), not #()"
    (let [form '(fn [%1 %2] (inc %1))
          printed (fmt-flat/format-form form)]
      (is (not (str/starts-with? printed "#("))
          "surplus params must not emit #() shorthand")
      (is (= form (first (core/meme->forms printed)))
          "roundtrip must preserve arity")))
  (testing "fn with matching % params uses #() only when :meme/sugar tagged"
    (let [form (with-meta '(fn [%1 %2] (+ %1 %2)) {:meme/sugar true})
          printed (fmt-flat/format-form form)]
      (is (str/starts-with? printed "#(")
          "matching params with sugar tag should emit #() shorthand")))
  (testing "fn without :meme/sugar never uses #() shorthand"
    (let [form '(fn [%1] (inc %1))
          printed (fmt-flat/format-form form)]
      (is (not (str/starts-with? printed "#("))
          "without :meme/sugar, fn always emits fn() form")
      (is (= form (first (core/meme->forms printed)))
          "roundtrip must preserve form"))))

;; ---------------------------------------------------------------------------
;; Bug: max-percent-n recursed into nested fn bodies, so
;; (fn [%1] (fn [%1] %1)) was printed as #(#(%1)) — the outer fn
;; incorrectly detected %1 from the inner fn's body and used #() shorthand.
;; Reader re-read this as (fn [] (fn [%1] %1)) — arity lost.
;; Fix: skip (fn ...) forms in max-percent-n, mirroring find-percent-params.
;; ---------------------------------------------------------------------------

(deftest anon-fn-nested-fn-not-counted
  (testing "(fn [%1] (fn [%1] %1)) — outer without sugar emits fn()"
    (let [form '(fn [%1] (fn [%1] %1))
          printed (fmt-flat/format-form form)]
      (is (not (str/starts-with? printed "#("))
          "without :meme/sugar, fn always emits fn() form")
      (is (= form (first (core/meme->forms printed)))
          "roundtrip must preserve outer arity")))
  (testing "(fn [%1] (inc %1)) — with sugar emits #()"
    (is (= "#(inc(%1))" (fmt-flat/format-form (with-meta '(fn [%1] (inc %1)) {:meme/sugar true}))))))

;; ---------------------------------------------------------------------------
;; Quote roundtrips — both sugar and call paths.
;; ---------------------------------------------------------------------------

(deftest quoted-call-form-roundtrip
  (testing "'f(g(x)) sugar roundtrips"
    (let [form (with-meta '(quote (f (g x))) {:meme/sugar true})
          printed (fmt-flat/format-form form)
          reread (first (core/meme->forms printed))]
      (is (= "'f(g(x))" printed))
      (is (= '(quote (f (g x))) reread))))
  (testing "quote(a(b(c) d)) call form roundtrips"
    (let [form '(quote (a (b c) d))
          printed (fmt-flat/format-form form)
          read-back (first (core/meme->forms printed))]
      (is (= form read-back)))))

;; ---------------------------------------------------------------------------
;; Scar tissue: canon formatter head-line args must respect width.
;; Bug: pp-call-smart always printed head-line args flat.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest canon-head-line-args-respect-width
     (testing "long if-condition falls back to body when it exceeds width"
       (let [form '(if (and (> x 100) (< y 200) (not= z 0) (pos? w)) (body1) (body2))
             result (fmt-canon/format-form form {:width 40})]
         (is (not (re-find #"if\(and" result))
             "long condition should not stay on head line")
         (doseq [line (str/split-lines result)]
           (is (<= (count line) 42)
               (str "line exceeds width: " (pr-str line))))))
     (testing "short if-condition stays on head line when multi-line needed"
       (let [form '(if (> x 0) (do-something-with x) (do-something-else y))
             result (fmt-canon/format-form form {:width 40})]
         (is (re-find #"if\(>" result)
             "short condition should stay on head line")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: canon formatter map value column must use actual key width.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest canon-map-value-column-uses-actual-key-width
     (testing "map value indentation based on actual key width, not flat width"
       (let [form {:k '(some-long-function arg1 arg2 arg3 arg4 arg5)}
             result (fmt-canon/format-form form {:width 40})
             lines (str/split-lines result)]
         (is (some? result))
         (is (> (count lines) 1) "should be multi-line")))))

;; ---------------------------------------------------------------------------
;; Bug: pp-map underestimated val-col for single-line keys.
;; val-col omitted inner-col, so values got more horizontal space than
;; they actually had, printing flat when they should break.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest canon-map-value-column-includes-indent
     (testing "map value breaks when key + indent + value exceed width"
       (let [form (sorted-map :k '(fff arg1 arg2 arg3 arg4 a) :z 1)
             result (fmt-canon/format-form form {:width 30})
             lines (str/split-lines result)]
         (is (> (count lines) 3) "value should break to multi-line")
         (doseq [line lines]
           (is (<= (count line) 34)
               (str "line exceeds width: " (pr-str line))))))))

;; ---------------------------------------------------------------------------
;; Bug: canon formatter silently dropped metadata on multi-line call forms.
;; ---------------------------------------------------------------------------

(deftest canon-metadata-on-multi-line-forms
  (testing "^:private preserved on multi-line call"
    (let [form (with-meta '(defn foo [x] x) {:private true})
          result (fmt-canon/format-form form {:width 10})]
      (is (re-find #"^\^:private" result))))
  (testing "^:private preserved on flat call"
    (let [form (with-meta '(defn foo [x] x) {:private true})
          result (fmt-canon/format-form form {:width 200})]
      (is (re-find #"^\^:private" result))))
  (testing "^String type tag preserved"
    (let [form (with-meta '(defn foo [x] x) {:tag 'String})
          result (fmt-canon/format-form form {:width 10})]
      (is (re-find #"^\^String" result))))
  (testing "^{:doc ...} map metadata preserved"
    (let [form (with-meta '(defn foo [x] x) {:doc "hello"})
          result (fmt-canon/format-form form {:width 10})]
      ;; L12: metadata maps now participate in width-aware layout, so at narrow
      ;; widths the map may break across lines. The key assertion: ^{ is present.
      (is (re-find #"^\^" result))
      (is (re-find #":doc" result))))
  (testing "^{:doc ...} map metadata flat at wide width"
    (let [form (with-meta '(defn foo [x] x) {:doc "hello"})
          result (fmt-canon/format-form form {:width 200})]
      (is (re-find #"^\^\{:doc" result))))
  (testing "metadata on non-call forms (vector)"
    (let [form (with-meta [1 2 3] {:tag 'ints})
          result (fmt-canon/format-form form {:width 10})]
      (is (re-find #"^\^ints" result)))))

;; ---------------------------------------------------------------------------
;; Bug: comment lines from :ws metadata emitted at column 0 inside nested
;; multi-line blocks.
;; ---------------------------------------------------------------------------

(deftest canon-comment-indentation-in-nested-blocks
  (testing "single comment indented to match body"
    (let [baz (with-meta '(baz) {:ws "; single\n"})
          form (list 'foo '(bar) baz)
          result (fmt-canon/format-form form {:width 10})
          lines (str/split-lines result)]
      (is (= "  ; single" (nth lines 2)))))
  (testing "multiple comment lines all indented"
    (let [baz (with-meta '(baz) {:ws "; line 1\n; line 2\n"})
          form (list 'foo '(bar) baz)
          result (fmt-canon/format-form form {:width 10})
          lines (str/split-lines result)]
      (is (= "  ; line 1" (nth lines 2)))
      (is (= "  ; line 2" (nth lines 3)))))
  (testing "original whitespace stripped and re-indented"
    (let [baz (with-meta '(baz) {:ws "    ; deep\n"})
          form (list 'foo '(bar) baz)
          result (fmt-canon/format-form form {:width 10})
          lines (str/split-lines result)]
      (is (= "  ; deep" (nth lines 2)))))
  (testing "top-level comments unchanged"
    (let [form (with-meta '(foo x) {:ws "; top\n"})
          result (fmt-canon/format-form form)]
      (is (str/starts-with? result "; top\n")))))

;; ---------------------------------------------------------------------------
;; Bug: canon formatter rendered deferred :: keywords as clojure.core/read-string(...)
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest canon-deferred-auto-keyword-not-corrupted
     (testing "::foo preserved at narrow width"
       (is (= "::foo"
              (fmt-canon/format-form (forms/deferred-auto-keyword "::foo") {:width 4}))))
     (testing "::keyword preserved when nested deeply"
       (let [result (fmt-canon/format-form
                     (list 'def 'x (forms/deferred-auto-keyword "::long-keyword"))
                     {:width 20})]
         (is (re-find #"::long-keyword" result))
         (is (not (re-find #"clojure.core/read-string" result)))))
     (testing ":: keyword roundtrips through canon formatter"
       (let [src "def(x ::my-key)"
             forms (core/meme->forms src)
             formatted (fmt-canon/format-forms forms {:width 10})
             re-read (core/meme->forms formatted)]
         (is (= forms re-read))))))

;; ---------------------------------------------------------------------------
;; Bug: canon formatter lost reader-sugar notation (@, #', ')
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest canon-preserves-deref-sugar
     (testing "@atom sugar preserved at narrow width"
       (let [form (with-meta (list 'clojure.core/deref 'my-very-long-atom-name) {:meme/sugar true})
             result (fmt-canon/format-form form {:width 10})]
         (is (str/starts-with? result "@"))
         (is (not (str/includes? result "clojure.core/deref")))))
     (testing "clojure.core/deref(x) call form preserved when not tagged"
       (let [form (list 'clojure.core/deref 'my-atom)
             result (fmt-canon/format-form form {:width 10})]
         (is (str/includes? result "clojure.core/deref("))))))

#?(:clj
   (deftest canon-preserves-var-sugar
     (testing "#'sym sugar preserved at narrow width"
       (let [form (with-meta (list 'var 'some.ns/my-var) {:meme/sugar true})
             result (fmt-canon/format-form form {:width 5})]
         (is (str/starts-with? result "#'"))
         (is (not (str/includes? result "var(")))))
     (testing "var(x) call form preserved when not tagged"
       (let [form (list 'var 'some.ns/my-var)
             result (fmt-canon/format-form form {:width 5})]
         (is (str/includes? result "var("))))))

#?(:clj
   (deftest canon-preserves-quote-sugar
     (testing "'sym sugar preserved at narrow width"
       (let [form (with-meta (list 'quote 'my-long-symbol-name) {:meme/sugar true})
             result (fmt-canon/format-form form {:width 5})]
         (is (str/starts-with? result "'"))
         (is (not (str/includes? result "quote(")))))
     (testing "quote(x) call form preserved when not tagged"
       (let [form (list 'quote 'my-long-symbol-name)
             result (fmt-canon/format-form form {:width 5})]
         (is (str/includes? result "quote("))))))

;; ---------------------------------------------------------------------------
;; Bug: reader-conditional printer emitted S-expressions via pr-str.
;; Fix: walk inner forms with print-form to emit meme syntax.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest reader-conditional-prints-meme-syntax
     (testing "reader conditional inner forms use meme syntax"
       (let [rc (read-string {:read-cond :preserve} "#?(:clj (+ 1 2) :cljs (- 3 4))")
             printed (fmt-flat/format-form rc)]
         (is (= "#?(:clj +(1 2) :cljs -(3 4))" printed))))
     (testing "#?@ splicing variant"
       (let [rc (read-string {:read-cond :preserve} "#?@(:clj [1 2] :cljs [3 4])")
             printed (fmt-flat/format-form rc)]
         (is (= "#?@(:clj [1 2] :cljs [3 4])" printed))))
     (testing "reader conditional with nested calls"
       (let [rc (read-string {:read-cond :preserve} "#?(:clj (defn foo [x] x))")
             printed (fmt-flat/format-form rc)]
         (is (re-find #"defn\(" printed))))))

;; ---------------------------------------------------------------------------
;; Bug: nil/true/false as call heads produced unparseable meme output.
;; nil/true/false as list heads — the rule is purely syntactic.
;; ---------------------------------------------------------------------------

(deftest literal-head-prints
  (testing "nil as head"
    (is (= "nil(1 2)" (fmt-flat/format-form (list nil 1 2)))))
  (testing "true as head"
    (is (= "true(1 2)" (fmt-flat/format-form (list true 1 2)))))
  (testing "false as head"
    (is (= "false(1 2)" (fmt-flat/format-form (list false 1 2))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: ' prefix sugar roundtrips for all inner form types.
;; ---------------------------------------------------------------------------

(deftest quote-sugar-roundtrips-all-heads
  (testing "' sugar roundtrips through meme reader for all head types"
    (doseq [[input expected-form]
            [["'1(2 3)"       '(quote (1 2 3))]
             ["'\"s\"(a)"     '(quote ("s" a))]
             ["':k(v)"        '(quote (:k v))]
             ["'[x](body)"   '(quote ([x] body))]
             ["'f(x)"         '(quote (f x))]
             ["'x"            '(quote x)]
             ["'42"           '(quote 42)]
             ["'()"           '(quote ())]]]
      (testing input
        (let [forms (core/meme->forms input)
              printed (core/forms->meme forms)]
          (is (= [expected-form] forms))
          (is (= input printed)))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: MemeRaw in canon formatter was rendered as {:value N :raw "..."}.
;; ---------------------------------------------------------------------------

(deftest canon-meme-raw-renders-source-notation
  #?(:clj
     (testing "hex literal in canon formatter renders as 0xFF, not {:value 255 :raw ...}"
       (let [forms (core/meme->forms "let([x 0xFF] x)")
             pp (core/format-meme forms)]
         (is (str/includes? pp "0xFF") "formatter must preserve hex notation")
         (is (not (str/includes? pp ":value")) "formatter must not leak MemeRaw fields"))))
  (testing "scientific notation in canon formatter"
    (let [forms (core/meme->forms "def(y 1e5)")
          pp (core/format-meme forms)]
      (is (str/includes? pp "1e5") "formatter must preserve scientific notation"))))

;; ---------------------------------------------------------------------------
;; Scar tissue: canon formatter multi-line paths lost notation-preserving metadata.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest canon-preserves-anon-fn-sugar
     (testing "#() sugar preserved in multi-line"
       (let [form (first (core/meme->forms "#(long-function(a b c d e f))"))
             result (fmt-canon/format-form form {:width 20})]
         (is (str/starts-with? result "#("))
         (is (not (str/includes? result "fn(")))))
     (testing "#() with short body stays flat"
       (let [form (first (core/meme->forms "#(inc(%))"))
             result (fmt-canon/format-form form {:width 80})]
         (is (= "#(inc(%))" result))))))

#?(:clj
   (deftest canon-preserves-namespaced-map
     (testing "#:ns{} prefix preserved in multi-line"
       (let [form (first (core/meme->forms "#:user{:name \"x\" :age 42 :email \"long@example.com\"}"))
             result (fmt-canon/format-form form {:width 20})]
         (is (str/starts-with? result "#:user{"))
         (is (not (re-find #"^\{" result)))))
     (testing "#:ns{} with auto-resolve preserved"
       (let [form (first (core/meme->forms "#::foo{:a 1 :b 2 :c \"a-very-long-value-here\"}"))
             result (fmt-canon/format-form form {:width 20})]
         (is (str/starts-with? result "#::foo{"))))))

#?(:clj
   (deftest canon-preserves-meta-chain
     (testing "^:a ^:b chain preserved in multi-line"
       (let [form (first (core/meme->forms "^:private ^:deprecated defn(foo [x] x)"))
             result (fmt-canon/format-form form {:width 30})]
         (is (str/starts-with? result "^:private ^:deprecated"))
         (is (not (str/includes? result "^{")))))
     (testing "single ^:key still works"
       (let [form (first (core/meme->forms "^:private defn(foo [x] x)"))
             result (fmt-canon/format-form form {:width 20})]
         (is (str/starts-with? result "^:private"))))))

;; ---------------------------------------------------------------------------
;; Bug: regex patterns containing " printed unescaped.
;; ---------------------------------------------------------------------------

(deftest regex-quote-in-pattern-escaped
  (testing "regex from meme source with \\\" roundtrips"
    (let [forms (core/meme->forms "#\"a\\\"b\"")
          printed (fmt-flat/format-forms forms)
          forms2 (core/meme->forms printed)]
      (is (= #?(:clj (.pattern ^java.util.regex.Pattern (first forms))
                :cljs (.-source (first forms)))
             #?(:clj (.pattern ^java.util.regex.Pattern (first forms2))
                :cljs (.-source (first forms2)))))))
  #?(:clj
     (testing "programmatic regex with bare \" produces parseable output"
       (let [r (re-pattern "a\"b")
             printed (fmt-flat/format-form r)]
         (is (re-find #"^#\"" printed))
         (is (some? (core/meme->forms printed)))
         (is (= (re-find r "a\"b")
                (re-find (first (core/meme->forms printed)) "a\"b"))))))
  (testing "no double-escaping of already-escaped quotes"
    (let [forms (core/meme->forms "#\"x\\\"y\"")
          printed (fmt-flat/format-forms forms)
          forms2 (core/meme->forms printed)]
      (is (= #?(:clj (.pattern ^java.util.regex.Pattern (first forms))
                :cljs (.-source (first forms)))
             #?(:clj (.pattern ^java.util.regex.Pattern (first forms2))
                :cljs (.-source (first forms2)))))))
  #?(:clj
     (testing "even backslashes before quote: \\\\ + \" not misidentified as escaped quote"
       (let [r (re-pattern "a\\\\\"b")
             printed (fmt-flat/format-form r)
             forms (core/meme->forms printed)]
         (is (some? forms) "printed regex should parse without error")
         (is (= (re-find r "a\\\"b")
                (re-find (first forms) "a\\\"b"))
             "regex behavior should be preserved")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: #() shorthand in :clj mode wrapped the body in extra parens.
;; (fn [%1] (+ %1 1)) with :meme/sugar printed as #((+ %1 1)) instead of
;; #(+ %1 1). In Clojure, #((f)) calls the result of (f) — different semantics.
;; Fix: unwrap the body list in :clj mode.
;; ---------------------------------------------------------------------------

(deftest anon-fn-clj-mode-no-double-parens
  (testing "single-arg #() in :clj mode"
    (let [form (with-meta '(fn [%1] (+ %1 1)) {:meme/sugar true})]
      (is (= "#(+ %1 1)" (fmt-flat/format-clj [form]))
          "#() body must not get extra parens in :clj mode")))
  (testing "multi-arg #() in :clj mode"
    (let [form (with-meta '(fn [%1 %2] (+ %1 %2)) {:meme/sugar true})]
      (is (= "#(+ %1 %2)" (fmt-flat/format-clj [form]))
          "#() with multiple args must not get extra parens")))
  (testing "#() in :meme mode is unchanged"
    (let [form (with-meta '(fn [%1] (+ %1 1)) {:meme/sugar true})]
      (is (= "#(+(%1 1))" (fmt-flat/format-forms [form]))
          "#() in meme mode should use call syntax"))))

;; ---------------------------------------------------------------------------
;; Scar tissue: #() with non-list body in :clj mode
;; ---------------------------------------------------------------------------
;; (fn [] 42) was emitted as #(42) in :clj mode, but in Clojure #(42) means
;; (fn [] (42)) — calling 42 as a function. Semantic corruption during
;; meme->clj conversion. Fix: reject #() shorthand for non-list bodies in
;; :clj mode; fall through to (fn ...) form.
;; ---------------------------------------------------------------------------

(deftest anon-fn-clj-mode-non-list-body
  (testing "(fn [] 42) must not become #(42) in :clj mode"
    (let [form (with-meta '(fn [] 42) {:meme/sugar true})]
      (is (= "(fn [] 42)" (fmt-flat/format-clj [form])))))
  (testing "(fn [] identity) must not become #(identity) in :clj mode"
    (let [form (with-meta '(fn [] identity) {:meme/sugar true})]
      (is (= "(fn [] identity)" (fmt-flat/format-clj [form])))))
  (testing "(fn [] :foo) must not become #(:foo) in :clj mode"
    (let [form (with-meta '(fn [] :foo) {:meme/sugar true})]
      (is (= "(fn [] :foo)" (fmt-flat/format-clj [form])))))
  (testing "list body still uses #() shorthand in :clj mode"
    (let [form (with-meta '(fn [%1] (+ %1 1)) {:meme/sugar true})]
      (is (= "#(+ %1 1)" (fmt-flat/format-clj [form])))))
  (testing "meme->clj roundtrip for #(42)"
    (is (= "(fn [] 42)" (core/meme->clj "#(42)"))))
  (testing "meme->clj roundtrip for #(identity)"
    (is (= "(fn [] identity)" (core/meme->clj "#(identity)")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: metadata stripping loses notation metadata
;; ---------------------------------------------------------------------------
;; When a form has both user metadata (^:foo) and internal notation metadata
;; (:meme/ns, :meme/order, :meme/sugar), the printer stripped ALL metadata
;; via (with-meta form nil). This lost the notation keys, causing #:ns{} maps
;; to print with fully-qualified keys, sets to lose insertion order, and
;; quote sugar to be lost.
;; Fix: preserve forms/notation-meta-keys when stripping user metadata.
;; ---------------------------------------------------------------------------

(deftest metadata-stripping-preserves-notation
  (testing "^:validated #:user{} preserves namespaced-map notation"
    (let [src "^:validated #:user{:name \"x\"}"
          printed (core/forms->meme (core/meme->forms src))]
      (is (str/includes? printed "#:user{")
          "namespaced-map notation must survive metadata stripping")))
  (testing "quote sugar preserved with user metadata"
    (let [src "^:foo 'x"
          printed (core/forms->meme (core/meme->forms src))]
      (is (str/includes? printed "'x")
          "quote sugar must survive metadata stripping"))))

;; ---------------------------------------------------------------------------
;; Scar tissue: set/map ordering in meme-rewrite lang
;; ---------------------------------------------------------------------------
;; Sets lost insertion order and maps lost key order in the meme-rewrite lang
;; because transform-structures used hash-map/bare set.
;; Fix: use array-map for maps and attach :meme/order metadata for sets,
;; and respect :meme/order in the emit module.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest rewrite-preserves-collection-order
     (testing "set insertion order preserved through meme-rewrite lang"
       (is (= "#{1 2 3}" ((:to-clj (lang/resolve-lang :meme-rewrite)) "#{1 2 3}"))))
     (testing "map key order preserved through meme-rewrite lang"
       (is (= "{:a 1 :b 2}" ((:to-clj (lang/resolve-lang :meme-rewrite)) "{:a 1 :b 2}"))))))

;; ---------------------------------------------------------------------------
;; F7: bare % was normalized to %1, losing the user's notation choice.
;; Bug: normalize-bare-percent converted % to %1 in the body for eval
;; correctness, but the printer always emitted %1 even when the user wrote %.
;; Fix: reader tags :meme/bare-percent in metadata; printer restores % from %1.
;; ---------------------------------------------------------------------------

(deftest bare-percent-notation-preserved
  (testing "#(inc(%)) roundtrips preserving bare %"
    (let [src "#(inc(%))"
          forms (core/meme->forms src)
          back (core/forms->meme forms)]
      (is (= src back))))
  (testing "#(+(%1 %2)) roundtrips preserving explicit %1"
    (let [src "#(+(%1 %2))"
          forms (core/meme->forms src)
          back (core/forms->meme forms)]
      (is (= src back))))
  (testing "#(+(% %2)) roundtrips preserving bare % mixed with %2"
    (let [src "#(+(% %2))"
          forms (core/meme->forms src)
          back (core/forms->meme forms)]
      (is (= src back)))))

;; ---------------------------------------------------------------------------
;; RT2-M15: restore-bare-percent didn't recurse into maps/sets.
;; #(#{%}) roundtripped as #(#{%1}), losing the bare % notation.
;; Fix: added map? and set? cases to restore-bare-percent.
;; ---------------------------------------------------------------------------

(deftest bare-percent-in-maps-and-sets
  (testing "#({:a %}) roundtrips preserving bare %"
    (let [src "#({:a %})"
          forms (core/meme->forms src)
          back (core/forms->meme forms)]
      (is (= src back))))
  (testing "#(#{%}) roundtrips preserving bare %"
    (let [src "#(#{%})"
          forms (core/meme->forms src)
          back (core/forms->meme forms)]
      (is (= src back))))
  (testing "#(`~%) roundtrips preserving bare % through AST nodes"
    (let [src "#(`~%)"
          forms (core/meme->forms src)
          back (core/forms->meme forms)]
      (is (= src back)))))

;; ---------------------------------------------------------------------------
;; RT2-L13: flat/format-forms dropped trailing file comments.
;; canon/format-forms preserved them. Fix: flat now mirrors canon behavior.
;; ---------------------------------------------------------------------------

(deftest flat-format-preserves-trailing-comments
  (testing "trailing comment after forms is preserved in flat output"
    (let [forms (core/meme->forms "def(x 1)\n; trailing comment")
          result (fmt-flat/format-forms forms)]
      (is (re-find #"; trailing comment" result)
          "trailing comment should appear in flat output")))
  (testing "no trailing comment — no extra output"
    (let [forms (core/meme->forms "def(x 1)")
          result (fmt-flat/format-forms forms)]
      (is (= "def(x 1)" result)))))

;; ---------------------------------------------------------------------------
;; Scar tissue: printer must not hang on infinite or very large lazy sequences.
;; RT3-F1: flat/format-form on (range) previously caused OOM.
;; ---------------------------------------------------------------------------

(deftest printer-handles-infinite-seq
  (testing "infinite lazy seq does not hang — produces bounded output"
    (let [result (fmt-flat/format-form (list 'foo (range)))]
      (is (string? result) "should produce a string, not hang")
      (is (str/starts-with? result "foo(") "should start with call head")))
  (testing "large lazy seq is bounded by *print-length*"
    (binding [*print-length* 5]
      (let [result (fmt-flat/format-form (cons '+ (range 1000)))]
        (is (string? result))
        (is (str/includes? result "...") "should contain truncation marker"))))
  (testing "small seq within bounds is not truncated"
    (let [result (fmt-flat/format-form '(+ 1 2 3))]
      (is (= "+(1 2 3)" result))
      (is (not (str/includes? result "...")) "should not be truncated"))))

;; ---------------------------------------------------------------------------
;; Scar tissue: letfn uses n-head=0 formatting like let (RT3-F25)
;; ---------------------------------------------------------------------------

(deftest letfn-formatting-matches-let
  (testing "letfn flat output is correct"
    (let [form '(letfn [(f [x] (+ x 1)) (g [y] y)] (f (g 1)))
          result (fmt-flat/format-form form)]
      (is (str/starts-with? result "letfn("))
      (is (str/includes? result "f([x]") "should use vector-as-head for arity clauses")))
  (testing "letfn breaks at narrow width like let"
    (let [form '(letfn [(process [data] (map inc data)) (validate [x] (pos? x))] (validate (first (process items))))
          result (fmt-canon/format-form form 30)]
      (is (str/starts-with? result "letfn("))
      (is (str/includes? result "\n") "should break at narrow width"))))

;; ---------------------------------------------------------------------------
;; Scar tissue: restore-bare-percent must skip nested fn bodies (D53)
;; Previously: inner fn's %1 was incorrectly restored to % by the outer #().
;; ---------------------------------------------------------------------------

(deftest restore-bare-percent-skips-nested-fn
  (testing "#() containing nested fn with %1 — inner %1 preserved"
    (let [;; Simulates: #(map(fn([x] %1) %))
          ;; The outer #() has bare-percent, inner fn has its own %1
          form (with-meta
                 '(fn [%1] (map (fn [x] %1) %1))
                 {:meme/sugar true :meme/bare-percent true})
          printed (fmt-flat/format-form form)]
      ;; The outer %1 should become %, but the inner fn's %1 should stay as %1
      (is (str/includes? printed "#(map(fn([x] %1) %)")
          "inner fn's %1 must not be restored to %"))))

;; ---------------------------------------------------------------------------
;; Scar tissue: rewrite/emit preserves hex/octal notation via :raw (D65)
;; Previously: used (:value form) which lost notation (0xFF → 255).
;; ---------------------------------------------------------------------------

(deftest rewrite-emit-preserves-raw-notation
  (testing "MemeRaw with hex notation preserved in rewrite emit"
    (let [raw-form (meme.forms/->MemeRaw 255 "0xFF")
          emitted (remit/emit raw-form)]
      (is (= "0xFF" emitted) "should use :raw notation, not numeric value")))
  (testing "MemeRaw with octal notation preserved"
    (let [raw-form (meme.forms/->MemeRaw 15 "017")
          emitted (remit/emit raw-form)]
      (is (= "017" emitted) "should use :raw notation, not numeric value"))))
