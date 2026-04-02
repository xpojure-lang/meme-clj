(ns meme.emit.formatter.canon
  "Canonical formatter: width-aware meme output.
   Composes printer (form → Doc) with render (layout @ target width).
   Used by `meme format` CLI command."
  (:require [meme.emit.printer :as printer]
            [meme.emit.render :as render]))

(def ^:private default-width 80)

(defn format-form
  "Format a single Clojure form as canonical meme text.
   Width-aware — uses indented multi-line layout for forms that exceed width.
   Preserves comments from :ws metadata.
   opts: {:width 80}"
  ([form] (format-form form nil))
  ([form opts]
   (let [width (or (:width opts) default-width)]
     (render/layout (printer/to-doc form :meme) width))))

(defn format-forms
  "Format a sequence of Clojure forms as canonical meme text,
   separated by blank lines. Preserves comments from :ws metadata.
   opts: {:width 80}"
  ([forms] (format-forms forms nil))
  ([forms opts]
   (when (nil? forms)
     (throw (ex-info "format-forms expects a sequence of forms, not nil" {})))
   (when (string? forms)
     (throw (ex-info "format-forms expects a sequence of forms, not a string"
                     {:input (subs forms 0 (min 50 (count forms)))})))
   (printer/join-with-trailing-comments #(format-form % opts) forms)))
