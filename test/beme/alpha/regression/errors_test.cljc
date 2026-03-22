(ns beme.alpha.regression.errors-test
  "Scar tissue: error infrastructure and resolve error-wrapping regression tests.
   Every test here prevents a specific bug from recurring."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [beme.alpha.errors]
            [beme.alpha.core :as core]))

;; ---------------------------------------------------------------------------
;; B5/B6: source-context nil/empty guards.
;; Bug: source-context threw NPE on nil source, returned truthy "" on empty.
;; ---------------------------------------------------------------------------

(deftest source-context-edge-cases
  (testing "nil source returns nil"
    (is (nil? (beme.alpha.errors/source-context nil 1))))
  (testing "empty source returns nil"
    (is (nil? (beme.alpha.errors/source-context "" 1))))
  (testing "blank source returns nil"
    (is (nil? (beme.alpha.errors/source-context "   " 1)))))

;; ---------------------------------------------------------------------------
;; Bug: error gutter misalignment when secondary line has more digits.
;; ---------------------------------------------------------------------------

(deftest error-gutter-width-spans-all-lines
  (testing "gutter width accommodates secondary line numbers wider than primary"
    (let [source (str/join "\n" (concat (repeat 999 "x") ["error-line"]))
          e (ex-info "test error"
              {:line 5 :col 1
               :secondary [{:line 1000 :col 1 :label "related"}]})
          result (beme.alpha.errors/format-error e source)]
      (is (re-find #"   5 \|" result) "primary line padded to 4-wide gutter")
      (is (re-find #"1000 \|" result) "secondary line fits in gutter"))))

;; ---------------------------------------------------------------------------
;; read-string errors include source location context.
;; ---------------------------------------------------------------------------

#?(:clj
(deftest read-string-errors-include-location
  (testing "malformed number includes location"
    (let [e (try (core/beme->forms "1/")
                 nil
                 (catch Exception e e))]
      (is (some? e))
      (is (= 1 (:line (ex-data e))))
      (is (= 1 (:col (ex-data e))))
      (is (re-find #"Invalid number" (ex-message e)))))
  (testing "malformed regex includes location"
    (let [e (try (core/beme->forms "#\"[unclosed\"")
                 nil
                 (catch Exception e e))]
      (is (some? e))
      (is (= 1 (:line (ex-data e))))
      (is (re-find #"Invalid regex" (ex-message e)))))))

;; ---------------------------------------------------------------------------
;; Opaque-form read-string errors must include beme source location.
;; ---------------------------------------------------------------------------

#?(:clj
(deftest opaque-form-errors-include-location
  (testing "malformed namespaced map has :line/:col in ex-data"
    (let [ex (try (core/beme->forms "#:ns{:a}")
                  (catch Exception e e))]
      (is (instance? clojure.lang.ExceptionInfo ex))
      (is (:line (ex-data ex)))
      (is (:col (ex-data ex)))
      (is (re-find #"Invalid namespaced map" (ex-message ex)))))))

;; ---------------------------------------------------------------------------
;; B1: tagged-literal CLJS guard.
;; Bug: tagged-literal function called unconditionally on CLJS, producing a
;; raw ReferenceError instead of a helpful beme error.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; B10: #=() read-eval in opaque forms.
;; Bug: host-read and host-read-with-opts called clojure.core/read-string
;; without binding *read-eval* to false, so #=() inside syntax-quote,
;; namespaced maps, or reader conditionals executed at read time.
;; Fix: bind *read-eval* false in both host-read and host-read-with-opts.
;; ---------------------------------------------------------------------------

#?(:clj
(deftest read-eval-blocked-in-opaque-forms
  (testing "#=() blocked in syntax-quote"
    (is (thrown? Exception (core/beme->forms "`(#=(+ 1 2))"))))
  (testing "#=() blocked in namespaced map"
    (is (thrown? Exception (core/beme->forms "#:ns{:k #=(+ 1 2)}"))))
  (testing "#=() blocked in reader conditional"
    (is (thrown? Exception (core/beme->forms "#?(:clj #=(+ 1 2))"))))))

#?(:cljs
(deftest tagged-literal-cljs-error
  (testing "#uuid on CLJS throws beme error, not ReferenceError"
    (is (thrown-with-msg? js/Error #"not supported in ClojureScript"
          (core/beme->forms "#uuid \"550e8400-e29b-41d4-a716-446655440000\""))))))
