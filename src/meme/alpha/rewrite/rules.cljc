(ns meme.alpha.rewrite.rules
  "Rewrite rule sets for S→M and M→S transformations.
   Each direction is a vector of rules for meme.alpha.rewrite/rewrite."
  (:require [meme.alpha.rewrite :as rw]))

;; ============================================================
;; S→M: Clojure forms → M-expression tagged tree
;;
;; Input:  (defn foo [x] (+ x 1))
;; Output: (m-call defn foo [x] (m-call + x 1))
;;
;; The m-call tag marks "this was a list with a callable head."
;; Vectors, maps, sets pass through unchanged.
;; ============================================================

(def s->m-rules
  "Rules that tag S-expression calls as m-call nodes.
   Apply bottom-up so inner calls are tagged before outer ones.
   List patterns only match lists (not vectors) — the engine distinguishes them."
  [(rw/rule '(?f ??args) '(m-call ?f ??args)
            (fn [bindings]
              (let [f (get bindings 'f)]
                (and (or (symbol? f) (keyword? f))
                     (not= f 'm-call)))))])

;; ============================================================
;; M→S: M-expression tagged tree → Clojure forms
;;
;; Input:  (m-call defn foo [x] (m-call + x 1))
;; Output: (defn foo [x] (+ x 1))
;; ============================================================

(def m->s-rules
  "Rules that convert m-call nodes back to S-expression lists."
  [(rw/rule '(m-call ?f ??args) '(?f ??args))])
