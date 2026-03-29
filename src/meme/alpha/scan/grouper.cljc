(ns meme.alpha.scan.grouper
  "Token grouping stage: collapses opaque regions (reader conditionals,
   namespaced maps, syntax-quote with brackets) from flat token sequences
   into single composite tokens.

   This replaces the tokenizer's read-balanced-raw by operating on
   already-tokenized input where bracket matching is trivial — strings,
   chars, and comments are already individual tokens."
  (:require [meme.alpha.errors :as errors]
            [meme.alpha.scan.source :as source]))

;; ---------------------------------------------------------------------------
;; Bracket matching on tokens (trivial — no string/char/comment handling)
;; ---------------------------------------------------------------------------

(defn- opening-type? [typ]
  (#{:open-paren :open-bracket :open-brace :open-set :open-anon-fn} typ))

(defn- closing-type? [typ]
  (#{:close-paren :close-bracket :close-brace} typ))

(defn- collect-balanced-tokens
  "Starting at index i (which must be an opening delimiter token),
   find the index one past the matching close. Returns end-index,
   or nil if the tokens are exhausted without finding a match.
   Counts opening/closing token types for depth tracking only — does
   not validate bracket type pairing (e.g. ( vs ]). Mismatched pairs
   are caught downstream by the host reader when resolving opaque forms."
  [tokens i]
  (let [n (count tokens)]
    (loop [j i depth 0]
      (if (>= j n)
        nil
        (let [typ (:type (nth tokens j))]
          (cond
            (opening-type? typ) (recur (inc j) (inc depth))
            (closing-type? typ) (if (= depth 1) (inc j) (recur (inc j) (dec depth)))
            :else               (recur (inc j) depth)))))))

;; ---------------------------------------------------------------------------
;; Source text extraction
;; ---------------------------------------------------------------------------

(def ^:private line-col->offset source/line-col->offset)

(defn- extract-source-range
  "Extract the source text spanning from the first token's start position
   to the last token's end position. Uses :offset/:end-offset directly (O(1))
   when available; falls back to line-col->offset."
  [tokens source]
  (let [first-tok (first tokens)
        last-tok (peek tokens)
        start (or (:offset first-tok)
                  (line-col->offset source (:line first-tok) (:col first-tok)))
        end (or (:end-offset last-tok)
                (if (and (:end-line last-tok) (:end-col last-tok))
                  (line-col->offset source (:end-line last-tok) (:end-col last-tok))
                  (+ (or (:offset last-tok)
                         (line-col->offset source (:line last-tok) (:col last-tok)))
                     (count (:value last-tok)))))]
    (if (<= end (count source))
      (subs source start end)
      ;; Fallback: concatenate token values (lossy — drops inter-token whitespace).
      ;; This can't trigger in normal operation: offsets from the tokenizer always
      ;; land within source bounds. Defensive only; prefer degraded output
      ;; (host reader will error with location) over crashing here.
      (apply str (map :value tokens)))))

(defn- end-loc
  "End position of the last token in a balanced group.
   Returns {:end-line l :end-col c :end-offset o} using the token's own
   end fields when available, falling back to start + value length."
  [last-tok]
  (if (and (:end-line last-tok) (:end-col last-tok))
    (cond-> {:end-line (:end-line last-tok) :end-col (:end-col last-tok)}
      (:end-offset last-tok) (assoc :end-offset (:end-offset last-tok)))
    (cond-> {:end-line (:line last-tok) :end-col (+ (:col last-tok) (count (:value last-tok)))}
      (:offset last-tok) (assoc :end-offset (+ (:offset last-tok) (count (:value last-tok)))))))

(defn- check-balanced!
  "Throw :incomplete error if collect-balanced-tokens returned nil
   (exhausted all tokens without finding a matching closer).
   This lets the REPL prompt for more input instead of reporting an error."
  [end-i marker-tok]
  (when-not end-i
    (errors/meme-error
      (str "Unclosed " (:value marker-tok) " — expected closing delimiter but reached end of input")
      (assoc (select-keys marker-tok [:line :col]) :incomplete true))))

;; ---------------------------------------------------------------------------
;; Group pass
;; ---------------------------------------------------------------------------

(defn group-tokens
  "Process a flat token vector, collapsing opaque regions into single tokens.
   Marker tokens (:reader-cond-start, :namespaced-map-start, :syntax-quote-start)
   followed by balanced delimiters are collapsed into the corresponding
   -raw composite tokens.

   source is the original source text, used for reconstructing raw values."
  [tokens source]
  (let [n (count tokens)]
    (loop [i 0 out (transient [])]
      (if (>= i n)
        (persistent! out)
        (let [tok (nth tokens i)
              typ (:type tok)]
          (case typ
            ;; Reader conditional: #?(...) or #?@(...) — pass through for native parsing
            :reader-cond-start
            (recur (inc i) (conj! out tok))

            ;; Namespaced map: #:ns{...} — pass through for native parsing
            :namespaced-map-start
            (recur (inc i) (conj! out tok))

            ;; Syntax-quote with brackets: `(...), `[...], `{...}
            :syntax-quote-start
            (let [next-i (inc i)]
              (if (and (< next-i n) (opening-type? (:type (nth tokens next-i))))
                (let [end-i (collect-balanced-tokens tokens next-i)
                      _ (check-balanced! end-i tok)
                      balanced-toks (subvec tokens next-i end-i)
                      raw (str (:value tok) (extract-source-range balanced-toks source))
                      end (end-loc (peek balanced-toks))]
                  (recur end-i (conj! out (merge tok end {:type :syntax-quote-raw :value raw}))))
                (recur (inc i) (conj! out tok))))

            ;; All other tokens pass through unchanged
            (recur (inc i) (conj! out tok))))))))
