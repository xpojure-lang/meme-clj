(ns meme.tools.clj.ast.nodes-test
  "Sanity tests for the AST node taxonomy.

  Every record must:
   - implement the AstNode protocol (children + rebuild)
   - round-trip through `(rebuild n (children n))` to an equivalent node
   - participate in `ast=` independently of pos/trivia"
  (:require [clojure.test :refer [deftest is testing]]
            [meme.tools.clj.ast.nodes :as nodes]))

(def ^:private p {:line 1 :col 1})
(def ^:private p2 {:line 9 :col 9})
(def ^:private tr [{:type :whitespace :raw " "}])

(defn- atom-sym [n] (nodes/->CljSymbol n nil p tr))
(defn- atom-int [v] (nodes/->CljNumber v (str v) p tr))

;; ---------------------------------------------------------------------------
;; Atomic literals — children are empty; rebuild is identity.
;; ---------------------------------------------------------------------------

(deftest atomic-literal-protocol
  (doseq [n [(nodes/->CljSymbol "foo" nil p tr)
             (nodes/->CljSymbol "foo" "my.ns" p tr)
             (nodes/->CljKeyword "kw" nil false p tr)
             (nodes/->CljKeyword "kw" "alias" true p tr)
             (nodes/->CljNumber 42 "42" p tr)
             (nodes/->CljNumber 42 "0x2A" p tr)
             (nodes/->CljString "hi" "\"hi\"" p tr)
             (nodes/->CljChar \A "\\A" p tr)
             (nodes/->CljChar \A "\\u0041" p tr)
             (nodes/->CljRegex "\\d+" p tr)
             (nodes/->CljNil p tr)
             (nodes/->CljBool true p tr)]]
    (testing (str (type n))
      (is (nodes/ast-node? n))
      (is (= [] (nodes/children n)))
      (is (= n (nodes/rebuild n []))
          "rebuild on atomic literal must be identity"))))

;; ---------------------------------------------------------------------------
;; Collections — children round-trip via rebuild.
;; ---------------------------------------------------------------------------

(deftest collection-children-roundtrip
  (let [a (atom-sym "a")
        b (atom-int 1)
        c (atom-int 2)
        cases [[(nodes/->CljList   [a b c] p tr [])  [a b c]]
               [(nodes/->CljVector [a b c] p tr [])  [a b c]]
               [(nodes/->CljSet    [a b c] p tr [])  [a b c]]
               [(nodes/->CljMap    [[a b] [c (atom-int 3)]] p tr [])
                [a b c (atom-int 3)]]]]
    (doseq [[node expected-children] cases]
      (testing (str (type node))
        (is (= expected-children (nodes/children node)))
        (let [rebuilt (nodes/rebuild node (nodes/children node))]
          (is (= node rebuilt)
              "rebuild with original children must reproduce the node")
          (is (= (type node) (type rebuilt))
              "rebuild must preserve record type"))))))

(deftest map-rebuild-repairs
  (let [m (nodes/->CljMap [[(atom-sym "a") (atom-int 1)]
                           [(atom-sym "b") (atom-int 2)]] p tr [])
        flat (nodes/children m)
        rebuilt (nodes/rebuild m flat)]
    (is (= 2 (count (:pairs rebuilt))))
    (is (= [(atom-sym "a") (atom-int 1)] (first (:pairs rebuilt))))))

;; ---------------------------------------------------------------------------
;; Reader-macro nodes — single-form children.
;; ---------------------------------------------------------------------------

(deftest sugar-node-protocol
  (let [inner (atom-sym "x")
        cases [(nodes/->CljQuote inner p tr :tick)
               (nodes/->CljDeref inner p tr)
               (nodes/->CljVar inner p tr)
               (nodes/->CljSyntaxQuote inner p tr)
               (nodes/->CljUnquote inner p tr)
               (nodes/->CljUnquoteSplicing inner p tr)
               (nodes/->CljDiscard inner p tr)
               (nodes/->CljAnonFn inner 1 p tr)]]
    (doseq [n cases]
      (testing (str (type n))
        (is (= [inner] (nodes/children n)))
        (let [inner2 (atom-sym "y")
              rebuilt (nodes/rebuild n [inner2])]
          (is (= (type n) (type rebuilt)))
          (is (= [inner2] (nodes/children rebuilt))))))))

;; ---------------------------------------------------------------------------
;; Compound nodes.
;; ---------------------------------------------------------------------------

(deftest tagged-protocol
  (let [t (nodes/->CljTagged "inst" (atom-sym "now") p tr)]
    (is (= [(atom-sym "now")] (nodes/children t)))
    (is (= "inst" (:tag t)))
    (let [r (nodes/rebuild t [(atom-sym "later")])]
      (is (= "inst" (:tag r)) "tag preserved through rebuild")
      (is (= [(atom-sym "later")] (nodes/children r))))))

(deftest reader-cond-protocol
  (let [k-clj  (nodes/->CljKeyword "clj" nil false p [])
        k-cljs (nodes/->CljKeyword "cljs" nil false p [])
        v1     (atom-int 1)
        v2     (atom-int 2)
        rc (nodes/->CljReaderCond [[k-clj v1] [k-cljs v2]] false p tr)]
    (is (= [k-clj v1 k-cljs v2] (nodes/children rc)))
    (is (false? (:splicing? rc)))
    (let [rebuilt (nodes/rebuild rc (nodes/children rc))]
      (is (= rc rebuilt)))))

(deftest meta-protocol
  (let [m1 (nodes/->CljKeyword "private" nil false p [])
        m2 (nodes/->CljKeyword "tag" nil false p [])
        target (atom-sym "x")
        meta (nodes/->CljMeta [m1 m2] target p tr)]
    (is (= [m1 m2 target] (nodes/children meta))
        "chain comes first, target last")
    (let [rebuilt (nodes/rebuild meta (nodes/children meta))]
      (is (= meta rebuilt)))))

(deftest namespaced-map-protocol
  (let [inner (nodes/->CljMap [] p [] [])
        nm (nodes/->CljNamespacedMap "my.ns" false inner p tr)]
    (is (= [inner] (nodes/children nm)))
    (is (= "my.ns" (:ns nm)))
    (let [inner2 (nodes/->CljMap [[(atom-sym "k") (atom-int 1)]] p [] [])
          r (nodes/rebuild nm [inner2])]
      (is (= "my.ns" (:ns r)) "ns preserved through rebuild")
      (is (= [inner2] (nodes/children r))))))

(deftest root-protocol
  (let [a (atom-sym "a")
        b (atom-int 1)
        root (nodes/->CljRoot [a b] [] p)]
    (is (= [a b] (nodes/children root)))
    (let [r (nodes/rebuild root [(atom-sym "c")])]
      (is (= [(atom-sym "c")] (nodes/children r)))
      (is (= (type root) (type r))))))

;; ---------------------------------------------------------------------------
;; ast= semantics
;; ---------------------------------------------------------------------------

(deftest ast=-ignores-positional-fields
  (testing "atomic literals — same name/value, different pos/trivia"
    (is (nodes/ast= (nodes/->CljSymbol "x" nil p [])
                    (nodes/->CljSymbol "x" nil p2
                                       [{:type :comment :raw ";; hi\n"}])))
    (is (nodes/ast= (nodes/->CljNumber 42 "42" p [])
                    (nodes/->CljNumber 42 "42" p2 [])))
    (is (nodes/ast= (nodes/->CljNil p []) (nodes/->CljNil p2 tr)))))

(deftest ast=-checks-scalar-fields
  (testing "different name → not ast="
    (is (not (nodes/ast= (atom-sym "x") (atom-sym "y")))))
  (testing "different ns → not ast="
    (is (not (nodes/ast= (nodes/->CljSymbol "x" nil p [])
                         (nodes/->CljSymbol "x" "my.ns" p [])))))
  (testing "different raw on same value → STILL ast="
    (is (nodes/ast= (nodes/->CljNumber 42 "42" p [])
                    (nodes/->CljNumber 42 "0x2A" p []))
        "raw is notation, not semantic — same value compares equal")))

(deftest ast=-recurses-into-children
  (testing "matching trees — ast="
    (is (nodes/ast= (nodes/->CljList [(atom-sym "a") (atom-int 1)] p tr [])
                    (nodes/->CljList [(atom-sym "a") (atom-int 1)] p2 [] []))))
  (testing "differing children — not ast="
    (is (not (nodes/ast= (nodes/->CljList [(atom-sym "a")] p tr [])
                         (nodes/->CljList [(atom-sym "b")] p tr [])))))
  (testing "different child counts — not ast="
    (is (not (nodes/ast= (nodes/->CljList [(atom-sym "a")] p tr [])
                         (nodes/->CljList [(atom-sym "a") (atom-int 1)] p tr [])))))
  (testing "different record types — not ast="
    (is (not (nodes/ast= (nodes/->CljList [(atom-sym "a")] p tr [])
                         (nodes/->CljVector [(atom-sym "a")] p tr []))))))

(deftest ast=-default-equality-includes-pos
  (testing "= (defrecord default) IS strict about pos/trivia"
    (is (not= (nodes/->CljSymbol "x" nil p [])
              (nodes/->CljSymbol "x" nil p2 [])))
    (is (not= (nodes/->CljSymbol "x" nil p [])
              (nodes/->CljSymbol "x" nil p tr)))))
