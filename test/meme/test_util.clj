(ns meme.test-util
  "Shared test utilities for meme-clj test suite.
   Extracts helpers that were duplicated across dogfood, vendor roundtrip,
   and benchmark tests."
  (:require [m1clj-lang.api :as lang]
            [m1clj-lang.formatter.flat :as fmt-flat]
            [meme.tools.clj.parser.api :as clj-parser]
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
  "Extract a readable name for a form (for failure messages). Always
   returns a non-empty string so failures are actionable: top-level
   def-likes get their declared name, other seqs show the head, and
   non-seq forms (bare symbols, literals, reader-conditional records)
   render as a truncated `pr-str`."
  [form]
  (cond
    (seq? form)
    (let [head (first form)]
      (cond
        (#{'defn 'defn- 'def 'defmacro 'defmulti 'defmethod
           'defprotocol 'defrecord 'deftype} head)
        (str head " " (second form))
        (= 'ns head) (str "ns " (second form))
        :else (str head "...")))
    :else
    (let [s (pr-str form)]
      (if (> (count s) 60) (str (subs s 0 57) "...") s))))

;; ---------------------------------------------------------------------------
;; Per-form roundtrip
;; ---------------------------------------------------------------------------

(defn try-roundtrip-form
  "Try to roundtrip a single form. Returns {:ok form} or {:error msg}.
   m1clj->forms preserves ReaderConditional records by default."
  [form]
  (try
    (let [meme-text (fmt-flat/format-form form)
          forms2 (lang/m1clj->forms meme-text)]
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
;; Native-parser cross-check — the native parser must not crash on
;; any source that clojure.core/read-string accepts. This is a parser
;; robustness net: real-world Clojure exercises corners (deep nesting,
;; multi-arity defs, exotic numerics, reader-conditional splicing)
;; that smoke tests miss.
;;
;; We deliberately do NOT do byte-level form equality. The two readers
;; produce the same SHAPE but differ in cosmetic ways (`fn*` vs `fn`,
;; gensym names vs `%1`, syntax-quote-in-record vs expanded form). A
;; deeper parity gate is future work.
;; ---------------------------------------------------------------------------

(defn cross-check-file
  "Run the native parser on `path` and report whether it succeeds.
   Returns a status map with one of:
     :ok                — native parser ran cleanly (read-string status irrelevant here)
     :native-crash      — native parser threw on input read-string accepted
     :read-string-also-failed — both parsers errored (skipped)
     :native-only       — native parsed; read-string failed (informational)"
  [path]
  (let [read-results (read-clj-forms path)
        read-errors (filterv :read-error read-results)
        rs-failed? (boolean (seq read-errors))
        native-result (try
                        (clj-parser/clj->forms (slurp path))
                        :ok
                        (catch Exception e
                          {:error (.getMessage e)}))
        native-failed? (and (map? native-result) (:error native-result))]
    (cond
      (and rs-failed? native-failed?)
      {:status :read-string-also-failed
       :read-error (first (map :read-error read-errors))
       :native-error (:error native-result)}

      (and (not rs-failed?) native-failed?)
      {:status :native-crash
       :native-error (:error native-result)}

      (and rs-failed? (not native-failed?))
      {:status :native-only
       :read-error (first (map :read-error read-errors))}

      :else
      {:status :ok})))

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
