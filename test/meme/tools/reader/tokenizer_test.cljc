(ns meme.tools.reader.tokenizer-test
  "Tests for the experimental byte-level exhaustive tokenizer.
   The fundamental property: token stream is a partition of the input."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [meme.tools.reader.tokenizer :as tok]))

;; ---------------------------------------------------------------------------
;; Partition invariant — the structural guarantee
;; ---------------------------------------------------------------------------

(defn partition-invariant?
  "Verify the token stream is a valid partition of the source."
  [source tokens]
  (and
   ;; Concatenation reproduces input exactly
   (= source (apply str (map :raw tokens)))
   ;; Every token is non-empty
   (every? #(pos? (count (:raw %))) tokens)
   ;; Every token type is from the closed enum
   (every? #(contains? tok/token-types (:type %)) tokens)
   ;; Offsets are contiguous
   (let [offsets (map :offset tokens)]
     (and (or (empty? tokens) (zero? (first offsets)))
          (every? identity
                  (map (fn [t1 t2]
                         (= (+ (:offset t1) (count (:raw t1))) (:offset t2)))
                       tokens (rest tokens)))))))

(deftest empty-input
  (let [tokens (tok/tokenize "")]
    (is (= [] tokens))
    (is (partition-invariant? "" tokens))))

(deftest single-chars
  (doseq [[label input expected-type]
          [["open-paren" "(" :open-paren]
           ["close-paren" ")" :close-paren]
           ["open-bracket" "[" :open-bracket]
           ["close-bracket" "]" :close-bracket]
           ["open-brace" "{" :open-brace]
           ["close-brace" "}" :close-brace]
           ["quote" "'" :quote]
           ["deref" "@" :deref]
           ["meta" "^" :meta]
           ["syntax-quote" "`" :syntax-quote]
           ["unquote" "~" :unquote]]]
    (testing label
      (let [tokens (tok/tokenize input)]
        (is (partition-invariant? input tokens))
        (is (= 1 (count tokens)))
        (is (= expected-type (:type (first tokens))))))))

(deftest whitespace-tokens
  (testing "spaces"
    (let [tokens (tok/tokenize "   ")]
      (is (partition-invariant? "   " tokens))
      (is (= 1 (count tokens)))
      (is (= :whitespace (:type (first tokens))))))
  (testing "tabs"
    (let [tokens (tok/tokenize "\t\t")]
      (is (partition-invariant? "\t\t" tokens))
      (is (= :whitespace (:type (first tokens))))))
  (testing "commas as whitespace"
    (let [tokens (tok/tokenize ",,,")]
      (is (partition-invariant? ",,," tokens))
      (is (= :whitespace (:type (first tokens)))))))

(deftest newline-tokens
  (testing "LF"
    (let [tokens (tok/tokenize "\n")]
      (is (partition-invariant? "\n" tokens))
      (is (= :newline (:type (first tokens))))
      (is (= "\n" (:raw (first tokens))))))
  (testing "CRLF"
    (let [tokens (tok/tokenize "\r\n")]
      (is (partition-invariant? "\r\n" tokens))
      (is (= 1 (count tokens)))
      (is (= :newline (:type (first tokens))))
      (is (= "\r\n" (:raw (first tokens))))))
  (testing "bare CR"
    (let [tokens (tok/tokenize "\r")]
      (is (partition-invariant? "\r" tokens))
      (is (= :newline (:type (first tokens)))))))

(deftest comment-tokens
  (testing "comment to end of line"
    (let [src "; hello world\nfoo"
          tokens (tok/tokenize src)]
      (is (partition-invariant? src tokens))
      (is (= :comment (:type (first tokens))))
      (is (= "; hello world" (:raw (first tokens))))))
  (testing "comment at EOF"
    (let [src "; trailing"
          tokens (tok/tokenize src)]
      (is (partition-invariant? src tokens))
      (is (= 1 (count tokens)))
      (is (= :comment (:type (first tokens)))))))

(deftest string-tokens
  (testing "simple string"
    (let [tokens (tok/tokenize "\"hello\"")]
      (is (partition-invariant? "\"hello\"" tokens))
      (is (= :string (:type (first tokens))))))
  (testing "string with escapes"
    (let [src "\"a\\nb\""]
      (is (partition-invariant? src (tok/tokenize src)))))
  (testing "unterminated string"
    (let [src "\"oops"]
      (let [tokens (tok/tokenize src)]
        (is (partition-invariant? src tokens))
        (is (= :string (:type (first tokens))))
        (is (= src (:raw (first tokens))))))))

(deftest dispatch-tokens
  (testing "#("
    (let [tokens (tok/tokenize "#(")]
      (is (partition-invariant? "#(" tokens))
      (is (= :open-anon-fn (:type (first tokens))))))
  (testing "#{"
    (let [tokens (tok/tokenize "#{")]
      (is (partition-invariant? "#{" tokens))
      (is (= :open-set (:type (first tokens))))))
  (testing "#'"
    (let [tokens (tok/tokenize "#'")]
      (is (partition-invariant? "#'" tokens))
      (is (= :var-quote (:type (first tokens))))))
  (testing "#_"
    (let [tokens (tok/tokenize "#_")]
      (is (partition-invariant? "#_" tokens))
      (is (= :discard (:type (first tokens))))))
  (testing "#?"
    (let [tokens (tok/tokenize "#?")]
      (is (partition-invariant? "#?" tokens))
      (is (= :reader-cond (:type (first tokens))))))
  (testing "#?@"
    (let [tokens (tok/tokenize "#?@")]
      (is (partition-invariant? "#?@" tokens))
      (is (= :reader-cond (:type (first tokens))))))
  (testing "#\"regex\""
    (let [src "#\"\\d+\""]
      (let [tokens (tok/tokenize src)]
        (is (partition-invariant? src tokens))
        (is (= :regex (:type (first tokens)))))))
  (testing "hashtag-symbol (# followed by symbol)"
    (let [tokens (tok/tokenize "#inst")]
      (is (partition-invariant? "#inst" tokens))
      (is (= :hashtag-symbol (:type (first tokens)))))))

(deftest bom-token
  (testing "BOM at start"
    (let [src (str \uFEFF "foo")
          tokens (tok/tokenize src)]
      (is (partition-invariant? src tokens))
      (is (= :bom (:type (first tokens))))
      (is (= "\uFEFF" (:raw (first tokens)))))))

(deftest shebang-token
  (testing "shebang at start"
    (let [src "#!/usr/bin/env bb\nfoo"
          tokens (tok/tokenize src)]
      (is (partition-invariant? src tokens))
      (is (= :shebang (:type (first tokens))))
      (is (= "#!/usr/bin/env bb" (:raw (first tokens)))))))

(deftest keyword-tokens
  (testing "simple keyword"
    (let [tokens (tok/tokenize ":foo")]
      (is (partition-invariant? ":foo" tokens))
      (is (= :keyword (:type (first tokens))))))
  (testing "namespaced keyword"
    (let [tokens (tok/tokenize ":foo/bar")]
      (is (partition-invariant? ":foo/bar" tokens))
      (is (= :keyword (:type (first tokens))))))
  (testing "auto-resolve keyword"
    (let [tokens (tok/tokenize "::foo")]
      (is (partition-invariant? "::foo" tokens))
      (is (= :keyword (:type (first tokens)))))))

(deftest number-tokens
  (doseq [[label src] [["integer" "42"]
                        ["float" "3.14"]
                        ["hex" "0xFF"]
                        ["octal" "0777"]
                        ["ratio" "22/7"]
                        ["bigint" "42N"]
                        ["bigdec" "1.0M"]
                        ["sci-notation" "1e10"]
                        ["negative" "-42"]
                        ["symbolic" "##NaN"]
                        ["symbolic-inf" "##Inf"]]]
    (testing label
      (is (partition-invariant? src (tok/tokenize src))))))

(deftest symbol-tokens
  (doseq [[label src] [["simple" "foo"]
                        ["qualified" "clojure.core/map"]
                        ["with-dash" "my-fn"]
                        ["with-question" "nil?"]
                        ["with-bang" "swap!"]
                        ["gensym" "x__123__auto__"]
                        ["dot-method" ".toString"]
                        ["static" "Math/PI"]]]
    (testing label
      (let [tokens (tok/tokenize src)]
        (is (partition-invariant? src tokens))
        (is (= :symbol (:type (first tokens))))))))

(deftest char-literal-tokens
  (doseq [[label src] [["single" "\\a"]
                        ["newline" "\\newline"]
                        ["unicode" "\\u0041"]
                        ["octal" "\\o101"]]]
    (testing label
      (let [tokens (tok/tokenize src)]
        (is (partition-invariant? src tokens))
        (is (= :char-literal (:type (first tokens))))))))

(deftest invalid-tokens
  (testing "null byte"
    (let [src (str (char 0))
          tokens (tok/tokenize src)]
      (is (partition-invariant? src tokens))
      (is (= :invalid (:type (first tokens))))))
  (testing "BOM mid-file"
    (let [src (str "a" \uFEFF "b")
          tokens (tok/tokenize src)]
      (is (partition-invariant? src tokens))
      ;; BOM mid-file should be :invalid
      (is (some #(= :invalid (:type %)) tokens)))))

;; ---------------------------------------------------------------------------
;; The big one: partition invariant holds for complex real-world input
;; ---------------------------------------------------------------------------

(deftest partition-invariant-real-world
  (doseq [[label src]
          [["full-program"
            "#!/usr/bin/env bb\n(ns my.app)\n\n(defn greet [name]\n  ;; say hello\n  (str \"Hello, \" name \"!\"))\n\n(greet \"world\")"]
           ["meme-syntax"
            "defn(foo [x y]\n  ;; add them\n  +(x y))"]
           ["all-delimiters"
            "() [] {} #{1 2} #(+ % 1) #'var #_ ignored"]
           ["reader-macros"
            "'x @atom ^:meta foo `~x ~@xs"]
           ["data-literals"
            ":kw ::auto :ns/kw 42 3.14 0xFF \"str\" \\a #\"re\" true nil"]
           ["edge-cases"
            "\uFEFF;; BOM file\n\r\n,,, \t "]
           ["empty" ""]
           ["whitespace-only" "   \n\n\t\t,,,"]
           ["binary-garbage" (apply str (map char (range 0 128)))]]]
    (testing label
      (let [tokens (tok/tokenize src)]
        (is (partition-invariant? src tokens)
            (str label " failed partition invariant"))))))

;; ---------------------------------------------------------------------------
;; Line/col tracking
;; ---------------------------------------------------------------------------

(deftest line-col-tracking
  (testing "tokens on different lines"
    (let [tokens (tok/tokenize "a\nb\nc")]
      (is (= 1 (:line (first tokens))))
      (is (= 1 (:col (first tokens))))
      ;; "b" is on line 2
      (let [b-tok (nth tokens 2)] ; a, \n, b
        (is (= 2 (:line b-tok)))
        (is (= 1 (:col b-tok)))))))
