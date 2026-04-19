(ns meme-lang.formatter.canon
  "Canonical formatter: width-aware meme output.
   Composes printer (form → Doc) with render (layout @ target width).
   Used by `meme format` CLI command.

   Owns the canonical style — layout policy for how calls are structured.
   Uses `meme-lang.form-shape/registry` as the default form-shape vocabulary;
   callers can override via the `:form-shape` opts key."
  (:require [meme-lang.printer :as printer]
            [meme-lang.form-shape :as form-shape]
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
   Preserves comments from :meme/leading-trivia metadata.

   opts: {:width 80 :form-shape reg :style s} or bare integer width.
     :width       target line width (default 80)
     :form-shape  registry (default meme-lang.form-shape/registry).  Pass
                  a custom registry to teach canon about user macros or
                  pass (with-structural-fallback ...) to infer shapes for
                  unregistered heads.  Pass nil to disable decomposition.
     :style       slot-keyed style map (default this namespace's `style`).
                  Useful for project-level tweaks like custom slot renderers
                  or a narrower :head-line-slots set."
  ([form] (format-form form nil))
  ([form opts]
   (let [opts       (if (integer? opts) {:width opts} opts)
         width      (or (:width opts) default-width)
         form-shape (:form-shape opts form-shape/registry)
         effective  (:style opts style)]
     (render/layout (printer/to-doc form :meme effective form-shape) width))))

(defn format-forms
  "Format a sequence of Clojure forms as canonical meme text,
   separated by blank lines. Preserves comments from :meme/leading-trivia metadata.
   opts: {:width 80} or bare integer width."
  ([forms] (format-forms forms nil))
  ([forms opts]
   (printer/validate-format-input forms)
   (let [opts (if (integer? opts) {:width opts} opts)]
     (printer/join-with-trailing-comments #(format-form % opts) forms))))
