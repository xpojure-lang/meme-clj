(ns infj-lang.api
  "Infj-lang composition: meme's lossless pipeline with an infix-
   augmented grammar.

   Infj reuses everything meme does — scanner trivia, CST reader,
   printer, formatter, stages, run/repl — by injecting its grammar
   through the existing `:grammar` opt on `meme-lang.stages/step-parse`.
   Output forms are normal Clojure forms; the printer/formatter still
   produce M-expression syntax (no infix rendering in v0)."
  (:require [meme-lang.api :as meme-api]
            [meme-lang.stages :as stages]
            [infj-lang.grammar :as grammar]
            #?(:clj [infj-lang.run :as run])
            #?(:clj [infj-lang.repl :as repl])
            #?(:clj [meme.registry :as registry])))

(defn- with-grammar [opts]
  (assoc (or opts {}) :grammar grammar/grammar))

(defn infj->forms
  "Read infj source string. Returns a vector of Clojure forms.
   opts are forwarded to `meme-lang.stages/step-read` (e.g.
   `:resolve-keyword`)."
  ([s] (infj->forms s nil))
  ([s opts]
   {:pre [(string? s)]}
   (:forms (stages/run s (with-grammar opts)))))

(defn infj->clj
  "Convert infj source to Clojure source text. Reuses meme's printer,
   so the output is M-expression syntax — to get Clojure surface syntax,
   pass the forms through `meme-lang.api/forms->clj`."
  ([s] (infj->clj s nil))
  ([s opts]
   {:pre [(string? s)]}
   (meme-api/forms->clj (infj->forms s opts))))

(defn format-infj
  "Format infj source text. Parses with infj grammar; formats via meme's
   canonical formatter (output is M-expression form — infix rendering
   is not implemented in v0)."
  [source opts]
  (let [forms (infj->forms source opts)]
    (if (empty? forms)
      source
      (meme-api/format-meme-forms forms opts))))

(defn ^:no-doc to-clj
  "CLI-dispatch adapter: infj source → Clojure text."
  ([source] (infj->clj source))
  ([source opts] (infj->clj source opts)))

(def lang-map
  "Command map for the infj lang. Reuses meme's form-shape registry:
   since infj lowers everything to the same Clojure forms meme produces,
   the formatter's special-form slots apply unchanged."
  (cond-> {:extension ".infj"
           :format    format-infj
           :to-clj    to-clj
           :form-shape (:form-shape meme-api/lang-map)}
    #?(:clj true :cljs false)
    (merge #?(:clj {:run  (fn [source opts] (run/run-string source opts))
                    :repl (fn [opts] (repl/start opts))}
              :cljs nil))))

;; Self-register on JVM/Babashka.
#?(:clj (registry/register-builtin! :infj lang-map))
