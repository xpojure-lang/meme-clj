(ns meme.alpha.parse.reader.errors-test
  "Parser tests for error handling: error cases, rejected forms,
   error messages with source locations, and CLJS-specific errors."
  (:require [clojure.test :refer [deftest is testing]]
            [meme.alpha.core :as core]
            [meme.alpha.scan.tokenizer :as tokenizer]
            [meme.alpha.scan.grouper :as grouper]))

(defn- tokenize [s]
  (-> (tokenizer/tokenize s) (grouper/group-tokens s)))

;; ---------------------------------------------------------------------------
;; Parse errors
;; ---------------------------------------------------------------------------

(deftest parse-unterminated-string
  (is (thrown? #?(:clj Exception :cljs js/Error)
              (core/meme->forms "\"unterminated"))))

(deftest parse-mismatched-paren
  (is (thrown? #?(:clj Exception :cljs js/Error)
              (core/meme->forms "foo(bar"))))

(deftest parse-unexpected-close-paren
  (is (thrown? #?(:clj Exception :cljs js/Error)
              (core/meme->forms ")"))))

(deftest parse-odd-count-map
  (is (thrown? #?(:clj Exception :cljs js/Error)
              (core/meme->forms "{:a 1 :b}")))
  (let [e (try (core/meme->forms "{:a 1 :b}")
               nil
               (catch #?(:clj Exception :cljs js/Error) e e))]
    (is (some? (:line (ex-data e))))
    (is (re-find #"even number" (ex-message e)))))

(deftest parse-mismatched-bracket-types
  (testing "closing paren where bracket expected"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                (core/meme->forms "[1 2)"))))
  (testing "closing bracket where brace expected"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                (core/meme->forms "{:a 1]"))))
  (testing "closing bracket where paren expected in call"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                (core/meme->forms "foo(1 2]")))))

;; ---------------------------------------------------------------------------
;; Syntax-quote is opaque passthrough (JVM), rejected on CLJS.
;; Unquote/unquote-splicing outside syntax-quote are always errors.
;; ---------------------------------------------------------------------------

#?(:clj
(deftest parse-syntax-quote-passthrough
  (testing "`foo passes through to Clojure's reader"
    (is (some? (first (core/meme->forms "`foo")))))
  (testing "`(if test then else) passes through"
    (is (some? (first (core/meme->forms "`(if test then else)")))))))

#?(:cljs
(deftest parse-syntax-quote-rejected-cljs
  (is (thrown? js/Error (core/meme->forms "`foo")))))

(deftest parse-unquote-outside-syntax-quote
  (is (thrown? #?(:clj Exception :cljs js/Error) (core/meme->forms "~x"))))

(deftest parse-unquote-splicing-outside-syntax-quote
  (is (thrown? #?(:clj Exception :cljs js/Error) (core/meme->forms "~@xs"))))

;; ---------------------------------------------------------------------------
;; Error messages include source location and are human-readable
;; ---------------------------------------------------------------------------

(deftest error-messages-include-location
  (testing "unterminated string includes location"
    (let [e (try (core/meme->forms "\"unclosed")
                 nil
                 (catch #?(:clj Exception :cljs js/Error) e e))]
      (is (some? e))
      (is (some? (:line (ex-data e))))
      (is (some? (:col (ex-data e))))))
  (testing "unmatched paren includes error"
    (let [e (try (core/meme->forms "foo(1 2")
                 nil
                 (catch #?(:clj Exception :cljs js/Error) e e))]
      (is (some? e))
      (is (re-find #"(?i)eof|expected" (ex-message e)))))
  (testing "unexpected token includes location"
    (let [e (try (core/meme->forms ")")
                 nil
                 (catch #?(:clj Exception :cljs js/Error) e e))]
      (is (some? e))
      (is (= 1 (:line (ex-data e))))
      (is (= 1 (:col (ex-data e))))))
  (testing "error on second line has location"
    (let [e (try (core/meme->forms "foo()\n\"unclosed")
                 nil
                 (catch #?(:clj Exception :cljs js/Error) e e))]
      (is (some? e))
      (is (= 2 (:line (ex-data e))))
      (is (some? (:col (ex-data e)))))))

;; ---------------------------------------------------------------------------
;; ClojureScript-specific: opaque forms give meme-specific error messages
;; ---------------------------------------------------------------------------

#?(:cljs
(deftest cljs-opaque-form-errors
  (testing "reader conditional gives meme-specific error on CLJS"
    (let [tokens (tokenize "#?(:clj 1 :cljs 2)")]
      (is (= 1 (count tokens)))
      (is (= :reader-cond-raw (:type (first tokens)))))
    (is (thrown-with-msg? js/Error #"Reader conditionals"
                          (core/meme->forms "#?(:clj 1 :cljs 2)"))))
  (testing "namespaced map gives meme-specific error on CLJS"
    (let [tokens (tokenize "#:user{:name \"x\"}")]
      (is (= 1 (count tokens)))
      (is (= :namespaced-map-raw (:type (first tokens)))))
    (is (thrown-with-msg? js/Error #"Namespaced maps"
                          (core/meme->forms "#:user{:name \"x\"}"))))))
