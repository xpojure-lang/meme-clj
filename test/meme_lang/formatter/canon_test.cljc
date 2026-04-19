(ns meme-lang.formatter.canon-test
  (:require [clojure.test :refer [deftest is testing]]
            [meme-lang.formatter.canon :as fmt-canon]
            [meme-lang.api :as lang]))

;; ---------------------------------------------------------------------------
;; Flat output — forms that fit within width
;; ---------------------------------------------------------------------------

(deftest flat-primitives
  (is (= "42" (fmt-canon/format-form 42)))
  (is (= ":foo" (fmt-canon/format-form :foo)))
  (is (= "\"hello\"" (fmt-canon/format-form "hello")))
  (is (= "nil" (fmt-canon/format-form nil)))
  (is (= "true" (fmt-canon/format-form true))))

(deftest flat-call
  (testing "call that fits stays flat"
    (is (= "+(1 2)" (fmt-canon/format-form '(+ 1 2)))))
  (testing "zero-arg call"
    (is (= "foo()" (fmt-canon/format-form '(foo))))))

(deftest flat-collections
  (is (= "[1 2 3]" (fmt-canon/format-form [1 2 3])))
  (is (= "{:a 1}" (fmt-canon/format-form {:a 1})))
  (is (= "#{42}" (fmt-canon/format-form #{42}))))

;; ---------------------------------------------------------------------------
;; Multi-line — parenthesized calls exceeding width
;; ---------------------------------------------------------------------------

(deftest multi-line-all-in-body
  (testing "let (head-line-args=1) keeps binding vector on head line"
    (let [result (fmt-canon/format-form '(let [x 1] (+ x 1)) {:width 15})]
      (is (= "let( [x 1]\n  +(x 1)\n)" result)))))

(deftest multi-line-head-line-args
  (testing "defn (head-line-args=1) keeps name on first line"
    (let [result (fmt-canon/format-form
                  '(defn greet [name] (str "Hello " name))
                  {:width 30})]
      (is (re-find #"^defn\( greet \[name\]\n" result))
      (is (re-find #"\)$" result)))))

(deftest multi-line-if-keeps-condition
  (testing "if (head-line-args=1) keeps condition on first line"
    (let [result (fmt-canon/format-form
                  '(if (> x 0) "positive" "negative")
                  {:width 30})]
      (is (re-find #"^if\( >\(x 0\)" result)))))

(deftest multi-line-defmethod-keeps-signature
  (testing "defmethod signature slots (name, dispatch-val, params) stay on head line when they fit"
    (let [result (fmt-canon/format-form
                  '(defmethod area :circle [x] (* Math/PI (* x x)))
                  {:width 35})]
      (is (re-find #"^defmethod\( area :circle \[x\]" result))))
  (testing "narrow width breaks all head-line slots uniformly"
    (let [result (fmt-canon/format-form
                  '(defmethod area :circle [{:keys [radius]}] (* Math/PI (* radius radius)))
                  {:width 40})]
      ;; At a width that cannot fit the full signature, the head group
      ;; breaks — each slot on its own line — rather than demoting some
      ;; arbitrary suffix to the body.
      (is (re-find #"^defmethod\( area" result))
      (is (re-find #":circle" result))
      (is (re-find #"\[\{:keys \[radius\]\}\]" result)))))

(deftest head-args-fallback-when-too-wide
  (testing "head-line args that don't fit fall back to all-in-body"
    (let [result (fmt-canon/format-form
                  '(defn a-very-long-function-name [x] (+ x 1))
                  {:width 25})]
      ;; name is too wide for first line — break-space still emits space
      (is (re-find #"^defn\( " result)))))

;; ---------------------------------------------------------------------------
;; Width parameter
;; ---------------------------------------------------------------------------

(deftest width-forces-layout
  (testing "narrow width forces multi-line"
    (is (re-find #"\n" (fmt-canon/format-form '(+ 1 2 3 4 5) {:width 10}))))
  (testing "wide width keeps flat"
    (is (not (re-find #"\n" (fmt-canon/format-form '(+ 1 2 3 4 5) {:width 200}))))))

;; ---------------------------------------------------------------------------
;; Collections — multi-line
;; ---------------------------------------------------------------------------

(deftest multi-line-vector
  (let [result (fmt-canon/format-form [1 2 3 4 5 6 7 8 9 10] {:width 15})]
    (is (re-find #"^\[" result))
    (is (re-find #"\n" result))
    (is (re-find #"\]$" result))))

(deftest multi-line-map
  (let [result (fmt-canon/format-form (sorted-map :a 1 :b 2 :c 3 :d 4) {:width 15})]
    (is (re-find #"^\{" result))
    (is (re-find #"\n" result))
    (is (re-find #"\}$" result))))

(deftest multi-line-set
  (let [result (fmt-canon/format-form (sorted-set 1 2 3 4 5 6 7 8 9 10) {:width 15})]
    (is (re-find #"^#\{" result))
    (is (re-find #"\n" result))
    (is (re-find #"\}$" result))))

;; ---------------------------------------------------------------------------
;; Comments from :meme/leading-trivia metadata
;; ---------------------------------------------------------------------------

(deftest comment-before-form
  (let [form (with-meta '(foo x) {:meme/leading-trivia "; a comment\n"})
        result (fmt-canon/format-form form)]
    (is (re-find #"^; a comment\n" result))
    (is (re-find #"foo\(x\)" result))))

(deftest multiple-comment-lines
  (let [form (with-meta '(foo) {:meme/leading-trivia "; line 1\n; line 2\n"})
        result (fmt-canon/format-form form)]
    (is (re-find #"; line 1\n; line 2\n" result))))

(deftest no-ws-metadata
  (testing "form without :meme/leading-trivia has no comment prefix"
    (is (= "+(1 2)" (fmt-canon/format-form '(+ 1 2))))))

;; ---------------------------------------------------------------------------
;; format-forms — multiple forms
;; ---------------------------------------------------------------------------

(deftest format-forms-separated-by-blank-lines
  (let [result (fmt-canon/format-forms ['(def x 42) '(println x)])]
    (is (= "def( x 42)\n\nprintln(x)" result))))

(deftest format-forms-trailing-comments
  (let [forms (with-meta ['(def x 1)] {:trailing-ws "\n; end of file\n"})
        result (fmt-canon/format-forms forms)]
    (is (re-find #"; end of file" result))))

(deftest format-forms-single
  (is (= "42" (fmt-canon/format-forms [42]))))

;; ---------------------------------------------------------------------------
;; Format roundtrip: formatted output must re-parse to same forms
;; ---------------------------------------------------------------------------

(deftest format-roundtrip-single-forms
  (doseq [[label form]
          [["def"         '(def x 42)]
           ["defn"        '(defn greet [name] (println (str "Hello " name)))]
           ["let"         '(let [x 1 y 2] (+ x y))]
           ["if"          '(if (> x 0) "positive" "negative")]
           ["when"        '(when (> x 0) (println x) x)]
           ["cond"        '(cond (> x 0) "pos" (< x 0) "neg" :else "zero")]
           ["case"        '(case x 1 "one" 2 "two" "other")]
           ["try"         '(try (risky) (catch Exception e (handle e)) (finally (cleanup)))]
           ["threading"   '(->> xs (filter odd?) (map inc) (reduce +))]
           ["ns"          '(ns my.app (:require [clojure.string :as str]))]
           ["defprotocol" '(defprotocol Drawable (draw [this canvas]))]
           ["defrecord"   '(defrecord Circle [center radius])]
           ["loop"        '(loop [i 0 acc []] (if (>= i 5) acc (recur (inc i) (conj acc i))))]
           ["for"         '(for [x xs :when (> x 0)] (* x x))]
           ["fn"          '(fn [x y] (+ x y))]]]
    (testing (str label " format roundtrip")
      (let [pp (fmt-canon/format-form form {:width 40})
            re-parsed (lang/meme->forms pp)]
        (is (= [form] re-parsed)
            (str label " failed:\n" pp))))))

(deftest format-roundtrip-nested-multi-line
  (testing "multi-level nested multi-line re-parses correctly"
    (let [form '(defn process [items]
                  (let [result (for [item items]
                                 (if (even? item) (* item 2) item))]
                    (reduce + 0 result)))
          pp (fmt-canon/format-form form {:width 40})
          re-parsed (lang/meme->forms pp)]
      (is (= [form] re-parsed))
      (is (re-find #"\n" pp) "should be multi-line at this width"))))

(deftest format-roundtrip-multi-forms
  (testing "format-forms output re-parses to same forms"
    (let [forms ['(ns my.app) '(def x 42) '(defn f [x] (+ x 1))]
          pp (fmt-canon/format-forms forms {:width 40})
          re-parsed (lang/meme->forms pp)]
      (is (= forms re-parsed)))))

;; ---------------------------------------------------------------------------
;; Comment preservation roundtrip: source → parse → format → comments intact
;; ---------------------------------------------------------------------------

;; NOTE: The experimental pipeline's CST reader attaches trivia (:meme/leading-trivia) from
;; the head token's trivia, which may not propagate to call-level metadata.
;; Comments on the head of a call may be lost. Tests verify form correctness.
(deftest comment-roundtrip-before-form
  (testing "form after comment parses correctly"
    (let [src "; header comment\ndef(x 42)"
          forms (lang/meme->forms src)]
      (is (= '[(def x 42)] forms)))))

(deftest comment-roundtrip-between-forms
  (testing "forms around comment parse correctly"
    (let [src "def(x 1)\n; middle comment\ndef(y 2)"
          forms (lang/meme->forms src)]
      (is (= '[(def x 1) (def y 2)] forms)))))

(deftest comment-roundtrip-end-of-line
  (testing "end-of-line comment attached to next form survives"
    (let [src "def(x 1) ; note\ndef(y 2)"
          formatted (fmt-canon/format-forms (lang/meme->forms src) {:width 80})]
      (is (re-find #"; note" formatted)))))

(deftest comment-roundtrip-trailing
  (testing "trailing comment after all forms survives"
    (let [src "def(x 1)\n; end of file"
          formatted (fmt-canon/format-forms (lang/meme->forms src) {:width 80})]
      (is (re-find #"; end of file" formatted)))))

(deftest comment-roundtrip-mid-expression-break
  (testing "comment inside a form appears when format forces multi-line"
    (let [src "defn(foo\n  ; body comment\n  [x]\n  +(x 1))"
          forms (lang/meme->forms src)
          formatted (fmt-canon/format-form (first forms) {:width 15})]
      (is (re-find #"; body comment" formatted))
      (is (re-find #"defn\( foo" formatted)))))

(deftest comment-roundtrip-mid-expression-wide
  (testing "comment inside a form preserved even at wide width"
    (let [src "defn(foo\n  ; body comment\n  [x]\n  +(x 1))"
          forms (lang/meme->forms src)
          formatted (fmt-canon/format-form (first forms) {:width 80})]
      (is (re-find #"; body comment" formatted))
      (is (re-find #"defn\( foo" formatted)))))

(deftest comment-roundtrip-forms-roundtrip
  (testing "formatted output with comments re-parses to same forms"
    (let [src "; top\ndef(x 42)\n; mid\ndefn(f [x] +(x 1))\n; end"
          forms (lang/meme->forms src)
          formatted (fmt-canon/format-forms forms {:width 80})
          re-read (lang/meme->forms formatted)]
      (is (= (pr-str forms) (pr-str re-read))))))

;; ---------------------------------------------------------------------------
;; Comment preservation fixture: comments.meme
;; Covers: Clojure code in comments, meme code in comments, mixed, multiple
;; semicolons (;, ;;, ;;;), commented-out code, multi-line comment blocks,
;; mid-expression comments, trailing comment after all forms.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest comment-preservation-fixture
     (let [src (slurp "test/examples/comments_fixture.meme")
           forms (lang/meme->forms src)
           formatted (fmt-canon/format-forms forms {:width 80})
           expected-comments
           ["; Clojure code in comment: (defn foo [x] (+ x 1))"
            ";; Meme code in comment: defn(foo [x] +(x 1))"
            "; Mixed: (+ 1 2) and defn(bar [y] *(y 2))"
            ";; double semicolon comment"
            ";;; triple semicolon comment"
            "; comment with parens () [] {} and special chars"
            ";; Commented-out Clojure: (defn old-fn [x] (str x \" world\"))"
            ";; Commented-out meme: defn(old-fn [x] str(x \" world\"))"
            "; multiple comments"
            "; before a single"
            "; form"
            "; comment inside function body"
            "; end of file comment"]]
       (testing "all comments survive parse→format"
         (doseq [comment expected-comments]
           (is (re-find (re-pattern (java.util.regex.Pattern/quote comment)) formatted)
               (str "missing comment: " comment))))
       (testing "forms survive roundtrip through commented source"
         (let [re-read (lang/meme->forms formatted)]
           (is (= (pr-str forms) (pr-str re-read)))))
       (testing "formatting is idempotent with comments"
         (let [re-read (lang/meme->forms formatted)
               re-formatted (fmt-canon/format-forms re-read {:width 80})]
           (is (= formatted re-formatted)))))))

;; ---------------------------------------------------------------------------
;; Idempotency: format(format(x)) == format(x)
;; ---------------------------------------------------------------------------

(deftest format-idempotent-flat
  (testing "forms that fit on one line are idempotent"
    (doseq [form ['(def x 42)
                  '(+ 1 2)
                  '(f x y z)
                  '[1 2 3]
                  '{:a 1 :b 2}]]
      (let [fmt1 (fmt-canon/format-form form {:width 80})
            reparsed (first (lang/meme->forms fmt1))
            fmt2 (fmt-canon/format-form reparsed {:width 80})]
        (is (= fmt1 fmt2) (str "not idempotent: " (pr-str form)))))))

(deftest format-idempotent-multiline
  (testing "multi-line forms are idempotent"
    (doseq [form ['(defn greet [name] (println (str "Hello " name)))
                  '(let [x 1 y 2 z 3] (+ x y z))
                  '(cond (> x 0) "pos" (< x 0) "neg" :else "zero")]]
      (let [fmt1 (fmt-canon/format-form form {:width 30})
            reparsed (first (lang/meme->forms fmt1))
            fmt2 (fmt-canon/format-form reparsed {:width 30})]
        (is (= fmt1 fmt2) (str "not idempotent: " (pr-str form)))))))

(deftest format-idempotent-nested
  (testing "deeply nested forms are idempotent"
    (let [form '(defn process [items]
                  (let [result (for [item items]
                                 (if (even? item) (* item 2) item))]
                    (reduce + 0 result)))
          fmt1 (fmt-canon/format-form form {:width 40})
          reparsed (first (lang/meme->forms fmt1))
          fmt2 (fmt-canon/format-form reparsed {:width 40})]
      (is (= fmt1 fmt2)))))

(deftest format-exact-indentation
  (testing "let body indented by 2"
    (is (= "let( [x 1]\n  +(x 1)\n)"
           (fmt-canon/format-form '(let [x 1] (+ x 1)) {:width 15}))))
  (testing "defn name on head line, body indented by 2"
    (let [result (fmt-canon/format-form '(defn f [x] (+ x 1)) {:width 15})]
      (is (= "defn( f [x]\n  +(x 1)\n)" result))))
  (testing "nested multi-line indentation compounds"
    (let [result (fmt-canon/format-form '(defn f [x] (let [y 1] (+ x y))) {:width 15})]
      (is (re-find #"\n    " result) "inner let body should be indented 4 spaces"))))
