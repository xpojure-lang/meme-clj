(ns mclj-lang.formatter.flat
  "Flat formatter: single-line meme output — true pass-through.
   Composes printer (form → Doc) with render (layout @ infinite width).
   No style opinions: no definition-form spacing, no head-line splitting.

   Form-shape decomposition still runs so semantic slots like :bindings
   and :clause render correctly (binding vectors, case/cond pairs); flat
   layout at infinite width produces the same result regardless."
  (:require [mclj-lang.printer :as printer]
            [mclj-lang.form-shape :as form-shape]
            [meme.tools.render :as render]))

(defn format-form
  "Format a single Clojure form as flat meme text (single-line, no style)."
  [form]
  (render/layout (printer/to-doc form :meme nil form-shape/registry) ##Inf))

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
   #(render/layout (printer/to-doc % :clj nil form-shape/registry) ##Inf)
   forms))
