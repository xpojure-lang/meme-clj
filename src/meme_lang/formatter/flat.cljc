(ns meme-lang.formatter.flat
  "Flat formatter: single-line meme output — true pass-through.
   Composes printer (form → Doc) with render (layout @ infinite width).
   No style opinions: no definition-form spacing, no head-line splitting."
  (:require [meme-lang.printer :as printer]
            [meme.tools.render :as render]))

(defn format-form
  "Format a single Clojure form as flat meme text (single-line, no style)."
  [form]
  (render/layout (printer/to-doc form :meme) ##Inf))

(defn format-forms
  "Format Clojure forms as flat meme text, separated by blank lines.
   L13: preserves trailing comments from :trailing-ws metadata, matching canon formatter."
  [forms]
  (printer/validate-format-input forms)
  (printer/join-with-trailing-comments format-form forms))

(defn format-clj
  "Format Clojure forms as Clojure text with reader sugar ('quote, @deref, #'var).
   Always produces flat (single-line per form) output — no width-aware breaking.
   For width-aware meme formatting, use canon/format-forms."
  [forms]
  (printer/validate-format-input forms)
  (printer/join-with-trailing-comments
   #(render/layout (printer/to-doc % :clj) ##Inf)
   forms))
