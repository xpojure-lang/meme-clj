(ns meme.tools.pratt.trivia
  "Trivia transformer for token streams.

   Trivia = syntactically insignificant tokens: whitespace, newlines, comments,
   BOM, shebangs. The tokenizer produces these as first-class tokens.
   This transformer attaches them to adjacent semantic tokens as structured metadata,
   preserving their identity (type, raw text, position) rather than collapsing
   them into an opaque string.

   Input:  flat token stream from tokenizer (semantic + trivia interleaved)
   Output: semantic tokens only, each with :trivia/before (vector of preceding trivia tokens)
           + :trivia/after on the vector metadata for trailing trivia")

;; ---------------------------------------------------------------------------
;; Classification
;; ---------------------------------------------------------------------------

(def default-trivia-types
  "Minimal set: tokens that are noise for all consumers.
   Comments, BOM, and shebang are NOT included — they carry meaning
   for formatters, documentation tools, and file-level processing.
   Pipeline callers can extend this set as needed."
  #{:whitespace :newline})

(defn trivia?
  "Is this token trivia? Uses the provided set, or default-trivia-types."
  ([tok] (trivia? tok default-trivia-types))
  ([tok trivia-types]
   (contains? trivia-types (:type tok))))

;; ---------------------------------------------------------------------------
;; Transformer
;; ---------------------------------------------------------------------------

(defn attach-trivia
  "Walk the token stream and attach trivia to semantic tokens.

   Each semantic token gets :trivia/before — a vector of the trivia tokens
   that immediately precede it. Trailing trivia (after the last semantic token)
   is stored as :trivia/after metadata on the returned vector.

   The trivia tokens retain their full structure (:type, :raw, :line, :col, :offset)
   so downstream consumers can distinguish comments from whitespace, preserve
   line breaks, etc."
  ([tokens] (attach-trivia tokens default-trivia-types))
  ([tokens trivia-types]
   (loop [i 0
          trivia-acc []
          result (transient [])]
     (if (>= i (count tokens))
       ;; Done — attach trailing trivia as metadata
       (let [v (persistent! result)]
         (if (seq trivia-acc)
           (with-meta v {:trivia/after trivia-acc})
           v))
       (let [tok (nth tokens i)]
         (if (trivia? tok trivia-types)
           ;; Accumulate trivia
           (recur (inc i)
                  (conj trivia-acc tok)
                  result)
           ;; Semantic token — attach accumulated trivia
           (let [tok' (if (seq trivia-acc)
                        (assoc tok :trivia/before trivia-acc)
                        tok)]
             (recur (inc i)
                    []
                    (conj! result tok')))))))))

;; ---------------------------------------------------------------------------
;; Convenience
;; ---------------------------------------------------------------------------

(defn trivia->ws
  "Collapse trivia tokens into a single whitespace string.
   Convenience for adapters that need the classic :ws format."
  [trivia-tokens]
  (when (seq trivia-tokens)
    (apply str (map :raw trivia-tokens))))

(defn semantic-count
  "Count semantic (non-trivia) tokens in a stream."
  ([tokens] (semantic-count tokens default-trivia-types))
  ([tokens trivia-types]
   (count (remove #(trivia? % trivia-types) tokens))))
