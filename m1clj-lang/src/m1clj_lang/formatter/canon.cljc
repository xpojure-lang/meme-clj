(ns m1clj-lang.formatter.canon
  "Canonical formatter: width-aware m1clj output.
   Composes printer (form → Doc) with render (layout @ target width).
   Used by `meme format` CLI command.

   Owns the canonical style — layout policy for how calls are structured.
   Uses `m1clj-lang.form-shape/registry` as the default form-shape vocabulary;
   callers can override via the `:form-shape` opts key."
  (:require [m1clj-lang.printer :as printer]
            [m1clj-lang.form-shape :as form-shape]
            [meme.tools.render :as render]))

(def ^:private default-width 80)

(def style
  "Canonical formatting style — opinions over semantic slot names.

   The vocabulary of slot names is contracted with `m1clj-lang.form-shape`.
   Style talks about categories (is this slot a signature part? a body
   part?), not about particular forms (`defn`, `let`, ...) — so any form
   that decomposes to the same slots inherits this layout for free.

   The same style serves both `:m1clj` and `:clj` output modes; slot
   decomposition is mode-independent. Only the geometry around the call
   (paren placement, spacing) differs by mode.

   :head-line-slots       slots kept on the head line with the call head
                          (applies to both modes)
   :force-open-space-for  slots whose presence forces `head( ` (open-paren
                          followed by space even when the call is flat).
                          m1clj-only — silently ignored under `:mode :clj`,
                          which has no `head( ` convention."
  {:head-line-slots
   #{:name :doc :params
     :dispatch-val :dispatch-fn
     :test :expr
     :bindings :as-name}

   :force-open-space-for
   #{:name}})

(defn format-form
  "Format a single Clojure form as canonical text.
   Width-aware — uses indented multi-line layout for forms that exceed width.
   Preserves comments only when called on AST nodes (`m1clj-lang.api/m1clj->ast`,
   `meme.tools.clj.parser.api/clj->ast`); plain forms carry no comment data.

   opts: {:width 80 :form-shape reg :style s :mode m} or bare integer width.
     :width       target line width (default 80)
     :form-shape  registry (default m1clj-lang.form-shape/registry).  Pass
                  a custom registry to teach canon about user macros or
                  pass (with-structural-fallback ...) to infer shapes for
                  unregistered heads.  Pass nil to disable decomposition.
     :style       slot-keyed style map (default this namespace's `style`).
                  Useful for project-level tweaks like custom slot renderers
                  or a narrower :head-line-slots set.
     :mode        :m1clj (default) — emit M-expression syntax
                  :clj             — emit native Clojure surface"
  ([form] (format-form form nil))
  ([form opts]
   (let [opts       (if (integer? opts) {:width opts} opts)
         width      (or (:width opts) default-width)
         form-shape (:form-shape opts form-shape/registry)
         effective  (:style opts style)
         mode       (or (:mode opts) :m1clj)]
     (render/layout (printer/to-doc form mode effective form-shape) width))))

(defn format-forms
  "Format a sequence of Clojure forms as canonical text, separated by
   blank lines. Comments are preserved only for AST input.
   opts: {:width 80 :mode :m1clj|:clj} or bare integer width."
  ([forms] (format-forms forms nil))
  ([forms opts]
   (printer/validate-format-input forms)
   (let [opts (if (integer? opts) {:width opts} opts)]
     (printer/join-with-trailing-comments #(format-form % opts) forms))))
