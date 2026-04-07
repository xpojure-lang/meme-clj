(ns meme-lang.formatter.canon
  "Canonical formatter: width-aware meme output.
   Composes printer (form → Doc) with render (layout @ target width).
   Used by `meme format` CLI command.

   Owns the canonical style — layout policy for how calls are structured."
  (:require [meme-lang.printer :as printer]
            [meme.tools.render :as render]))

(def ^:private default-width 80)

(def style
  "Canonical formatting style — full layout policy.
   head-line-args:    how many args stay on the head line per form.
   definition-forms:  forms that always get space after ( even on one line.
   pair-body-forms:   forms with test-value pair bodies (case, cond, condp).
   binding-forms:     forms with name-value pair binding vectors."
  {:head-line-args
   {'def 1, 'def- 1, 'defonce 1,
    'defn 1, 'defn- 1, 'defmacro 1, 'defmulti 1, 'defmethod 2,
    'defprotocol 1, 'defrecord 1, 'deftype 1,
    'let 1, 'loop 1, 'for 1, 'doseq 1,
    'binding 1, 'with-open 1, 'with-local-vars 1, 'with-redefs 1,
    'if-let 1, 'when-let 1, 'if-some 1, 'when-some 1,
    'if 1, 'if-not 1,
    'when 1, 'when-not 1,
    'condp 2, 'case 1, 'cond-> 1, 'cond->> 1,
    'catch 2,
    'ns 1,
    '-> 1, '->> 1, 'some-> 1, 'some->> 1, 'as-> 2,
    'deftest 1, 'testing 1}

   :definition-forms
   #{'def 'def- 'defn 'defn- 'defonce
     'defmacro 'defmulti 'defmethod
     'defprotocol 'defrecord 'deftype
     'deftest 'ns}

   :pair-body-forms
   #{'case 'cond 'condp}

   :binding-forms
   #{'let 'loop 'for 'doseq 'binding
     'with-open 'with-local-vars 'with-redefs 'if-let 'when-let
     'if-some 'when-some 'as->}})

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
