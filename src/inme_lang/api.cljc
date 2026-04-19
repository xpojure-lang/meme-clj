(ns inme-lang.api
  "Inme-lang composition: meme's lossless pipeline with an infix-
   augmented grammar.

   Inme reuses everything meme does — scanner trivia, CST reader,
   printer, formatter, stages, run/repl — by injecting its grammar
   through the existing `:grammar` opt on `meme-lang.stages/step-parse`.
   Output forms are normal Clojure forms; the printer/formatter still
   produce M-expression syntax (no infix rendering in v0)."
  (:require [meme-lang.api :as meme-api]
            [meme-lang.stages :as stages]
            [inme-lang.grammar :as grammar]
            #?(:clj [inme-lang.run :as run])
            #?(:clj [inme-lang.repl :as repl])
            #?(:clj [meme.registry :as registry])))

(defn- with-grammar [opts]
  (assoc (or opts {}) :grammar grammar/grammar))

(defn inme->forms
  "Read inme source string. Returns a vector of Clojure forms.
   opts are forwarded to `meme-lang.stages/step-read` (e.g.
   `:resolve-keyword`)."
  ([s] (inme->forms s nil))
  ([s opts]
   {:pre [(string? s)]}
   (:forms (stages/run s (with-grammar opts)))))

(defn inme->clj
  "Convert inme source to Clojure source text. Reuses meme's printer,
   so the output is M-expression syntax — to get Clojure surface syntax,
   pass the forms through `meme-lang.api/forms->clj`."
  ([s] (inme->clj s nil))
  ([s opts]
   {:pre [(string? s)]}
   (meme-api/forms->clj (inme->forms s opts))))

(defn format-inme
  "Format inme source text. Parses with inme grammar; formats via meme's
   canonical formatter (output is M-expression form — infix rendering
   is not implemented in v0)."
  [source opts]
  (let [forms (inme->forms source opts)]
    (if (empty? forms)
      source
      (meme-api/format-meme-forms forms opts))))

(defn ^:no-doc to-clj
  "CLI-dispatch adapter: inme source → Clojure text."
  ([source] (inme->clj source))
  ([source opts] (inme->clj source opts)))

(def lang-map
  "Command map for the inme lang. Reuses meme's form-shape registry:
   since inme lowers everything to the same Clojure forms meme produces,
   the formatter's special-form slots apply unchanged."
  (cond-> {:extension ".inme"
           :format    format-inme
           :to-clj    to-clj
           :form-shape (:form-shape meme-api/lang-map)}
    #?(:clj true :cljs false)
    (merge #?(:clj {:run  (fn [source opts] (run/run-string source opts))
                    :repl (fn [opts] (repl/start opts))}
              :cljs nil))))

;; Self-register on JVM/Babashka.
#?(:clj (registry/register-builtin! :inme lang-map))
