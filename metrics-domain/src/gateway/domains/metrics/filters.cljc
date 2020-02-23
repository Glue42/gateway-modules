(ns gateway.domains.metrics.filters
  (:require [gateway.common.filters :as f]))

(defn publisher-matches?
  [publisher-filter repo-id]
  (reduce-kv
    (fn [_ k v]
      (let [rv (get repo-id k)]
        (if-not (f/value-matches? v rv)
          (reduced false)
          true)))
    true
    publisher-filter))


(defn allowed?
  [filters repo-id name]
  (let [result (reduce
                 (fn [result filter-config]
                   (if (publisher-matches? (:publisher filter-config) repo-id)
                     (cond
                       (f/values-match? (get-in filter-config [:metrics :blacklist]) name) (reduced false)
                       :else (or result (f/values-match? (get-in filter-config [:metrics :whitelist]) name)))

                     result))
                 nil
                 (:publishers filters))]
    (if-not (nil? result)
      result
      (case (:non-matched filters)
        :whitelist true
        :blacklist false
        true))))
