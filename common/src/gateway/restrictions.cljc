(ns gateway.restrictions
  (:require [gateway.reason :refer [throw-reason]]

            [instaparse.transform :as ipt]
            [instaparse.core :as ip]
    #?(:cljs [cljs.reader :as reader])))


(def restrictions-parser
  (ip/parser "
    expr=and-or
    <and-or>=<ws*> (eq-neq | and | or)
    <eq-neq>=<ws*> (term | eq | neq | match)
    and=and-or <ws*> <'&&'> eq-neq
    or=and-or <ws*> <'||'> eq-neq
    eq=eq-neq <ws*> <'=='> term
    neq=eq-neq <ws*> <'!='> term
    match=eq-neq <ws*> <'?'> term
    <term>=<ws*> (ident | own-ident | number | str | lparen and-or rparen)
    <lparen> = <ws*'('ws*>
    <rparen> = <ws*')'ws*>
    ident=<'$'> word
    own-ident=<'#'> word
    str=<'\\''> #'[^\\']+' <'\\''>
    word=#'[a-zA-Z]+'
    number=#'[-+]?[0-9]*\\.?[0-9]+'
    ws=#'[\\s\\t]+'
    "))

(defn- invalid? [parsed-restriction]
  (when-not (nil? parsed-restriction)
    (when-let [failure (ip/get-failure parsed-restriction)]
      #?(:clj  (with-out-str (clojure.pprint/pprint failure))
         :cljs (str failure))
      )))

(defn parse
  [restrictions]
  (when (seq restrictions)
    (let [parsed (ip/parse restrictions-parser restrictions)]
      (if-let [errors (invalid? parsed)]
        (throw (ex-info (str "Error parsing restrictions " errors) {:message errors}))
        parsed))))

(defn check? [parsed-restriction own-context context]
  (if (seq parsed-restriction)
    (ipt/transform
      {:and       (fn [lhs rhs] (= lhs rhs))
       :or        (fn [lhs rhs] (or lhs rhs))
       :eq        =
       :neq       not=
       :match     (fn [lhs rhs] (if (and rhs lhs) (re-matches (re-pattern rhs) lhs)))
       :str       identity
       :number    #?(:clj  clojure.edn/read-string
                     :cljs reader/read-string)
       :expr      identity
       :own-ident (fn [[_ idn]] (or (get own-context idn) (get own-context (keyword idn))))
       :ident     (fn [[_ idn]] (or (get context idn) (get context (keyword idn))))
       } parsed-restriction)
    true))

