# Red Team Report: meme-clj Adversarial Analysis

**Date:** 2026-04-05
**Branch:** `fuzzer`
**Methodology:** 5 parallel adversarial agents testing 18 hypotheses across parser robustness, roundtrip fidelity, security, static analysis, and platform divergence. Used nREPL (port 7888), clj-kondo, clojure-lsp, Babashka CLI, and code search.

---

## Executive Summary

The meme-clj codebase is **generally robust**. The native reader is secure (no `read-string` delegation, tagged literals are lazy, no eval at parse time). Degenerate inputs, unicode, numeric edge cases, and CLI argument injection are all handled cleanly. Two real bugs were found, one dead-code design gap was confirmed by three independent agents, and several lower-severity observations were catalogued.

| Severity | Count | Key Findings |
|----------|-------|--------------|
| **HIGH** | 1 | Alias-qualified namespaced map roundtrip diverges unboundedly |
| **MEDIUM** | 3 | `max-parse-depth` dead code + SOE; `strip-shebang` \r bug; REPL catch inconsistency |
| **LOW** | 3 | Metadata trust, error leakage, test coverage gaps |
| **INFO** | 5 | Unterminated string design choice, UTF-16 columns, regex compile-not-match, etc. |

---

## Confirmed Bugs

### BUG-1: Alias-qualified namespaced maps diverge on roundtrip (HIGH)

**Hypothesis H9.** Each roundtrip of `#::alias{...}` adds an extra colon:

```
trip 0: #::foo{:bar 1 :baz 2}
trip 1: #:::foo{:bar 1 :baz 2}   (WRONG)
trip 2: #::::foo{:bar 1 :baz 2}
trip 3: #:::::foo{:bar 1 :baz 2}
```

**Root cause:** `cst_reader.cljc:305` stores `(str "::" ns-name)` = `"::foo"` in `:meme/ns`. `printer.cljc:347` prepends `"#:"`, producing `"#:" + "::foo" + "{"` = `"#:::foo{"`.

**Fix:** Either the reader should store `":foo"` (single colon) for alias mode, or the printer should detect the `::` prefix and emit `"#" + ns-str + "{"` instead of `"#:" + ns-str + "{"`.

**Impact:** Any meme code using alias-qualified namespaced maps (`#::alias{}`) will corrupt on format or roundtrip.

---

### BUG-2: `strip-shebang` silently discards entire file on `\r`-only line endings (MEDIUM)

**Hypothesis H18.** When a shebang line ends with bare `\r` (no `\n`), `strip-shebang` returns `""`, silently discarding all code after the shebang.

**Root cause:** `stages.cljc:77` uses `(str/index-of source "\n")` to find end of shebang. Bare `\r` returns nil, falling through to return `""`.

**Fix:**
```clojure
(let [nl (or (str/index-of source "\n")
             (str/index-of source "\r"))]
  ...)
```

**Impact:** Files originating from classic Mac systems or editors that use `\r`-only line endings would silently produce no output.

---

## Design Gaps

### GAP-1: `max-parse-depth` defined but never enforced (MEDIUM)

**Hypotheses H1 (parser), H15 (security), static analysis.** All three independent investigations confirmed: `max-parse-depth` (512) in `forms.cljc:134` is dead code. The parser (`parser.cljc:348`) and CST reader (`cst_reader.cljc:162`) recurse without depth tracking.

**Measured limits:**
- CST reader: StackOverflowError at ~990 depth
- Parser alone: SOE at ~2300 depth
- Vector nesting: SOE at ~2000 depth

CI mitigates with `-Xss4m`, but the error is an uncaught `StackOverflowError`, not a controlled `meme-error`.

**Recommendation:** Either wire `max-parse-depth` into the parser engine (check depth before recursing, throw `meme-error`) or remove the dead constant to avoid misleading maintainers.

### GAP-2: REPL catches `Exception` for parsing but `Throwable` for eval (MEDIUM)

**Static analysis finding.** In `tools/repl.clj`:
- `try-input-state` (line 23) and `try-parse` (line 33): catch `Exception`
- Eval loop (line 110): catches `Throwable`

A `StackOverflowError` during parsing kills the REPL process. During eval, it's caught and reported. Combined with GAP-1, deeply nested input crashes the REPL rather than showing an error.

### GAP-3: No unit tests for lexlets, parselets, tools.lexer, tools.run (LOW)

These namespaces are exercised indirectly through integration tests, but have no dedicated unit tests. `lexlets` in particular contains character-by-character parsing (`consume-string`, `consume-keyword`, `consume-number`) most vulnerable to off-by-one bugs.

### GAP-4: No direct test for `printer.to-doc` Doc tree output (LOW)

The printer's Doc tree is tested indirectly through formatter output, but a bug in Doc tree construction that produces correct output at one width could break at a different width.

---

## Refuted Hypotheses (Codebase Strengths)

| ID | Hypothesis | Result | Notes |
|----|-----------|--------|-------|
| H2 | Long input causes OOM/hang | **REFUTED** | Linear scaling: 100K tokens in 280ms |
| H3 | Pathological backtracking | **REFUTED** | All patterns handled in <2ms |
| H4 | Null bytes crash parser | **REFUTED** | Clean "invalid token" errors |
| H5 | Unicode breaks column tracking | **REFUTED** | Consistent UTF-16 code unit semantics |
| H7 | Reader sugar ambiguity | **REFUTED** | `:meme/sugar` metadata correctly distinguishes all pairs |
| H8 | Syntax-quote roundtrip lossy | **REFUTED** | All tested patterns roundtrip exactly |
| H10 | Reader conditional roundtrip | **REFUTED** | Works with `:read-cond :preserve` |
| H11 | Regex ReDoS at parse time | **REFUTED** | Regex compiled but not matched during parsing |
| H12 | Tagged literal code execution | **REFUTED** | Produces lazy `TaggedLiteral`, no function invocation |
| H14 | Integer overflow | **REFUTED** | Auto-promotion to BigInt, proper errors for invalid radix |
| H15 | Degenerate inputs crash | **REFUTED** | All 10 degenerate inputs produce clean results/errors |
| H16 | JVM vs Babashka divergence | **REFUTED** | Identical results across all tested types |
| H17 | CLI argument injection | **REFUTED** | All adversarial args handled gracefully |

---

## Lower-Severity Observations

### OBS-1: Printer trusts `:meme/meta-chain` over actual metadata (LOW)

**H6.** Programmatically constructed forms with a `:meme/meta-chain` that contradicts real metadata will print according to the chain, silently dropping the real metadata. Not a real-world issue (reader-produced forms are always consistent), but relevant for third-party code constructing forms with meme metadata.

### OBS-2: Error messages don't leak source context (LOW)

**H13.** Parser errors include only line/col and a generic message. `format-error` shows only the relevant source line (expected for developer tooling). No excessive leakage.

### OBS-3: Unterminated strings are silently accepted (INFO, intentional)

**H3 sub-finding.** `"abc` parses as `"ab"` — the tokenizer truncates rather than throws. Documented as a deliberate design choice for error-recovery tooling. Multiline unterminated strings absorb subsequent content silently.

### OBS-4: No `read-string` in parsing pipeline (INFO, strength)

The reader is a pure native implementation. `clojure.edn/read-string` is used only for EDN config loading in the registry. `clojure.core/read` is used in `clj->forms` with `*read-eval*` bound to `false`.

### OBS-5: `forms->clj` expands syntax quotes but `format-meme` does not (INFO, by design)

Asymmetric behavior — `format-meme` passes through `MemeSyntaxQuote` AST nodes. Correct for formatter use case, but output contains unexpanded records if consumed by non-meme systems.

### OBS-6: `api/meme->forms` doesn't expose `:read-cond :preserve` (INFO)

The public API always resolves reader conditionals at read time. Vendor roundtrip tests use the lower-level stages API for lossless behavior. Potential usability gap for library consumers.

---

## No Issues Found

- **Security:** No `read-string` delegation, no `eval` in parsing, no `load-string`. Tagged literals are lazy. Registry EDN loading uses safe `clojure.edn/read-string`.
- **CLI robustness:** All adversarial inputs (flag-like filenames, `/dev/null`, missing args, spaces) handled correctly.
- **Platform parity:** JVM and Babashka produce identical results. No `:bb` reader conditionals exist.
- **Numeric resolution:** BigInt auto-promotion, proper radix validation, CLJS overflow guards all work.
- **`.charAt` safety:** All calls in `lexlets.cljc` and `parser.cljc` have bounds checks.

---

## Recommended Priority

1. **Fix BUG-1** (alias namespaced map roundtrip) — data corruption on format
2. **Fix BUG-2** (shebang `\r` handling) — silent data loss
3. **Enforce or remove `max-parse-depth`** — dead code misleads maintainers
4. **Align REPL error handling** — catch `Throwable` in parse path too
5. Add unit tests for `lexlets`, `parselets` (optional, lower priority)
