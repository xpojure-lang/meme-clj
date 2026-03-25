(ns beme.resolve
  "Value resolution: converts raw token text to Clojure values.
   Centralizes all host reader delegation (read-string calls) that were
   previously scattered across the parser."
  (:require [beme.errors :as errors]
            #?@(:cljs [[cljs.reader]])))

;; ---------------------------------------------------------------------------
;; Host reader delegation — single try/catch pattern
;; ---------------------------------------------------------------------------

(defn- host-read
  "Read a raw string via the host platform's reader, wrapping errors
   with beme location info."
  [raw loc error-prefix]
  (try (#?(:clj clojure.core/read-string :cljs cljs.reader/read-string) raw)
       (catch #?(:clj Exception :cljs :default) e
         (let [cause-msg (#?(:clj ex-message :cljs .-message) e)
               detail (if cause-msg (str error-prefix " " raw " — " cause-msg)
                                    (str error-prefix ": " raw))]
           (errors/beme-error detail (assoc loc :cause e))))))

#?(:clj
(defn- host-read-with-opts
  "Read a raw string via the host platform's reader with options (JVM only)."
  [read-opts raw loc error-prefix]
  (try (clojure.core/read-string read-opts raw)
       (catch Exception e
         (let [cause-msg (ex-message e)
               detail (if cause-msg (str error-prefix " " raw " — " cause-msg)
                                    (str error-prefix ": " raw))]
           (errors/beme-error detail (assoc loc :cause e)))))))

;; ---------------------------------------------------------------------------
;; Value resolvers by type
;; ---------------------------------------------------------------------------

(defn resolve-number [raw loc]
  (host-read raw loc "Invalid number"))

(defn resolve-string [raw loc]
  (host-read raw loc "Invalid string"))

(defn resolve-char [raw loc]
  (host-read raw loc "Invalid character literal"))

(defn resolve-regex [raw loc]
  #?(:clj (host-read raw loc "Invalid regex")
     :cljs (try (js/RegExp. (subs raw 2 (dec (count raw))))
                (catch :default e
                  (let [cause-msg (.-message e)
                        detail (if cause-msg
                                 (str "Invalid regex " raw " — " cause-msg)
                                 (str "Invalid regex: " raw))]
                    (errors/beme-error detail (assoc loc :cause e)))))))

(defn resolve-syntax-quote [raw loc]
  #?(:clj (host-read raw loc "Invalid syntax-quote")
     :cljs (errors/beme-error
             (str "Syntax-quote (`) is not supported in ClojureScript beme reader. Raw form: " raw)
             loc)))

(defn resolve-namespaced-map [raw loc]
  #?(:clj (host-read raw loc "Invalid namespaced map")
     :cljs (errors/beme-error
             (str "Namespaced maps (#:ns{}) are not supported in ClojureScript beme reader. Use .cljc files with Clojure/Babashka instead. Raw form: " raw)
             loc)))

(defn resolve-reader-cond [raw loc]
  #?(:clj (host-read-with-opts {:read-cond :preserve} raw loc "Invalid reader conditional")
     :cljs (errors/beme-error
             (str "Reader conditionals (#?) are not supported in ClojureScript beme reader. Use .cljc files with Clojure/Babashka instead. Raw form: " raw)
             loc)))


(defn resolve-auto-keyword
  "Resolve an auto-resolve keyword (::foo).
   If resolve-fn is provided, resolves at read time.
   Otherwise, defers to eval time via (read-string \"::foo\").
   The printer recognizes this exact encoding to round-trip ::keywords.
   This coupling is intentional: the form must be eval-correct (resolves
   in the user's namespace) and there is no lighter encoding that works
   without a runtime dependency.

   On CLJS, :resolve-keyword is required — without it, :: keywords
   cannot be correctly resolved (cljs.reader/read-string resolves in
   the compiler's namespace, not the caller's)."
  [raw loc resolve-fn]
  (if resolve-fn
    (try (resolve-fn raw)
         (catch #?(:clj Exception :cljs :default) e
           (errors/beme-error (str "Failed to resolve keyword: " raw)
                              (assoc loc :cause e))))
    #?(:clj (list 'clojure.core/read-string raw)
       :cljs (errors/beme-error
               (str "Auto-resolve keywords (" raw ") require the :resolve-keyword option in ClojureScript — without it, namespace resolution is incorrect")
               loc))))

(defn resolve-tagged-literal
  "Resolve a tagged literal. JVM: produces TaggedLiteral. CLJS: error."
  [tag data loc]
  #?(:clj (tagged-literal tag data)
     :cljs (errors/beme-error
             (str "Tagged literals (#" tag ") are not supported in ClojureScript beme reader. Use .cljc files with Clojure/Babashka instead.")
             loc)))
