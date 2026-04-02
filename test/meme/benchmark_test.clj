(ns meme.benchmark-test
  "Comparative benchmark: classic vs rewrite vs ts-trs langs.

   Two benchmark axes:
     1. meme→clj roundtrip — .meme fixture files through each lang
     2. clj→meme→clj vendor roundtrip — real-world .clj files per-form

   Reports timing, correctness (agreement between langs), and
   roundtrip fidelity (clj→meme→clj identity) for each lang."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [meme.lang :as lang]
            [meme.core :as core]
            [meme.test-util :as tu]))

;; ============================================================
;; Timing
;; ============================================================

(defmacro ^:private timed
  "Execute body, return [result elapsed-ms]."
  [& body]
  `(let [start# (System/nanoTime)
         result# (do ~@body)
         elapsed# (/ (double (- (System/nanoTime) start#)) 1e6)]
     [result# elapsed#]))

;; ============================================================
;; File discovery
;; ============================================================

(def ^:private fixture-dir "test/examples/fixtures")
(def ^:private vendor-dir "test/vendor")

(defn- meme-fixtures []
  (->> (file-seq (io/file fixture-dir))
       (filter #(str/ends-with? (.getName %) ".meme"))
       (sort-by str)))

(defn- vendor-projects []
  (let [dir (io/file vendor-dir)]
    (when (.isDirectory dir)
      (->> (.listFiles dir)
           (filter #(.isDirectory %))
           (sort-by str)))))

;; ============================================================
;; Clojure reader (per-form, with :read-cond :preserve)
;; Note: returns plain forms (not {:form f} maps), unlike tu/read-clj-forms.
;; ============================================================

(def ^:private eof-sentinel (Object.))

(defn- read-clj-forms [path]
  (binding [*read-eval* false]
    (let [rdr (java.io.PushbackReader. (io/reader path))]
      (loop [forms []]
        (let [form (try (read {:read-cond :preserve :eof eof-sentinel} rdr)
                        (catch Exception _ ::read-error))]
          (cond
            (= form ::read-error) forms
            (identical? form eof-sentinel) forms
            :else (recur (conj forms form))))))))

;; ============================================================
;; Benchmark 1: meme→clj — fixture files
;; ============================================================

(defn- bench-meme->clj-file
  "Convert a .meme file through each lang, return timing and agreement.
   Classic and rewrite are compared as text (same formatter).
   ts-trs is compared at form level (different text reconstruction)."
  [file]
  (let [src (slurp file)
        to-clj (fn [lang-name] (:to-clj (lang/resolve-lang lang-name)))
        [classic-result classic-ms] (timed ((to-clj :meme-classic) src))
        [rewrite-result rewrite-ms] (timed ((to-clj :meme-rewrite) src))
        [ts-trs-result ts-trs-ms] (timed ((to-clj :meme-trs) src))
        ;; ts-trs reconstructs text from tokens (different whitespace),
        ;; so compare at form level via clj->forms + pr-str
        classic-forms (pr-str (core/clj->forms classic-result))
        ts-trs-forms (pr-str (core/clj->forms ts-trs-result))]
    {:file (.getName file)
     :classic-ms classic-ms
     :rewrite-ms rewrite-ms
     :ts-trs-ms ts-trs-ms
     :classic=rewrite (= classic-result rewrite-result)
     :classic=ts-trs (= classic-forms ts-trs-forms)}))

;; ============================================================
;; Structural equality — handles regex (Pattern lacks .equals)
;; ============================================================

(defn- form=
  "Deep equality that handles types where Object.equals is identity-based.
   Regex Pattern and ReaderConditional containing regex both fail = after
   roundtrip because each read creates a new Pattern instance."
  [a b]
  (cond
    (and (instance? java.util.regex.Pattern a)
         (instance? java.util.regex.Pattern b))
    (= (.pattern ^java.util.regex.Pattern a)
       (.pattern ^java.util.regex.Pattern b))

    ;; ReaderConditional — must be before map? (it satisfies map? on some paths)
    (and (reader-conditional? a) (reader-conditional? b))
    (and (= (.-splicing ^clojure.lang.ReaderConditional a)
            (.-splicing ^clojure.lang.ReaderConditional b))
         (form= (.-form ^clojure.lang.ReaderConditional a)
                (.-form ^clojure.lang.ReaderConditional b)))

    (and (sequential? a) (sequential? b))
    (and (= (count a) (count b))
         (every? true? (map form= a b)))

    (and (map? a) (map? b))
    (and (= (count a) (count b))
         ;; Can't use contains?/get when keys have broken .equals (regex in RC keys).
         ;; Match by pr-str of keys, then form= on values.
         (let [a-entries (into {} (map (fn [[k v]] [(pr-str k) {:k k :v v}]) a))
               b-entries (into {} (map (fn [[k v]] [(pr-str k) {:k k :v v}]) b))]
           (and (= (set (keys a-entries)) (set (keys b-entries)))
                (every? (fn [[ks ae]]
                          (let [be (get b-entries ks)]
                            (and (form= (:k ae) (:k be))
                                 (form= (:v ae) (:v be)))))
                        a-entries))))

    (and (set? a) (set? b))
    (and (= (count a) (count b))
         (= (set (map pr-str a)) (set (map pr-str b))))

    :else (= a b)))

;; ============================================================
;; Benchmark 2: clj→meme→clj vendor roundtrip (per-form)
;; ============================================================

(defn- roundtrip-form
  "Roundtrip a single Clojure form through clj→meme→clj using the public API.
   End-to-end: pr-str → :to-meme → :to-clj → clj->forms."
  [form lang-name]
  (try
    (let [l (lang/resolve-lang lang-name)
          clj-src (pr-str form)
          meme-text ((:to-meme l) clj-src)
          clj-back ((:to-clj l) meme-text)
          back-forms (core/clj->forms clj-back)]
      (if (form= [form] back-forms)
        {:ok true}
        {:error "roundtrip mismatch"}))
    (catch Exception e
      {:error (.getMessage e)})))

(defn- bench-vendor-project
  "Roundtrip all forms in a vendor project through each lang.
   Returns per-lang summary."
  [project-dir]
  (let [project (.getName project-dir)
        files (tu/find-clj-files project-dir)
        all-forms (mapcat (fn [f]
                            (mapv (fn [form] {:form form :file (.getName f)})
                                  (read-clj-forms f)))
                          files)
        n (count all-forms)
        bench-lang (fn [lang-name]
                     (let [[results ms] (timed
                                          (mapv #(roundtrip-form (:form %) lang-name)
                                                all-forms))
                           ok (count (filter :ok results))
                           errors (count (filter :error results))]
                       {:lang lang-name
                            :forms n
                            :ok ok
                            :errors errors
                            :ms ms}))]
    {:project project
     :files (count files)
     :forms n
     :classic (bench-lang :meme-classic)
     :rewrite (bench-lang :meme-rewrite)
     :ts-trs (bench-lang :meme-trs)}))

;; ============================================================
;; Reporting
;; ============================================================

(defn- format-ms [ms]
  (if (< ms 1000)
    (format "%.0fms" ms)
    (format "%.2fs" (/ ms 1000.0))))

(defn- report-meme->clj [results]
  (println "\n╔═══════════════════════════════════════════════════════════════════╗")
  (println "║                meme→clj Fixture Benchmark                        ║")
  (println "╠═══════════════════════════════════════════════════════════════════╣")
  (println (format "║ %-22s %8s %8s %8s  C=R C=T ║" "file" "classic" "rewrite" "ts-trs"))
  (println "╠═══════════════════════════════════════════════════════════════════╣")
  (doseq [{:keys [file classic-ms rewrite-ms ts-trs-ms classic=rewrite classic=ts-trs]} results]
    (println (format "║ %-22s %8s %8s %8s   %s   %s  ║"
                     (subs file 0 (min 22 (count file)))
                     (format-ms classic-ms)
                     (format-ms rewrite-ms)
                     (format-ms ts-trs-ms)
                     (if classic=rewrite "✓" "✗")
                     (if classic=ts-trs "✓" "✗"))))
  (let [totals (reduce (fn [acc r]
                         (-> acc
                             (update :classic + (:classic-ms r))
                             (update :rewrite + (:rewrite-ms r))
                             (update :ts-trs + (:ts-trs-ms r))
                             (update :agree-r + (if (:classic=rewrite r) 1 0))
                             (update :agree-t + (if (:classic=ts-trs r) 1 0))))
                       {:classic 0 :rewrite 0 :ts-trs 0 :agree-r 0 :agree-t 0}
                       results)
        n (count results)]
    (println "╠═══════════════════════════════════════════════════════════════════╣")
    (println (format "║ %-22s %8s %8s %8s %d/%d %d/%d ║"
                     "TOTAL"
                     (format-ms (:classic totals))
                     (format-ms (:rewrite totals))
                     (format-ms (:ts-trs totals))
                     (:agree-r totals) n
                     (:agree-t totals) n)))
  (println "╚═══════════════════════════════════════════════════════════════════╝")
  (println "  C=R: classic agrees with rewrite, C=T: classic agrees with ts-trs"))

(defn- report-vendor [results]
  (println "\n╔═════════════════════════════════════════════════════════════════════════╗")
  (println "║              clj→meme→clj Vendor Roundtrip Benchmark                    ║")
  (println "╠═════════════════════════════════════════════════════════════════════════╣")
  (println (format "║ %-14s %5s │ %14s │ %14s │ %14s ║"
                   "project" "forms" "classic" "rewrite" "ts-trs"))
  (println "╠═════════════════════════════════════════════════════════════════════════╣")
  (doseq [{:keys [project forms classic rewrite ts-trs]} results]
    (let [fmt-p (fn [{:keys [ok errors ms]}]
                  (format "%4d/%4d %6s" ok (+ ok errors) (format-ms ms)))]
      (println (format "║ %-14s %5d │ %14s │ %14s │ %14s ║"
                       project forms
                       (fmt-p classic) (fmt-p rewrite) (fmt-p ts-trs)))))
  (let [sum (fn [k] (reduce (fn [acc r] (let [p (get r k)]
                                           (-> acc
                                               (update :ok + (:ok p))
                                               (update :errors + (:errors p))
                                               (update :ms + (:ms p)))))
                             {:ok 0 :errors 0 :ms 0}
                             results))
        total-forms (reduce + (map :forms results))
        fmt-p (fn [{:keys [ok errors ms]}]
                (format "%4d/%4d %6s" ok (+ ok errors) (format-ms ms)))]
    (println "╠═════════════════════════════════════════════════════════════════════════╣")
    (println (format "║ %-14s %5d │ %14s │ %14s │ %14s ║"
                     "TOTAL" total-forms
                     (fmt-p (sum :classic))
                     (fmt-p (sum :rewrite))
                     (fmt-p (sum :ts-trs)))))
  (println "╚═════════════════════════════════════════════════════════════════════════╝"))

;; ============================================================
;; Tests
;; ============================================================

(deftest benchmark-meme->clj-fixtures
  (testing "meme→clj conversion across all langs"
    (let [files (meme-fixtures)
          results (mapv bench-meme->clj-file files)]
      (report-meme->clj results)
      (is (seq results) "Should have fixture files to benchmark")
      (doseq [{:keys [file classic=rewrite classic=ts-trs]} results]
        (is classic=rewrite (str file " classic and rewrite must agree"))
        (is classic=ts-trs (str file " classic and ts-trs must agree"))))))

(deftest benchmark-vendor-roundtrip
  (testing "clj→meme→clj vendor roundtrip across all langs"
    (let [projects (vendor-projects)]
      (if (empty? projects)
        (println "SKIP — vendor submodules not initialized (git submodule update --init)")
        (let [results (mapv bench-vendor-project projects)]
          (report-vendor results)
          ;; All langs should agree on roundtrip success count.
          (doseq [{:keys [project classic rewrite ts-trs]} results]
            (is (= (:ok classic) (:ok rewrite))
                (str project " classic and rewrite should agree"))
            (is (= (:ok classic) (:ok ts-trs))
                (str project " classic and ts-trs should agree"))))))))
