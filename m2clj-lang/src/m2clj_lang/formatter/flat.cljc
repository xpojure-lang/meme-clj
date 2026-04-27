(ns m2clj-lang.formatter.flat
  "Flat formatter: single-line m2clj output — true pass-through.
   Composes printer (form → Doc) with render (layout @ infinite width).
   No style opinions: no definition-form spacing, no head-line splitting.

   Form-shape decomposition still runs so semantic slots like :bindings
   and :clause render correctly (binding vectors, case/cond pairs); flat
   layout at infinite width produces the same result regardless."
  (:require [m2clj-lang.printer :as printer]
            [m2clj-lang.form-shape :as form-shape]
            [meme.tools.render :as render]))

(defn format-form
  "Format a single Clojure form as flat m2clj text (single-line, no style)."
  [form]
  (render/layout (printer/to-doc form :m2clj nil form-shape/registry) ##Inf))

(defn format-forms
  "Format Clojure forms as flat m2clj text, separated by blank lines.
   Preserves trailing comments from :trailing-ws metadata."
  [forms]
  (printer/validate-format-input forms)
  (printer/join-with-trailing-comments format-form forms))

(defn format-clj
  "Format Clojure forms as Clojure text with reader sugar ('quote, @deref, #'var).
   Always produces flat (single-line per form) output — no width-aware breaking.
   For width-aware m2clj formatting, use canon/format-forms."
  [forms]
  (printer/validate-format-input forms)
  (printer/join-with-trailing-comments
   #(render/layout (printer/to-doc % :clj nil form-shape/registry) ##Inf)
   forms))
