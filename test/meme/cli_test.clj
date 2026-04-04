(ns meme.cli-test
  (:require [clojure.test :refer [deftest is testing]]
            [meme.cli :as cli]))

(deftest meme-file?-test
  (let [meme-file? #'cli/meme-file?]
    (testing "returns true for .meme files"
      (is (true? (meme-file? "foo.meme")))
      (is (true? (meme-file? "path/to/bar.meme")))
      (is (true? (meme-file? "/absolute/path.meme"))))
    (testing "returns false for non-.meme files"
      (is (false? (meme-file? "foo.clj")))
      (is (false? (meme-file? "foo.cljc")))
      (is (false? (meme-file? "foo.txt")))
      (is (false? (meme-file? "foo.memes")))
      (is (false? (meme-file? "meme"))))))

(deftest clj-file?-test
  (let [clj-file? #'cli/clj-file?]
    (testing "returns true for Clojure source files"
      (is (true? (clj-file? "foo.clj")))
      (is (true? (clj-file? "foo.cljc")))
      (is (true? (clj-file? "foo.cljs")))
      (is (true? (clj-file? "foo.cljx")))
      (is (true? (clj-file? "path/to/bar.clj"))))
    (testing "returns false for non-Clojure files"
      (is (false? (clj-file? "foo.txt")))
      (is (false? (clj-file? "foo.meme")))
      (is (false? (clj-file? "foo.cljfoo")))
      (is (false? (clj-file? "clj"))))))

(deftest swap-ext-test
  (let [swap-ext #'cli/swap-ext]
    (testing "swaps .meme to .clj"
      (is (= "foo.clj" (swap-ext "foo.meme" "meme" "clj")))
      (is (= "path/to/bar.clj" (swap-ext "path/to/bar.meme" "meme" "clj"))))
    (testing "swaps .clj variants to .meme"
      (is (= "foo.meme" (swap-ext "foo.clj" "clj" "meme")))
      (is (= "foo.meme" (swap-ext "foo.cljc" "clj" "meme")))
      (is (= "foo.meme" (swap-ext "foo.cljs" "clj" "meme"))))))

(deftest lang-opts-test
  (let [lang-opts #'cli/lang-opts]
    (testing "removes CLI-specific keys"
      (is (= {} (lang-opts {:file "x" :files ["a"] :stdout true :check true :lang "meme-classic"}))))
    (testing "preserves non-CLI keys"
      (is (= {:width 80 :style "canon"}
             (lang-opts {:file "x" :stdout true :width 80 :style "canon"}))))
    (testing "empty map passes through"
      (is (= {} (lang-opts {}))))))
