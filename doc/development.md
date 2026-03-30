# Development

## Testing

```bash
bb test-meme           # Babashka example + fixture tests
clojure -X:test        # JVM unit tests
bb test-cljs           # ClojureScript tests (needs Node.js)
bb test-all            # All three suites
```

## Architecture

```
.meme file -> tokenizer -> parser -> Clojure forms -> eval
```

Pure-function reader and printer (`.cljc`), portable across JVM, Babashka, and ClojureScript. No runtime dependency. meme is a reader, not a language.

See also [Design Decisions](design-decisions.md) for rationale behind each choice.
