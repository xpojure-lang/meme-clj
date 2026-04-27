(ns meme.tools.clj.errors-test
  "Unit tests for meme.tools.clj.errors: format-error display and source-context extraction."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [meme.tools.clj.errors :as errors]))

;; ---------------------------------------------------------------------------
;; source-context
;; ---------------------------------------------------------------------------

(deftest source-context-extracts-line
  (testing "extracts first line"
    (is (= "hello" (errors/source-context "hello\nworld" 1))))
  (testing "extracts second line"
    (is (= "world" (errors/source-context "hello\nworld" 2))))
  (testing "out-of-range returns nil"
    (is (nil? (errors/source-context "hello" 5))))
  (testing "zero line returns nil"
    (is (nil? (errors/source-context "hello" 0)))))

;; ---------------------------------------------------------------------------
;; meme-error
;; ---------------------------------------------------------------------------

(deftest meme-error-basic
  (testing "throws ExceptionInfo with message"
    (let [e (try (errors/meme-error "test error") nil
                 (catch #?(:clj Exception :cljs :default) e e))]
      (is (some? e))
      (is (= "test error" (ex-message e)))))
  (testing "appends line/col to message"
    (let [e (try (errors/meme-error "bad token" {:line 3 :col 7}) nil
                 (catch #?(:clj Exception :cljs :default) e e))]
      (is (re-find #"line 3, col 7" (ex-message e)))))
  (testing "line/col present in ex-data"
    (let [e (try (errors/meme-error "err" {:line 2 :col 5}) nil
                 (catch #?(:clj Exception :cljs :default) e e))]
      (is (= 2 (:line (ex-data e))))
      (is (= 5 (:col (ex-data e))))))
  (testing ":cause is excluded from ex-data but attached as cause"
    (let [cause (ex-info "root" {})
          e (try (errors/meme-error "wrap" {:line 1 :col 1 :cause cause}) nil
                 (catch #?(:clj Exception :cljs :default) e e))]
      (is (nil? (:cause (ex-data e))))
      (is (= cause #?(:clj (.getCause e) :cljs (ex-cause e))))))
  (testing ":source is excluded from ex-data"
    (let [e (try (errors/meme-error "err" {:line 1 :col 1 :source "hello"}) nil
                 (catch #?(:clj Exception :cljs :default) e e))]
      (is (nil? (:source (ex-data e))))))
  (testing ":source-context added when both :source and :line present"
    (let [e (try (errors/meme-error "err" {:line 1 :col 1 :source "hello"}) nil
                 (catch #?(:clj Exception :cljs :default) e e))]
      (is (= "hello" (:source-context (ex-data e))))))
  (testing ":source-context absent when :line missing"
    (let [e (try (errors/meme-error "err" {:col 1 :source "hello"}) nil
                 (catch #?(:clj Exception :cljs :default) e e))]
      (is (nil? (:source-context (ex-data e)))))))

;; ---------------------------------------------------------------------------
;; format-error
;; ---------------------------------------------------------------------------

(deftest format-error-with-location-and-source
  (let [e (ex-info "Unterminated string" {:line 2 :col 5})
        result (errors/format-error e "line one\nlet x = \"abc")]
    (testing "includes error message"
      (is (re-find #"Unterminated string" result)))
    (testing "includes context line with gutter"
      (is (re-find #"2 \| let x = \"abc" result)))
    (testing "includes caret at correct column in gutter format"
      (is (re-find #"\| +\^" result)))))

(deftest format-error-without-source
  (let [e (ex-info "Unexpected EOF" {:line 1 :col 3})
        result (errors/format-error e)]
    (testing "includes message"
      (is (re-find #"Unexpected EOF" result)))
    (testing "no context line without source"
      (is (not (re-find #"\n" result))))))

(deftest format-error-plain-exception
  (let [e (#?(:clj Exception. :cljs js/Error.) "plain error")
        result (errors/format-error e "some source")]
    (testing "includes message"
      (is (re-find #"plain error" result)))
    (testing "no caret for non-ExceptionInfo"
      (is (not (re-find #"\^" result))))))

(deftest format-error-missing-line-col
  (let [e (ex-info "some error" {})
        result (errors/format-error e "source text")]
    (testing "no context without :line"
      (is (not (re-find #"source text" result))))))

(deftest source-context-negative-line
  (is (nil? (errors/source-context "hello" -1))))

(deftest format-error-col-zero
  (let [e (ex-info "bad col" {:line 1 :col 0})
        result (errors/format-error e "hello")]
    (is (not (re-find #"\^" result)))))

(deftest format-error-error-prefix
  (let [e (ex-info "some message" {:line 1 :col 1})
        result (errors/format-error e "hello")]
    (is (str/starts-with? result "Error: "))))

;; ---------------------------------------------------------------------------
;; Advanced features: :end-col span, :hint, :secondary locations
;; ---------------------------------------------------------------------------

(deftest format-error-end-col-span
  (testing "multi-column span uses ~ underline"
    (let [e (ex-info "bad token" {:line 1 :col 3 :end-col 7})
          result (errors/format-error e "let foo = bar")]
      (is (re-find #"~~~~" result)))))

(deftest format-error-hint
  (testing ":hint is displayed"
    (let [e (ex-info "unexpected token" {:line 1 :col 1 :hint "Did you mean foo?"})
          result (errors/format-error e "bar")]
      (is (re-find #"Hint: Did you mean foo\?" result)))))

(deftest format-error-secondary-locations
  (testing ":secondary locations are displayed with labels"
    (let [e (ex-info "mismatched bracket"
                     {:line 3 :col 5
                      :secondary [{:line 1 :col 1 :label "opened here"}]})
          source "foo(\n  bar\n    )"
          result (errors/format-error e source)]
      (is (re-find #"opened here" result)))))

(deftest format-error-gutter-width-spans-all-lines
  (testing "gutter width accommodates secondary line numbers wider than primary"
    (let [;; Build source with 1000 lines so line 1000 is valid
          source (str/join "\n" (concat (repeat 999 "x") ["error-line"]))
          e (ex-info "test error"
                     {:line 5 :col 1
                      :secondary [{:line 1000 :col 1 :label "related"}]})
          result (errors/format-error e source)]
      ;; gutter-w should be 4 (digits in "1000"), not 1 (digits in "5")
      ;; so primary line 5 should be padded: "   5 | "
      (is (re-find #"   5 \|" result) "primary line padded to 4-wide gutter")
      (is (re-find #"1000 \|" result) "secondary line fits in gutter"))))

(deftest format-error-hint-without-source
  (testing ":hint works even without source context"
    (let [e (ex-info "error" {:hint "Try this instead"})
          result (errors/format-error e)]
      (is (re-find #"Hint: Try this instead" result)))))

(deftest format-error-crlf-line-endings
  (testing "CRLF source displays correct context line and caret"
    (let [e (ex-info "bad token" {:line 3 :col 5})
          source "line one\r\nline two\r\n    bad-token here"
          result (errors/format-error e source)]
      (is (re-find #"bad token" result))
      (is (re-find #"3 \|" result) "line 3 shown in gutter")
      (is (re-find #"bad-token here" result) "correct context line extracted")))
  (testing "CRLF source — line 1 error"
    (let [e (ex-info "error" {:line 1 :col 1})
          source "first\r\nsecond\r\nthird"
          result (errors/format-error e source)]
      (is (re-find #"first" result)))))

;; ---------------------------------------------------------------------------
;; format-error with :secondary locations — detailed verification
;; ---------------------------------------------------------------------------

(deftest format-error-secondary-with-caret-and-label
  (testing ":secondary locations render line, caret, and label"
    (let [source "open(\n  body\n  close)"
          e (ex-info "mismatched delimiter"
                     {:line 3 :col 3
                      :secondary [{:line 1 :col 1 :label "opening paren here"}]})
          result (errors/format-error e source)]
      (testing "primary error message present"
        (is (re-find #"mismatched delimiter" result)))
      (testing "primary line shown"
        (is (re-find #"3 \|" result)))
      (testing "secondary line shown"
        (is (re-find #"1 \|" result)))
      (testing "secondary label present"
        (is (re-find #"opening paren here" result)))
      (testing "secondary caret present"
        (is (re-find #"\^ opening paren here" result))))))
