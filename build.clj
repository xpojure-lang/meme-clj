(ns build
  (:require [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'io.github.xpojure-lang/meme-clj)
(def version (str/trim (slurp "toolkit/src/meme/version.txt")))
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (clean nil)
  (let [basis @basis]
    (b/write-pom {:class-dir class-dir
                  :lib lib
                  :version version
                  :basis basis
                  :src-dirs ["src" "toolkit/src" "m1clj-lang/src" "m2clj-lang/src" "clj-lang/src"]
                  :scm {:url "https://github.com/xpojure-lang/meme-clj"
                        :connection "scm:git:git://github.com/xpojure-lang/meme-clj.git"
                        :developerConnection "scm:git:ssh://git@github.com/xpojure-lang/meme-clj.git"
                        :tag (str "v" version)}
                  :pom-data [[:licenses
                              [:license
                               [:name "MIT License"]
                               [:url "https://opensource.org/licenses/MIT"]]]]})
    (b/copy-dir {:src-dirs ["src" "toolkit/src" "m1clj-lang/src" "m2clj-lang/src" "clj-lang/src" "resources"]
                 :target-dir class-dir})
    (b/jar {:class-dir class-dir
            :jar-file jar-file})))

(defn deploy [_]
  (jar nil)
  (dd/deploy {:installer :remote
              :artifact (b/resolve-path jar-file)
              :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))

(def fuzzer-class-dir "target/fuzzer-classes")
(def fuzzer-jar-file "target/fuzzer.jar")

(defn fuzzer-jar
  "Build an uberjar with AOT-compiled fuzz targets for Jazzer."
  [_]
  (b/delete {:path "target"})
  (let [basis (b/create-basis {:project "deps.edn" :aliases [:fuzzer]})]
    (b/compile-clj {:basis basis
                    :src-dirs ["src" "toolkit/src" "m1clj-lang/src" "m2clj-lang/src" "clj-lang/src" "fuzz"]
                    :class-dir fuzzer-class-dir
                    :ns-compile ['meme.fuzz.roundtrip]})
    (b/copy-dir {:src-dirs ["src" "toolkit/src" "m1clj-lang/src" "m2clj-lang/src" "clj-lang/src" "fuzz" "resources"]
                 :target-dir fuzzer-class-dir})
    (b/uber {:basis basis
             :class-dir fuzzer-class-dir
             :uber-file fuzzer-jar-file
             :main 'com.code_intelligence.jazzer.Jazzer})))
