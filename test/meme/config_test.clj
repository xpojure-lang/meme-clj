(ns meme.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [meme.config :as config]
            [meme-lang.form-shape :as form-shape]
            [meme-lang.formatter.canon :as canon]))

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(deftest validate-accepts-empty-config
  (is (= {} (config/validate-config {}))))

(deftest validate-width-rejects-non-integers
  (is (thrown-with-msg? Exception #":width must be a positive integer"
                        (config/validate-config {:width "80"})))
  (is (thrown-with-msg? Exception #":width must be a positive integer"
                        (config/validate-config {:width 0})))
  (is (thrown-with-msg? Exception #":width must be a positive integer"
                        (config/validate-config {:width -5}))))

(deftest validate-structural-fallback-must-be-bool
  (is (thrown-with-msg? Exception #":structural-fallback\? must be a boolean"
                        (config/validate-config {:structural-fallback? "yes"}))))

(deftest validate-form-shape-structure
  (testing ":form-shape must be a map"
    (is (thrown-with-msg? Exception #":form-shape must be a map"
                          (config/validate-config {:form-shape [:foo :bar]}))))
  (testing "entries must be symbol → symbol"
    (is (thrown-with-msg? Exception #":form-shape entries must be symbol"
                          (config/validate-config {:form-shape {"my-defn" "defn"}}))))
  (testing "alias target must name a registered head"
    (is (thrown-with-msg? Exception #":form-shape alias target is not a registered head"
                          (config/validate-config {:form-shape {'my-defn 'nonexistent}})))))

(deftest validate-style-sets-are-keyword-sets
  (is (thrown-with-msg? Exception #":style :head-line-slots must be a set of keywords"
                        (config/validate-config {:style {:head-line-slots [:name :doc]}})))
  (is (thrown-with-msg? Exception #":style :head-line-slots must be a set of keywords"
                        (config/validate-config {:style {:head-line-slots #{:name "doc"}}}))))

(deftest validate-accepts-well-formed-config
  (is (= {:width 100
          :structural-fallback? true
          :form-shape {'my-defn 'defn, 'my-let 'let}
          :style {:head-line-slots #{:name :params :bindings}
                  :force-open-space-for #{:name}}}
         (config/validate-config
          {:width 100
           :structural-fallback? true
           :form-shape {'my-defn 'defn, 'my-let 'let}
           :style {:head-line-slots #{:name :params :bindings}
                   :force-open-space-for #{:name}}}))))

;; ---------------------------------------------------------------------------
;; config → opts translation
;; ---------------------------------------------------------------------------

(deftest empty-config-produces-empty-opts
  (is (= {} (config/config->opts {}))))

(deftest width-passes-through
  (is (= 100 (:width (config/config->opts {:width 100})))))

(deftest form-shape-aliases-extend-registry
  (let [opts (config/config->opts {:form-shape {'my-defn 'defn}})
        reg (:form-shape opts)]
    (is (contains? reg 'my-defn))
    (is (= (get reg 'my-defn) (get form-shape/registry 'defn)))
    ;; Other built-ins still present
    (is (contains? reg 'let))
    (is (contains? reg 'case))))

(deftest structural-fallback-wraps-registry
  (let [opts (config/config->opts {:structural-fallback? true})
        reg (:form-shape opts)]
    ;; Wrapper adds ::fallback-fn in metadata
    (is (some? (get (meta reg) :meme-lang.form-shape/fallback-fn)))))

(deftest style-override-merges-over-canon-default
  (let [opts (config/config->opts {:style {:head-line-slots #{:name :params}}})
        style (:style opts)]
    ;; Override applied
    (is (= #{:name :params} (:head-line-slots style)))
    ;; Default keys preserved
    (is (= (:force-open-space-for canon/style)
           (:force-open-space-for style)))))

;; ---------------------------------------------------------------------------
;; File discovery + reading (via temp directories)
;; ---------------------------------------------------------------------------

(defn- temp-dir! []
  (let [d (doto (java.io.File/createTempFile "meme-config-test" "")
            (.delete)
            (.mkdirs))]
    (.deleteOnExit d)
    d))

(defn- write-config! [dir content]
  (let [f (io/file dir ".meme-format.edn")]
    (spit f content)
    f))

(deftest find-config-file-walks-up
  (let [root (temp-dir!)
        sub  (doto (io/file root "sub" "deeper") (.mkdirs))]
    (write-config! root "{:width 120}")
    (let [found (config/find-config-file sub)]
      (is (= (.getCanonicalPath (io/file root ".meme-format.edn"))
             (.getCanonicalPath found))))))

(deftest find-config-file-returns-nil-when-absent
  (let [root (temp-dir!)]
    (is (nil? (config/find-config-file root)))))

(deftest read-config-file-parses-and-validates
  (let [root (temp-dir!)
        f (write-config! root "{:width 100 :structural-fallback? true}")]
    (is (= {:width 100 :structural-fallback? true}
           (config/read-config-file f)))))

(deftest read-config-file-rejects-malformed-edn
  (let [root (temp-dir!)
        f (write-config! root "{:width ")]
    (is (thrown-with-msg? Exception #"Malformed EDN"
                          (config/read-config-file f)))))

(deftest read-config-file-rejects-reader-tags
  (let [root (temp-dir!)
        f (write-config! root "{:width #custom/tag 80}")]
    (is (thrown-with-msg? Exception #"Unknown tag in config"
                          (config/read-config-file f)))))

(deftest resolve-project-opts-returns-empty-when-no-config
  (let [root (temp-dir!)]
    (is (= {} (config/resolve-project-opts root)))))

(deftest resolve-project-opts-translates-config
  (let [root (temp-dir!)
        _ (write-config! root "{:width 120 :form-shape {my-defn defn}}")
        opts (config/resolve-project-opts root)]
    (is (= 120 (:width opts)))
    (is (contains? (:form-shape opts) 'my-defn))))

;; ---------------------------------------------------------------------------
;; End-to-end: config-derived opts produce the expected canonical output
;; ---------------------------------------------------------------------------

(deftest config-opts-drive-canon-layout
  (testing "my-defn aliased to defn renders with defn-like layout"
    (let [root (temp-dir!)
          _ (write-config! root "{:form-shape {my-defn defn}}")
          opts (config/resolve-project-opts root)
          result (canon/format-form '(my-defn foo [x] (some-body x)) (assoc opts :width 20))]
      (is (re-find #"^my-defn\( foo \[x\]" result))))
  (testing "structural fallback finds my-let via inference"
    (let [root (temp-dir!)
          _ (write-config! root "{:structural-fallback? true}")
          opts (config/resolve-project-opts root)
          ;; Width narrow enough to force break — verifies the
          ;; :bindings slot stayed on the head line (not demoted to body)
          result (canon/format-form '(my-let [x 1 y 2] (long-body x y)) (assoc opts :width 15))]
      (is (re-find #"^my-let\( \[x 1" result)))))
