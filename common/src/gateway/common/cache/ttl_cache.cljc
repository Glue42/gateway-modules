(ns gateway.common.cache.ttl-cache
  (:require [gateway.common.cache.cache :as c]
            [gateway.common.utilities :refer [current-time]]))

;; shamelessly lifted from core.cache


(defn- key-killer
  [ttl expiry now]
  (let [ks (map key (filter #(> (- now (val %)) expiry) ttl))]
    #(apply dissoc % ks)))

(deftype TTLCache [cache ttl ttl-ms]
  c/CacheProtocol
  (lookup [this item]
    (let [ret (c/lookup this item ::nope)]
      (when-not (= ::nope ret) ret)))
  (lookup [this item not-found]
    (if (c/has? this item)
      (get cache item)
      not-found))
  (has? [_ item]
    (let [t (get ttl item (- ttl-ms))]
      (< (- (current-time)
            t)
         ttl-ms)))
  (hit [this item] this)
  (miss [this item result]
    (let [now (current-time)
          kill-old (key-killer ttl ttl-ms now)]
      (TTLCache. (assoc (kill-old cache) item result)
                 (assoc (kill-old ttl) item now)
                 ttl-ms)))
  (seed [_ base]
    (let [now (current-time)]
      (TTLCache. base
                 (into {} (for [x base] [(key x) now]))
                 ttl-ms)))
  (evict [_ key]
    (TTLCache. (dissoc cache key)
               (dissoc ttl key)
               ttl-ms)))

(defn ttl-cache-factory
  "Returns a TTL cache with the cache and expiration-table initialized to `base` --
   each with the same time-to-live.

   This function also allows an optional `:ttl` argument that defines the default
   time in milliseconds that entries are allowed to reside in the cache."
  [base & {ttl :ttl :or {ttl 2000}}]
  {:pre [(number? ttl) (<= 0 ttl)
         (map? base)]}
  (c/seed (TTLCache. {} {} ttl) base))