(ns m1clj-lang.printer-test
  "Printer-level tests — exercising seams like style's :slot-renderers
   override.  Output-level behavior lives in formatter/flat_test and
   formatter/canon_test; these tests target specific printer contracts."
  (:require [clojure.test :refer [deftest is testing]]
            [m1clj-lang.printer :as printer]
            [m1clj-lang.form-shape :as form-shape]
            [meme.tools.render :as render]))

(defn- render-at
  "Convenience: format a form through the printer and render at `width`."
  ([form width] (render-at form width nil))
  ([form width style]
   (render/layout
    (printer/to-doc form :m1clj style form-shape/registry)
    width)))

(defn- render-clj
  "Render through the printer in :clj mode."
  [form width]
  (render/layout
   (printer/to-doc form :clj nil form-shape/registry)
   width))

;; ---------------------------------------------------------------------------
;; Default slot renderers — baseline behavior
;; ---------------------------------------------------------------------------

(deftest clause-default-renders-as-pair
  (testing ":clause slot value (a [test value] pair) renders joined by a space"
    ;; cond pairs render as `test value` — not as vector literals
    (is (= "cond(a 1 b 2)"
           (render-at '(cond a 1 b 2) ##Inf)))))

(deftest bindings-default-renders-columnar
  (testing ":bindings slot uses columnar pair layout via binding-vector-doc"
    ;; Flat width — columnar padding is invisible
    (is (= "let([x 1 y 2] +(x y))"
           (render-at '(let [x 1 y 2] (+ x y)) ##Inf)))))

;; ---------------------------------------------------------------------------
;; Style's :slot-renderers overrides the default
;; ---------------------------------------------------------------------------

(deftest slot-renderer-override-takes-precedence
  (testing "a style with :slot-renderers override replaces the default"
    (let [;; Custom :clause renderer that joins with ` => ` instead of a space
          arrow-clause (fn [value _ctx]
                         (let [[a b] value]
                           (render/doc-cat
                            (render/text (pr-str a))
                            (render/text " => ")
                            (render/text (pr-str b)))))
          style {:slot-renderers {:clause arrow-clause}}]
      (is (= "cond(a => 1 b => 2)"
             (render-at '(cond a 1 b 2) ##Inf style))))))

(deftest partial-slot-renderer-override-keeps-other-defaults
  (testing "overriding one slot keeps defaults for others"
    (let [;; Override only :bindings; :clause still uses default
          boxed-bindings (fn [_value _ctx]
                           (render/text "<bindings>"))
          style {:slot-renderers {:bindings boxed-bindings}}
          ;; Form has both :bindings (via let) and :clause (via cond)
          result (render-at '(let [x 1] (cond a 1 b 2)) ##Inf style)]
      ;; bindings slot uses the override; clause default still active
      (is (re-find #"<bindings>" result))
      (is (re-find #"a 1 b 2" result)))))

(deftest unknown-slot-falls-back-to-to-doc-inner
  (testing "a slot without any renderer (default or override) uses plain recursion"
    ;; Add an unregistered slot via a custom form-shape registry.  The slot
    ;; name has no default and no override — printer falls back to
    ;; to-doc-inner, rendering the value as a plain form.
    (let [custom-registry (assoc form-shape/registry
                                 'my-form (fn [args]
                                            [[:custom-slot (first args)]]))
          doc (printer/to-doc '(my-form 42) :m1clj nil custom-registry)]
      (is (= "my-form(42)" (render/layout doc ##Inf))))))

;; ---------------------------------------------------------------------------
;; :m1clj vs :clj mode dispatch — the two notations
;; ---------------------------------------------------------------------------

(deftest mode-dispatch-call-notation
  (testing ":m1clj mode emits head-adjacent call syntax"
    (is (= "f(x y)" (render-at '(f x y) ##Inf))))
  (testing ":clj mode emits S-expression call syntax"
    (is (= "(f x y)" (render-clj '(f x y) ##Inf))))
  (testing "nested calls preserve mode"
    (is (= "f(g(x))"   (render-at  '(f (g x)) ##Inf)))
    (is (= "(f (g x))" (render-clj '(f (g x)) ##Inf)))))

(deftest mode-dispatch-data-literals-agree
  (testing "vectors, maps, sets render identically in both modes"
    (is (= "[1 2 3]" (render-at  '[1 2 3] ##Inf)))
    (is (= "[1 2 3]" (render-clj '[1 2 3] ##Inf)))
    (is (= "{:a 1}"  (render-at  '{:a 1}  ##Inf)))
    (is (= "{:a 1}"  (render-clj '{:a 1}  ##Inf)))))

;; ---------------------------------------------------------------------------
;; Reader-sugar in the form path
;;
;; After the AST cutover, the form printer is structural. `(quote x)` still
;; collapses to `'x` because that's canonical Clojure pretty-printing, but
;; `@x`, `#'x`, and `#()` require the AST tier to disambiguate user notation
;; from explicit calls.  Lossless meme→meme round-trip lives in `format-m1clj`
;; (source-driven, AST-backed).
;; ---------------------------------------------------------------------------

(deftest quote-renders-as-call-in-form-path
  (testing "(quote x) prints structurally; ' sugar requires AST"
    (is (= "quote(x)" (render-at (list 'quote 'x) ##Inf)))))

(deftest deref-renders-as-call-in-form-path
  (testing "(clojure.core/deref x) prints structurally; @ requires AST"
    (is (re-find #"deref\(x\)$"
                 (render-at (list 'clojure.core/deref 'x) ##Inf)))))

(deftest var-renders-as-call-in-form-path
  (testing "(var x) prints structurally; #' requires AST"
    (is (= "var(x)" (render-at (list 'var 'x) ##Inf)))))

;; ---------------------------------------------------------------------------
;; Metadata preservation through to-doc
;; ---------------------------------------------------------------------------

(deftest user-metadata-renders-as-caret
  (testing "^:private on a symbol round-trips as a caret-prefixed form"
    (let [form (with-meta 'x {:private true})]
      (is (re-find #"\^:private x" (render-at form ##Inf)))))
  (testing "^{:a 1 :b 2} with an explicit map renders the map"
    (let [form (with-meta 'x {:a 1 :b 2})
          out  (render-at form ##Inf)]
      (is (re-find #"\^" out))
      (is (re-find #":a 1" out))
      (is (re-find #":b 2" out)))))

(deftest internal-metadata-does-not-leak
  (testing "position keys (:line, :col, :column, :file) are not emitted"
    (let [form (with-meta 'x {:line 1 :col 1 :column 1 :file "f.m1clj"})]
      (is (= "x" (render-at form ##Inf))))))

;; ---------------------------------------------------------------------------
;; Anonymous-fn shorthand — AST-only after cutover
;; ---------------------------------------------------------------------------

(deftest anon-fn-renders-as-call-in-form-path
  (testing "(fn [%1] body) in the form path renders as a plain fn call"
    (let [form (list 'fn '[%1] (list 'inc '%1))]
      (is (re-find #"^fn\(" (render-at form ##Inf))))))
