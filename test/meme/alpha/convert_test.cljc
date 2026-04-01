(ns meme.alpha.convert-test
  "Tests for meme.alpha.convert: unified meme↔clj conversion.
   Iterates over all known langs for exhaustive coverage."
  (:require [clojure.test :refer [deftest is testing]]
            [meme.alpha.convert :as convert]
            [meme.alpha.lang :as lang]))

(def all-lang-names (keys #?(:clj @lang/builtin :cljs lang/builtin)))

;; ---------------------------------------------------------------------------
;; meme->clj: every lang must handle basic cases
;; ---------------------------------------------------------------------------

(deftest meme->clj-all-langs
  (doseq [lang-name all-lang-names]
    (testing (str lang-name " basic call")
      (is (= "(f x y)" (convert/meme->clj "f(x y)" lang-name))
          (str lang-name " failed on f(x y)")))
    (testing (str lang-name " nested calls")
      (is (= "(+ 1 (* 2 3))" (convert/meme->clj "+(1 *(2 3))" lang-name))
          (str lang-name " failed on nested calls")))))

(deftest meme->clj-default-is-meme-classic
  (is (= "(f x)" (convert/meme->clj "f(x)"))))

(deftest meme->clj-unknown-lang-throws
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                        #"Unknown"
                        (convert/meme->clj "f(x)" :nonexistent))))

;; ---------------------------------------------------------------------------
;; clj->meme: every lang must handle basic cases (JVM only)
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest clj->meme-all-langs
     (doseq [lang-name all-lang-names]
       (testing (str lang-name " basic S-expression")
         (is (= "f(x y)" (convert/clj->meme "(f x y)" lang-name))
             (str lang-name " failed on (f x y)"))))))

#?(:clj
   (deftest clj->meme-unknown-lang-throws
     (is (thrown-with-msg? clojure.lang.ExceptionInfo
                           #"Unknown"
                           (convert/clj->meme "(f x)" :nonexistent)))))

;; ---------------------------------------------------------------------------
;; Legacy name backward compatibility
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest legacy-name-aliases
     (testing ":classic produces same output as :meme-classic"
       (is (= (convert/meme->clj "f(x y)" :meme-classic)
              (convert/meme->clj "f(x y)" :classic))))
     (testing ":rewrite produces same output as :meme-rewrite"
       (is (= (convert/meme->clj "f(x y)" :meme-rewrite)
              (convert/meme->clj "f(x y)" :rewrite))))
     (testing ":ts-trs produces same output as :meme-trs"
       (is (= (convert/meme->clj "f(x y)" :meme-trs)
              (convert/meme->clj "f(x y)" :ts-trs))))))

;; ---------------------------------------------------------------------------
;; Cross-lang agreement: all langs produce same output for basic inputs
;; ---------------------------------------------------------------------------

(deftest all-langs-agree-on-meme->clj
  (doseq [src ["f(x y)" "+(1 2)" "defn(foo [x] +(x 1))" "[1 2 3]"]]
    (let [results (into {} (map (fn [n] [n (convert/meme->clj src n)]) all-lang-names))
          first-result (val (first results))]
      (doseq [[lang-name result] results]
        (is (= first-result result)
            (str lang-name " diverges on: " src))))))

#?(:clj
   (deftest all-langs-agree-on-clj->meme
     (doseq [src ["(f x y)" "(+ 1 2)" "(defn foo [x] (+ x 1))"]]
       (let [results (into {} (map (fn [n] [n (convert/clj->meme src n)]) all-lang-names))
             first-result (val (first results))]
         (doseq [[lang-name result] results]
           (is (= first-result result)
               (str lang-name " diverges on: " src)))))))
