(ns meme.runtime.resolve
  "Default symbol resolution for syntax-quote expansion.
   Matches Clojure's SyntaxQuoteReader behavior: special forms stay
   unqualified, vars resolve to their defining namespace, unresolved
   symbols get current-namespace qualification.
   JVM/Babashka only — CLJS callers must provide their own resolver."
  #?(:clj (:require [clojure.string :as str])))

#?(:clj
   (def ^:private special-forms
     "Clojure compiler special forms — must stay unqualified in syntax-quote."
     #{'def 'if 'do 'let* 'fn* 'loop* 'try 'catch 'finally 'recur
       'quote 'var 'throw 'new 'set! 'monitor-enter 'monitor-exit
       'deftype* 'reify* 'letfn* 'case* 'clojure.core/import* '& '.}))

#?(:clj
   (defn default-resolve-symbol
     "Resolve a symbol for syntax-quote, matching Clojure's behavior.
   - Already namespace-qualified: keep as-is
   - Special forms: keep unqualified
   - Interop (.method): keep unqualified
   - Resolves to a var: use that var's namespace
   - Resolves to a class: use full class name
   - Unresolved: qualify with current namespace"
     [sym]
     (cond
    ;; Already namespace-qualified
       (namespace sym) sym

    ;; Special form
       (contains? special-forms sym) sym

    ;; Interop: .method stays unqualified
       (str/starts-with? (name sym) ".") sym

    ;; Try to resolve as var or class
       :else
       (if-let [resolved (ns-resolve *ns* sym)]
         (cond
           (var? resolved)
           (symbol (name (ns-name (.-ns ^clojure.lang.Var resolved))) (name sym))

           (class? resolved)
           (let [cname (.getName ^Class resolved)]
             (if (str/ends-with? (name sym) ".")
               (symbol (str cname "."))
               (symbol cname)))

           :else sym)
      ;; Unresolved — qualify with current namespace
         (symbol (name (ns-name *ns*)) (name sym))))))
