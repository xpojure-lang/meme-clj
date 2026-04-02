(ns meme.emit.formatter.flat
  "Flat formatter: single-line meme output.
   Composes printer (form → Doc) with render (layout @ infinite width)."
  (:require [meme.emit.printer :as printer]
            [meme.emit.render :as render]))

(defn format-form
  "Format a single Clojure form as flat meme text (single-line)."
  [form]
  (render/layout (printer/to-doc form :meme) ##Inf))

(defn format-forms
  "Format Clojure forms as flat meme text, separated by blank lines.
   L13: preserves trailing comments from :trailing-ws metadata, matching canon formatter."
  [forms]
  ;; RT3-F28: guard nil input
  (when (nil? forms)
    (throw (ex-info "format-forms expects a sequence of forms, not nil" {})))
  (when (string? forms)
    (throw (ex-info "format-forms expects a sequence of forms, not a string"
                    {:input (subs forms 0 (min 50 (count forms)))})))
  (printer/join-with-trailing-comments format-form forms))

(defn format-clj
  "Format Clojure forms as Clojure text with reader sugar ('quote, @deref, #'var).
   Always produces flat (single-line per form) output — no width-aware breaking.
   For width-aware meme formatting, use canon/format-forms."
  [forms]
  (printer/join-with-trailing-comments
   #(render/layout (printer/to-doc % :clj) ##Inf)
   forms))
