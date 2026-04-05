# Red Team Report Round 2: meme-clj Adversarial Analysis

**Date:** 2026-04-05
**Branch:** `fuzzer` (after round 1 fixes applied)
**Methodology:** 5 parallel adversarial agents testing 23 hypotheses across formatter correctness, whitespace sensitivity, escape sequences, discard/anon-fn edge cases, depth limit interactions, concurrency, REPL detection, printer modes, vendor roundtrip, and type coverage. Used nREPL (port 7889), clj-kondo, clojure-lsp, Babashka CLI, and code search.

---

## Executive Summary

Round 2 probed different attack surfaces from round 1. The formatter, printer modes, registry, concurrency model, and real-world vendor roundtrips are all solid. However, significant semantic divergences from Clojure were found in the scanner and reader — forms that Clojure rejects are silently accepted by meme, producing invalid output or wrong values.

| Severity | Count | Key Findings |
|----------|-------|--------------|
| **HIGH** | 2 | Consecutive `#_` semantics wrong; call nesting bypasses depth limit |
| **MEDIUM** | 4 | Keyword validation gaps (5 bugs); unterminated strings accepted; nested `#()` not rejected; `%0` accepted |
| **LOW** | 2 | Comments lost in data literals; Var crash in printer |
| **INFO** | 3 | Dogfood style drift; regex `=` quirk; `#_` at EOF lenient |

---

## Confirmed Bugs

### BUG-1: Consecutive `#_` discards only discard 1 form (HIGH)

Clojure semantics: N consecutive `#_` tokens discard N forms. Meme treats `#_ #_ a b c` as `discard(discard(a))` — a nested structure that discards only `a`.

```clojure
;; Meme:    (meme->forms "#_ #_ a b c") => [b c]   (WRONG)
;; Clojure: (read-string "[#_ #_ a b c]") => [c]    (CORRECT)
```

**Root cause:** The `#_` prefix parselet recursively parses its operand. When that operand is another `#_`, it nests rather than consuming sequentially. Clojure's reader uses an iterative read-and-discard loop.

**Impact:** Any code using consecutive discards for commenting out multiple forms will behave differently in meme vs Clojure.

---

### BUG-2: Call nesting bypasses the depth limit (HIGH)

The depth limit added in round 1 only works for nud-based nesting (vectors, prefix operators). Call nesting `f(f(f(...)))` bypasses it entirely — 1400+ nested calls cause uncaught `StackOverflowError`.

```clojure
;; 1000 nested calls succeed (should fail at 512):
(api/meme->forms (str (apply str (repeat 1000 "f(")) "x" (apply str (repeat 1000 ")"))))
;; => succeeds (WRONG — should error at depth 512)

;; ~1400 calls cause StackOverflowError (should be clean error)
```

**Root cause:** In `parse-expr` (`parser.cljc:371`), the depth counter is decremented *before* the `led` handler (call parselet) fires. The call parselet's recursive `parse-expr` call sees the already-decremented depth, so call nesting never accumulates depth.

**Fix:** Move the `vswap! dec` to after the led loop completes, not before it enters.

---

### BUG-3: Keyword validation missing — 5 invalid forms accepted (MEDIUM)

The keyword scanner (`consume-keyword` in `lexlets.cljc`) greedily consumes `symbol-char?` characters (which includes `:`) without post-consumption validation. These forms are accepted by meme but rejected by Clojure:

| Input | Meme result | Clojure |
|-------|-------------|---------|
| `:` | `(keyword "")` | "Invalid token: :" |
| `:foo:` | `:foo:` | "Invalid token: :foo:" |
| `:a::b` | `:a::b` | "Invalid token: :a::b" |
| `:::foo` | `MemeAutoKeyword` | "Invalid token: :::foo" |
| `:foo/` | `:foo/` | "Invalid token: :foo/" |

**Root cause:** `consume-keyword` in `lexlets.cljc:148` — no validation that:
- Name part is non-empty (bare `:`)
- Name doesn't end with `/` or `:`
- Exactly 1 or 2 leading colons (not 3+)
- No colons after the prefix

**Impact:** Code with these keywords passes meme's reader but produces invalid Clojure output via `to-clj`.

---

### BUG-4: Unterminated strings silently produce wrong values (MEDIUM)

```clojure
(api/meme->forms "\"hello")   ;; => ["hell"]  (WRONG — last char eaten as closing quote)
(api/meme->forms "\"a")       ;; => [""]      (WRONG — empty string)
```

**Root cause:** `consume-string` in `lexlets.cljc:109` — when the loop reaches EOF without finding a closing `"`, it returns `i` (the EOF position) instead of signaling an error. `resolve-string` then strips first and last characters (assuming both are quotes), silently truncating content.

**REPL impact:** `input-state` returns `:complete` for unterminated strings, so the REPL evaluates the wrong value instead of prompting for continuation.

---

### BUG-5: Nested `#()` not rejected (MEDIUM)

```clojure
;; Meme accepts (should reject):
(api/meme->forms "#(#(+(% %2)))")
;; => [(fn [] (fn [%1 %2] (+ %1 %2)))]

;; Clojure rejects: "Nested #()s are not allowed"
```

**Root cause:** The `:anon-fn` handler in `cst_reader.cljc` doesn't track nesting depth. The existing comment in `reader_test.cljc` notes this is known: "The experimental pipeline does not reject nested #() ��� it treats each #() independently."

---

### BUG-6: `%0` accepted as valid anonymous function parameter (MEDIUM)

```clojure
(api/meme->forms "#(+(%0 %1))")
;; => [(fn [%1] (+ %0 %1))]   (WRONG — %0 becomes free variable)
;; Clojure: "arg literal must be %, %& or %integer"
```

**Root cause:** `percent-param-type` in `forms.cljc` uses `\d+` regex which matches "0". Should require `[1-9]\d*`.

---

### BUG-7: Comments inside data literals silently dropped by formatter (LOW)

```clojure
;; Input:
{:a 1 ;; first
 :b 2}

;; After format-meme:
{:a 1 :b 2}   ;; comment lost
```

Affects maps, vectors, and sets. Comments in function bodies and between top-level forms are preserved correctly.

**Root cause:** The formatter operates on Clojure forms (which don't carry comments). CST-level comment extraction only handles top-level and body-position comments, not comments embedded within data literals.

---

### BUG-8: `forms->meme` crashes on Var objects (LOW)

```clojure
(api/forms->meme [#'clojure.core/inc])
;; => ClassCastException: Var cannot be cast to IObj
```

**Root cause:** `to-doc-form` in `printer.cljc:~242` calls `(with-meta form ...)` on a Var. Vars implement `IMeta` but not `IObj`. Needs an `IObj` guard before the `with-meta` call.

**Practical impact:** Low — Var objects rarely appear as data values (they're usually behind `#'` syntax which roundtrips fine as a `(var ...)` list form).

---

## Refuted Hypotheses (Codebase Strengths)

| ID | Hypothesis | Result | Notes |
|----|-----------|--------|-------|
| H1 | Formatter not idempotent | **REFUTED** | 66 form/width combos all idempotent |
| H2 | Format output doesn't re-parse | **REFUTED** | All 49 cases re-parse to same form |
| H4 | Extreme widths crash | **REFUTED** | Width 0/-1 properly rejected; width 1 works |
| H5 | Flat/canon diverge | **REFUTED** | 28 forms identical at large width |
| H6 | Zero-width chars break adjacency | **REFUTED** | All invisible chars handled safely |
| H7 | String escape gaps | **REFUTED** | All escapes match Clojure exactly |
| H8 | Char literal gaps | **REFUTED** | All char types match Clojure |
| H9 | Regex literal gaps | **REFUTED** | All edge cases handled correctly |
| H15 | Parser not thread-safe | **REFUTED** | Fresh engine per call; 250 concurrent parses pass |
| H17 | Printer mode divergence | **REFUTED** | Both modes produce correct output |
| H18 | Registry edge cases | **REFUTED** | All error paths well-defended |
| H19 | Formatter corrupts real-world patterns | **REFUTED** | 22 patterns at 5 widths all correct |
| H21 | Vendor regressions from round 1 fixes | **REFUTED** | 796 tests pass, 0 failures |
| H22 | clj->forms edge cases | **REFUTED** | All cases handled, `#=` properly blocked |

---

## Lower-Severity Observations

### OBS-1: Depth limit boundary is correct for nud-based nesting (INFO)
511 nested vectors succeed, 512 fail with clean error and location info. State leak check passed — API works normally after a depth error.

### OBS-2: Dogfood formatting disagrees with hand-written style (INFO)
All 15 `.meme` files differ from canonical formatter output. Formatting is idempotent (format of format = format) but disagrees with the project's hand-written style choices.

### OBS-3: Bare `#_` at EOF returns empty result instead of error (INFO)
Clojure throws "EOF while reading" for bare `#_`. Meme returns `[]`. Inside delimiters (`[1 2 #_]`), meme correctly errors.

### OBS-4: `%&` rest parameter works correctly (INFO, strength)
`#(apply + %&)` correctly generates `(fn [& %&] (apply + %&))`.

### OBS-5: Tagged literals produce `TaggedLiteral` by design (INFO, strength)
`#uuid` and `#inst` produce data objects, not resolved values. Consistent with "reader is a pure function" design.

---

## Recommended Priority

1. **Fix BUG-2** (call depth limit bypass) — the round 1 fix is incomplete; calls still cause SOE
2. **Fix BUG-1** (consecutive `#_` semantics) — semantic divergence from Clojure
3. **Fix BUG-3** (keyword validation) — invalid keywords pass through silently
4. **Fix BUG-4** (unterminated strings) — wrong values and broken REPL continuation
5. **Fix BUG-6** (`%0` acceptance) — simple regex fix
6. **Fix BUG-5** (nested `#()`) — known issue, lower priority
7. **Fix BUG-8** (Var crash) — add `IObj` guard
8. **Fix BUG-7** (comment loss) — design-level issue, harder to fix
