(ns meme.alpha.parse.resolve-test
  "Unit tests for meme.alpha.parse.resolve: value resolution from raw token text."
  (:require [clojure.test :refer [deftest is testing]]
            [meme.alpha.forms :as forms]
            [meme.alpha.parse.resolve :as resolve]))

;; ---------------------------------------------------------------------------
;; Numbers
;; ---------------------------------------------------------------------------

(deftest resolve-number-basic
  (is (= 42 (resolve/resolve-number "42" {:line 1 :col 1})))
  (is (= -1 (resolve/resolve-number "-1" {:line 1 :col 1})))
  (is (= 3.14 (resolve/resolve-number "3.14" {:line 1 :col 1}))))

#?(:clj
(deftest resolve-number-formats
  (testing "hex wraps in MemeRaw"
    (let [r (resolve/resolve-number "0xFF" {:line 1 :col 1})]
      (is (forms/raw? r))
      (is (= 255 (:value r)))
      (is (= "0xFF" (:raw r)))))
  (is (= 42N (resolve/resolve-number "42N" {:line 1 :col 1})))
  (is (= 1.5M (resolve/resolve-number "1.5M" {:line 1 :col 1})))
  (is (= 1/2 (resolve/resolve-number "1/2" {:line 1 :col 1})))))

(deftest resolve-number-invalid
  (is (thrown? #?(:clj Exception :cljs :default)
              (resolve/resolve-number "1/" {:line 1 :col 1}))))

;; ---------------------------------------------------------------------------
;; Strings
;; ---------------------------------------------------------------------------

(deftest resolve-string-basic
  (is (= "hello" (resolve/resolve-string "\"hello\"" {:line 1 :col 1})))
  (is (= "a\nb" (resolve/resolve-string "\"a\\nb\"" {:line 1 :col 1}))))

;; ---------------------------------------------------------------------------
;; Characters
;; ---------------------------------------------------------------------------

(deftest resolve-char-basic
  (is (= \a (resolve/resolve-char "\\a" {:line 1 :col 1})))
  (is (= \newline (resolve/resolve-char "\\newline" {:line 1 :col 1})))
  (is (= \space (resolve/resolve-char "\\space" {:line 1 :col 1}))))

;; ---------------------------------------------------------------------------
;; Regex
;; ---------------------------------------------------------------------------

(deftest resolve-regex-basic
  (let [r (resolve/resolve-regex "#\"abc\"" {:line 1 :col 1})]
    (is (instance? #?(:clj java.util.regex.Pattern :cljs js/RegExp) r))
    (is (= "abc" #?(:clj (.pattern ^java.util.regex.Pattern r) :cljs (.-source r))))))

(deftest resolve-regex-invalid
  (is (thrown? #?(:clj Exception :cljs :default)
              (resolve/resolve-regex "#\"[unclosed\"" {:line 1 :col 1}))))

;; ---------------------------------------------------------------------------
;; Auto-resolve keywords
;; ---------------------------------------------------------------------------

(deftest resolve-auto-keyword-deferred
  #?(:clj
     (testing "without resolver, emits read-string form on JVM"
       (let [form (resolve/resolve-auto-keyword "::foo" {:line 1 :col 1} nil)]
         (is (seq? form))
         (is (= 'clojure.core/read-string (first form)))
         (is (= "::foo" (second form)))))
     :cljs
     (testing "without resolver, errors on CLJS"
       (is (thrown-with-msg? js/Error #"resolve-keyword"
             (resolve/resolve-auto-keyword "::foo" {:line 1 :col 1} nil))))))

#?(:clj
(deftest resolve-auto-keyword-with-resolver
  (testing "with resolver, resolves at read time"
    (let [kw (resolve/resolve-auto-keyword "::foo" {:line 1 :col 1}
               #(clojure.core/read-string %))]
      (is (keyword? kw))
      (is (= :user/foo kw))))))

;; ---------------------------------------------------------------------------
;; Tagged literals (JVM only)
;; ---------------------------------------------------------------------------

#?(:clj
(deftest resolve-tagged-literal-basic
  (let [tl (resolve/resolve-tagged-literal 'mytag "data" {:line 1 :col 1})]
    (is (tagged-literal? tl))
    (is (= 'mytag (.-tag tl)))
    (is (= "data" (.-form tl))))))

;; ---------------------------------------------------------------------------
;; Error location wrapping
;; ---------------------------------------------------------------------------

(deftest resolve-errors-include-location
  (testing "invalid number error includes line/col"
    (let [e (try (resolve/resolve-number "1/" {:line 5 :col 10})
                 nil
                 (catch #?(:clj Exception :cljs :default) e e))]
      (is (some? e))
      (is (= 5 (:line (ex-data e))))
      (is (= 10 (:col (ex-data e))))))
  #?(:clj
  (testing "invalid char literal error includes line/col"
    (let [e (try (resolve/resolve-char "\\zzz" {:line 3 :col 7})
                 nil
                 (catch Exception e e))]
      (is (some? e))
      (is (= 3 (:line (ex-data e))))
      (is (= 7 (:col (ex-data e)))))))
  (testing "invalid regex error includes line/col"
    (let [e (try (resolve/resolve-regex "#\"[unclosed\"" {:line 2 :col 4})
                 nil
                 (catch #?(:clj Exception :cljs :default) e e))]
      (is (some? e))
      (is (= 2 (:line (ex-data e))))
      (is (= 4 (:col (ex-data e)))))))
