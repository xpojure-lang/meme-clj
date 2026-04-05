(ns meme.loader-test
  "Tests for the meme namespace loader."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [meme.loader :as loader]))

(use-fixtures :each
  (fn [f]
    (let [orig-ns *ns*]
      (loader/install!)
      (try (f)
           (finally
             ;; Restore ns, remove test namespace, uninstall loader
             (in-ns (ns-name orig-ns))
             (when (find-ns 'test-meme-ns.greeter)
               (remove-ns 'test-meme-ns.greeter))
             (loader/uninstall!))))))

(deftest loader-install-uninstall
  (testing "install! is idempotent"
    (is (= :installed (loader/install!)))
    (is (= :installed (loader/install!))))
  (testing "uninstall! restores original load"
    (loader/uninstall!)
    (is (= :uninstalled (loader/uninstall!)))
    ;; reinstall for rest of test
    (loader/install!)))

(deftest require-meme-namespace
  (testing "require loads .meme file from classpath"
    (require 'test-meme-ns.greeter)
    (let [hello (ns-resolve 'test-meme-ns.greeter 'hello)
          add (ns-resolve 'test-meme-ns.greeter 'add)]
      (is (some? hello) "hello should be defined")
      (is (some? add) "add should be defined")
      (is (= "Hello, World!" (hello "World")))
      (is (= 5 (add 2 3))))))

(deftest clj-require-still-works
  (testing "standard .clj require still works after install"
    (require 'clojure.string)
    (is (some? (ns-resolve 'clojure.string 'join)))))
