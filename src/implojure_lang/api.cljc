(ns implojure-lang.api
  "Implojure-lang composition: meme's lossless pipeline with an infix-
   augmented grammar.

   Implojure reuses everything meme does — scanner trivia, CST reader,
   printer, formatter, stages, run/repl — by injecting its grammar
   through the existing `:grammar` opt on `meme.tools.clj.stages/step-parse`.
   Output forms are normal Clojure forms; the printer/formatter still
   produce M-expression syntax (no infix rendering in v0)."
  (:require [meme-lang.api :as meme-api]
            [meme.tools.clj.stages :as stages]
            [implojure-lang.grammar :as grammar]
            #?(:clj [implojure-lang.run :as run])
            #?(:clj [implojure-lang.repl :as repl])
            #?(:clj [meme.registry :as registry])))

(defn- with-grammar [opts]
  (assoc (or opts {}) :grammar grammar/grammar))

(defn implojure->forms
  "Read implojure source string. Returns a vector of Clojure forms.
   opts are forwarded to `meme.tools.clj.stages/step-read` (e.g.
   `:resolve-keyword`)."
  ([s] (implojure->forms s nil))
  ([s opts]
   {:pre [(string? s)]}
   (:forms (stages/run s (with-grammar opts)))))

(defn implojure->clj
  "Convert implojure source to Clojure source text. Reuses meme's printer,
   so the output is M-expression syntax — to get Clojure surface syntax,
   pass the forms through `meme-lang.api/forms->clj`."
  ([s] (implojure->clj s nil))
  ([s opts]
   {:pre [(string? s)]}
   (meme-api/forms->clj (implojure->forms s opts))))

(defn format-implojure
  "Format implojure source text. Parses with implojure grammar; formats via meme's
   canonical formatter (output is M-expression form — infix rendering
   is not implemented in v0)."
  [source opts]
  (let [forms (implojure->forms source opts)]
    (if (empty? forms)
      source
      (meme-api/format-meme-forms forms opts))))

(defn ^:no-doc to-clj
  "CLI-dispatch adapter: implojure source → Clojure text."
  ([source] (implojure->clj source))
  ([source opts] (implojure->clj source opts)))

(def lang-map
  "Command map for the implojure lang. Reuses meme's form-shape registry:
   since implojure lowers everything to the same Clojure forms meme produces,
   the formatter's special-form slots apply unchanged."
  (cond-> {:extension  ".implj"
           :extensions [".impljc" ".impljs"]
           :format     format-implojure
           :to-clj     to-clj
           :form-shape (:form-shape meme-api/lang-map)}
    #?(:clj true :cljs false)
    (merge #?(:clj {:run  (fn [source opts] (run/run-string source opts))
                    :repl (fn [opts] (repl/start opts))}
              :cljs nil))))

;; Self-register on JVM/Babashka.
#?(:clj (registry/register-builtin! :implojure lang-map))
