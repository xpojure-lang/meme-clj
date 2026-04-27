(ns m2clj-lang.api-test
  "Public API surface for m2clj-lang."
  (:require [clojure.test :refer [deftest is testing]]
            [m2clj-lang.api :as api]))

;; ---------------------------------------------------------------------------
;; m2clj→forms / forms→m2clj
;; ---------------------------------------------------------------------------

(deftest m2clj-to-forms-roundtrip
  (testing "round-trip via plain-form path"
    (let [src "println(str(\"the literal list (1 2 3) is: \" (1 2 3)))"
          forms (api/m2clj->forms src)
          printed (api/forms->m2clj forms)
          reparsed (api/m2clj->forms printed)]
      (is (= forms reparsed)
          (str "form equality lost: src=" src
               " printed=" printed)))))

(deftest m2clj-bare-paren-as-list
  (testing "(quote 1 2 3) — illegal call in Clojure, valid data in m2clj"
    (is (= '[(quote (quote 1 2 3))] (api/m2clj->forms "(quote 1 2 3)"))))
  (testing "+(1 2) is a call"
    (is (= '[(+ 1 2)] (api/m2clj->forms "+(1 2)"))))
  (testing "(+ 1 2) is a literal list (not callable)"
    (is (= '[(quote (+ 1 2))] (api/m2clj->forms "(+ 1 2)")))))

;; ---------------------------------------------------------------------------
;; m2clj↔clj conversion
;; ---------------------------------------------------------------------------

(deftest m2clj-to-clj-conversion
  (testing "bare-paren list literal → clj 'quote sugar"
    (is (= "'(x y z)" (api/m2clj->clj "(x y z)"))))
  (testing "call form preserved"
    (is (= "(+ 1 2)" (api/m2clj->clj "+(1 2)"))))
  (testing "mixed body: call with literal-list arg"
    (is (= "(f '(a b) c)" (api/m2clj->clj "f((a b) c)")))))

(deftest clj-to-m2clj-conversion
  (testing "clj 'quote sugar → m2clj bare-paren"
    (is (= "(x y z)" (api/clj->m2clj "'(x y z)"))))
  (testing "explicit (quote (x y z)) → m2clj bare-paren (canonicalised)"
    (is (= "(x y z)" (api/clj->m2clj "(quote (x y z))"))))
  (testing "explicit (quote sym) → 'sym"
    (is (= "'sym" (api/clj->m2clj "(quote sym)"))))
  (testing "call form → m2clj call form"
    (is (= "+(1 2)" (api/clj->m2clj "(+ 1 2)")))))

;; ---------------------------------------------------------------------------
;; Lang registration — m2clj is a built-in
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest lang-map-shape
     (testing "m2clj lang-map exposes the expected commands"
       (is (= ".m2clj" (:extension api/lang-map)))
       (is (some? (:format api/lang-map)))
       (is (some? (:to-clj api/lang-map)))
       (is (some? (:to-m2clj api/lang-map)))
       (is (some? (:run api/lang-map)))
       (is (some? (:repl api/lang-map)))
       (is (some? (:form-shape api/lang-map))))))
