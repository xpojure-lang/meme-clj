(ns mclj-lang.reader.errors-test
  "Parser tests for error handling: error cases, rejected forms,
   error messages with source locations."
  (:require [clojure.test :refer [deftest is testing]]
            [mclj-lang.api :as lang]))

;; ---------------------------------------------------------------------------
;; Parse errors
;; ---------------------------------------------------------------------------

;; Unterminated strings are now properly rejected with :incomplete marker.
(deftest parse-unterminated-string
  (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                        #"Unterminated string"
                        (lang/meme->forms "\"unterminated"))))

(deftest parse-mismatched-paren
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (lang/meme->forms "foo(bar"))))

(deftest parse-unexpected-close-paren
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (lang/meme->forms ")"))))

(deftest parse-odd-count-map
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (lang/meme->forms "{:a 1 :b}")))
  (let [e (try (lang/meme->forms "{:a 1 :b}")
               nil
               (catch #?(:clj Exception :cljs js/Error) e e))]
    (is (some? (:line (ex-data e))))
    (is (re-find #"even number" (ex-message e)))))

(deftest parse-mismatched-bracket-types
  (testing "closing paren where bracket expected"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (lang/meme->forms "[1 2)"))))
  (testing "closing bracket where brace expected"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (lang/meme->forms "{:a 1]"))))
  (testing "closing bracket where paren expected in call"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (lang/meme->forms "foo(1 2]")))))

;; ---------------------------------------------------------------------------
;; Syntax-quote is opaque passthrough (JVM), rejected on CLJS.
;; Unquote/unquote-splicing outside syntax-quote are always errors.
;; ---------------------------------------------------------------------------

(deftest parse-syntax-quote-native
  (testing "`foo produces expanded form"
    (is (some? (first (lang/meme->forms "`foo")))))
  (testing "`if(test then else) produces expanded form"
    (is (some? (first (lang/meme->forms "`if(test then else)"))))))

;; NOTE: The experimental pipeline accepts ~ and ~@ outside syntax-quote at
;; parse time (produces CljUnquote/CljUnquoteSplicing). Error at eval time.
(deftest parse-unquote-outside-syntax-quote
  (is (some? (lang/meme->forms "~x"))))

(deftest parse-unquote-splicing-outside-syntax-quote
  (is (some? (lang/meme->forms "~@xs"))))

;; ---------------------------------------------------------------------------
;; Error messages include source location and are human-readable
;; ---------------------------------------------------------------------------

(deftest error-messages-include-location
  (testing "unterminated string rejected with error"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Unterminated string"
                          (lang/meme->forms "\"unclosed"))))
  (testing "unmatched paren includes error"
    (let [e (try (lang/meme->forms "foo(1 2")
                 nil
                 (catch #?(:clj Exception :cljs js/Error) e e))]
      (is (some? e))
      (is (re-find #"(?i)eof|expected" (ex-message e)))))
  (testing "unexpected token includes location"
    (let [e (try (lang/meme->forms ")")
                 nil
                 (catch #?(:clj Exception :cljs js/Error) e e))]
      (is (some? e))
      (is (= 1 (:line (ex-data e))))
      (is (= 1 (:col (ex-data e))))))
  (testing "unterminated string on second line rejected"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Unterminated string"
                          (lang/meme->forms "foo()\n\"unclosed")))))


