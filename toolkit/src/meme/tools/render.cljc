(ns meme.tools.render
  "Wadler-Lindig document algebra and layout engine.
   Generic — no meme-specific knowledge. Reusable for any pretty-printing task.

   Doc types form a small algebra:
     DocText     — literal string
     DocLine     — newline+indent (or flat-alt when flat)
     DocCat      — concatenation
     DocNest     — increase indent
     DocGroup    — try flat, break if too wide
     DocIfBreak  — conditional on flat/break mode

   layout renders a Doc tree to a string at a given page width.
   Use ##Inf for single-line (flat) rendering."
)

;; ---------------------------------------------------------------------------
;; Doc types — Lindig's strict-language variant of Wadler's algebra
;; ---------------------------------------------------------------------------

(defrecord DocText [s])
(defrecord DocLine [flat-alt])     ; nil flat-alt = hardline (always breaks)
(defrecord DocCat [a b])
(defrecord DocNest [indent doc])
(defrecord DocGroup [doc])
(defrecord DocIfBreak [break-doc flat-doc])

;; ---------------------------------------------------------------------------
;; Smart constructors
;; ---------------------------------------------------------------------------

(def line  "Space when flat, newline+indent when broken." (->DocLine " "))
(def line0 "Empty when flat, newline+indent when broken." (->DocLine ""))
(def hardline "Always breaks — never renders flat." (->DocLine nil))

(defn text
  "Literal string doc node. Returns nil for nil or empty string."
  [s]
  (when (and s (not= s ""))
    (->DocText s)))

(defn nest
  "Increase indent by i for doc. Returns nil if doc is nil."
  [i doc]
  (when doc (->DocNest i doc)))

(defn group
  "Try to render doc flat; break to multi-line if too wide. Returns nil if doc is nil."
  [doc]
  (when doc (->DocGroup doc)))

(defn doc-cat
  "Concatenate doc nodes. Nils are ignored."
  ([] nil)
  ([a] a)
  ([a b] (cond (nil? a) b (nil? b) a :else (->DocCat a b)))
  ([a b & more] (reduce doc-cat (doc-cat a b) more)))

;; ---------------------------------------------------------------------------
;; Portable string builder
;; ---------------------------------------------------------------------------

(defn- make-sb [] #?(:clj (StringBuilder.) :cljs #js []))

(defn- sb-append! [sb x]
  #?(:clj (.append ^StringBuilder sb ^String x) :cljs (.push sb x))
  sb)

(defn- sb-str [sb]
  #?(:clj (.toString sb) :cljs (.join sb "")))

(let [cache (mapv #(apply str (repeat % \space)) (range 64))]
  (defn- indent-str [n]
    (if (< n (count cache))
      (nth cache n)
      (apply str (repeat n \space)))))

;; ---------------------------------------------------------------------------
;; Layout engine — Lindig's format algorithm
;;
;; Work-list items: [indent mode doc]
;;   indent — current indentation level (absolute)
;;   mode   — :flat or :break
;;   doc    — Doc node to process
;;
;; The work-list is a persistent list (not a vector) so that prepend
;; via cons/list* is O(1). This is critical for performance: DocCat
;; prepends two items per step, and O(n) vector-into was quadratic.
;; ---------------------------------------------------------------------------

(defn- fits?
  "Does the first line of the work-list fit in `remaining` columns?
   Returns true at any line break (rest is on the next line)."
  [remaining work]
  (loop [remaining remaining
         work work]
    (cond
      (neg? remaining) false
      (nil? (seq work)) true
      :else
      (let [[i mode doc] (first work)
            rest-work (rest work)]
        (condp instance? doc
          DocText   (recur (- remaining (count (:s doc))) rest-work)
          DocLine   (if (= mode :flat)
                      (if (nil? (:flat-alt doc))
                        false ; hardline never fits flat
                        (recur (- remaining (count (:flat-alt doc))) rest-work))
                      true) ; break mode line → always fits (rest on next line)
          DocCat    (recur remaining (list* [i mode (:a doc)] [i mode (:b doc)] rest-work))
          DocNest   (recur remaining (cons [(+ i (:indent doc)) mode (:doc doc)] rest-work))
          DocGroup  (recur remaining (cons [i :flat (:doc doc)] rest-work))
          DocIfBreak (if (= mode :flat)
                       (if (:flat-doc doc)
                         (recur remaining (cons [i :flat (:flat-doc doc)] rest-work))
                         (recur remaining rest-work))
                       (if (:break-doc doc)
                         (recur remaining (cons [i :break (:break-doc doc)] rest-work))
                         (recur remaining rest-work)))
          ;; nil doc (from cat filtering)
          (recur remaining rest-work))))))

(defn layout
  "Render a Doc tree as a string at the given page width.
   Use ##Inf for flat (single-line) rendering."
  [doc width]
  ;; RT6-F18: explicit error instead of AssertionError for invalid width
  (when-not (or (= ##Inf width) (and (number? width) (pos? width)))
    (throw (ex-info (str "Layout width must be a positive number or ##Inf, got: " (pr-str width))
                    {:width width})))
  (let [sb (make-sb)]
    (loop [col 0
           work (list [0 :break doc])]
      (if (nil? (seq work))
        (sb-str sb)
        (let [[i mode d] (first work)
              rest-work (rest work)]
          (if (nil? d)
            (recur col rest-work)
            (condp instance? d
              DocText
              (let [s (:s d)]
                (sb-append! sb s)
                (recur (+ col (count s)) rest-work))

              DocLine
              (if (and (= mode :flat) (some? (:flat-alt d)))
                ;; Flat mode: emit flat-alt
                (let [alt (:flat-alt d)]
                  (when (not= alt "") (sb-append! sb alt))
                  (recur (+ col (count alt)) rest-work))
                ;; Break mode (or hardline in flat — shouldn't happen but handle safely)
                (let [indent-s (indent-str i)]
                  (sb-append! sb "\n")
                  (sb-append! sb indent-s)
                  (recur i rest-work)))

              DocCat
              (recur col (list* [i mode (:a d)] [i mode (:b d)] rest-work))

              DocNest
              (recur col (cons [(+ i (:indent d)) mode (:doc d)] rest-work))

              DocGroup
              ;; P1: when width is ##Inf (flat rendering), skip fits? entirely —
              ;; everything fits flat. Previously fits? traversed the entire
              ;; remaining work-list for each group → O(n²).
              (if (infinite? width)
                (recur col (cons [i :flat (:doc d)] rest-work))
                (let [flat-work (cons [i :flat (:doc d)] rest-work)]
                  (if (fits? (- width col) flat-work)
                    (recur col flat-work)
                    (recur col (cons [i :break (:doc d)] rest-work)))))

              DocIfBreak
              (if (= mode :flat)
                (if (:flat-doc d)
                  (recur col (cons [i :flat (:flat-doc d)] rest-work))
                  (recur col rest-work))
                (if (:break-doc d)
                  (recur col (cons [i :break (:break-doc d)] rest-work))
                  (recur col rest-work)))

              ;; Unknown — skip
              (recur col rest-work))))))))
