(ns meme-lang.errors
  "Consistent error infrastructure for the meme reader/tokenizer.
   All error throw sites should use `meme-error` to ensure uniform
   location tracking and message formatting."
  (:require [clojure.string :as str]))

(defn ^:no-doc source-context
  "Extract the source line at the given 1-indexed line number for display.
   Uses str/split-lines which splits on \\n and \\r\\n (stripping \\r from
   CRLF pairs). This is the *display* line model — it may differ from the
   scanner line model (meme-lang.tokenizer) which treats
   \\r as a regular character. The difference only matters for CRLF sources.
   Returns nil if source is nil/blank, line is nil, or line is out of range."
  [source line]
  (when (and source line (not (str/blank? source)))
    (let [lines (str/split-lines source)
          idx (dec line)]
      (when (and (>= idx 0) (< idx (count lines)))
        (nth lines idx)))))

(defn meme-error
  "Throw a meme reader/tokenizer error with consistent structure.
   data may contain :line, :col (1-indexed), :cause, and :source.
   :source-context is added to ex-data when both :source and :line
   are present. :source and :cause are always excluded from ex-data."
  ([msg] (meme-error msg {}))
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
   Single-column spans use ^, multi-column use ~.
   end-col is exclusive (one past the last character), matching the
   tokenizer's convention where sloc returns the next position after
   consumption."
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

   This function bridges two line models: :line/:col in ex-data come from
   the scanner (where \\r occupies a column), while the display line comes
   from source-context (which uses str/split-lines, stripping \\r from CRLF
   pairs). For CRLF sources the display line may be shorter than the scanner
   col implies — span-underline clamps to avoid overrunning the display.
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
         parts (volatile! [(str "Error: " msg)])]
     ;; Source context with gutter
     (when context-line
       (vswap! parts conj (str "\n" (line-number-gutter line gutter-w) context-line))
       (when (and col (pos? col))
         ;; Clamp col/end-col to display line length — the scanner line model
         ;; may count chars (e.g. \r in CRLF) that the display line omits.
         (let [display-len (count context-line)
               clamped-col (min col (inc display-len))
               clamped-end (when end-col (min end-col (inc display-len)))]
           (vswap! parts conj (str "\n" (blank-gutter gutter-w)
                                   (span-underline clamped-col clamped-end))))))
     ;; Secondary locations
     (when (seq secondary)
       (doseq [{s-line :line s-col :col s-label :label} secondary]
         (when-let [ctx (and source s-line (source-context source s-line))]
           (vswap! parts conj (str "\n" (line-number-gutter s-line gutter-w) ctx))
           (when (and s-col (pos? s-col))
             ;; Clamp secondary col to display line length (same bridge as primary)
             (let [clamped (min s-col (inc (count ctx)))]
               (vswap! parts conj (str "\n" (blank-gutter gutter-w)
                                       (apply str (repeat (dec clamped) " ")) "^ " s-label)))))))
     ;; Hint
     (when hint
       (vswap! parts conj (str "\nHint: " hint)))
     ;; I4: for non-ExceptionInfo exceptions (e.g. user code eval errors),
     ;; include the cause chain for diagnostics
     ;; RT3-F33: support JS Error.cause (ES2022) on CLJS
     (when-let [cause #?(:clj  (when (not data) (.getCause ^Throwable e))
                         :cljs (when (not data) (.-cause e)))]
       (vswap! parts conj (str "\nCaused by: "
                               #?(:clj (.getName (class cause)) :cljs (or (.-name cause) "Error"))
                               ": " (ex-message cause))))
     (apply str @parts))))
