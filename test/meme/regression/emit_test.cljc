(ns meme.regression.emit-test
  "Scar tissue: printer and formatter regression tests.
   Every test here prevents a specific bug from recurring."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [m1clj-lang.api :as lang]
            [m1clj-lang.formatter.flat :as fmt-flat]
            [m1clj-lang.formatter.canon :as fmt-canon]
            [meme.tools.clj.values :as values]
            [meme.tools.clj.forms :as forms]
            [meme.tools.clj.stages :as stages]
            [m1clj-lang.grammar :as grammar]))

;; ---------------------------------------------------------------------------
;; Scar tissue: quoted lists print correctly in both sugar and call modes.
;; Sugar mode (:m1clj/sugar true) emits 'f(x y).
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
  (testing "fn with surplus % params in the form path prints as fn(...)"
    (let [form '(fn [%1 %2] (inc %1))
          printed (fmt-flat/format-form form)]
      (is (not (str/starts-with? printed "#("))
          "surplus params must not emit #() shorthand")
      (is (= form (first (lang/m1clj->forms printed)))
          "roundtrip must preserve arity")))
  (testing "form path always emits (fn ...) — #() sugar requires AST"
    (let [form '(fn [%1] (inc %1))
          printed (fmt-flat/format-form form)]
      (is (not (str/starts-with? printed "#("))
          "without an AST node, fn always emits fn() form")
      (is (= form (first (lang/m1clj->forms printed)))
          "roundtrip must preserve form"))))

;; ---------------------------------------------------------------------------
;; Bug: max-percent-n recursed into nested fn bodies, so
;; (fn [%1] (fn [%1] %1)) was printed as #(#(%1)) — the outer fn
;; incorrectly detected %1 from the inner fn's body and used #() shorthand.
;; Reader re-read this as (fn [] (fn [%1] %1)) — arity lost.
;; Fix: skip (fn ...) forms in max-percent-n, mirroring find-percent-params.
;; ---------------------------------------------------------------------------

(deftest anon-fn-nested-fn-not-counted
  (testing "(fn [%1] (fn [%1] %1)) — form path emits fn()"
    (let [form '(fn [%1] (fn [%1] %1))
          printed (fmt-flat/format-form form)]
      (is (not (str/starts-with? printed "#("))
          "form path always emits fn() form")
      (is (= form (first (lang/m1clj->forms printed)))
          "roundtrip must preserve outer arity")))
  (testing "source-driven format-m1clj reproduces #() from #() input"
    (is (= "#(inc(%1))" (lang/format-m1clj "#(inc(%1))" nil)))))

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
         (is (> (count lines) 1) "map should be multi-line")
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
;; Bug: comment lines from :m1clj/leading-trivia metadata emitted at column 0 inside nested
;; multi-line blocks.
;; ---------------------------------------------------------------------------

(deftest canon-comment-indentation-in-nested-blocks
  (testing "single comment indented to match body"
    (let [src "foo(bar()\n  ; single\n  baz())"
          result (lang/format-m1clj src {:width 10})
          lines (str/split-lines result)]
      (is (= "  ; single" (nth lines 2)))))
  (testing "multiple comment lines all indented"
    (let [src "foo(bar()\n  ; line 1\n  ; line 2\n  baz())"
          result (lang/format-m1clj src {:width 10})
          lines (str/split-lines result)]
      (is (= "  ; line 1" (nth lines 2)))
      (is (= "  ; line 2" (nth lines 3)))))
  (testing "original whitespace stripped and re-indented"
    (let [src "foo(bar()\n    ; deep\n  baz())"
          result (lang/format-m1clj src {:width 10})
          lines (str/split-lines result)]
      (is (= "  ; deep" (nth lines 2)))))
  (testing "top-level comments unchanged"
    (let [src "; top\nfoo(x)"
          result (lang/format-m1clj src nil)]
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
             forms (lang/m1clj->forms src)
             formatted (fmt-canon/format-forms forms {:width 10})
             re-read (lang/m1clj->forms formatted)]
         (is (= forms re-read))))))

;; ---------------------------------------------------------------------------
;; Bug: canon formatter lost reader-sugar notation (@, #', ')
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest canon-preserves-deref-sugar
     (testing "@atom sugar preserved through source-driven canon (AST path)"
       (let [result (lang/format-m1clj "@my-very-long-atom-name" {:width 10})]
         (is (str/starts-with? result "@"))
         (is (not (str/includes? result "clojure.core/deref")))))
     (testing "clojure.core/deref(x) call form prints structurally in form path"
       (let [form (list 'clojure.core/deref 'my-atom)
             result (fmt-canon/format-form form {:width 10})]
         (is (str/includes? result "clojure.core/deref("))))))

#?(:clj
   (deftest canon-preserves-var-sugar
     (testing "#'sym sugar preserved through source-driven canon (AST path)"
       (let [result (lang/format-m1clj "#'some.ns/my-var" {:width 5})]
         (is (str/starts-with? result "#'"))
         (is (not (str/includes? result "var(")))))
     (testing "var(x) call form prints structurally in form path"
       (let [form (list 'var 'some.ns/my-var)
             result (fmt-canon/format-form form {:width 5})]
         (is (str/includes? result "var("))))))

#?(:clj
   (deftest canon-preserves-quote-sugar
     (testing "'sym sugar preserved through source-driven canon (AST path)"
       (let [result (lang/format-m1clj "'my-long-symbol-name" {:width 5})]
         (is (str/starts-with? result "'"))
         (is (not (str/includes? result "quote(")))))
     (testing "(quote x) form-path renders structurally as a call"
       (let [form (list 'quote 'my-long-symbol-name)
             result (fmt-canon/format-form form {:width 5})]
         (is (str/starts-with? result "quote("))))))

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
;; Scar tissue: CljRaw in canon formatter was rendered as {:value N :raw "..."}.
;; ---------------------------------------------------------------------------

(deftest canon-meme-raw-renders-source-notation
  #?(:clj
     (testing "hex literal in canon formatter renders as 0xFF, not {:value 255 :raw ...}"
       (let [forms (lang/m1clj->forms "let([x 0xFF] x)")
             pp (lang/format-m1clj-forms forms)]
         (is (str/includes? pp "0xFF") "formatter must preserve hex notation")
         (is (not (str/includes? pp ":value")) "formatter must not leak CljRaw fields"))))
  (testing "scientific notation in canon formatter"
    (let [forms (lang/m1clj->forms "def(y 1e5)")
          pp (lang/format-m1clj-forms forms)]
      (is (str/includes? pp "1e5") "formatter must preserve scientific notation"))))

;; ---------------------------------------------------------------------------
;; Scar tissue: canon formatter multi-line paths lost notation-preserving metadata.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest canon-preserves-anon-fn-sugar
     (testing "#() sugar preserved through source-driven canon (AST path)"
       (let [result (lang/format-m1clj "#(long-function(a b c d e f))" {:width 20})]
         (is (str/starts-with? result "#("))
         (is (not (str/includes? result "fn(")))))
     (testing "#() with short body stays flat — bare % preserved through AST"
       (let [result (lang/format-m1clj "#(inc(%))" {:width 80})]
         (is (= "#(inc(%))" result))))))

#?(:clj
   (deftest canon-preserves-namespaced-map
     (testing "#:ns{} prefix preserved through source-driven canon"
       (let [result (lang/format-m1clj "#:user{:name \"x\" :age 42 :email \"long@example.com\"}"
                                       {:width 20})]
         (is (str/starts-with? result "#:user{"))
         (is (not (re-find #"^\{" result)))))
     (testing "#::ns{} auto-resolve preserved through source-driven canon"
       (let [result (lang/format-m1clj "#::foo{:a 1 :b 2 :c \"a-very-long-value-here\"}"
                                       {:width 20})]
         (is (str/starts-with? result "#::foo{"))))))

#?(:clj
   (deftest canon-preserves-meta-chain
     (testing "^:a ^:b chain preserved through source-driven canon (AST path)"
       (let [result (lang/format-m1clj "^:private ^:deprecated defn(foo [x] x)" {:width 30})]
         (is (str/starts-with? result "^:private ^:deprecated"))
         (is (not (str/includes? result "^{")))))
     (testing "single ^:key still works"
       (let [result (lang/format-m1clj "^:private defn(foo [x] x)" {:width 20})]
         (is (str/starts-with? result "^:private"))))))

;; ---------------------------------------------------------------------------
;; Bug: regex patterns containing " printed unescaped.
;; ---------------------------------------------------------------------------

(deftest regex-quote-in-pattern-escaped
  (testing "regex from meme source with \\\" roundtrips"
    (let [forms (lang/m1clj->forms "#\"a\\\"b\"")
          printed (fmt-flat/format-forms forms)
          forms2 (lang/m1clj->forms printed)]
      (is (= #?(:clj (.pattern ^java.util.regex.Pattern (first forms))
                :cljs (.-source (first forms)))
             #?(:clj (.pattern ^java.util.regex.Pattern (first forms2))
                :cljs (.-source (first forms2)))))))
  #?(:clj
     (testing "programmatic regex with bare \" produces parseable output"
       (let [r (re-pattern "a\"b")
             printed (fmt-flat/format-form r)]
         (is (re-find #"^#\"" printed))
         (is (some? (lang/m1clj->forms printed)))
         (is (= (re-find r "a\"b")
                (re-find (first (lang/m1clj->forms printed)) "a\"b"))))))
  (testing "no double-escaping of already-escaped quotes"
    (let [forms (lang/m1clj->forms "#\"x\\\"y\"")
          printed (fmt-flat/format-forms forms)
          forms2 (lang/m1clj->forms printed)]
      (is (= #?(:clj (.pattern ^java.util.regex.Pattern (first forms))
                :cljs (.-source (first forms)))
             #?(:clj (.pattern ^java.util.regex.Pattern (first forms2))
                :cljs (.-source (first forms2)))))))
  #?(:clj
     (testing "even backslashes before quote: \\\\ + \" not misidentified as escaped quote"
       (let [r (re-pattern "a\\\\\"b")
             printed (fmt-flat/format-form r)
             forms (lang/m1clj->forms printed)]
         (is (some? forms) "printed regex should parse without error")
         (is (= (re-find r "a\\\"b")
                (re-find (first forms) "a\\\"b"))
             "regex behavior should be preserved")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: regex roundtrip matrix — an external audit suggested
;; emit-regex-str was mis-escaping backslashes.  It was not; the existing
;; code correctly roundtrips the full range of real-world regex shapes
;; (escapes, character classes, alternation, anchors, unicode).  This
;; test locks the matrix in so a future refactor of emit-regex-str
;; cannot silently break any of these cases.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest regex-roundtrip-matrix
     (doseq [[label p] [["escape: \\n (newline)"      #"\n"]
                        ["escape: \\d (digit class)"  #"\d+"]
                        ["literal: \\\\n (bs + n)"    #"\\n"]
                        ["four backslashes"           #"\\\\"]
                        ["escaped quote: \\\""        #"a\"b"]
                        ["char class"                 #"[a-z]+"]
                        ["alternation"                #"a|b"]
                        ["anchors"                    #"^foo$"]
                        ["empty pattern"              #""]
                        ["unicode escape"             #"\u00A0"]
                        ["named group"                #"(?<name>\w+)"]
                        ["non-capturing group"        #"(?:a|b)"]]]
       (testing label
         (let [printed (fmt-flat/format-form p)
               forms (lang/m1clj->forms printed)
               reparsed (first forms)]
           (is (some? reparsed) (str "printed output must parse: " printed))
           (is (instance? java.util.regex.Pattern reparsed)
               "reparsed form must be a Pattern")
           (is (= (.pattern ^java.util.regex.Pattern p)
                  (.pattern ^java.util.regex.Pattern reparsed))
               "pattern source must be identical after roundtrip"))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: #() shorthand in :clj mode wrapped the body in extra parens.
;; (fn [%1] (+ %1 1)) with :m1clj/sugar printed as #((+ %1 1)) instead of
;; #(+ %1 1). In Clojure, #((f)) calls the result of (f) — different semantics.
;; Fix: unwrap the body list in :clj mode.
;; ---------------------------------------------------------------------------

(deftest anon-fn-clj-mode-no-double-parens
  (testing "single-arg #() through m1clj->clj (lossless via AST)"
    (is (= "#(+ %1 1)" (lang/m1clj->clj "#(+(%1 1))"))
        "#() body must not get extra parens in :clj mode"))
  (testing "multi-arg #() through m1clj->clj"
    (is (= "#(+ %1 %2)" (lang/m1clj->clj "#(+(%1 %2))"))
        "#() with multiple args must not get extra parens")))

;; ---------------------------------------------------------------------------
;; Scar tissue: #() with non-list body in :clj mode
;; ---------------------------------------------------------------------------
;; (fn [] 42) was emitted as #(42) in :clj mode, but in Clojure #(42) means
;; (fn [] (42)) — calling 42 as a function. Semantic corruption during
;; m1clj->clj conversion. Fix: reject #() shorthand for non-list bodies in
;; :clj mode; fall through to (fn ...) form.
;; ---------------------------------------------------------------------------

(deftest anon-fn-clj-mode-non-list-body
  (testing "m1clj->clj for #(42) — non-list body falls through to (fn ...)"
    (is (= "(fn [] 42)" (lang/m1clj->clj "#(42)"))))
  (testing "m1clj->clj for #(identity) — non-list body falls through to (fn ...)"
    (is (= "(fn [] identity)" (lang/m1clj->clj "#(identity)"))))
  (testing "m1clj->clj for #(:foo) — non-list body falls through to (fn ...)"
    (is (= "(fn [] :foo)" (lang/m1clj->clj "#(:foo)"))))
  (testing "m1clj->clj preserves #() shorthand for list body"
    (is (= "#(+ %1 1)" (lang/m1clj->clj "#(+(%1 1))")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: metadata stripping loses notation metadata
;; ---------------------------------------------------------------------------
;; When a form has both user metadata (^:foo) and internal notation metadata
;; (:m1clj/namespace-prefix, :m1clj/insertion-order, :m1clj/sugar), the printer stripped ALL metadata
;; via (with-meta form nil). This lost the notation keys, causing #:ns{} maps
;; to print with fully-qualified keys, sets to lose insertion order, and
;; quote sugar to be lost.
;; Fix: preserve forms/notation-meta-keys when stripping user metadata.
;; ---------------------------------------------------------------------------

(deftest metadata-stripping-preserves-notation
  (testing "^:validated #:user{} preserves namespaced-map notation through AST formatter"
    (let [printed (lang/format-m1clj "^:validated #:user{:name \"x\"}" nil)]
      (is (str/includes? printed "#:user{")
          "namespaced-map notation must survive metadata stripping")))
  (testing "quote sugar preserved with user metadata through AST formatter"
    (let [printed (lang/format-m1clj "^:foo 'x" nil)]
      (is (str/includes? printed "'x")
          "quote sugar must survive metadata stripping"))))

;; ---------------------------------------------------------------------------
;; F7: bare % was normalized to %1, losing the user's notation choice.
;; Bug: normalize-bare-percent converted % to %1 in the body for eval
;; correctness, but the printer always emitted %1 even when the user wrote %.
;; Fix: reader tags :m1clj/bare-percent in metadata; printer restores % from %1.
;; ---------------------------------------------------------------------------

;; The AST tier preserves bare `%` as written (sugar on the AST node).
;; Normalization to `%1` happens only in the eval-form path (where it's
;; needed for arity correctness). format-m1clj is the source-faithful
;; printer and therefore reproduces `%`.
(deftest bare-percent-notation-preserved
  (testing "#(inc(%)) round-trips preserving the user's bare %"
    (is (= "#(inc(%))" (lang/format-m1clj "#(inc(%))" nil))))
  (testing "#(+(%1 %2)) roundtrips preserving explicit %1"
    (is (= "#(+(%1 %2))" (lang/format-m1clj "#(+(%1 %2))" nil))))
  (testing "#(+(% %2)) preserves the mixed % / %2 spelling"
    (is (= "#(+(% %2))" (lang/format-m1clj "#(+(% %2))" nil)))))

;; ---------------------------------------------------------------------------
;; Bare % inside a #() body is uniformly normalized to %1 on roundtrip, in
;; agreement with bare-percent-notation-normalized and roundtrip-anon-fn-
;; sugar-preserved.
;;
;; Historical note: this test previously asserted that #(#{%}) round-tripped
;; verbatim while maps and syntax-quote bodies degraded to %1. That asymmetry
;; was accidental — walk-anon-fn-body's set branch rebuilt the set with stale
;; :m1clj/insertion-order metadata still pointing at the source `%`, which the
;; printer then emitted instead of the normalized `%1`. Once the walker began
;; refreshing set order via walk-meme-set, the set case aligned with the
;; map/syntax-quote behavior.
;; ---------------------------------------------------------------------------

(deftest bare-percent-in-nested-containers-preserved
  (testing "#({:a %}) preserves bare % through AST formatter"
    (is (= "#({:a %})" (lang/format-m1clj "#({:a %})" nil))))
  (testing "#(#{%}) preserves bare %"
    (is (= "#(#{%})" (lang/format-m1clj "#(#{%})" nil))))
  (testing "#(`~%) preserves bare %"
    (is (= "#(`~%)" (lang/format-m1clj "#(`~%)" nil)))))

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

;; Note: bare-% restoration was tied to the deleted :m1clj/bare-percent
;; metadata.  The AST tier captures % vs %1 in the source-driven path; the
;; eval-form path normalizes everything to %1 (correct for eval).

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
;; Scar tissue: forms->m1clj must not crash on Var objects.
;; Bug: Vars implement IMeta but not IObj; with-meta threw ClassCastException.
;; Fix: changed IMeta check to IObj in printer's metadata branch.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest var-object-does-not-crash-printer
     (testing "forms->m1clj handles Var without crashing"
       (is (string? (lang/forms->m1clj [#'clojure.core/inc]))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: format-clj must preserve trailing comments
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Scar tissue: format-forms nil must throw consistently
;; ---------------------------------------------------------------------------

;; Known limitation: CLJS cannot distinguish 1.0 from 1 at the value level
;; (JavaScript has no integer/float type distinction). The emitter produces "1"
;; for both. A full fix requires parser-level notation preservation via CljRaw.
;; This test documents the JVM behavior as correct and CLJS as a known limitation.
;; ---------------------------------------------------------------------------
;; Scar tissue: format-m1clj drops reader-conditional branches (P0)
;; format-m1clj did not forward opts to m1clj->forms, so reader conditionals were
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
;; Fix: format-m1clj returns source unchanged when forms is empty.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Fuzzer finding: control char roundtrip — bare control char prints as
;; \uHHHH unicode escape, which re-parses as CljRaw-wrapped char.
;; The semantic value is preserved; the notation change is accepted.
;; This test documents the behavior: print→re-parse produces the same value.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest control-char-semantic-roundtrip
     (testing "control char U+0001 prints as unicode escape, value preserved"
       (let [form (char 1)
             printed (fmt-flat/format-form form)
             reparsed (first (lang/m1clj->forms printed))]
         (is (= "\\u0001" printed))
         (is (forms/raw? reparsed) "re-parsed control char is CljRaw-wrapped")
         (is (= form (:value reparsed)) "semantic value preserved through roundtrip")))))

;; ---------------------------------------------------------------------------
;; Fuzzer finding: ~,@J printed as ~@J (unquote-splicing) instead of
;; ~clojure.core/deref(J). The comma (whitespace) between ~ and @ was lost,
;; merging unquote + deref-sugar into unquote-splicing.
;; Fix: suppress @deref sugar inside ~ to prevent ~@ ambiguity.
;; ---------------------------------------------------------------------------

(deftest unquote-deref-sugar-no-ambiguity
  (testing "`~,@x` (unquote separated by whitespace from @-deref) preserves
            the comma in the AST path so output isn't parsed as ~@"
    (let [printed (lang/format-m1clj "`~,@x" nil)]
      (is (not (str/includes? printed "~@x"))
          "must not collapse to ~@x which re-parses as unquote-splicing"))))

;; ---------------------------------------------------------------------------
;; Scar tissue: :ws metadata key collision — user :ws was silently interpreted
;; as trivia/comments. Fix: renamed internal key to :m1clj/leading-trivia.
;; ---------------------------------------------------------------------------

(deftest user-ws-metadata-not-silently-consumed
  (testing "user :ws metadata is preserved in output (no longer an internal key)"
    (let [form (with-meta '(foo) {:ws "user-value"})
          printed (fmt-flat/format-form form)]
      (is (str/includes? printed "^") "user :ws should produce a metadata prefix"))))

;; ---------------------------------------------------------------------------
;; Scar tissue: stale :m1clj/insertion-order on set — printer trusted (:m1clj/insertion-order) blindly.
;; Fix: validate :m1clj/insertion-order count matches set size; fall back to (vec form).
;; ---------------------------------------------------------------------------

;; Note: set source-order tracking was tied to :m1clj/insertion-order, which
;; only existed on forms.  After A5 it lives on `CljSet` AST nodes via the
;; ordered `:children` vec; tooling that needs source order consumes the AST.
;; The form path renders sets in hash order — acceptable lossy behavior.

(deftest source-driven-set-preserves-source-order
  (testing "format-m1clj reproduces written set order via the AST tier"
    (is (= "#{:a :b :c :d}" (lang/format-m1clj "#{:a :b :c :d}" nil)))))

(deftest rc-splice-into-set-evaluates-correctly
  (testing "#?@ splice produces the right set elements (order via AST path)"
    (let [[form] (:forms (stages/step-evaluate-reader-conditionals
                           (assoc (stages/run "#{:a #?@(:clj [:b :c] :cljs []) :d}"
                                              {:grammar grammar/grammar})
                                  :opts {:platform :clj})))]
      (is (= #{:a :b :c :d} form))))
  (testing "#?@ that contributes zero items leaves the surrounding set intact"
    (let [[form] (:forms (stages/step-evaluate-reader-conditionals
                           (assoc (stages/run "#{:a #?@(:cljs [:x]) :b}"
                                              {:grammar grammar/grammar})
                                  :opts {:platform :clj})))]
      (is (= #{:a :b} form)))))

;; ---------------------------------------------------------------------------
;; Scar tissue: outer expand-syntax-quotes walker rebuilt sets without
;; refreshing :m1clj/insertion-order. After walking, set contents and the
;; stale order vector diverged, and the printer iterated the order vector,
;; so a syntax-quote inside a set printed as the unexpanded `\`x` instead
;; of the expanded (quote x). Sibling of 5f7d0fb (which fixed walk-rc).
;; Fix: walk in :m1clj/insertion-order when present, then rebuild it from
;; walked elements so it stays consistent with the new set's contents.
;; ---------------------------------------------------------------------------

(deftest sq-inside-set-expands-on-forms->clj
  (testing "syntax-quote inside container expands to (quote ...) in forms->clj
            output (the form path renders structurally — no ' sugar)"
    (is (= "(quote x)" (lang/forms->clj (lang/m1clj->forms "`x"))))
    (is (= "[(quote x) 2]" (lang/forms->clj (lang/m1clj->forms "[`x 2]"))))
    (is (re-find #"#\{.*\(quote x\).*\}"
                 (lang/forms->clj (lang/m1clj->forms "#{`x 2}")))))
  #?(:clj
     (testing "CljRaw inside a set unwraps to its value, matching every other container"
       ;; Set hash order is not deterministic in the form path; just check
       ;; semantic equality of the contained ints.
       (let [out (lang/forms->clj (lang/m1clj->forms "#{0xFF 2 3}"))]
         (is (re-find #"#\{[0-9 ]*255[0-9 ]*\}" out))
         (is (re-find #"\b2\b" out))
         (is (re-find #"\b3\b" out)))
       (is (= "[255 2 3]"  (lang/forms->clj (lang/m1clj->forms "[0xFF 2 3]")))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: interior comments in maps, sets, call args, anon-fn bodies,
;; and reader conditionals must survive to the formatter output.
;; Bug: `read-children` in cst-reader stripped leading trivia on children,
;; so only vectors (which used `read-children-with-ws`) preserved comments.
;; Fix: merged the two — `read-children` now always preserves trivia on
;; metadatable children. Non-metadatable atoms (keywords/numbers/strings as
;; map keys) still lose their trivia; they cannot carry Clojure metadata.
;; ---------------------------------------------------------------------------

(deftest interior-comments-preserved-in-collections
  (testing "comment inside a map (before a symbol key) survives via AST formatter"
    (let [out (lang/format-m1clj "{;; section\n 'a 1}" {:width 80})]
      (is (re-find #";; section" out)
          (str "expected comment in output, got: " (pr-str out)))))
  (testing "comment inside a set (before a symbol) survives via AST formatter"
    (let [out (lang/format-m1clj "#{;; odds\n 'a 'b}" {:width 80})]
      (is (re-find #";; odds" out)
          (str "expected comment in output, got: " (pr-str out)))))
  (testing "comment inside a call (before an arg) survives via AST formatter"
    (let [out (lang/format-m1clj "f(;; head\n 'x)" {:width 80})]
      (is (re-find #";; head" out)
          (str "expected comment in output, got: " (pr-str out))))))
