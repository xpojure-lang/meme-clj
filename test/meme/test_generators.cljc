(ns meme.test-generators
  "Shared test.check generators for meme-clj generative tests.
   Portable (.cljc) — uses reader conditionals for platform-specific generators.
   Extracted from generative_test.clj and generative_cljs_test.cljc."
  (:require [clojure.test.check.generators :as gen]))

;; ===========================================================================
;; Character sets and reserved names
;; ===========================================================================

(def safe-symbol-chars "abcdefghijklmnopqrstuvwxyz")
(def symbol-suffix-chars "abcdefghijklmnopqrstuvwxyz0123456789-?!*")

(def reserved-symbols #{'fn 'quote 'var 'clojure.core/deref
                        (symbol "nil") (symbol "true") (symbol "false")})

;; Keywords the printer strips from metadata (compiler/reader-added keys)
(def reserved-meta-keywords #{:ws :line :column :file})

;; ===========================================================================
;; Leaf generators (portable)
;; ===========================================================================

(def gen-simple-symbol
  (gen/let [first-char (gen/elements (seq safe-symbol-chars))
            rest-chars (gen/vector (gen/elements (seq symbol-suffix-chars)) 0 8)]
    (let [sym (symbol (apply str first-char rest-chars))]
      (if (reserved-symbols sym) (symbol (str first-char "x")) sym))))

(def gen-keyword
  (gen/such-that
   #(not (reserved-meta-keywords %))
   (gen/one-of
    [(gen/let [n (gen/not-empty (gen/vector (gen/elements (seq safe-symbol-chars)) 1 8))]
       (keyword (apply str n)))
     (gen/let [ns-chars (gen/not-empty (gen/vector (gen/elements (seq safe-symbol-chars)) 1 5))
               n-chars (gen/not-empty (gen/vector (gen/elements (seq safe-symbol-chars)) 1 5))]
       (keyword (apply str ns-chars) (apply str n-chars)))])))

(def gen-number
  #?(:clj
     (gen/frequency
      [[5 (gen/one-of [gen/small-integer
                       (gen/fmap long (gen/large-integer* {:min -1000000 :max 1000000}))])]
       [2 (gen/let [n gen/small-integer d (gen/choose 1 99)]
            (+ (double n) (/ d 100.0)))]
       [1 (gen/let [num (gen/such-that #(not= 0 %) gen/small-integer)
                    den (gen/such-that #(pos? %) (gen/fmap #(Math/abs (int %)) gen/small-integer) 100)]
            (/ num (max den 1)))]
       [1 (gen/fmap bigint (gen/large-integer* {:min -10000 :max 10000}))]
       [1 (gen/let [n gen/small-integer d (gen/choose 1 99)]
            (bigdec (+ n (/ d 100.0))))]
       ;; Edge-case numbers from regression bugs (##NaN excluded: NaN != NaN by IEEE 754)
       [1 (gen/elements [##Inf ##-Inf])]])
     :cljs
     (gen/one-of [gen/small-integer
                  (gen/fmap long (gen/large-integer* {:min -1000000 :max 1000000}))
                  (gen/let [n gen/small-integer d (gen/choose 1 99)]
                    (+ (double n) (/ d 100.0)))])))

(def gen-string
  (gen/one-of
   [(gen/fmap #(apply str %) (gen/vector (gen/elements (seq "abcdefghijklmnopqrstuvwxyz 0123456789!?.-_")) 0 20))
    ;; Strings with escape-producing characters (quotes, backslashes, newlines)
    (gen/fmap #(apply str %)
              (gen/vector (gen/elements [\a \b \c \space \" \\ \newline \tab
                                         #?(:clj \return :cljs \newline)]) 0 10))]))

#?(:clj
   (def gen-char
     (gen/elements [\a \b \c \d \e \f \g \h \i \j \k \l \m
                    \newline \tab \space \return \backspace \formfeed])))

(def gen-primitive
  (gen/one-of
   [gen-number gen-string gen-keyword gen-simple-symbol
    gen/boolean (gen/return nil)
    #?@(:clj [gen-char])]))

;; ===========================================================================
;; Collection generators
;; ===========================================================================

(defn gen-vector-of [g] (gen/vector g 0 5))
(defn gen-map-of [g] (gen/let [kvs (gen/vector (gen/tuple gen-keyword g) 0 4)]
                       (apply array-map (mapcat identity kvs))))
(defn gen-set-of [g] (gen/fmap set (gen/vector g 0 4)))
