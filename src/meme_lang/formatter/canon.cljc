(ns meme-lang.formatter.canon
  "Canonical formatter: width-aware meme output.
   Composes printer (form → Doc) with render (layout @ target width).
   Used by `meme format` CLI command.

   Owns the canonical style — layout policy for how calls are structured."
  (:require [meme-lang.printer :as printer]
            [meme.tools.render :as render]))

(def ^:private default-width 80)

(def style
  "Canonical formatting style — opinions over semantic slot names.

   The vocabulary of slot names is contracted with `meme-lang.form-shape`.
   Style talks about categories (is this slot a signature part? a body
   part?), not about particular forms (`defn`, `let`, ...) — so any form
   that decomposes to the same slots inherits this layout for free.

   :head-line-slots       slots kept on the head line with the call head
   :force-open-space-for  slots whose presence forces `head( ` (open-paren
                          followed by space even when the call is flat)"
  {:head-line-slots
   #{:name :doc :params
     :dispatch-val :dispatch-fn
     :test :expr
     :bindings :as-name}

   :force-open-space-for
   #{:name}})

(defn format-form
  "Format a single Clojure form as canonical meme text.
   Width-aware — uses indented multi-line layout for forms that exceed width.
   Preserves comments from :meme-lang/leading-trivia metadata.
   opts: {:width 80} or bare integer width."
  ([form] (format-form form nil))
  ([form opts]
   (let [opts  (if (integer? opts) {:width opts} opts)
         width (or (:width opts) default-width)]
     (render/layout (printer/to-doc form :meme style) width))))

(defn format-forms
  "Format a sequence of Clojure forms as canonical meme text,
   separated by blank lines. Preserves comments from :meme-lang/leading-trivia metadata.
   opts: {:width 80} or bare integer width."
  ([forms] (format-forms forms nil))
  ([forms opts]
   (printer/validate-format-input forms)
   (let [opts (if (integer? opts) {:width opts} opts)]
     (printer/join-with-trailing-comments #(format-form % opts) forms))))
