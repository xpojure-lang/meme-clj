(ns meme.alpha.rewrite.emit
  "Serialize m-call tagged trees to meme text.
   Companion to meme.alpha.rewrite.rules — renders the output of S→M rules."
  (:require [clojure.string :as str]))

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

    (seq? form)
    (emit-seq form)

    (vector? form)
    (str "[" (str/join " " (map emit form)) "]")

    (map? form)
    (str "{" (str/join " " (map (fn [[k v]] (str (emit k) " " (emit v))) form)) "}")

    (set? form)
    (str "#{" (str/join " " (map emit form)) "}")

    (string? form)
    (pr-str form)

    (nil? form)
    "nil"

    #?@(:clj [(char? form) (str \\ form)])

    (instance? #?(:clj java.util.regex.Pattern :cljs js/RegExp) form)
    (str "#\"" #?(:clj (.pattern ^java.util.regex.Pattern form) :cljs (.-source form)) "\"")

    :else
    (str form)))

(defn emit-forms
  "Emit a sequence of top-level forms as meme text, separated by newlines."
  [forms]
  (str/join "\n\n" (map emit forms)))
