(ns meme.alpha.emit.formatter.flat
  "Flat formatter: single-line meme output.
   Composes printer (form → Doc) with render (layout @ infinite width)."
  (:require [clojure.string :as str]
            [meme.alpha.emit.printer :as printer]
            [meme.alpha.emit.render :as render]))

(defn format-form
  "Format a single Clojure form as flat meme text (single-line)."
  [form]
  (render/layout (printer/to-doc form :meme) ##Inf))

(defn format-forms
  "Format Clojure forms as flat meme text, separated by blank lines."
  [forms]
  (str/join "\n\n" (map format-form forms)))

(defn format-clj
  "Format Clojure forms as Clojure text with reader sugar ('quote, @deref, #'var)."
  [forms]
  (str/join "\n\n" (map #(render/layout (printer/to-doc % :clj) ##Inf) forms)))
