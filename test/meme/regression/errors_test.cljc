(ns meme.regression.errors-test
  "Scar tissue: error infrastructure and resolve error-wrapping regression tests.
   Every test here prevents a specific bug from recurring."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [meme.errors]
            [meme.core :as core]))

;; ---------------------------------------------------------------------------
;; B5/B6: source-context nil/empty guards.
;; Bug: source-context threw NPE on nil source, returned truthy "" on empty.
;; ---------------------------------------------------------------------------

(deftest source-context-edge-cases
  (testing "nil source returns nil"
    (is (nil? (meme.errors/source-context nil 1))))
  (testing "empty source returns nil"
    (is (nil? (meme.errors/source-context "" 1))))
  (testing "blank source returns nil"
    (is (nil? (meme.errors/source-context "   " 1)))))

;; ---------------------------------------------------------------------------
;; Bug: error gutter misalignment when secondary line has more digits.
;; ---------------------------------------------------------------------------

(deftest error-gutter-width-spans-all-lines
  (testing "gutter width accommodates secondary line numbers wider than primary"
    (let [source (str/join "\n" (concat (repeat 999 "x") ["error-line"]))
          e (ex-info "test error"
                     {:line 5 :col 1
                      :secondary [{:line 1000 :col 1 :label "related"}]})
          result (meme.errors/format-error e source)]
      (is (re-find #"   5 \|" result) "primary line padded to 4-wide gutter")
      (is (re-find #"1000 \|" result) "secondary line fits in gutter"))))

;; ---------------------------------------------------------------------------
;; read-string errors include source location context.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest read-string-errors-include-location
     (testing "malformed number includes location"
       (let [e (try (core/meme->forms "1/")
                    nil
                    (catch Exception e e))]
         (is (some? e))
         (is (= 1 (:line (ex-data e))))
         (is (= 1 (:col (ex-data e))))
         (is (re-find #"Invalid number" (ex-message e)))))
     (testing "malformed regex includes location"
       (let [e (try (core/meme->forms "#\"[unclosed\"")
                    nil
                    (catch Exception e e))]
         (is (some? e))
         (is (= 1 (:line (ex-data e))))
         (is (re-find #"Invalid regex" (ex-message e)))))))

;; ---------------------------------------------------------------------------
;; Opaque-form read-string errors must include meme source location.
;; ---------------------------------------------------------------------------

(deftest namespaced-map-errors-include-location
  (testing "malformed namespaced map has :line/:col in ex-data"
    (let [ex (try (core/meme->forms "#:ns{:a}")
                  (catch #?(:clj Exception :cljs :default) e e))]
      (is (some? ex))
      (is (:line (ex-data ex)))
      (is (:col (ex-data ex))))))

;; ---------------------------------------------------------------------------
;; B1: tagged-literal CLJS guard.
;; Bug: tagged-literal function called unconditionally on CLJS, producing a
;; raw ReferenceError instead of a helpful meme error.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; B10: #=() read-eval blocked in all contexts.
;; Bug: originally, opaque-region functions called clojure.core/read-string
;; without binding *read-eval* to false, allowing #=() to execute at read time.
;; Fix: all forms are now parsed natively by the recursive-descent parser,
;; which does not recognize #= as a dispatch form — it is rejected at scan time.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest read-eval-blocked-in-opaque-forms
     (testing "#=() blocked in syntax-quote"
       (is (thrown? Exception (core/meme->forms "`(#=(+ 1 2))"))))
     (testing "#=() blocked in namespaced map"
       (is (thrown? Exception (core/meme->forms "#:ns{:k #=(+ 1 2)}"))))
     (testing "#=() blocked in reader conditional"
       (is (thrown? Exception (core/meme->forms "#?(:clj #=(+ 1 2))"))))))

;; ---------------------------------------------------------------------------
;; S1: clj->forms executed #=() at read time — no *read-eval* binding.
;; Every other host-reader call bound *read-eval* false, but clj->forms
;; and dogfood_test/read-clj-forms did not.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest read-eval-blocked-in-clj->forms
     (testing "#=() blocked in clj->forms"
       (is (thrown? Exception (core/clj->forms "#=(+ 1 2)"))))
     (testing "normal Clojure still reads fine"
       (is (= '[(+ 1 2)] (core/clj->forms "(+ 1 2)"))))))

;; ---------------------------------------------------------------------------
;; Scanner vs display line model: CRLF bridge in format-error.
;; The scanner line model (line-col->offset) treats \r as a regular column.
;; The display model (source-context via str/split-lines) strips \r from
;; \r\n pairs. format-error bridges these — caret must not overrun the
;; displayed line when scanner col exceeds display line length.
;; ---------------------------------------------------------------------------

(deftest format-error-crlf-caret-clamp
  (testing "caret clamped to display line length on CRLF source"
    ;; Scanner sees line 1 as [f o o \r] (4 cols), but display shows "foo" (3 chars).
    ;; A col pointing at \r (col 4) should not produce a caret past "foo".
    (let [e (ex-info "bad" {:line 1 :col 4})
          source "foo\r\nbar"
          result (meme.errors/format-error e source)]
      (is (re-find #"foo" result))
      ;; Caret at col 4 is clamped to col 4 (inc display-len=3+1=4), which is
      ;; one past "foo" — acceptable for exclusive end position
      (is (re-find #"\^" result))))
  (testing "span underline clamped on CRLF source"
    ;; end-col 5 would overrun "foo" (3 chars) — should be clamped
    (let [e (ex-info "bad" {:line 1 :col 3 :end-col 5})
          source "foo\r\nbar"
          result (meme.errors/format-error e source)]
      (is (re-find #"foo" result))
      ;; Span from col 3 to clamped end-col 4: single ~ or ^
      (is (re-find #"\| +[~^]" result))))
  (testing "normal LF source unaffected by clamp"
    (let [e (ex-info "bad" {:line 1 :col 1 :end-col 4})
          source "foo\nbar"
          result (meme.errors/format-error e source)]
      (is (re-find #"~~~" result)))))

#?(:cljs
   (deftest tagged-literal-cljs-error
     (testing "#uuid on CLJS throws meme error, not ReferenceError"
       (is (thrown-with-msg? js/Error #"not supported in ClojureScript"
                             (core/meme->forms "#uuid \"550e8400-e29b-41d4-a716-446655440000\""))))))

;; ---------------------------------------------------------------------------
;; CLJS-only: unsupported number formats must error, not silently truncate.
;; ---------------------------------------------------------------------------

#?(:cljs
   (deftest cljs-unsupported-number-formats
     (testing "BigInt N suffix"
       (is (thrown-with-msg? js/Error #"BigInt" (core/meme->forms "42N"))))
     (testing "BigDecimal M suffix"
       (is (thrown-with-msg? js/Error #"BigDecimal" (core/meme->forms "42M"))))
     (testing "Ratio"
       (is (thrown-with-msg? js/Error #"Ratio" (core/meme->forms "1/2"))))
     (testing "Hex"
       (is (thrown-with-msg? js/Error #"Hex" (core/meme->forms "0xFF"))))
     (testing "Radix"
       (is (thrown-with-msg? js/Error #"Radix" (core/meme->forms "2r1010"))))
     (testing "Octal"
       (is (thrown-with-msg? js/Error #"Octal" (core/meme->forms "010"))))))

;; ---------------------------------------------------------------------------
;; Scar tissue: error message quality improvements
;; ---------------------------------------------------------------------------

(deftest mismatched-bracket-error-is-specific
  (testing "[1 2) produces mismatch message, not generic 'Unexpected )'"
    (let [e (try (core/meme->forms "[1 2)")
                 nil
                 (catch #?(:clj Exception :cljs js/Error) e e))]
      (is (re-find #"[Mm]ismatched" (ex-message e))
          "error should mention 'mismatched', not generic 'Unexpected'")
      (is (re-find #"expected \]" (ex-message e)))
      (is (re-find #"got [)]" (ex-message e)))))
  (testing "{:a 1] produces mismatch with opened-here secondary"
    (let [e (try (core/meme->forms "{:a 1]")
                 nil
                 (catch #?(:clj Exception :cljs js/Error) e e))]
      (is (re-find #"[Mm]ismatched" (ex-message e)))
      (is (some? (:secondary (ex-data e)))
          "should have secondary location pointing at opener"))))

(deftest duplicate-key-names-the-offender
  (testing "duplicate map key includes the key in the message"
    (let [e (try (core/meme->forms "{:a 1 :b 2 :a 3}")
                 nil
                 (catch #?(:clj Exception :cljs js/Error) e e))]
      (is (re-find #":a" (ex-message e))
          "error should name the duplicate key :a")))
  (testing "duplicate set element includes the element in the message"
    (let [e (try (core/meme->forms "#{1 2 1}")
                 nil
                 (catch #?(:clj Exception :cljs js/Error) e e))]
      (is (re-find #"1" (ex-message e))
          "error should name the duplicate element"))))

(deftest unclosed-reader-conditional-has-context
  (testing "unclosed #?( has secondary and hint"
    (let [e (try (core/meme->forms "#?(:clj 1")
                 nil
                 (catch #?(:clj Exception :cljs js/Error) e e))]
      (is (:incomplete (ex-data e)))
      (is (re-find #"end of input" (ex-message e)))
      (is (some? (:secondary (ex-data e)))
          "should have secondary location")
      (is (some? (:hint (ex-data e)))
          "should have hint about closing )"))))

(deftest unquote-discard-messages-have-suffix
  (testing "unquote discard message ends with action"
    (let [e (try (core/meme->forms "`[~#_ x]")
                 nil
                 (catch #?(:clj Exception :cljs js/Error) e e))]
      (is (re-find #"nothing to unquote" (ex-message e))))))

(deftest anon-fn-multi-body-shows-opener
  (testing "#() with two expressions shows #( location"
    (let [e (try (core/meme->forms "#(1 2)")
                 nil
                 (catch #?(:clj Exception :cljs js/Error) e e))]
      (is (re-find #"single expression" (ex-message e)))
      (is (some? (:secondary (ex-data e)))
          "should have secondary pointing at #( opener"))))

#?(:clj
   (deftest unicode-escape-in-string-reports-count
     (testing "truncated \\u in string includes digit count"
       (let [e (try (core/meme->forms "\"\\u41\"")
                    nil
                    (catch Exception e e))]
         (is (re-find #"got 2" (ex-message e))
             "should report how many hex digits were found")))))

;; ---------------------------------------------------------------------------
;; F9: Unpaired surrogates (\uD800-\uDFFF) were silently accepted.
;; Bug: parse-unicode-escape accepted any 4-hex-digit code point, including
;; surrogates. This diverged from valid Unicode and produced replacement chars.
;; Fix: reject code points in the surrogate range with a clear error.
;; ---------------------------------------------------------------------------

(deftest surrogate-unicode-escape-rejected
  (testing "\\uD800 (low surrogate start) is rejected"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"(?i)surrogate"
                          (core/meme->forms "\"\\uD800\""))))
  (testing "\\uDFFF (high surrogate end) is rejected"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"(?i)surrogate"
                          (core/meme->forms "\"\\uDFFF\""))))
  (testing "valid unicode escapes still work"
    (is (some? (core/meme->forms "\"\\u0041\"")))
    (is (some? (core/meme->forms "\"\\uFFFF\"")))))
