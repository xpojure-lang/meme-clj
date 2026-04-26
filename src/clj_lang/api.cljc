(ns clj-lang.api
  "Native Clojure lang — registered as `:clj` so the CLI's `format`,
   `to-clj`, `to-m1clj` commands work on `.clj`/`.cljc` files via the
   native parser (`meme.tools.clj.parser.*`).

   This is a thin shim:

     • parse: `meme.tools.clj.parser.api/clj->ast`
     • format: `m1clj-lang.formatter.canon/format-forms` with `:mode :clj`
     • to-m1clj: `m1clj-lang.api/clj->m1clj` (lossless via AST)

   Self-registers as a built-in lang on JVM/Babashka load. The `:run`
   slot is intentionally absent — `meme.cli` already eval-loads `.clj`
   files via Clojure's standard mechanisms; meme only needs to handle
   the surface conversions and formatting."
  (:require [meme.tools.clj.parser.api :as clj-parser]
            [meme.tools.clj.ast.nodes :as nodes
             #?@(:cljs [:refer [CljDiscard]])]
            [m1clj-lang.formatter.flat :as fmt-flat]
            [m1clj-lang.formatter.canon :as fmt-canon]
            [m1clj-lang.form-shape :as form-shape]
            [m1clj-lang.api :as m1clj-api]
            #?(:clj [meme.registry :as registry]))
  #?(:clj (:import [meme.tools.clj.ast.nodes CljDiscard])))

;; ---------------------------------------------------------------------------
;; Bridge helpers — mirror m1clj-lang.api's ast-root → vec shape so the
;; formatter sees the same input on both surfaces.
;; ---------------------------------------------------------------------------

(defn- ^:no-doc ast-root->children-with-trailing
  "Extract CljRoot's children as a vec, threading :trailing-trivia as
  `:trailing-ws` metadata. Matches the shape m1clj-lang.api uses to feed
  the formatter."
  [root]
  (let [children (filterv #(not (instance? CljDiscard %))
                          (:children root))
        trailing (seq (:trailing-trivia root))]
    (if trailing
      (with-meta children
        {:trailing-ws (apply str (map :raw trailing))})
      children)))

;; ---------------------------------------------------------------------------
;; Lang command implementations
;; ---------------------------------------------------------------------------

(defn format-clj
  "Format `.clj` source: read via native parser, render canonically in
   `:clj` mode. Preserves comments and trivia from the AST.

   opts: {:width 80 :style \"flat\"|\"clj\"|\"m1clj\"}
     :width   target line width (default 80)
     :style   \"flat\"  — single-line per form, native Clojure syntax
              \"m1clj\" — single-line per form, m1clj syntax
              other    — canonical width-aware Clojure formatting (default)"
  [source opts]
  (let [ast (clj-parser/clj->ast source opts)
        children (ast-root->children-with-trailing ast)]
    (if (empty? children)
      source
      (case (:style opts)
        "flat"  (fmt-flat/format-clj children)
        "m1clj" (fmt-flat/format-forms children)
        (fmt-canon/format-forms children (assoc opts :mode :clj))))))

(defn ^:no-doc to-clj
  "CLI-dispatch adapter: round-trip `.clj` source through the native
   parser and back. Useful for normalisation; behaves like `format` at
   default width when `opts` is empty."
  ([source] (to-clj source nil))
  ([source opts] (format-clj source opts)))

(defn ^:no-doc to-m1clj
  "CLI-dispatch adapter: convert `.clj` source to m1clj. Lossless via
   the AST tier (delegates to `m1clj-lang.api/clj->m1clj`)."
  ([source] (m1clj-api/clj->m1clj source))
  ([source opts] (m1clj-api/clj->m1clj source opts)))

;; ---------------------------------------------------------------------------
;; Lang map + registration
;; ---------------------------------------------------------------------------

(def lang-map
  "Command map for the native Clojure lang.

   The `:form-shape` registry is shared with m1clj-lang — both langs
   carry Clojure semantics; only the surface syntax differs. A future
   Clojure-only macro registry could replace this without disturbing
   m1clj's, since registries are per-lang values."
  {:extension  ".clj"
   :extensions [".cljc"]
   :format     format-clj
   :to-clj     to-clj
   :to-m1clj   to-m1clj
   :form-shape form-shape/registry})

#?(:clj (registry/register-builtin! :clj lang-map))
