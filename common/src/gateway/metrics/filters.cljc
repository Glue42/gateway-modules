(ns gateway.metrics.filters
  (:require [gateway.common.filters :as f]))

(defn arg-blacklisted?
  [arg-filters arg]
  (reduce
    (fn [result f] result
      (if (f/value-matches? f arg)
        (reduced true)
        result))
    false
    (:blacklist arg-filters)))

(defn arg-whitelisted?
  [arg-filters arg]
  (if-let [whitelist (:whitelist arg-filters)]
    (reduce
      (fn [result f]
        (if (f/value-matches? f arg)
          (reduced true)
          result))
      false
      whitelist)
    true))

(defn filter-args
  [arg-filters args]
  (if (map? args)
    (reduce-kv
      (fn [filtered k v]
        (cond
          (arg-blacklisted? arg-filters k) (assoc filtered k "<REMOVED>")
          (arg-whitelisted? arg-filters k) (assoc filtered k v)
          :else (assoc filtered k "<REMOVED>")))
      {}
      args)
    args))

(defn method-allowed?
  [filters method-name]
  (let [result (reduce
                 (fn [result filter-config]
                   (if (and (f/value-matches? (:name filter-config) method-name)
                            (:allowed filter-config true))
                     (reduced filter-config)
                     result))
                 nil
                 (:methods filters))]
    (if-not (nil? result)
      result
      (case (:non-matched filters)
        :whitelist {:arguments {:blacklist []}}
        :blacklist nil
        {:arguments {:blacklist []}}))))
