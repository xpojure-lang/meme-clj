(ns beme.alpha.forms
  "Shared form-level predicates and constructors.
   Cross-stage contracts that both the parser and printer depend on."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Deferred auto-resolve keyword encoding
;;
;; When no :resolve-keyword fn is provided (the default on JVM), :: keywords
;; are emitted as (clojure.core/read-string "::foo") — a form that resolves
;; at eval time in the user's namespace. The printer must recognize this
;; encoding to round-trip :: keywords back to "::foo" text.
;; ---------------------------------------------------------------------------

(defn deferred-auto-keyword
  "Wrap a :: keyword string as a deferred eval form.
   Returns (clojure.core/read-string \"::foo\")."
  [raw]
  (list 'clojure.core/read-string raw))

(defn deferred-auto-keyword?
  "Is form a deferred auto-resolve keyword produced by the reader?"
  [form]
  (and (seq? form)
       (= 2 (count form))
       (= 'clojure.core/read-string (first form))
       (let [s (second form)]
         (and (string? s) (str/starts-with? s "::")))))

(defn deferred-auto-keyword-raw
  "Extract the raw :: keyword string from a deferred form.
   Caller must check deferred-auto-keyword? first."
  [form]
  (second form))
