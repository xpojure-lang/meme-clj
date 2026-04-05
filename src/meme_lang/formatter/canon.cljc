(ns meme-lang.formatter.canon
  "Canonical formatter: width-aware meme output.
   Composes printer (form → Doc) with render (layout @ target width).
   Used by `meme format` CLI command."
  (:require [meme-lang.printer :as printer]
            [meme.tools.render :as render]))

(def ^:private default-width 80)

(defn format-form
  "Format a single Clojure form as canonical meme text.
   Width-aware — uses indented multi-line layout for forms that exceed width.
   Preserves comments from :meme/ws metadata.
   opts: {:width 80} or bare integer width."
  ([form] (format-form form nil))
  ([form opts]
   (let [opts  (if (integer? opts) {:width opts} opts)
         width (or (:width opts) default-width)]
     (render/layout (printer/to-doc form :meme) width))))

(defn format-forms
  "Format a sequence of Clojure forms as canonical meme text,
   separated by blank lines. Preserves comments from :meme/ws metadata.
   opts: {:width 80} or bare integer width."
  ([forms] (format-forms forms nil))
  ([forms opts]
   (printer/validate-format-input forms)
   (let [opts (if (integer? opts) {:width opts} opts)]
     (printer/join-with-trailing-comments #(format-form % opts) forms))))
