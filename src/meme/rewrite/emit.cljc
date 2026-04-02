(ns meme.rewrite.emit
  "Serialize m-call tagged trees to meme text.
   Companion to meme.rewrite.rules — renders the output of S→M rules."
  (:require [clojure.string :as str]
            [meme.emit.values :as values]
            [meme.forms :as forms]))

(declare emit)

(defn- emit-seq
  "Emit a sequence. m-call nodes become head(args), plain lists become (elems)."
  [form]
  (if (and (seq? form) (= 'm-call (first form)))
    ;; m-call: head(args...)
    (let [head (second form)
          args (drop 2 form)]
      (if (seq args)
        (str (emit head) "(" (str/join " " (map emit args)) ")")
        (str (emit head) "()")))
    ;; plain list (empty list or non-m-call)
    (if (empty? form)
      "()"
      (str "(" (str/join " " (map emit form)) ")"))))

(defn emit
  "Convert a form (with m-call tags) to meme text string."
  [form]
  (cond
    (and (seq? form) (= 'm-call (first form)))
    (emit-seq form)

    ;; Tagged reader conditional from tree builder: (meme/reader-cond :clj x :cljs y)
    (and (seq? form) (= 'meme/reader-cond (first form)))
    (let [pairs (partition 2 (rest form))
          body (str/join " " (mapcat (fn [[k v]] [(emit k) (emit v)]) pairs))]
      (str "#?(" body ")"))

    (and (seq? form) (= 'meme/reader-cond-splicing (first form)))
    (let [pairs (partition 2 (rest form))
          body (str/join " " (mapcat (fn [[k v]] [(emit k) (emit v)]) pairs))]
      (str "#?@(" body ")"))

    (seq? form)
    (emit-seq form)

    (vector? form)
    (str "[" (str/join " " (map emit form)) "]")

    ;; ReaderConditional — must be before map? (CLJS defrecords satisfy map?)
    (forms/meme-reader-conditional? form)
    (let [prefix (if (forms/rc-splicing? form) "#?@(" "#?(")
          pairs (partition 2 (forms/rc-form form))
          body (str/join " " (mapcat (fn [[k v]] [(emit k) (emit v)]) pairs))]
      (str prefix body ")"))

    ;; I3: AST node types — must be before map? (defrecords satisfy map?)
    ;; D65: use :raw to preserve hex/octal notation (was :value which loses it)
    (forms/raw? form)
    (:raw form)

    (forms/deferred-auto-keyword? form)
    (:raw form)

    (forms/syntax-quote? form)
    (str "`" (emit (:form form)))

    (forms/unquote? form)
    (str "~" (emit (:form form)))

    (forms/unquote-splicing? form)
    (str "~@" (emit (:form form)))

    (map? form)
    (str "{" (str/join " " (map (fn [[k v]] (str (emit k) " " (emit v))) form)) "}")

    (set? form)
    (let [elements (or (:meme/order (meta form)) (seq form))]
      (str "#{" (str/join " " (map emit (or elements []))) "}"))

    ;; Atomic values — shared with printer via emit.values
    :else
    (or (values/emit-value-str form emit)
        (pr-str form))))

(defn emit-forms
  "Emit a sequence of top-level forms as meme text, separated by newlines."
  [forms]
  (str/join "\n\n" (map emit forms)))
