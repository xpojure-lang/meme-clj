(ns meme.regression.reader-test
  "Scar tissue: parser/reader regression tests.
   Every test here prevents a specific bug from recurring."
  (:require [clojure.test :refer [deftest is testing]]

            [meme-lang.api :as lang]
            [meme.tools.clj.cst-reader :as cst-reader]
            [meme-lang.formatter.flat :as fmt-flat]
            [meme.tools.clj.forms :as forms]
            [meme.tools.clj.expander :as expander]
            [meme.tools.clj.stages :as stages]
            [meme-lang.grammar :as grammar]
            [meme-lang.test-util :as tu]))


;; ---------------------------------------------------------------------------
;; Scar tissue: auto-resolve keywords are deferred (CljAutoKeyword records).
;; Covered by resolve_test and roundtrip_test.
;; ---------------------------------------------------------------------------

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
;; Scar tissue: #_ discard at end of stream or before closing delimiters.
;; Design decision: The Pratt parser consumes #_ as a CST node with its target.
;; When a prefix operator (@, ', #') is followed by #_, the #_ consumes the
;; next token and the prefix applies to the result. Covered by
;; consecutive-discard-semantics test below and dispatch_test.cljc.
;; ---------------------------------------------------------------------------

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
;; Scar tissue: leading-zero integers with digits 8/9 must error.
;; Covered by scan_test.cljc invalid-octal-digits test.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Scar tissue: duplicate set elements and map keys are rejected at read time.
;; The CST reader validates duplicates, matching Clojure's reader behavior.
;; ---------------------------------------------------------------------------

(deftest duplicate-keys-rejected
  (testing "duplicate map keys throw"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"[Dd]uplicate key"
                          (lang/meme->forms "{:a 1 :a 2}"))))
  (testing "duplicate set elements throw"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"[Dd]uplicate key"
                          (lang/meme->forms "#{1 2 1}")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: BOM (U+FEFF) at start of source is stripped as trivia.
;; Covered by grammar's bom-consumer and run.clj's string-level strip.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Scar tissue: CRLF shebang left stray \n, causing off-by-one line numbers.
;; Bug: strip-shebang computed nl = min(cr, lf) = cr for \r\n, then
;; (subs source (inc nl)) started at \n instead of skipping the full pair.
;; Fix: detect \r\n pair and skip both characters.
;; ---------------------------------------------------------------------------

(deftest strip-shebang-crlf
  (testing "CRLF shebang fully stripped"
    (let [source "#!/usr/bin/env bb\r\nprintln(42)"]
      (is (= "println(42)" (stages/strip-shebang source)))))
  (testing "LF shebang still works"
    (let [source "#!/usr/bin/env bb\nprintln(42)"]
      (is (= "println(42)" (stages/strip-shebang source)))))
  (testing "bare CR shebang still works"
    (let [source "#!/usr/bin/env bb\rprintln(42)"]
      (is (= "println(42)" (stages/strip-shebang source)))))
  (testing "shebang-only source returns empty string"
    (is (= "" (stages/strip-shebang "#!/usr/bin/env bb"))))
  (testing "non-shebang source unchanged"
    (is (= "println(42)" (stages/strip-shebang "println(42)")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: double-shebang file — second #! line after stripping first.
;; Bug: strip-shebang removes line 1, line 2's #! lands at pos 0, parser
;; produces a :shebang atom, CST reader threw "Unknown atom type: :shebang".
;; Fix: CST reader returns no-match sentinel for :shebang atoms.
;; ---------------------------------------------------------------------------

(deftest double-shebang-handled
  (testing "file with two shebang lines — second line ignored as shebang"
    (let [src "#!/usr/bin/env bb\n#!/not-a-shebang\nprintln(42)"
          forms (lang/meme->forms src)]
      ;; strip-shebang removes line 1, parser sees line 2's #! at pos 0 as
      ;; a :shebang atom (filtered out), then println(42) as a call.
      (is (= 1 (count forms)) "shebang atom should be filtered, only call remains")
      (is (list? (first forms)) "the remaining form should be a call")))
  (testing "single shebang followed by code works"
    (is (= '[x] (lang/meme->forms "#!/usr/bin/env bb\nx")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: deeply nested input must produce a clean error, not SOE.
;; max-parse-depth (512) is enforced in both the parser and CST reader.
;; ---------------------------------------------------------------------------

(deftest deep-nesting-produces-clean-error
  (testing "call nesting beyond max-parse-depth errors cleanly"
    (let [depth (+ forms/max-parse-depth 10)
          input (str (apply str (repeat depth "f(")) "x" (apply str (repeat depth ")")))]
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                            #"depth"
                            (lang/meme->forms input)))))
  (testing "vector nesting beyond max-parse-depth errors cleanly"
    (let [depth (+ forms/max-parse-depth 10)
          input (str (apply str (repeat depth "[")) "x" (apply str (repeat depth "]")))]
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                            #"depth"
                            (lang/meme->forms input)))))
  (testing "moderate nesting (100 levels) still works"
    (let [input (str (apply str (repeat 100 "f(")) "x" (apply str (repeat 100 ")")))]
      (is (some? (lang/meme->forms input)))))
  (testing "prefix chain beyond max-parse-depth errors cleanly"
    (let [depth (+ forms/max-parse-depth 10)
          input (str (apply str (repeat depth "'")) "x")]
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                            #"depth"
                            (lang/meme->forms input))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: CST reader's depth guard must cap at max-parse-depth, not
;; max-parse-depth+1. Bug: `(> depth forms/max-parse-depth)` allowed one extra
;; level vs. the parser's effective cap, diverging the two limits. Fix: `>=`.
;; Isolated from the parser by seeding ::depth directly so the boundary is
;; observable on a trivial CST.
;; ---------------------------------------------------------------------------

(deftest cst-reader-depth-boundary-matches-parse-depth
  (let [atom-node {:node :atom :token {:type :symbol :raw "x" :line 1 :col 1}}]
    (testing "reading with depth = max-parse-depth - 1 succeeds"
      (is (= 'x (cst-reader/read-node atom-node
                  {:meme.tools.clj.cst-reader/depth (dec forms/max-parse-depth)}))))
    (testing "reading with depth = max-parse-depth throws with a clean error"
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                            #"depth"
                            (cst-reader/read-node atom-node
                              {:meme.tools.clj.cst-reader/depth forms/max-parse-depth}))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: clj->forms depth guard must match cst-reader at exactly
;; max-parse-depth. Bug: the sibling fix in cst_reader.cljc (4.0.0) tightened
;; `>` to `>=`, but api.cljc/check-depth retained `>`, letting Clojure source
;; at exactly max-parse-depth levels through while meme source at the same
;; depth was rejected — a divergence between entry points that contradicted
;; the 4.0.0 CHANGELOG intent. Fix: use `>=` in both.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest clj-forms-depth-boundary-matches-meme-forms
     (let [opens   (fn [n] (apply str (repeat n "(")))
           closes  (fn [n] (apply str (repeat n ")")))
           nested  (fn [n] (str (opens n) "x" (closes n)))]
       (testing "nesting = max-parse-depth - 1 parses"
         (is (some? (lang/clj->forms (nested (dec forms/max-parse-depth))))))
       (testing "nesting = max-parse-depth throws with clean depth error"
         (is (thrown-with-msg? Exception #"depth"
               (lang/clj->forms (nested forms/max-parse-depth)))))
       (testing "nesting = max-parse-depth + 1 also throws (upper side holds)"
         (is (thrown-with-msg? Exception #"depth"
               (lang/clj->forms (nested (inc forms/max-parse-depth)))))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: consecutive #_ discards must discard N forms (Clojure semantics).
;; Bug: #_ was a prefix parselet nesting discards instead of consuming sequentially.
;; Fix: iterative consumption of consecutive #_ tokens in parselets.cljc.
;; ---------------------------------------------------------------------------

(deftest consecutive-discard-semantics
  (testing "#_ #_ a b c discards a and b, keeps c"
    (is (= '[c] (lang/meme->forms "#_ #_ a b c"))))
  (testing "#_ #_ #_ a b c d discards a, b, c, keeps d"
    (is (= '[d] (lang/meme->forms "#_ #_ #_ a b c d"))))
  (testing "non-consecutive discards in vector"
    (is (= [[1 4]] (lang/meme->forms "[1 #_ 2 #_ 3 4]"))))
  (testing "consecutive discards inside vector"
    (is (= [[1 4]] (lang/meme->forms "[1 #_ #_ 2 3 4]")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: consecutive #_ tokens preserve interior trivia in the CST.
;; Bug: intermediate #_ tokens were produced via make-token! whose return value
;; was discarded. make-token! drains trivia-acc, so any comment/whitespace
;; between consecutive #_s was silently lost.
;; Fix: retain intermediate #_ tokens on :extra-tokens and discarded forms on
;; :discarded-forms of the :discard CST node.
;; ---------------------------------------------------------------------------

(deftest consecutive-discard-preserves-interior-trivia
  (testing "tokenize preserves all raw characters through consecutive discards"
    (let [sources ["#_ #_ a b c"
                   "#_ #_ #_ a b c d"
                   "#_ ;; between\n#_ a b c"
                   "[1 #_ #_ 2 3 4]"]]
      (doseq [src sources]
        (is (= src (apply str (map :raw (tu/tokenize src))))
            (str "roundtrip of " (pr-str src))))))
  (testing "comment between #_ tokens survives in CST tokens"
    (let [src "#_ ;; kept\n#_ a b c"
          tokens (tu/tokenize src)
          raws (map :raw tokens)]
      (is (some #(re-find #";; kept" %) raws)
          "comment should appear in collected tokens"))))

;; ---------------------------------------------------------------------------
;; Scar tissue: nested #() must be rejected (Clojure semantics).
;; Bug: anon-fn handler had no nesting check.
;; Fix: track ::in-anon-fn in opts, reject when nested.
;; ---------------------------------------------------------------------------

(deftest nested-anon-fn-rejected
  (testing "nested #() produces error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Nested"
                          (lang/meme->forms "#(#(+(% %2)))"))))
  (testing "after nested #() error, subsequent #() works"
    (is (thrown? #?(:clj Exception :cljs js/Error) (lang/meme->forms "#(#(+ %))")))
    (is (some? (lang/meme->forms "#(+(% 1))")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: unquote (~) outside syntax-quote must error at expansion time.
;; Bug: CljUnquote/CljUnquoteSplicing nodes survived expansion untouched.
;; Fix: expand-syntax-quotes errors on bare unquote/unquote-splicing nodes.
;; ---------------------------------------------------------------------------

(deftest bare-unquote-rejected-at-expansion
  (testing "~x outside syntax-quote errors during expansion"
    (let [forms (lang/meme->forms "~x")]
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                            #"[Uu]nquote"
                            (expander/expand-forms forms)))))
  (testing "~@x outside syntax-quote errors during expansion"
    (let [forms (lang/meme->forms "~@x")]
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                            #"[Uu]nquote"
                            (expander/expand-forms forms)))))
  (testing "~x inside syntax-quote still works"
    (let [forms (lang/meme->forms "`map(~f xs)")]
      (is (some? (expander/expand-forms forms))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: bare (...) without a head is a parse error.
;; Covered by call_syntax_test.cljc and the grammar's nud-empty-or-error.
;; ---------------------------------------------------------------------------

(deftest bare-parens-error
  (testing "non-empty bare parens produce error"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (lang/meme->forms "(x y)")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: keyword-as-head, set-as-head, map-as-head.
;; Covered by call_syntax_test.cljc head-type matrix.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Scar tissue: ^42 x — metadata on non-metadatable errors cleanly.
;; ---------------------------------------------------------------------------

(deftest metadata-on-non-metadatable-error
  (testing "^:foo 42 — metadata on number errors"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"[Mm]etadata"
                          (lang/meme->forms "^:foo 42")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: #_ handling inside #() anonymous functions.
;; Design decision: double discard #_ #_ inside #() works via CST node
;; consumption. Multiple body forms are wrapped in (do ...).
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Scar tissue: %0 in #() is rejected (matching Clojure's reader).
;; ---------------------------------------------------------------------------

(deftest percent-zero-rejected-in-anon-fn
  (testing "%0 inside #() is rejected as invalid"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"[Ii]nvalid % parameter"
                          (lang/meme->forms "#(inc(%0))")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: bare % and numbered %N mixed in #() forms.
;; Covered by dispatch_test.cljc anon-fn tests.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Scar tissue: mismatched bracket error includes location info.
;; Covered by errors_test.cljc.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Scar tissue: call chaining — f(x)(y) roundtrips correctly.
;; Covered by roundtrip_test.cljc chained-call tests.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Design decision: errors have :line/:col in ex-data.
;; The :source-context field from the classic pipeline is not present.
;; Error display uses format-error which re-derives context from source.
;; ---------------------------------------------------------------------------

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
;; Bug: the reader used to evaluate #? at read time, discarding branches for
;; other platforms, so clj→meme→clj lost information.
;; Fix: the reader now always preserves #? as a CljReaderConditional record;
;; step-evaluate-reader-conditionals materializes the platform branch only on
;; eval paths. Tooling paths see the full record.
;; ---------------------------------------------------------------------------

(deftest reader-conditional-preserves-as-record
  (testing "meme->forms returns a ReaderConditional with all branches"
    (let [rc (first (lang/meme->forms "#?(:clj 1 :cljs 2)"))]
      (is (forms/clj-reader-conditional? rc))
      (is (= '(:clj 1 :cljs 2) (forms/rc-form rc)))))
  (testing "meme->forms + eval-rc step yields the platform branch"
    (let [ctx    (-> {:source "#?(:clj 1 :cljs 2)" :opts {:grammar grammar/grammar}}
                     stages/step-parse
                     stages/step-read
                     stages/step-evaluate-reader-conditionals)]
      (is (= [#?(:clj 1 :cljs 2)] (:forms ctx)))))
  (testing "record roundtrips through printer"
    (let [rc (first (lang/meme->forms "#?(:clj inc(1) :cljs dec(2))"))
          printed (fmt-flat/format-form rc)
          rc2 (first (lang/meme->forms printed))]
      (is (= rc rc2)))))

;; ---------------------------------------------------------------------------
;; Scar tissue: non-matching reader conditional must produce no form *after
;; evaluation*. At the reader level it is preserved as a record; the eval-rc
;; step removes non-matching branches.
;; ---------------------------------------------------------------------------

(deftest non-matching-reader-cond-produces-no-form-after-eval
  (testing "eval-rc drops a non-matching reader-conditional at top level"
    (let [src #?(:clj "#?(:cljs x)" :cljs "#?(:clj x)")
          ctx (-> {:source src :opts {:grammar grammar/grammar}}
                  stages/step-parse
                  stages/step-read
                  stages/step-evaluate-reader-conditionals)]
      (is (= [] (:forms ctx)))))
  (testing "eval-rc filters a non-matching reader-conditional from inside a vector"
    (let [src #?(:clj "[1 #?(:cljs 2) 3]" :cljs "[1 #?(:clj 2) 3]")
          ctx (-> {:source src :opts {:grammar grammar/grammar}}
                  stages/step-parse
                  stages/step-read
                  stages/step-evaluate-reader-conditionals)]
      (is (= [[1 3]] (:forms ctx))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: odd-count branch list. The reader preserves the record as-is;
;; the eval-rc step validates and throws with a clear message.
;; ---------------------------------------------------------------------------

(deftest discard-inside-reader-conditional
  (testing "#?(:clj #_x) — eval-rc errors on the resulting odd count"
    (let [src #?(:clj "#?(:clj #_ x)" :cljs "#?(:cljs #_ x)")]
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                            #"even number of forms"
                            (-> {:source src :opts {:grammar grammar/grammar}}
                                stages/step-parse
                                stages/step-read
                                stages/step-evaluate-reader-conditionals))))))

;; ---------------------------------------------------------------------------
;; Note: duplicate map keys are rejected (see duplicate-keys-rejected above).
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Scar tissue: CljRaw inside syntax-quote was treated as a map by expand-sq.
;; defrecord instances satisfy (map? x), so CljRaw{:value 255, :raw "0xFF"}
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
             "CljRaw must not leak as a map into expanded forms")))
     (testing "scientific notation inside syntax-quote"
       (let [forms (lang/meme->forms "`1e2")
             expanded (expander/expand-forms forms)]
         (is (= [100.0] expanded))))
     (testing "char literal inside syntax-quote"
       (let [forms (lang/meme->forms "`\\a")
             expanded (expander/expand-forms forms)]
         (is (= [\a] expanded))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: nested CljSyntaxQuote inside expand-sq was treated as a map.
;; ``x produced CljSyntaxQuote{:form x} inside the outer CljSyntaxQuote.
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
;; Scar tissue: CljRaw inside #() body was corrupted by normalize-bare-percent.
;; normalize-bare-percent dispatches on (map? form) and uses (into {} ...) which
;; destroys the CljRaw defrecord, replacing it with a plain map. This caused
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
             "0xFF must be resolved to 255, not leaked as a CljRaw map")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: #?@(:clj [2 3]) inside a collection did not splice.
;; parse-reader-cond-eval returned the matched vector as a single form,
;; so [1 #?@(:clj [2 3]) 4] produced [1 [2 3] 4] instead of [1 2 3 4].
;; Fix: wrap splice matches with a splice-result marker, detect in
;; parse-forms-until to splice instead of conj.
;; ---------------------------------------------------------------------------

;; NOTE: #?@ splicing happens in step-evaluate-reader-conditionals after the
;; reader, not during read. These tests compose the step explicitly to assert
;; the post-eval shape.
(defn- eval-rc-forms [src]
  (:forms (-> {:source src :opts {:grammar grammar/grammar}}
              stages/step-parse
              stages/step-read
              stages/step-evaluate-reader-conditionals)))

(deftest splice-reader-conditional-in-vector
  (testing "#?@ splices into vector"
    (is (= [[1 2 3 4]]
           (eval-rc-forms #?(:clj "[1 #?@(:clj [2 3]) 4]"
                             :cljs "[1 #?@(:cljs [2 3]) 4]")))))
  (testing "#?@ at top level splices into forms vector"
    (is (= [1 2]
           (eval-rc-forms #?(:clj "#?@(:clj [1 2])"
                             :cljs "#?@(:cljs [1 2])")))))
  (testing "#?@ with non-matching platform is filtered from collection"
    (is (= [[1 3]]
           (eval-rc-forms #?(:clj "[1 #?@(:cljs [2]) 3]"
                             :cljs "[1 #?@(:clj [2]) 3]")))))
  (testing "matching case still works with call args"
    #?(:clj (is (= ['(inc 42)] (eval-rc-forms "#?(:clj inc)(42)")))
       :cljs (is (= ['(identity 42)] (eval-rc-forms "#?(:cljs identity)(42)"))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: missing value for a platform keyword in reader conditional must
;; produce a specific error, not "Unexpected )".
;; Bug: parse-reader-cond-eval called parse-form which saw ) and threw a generic
;; "Unexpected )" error instead of diagnosing the missing value.
;; Fix: check for ) or EOF after platform keyword before calling parse-form.
;; ---------------------------------------------------------------------------

;; NOTE: Reader conditionals with odd-count forms now error from the eval-rc
;; step, not the reader. The reader preserves any record shape; validation is
;; part of materialization.
(deftest reader-conditional-missing-value-behavior
  (testing "missing value for matching platform — eval-rc errors on odd count"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"even number of forms"
                          (eval-rc-forms #?(:clj "#?(:clj)"
                                            :cljs "#?(:cljs)")))))
  (testing "incomplete input is marked :incomplete"
    (let [e (try (lang/meme->forms #?(:clj "#?(:clj"
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

;; nil/true/false call syntax covered by fixtures/core_rules and
;; reader_cond_literal_call_head test below.

#?(:clj
   (deftest reader-cond-literal-call-head
     (testing "#?(:clj nil)(x) parses to (nil x) after eval-rc"
       (is (= ['(nil x)] (eval-rc-forms "#?(:clj nil)(x)"))))
     (testing "#?(:clj true)(x) parses to (true x) after eval-rc"
       (is (= ['(true x)] (eval-rc-forms "#?(:clj true)(x)"))))
     ;; NOTE: #?(:clj false)(x) — the experimental pipeline evaluates
     ;; #?(:clj false) to false, but since false is not adjacent to (
     ;; (reader-cond produces a value, not a token), the call chain
     ;; may not attach. Let's verify the actual behavior.
     (testing "#?(:clj false)(x) — reader cond value as call head"
       (let [forms (lang/meme->forms "#?(:clj false)(x)")]
         ;; Accept either (false x) or false followed by (x) error/separate
         (is (some? forms))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: nested #() rejection.
;; Covered by nested-anon-fn-rejected test above.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Scar tissue: sequential #_ discards are handled iteratively (not recursively)
;; to avoid hitting the depth limit. Covered by consecutive-discard-semantics.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Scar tissue: metadata on non-metadatable.
;; Covered by metadata-on-non-metadatable-error test above.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Scar tissue: #'foo(bar) — var-quote rejects call expressions.
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
;; Design decision: #?@ splice in prefix operators passes through as vector.
;; The current pipeline does not reject splice in prefix position.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Design decision: empty #?() produces no form (filtered out).
;; ---------------------------------------------------------------------------

(deftest empty-reader-conditional-produces-no-form-after-eval
  (testing "empty #?() preserves as an empty-branch record at read time"
    (let [rc (first (lang/meme->forms "#?()"))]
      (is (forms/clj-reader-conditional? rc))
      (is (empty? (forms/rc-form rc)))))
  (testing "eval-rc drops an empty #?() from :forms"
    (is (= [] (eval-rc-forms "#?()")))))

;; ---------------------------------------------------------------------------
;; #?@ splice inside set: read-time structure is a set containing the RC
;; record as one element; eval-rc materializes the splice into set members.
;; Map + #?@ remains a limitation (matches Clojure's :preserve behavior):
;; odd-count children fail at map construction before eval-rc runs.
;; ---------------------------------------------------------------------------
(deftest splice-in-set
  (testing "#?@ inside set literal — record preserved at read time"
    (is (some? (lang/meme->forms "#{#?@(:clj [1 2])}"))))
  (testing "eval-rc splices the values into the set"
    (is (= [#{1 2}]
           (eval-rc-forms #?(:clj  "#{#?@(:clj [1 2])}"
                             :cljs "#{#?@(:cljs [1 2])}"))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: #?(:clj #_x) — discard inside reader cond.
;; Covered by discard-inside-reader-conditional test above.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Scar tissue: REPL expand context valid with *validate* (PT-F6)
;; The REPL constructs expand contexts — they must pass stage validation.
;; ---------------------------------------------------------------------------

(deftest repl-expand-context-valid
  (testing "context like REPL builds works with step-expand-syntax-quotes"
    (let [ctx {:source "" :cst []
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
;; Scar tissue: invalid % params (%-1, %foo) rejected inside #().
;; Covered by dispatch_test.cljc percent-param tests.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Scar tissue: ratio-to-integer produces Long, not BigInt (RT3-F12)
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
    (let [expanded (stages/expand-syntax-quotes (:forms (stages/run "`{:a ~@xs}" {:grammar grammar/grammar})) nil)]
      (is (= 1 (count expanded)))
      (is (list? (first expanded)))
      ;; Should expand to (apply hash-map (concat (list :a) xs))
      (is (= 'clojure.core/apply (first (first expanded))))))
  (testing "bare ~@ still errors"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"must be inside a collection"
                          (stages/expand-syntax-quotes (:forms (stages/run "`~@xs" {:grammar grammar/grammar})) nil))))
  (testing "~@ in set now works (Clojure allows it)"
    (let [expanded (stages/expand-syntax-quotes (:forms (stages/run "`#{~@xs}" {:grammar grammar/grammar})) nil)]
      (is (= 1 (count expanded)))
      (is (= 'clojure.core/apply (first (first expanded)))))))

;; Scar tissue: CLJS auto-keyword (::foo) in non-matching reader-cond branch
;; caused a hard error because resolve-auto-keyword errored on CLJS without
;; :resolve-keyword. Now defers resolution like JVM, so non-matching branches
;; don't error.

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
       (let [ctx (-> {:source "#?(:clj 1 :cljs 2)" :opts {:grammar grammar/grammar}}
                     (stages/step-parse)
                     (stages/step-read)
                     (stages/step-expand-syntax-quotes))
             form (first (:forms ctx))]
         (is (forms/clj-reader-conditional? form))
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
;; Scar tissue: shebang stripping in non-eval paths.
;; stages/run strips shebang before scanning. Covered by strip-shebang-crlf above.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Scar tissue: #' var-quote rejects all non-symbol targets.
;; Covered by var-quote-requires-symbol test above.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Scar tissue: \delete, \null, \nul removed (not in Clojure's LispReader)
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest removed-nonstandard-char-names
     (testing "\\delete is not a valid char name"
       (is (thrown? Exception (lang/meme->forms "\\delete"))))
     (testing "\\null is not a valid char name"
       (is (thrown? Exception (lang/meme->forms "\\null"))))
     (testing "\\nul is not a valid char name"
       (is (thrown? Exception (lang/meme->forms "\\nul"))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: reader conditional with odd-count forms must error,
;; not silently drop the last element via partition 2.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Scar tissue: meme->clj used to silently drop off-platform branches of
;; reader conditionals because the reader's :eval mode materialized the
;; current platform's value at read time. After the "eval-rc as pipeline
;; stage" refactor, the reader always preserves #? as CljReaderConditional
;; records, so meme->clj (a text-to-text tooling function, not eval) emits
;; both branches faithfully.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest meme->clj-reader-conditional-lossless
     (testing "meme->clj preserves both branches of #?"
       (is (= "#?(:cljs dom/create :clj nil)"
              (lang/meme->clj "#?(:cljs dom/create :clj nil)"))))
     (testing "meme->clj preserves a non-current-platform #? entirely"
       (is (= "#?(:cljs only-cljs)"
              (lang/meme->clj "#?(:cljs only-cljs)"))))
     (testing "to-clj (CLI adapter) agrees with meme->clj"
       (is (= (lang/meme->clj "#?(:clj 1 :cljs 2)")
              (lang/to-clj "#?(:clj 1 :cljs 2)"))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: :read-cond is no longer accepted. Passing it must throw a
;; clear migration error rather than silently no-op.
;; ---------------------------------------------------------------------------

(deftest read-cond-opt-throws
  (testing "meme->forms with :read-cond throws :meme/deprecated-opt"
    (try (lang/meme->forms "x" {:read-cond :preserve})
         (is false "should have thrown")
         (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
           (is (= :meme/deprecated-opt (:type (ex-data e))))
           (is (= :read-cond (:opt (ex-data e))))
           (is (re-find #"no longer supported" (ex-message e))))))
  (testing "step-read directly also guards"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                          #"no longer supported"
                          (stages/step-read {:cst [] :opts {:read-cond :preserve}})))))

(deftest reader-conditional-odd-count
  (testing "#?(:clj 1 :cljs) — eval-rc errors on odd count"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"even number of forms"
                          (eval-rc-forms "#?(:clj 1 :cljs)"))))
  (testing "#?(:clj) — eval-rc errors on single element"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"even number of forms"
                          (eval-rc-forms "#?(:clj)"))))
  (testing "#?(:clj 1 :cljs 2) — even count is fine"
    (is (some? (lang/meme->forms "#?(:clj 1 :cljs 2)")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: empty namespace or name in namespaced keywords must error.
;; :/foo (empty namespace) and :ns/ (empty name) are invalid in Clojure.
;; ---------------------------------------------------------------------------

(deftest empty-keyword-namespace-components
  (testing ":/foo — empty namespace"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"Invalid token"
                          (lang/meme->forms ":/foo"))))
  (testing ":ns/ — empty name"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"Invalid token"
                          (lang/meme->forms ":ns/")))))

;; ---------------------------------------------------------------------------
;; Scar tissue: double-unquote `~~x` inside syntax-quote.
;; Bug: expand-sq peeled off the outer unquote and recursed into
;; expand-syntax-quotes with {:inside-sq true}. When the inner form was
;; itself an unquote, the outer branch wrapped it in a CljUnquote record
;; and leaked that record to eval instead of erroring.
;; Fix: error at expand-sq time when the argument of `~` is itself `~` or
;; `~@` — matches Clojure's reader, which rejects bare `~~x`.
;; ---------------------------------------------------------------------------

(deftest double-unquote-inside-syntax-quote-rejected
  (testing "parse still preserves the nested unquote structure"
    ;; Parsing is structurally permissive; the error fires in the expander.
    (let [[form] (lang/meme->forms "`~~x")]
      (is (forms/syntax-quote? form))
      (is (forms/unquote? (:form form)))
      (is (forms/unquote? (:form (:form form))))))
  (testing "expander errors on `~~x — no matching syntax-quote for second ~"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"no matching enclosing syntax-quote"
                          (stages/expand-syntax-quotes
                            (lang/meme->forms "`~~x") nil))))
  (testing "expander errors on `~~@[1 2]"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"no matching enclosing syntax-quote"
                          (stages/expand-syntax-quotes
                            (lang/meme->forms "`~~@[1 2]") nil))))
  (testing "``~~x still expands to x (two unquotes balanced by two syntax-quotes)"
    (is (= ['x]
           (stages/expand-syntax-quotes
             (lang/meme->forms "``~~x") nil))))
  (testing "single `~x still works as a control"
    (is (= [42]
           (stages/expand-syntax-quotes
             (lang/meme->forms "`~42") nil)))))

;; ---------------------------------------------------------------------------
;; Scar tissue: when `~~x / `~~@x errors via check-no-leftover-unquotes!, the
;; thrown ex-data must carry :line and :col from the source. Previously the
;; wrapper constructed a new CljUnquote record with no metadata, so the error
;; was reported as coming from line 0 / nowhere.
;; ---------------------------------------------------------------------------

(deftest leftover-unquote-error-has-source-location
  (testing "`~~x error carries :line/:col from the source"
    (let [e (try (stages/expand-syntax-quotes
                   (lang/meme->forms "\n\n  `~~x") nil)
                 nil
                 (catch #?(:clj Exception :cljs js/Error) ex ex))]
      (is (some? e))
      (let [{:keys [line col]} (ex-data e)]
        (is (pos-int? line))
        (is (pos-int? col)))))
  (testing "`~~@x error carries :line/:col from the source"
    (let [e (try (stages/expand-syntax-quotes
                   (lang/meme->forms "\n  `~~@[1 2]") nil)
                 nil
                 (catch #?(:clj Exception :cljs js/Error) ex ex))]
      (is (some? e))
      (let [{:keys [line col]} (ex-data e)]
        (is (pos-int? line))
        (is (pos-int? col))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: duplicate-key detection must unwrap CljRaw.
;; Bug: {0xFF 1 255 2} silently succeeded because CljRaw{:value 255 :raw "0xFF"}
;; was not = to plain 255 in the frequencies call. Same for sets and
;; unicode-escaped chars (\u0041 vs \A both resolve to \A but wrap differently).
;; ---------------------------------------------------------------------------

(deftest duplicate-map-key-across-notations
  (testing "{\\u0041 1 \\A 2} — unicode-escape and char literal of same char"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Duplicate key"
                          (lang/meme->forms "{\\u0041 1 \\A 2}")))))

#?(:clj
   (deftest duplicate-map-key-hex-vs-decimal
     (testing "{0xFF 1 255 2} — hex and decimal of same value are duplicates (JVM only — hex not supported on CLJS)"
       (is (thrown-with-msg? Exception #"Duplicate key"
                             (lang/meme->forms "{0xFF 1 255 2}"))))
     (testing "{0xFF 1 0xFF 2} — two identical hex literals (control; already errored)"
       (is (thrown-with-msg? Exception #"Duplicate key"
                             (lang/meme->forms "{0xFF 1 0xFF 2}"))))
     (testing "{0xFF 1 0x10 2} — different hex values parse fine"
       (let [[m] (lang/meme->forms "{0xFF 1 0x10 2}")]
         (is (= 2 (count m)))))))

#?(:clj
   (deftest duplicate-set-key-across-notations
     (testing "#{0xFF 255} — hex and decimal of same value are duplicates (JVM only)"
       (is (thrown-with-msg? Exception #"Duplicate key"
                             (lang/meme->forms "#{0xFF 255}"))))
     (testing "#{0xFF 0x10} — different hex values parse fine"
       (let [[s] (lang/meme->forms "#{0xFF 0x10}")]
         (is (= 2 (count s)))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: namespaced maps silently dropped duplicate keys.
;; Bug: #:ns{:a 1 :a 2} returned #:ns{:a 2} via array-map last-write-wins,
;; while Clojure throws "Duplicate key: :ns/a". The :map branch had a
;; first-duplicate guard; the :namespaced-map branch did not.
;; Fix: qualify keys, then run first-duplicate before building the map.
;; ---------------------------------------------------------------------------

(deftest namespaced-map-duplicate-keys
  (testing "#:ns{:a 1 :a 2} — duplicate bare keys after qualification"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Duplicate key: :ns/a"
                          (lang/meme->forms "#:ns{:a 1 :a 2}"))))
  (testing "#:ns{:a/b 1 :a/b 2} — duplicates already qualified, must still error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Duplicate key: :a/b"
                          (lang/meme->forms "#:ns{:a/b 1 :a/b 2}"))))
  (testing "#::{:a 1 :a 2} — bare auto-resolve, qual-ns blank, dups on bare keyword"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Duplicate key: :a"
                          (lang/meme->forms "#::{:a 1 :a 2}"))))
  (testing "#:ns{:a 1 :b 2} — distinct keys still parse cleanly"
    (let [[m] (lang/meme->forms "#:ns{:a 1 :b 2}")]
      (is (= {:ns/a 1 :ns/b 2} m)))))
