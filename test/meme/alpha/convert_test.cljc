(ns meme.alpha.convert-test
  "Tests for meme.alpha.convert: unified meme↔clj conversion via two pipelines."
  (:require [clojure.test :refer [deftest is testing]]
            [meme.alpha.convert :as convert]))

;; ---------------------------------------------------------------------------
;; meme->clj
;; ---------------------------------------------------------------------------

(deftest meme->clj-classic
  (testing "basic call syntax"
    (is (= "(f x y)" (convert/meme->clj "f(x y)" :classic))))
  (testing "nested calls"
    (is (= "(+ 1 (* 2 3))" (convert/meme->clj "+(1 *(2 3))" :classic))))
  (testing "defn"
    (is (= "(defn foo [x] (+ x 1))" (convert/meme->clj "defn(foo [x] +(x 1))" :classic)))))

(deftest meme->clj-rewrite
  (testing "basic call syntax"
    (is (= "(f x y)" (convert/meme->clj "f(x y)" :rewrite))))
  (testing "nested calls"
    (is (= "(+ 1 (* 2 3))" (convert/meme->clj "+(1 *(2 3))" :rewrite))))
  (testing "data literals preserved"
    (is (= "[1 2 3]" (convert/meme->clj "[1 2 3]" :rewrite))))
  (testing "discard in collection"
    (is (= "[1 2]" (convert/meme->clj "[1 2 #_ 3]" :rewrite)))))

(deftest meme->clj-default-is-classic
  (testing "no pipeline arg defaults to :classic"
    (is (= "(f x)" (convert/meme->clj "f(x)")))))

(deftest meme->clj-unknown-pipeline-throws
  (testing "unknown pipeline name throws"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                          #"Unknown pipeline"
                          (convert/meme->clj "f(x)" :nonexistent)))))

;; ---------------------------------------------------------------------------
;; clj->meme (JVM only)
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest clj->meme-classic
     (testing "basic S-expression"
       (is (= "f(x y)" (convert/clj->meme "(f x y)" :classic))))
     (testing "defn"
       (is (= "defn(foo [x] +(x 1))" (convert/clj->meme "(defn foo [x] (+ x 1))" :classic))))))

#?(:clj
   (deftest clj->meme-rewrite
     (testing "basic S-expression"
       (is (= "f(x y)" (convert/clj->meme "(f x y)" :rewrite))))))

#?(:clj
   (deftest clj->meme-unknown-pipeline-throws
     (testing "unknown pipeline name throws"
       (is (thrown-with-msg? clojure.lang.ExceptionInfo
                             #"Unknown pipeline"
                             (convert/clj->meme "(f x)" :nonexistent))))))

;; ---------------------------------------------------------------------------
;; Cross-pipeline consistency
;; ---------------------------------------------------------------------------

(deftest pipelines-produce-equivalent-clojure
  (testing "both pipelines produce same Clojure for simple inputs"
    (doseq [src ["f(x y)"
                 "+(1 2)"
                 "defn(foo [x] +(x 1))"
                 "[1 2 3]"]]
      (let [classic (convert/meme->clj src :classic)
            rewrite (convert/meme->clj src :rewrite)]
        (is (= classic rewrite)
            (str "rewrite diverges from classic for: " src))))))
