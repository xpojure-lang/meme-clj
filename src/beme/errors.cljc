(ns beme.errors
  "Consistent error infrastructure for the beme reader/tokenizer.
   All error throw sites should use `beme-error` to ensure uniform
   location tracking and message formatting."
  (:require [clojure.string :as str]))

(defn source-context
  "Extract the source line at the given 1-indexed line number.
   Returns nil if source is nil/blank, line is nil, or line is out of range."
  [source line]
  (when (and source line (not (str/blank? source)))
    (let [lines (str/split-lines source)
          idx (dec line)]
      (when (and (>= idx 0) (< idx (count lines)))
        (nth lines idx)))))

(defn beme-error
  "Throw a beme reader/tokenizer error with consistent structure.
   data may contain :line, :col (1-indexed), :cause, and :source.
   When :source is provided, :source-context is added to ex-data."
  ([msg] (beme-error msg {}))
  ([msg {:keys [line col cause source] :as data}]
   (let [loc-suffix (when (and line col)
                      (str " (line " line ", col " col ")"))
         full-msg (str msg loc-suffix)
         ex-data (cond-> (dissoc data :cause :source)
                   (and source line) (assoc :source-context
                                            (source-context source line)))]
     (throw (ex-info full-msg ex-data cause)))))

(defn- line-number-gutter
  "Format a line number for the gutter, right-aligned to width."
  [n width]
  (let [s (str n)
        pad (- width (count s))]
    (str (apply str (repeat pad " ")) s " | ")))

(defn- blank-gutter
  "Blank gutter of the same width as a line number gutter."
  [width]
  (str (apply str (repeat width " ")) " | "))

(defn- span-underline
  "Create an underline string: spaces up to start-col, then ~ or ^ chars.
   Single-column spans use ^, multi-column use ~."
  [col end-col]
  (let [start (max 1 (or col 1))
        end (or end-col start)
        len (max 1 (- end start))]
    (str (apply str (repeat (dec start) " "))
         (if (= len 1) "^" (apply str (repeat len "~"))))))

(defn format-error
  "Format an exception for display, optionally with source context.
   Shows multi-line context with line numbers, span underlines, and
   secondary locations when available in ex-data.
   Returns a string suitable for printing."
  ([e] (format-error e nil))
  ([e source]
   (let [msg (ex-message e)
         data (when (instance? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e)
                (ex-data e))
         line (:line data)
         col  (:col data)
         end-col (:end-col data)
         hint (:hint data)
         secondary (:secondary data)
         context-line (or (when (and source line)
                          (source-context source line))
                        (:source-context data))
         ;; Line number gutter width — max of all line numbers for alignment
         all-lines (cond-> [] line (conj line)
                     secondary (into (keep :line secondary)))
         gutter-w (if (seq all-lines)
                    (count (str (apply max all-lines)))
                    1)
         parts (transient [(str "Error: " msg)])]
     ;; Source context with gutter
     (when context-line
       (conj! parts (str "\n" (line-number-gutter line gutter-w) context-line))
       (when (and col (pos? col))
         (conj! parts (str "\n" (blank-gutter gutter-w)
                           (span-underline col end-col)))))
     ;; Secondary locations
     (when (seq secondary)
       (doseq [{s-line :line s-col :col s-label :label} secondary]
         (when-let [ctx (and source s-line (source-context source s-line))]
           (conj! parts (str "\n" (line-number-gutter s-line gutter-w) ctx))
           (when (and s-col (pos? s-col))
             (conj! parts (str "\n" (blank-gutter gutter-w)
                               (apply str (repeat (dec s-col) " ")) "^ " s-label))))))
     ;; Hint
     (when hint
       (conj! parts (str "\nHint: " hint)))
     (apply str (persistent! parts)))))
