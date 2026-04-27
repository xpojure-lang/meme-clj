(ns meme.tools.lexer-test
  "Direct unit tests for the scanlet builders and cross-platform primitives.

   The toolkit only exports factories + primitives — lexical conventions
   live in each lang. Tests here therefore define their own tiny consume-fns
   inline to exercise the factories."
  (:require [clojure.test :refer [deftest is testing]]
            [meme.tools.parser :as p]
            [meme.tools.lexer :as lex]))

(def ^:private dummy-grammar
  "Empty grammar — scanlet tests drive the engine directly and don't parse."
  {:nud {} :led []})

;; ---------------------------------------------------------------------------
;; Local helpers — synthetic lexical conventions for testing
;; ---------------------------------------------------------------------------

(defn- digits-consume
  "Consume run of digits: (source len pos) → end-pos."
  [^String source ^long len ^long pos]
  (loop [i pos]
    (if (and (< i len) (lex/digit? (.charAt source i)))
      (recur (inc i)) i)))

(defn- space-ws-consumer
  "Trivia consumer for a run of space characters."
  [engine]
  (let [start (p/cursor engine)
        src   (p/source-str engine)
        len   (p/source-len engine)]
    (loop [i start]
      (if (and (< i len) (= (.charAt src i) \space))
        (recur (inc i))
        (do (p/set-pos! engine i)
            (p/make-trivia-token! engine :whitespace start))))))

;; ---------------------------------------------------------------------------
;; Character primitives
;; ---------------------------------------------------------------------------

(deftest digit-predicate
  (is (every? lex/digit? [\0 \1 \5 \9]))
  (is (not-any? lex/digit? [\a \A \_ \+ \space \newline nil])))

;; ---------------------------------------------------------------------------
;; Scanlet builders
;; ---------------------------------------------------------------------------

(deftest atom-scanlet-builds-atom-node
  (let [scanlet (lex/atom-scanlet :number digits-consume)
        e (p/make-engine "42" dummy-grammar)
        node (scanlet e)]
    (is (= :atom (:node node)))
    (is (= "42" (get-in node [:token :raw])))
    (is (= :number (get-in node [:token :type])))
    (is (= 2 (p/cursor e)) "cursor advanced past the consumed chars")))

(deftest single-char-scanlet-delegates-to-factory
  (let [captured (atom nil)
        fake-factory (fn [_engine tok]
                       (reset! captured tok)
                       (p/cst :marker {:token tok}))
        scanlet (lex/single-char-scanlet :bang fake-factory)
        e (p/make-engine "!x" dummy-grammar)
        node (scanlet e)]
    (testing "consumes one character and calls the factory with its token"
      (is (= 1 (p/cursor e)))
      (is (= "!" (:raw @captured)))
      (is (= :bang (:type @captured))))
    (testing "node returned is whatever the factory returned"
      (is (= :marker (:node node))))))

(deftest delimited-scanlet-builds-delimited-node
  (let [grammar {:nud {\[ (lex/delimited-scanlet :vec :lbrack \] :rbrack)}
                 :nud-pred [[(fn [c _] (lex/digit? c))
                             (lex/atom-scanlet :num digits-consume)]]
                 :trivia {\space space-ws-consumer}
                 :led []}
        [node] (p/parse "[1 2 3]" grammar)]
    (is (= :vec (:node node)))
    (is (= :lbrack (get-in node [:open :type])))
    (is (= :rbrack (get-in node [:close :type])))
    (is (= ["1" "2" "3"]
           (mapv #(get-in % [:token :raw]) (:children node))))))
