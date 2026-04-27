(ns meme.tools.clj.lex-test
  "Direct unit tests for meme.tools.clj.lex consume-fns and predicates.
   These functions are also covered indirectly via the grammar/parser path,
   but the layer's edge cases (named char literals, \\uXXXX/\\oNNN, invisible-
   char rejection, ::keyword, namespaced symbol slash handling) deserve
   targeted asserts so a regression in the consume layer can't slip through
   to the parser undetected."
  (:require [clojure.test :refer [deftest is testing]]
            [meme.tools.clj.lex :as lex]))

;; ---------------------------------------------------------------------------
;; consume-char-literal — pos points AT the backslash; returns end-pos.
;; ---------------------------------------------------------------------------

(deftest consume-char-literal-single
  (testing "\\a — single lowercase letter (with no follow-on letters): 2 chars"
    (is (= 2 (lex/consume-char-literal "\\a" 2 0))))
  (testing "\\1 — single non-letter char: 2 chars"
    (is (= 2 (lex/consume-char-literal "\\1" 2 0))))
  (testing "\\$ — single non-letter punctuation: 2 chars"
    (is (= 2 (lex/consume-char-literal "\\$" 2 0)))))

(deftest consume-char-literal-named
  (testing "\\newline — multi-letter named char"
    (is (= 8 (lex/consume-char-literal "\\newline" 8 0))))
  (testing "\\space"
    (is (= 6 (lex/consume-char-literal "\\space" 6 0))))
  (testing "\\tab"
    (is (= 4 (lex/consume-char-literal "\\tab" 4 0)))))

(deftest consume-char-literal-unicode
  (testing "\\u0041 — 4 hex digits"
    (is (= 6 (lex/consume-char-literal "\\u0041" 6 0))))
  (testing "\\uFEFF — uppercase hex"
    (is (= 6 (lex/consume-char-literal "\\uFEFF" 6 0))))
  (testing "\\u00g1 — invalid hex; consume trailing alphanums so resolve-char rejects it"
    (is (= 6 (lex/consume-char-literal "\\u00g1" 6 0))))
  (testing "\\uXYZW — no hex digits at all; only \\u consumed (back-compat)"
    (is (= 2 (lex/consume-char-literal "\\uXYZW" 6 0)))))

(deftest consume-char-literal-octal
  (testing "\\o12 — octal escape, 2 digits"
    (is (= 4 (lex/consume-char-literal "\\o12" 4 0))))
  (testing "\\o7 — octal escape, 1 digit"
    (is (= 3 (lex/consume-char-literal "\\o7" 3 0))))
  (testing "\\o377 — octal escape, max 3 digits"
    (is (= 5 (lex/consume-char-literal "\\o377" 5 0)))))

;; ---------------------------------------------------------------------------
;; consume-symbol — pos points AT the first symbol char; returns end-pos.
;; ---------------------------------------------------------------------------

(deftest consume-symbol-basic
  (testing "foo — bare symbol to EOF"
    (is (= 3 (lex/consume-symbol "foo" 3 0))))
  (testing "foo) — stops at delimiter"
    (is (= 3 (lex/consume-symbol "foo)" 4 0))))
  (testing "foo bar — stops at whitespace"
    (is (= 3 (lex/consume-symbol "foo bar" 7 0)))))

(deftest consume-symbol-namespaced
  (testing "foo/bar — slash inside symbol consumed"
    (is (= 7 (lex/consume-symbol "foo/bar" 7 0))))
  (testing "foo/bar/baz — multiple slashes consumed at lex layer (validity checked later)"
    (is (= 11 (lex/consume-symbol "foo/bar/baz" 11 0)))))

(deftest consume-symbol-rejects-invisible
  (testing "foo<U+200B>bar — zero-width space terminates the symbol"
    (let [s (str "foo" (char 0x200B) "bar")]
      (is (= 3 (lex/consume-symbol s (count s) 0)))))
  (testing "foo<U+FEFF>bar — BOM character terminates the symbol"
    (let [s (str "foo" (char 0xFEFF) "bar")]
      (is (= 3 (lex/consume-symbol s (count s) 0)))))
  (testing "foo<U+FE0F>bar — variation selector terminates the symbol"
    (let [s (str "foo" (char 0xFE0F) "bar")]
      (is (= 3 (lex/consume-symbol s (count s) 0))))))

;; ---------------------------------------------------------------------------
;; consume-keyword — pos points AT the leading colon; returns end-pos.
;; ---------------------------------------------------------------------------

(deftest consume-keyword-basic
  (testing ":foo"
    (is (= 4 (lex/consume-keyword ":foo" 4 0))))
  (testing ":foo) — stops at delimiter"
    (is (= 4 (lex/consume-keyword ":foo)" 5 0)))))

(deftest consume-keyword-auto-resolve
  (testing "::foo — auto-resolve form"
    (is (= 5 (lex/consume-keyword "::foo" 5 0))))
  (testing "::ns/foo — namespaced auto-resolve"
    (is (= 8 (lex/consume-keyword "::ns/foo" 8 0)))))

(deftest consume-keyword-namespaced
  (testing ":ns/foo — first slash allowed"
    (is (= 7 (lex/consume-keyword ":ns/foo" 7 0)))))

;; ---------------------------------------------------------------------------
;; consume-number — pos points AT the first digit/sign; returns end-pos.
;; ---------------------------------------------------------------------------

(deftest consume-number-basic
  (testing "42"
    (is (= 2 (lex/consume-number "42" 2 0))))
  (testing "3.14"
    (is (= 4 (lex/consume-number "3.14" 4 0))))
  (testing "1e10"
    (is (= 4 (lex/consume-number "1e10" 4 0))))
  (testing "0xFF — hex literal lexed as one number token"
    (is (= 4 (lex/consume-number "0xFF" 4 0))))
  (testing "1/2 — ratio"
    (is (= 3 (lex/consume-number "1/2" 3 0))))
  (testing "42) — stops at delimiter"
    (is (= 2 (lex/consume-number "42)" 3 0)))))

;; ---------------------------------------------------------------------------
;; consume-string — pos points AT the opening "; returns end-pos AFTER closing ".
;; ---------------------------------------------------------------------------

(deftest consume-string-basic
  (testing "\"abc\" — closing quote consumed"
    (is (= 5 (lex/consume-string "\"abc\"" 5 0))))
  (testing "\"a\\\"b\" — escaped quote does not terminate"
    (is (= 6 (lex/consume-string "\"a\\\"b\"" 6 0))))
  (testing "\"unterminated — runs to EOF"
    (is (= 13 (lex/consume-string "\"unterminated" 13 0)))))

;; ---------------------------------------------------------------------------
;; Predicates
;; ---------------------------------------------------------------------------

(deftest whitespace-recognition
  (testing "comma is whitespace"
    (is (lex/whitespace-char? \,)))
  (testing "space is whitespace"
    (is (lex/whitespace-char? \space)))
  (testing "tab is whitespace"
    (is (lex/whitespace-char? \tab)))
  (testing "letter is not whitespace"
    (is (not (lex/whitespace-char? \a))))
  (testing "newline is NOT whitespace (it's its own category)"
    (is (not (lex/whitespace-char? \newline)))))

(deftest symbol-start-rejects-invisible
  (testing "BOM cannot start a symbol"
    (is (not (lex/symbol-start? (char 0xFEFF)))))
  (testing "zero-width space cannot start a symbol"
    (is (not (lex/symbol-start? (char 0x200B)))))
  (testing "variation selector cannot start a symbol"
    (is (not (lex/symbol-start? (char 0xFE0F)))))
  (testing "regular letter can start a symbol"
    (is (lex/symbol-start? \a)))
  (testing "digit cannot start a symbol"
    (is (not (lex/symbol-start? \1))))
  (testing "delimiters cannot start a symbol"
    (is (not (lex/symbol-start? \()))
    (is (not (lex/symbol-start? \])))))
