(ns meme.tools.clj.resolve-test
  "Unit tests for meme.tools.clj.resolve: value resolution from raw token text."
  (:require [clojure.test :refer [deftest is testing]]
            [meme.tools.clj.forms :as forms]
            [meme.tools.clj.resolve :as resolve]))

;; ---------------------------------------------------------------------------
;; Numbers
;; ---------------------------------------------------------------------------

(deftest resolve-number-basic
  (is (= 42 (resolve/resolve-number "42" {:line 1 :col 1})))
  (is (= -1 (resolve/resolve-number "-1" {:line 1 :col 1})))
  (is (= 3.14 (resolve/resolve-number "3.14" {:line 1 :col 1}))))

#?(:clj
   (deftest resolve-number-formats
     (testing "hex wraps in CljRaw"
       (let [r (resolve/resolve-number "0xFF" {:line 1 :col 1})]
         (is (forms/raw? r))
         (is (= 255 (:value r)))
         (is (= "0xFF" (:raw r)))))
     (is (= 42N (resolve/resolve-number "42N" {:line 1 :col 1})))
     (is (= 42N (resolve/resolve-number "+42N" {:line 1 :col 1})))
     (is (= -42N (resolve/resolve-number "-42N" {:line 1 :col 1})))
     (is (= 1.5M (resolve/resolve-number "1.5M" {:line 1 :col 1})))
     (is (= 1/2 (resolve/resolve-number "1/2" {:line 1 :col 1})))
     (is (= 3/4 (resolve/resolve-number "+3/4" {:line 1 :col 1})))
     (is (= -3/4 (resolve/resolve-number "-3/4" {:line 1 :col 1})))))

(deftest resolve-number-invalid
  (is (thrown? #?(:clj Exception :cljs :default)
               (resolve/resolve-number "1/" {:line 1 :col 1}))))

;; ---------------------------------------------------------------------------
;; Strings
;; ---------------------------------------------------------------------------

(deftest resolve-string-basic
  (is (= "hello" (resolve/resolve-string "\"hello\"" {:line 1 :col 1})))
  (is (= "a\nb" (resolve/resolve-string "\"a\\nb\"" {:line 1 :col 1}))))

(deftest resolve-string-fast-path
  (testing "escape-free strings use fast path — result identical"
    (is (= "hello world" (resolve/resolve-string "\"hello world\"" {:line 1 :col 1})))
    (is (= "" (resolve/resolve-string "\"\"" {:line 1 :col 1})))
    (is (= "no escapes here" (resolve/resolve-string "\"no escapes here\"" {:line 1 :col 1}))))
  (testing "strings with escapes still resolve correctly"
    (is (= "a\tb" (resolve/resolve-string "\"a\\tb\"" {:line 1 :col 1})))
    (is (= "a\"b" (resolve/resolve-string "\"a\\\"b\"" {:line 1 :col 1})))
    (is (= "a\\b" (resolve/resolve-string "\"a\\\\b\"" {:line 1 :col 1})))))

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
     (testing "without resolver, emits CljAutoKeyword on JVM"
       (let [form (resolve/resolve-auto-keyword "::foo" {:line 1 :col 1} nil)]
         (is (forms/deferred-auto-keyword? form))
         (is (= "::foo" (forms/deferred-auto-keyword-raw form)))))
     :cljs
     (testing "without resolver, defers on CLJS (same as JVM)"
       (let [form (resolve/resolve-auto-keyword "::foo" {:line 1 :col 1} nil)]
         (is (forms/deferred-auto-keyword? form))
         (is (= "::foo" (forms/deferred-auto-keyword-raw form)))))))

#?(:clj
   (deftest resolve-auto-keyword-with-resolver
     (testing "with resolver, resolves at read time"
       (let [kw (resolve/resolve-auto-keyword "::foo" {:line 1 :col 1}
                                              #(clojure.core/read-string %))]
         (is (keyword? kw))
         (is (= :user/foo kw))))))

;; ---------------------------------------------------------------------------
;; Tagged literals
;; ---------------------------------------------------------------------------

(deftest resolve-tagged-literal-unknown-tag-falls-back
  ;; Unknown tags (no entry in *data-readers* or default-data-readers) produce
  ;; a TaggedLiteral on both JVM and CLJS so consumer code can decide.
  (let [tl (resolve/resolve-tagged-literal 'mytag "data" {:line 1 :col 1})]
    (is (tagged-literal? tl))
    (is (= 'mytag (:tag tl)))
    (is (= "data" (:form tl)))))

(deftest resolve-tagged-literal-inst
  ;; #inst is recognised by default on both platforms.
  (let [v (resolve/resolve-tagged-literal 'inst "2024-01-15T10:30:00.000-00:00"
                                          {:line 1 :col 1})]
    (is (instance? #?(:clj java.util.Date :cljs js/Date) v))))

(deftest resolve-tagged-literal-inst-invalid
  ;; Garbage-in-#inst surfaces as a meme error with location preserved.
  (let [e (try (resolve/resolve-tagged-literal 'inst "not-a-date" {:line 4 :col 9})
               nil
               (catch #?(:clj Exception :cljs :default) e e))]
    (is (some? e))
    (is (= 4 (:line (ex-data e))))
    (is (= 9 (:col (ex-data e))))))

(deftest resolve-tagged-literal-uuid
  ;; #uuid is recognised by default on both platforms.
  (let [v (resolve/resolve-tagged-literal 'uuid "550e8400-e29b-41d4-a716-446655440000"
                                          {:line 1 :col 1})]
    (is (instance? #?(:clj java.util.UUID :cljs cljs.core/UUID) v))))

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

;; ---------------------------------------------------------------------------
;; RT2-M10: Surrogate char literals (\uD800-\uDFFF) were accepted.
;; Clojure rejects them. Fix: surrogate range check in resolve-char.
;; ---------------------------------------------------------------------------

(deftest surrogate-char-literals-rejected
  (testing "\\uD800 (low surrogate start) is rejected"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"(?i)surrogate"
                          (resolve/resolve-char "\\uD800" {:line 1 :col 1}))))
  (testing "\\uDFFF (high surrogate end) is rejected"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"(?i)surrogate"
                          (resolve/resolve-char "\\uDFFF" {:line 1 :col 1}))))
  (testing "\\uD900 (mid-surrogate) is rejected"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"(?i)surrogate"
                          (resolve/resolve-char "\\uD900" {:line 1 :col 1}))))
  (testing "\\u0041 (A) still works"
    (is (some? (resolve/resolve-char "\\u0041" {:line 1 :col 1}))))
  (testing "\\uE000 (just above surrogate range) still works"
    (is (some? (resolve/resolve-char "\\uE000" {:line 1 :col 1})))))
