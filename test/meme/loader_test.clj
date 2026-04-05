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
             ;; Restore ns, remove test namespaces, uninstall loader
             (in-ns (ns-name orig-ns))
             (doseq [ns-sym '[test-meme-ns.greeter test-meme-ns.shadow test-meme-ns.broken]]
               (when (find-ns ns-sym)
                 (remove-ns ns-sym)))
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

(deftest meme-takes-precedence-over-clj
  (testing ".meme file is loaded when both .meme and .clj exist"
    (require 'test-meme-ns.shadow :reload)
    (let [source-var (ns-resolve 'test-meme-ns.shadow 'source)]
      (is (some? source-var) "source should be defined")
      (is (= :meme @source-var) ".meme file should take precedence over .clj"))))

(deftest meme-parse-error-propagates
  (testing "require of a .meme file with syntax error throws"
    (is (thrown? Exception
                (require 'test-meme-ns.broken :reload)))))

;; ---------------------------------------------------------------------------
;; C3: Namespace denylist — core infrastructure cannot be shadowed
;; ---------------------------------------------------------------------------

(deftest denied-namespaces-not-intercepted
  (testing "clojure.* namespaces are not intercepted by the loader"
    ;; If the denylist works, find-lang-resource returns nil for clojure/* paths
    (let [find-fn @(resolve 'meme.loader/find-lang-resource)]
      (is (nil? (find-fn "/clojure/string")) "clojure/string should be denied")
      (is (nil? (find-fn "/clojure/core")) "clojure/core should be denied")
      (is (nil? (find-fn "/java/lang")) "java/lang should be denied")
      (is (nil? (find-fn "/nrepl/core")) "nrepl/core should be denied"))))

;; ---------------------------------------------------------------------------
;; C4: Cannot uninstall loader from within a loaded .meme file
;; ---------------------------------------------------------------------------

(deftest uninstall-during-load-rejected
  (testing "uninstall! throws when called during lang-load"
    ;; Simulate: bind *loading* true and try to uninstall
    (binding [meme.loader/*loading* true]
      (is (thrown-with-msg? Exception #"Cannot uninstall"
                            (loader/uninstall!))))))
