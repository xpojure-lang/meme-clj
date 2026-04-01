(ns meme.emit.printer
  "Meme printer: Clojure forms → Doc trees.
   Builds Wadler-Lindig Doc trees from Clojure forms, handling meme syntax
   (call notation, sugar, metadata, comments) and Clojure output mode.
   Delegates to render for Doc algebra and layout."
  (:require [clojure.string :as str]
            [meme.emit.render :as render]
            [meme.forms :as forms]))

;; ---------------------------------------------------------------------------
;; Comment extraction from :ws metadata
;; ---------------------------------------------------------------------------

(defn extract-comments
  "Extract comment lines from a :ws metadata string.
   Returns a vector of trimmed comment strings, or nil."
  [ws]
  (when ws
    (let [lines (str/split-lines ws)]
      (not-empty (mapv str/triml (filterv #(re-find #"^\s*;" %) lines))))))

(defn- form-comments
  "Get comment lines from a form's :ws metadata, or nil."
  [form]
  (when (and (some? form)
             #?(:clj  (instance? clojure.lang.IMeta form)
                :cljs (satisfies? IMeta form))
             (meta form))
    (extract-comments (:ws (meta form)))))

(defn- comment-doc
  "Build a Doc that emits comment lines followed by a hardline.
   Each comment line is on its own line."
  [comments]
  (reduce (fn [acc c]
            (render/cat acc (render/text c) render/hardline))
          nil
          comments))

;; ---------------------------------------------------------------------------
;; Notation helpers
;; ---------------------------------------------------------------------------

(def ^:private head-line-args
  "How many args to keep on the first line with the head.
   nil means no special treatment (default: all args in body)."
  {'def 1, 'def- 1,
   'defn 1, 'defn- 1, 'defmacro 1, 'defmulti 1, 'defmethod 2,
   'defprotocol 1, 'defrecord 1, 'deftype 1,
   'fn 0,
   'let 0, 'loop 0, 'binding 0, 'doseq 0, 'for 0,
   'if 1, 'if-not 1, 'if-let 0, 'if-some 0,
   'when 1, 'when-not 1, 'when-let 0, 'when-some 0, 'when-first 0,
   'cond 0, 'condp 2, 'case 1, 'cond-> 1, 'cond->> 1,
   'try 0, 'catch 2, 'finally 0,
   'do 0,
   'ns 1,
   '-> 1, '->> 1, 'some-> 1, 'some->> 1, 'as-> 2,
   'deftest 1, 'testing 1, 'is 0, 'are 0})

(defn- anon-fn-shorthand?
  "Can (fn [params] body) be printed as #(body)?
   Only when :meme/sugar tagged by reader."
  [form]
  (and (:meme/sugar (meta form))
       (seq? form)
       (= 'fn (first form))
       (= 3 (count form))
       (vector? (second form))))

;; ---------------------------------------------------------------------------
;; Doc helpers for building common structures
;; ---------------------------------------------------------------------------

(defn- intersperse
  "Interleave docs with separator, returning concatenated Doc."
  [sep docs]
  (when (seq docs)
    (reduce (fn [acc d] (render/cat acc sep d)) (first docs) (rest docs))))

;; ---------------------------------------------------------------------------
;; Form → Doc — single source of truth for notation AND formatting
;; ---------------------------------------------------------------------------

(declare to-doc to-doc-form)

(defn- emit-meta-prefix
  "Compute metadata prefix string: ^:key, ^Type, or ^{map}."
  [m mode]
  (cond
    (and (= 1 (count m))
         (keyword? (key (first m)))
         (true? (val (first m))))
    (let [kw (key (first m))]
      (str "^" (pr-str kw)))
    (and (= 1 (count m))
         (contains? m :tag)
         (symbol? (:tag m)))
    (str "^" (:tag m))
    :else
    (str "^" (render/layout (to-doc-form m mode) ##Inf))))

(defn- call-doc
  "Build Doc for a call form. Handles head-line-args and meme/clj modes."
  [head args mode]
  (let [head-doc (to-doc head mode)
        arg-docs (mapv #(to-doc % mode) args)]
    (if (= mode :clj)
      ;; Clojure mode: (head arg1 arg2)
      (if (empty? arg-docs)
        (render/group (render/cat (render/text "(") head-doc (render/text ")")))
        (render/group
         (render/cat
          (render/text "(") head-doc
          (render/nest 2 (render/cat render/line (intersperse render/line arg-docs)))
          (render/text ")"))))
      ;; Meme mode: head(arg1 arg2) with head-line-args
      (let [n-head (get head-line-args head)]
          (cond
            ;; Zero args: head()
            (empty? arg-docs)
            (render/group (render/cat head-doc (render/text "(") (render/text ")")))

            ;; Head-line args: keep n args on head line, rest in body
            (and n-head (pos? n-head) (> (count arg-docs) n-head))
            (let [hl (subvec arg-docs 0 n-head)
                  body (subvec arg-docs n-head)]
              (render/group
               (render/cat
                head-doc (render/text "(")
                (render/nest 2
                             (render/cat
                              (render/group (render/cat render/line0 (intersperse render/line hl)))
                              (reduce (fn [acc d] (render/cat acc render/line d)) nil body)))
                (render/text ")"))))

            ;; Default: all args in body
            :else
            (render/group
             (render/cat
              head-doc (render/text "(")
              (render/nest 2 (render/cat render/line0 (intersperse render/line arg-docs)))
              (render/text ")"))))))))

(defn- collection-doc
  "Build Doc for a delimited collection: [elems], #{elems}, #(body)."
  [open close children mode]
  (if (empty? children)
    (render/text (str open close))
    (let [child-docs (mapv #(to-doc % mode) children)]
      (render/group
       (render/cat
        (render/text open)
        (render/nest 2 (render/cat render/line0 (intersperse render/line child-docs)))
        render/line0
        (render/text close))))))

(defn- pairs-doc
  "Build Doc for key-value pairs: {k v ...}, #:ns{k v ...}, #?(k v ...)."
  [open close entries mode]
  (if (empty? entries)
    (render/text (str open close))
    (let [pair-docs (mapv (fn [[k v]]
                            (render/cat (to-doc k mode) (render/text " ") (to-doc v mode)))
                          entries)]
      (render/group
       (render/cat
        (render/text open)
        (render/nest 2 (render/cat render/line0 (intersperse render/line pair-docs)))
        render/line0
        (render/text close))))))

(defn- to-doc-form
  "Convert a Clojure form to a Doc tree. Handles metadata wrapping.
   mode is :meme (default) or :clj."
  [form mode]
  (cond
    ;; Metadata prefix — checked first, before structural checks.
    (and (some? form)
         #?(:clj (instance? clojure.lang.IMeta form)
            :cljs (satisfies? IMeta form))
         (some? (meta form))
         (seq (forms/strip-internal-meta (meta form))))
    (let [chain (:meme/meta-chain (meta form))
          stripped (with-meta form (select-keys (meta form) forms/notation-meta-keys))
          prefixes (if chain
                     (mapv #(emit-meta-prefix % mode) (reverse chain))
                     [(emit-meta-prefix (forms/strip-internal-meta (meta form)) mode)])]
      (render/cat (render/text (str (str/join " " prefixes) " ")) (to-doc-form stripped mode)))

    ;; Raw value wrapper — emit original source text
    (forms/raw? form) (render/text (:raw form))

    ;; nil
    (nil? form) (render/text "nil")

    ;; boolean
    (boolean? form) (render/text (str form))

    ;; Deferred auto-resolve keywords
    (forms/deferred-auto-keyword? form)
    (render/text (forms/deferred-auto-keyword-raw form))

    ;; Empty list
    (and (seq? form) (empty? form))
    (render/text "()")

    ;; Anon-fn shorthand #()
    ;; In :clj mode, only use #() when the body is a list (call).
    ;; #(42) in Clojure means (fn [] (42)) — calling 42 — not (fn [] 42).
    (and (anon-fn-shorthand? form)
         (or (not= mode :clj) (seq? (nth form 2))))
    (if (and (= mode :clj) (seq? (nth form 2)))
      ;; :clj mode: unwrap body list to avoid double parens.
      ;; (fn [%1] (+ %1 1)) → #(+ %1 1), not #((+ %1 1))
      (collection-doc "#(" ")" (seq (nth form 2)) mode)
      (collection-doc "#(" ")" [(nth form 2)] mode))

    ;; Sequences — check sugar then call
    (seq? form)
    (let [head (first form)]
      (cond
        ;; @deref sugar
        (and (= head 'clojure.core/deref) (:meme/sugar (meta form)))
        (render/cat (render/text "@") (to-doc (second form) mode))

        ;; 'quote sugar
        (and (= head 'quote) (:meme/sugar (meta form)))
        (render/cat (render/text "'") (to-doc (second form) mode))

        ;; #'var sugar
        (and (= head 'var) (:meme/sugar (meta form)))
        (render/cat (render/text "#'") (to-doc (second form) mode))

        ;; Regular call
        :else
        (call-doc head (vec (rest form)) mode)))

    ;; Syntax-quote / unquote / unquote-splicing AST nodes
    ;; Must be before map? (defrecords satisfy map?)
    (forms/syntax-quote? form)
    (render/cat (render/text "`") (to-doc (:form form) mode))

    (forms/unquote? form)
    (render/cat (render/text "~") (to-doc (:form form) mode))

    (forms/unquote-splicing? form)
    (render/cat (render/text "~@") (to-doc (:form form) mode))

    ;; Reader conditional — must be before map?
    (forms/meme-reader-conditional? form)
    (let [prefix (if (forms/rc-splicing? form) "#?@(" "#?(")]
      (pairs-doc prefix ")" (vec (partition 2 (forms/rc-form form))) mode))

    ;; Vector
    (vector? form)
    (collection-doc "[" "]" (vec form) mode)

    ;; Map — reconstruct #:ns{} when :meme/ns metadata present
    (map? form)
    (if-let [ns-str (:meme/ns (meta form))]
      (let [strip-ns (fn [k]
                       (if (and (keyword? k)
                                (= (namespace k)
                                   (if (str/starts-with? ns-str ":") (subs ns-str 1) ns-str)))
                         (keyword (name k))
                         k))]
        (pairs-doc (str "#:" ns-str "{") "}" (mapv (fn [[k v]] [(strip-ns k) v]) form) mode))
      (pairs-doc "{" "}" (vec form) mode))

    ;; Set — use :meme/order for insertion-order output
    (set? form)
    (let [elements (or (:meme/order (meta form)) (seq form))]
      (collection-doc "#{" "}" (vec (or elements [])) mode))

    ;; Symbol
    (symbol? form) (render/text (str form))

    ;; Keyword
    (keyword? form)
    (render/text (if (namespace form)
                   (str ":" (namespace form) "/" (name form))
                   (str ":" (name form))))

    ;; String
    (string? form) (render/text (pr-str form))

    ;; Regex
    (instance? #?(:clj java.util.regex.Pattern :cljs js/RegExp) form)
    (let [raw #?(:clj (.pattern ^java.util.regex.Pattern form) :cljs (.-source form))]
      (render/text (str "#\"" (str/replace raw #"\\.|\"" (fn [m] (if (= m "\"") "\\\"" m))) "\"")))

    ;; Char (JVM only)
    #?@(:clj [(char? form)
              (let [named {(char 10) "newline" (char 13) "return" (char 9) "tab"
                           (char 32) "space" (char 8) "backspace" (char 12) "formfeed"}]
                (render/text (if-let [n (get named form)]
                               (str \\ n)
                               (str \\ form))))])

    ;; Number — preserve BigDecimal M and BigInt N suffixes, symbolic values
    #?@(:clj [(decimal? form) (render/text (str form "M"))
              (instance? clojure.lang.BigInt form) (render/text (str form "N"))
              (instance? java.math.BigInteger form) (render/text (str form "N"))])
    (and (number? form)
         #?(:clj (Double/isNaN (double form))
            :cljs (js/isNaN form)))
    (render/text "##NaN")
    (and (number? form)
         #?(:clj (Double/isInfinite (double form))
            :cljs (and (not (js/isFinite form)) (not (js/isNaN form)))))
    (render/text (if (pos? (double form)) "##Inf" "##-Inf"))
    (number? form) (render/text (str form))

    ;; Tagged literal (JVM only)
    #?@(:clj [(tagged-literal? form)
              (render/cat (render/text (str "#" (.-tag form) " ")) (to-doc (.-form form) mode))])

    ;; Fallback
    :else (render/text (pr-str form))))

(defn to-doc
  "Convert a Clojure form to a Doc tree, with comment attachment.
   Comments are always emitted — the hardline in comment-doc forces the
   enclosing group to break, so comments are never silently dropped.
   mode is :meme (default) or :clj."
  ([form] (to-doc form :meme))
  ([form mode]
   (let [doc (to-doc-form form mode)
         comments (form-comments form)]
     (if comments
       (render/cat (comment-doc comments) doc)
       doc))))
