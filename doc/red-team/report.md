# Red Team Report — meme-clj

**Date:** 2026-04-05
**Branch:** classloader (commit 8f50315)
**Methodology:** Adversarial hypothesis generation and REPL-based verification across parser, value resolution, syntax-quote expansion, printer, loader, registry, CLI, and runtime. 71 hypotheses tested.
**Tools used:** clojure-mcp (nREPL), clojure-lsp, LSP/clj-kondo diagnostics, CLI, web search (CVE database), 3 parallel exploration agents.

---

## Executive Summary

The meme-clj codebase is well-hardened. Multiple red team rounds and fuzzing have addressed most common attack vectors. This round found **8 confirmed issues**, **6 plausible concerns**, and **57 refuted hypotheses**. The most critical finding is a resource exhaustion bug via unbounded `%N` parameters in anonymous functions. The loader's classpath trust model and the registry's TOCTOU race are the main security concerns.

---

## Confirmed Issues

### C1. `%N` parameter index allows N up to ~10^11 — OOM via `build-anon-fn-params` [HIGH]

**Hypothesis:** A `#()` form with `%99999999999` triggers allocation of ~10^11 element vector.

**Code path:** `forms.cljc:176-177` — the `percent-param-type` guard checks `(<= (count n) 12)` (string length), allowing numeric values up to 99,999,999,999. `build-anon-fn-params` at `forms.cljc:273-281` calls `(range 1 (inc max-n))` then `mapv`, attempting to allocate N symbols.

**Verification:**
```clojure
(api/meme->forms "#(+(%10000 1))")
;; => (fn [%1 %2 ... %10000] (+ %10000 1)) — 10K params in 2ms
;; %99999999999 would attempt ~100 billion element vector → instant OOM
```

**Fix:** Add a numeric value cap (e.g. `(<= N 20)`) in `percent-param-type` or `build-anon-fn-params`. Clojure itself limits `%` params to `%1` through `%20` in practice.

---

### C2. Registry `register!` TOCTOU — concurrent registrations bypass conflict check [MEDIUM]

**Hypothesis:** Two threads registering the same extension concurrently both pass the conflict check.

**Code path:** `registry.clj:148-177` — conflict check reads `@registry` (line 169), then `swap!` writes (line 177). Not atomic.

**Verification:**
```clojure
;; Two threads register :racer0 and :racer1 both with ".conflict"
;; Result: {:results [[:ok 0] [:ok 1]], :both-succeeded? true}
```

Both succeed. `resolve-by-extension` returns whichever `some` finds first — non-deterministic.

**Fix:** Move the conflict check inside the `swap!` callback function.

---

### C3. Loader namespace hijacking — .meme file on classpath shadows any namespace [HIGH]

**Hypothesis:** A `.meme` file at `resources/clojure/string.meme` would be loaded instead of `clojure.string`.

**Code path:** `loader.clj:17-26` — `find-lang-resource` uses `io/resource` with no namespace allowlist. Any `.meme` file matching a namespace path on any classpath entry (including JAR dependencies) will be executed instead of the real namespace for any `require` that occurs after `install!`.

**Mitigation:** Already-loaded namespaces (like `clojure.core`, `clojure.string`) are cached by the JVM classloader. Only lazily-required namespaces after `install!` are vulnerable.

**Fix:** Consider a namespace prefix allowlist, or only intercept namespaces matching a configured set of extensions/paths.

---

### C4. `.meme` code can uninstall the loader mid-execution [MEDIUM]

**Hypothesis:** A running `.meme` file can call `loader/uninstall!()`.

**Verification:**
```clojure
;; Run meme code that calls loader/uninstall!
;; Result: {:loader-uninstalled-by-meme? true}
```

Subsequent `require` calls fall back to Clojure's original loader — `.meme` namespaces stop working.

**Fix:** This is inherent to the no-sandbox design. Document as a known property.

---

### C5. `clj->meme` stack overflow on deeply nested Clojure input [MEDIUM]

**Hypothesis:** `clj->forms` delegates to Clojure's reader which has no depth limit.

**Verification:**
```clojure
(api/clj->meme (str (apply str (repeat 10000 "(")) "x" (apply str (repeat 10000 ")"))))
;; => StackOverflowError
```

The meme pipeline protects its own parser (max-depth 512) but `clj->forms` uses Clojure's recursive reader.

**Fix:** Wrap `clj->forms` in a thread with a stack size limit, or add a depth check post-read.

---

### C6. `:/foo` keyword accepted by meme but rejected by Clojure [LOW]

**Hypothesis:** Keyword with leading slash diverges from Clojure's reader.

**Verification:**
```clojure
(api/meme->forms ":/foo") ;; => [:/foo]
(read-string ":/foo")     ;; => Exception: "Invalid token: :/foo"
```

**Fix:** Add validation in `cst_reader.cljc` keyword handling to reject `:/` prefix.

---

### C7. `#::{}` (bare auto-resolve namespaced map) rejected by meme but accepted by Clojure [LOW]

**Hypothesis:** Clojure accepts `#::{}` (resolves to current namespace).

**Verification:**
```clojure
(api/meme->forms "#::{:a 1}")
;; => Error: "Auto-resolve namespaced map #::{} requires a namespace alias"
(read-string "#::{:a 1}")
;; => #:user{:a 1}
```

**Fix:** Support bare `#::{}` by resolving to current namespace when alias is empty.

---

### C8. Double-shebang — second `#!` line becomes position 0 after stripping [LOW]

**Hypothesis:** A file with two shebang lines causes the second to be treated as a shebang atom.

**Verification:**
```clojure
(api/meme->forms "#!/usr/bin/env bb\n#!/not-a-shebang\nprintln(42)")
;; => Error: "Unknown atom type: :shebang"
;; strip-shebang removes line 1, line 2's #! is now at pos 0
```

The CST reader doesn't handle `:shebang` atom nodes. Edge case but confusing error message.

**Fix:** Either strip all leading shebang lines, or handle `:shebang` in CST reader by ignoring it.

---

## Plausible Concerns

### P1. EDN `--lang` flag as arbitrary code execution vector [HIGH]

`load-edn` resolves qualified symbols via `requiring-resolve`, triggering namespace loading from classpath. `--lang evil.edn` with `{:run some.malicious/fn}` runs arbitrary code. Documented as "H5 WARNING" in source but no user confirmation in CLI.

### P2. EDN `:run` absolute path bypasses `..` traversal guard [MEDIUM]

The `".."` check in `load-edn` (`registry.clj:139`) only blocks relative traversal. Absolute paths like `/etc/passwd` pass through. The file content would fail to parse as meme, but it would be read.

### P3. 1e99999 roundtrip produces nested MemeRaw [LOW]

`1e99999` resolves to `MemeRaw{:value ##Inf, :raw "1e99999"}`. The printer outputs `1e99999`, but re-parsing wraps in another `MemeRaw` layer. `(:value (first forms))` is `##Inf` first time, `MemeRaw{:value ##Inf ...}` second time.

### P4. Infinite lazy seq prints as call expression [LOW]

`(forms->meme [(repeat 42)])` prints as `42(42 42 42 ...)` — first element becomes call head. Bounded by `*print-length*` / 10000 so no hang, but semantically wrong output.

### P5. Memory exhaustion via large string literals [MEDIUM]

No size limit on string literals. A `.meme` file with a multi-GB string literal will consume proportional heap. Linear time, but no guard.

### P6. CLI `slurp`/`spit` TOCTOU in format/convert [LOW]

`process-files` reads then writes files non-atomically. In `--check` mode, reads twice. Concurrent file modification can cause data loss or incorrect check results.

---

## Refuted Hypotheses (57)

| # | Hypothesis | Why Refuted |
|---|-----------|-------------|
| H1 | Empty string crashes parser | Returns `[]` correctly |
| H2 | Null byte in source crashes | Errors cleanly: "Unexpected token: :invalid" |
| H3 | BOM mid-file crashes | Errors cleanly (only valid at pos 0) |
| H4 | Zero-width space bypasses adjacency | ZWJ is not a symbol char — errors at scanner level |
| H6 | Ratio 0/0 crashes | "Invalid number 0/0 — Divide by zero" |
| H7 | Radix 0r, 1r, 37r crash | All produce "Radix out of range" with correct location |
| H8 | 100K symbol name causes slow parse | 98ms — linear, acceptable |
| H9 | 6-level nested syntax-quote crashes | Parses correctly to MemeSyntaxQuote |
| H10 | \o400 octal char crashes | "Octal character out of range" |
| H11 | \uD800/\uDFFF surrogate chars crash | "code point is in the surrogate range" |
| H13 | \uFFGG unicode escape crashes | "Invalid unicode escape" |
| H14 | Regex with unbalanced parens crashes | "Invalid regex — Unclosed group" |
| H15 | User :meme/sugar metadata fools printer | `instance?` check distinguishes records from plain maps |
| H16 | User :meme/order on non-set value | Printer ignores metadata on non-set types |
| H17 | Plain map fools `syntax-quote?` | `instance?` check on MemeSyntaxQuote correctly rejects |
| H18 | Very large %N (>Long.MAX_VALUE) | "Invalid % parameter" — string length guard works |
| H19 | 1e99999 crashes resolver | Produces MemeRaw wrapping ##Inf — no crash |
| H20 | -0.0 roundtrip loses negative | Preserves negative zero correctly |
| H21 | ##NaN roundtrip breaks | Prints as "##NaN", re-parses correctly |
| H22 | 10 concurrent register! calls crash | All succeed without error |
| H24 | Loader shadows clojure.core | `find-lang-resource` returns nil — no .meme on classpath |
| H25 | Parse error in require corrupts loader | Loader continues working after caught error |
| H27 | Width 0 crashes formatter | "Layout width must be a positive number" |
| H28 | Negative width crashes formatter | Same validation catches it |
| H29 | String octal escapes \377/\400 | \377 accepted, \400 rejected correctly |
| H30 | :foo/bar/baz (multi-slash keyword) | Accepted — matches Clojure's behavior |
| H32 | 50K comment lines cause O(n²) | 163ms — linear, trivia accumulation is O(n) |
| H33 | #() with %1 through %500 | 12ms — parameter discovery is O(n) |
| H34 | ReDoS in resolve.cljc | No vulnerable patterns — all linear/anchored |
| H35 | .meme code modifies registry | Syntax makes it hard; registry unaffected in test |
| H37 | :meme/sugar in syntax-quote | strip-internal-meta correctly removes internal keys |
| H38 | forms->clj with nil/true/false | Outputs "nil\ntrue\nfalse" correctly |
| H39 | Unterminated string at EOF | "Unterminated string literal" with :incomplete true |
| H40 | Reader-cond :preserve roundtrip | Perfect roundtrip including nested calls |
| H42 | Empty #() body | "#() requires a body" with correct location |
| H43 | 200-deep nested maps | 4.8ms — linear, duplicate check is per-level |
| H44 | Comma-separated values | Commas are whitespace — `[1,2,3]` → `[1 2 3]` |
| H45 | Alternating call+vector nesting | 170 levels works fine |
| H46 | #inst at EOF | "Unexpected end of input" |
| H48 | clj->meme with #'foo | Produces "var(foo)" correctly |
| H50 | Reader-cond odd forms | Produces `[1]` (last value has no pair, filtered) |
| H51 | Backslash at EOF | "Invalid character literal" with correct location |
| H52 | forms->meme with Java objects | Falls back to `pr-str` — no crash |
| H53 | clj->meme with #= eval reader | "EvalReader not allowed when *read-eval* is false" |
| H54 | Set ordering roundtrip | :meme/order metadata preserves element order |
| H55 | ::foo in meme->clj | Outputs "::foo" correctly (deferred resolution) |
| H57 | MemeAutoKeyword expansion | Uses `clojure.core/read-string` on JVM correctly |
| H58 | Whitespace/comment-only files | All return `[]` — no crash |
| H59 | #_ #_ at EOF with no targets | Returns `[]` — double discard consumes error nodes |
| H60 | clj->meme->clj roundtrip | Perfect identity for standard Clojure forms |
| H61 | Concurrent parse calls | 100 parallel parses — 0 errors (engine per-call) |
| H62 | Depth limit 511 with prefix chains | Works correctly (depth 511 < 512 limit) |
| H63 | Only discard nodes (no forms) | Returns `[]` |
| H64 | MemeAutoKeyword in forms->clj | Prints as "::foo" correctly |
| H65 | ::str/join auto-resolve keyword | Produces MemeAutoKeyword record correctly |
| H67 | Gensym collision across parse calls | Different gensyms each call |
| H69 | Clojure version CVE check | Running 1.12.4 — patched |

---

## Dead Code (confirmed via LSP `findReferences`)

| Symbol | File:Line | References |
|--------|-----------|-----------|
| `nud-atom` | `parser.cljc:235` | 0 callers (self only) |
| `led-call` | `parser.cljc:299` | 0 callers (self only) |
| `led-infix` | `parser.cljc:306` | 0 callers (self-ref only) |
| `DocIfBreak` | `render.cljc:26` | Handled in layout/fits but never constructed |

These exist as extension points for future languages reusing the parser engine.

---

## Recommendations (Priority Order)

1. **Cap `%N` parameter index** at a reasonable maximum (e.g. 20) in `forms.cljc` — prevents OOM from `#(%99999999999)`.
2. **Make registry conflict check atomic** — move the `doseq` extension-conflict check inside the `swap!` callback.
3. **Add namespace prefix restriction to loader** — at minimum, disallow intercepting `clojure.*` and `java.*` namespaces.
4. **Guard `clj->forms` against deep nesting** — wrap Clojure's reader call with a stack depth check or thread with bounded stack.
5. **Reject `:/foo` keywords** — add validation for leading-slash keywords to match Clojure's reader.
6. **Support `#::{}` bare auto-resolve** — match Clojure's behavior (resolve to current namespace).
7. **Handle `:shebang` atom type in CST reader** — either skip it or produce a better error message.
8. **Document security model explicitly** — the no-sandbox design, loader trust model, and EDN loading as code execution should be in a SECURITY.md.

---

## Test Methodology

- **REPL testing:** 71 adversarial inputs evaluated via clojure-mcp nREPL
- **Static analysis:** LSP/clj-kondo diagnostics, `findReferences` for dead code
- **Concurrent testing:** `CyclicBarrier`-based race condition probes on registry and parser
- **Performance testing:** Timed parsing of 100K symbols, 50K comment lines, 10K-param anonymous functions
- **CVE research:** Verified Clojure 1.12.4 is patched for CVE-2024-22871
- **Agent exploration:** 3 parallel code-explorer agents analyzing parser, value resolution, and security surfaces
