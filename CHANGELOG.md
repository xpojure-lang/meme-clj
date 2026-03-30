# Changelog

All notable changes to meme-clj will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

### Fixed
- **defrecord-as-map bugs**: `MemeRaw`, `MemeSyntaxQuote`, and other AST node defrecords satisfy `(map? x)`, causing silent mishandling in `expand-sq`, `normalize-bare-percent`, `find-percent-params`, `pp`, and `max-percent-n`. All dispatch sites now guard with `forms/raw?`, `forms/syntax-quote?`, etc. before the `(map? form)` branch.
- **#?@ splicing**: `#?@(:clj [2 3])` inside a collection now correctly splices elements (`[1 #?@(:clj [2 3]) 4]` produces `[1 2 3 4]`, not `[1 [2 3] 4]`). Non-sequential splice values produce a clear error.

### Changed
- **Expander extracted**: Syntax-quote expansion (`expand-sq`, `expand-syntax-quotes`, `expand-forms`) moved from `meme.alpha.parse.reader` to new `meme.alpha.parse.expander` namespace. Re-exports in `reader` preserve backwards compatibility.
- **Shared utilities**: Metadata exclusion key set (`strip-internal-meta`) and `percent-param-type` extracted to `meme.alpha.forms` to prevent drift between reader and printer.

## [0.5.0-alpha] — 2025-03-30

Initial public alpha release.

### Added
- Three-stage pipeline: scan (tokenizer) -> group -> parse (reader)
- Printer and pretty-printer (width-aware, comment-preserving)
- Full Clojure syntax support: all special forms, reader macros, dispatch forms
- Syntactic transparency via `:meme/sugar` metadata preservation
- Syntax-quote preserved as AST node (`MemeSyntaxQuote`), expanded before eval
- Raw value wrapper (`MemeRaw`) for numbers/chars/strings with alternate notation
- `:read-cond :preserve` option for lossless reader conditional roundtrips
- REPL with multi-line input and `:incomplete` continuation protocol
- CLI (run, repl, convert, format) — self-hosted in `.meme`
- Cross-platform support: JVM, Babashka, ClojureScript
- Generative property-based tests (14 properties, 200-300 samples each)
- Vendor roundtrip tests against 7 real-world Clojure libraries
- Dogfood tests with clj-kondo semantic equivalence verification
- Clojars deployment via CI on version tags
