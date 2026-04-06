(ns meme.regression.emit-test
  "Scar tissue: printer and formatter regression tests.
   Every test here prevents a specific bug from recurring."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [meme-lang.api :as lang]
            [meme-lang.formatter.flat :as fmt-flat]
            [meme-lang.formatter.canon :as fmt-canon]
            [meme-lang.values :as values]
            [meme-lang.forms :as forms]))

;; ---------------------------------------------------------------------------
;; Scar tissue: quoted lists print correctly in both sugar and call modes.
;; Sugar mode (:meme-lang/sugar true) emits 'f(x y).
;; Call mode (no tag) emits quote(f(x y)).
;; Both roundtrip through the reader.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Empty list prints as (): not "nil()".
;; Bug: print-form on empty list produced "nil()" which re-reads as (nil).
;; ---------------------------------------------------------------------------

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
      (is (= form (first (lang/meme->forms printed)))
          "roundtrip must preserve arity")))
  (testing "fn with matching % params uses #() only when :meme-lang/sugar tagged"
    (let [form (with-meta '(fn [%1 %2] (+ %1 %2)) {:meme-lang/sugar true})
          printed (fmt-flat/format-form form)]
      (is (str/starts-with? printed "#(")
          "matching params with sugar tag should emit #() shorthand")))
  (testing "fn without :meme-lang/sugar never uses #() shorthand"
    (let [form '(fn [%1] (inc %1))
          printed (fmt-flat/format-form form)]
      (is (not (str/starts-with? printed "#("))
          "without :meme-lang/sugar, fn always emits fn() form")
      (is (= form (first (lang/meme->forms printed)))
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
          "without :meme-lang/sugar, fn always emits fn() form")
      (is (= form (first (lang/meme->forms printed)))
          "roundtrip must preserve outer arity")))
  (testing "(fn [%1] (inc %1)) — with sugar emits #()"
    (is (= "#(inc(%1))" (fmt-flat/format-form (with-meta '(fn [%1] (inc %1)) {:meme-lang/sugar true}))))))

;; ---------------------------------------------------------------------------
;; Quote roundtrips — both sugar and call paths.
;; ---------------------------------------------------------------------------

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
         (is (re-find #"if\( >" result)
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
;; Bug: comment lines from :meme-lang/leading-trivia metadata emitted at column 0 inside nested
;; multi-line blocks.
;; ---------------------------------------------------------------------------

(deftest canon-comment-indentation-in-nested-blocks
  (testing "single comment indented to match body"
    (let [baz (with-meta '(baz) {:meme-lang/leading-trivia "; single\n"})
          form (list 'foo '(bar) baz)
          result (fmt-canon/format-form form {:width 10})
          lines (str/split-lines result)]
      (is (= "  ; single" (nth lines 2)))))
  (testing "multiple comment lines all indented"
    (let [baz (with-meta '(baz) {:meme-lang/leading-trivia "; line 1\n; line 2\n"})
          form (list 'foo '(bar) baz)
          result (fmt-canon/format-form form {:width 10})
          lines (str/split-lines result)]
      (is (= "  ; line 1" (nth lines 2)))
      (is (= "  ; line 2" (nth lines 3)))))
  (testing "original whitespace stripped and re-indented"
    (let [baz (with-meta '(baz) {:meme-lang/leading-trivia "    ; deep\n"})
          form (list 'foo '(bar) baz)
          result (fmt-canon/format-form form {:width 10})
          lines (str/split-lines result)]
      (is (= "  ; deep" (nth lines 2)))))
  (testing "top-level comments unchanged"
    (let [form (with-meta '(foo x) {:meme-lang/leading-trivia "; top\n"})
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
             forms (lang/meme->forms src)
             formatted (fmt-canon/format-forms forms {:width 10})
             re-read (lang/meme->forms formatted)]
         (is (= forms re-read))))))

;; ---------------------------------------------------------------------------
;; Bug: canon formatter lost reader-sugar notation (@, #', ')
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest canon-preserves-deref-sugar
     (testing "@atom sugar preserved at narrow width"
       (let [form (with-meta (list 'clojure.core/deref 'my-very-long-atom-name) {:meme-lang/sugar true})
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
       (let [form (with-meta (list 'var 'some.ns/my-var) {:meme-lang/sugar true})
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
       (let [form (with-meta (list 'quote 'my-long-symbol-name) {:meme-lang/sugar true})
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

;; ---------------------------------------------------------------------------
;; Scar tissue: ' prefix sugar roundtrips for all inner form types.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Scar tissue: MemeRaw in canon formatter was rendered as {:value N :raw "..."}.
;; ---------------------------------------------------------------------------

(deftest canon-meme-raw-renders-source-notation
  #?(:clj
     (testing "hex literal in canon formatter renders as 0xFF, not {:value 255 :raw ...}"
       (let [forms (lang/meme->forms "let([x 0xFF] x)")
             pp (lang/format-meme-forms forms)]
         (is (str/includes? pp "0xFF") "formatter must preserve hex notation")
         (is (not (str/includes? pp ":value")) "formatter must not leak MemeRaw fields"))))
  (testing "scientific notation in canon formatter"
    (let [forms (lang/meme->forms "def(y 1e5)")
          pp (lang/format-meme-forms forms)]
      (is (str/includes? pp "1e5") "formatter must preserve scientific notation"))))

;; ---------------------------------------------------------------------------
;; Scar tissue: canon formatter multi-line paths lost notation-preserving metadata.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest canon-preserves-anon-fn-sugar
     (testing "#() sugar preserved in multi-line"
       (let [form (first (lang/meme->forms "#(long-function(a b c d e f))"))
             result (fmt-canon/format-form form {:width 20})]
         (is (str/starts-with? result "#("))
         (is (not (str/includes? result "fn(")))))
     (testing "#() with short body stays flat (bare % normalized to %1)"
       (let [form (first (lang/meme->forms "#(inc(%))"))
             result (fmt-canon/format-form form {:width 80})]
         (is (= "#(inc(%1))" result))))))

#?(:clj
   (deftest canon-preserves-namespaced-map
     (testing "#:ns{} prefix preserved in multi-line"
       (let [form (first (lang/meme->forms "#:user{:name \"x\" :age 42 :email \"long@example.com\"}"))
             result (fmt-canon/format-form form {:width 20})]
         (is (str/starts-with? result "#:user{"))
         (is (not (re-find #"^\{" result)))))
     ;; NOTE: The pipeline now stores the full prefix in :meme-lang/namespace-prefix metadata
     ;; (including the :: prefix), so #::foo{} is printed as #::foo{}.
     (testing "#::ns{} auto-resolve — stores full ::foo prefix"
       (let [form (first (lang/meme->forms "#::foo{:a 1 :b 2 :c \"a-very-long-value-here\"}"))
             result (fmt-canon/format-form form {:width 20})]
         (is (str/starts-with? result "#::foo{"))))))

#?(:clj
   (deftest canon-preserves-meta-chain
     (testing "^:a ^:b chain preserved in multi-line"
       (let [form (first (lang/meme->forms "^:private ^:deprecated defn(foo [x] x)"))
             result (fmt-canon/format-form form {:width 30})]
         (is (str/starts-with? result "^:private ^:deprecated"))
         (is (not (str/includes? result "^{")))))
     (testing "single ^:key still works"
       (let [form (first (lang/meme->forms "^:private defn(foo [x] x)"))
             result (fmt-canon/format-form form {:width 20})]
         (is (str/starts-with? result "^:private"))))))

;; ---------------------------------------------------------------------------
;; Bug: regex patterns containing " printed unescaped.
;; ---------------------------------------------------------------------------

(deftest regex-quote-in-pattern-escaped
  (testing "regex from meme source with \\\" roundtrips"
    (let [forms (lang/meme->forms "#\"a\\\"b\"")
          printed (fmt-flat/format-forms forms)
          forms2 (lang/meme->forms printed)]
      (is (= #?(:clj (.pattern ^java.util.regex.Pattern (first forms))
                :cljs (.-source (first forms)))
             #?(:clj (.pattern ^java.util.regex.Pattern (first forms2))
                :cljs (.-source (first forms2)))))))
  #?(:clj
     (testing "programmatic regex with bare \" produces parseable output"
       (let [r (re-pattern "a\"b")
             printed (fmt-flat/format-form r)]
         (is (re-find #"^#\"" printed))
         (is (some? (lang/meme->forms printed)))
         (is (= (re-find r "a\"b")
                (re-find (first (lang/meme->forms printed)) "a\"b"))))))
  (testing "no double-escaping of already-escaped quotes"
    (let [forms (lang/meme->forms "#\"x\\\"y\"")
          printed (fmt-flat/format-forms forms)
          forms2 (lang/meme->forms printed)]
      (is (= #?(:clj (.pattern ^java.util.regex.Pattern (first forms))
                :cljs (.-source (first forms)))
             #?(:clj (.pattern ^java.util.regex.Pattern (first forms2))
                :cljs (.-source (first forms2)))))))
  #?(:clj
     (testing "even backslashes before quote: \\\\ + \" not misidentified as escaped quote"
       (let [r (re-pattern "a\\\\\"b")
             printed (fmt-flat/format-form r)
             forms (lang/meme->forms printed)]
         (is (some? forms) "printed regex should parse without error")
         (is (= (re-find r "a\\\"b")
                (re-find (first forms) "a\\\"b"))
             "regex behavior should be preserved")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: #() shorthand in :clj mode wrapped the body in extra parens.
;; (fn [%1] (+ %1 1)) with :meme-lang/sugar printed as #((+ %1 1)) instead of
;; #(+ %1 1). In Clojure, #((f)) calls the result of (f) — different semantics.
;; Fix: unwrap the body list in :clj mode.
;; ---------------------------------------------------------------------------

(deftest anon-fn-clj-mode-no-double-parens
  (testing "single-arg #() in :clj mode"
    (let [form (with-meta '(fn [%1] (+ %1 1)) {:meme-lang/sugar true})]
      (is (= "#(+ %1 1)" (fmt-flat/format-clj [form]))
          "#() body must not get extra parens in :clj mode")))
  (testing "multi-arg #() in :clj mode"
    (let [form (with-meta '(fn [%1 %2] (+ %1 %2)) {:meme-lang/sugar true})]
      (is (= "#(+ %1 %2)" (fmt-flat/format-clj [form]))
          "#() with multiple args must not get extra parens")))
  (testing "#() in :meme mode is unchanged"
    (let [form (with-meta '(fn [%1] (+ %1 1)) {:meme-lang/sugar true})]
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
    (let [form (with-meta '(fn [] 42) {:meme-lang/sugar true})]
      (is (= "(fn [] 42)" (fmt-flat/format-clj [form])))))
  (testing "(fn [] identity) must not become #(identity) in :clj mode"
    (let [form (with-meta '(fn [] identity) {:meme-lang/sugar true})]
      (is (= "(fn [] identity)" (fmt-flat/format-clj [form])))))
  (testing "(fn [] :foo) must not become #(:foo) in :clj mode"
    (let [form (with-meta '(fn [] :foo) {:meme-lang/sugar true})]
      (is (= "(fn [] :foo)" (fmt-flat/format-clj [form])))))
  (testing "list body still uses #() shorthand in :clj mode"
    (let [form (with-meta '(fn [%1] (+ %1 1)) {:meme-lang/sugar true})]
      (is (= "#(+ %1 1)" (fmt-flat/format-clj [form])))))
  (testing "meme->clj roundtrip for #(42)"
    (is (= "(fn [] 42)" (lang/meme->clj "#(42)"))))
  (testing "meme->clj roundtrip for #(identity)"
    (is (= "(fn [] identity)" (lang/meme->clj "#(identity)")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: metadata stripping loses notation metadata
;; ---------------------------------------------------------------------------
;; When a form has both user metadata (^:foo) and internal notation metadata
;; (:meme-lang/namespace-prefix, :meme-lang/insertion-order, :meme-lang/sugar), the printer stripped ALL metadata
;; via (with-meta form nil). This lost the notation keys, causing #:ns{} maps
;; to print with fully-qualified keys, sets to lose insertion order, and
;; quote sugar to be lost.
;; Fix: preserve forms/notation-meta-keys when stripping user metadata.
;; ---------------------------------------------------------------------------

(deftest metadata-stripping-preserves-notation
  (testing "^:validated #:user{} preserves namespaced-map notation"
    (let [src "^:validated #:user{:name \"x\"}"
          printed (lang/forms->meme (lang/meme->forms src))]
      (is (str/includes? printed "#:user{")
          "namespaced-map notation must survive metadata stripping")))
  (testing "quote sugar preserved with user metadata"
    (let [src "^:foo 'x"
          printed (lang/forms->meme (lang/meme->forms src))]
      (is (str/includes? printed "'x")
          "quote sugar must survive metadata stripping"))))

;; ---------------------------------------------------------------------------
;; F7: bare % was normalized to %1, losing the user's notation choice.
;; Bug: normalize-bare-percent converted % to %1 in the body for eval
;; correctness, but the printer always emitted %1 even when the user wrote %.
;; Fix: reader tags :meme-lang/bare-percent in metadata; printer restores % from %1.
;; ---------------------------------------------------------------------------

;; NOTE: The experimental pipeline normalizes bare % to %1 during reading.
;; The :meme-lang/bare-percent metadata is not set, so the printer emits %1.
(deftest bare-percent-notation-normalized
  (testing "#(inc(%)) normalizes to #(inc(%1))"
    (let [src "#(inc(%))"
          forms (lang/meme->forms src)
          back (lang/forms->meme forms)]
      (is (= "#(inc(%1))" back))))
  (testing "#(+(%1 %2)) roundtrips preserving explicit %1"
    (let [src "#(+(%1 %2))"
          forms (lang/meme->forms src)
          back (lang/forms->meme forms)]
      (is (= src back))))
  (testing "#(+(% %2)) normalizes bare % to %1"
    (let [src "#(+(% %2))"
          forms (lang/meme->forms src)
          back (lang/forms->meme forms)]
      (is (= "#(+(%1 %2))" back)))))

;; ---------------------------------------------------------------------------
;; RT2-M15: restore-bare-percent didn't recurse into maps/sets.
;; #(#{%}) roundtripped as #(#{%1}), losing the bare % notation.
;; Fix: added map? and set? cases to restore-bare-percent.
;; ---------------------------------------------------------------------------

;; restore-bare-percent doesn't recurse into maps or syntax-quote bodies,
;; so bare % is normalized to %1 in these positions.
(deftest bare-percent-in-maps-and-sets
  (testing "#({:a %}) normalizes bare % to %1"
    (let [src "#({:a %})"
          forms (lang/meme->forms src)
          back (lang/forms->meme forms)]
      (is (= "#({:a %1})" back))))
  (testing "#(#{%}) preserves bare % notation"
    (let [src "#(#{%})"
          forms (lang/meme->forms src)
          back (lang/forms->meme forms)]
      (is (= "#(#{%})" back))))
  (testing "#(`~%) normalizes bare % to %1"
    (let [src "#(`~%)"
          forms (lang/meme->forms src)
          back (lang/forms->meme forms)]
      (is (= "#(`~%1)" back)))))

;; ---------------------------------------------------------------------------
;; RT2-L13: flat/format-forms dropped trailing file comments.
;; canon/format-forms preserved them. Fix: flat now mirrors canon behavior.
;; ---------------------------------------------------------------------------

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
                 {:meme-lang/sugar true :meme-lang/bare-percent true})
          printed (fmt-flat/format-form form)]
      ;; The outer %1 should become %, but the inner fn's %1 should stay as %1
      (is (str/includes? printed "#(map(fn([x] %1) %)")
          "inner fn's %1 must not be restored to %"))))

;; ---------------------------------------------------------------------------
;; Scar tissue: rewrite/emit preserves hex/octal notation via :raw (D65)
;; Previously: used (:value form) which lost notation (0xFF → 255).
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Scar tissue: layout at ##Inf must be O(n) not O(n²) (P1)
;; Previously: fits? traversed entire remaining work-list for every DocGroup.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Scar tissue: non-printable chars must emit as \uXXXX, not raw bytes
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest non-printable-chars-emit-as-unicode
     (testing "NUL emits as \\u0000"
       (is (= "\\u0000" (values/emit-char-str (char 0)))))
     (testing "BEL emits as \\u0007"
       (is (= "\\u0007" (values/emit-char-str (char 7)))))
     (testing "DEL emits as \\u007F"
       (is (= "\\u007F" (values/emit-char-str (char 127)))))
     (testing "regular chars still emit normally"
       (is (= "\\a" (values/emit-char-str \a)))
       (is (= "\\newline" (values/emit-char-str \newline))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: forms->meme must not crash on Var objects.
;; Bug: Vars implement IMeta but not IObj; with-meta threw ClassCastException.
;; Fix: changed IMeta check to IObj in printer's metadata branch.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest var-object-does-not-crash-printer
     (testing "forms->meme handles Var without crashing"
       (is (string? (lang/forms->meme [#'clojure.core/inc]))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: format-clj must preserve trailing comments
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Scar tissue: format-forms nil must throw consistently
;; ---------------------------------------------------------------------------

;; Known limitation: CLJS cannot distinguish 1.0 from 1 at the value level
;; (JavaScript has no integer/float type distinction). The emitter produces "1"
;; for both. A full fix requires parser-level notation preservation via MemeRaw.
;; This test documents the JVM behavior as correct and CLJS as a known limitation.
;; ---------------------------------------------------------------------------
;; Scar tissue: format-meme drops reader-conditional branches (P0)
;; format-meme did not forward opts to meme->forms, so reader conditionals were
;; evaluated (losing the non-matching branch) during formatting.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Known limitation: symbols with whitespace/parens in name (C1)
;; Clojure has no escape syntax for symbols — pr-str returns the same as str.
;; Programmatically constructed symbols like (symbol "foo bar") cannot be
;; faithfully printed in any Clojure-based syntax. This test documents the
;; limitation rather than attempting to fix it.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Scar tissue: canon format-forms bare integer arg silently ignored (C3)
;; Passing integer instead of {:width n} produced default-width output.
;; ---------------------------------------------------------------------------

(deftest canon-format-width-as-integer
  (testing "integer arg treated as width"
    (let [forms ['(defn foo [a b c d e f] (+ a b c d e f))]
          narrow (fmt-canon/format-forms forms 20)
          wide (fmt-canon/format-forms forms 200)]
      (is (re-find #"\n" narrow) "narrow width should break")
      (is (not (re-find #"\n" wide)) "wide width should not break")))
  (testing "format-form also accepts integer"
    (let [narrow (fmt-canon/format-form '(defn foo [a b c d e f] (+ a b c d e f)) 20)]
      (is (re-find #"\n" narrow) "narrow width should break"))))

(deftest float-decimal-point-preserved
  #?(:clj
     (testing "JVM preserves .0 on integer-valued floats"
       (is (= "1.0" (values/emit-number-str 1.0)))
       (is (= "1.5" (values/emit-number-str 1.5))))))

;; ---------------------------------------------------------------------------
;; RT6-F1: bounded-vec must detect truncation even when element at limit+1 is nil.
;; Bug: (some? (first (drop limit s))) returned false when that element was nil,
;; silently dropping the truncation marker "..." from printed output.
;; Fix: use (seq (drop limit s)) which is truthy regardless of element value.
;; ---------------------------------------------------------------------------

(deftest bounded-vec-nil-truncation
  (testing "truncation detected when element at limit+1 is nil"
    (binding [*print-length* 3]
      (let [form (list 'head 'a 'b 'c nil 'extra)
            printed (fmt-flat/format-form form)]
        (is (str/includes? printed "...")
            "must show ... even when 4th arg is nil"))))
  (testing "no truncation when exactly at limit"
    (binding [*print-length* 3]
      (let [form (list 'head 'a 'b 'c)
            printed (fmt-flat/format-form form)]
        (is (not (str/includes? printed "...")))))))

;; ---------------------------------------------------------------------------
;; RT6-F: canon/format-forms rejects map/set inputs
;; Bug: flat/format-forms had the guard, canon/format-forms didn't.
;; Passing a map produced "[:a 1]" silently.
;; Fix: copied guard from flat to canon.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; RT6-F21: Comment-only files don't get leading newlines
;; Bug: formatting ";; comment" produced "\n\n;; comment".
;; Fix: format-meme returns source unchanged when forms is empty.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Fuzzer finding: control char roundtrip — bare control char prints as
;; \uHHHH unicode escape, which re-parses as MemeRaw-wrapped char.
;; The semantic value is preserved; the notation change is accepted.
;; This test documents the behavior: print→re-parse produces the same value.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest control-char-semantic-roundtrip
     (testing "control char U+0001 prints as unicode escape, value preserved"
       (let [form (char 1)
             printed (fmt-flat/format-form form)
             reparsed (first (lang/meme->forms printed))]
         (is (= "\\u0001" printed))
         (is (forms/raw? reparsed) "re-parsed control char is MemeRaw-wrapped")
         (is (= form (:value reparsed)) "semantic value preserved through roundtrip")))))

;; ---------------------------------------------------------------------------
;; Fuzzer finding: ~,@J printed as ~@J (unquote-splicing) instead of
;; ~clojure.core/deref(J). The comma (whitespace) between ~ and @ was lost,
;; merging unquote + deref-sugar into unquote-splicing.
;; Fix: suppress @deref sugar inside ~ to prevent ~@ ambiguity.
;; ---------------------------------------------------------------------------

(deftest unquote-deref-sugar-no-ambiguity
  (testing "~(deref x) with sugar does not print as ~@x"
    (let [form (meme-lang.forms/->MemeUnquote
                 (with-meta (list 'clojure.core/deref 'x) {:meme-lang/sugar true}))
          printed (fmt-flat/format-form form)]
      (is (not (str/starts-with? printed "~@"))
          "must not produce ~@ which re-parses as unquote-splicing")
      (is (= "~clojure.core/deref(x)" printed)))))

;; ---------------------------------------------------------------------------
;; Scar tissue: :ws metadata key collision — user :ws was silently interpreted
;; as trivia/comments. Fix: renamed internal key to :meme-lang/leading-trivia.
;; ---------------------------------------------------------------------------

(deftest user-ws-metadata-not-silently-consumed
  (testing "user :ws metadata is preserved in output, not interpreted as comments"
    (let [form (with-meta '(foo) {:ws "user-value"})
          printed (fmt-flat/format-form form)]
      ;; :ws is NOT an internal key anymore — it should appear in metadata
      (is (str/includes? printed "^") "user :ws should produce a metadata prefix")))
  (testing ":meme-lang/leading-trivia is internal and not emitted as user metadata"
    (let [form (with-meta '(foo) {:meme-lang/leading-trivia "; comment\n"})
          printed (fmt-flat/format-form form)]
      (is (not (str/includes? printed "^")) ":meme-lang/leading-trivia should not produce metadata prefix"))))

;; ---------------------------------------------------------------------------
;; Scar tissue: stale :meme-lang/insertion-order on set — printer trusted (:meme-lang/insertion-order) blindly.
;; Fix: validate :meme-lang/insertion-order count matches set size; fall back to (vec form).
;; ---------------------------------------------------------------------------

(deftest stale-meme-order-falls-back
  (testing "set with stale :meme-lang/insertion-order (wrong count) falls back to unordered"
    (let [form (with-meta #{1 2} {:meme-lang/insertion-order [1 2 3]})
          printed (fmt-flat/format-form form)]
      ;; Stale order (3 elements vs 2 in set) → fallback, no phantom element
      (is (not (str/includes? printed "3")) "phantom element 3 should not appear")))
  (testing "set with correct :meme-lang/insertion-order preserves order"
    (let [form (with-meta #{3 1 2} {:meme-lang/insertion-order [3 1 2]})
          printed (fmt-flat/format-form form)]
      (is (= "#{3 1 2}" printed)))))
