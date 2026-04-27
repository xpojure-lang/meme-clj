(ns meme.test-registry
  "Test-only helpers that touch the lang registry's private state.
   Lives in `test/` so production `src/` carries no test-only API surface."
  (:require [meme.registry]))

;; Reach into the private registry atom. Tests need to clear user-registered
;; langs between cases without exposing a clear! function on the public
;; surface.
(def ^:private registry-atom @#'meme.registry/registry)

(defn clear-user-langs!
  "Clear all registered user languages, preserving built-ins. Test-only."
  []
  (swap! registry-atom
         (fn [m] (into {} (filter (fn [[_ v]] (:builtin? (meta v)))) m))))
