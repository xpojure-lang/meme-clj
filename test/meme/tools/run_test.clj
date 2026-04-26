(ns meme.tools.run-test
  "Tests for meme.tools.run — the generic, language-agnostic eval pipeline.

   The meme-specific wrapper is tested in m1clj-lang.run-test. These tests
   exercise the generic layer directly, covering :pre conditions, eval-fn
   exception propagation, prelude ordering, and run-file dispatch via
   resolve-run-fn."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [meme.tools.run :as trun]))

;; ---------------------------------------------------------------------------
;; Minimal stand-in parser and expander
;; ---------------------------------------------------------------------------

(defn- stub-run-fn
  "Pretend-parser: returns one form per line, each form being a symbol
   built from the (trimmed) line. Empty lines produce no form."
  [src _opts]
  {:forms (->> (str/split-lines src)
               (map str/trim)
               (remove empty?)
               (mapv symbol))})

(defn- identity-expand [forms _opts] forms)

(def ^:private base-opts
  {:run-fn stub-run-fn
   :expand-forms identity-expand})

;; ---------------------------------------------------------------------------
;; :pre condition failures
;; ---------------------------------------------------------------------------

(deftest run-string-requires-string-source
  (testing "non-string source trips :pre"
    (is (thrown? AssertionError (trun/run-string 42 base-opts)))
    (is (thrown? AssertionError (trun/run-string nil base-opts)))))

(deftest run-string-requires-run-fn
  (testing "missing :run-fn trips :pre"
    (is (thrown? AssertionError
                 (trun/run-string "a" (dissoc base-opts :run-fn))))))

(deftest run-string-requires-expand-fn
  (testing "missing :expand-forms trips :pre"
    (is (thrown? AssertionError
                 (trun/run-string "a" (dissoc base-opts :expand-forms))))))

;; ---------------------------------------------------------------------------
;; eval-fn: exception propagation and ordering
;; ---------------------------------------------------------------------------

(deftest eval-fn-exception-propagates
  (testing "an exception from :eval is surfaced, not swallowed"
    (let [boom (fn [_] (throw (ex-info "boom" {:from :eval-fn})))]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #"boom"
            (trun/run-string "a" (assoc base-opts :eval boom)))))))

(deftest eval-fn-throw-mid-sequence-halts-remaining
  (testing "when :eval throws on form N, forms after N are not evaluated"
    (let [seen (atom [])
          eval-fn (fn [form]
                    (if (= form 'boom)
                      (throw (ex-info "stop" {}))
                      (do (swap! seen conj form) form)))]
      (is (thrown? Exception
                   (trun/run-string "a\nboom\nc"
                                    (assoc base-opts :eval eval-fn))))
      ;; `a` was evaluated; `c` was not.
      (is (= ['a] @seen)))))

;; ---------------------------------------------------------------------------
;; Prelude evaluation
;; ---------------------------------------------------------------------------

(deftest prelude-evaluated-before-user-forms
  (testing "prelude forms run first, in order, before any user form"
    (let [seen (atom [])
          eval-fn (fn [form] (swap! seen conj form) nil)]
      (trun/run-string "u1\nu2"
                       (assoc base-opts
                              :eval eval-fn
                              :prelude ['p1 'p2]))
      (is (= ['p1 'p2 'u1 'u2] @seen)))))

(deftest prelude-passed-through-expand-forms
  (testing "prelude is expanded via :expand-forms just like user forms"
    (let [expand-calls (atom [])
          expand-fn (fn [forms _opts]
                      (swap! expand-calls conj (vec forms))
                      forms)]
      (trun/run-string "u1"
                       (assoc base-opts
                              :expand-forms expand-fn
                              :eval (constantly nil)
                              :prelude ['p1]))
      ;; Expand is called for the prelude; user forms flow through run-fn
      ;; (which the stub doesn't expand). The point is that the prelude
      ;; passes through the expander.
      (is (some #{['p1]} @expand-calls)))))

;; ---------------------------------------------------------------------------
;; Return value
;; ---------------------------------------------------------------------------

(deftest run-string-returns-last-eval-result
  (is (= :third
         (trun/run-string "a\nb\nc"
                          (assoc base-opts
                                 :eval (fn [form]
                                         (case form
                                           a :first
                                           b :second
                                           c :third)))))))

(deftest run-string-empty-source-returns-nil
  (is (nil? (trun/run-string "" base-opts))))

;; ---------------------------------------------------------------------------
;; run-file dispatch
;; ---------------------------------------------------------------------------

(deftest run-file-dispatches-via-resolve-run-fn
  (testing "when resolve-run-fn returns a lang-run, that is called with (src opts)"
    (let [tmp (doto (java.io.File/createTempFile "run-file-dispatch" ".txt")
                (.deleteOnExit))
          _ (spit tmp "hello\n")
          lang-called? (atom false)
          lang-run (fn [src _opts]
                     (reset! lang-called? true)
                     (is (= "hello\n" src))
                     :lang-result)
          resolve-run-fn (fn [path _opts]
                           (is (= (.getAbsolutePath tmp) path))
                           lang-run)]
      (is (= :lang-result
             (trun/run-file (.getAbsolutePath tmp) base-opts resolve-run-fn)))
      (is @lang-called?))))

(deftest run-file-falls-back-to-run-string
  (testing "when resolve-run-fn returns nil, falls back to the generic run-string pipeline"
    (let [tmp (doto (java.io.File/createTempFile "run-file-fallback" ".txt")
                (.deleteOnExit))
          _ (spit tmp "alpha\n")
          seen (atom [])
          opts (assoc base-opts :eval (fn [form] (swap! seen conj form) form))]
      (trun/run-file (.getAbsolutePath tmp) opts (constantly nil))
      (is (= ['alpha] @seen)))))

(deftest run-file-without-resolve-run-fn-uses-run-string
  (testing "the 2-arity run-file delegates straight to run-string"
    (let [tmp (doto (java.io.File/createTempFile "run-file-2arity" ".txt")
                (.deleteOnExit))
          _ (spit tmp "beta\n")
          seen (atom [])
          opts (assoc base-opts :eval (fn [form] (swap! seen conj form) form))]
      (trun/run-file (.getAbsolutePath tmp) opts)
      (is (= ['beta] @seen)))))

(deftest run-file-missing-file-throws
  (testing "a path that does not exist surfaces the slurp IOException"
    (is (thrown? java.io.FileNotFoundException
                 (trun/run-file "/nonexistent/path/to/nothing.txt" base-opts)))))
