(ns gateway.common.utilities
  (:require [clojure.core.async :as a]))

(defn now []
  #?(:clj (System/currentTimeMillis))
  #?(:cljs (.getTime (js/Date.))))


(defn dissoc-in
  "Dissociate a value in a nested associative structure, identified by a sequence
  of keys. Any collections left empty by the operation will be dissociated from
  their containing structures."
  [m ks]
  (if-let [[k & ks] (seq ks)]
    (if (seq ks)
      (let [v (dissoc-in (get m k) ks)]
        (if (zero? (count v))
          (dissoc m k)
          (assoc m k v)))
      (dissoc m k))
    m))

(defn merge-in
  [m ks & vs]
  (apply update-in m ks merge vs))

(defn conj-in
  [m ks & vs]
  (apply update-in m ks (fnil conj #{}) vs))

(defn into-in
  [m ks v]
  (update-in m ks into v))

(defn disj-in
  [m ks dv]
  (if-let [[k & ks] ks]
    (let [v (if-not (seq ks)
              (disj (get m k) dv)
              (disj-in (get m k) ks dv))]

      (if (zero? (count v))
        (dissoc m k)
        (assoc m k v)))
    m))

(defn close-and-flush! [c]
  (when c
    (a/close! c)
    (a/reduce (fn [_ _] nil) [] c)))

(defmacro assert-args
  [& pairs]
  `(do (when-not ~(first pairs)
         (throw (IllegalArgumentException.
                  (str (first ~'&form) " requires " ~(second pairs) " in " ~'*ns* ":" (:line (meta ~'&form))))))
       ~(let [more (nnext pairs)]
          (when more
            (list* `assert-args more)))))


(defn removev [el c]
  (reduce
    (fn [r e] (if (= e el) r (conj r e)))
    []
    c))

(defn find-first
  [pred c]
  (reduce
    (fn [_ el] (when (pred el) (reduced el)))
    c))

(defn ensure-domain
  [peer domain]
  (if-not (get peer domain)
    (assoc peer domain {})
    peer))

(defmacro ?->
  [expr & forms]
  (let [g (gensym)
        steps (map (fn [step] `(let [g# ~g
                                     x# (-> g# ~step)]
                                 (or x# g#)))
                   forms)]
    `(let [~g ~expr
           ~@(interleave (repeat g) (butlast steps))]
       ~(if (empty? steps)
          g
          (last steps)))))

(defmacro state->
  "Takes a [state actions] expression and threads the state value thru functions that are expected to
  return [new-state more-action(s)].

  The returned result's new-state is used for the next thread where the more-actions are added to the actions list.
  If a function returns nil, its result is discarded and the previous expression is threaded thru the next function"

  [expr & forms]
  (let [g (gensym)
        steps (map (fn [step] `(let [[os# om#] ~g
                                     [s# m#] (-> os# ~step)]
                                 [(or s# os#)
                                  (cond
                                    (or (seq? m#) (vector? m#)) (reduce (fnil conj []) om# m#)
                                    (some? m#) ((fnil conj []) om# m#)
                                    :else om#)]))
                   forms)]
    `(let [~g ~expr
           ~@(interleave (repeat g) (butlast steps))]
       ~(if (empty? steps)
          g
          (last steps)))))

(defn keywordize [m]
  (reduce-kv #(assoc %1 (keyword %2) %3) {} m))

(defn conj-if!
  [coll el]
  (if-not el
    coll
    (conj! coll el)))

(defn ex-msg
  [ex]
  #?(:clj  (.getMessage ex)
     :cljs (ex-message ex)))

(defn current-time []
  #?(:clj (System/currentTimeMillis))
  #?(:cljs (.getTime (js/Date.))))

(def os
  #?(:clj  (-> (System/getProperties)
               (.get "os.name"))
     :cljs "unknown"))




