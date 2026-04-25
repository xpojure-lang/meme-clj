(ns meme.test-util
  "Shared test utilities for meme-clj test suite.
   Extracts helpers that were duplicated across dogfood, vendor roundtrip,
   and benchmark tests."
  (:require [mclj-lang.api :as lang]
            [mclj-lang.formatter.flat :as fmt-flat]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Clojure reader — read all forms from a .clj/.cljc file
;; ---------------------------------------------------------------------------

(defn read-clj-forms
  "Read all Clojure forms from a .clj/.cljc file using Clojure's reader.
   Returns vector of {:form f} or {:read-error msg}."
  [path]
  (binding [*read-eval* false]
    (let [rdr (java.io.PushbackReader. (io/reader path))]
      (loop [forms []]
        (let [result (try {:form (read {:read-cond :preserve :eof ::eof} rdr)}
                          (catch Exception e {:read-error (.getMessage e)}))]
          (cond
            (:read-error result) (conj forms result)
            (= (:form result) ::eof) forms
            :else (recur (conj forms result))))))))

;; ---------------------------------------------------------------------------
;; Form naming — extract a readable name for error messages
;; ---------------------------------------------------------------------------

(defn form-name
  "Extract a readable name for a form (for failure messages)."
  [form]
  (when (seq? form)
    (let [head (first form)]
      (cond
        (#{'defn 'defn- 'def 'defmacro 'defmulti 'defmethod
           'defprotocol 'defrecord 'deftype} head)
        (str head " " (second form))
        (= 'ns head) (str "ns " (second form))
        :else (str head "...")))))

;; ---------------------------------------------------------------------------
;; Per-form roundtrip
;; ---------------------------------------------------------------------------

(defn try-roundtrip-form
  "Try to roundtrip a single form. Returns {:ok form} or {:error msg}.
   meme->forms preserves ReaderConditional records by default."
  [form]
  (try
    (let [meme-text (fmt-flat/format-form form)
          forms2 (lang/meme->forms meme-text)]
      {:ok (if (= 1 (count forms2)) (first forms2) forms2)})
    (catch Exception e
      {:error (.getMessage e)})))

(defn roundtrip-file-forms
  "Roundtrip every form in a file individually.
   Returns {:path p :total n :succeeded [...] :failed [...] :read-errors [...]}.
   The :path key is the stringified path (nil if not provided)."
  ([path] (roundtrip-file-forms path nil))
  ([path opts]
   (let [read-results (read-clj-forms path)
         read-errors (filterv :read-error read-results)
         forms (mapv :form (filterv :form read-results))
         results (mapv (fn [form]
                         (merge (try-roundtrip-form form)
                                {:name (form-name form)}))
                       forms)]
     (cond-> {:total (+ (count results) (count read-errors))
              :succeeded (filterv :ok results)
              :failed (filterv :error results)
              :read-errors read-errors}
       (:include-path opts) (assoc :path (str path))))))

;; ---------------------------------------------------------------------------
;; File discovery
;; ---------------------------------------------------------------------------

(defn find-clj-files
  "Find all .clj and .cljc files under a directory, sorted."
  [dir]
  (->> (file-seq (io/file dir))
       (filter #(.isFile %))
       (filter #(let [name (.getName %)]
                  (or (str/ends-with? name ".clj")
                      (str/ends-with? name ".cljc"))))
       (sort-by str)))
