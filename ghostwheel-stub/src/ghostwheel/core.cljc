(ns ghostwheel.core
  #?(:cljs (:require-macros ghostwheel.core))
  (:require [clojure.walk :as walk]
            [clojure.spec.alpha :as s]))

(defn clj->cljs
  ([form]
   (clj->cljs form true))
  ([form strip-core-ns]
   (let [ns-replacements (cond-> {"clojure.core"            "cljs.core"
                                  "clojure.test"            "cljs.test"
                                  "clojure.spec.alpha"      "cljs.spec.alpha"
                                  "clojure.spec.test.alpha" "cljs.spec.test.alpha"
                                  "orchestra.spec.test"     "orchestra-cljs.spec.test"
                                  "clojure.spec.gen.alpha"  "cljs.spec.gen.alpha"}
                                 strip-core-ns (merge {"clojure.core" nil
                                                       "cljs.core"    nil}))
         replace-namespace #(if-not (qualified-symbol? %)
                              %
                              (let [nspace (namespace %)]
                                (if (contains? ns-replacements nspace)
                                  (symbol (get ns-replacements nspace) (name %))
                                  %)))]
     (walk/postwalk replace-namespace form))))

(defn clean-defn
  "This removes the gspec and returns a
  clean defn for use in production builds."
  [op forms]
  (let [single-arity? (fn [fn-forms] (boolean (some vector? fn-forms)))
        strip-gspec (fn [body] (let [[args _gspec & more] body]
                                 (cons args more)))]
    (->> (if (single-arity? forms)
           (let [[head-forms body-forms] (split-with (complement vector?) forms)]
             `(~op ~@head-forms ~@(strip-gspec body-forms)))
           (let [[head-forms body-forms tail-attr-map] (partition-by (complement seq?) forms)]
             `(~op ~@head-forms ~@(map strip-gspec body-forms) ~@tail-attr-map)))
         (remove nil?))))

(defmacro >defn
  "Like defn, but requires a (nilable) gspec definition and generates
  additional `s/fdef`, generative tests, instrumentation code, an
  fspec-based stub, and/or tracing code, depending on the configuration
  metadata and the existence of a valid gspec and non-nil body."
  {:arglists '([name doc-string? attr-map? [params*] gspec prepost-map? body?]
               [name doc-string? attr-map? ([params*] gspec prepost-map? body?) + attr-map?])}
  [& forms]
  (clean-defn 'defn forms))

(defmacro >defn-
  "Like defn-, but requires a (nilable) gspec definition and generates
  additional `s/fdef`, generative tests, instrumentation code, an
  fspec-based stub, and/or tracing code, depending on the configuration
  metadata and the existence of a valid gspec and non-nil body."
  {:arglists '([name doc-string? attr-map? [params*] gspec prepost-map? body?]
               [name doc-string? attr-map? ([params*] gspec prepost-map? body?) + attr-map?])}
  [& forms]
  (clean-defn 'defn- forms))

(defmacro >fdef
  "Defines an fspec using gspec syntax – pretty much a `>defn` without the body.

  `name` can be a symbol or a qualified keyword, depending on whether the
  fspec is meant to be registered as a top-level fspec (=> s/fdef fn-sym
  ...) or used in other specs (=> s/def ::spec-keyword (s/fspec ...)).

  When defining global fspecs, instrumentation can be directly enabled by
  setting the `^::g/instrument` or `^::g/outstrument` metadata on the symbol."
  {:arglists '([name [params*] gspec]
               [name ([params*] gspec) +])}
  [& forms])

(def => :ret)
(def | :st)
(def <- :gen)

(defmacro ? [& forms]
  (-> `(s/nilable ~@forms)
      clj->cljs))
