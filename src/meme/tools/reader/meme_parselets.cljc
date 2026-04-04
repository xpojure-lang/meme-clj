(ns meme.tools.reader.meme-parselets
  "Meme-specific parselets for the Pratt parser.

   These are the custom parse functions that handle meme's unique constructs.
   Generic parselets (nud-atom, nud-prefix, etc.) come from the parser engine.
   This file holds only the meme-specific logic that can't be expressed
   as a factory call."
)

;; ---------------------------------------------------------------------------
;; Meme-specific predicates
;; ---------------------------------------------------------------------------

(defn no-trivia?
  "Call predicate: ( is a call only when adjacent (no trivia before it)."
  [tok]
  (empty? (:trivia/before tok)))

;; ---------------------------------------------------------------------------
;; Meme-specific parselet extras
;; ---------------------------------------------------------------------------

(defn reader-cond-extra
  "Extract :splicing? from a reader-cond token (#? vs #?@)."
  [tok]
  (let [raw (:raw tok)]
    {:splicing? (and (>= (count raw) 3) (= "#?@" (subs raw 0 3)))}))
