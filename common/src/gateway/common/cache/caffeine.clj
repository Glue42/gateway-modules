(ns gateway.common.cache.caffeine
  (:require [gateway.common.cache.cache :as c])
  (:import (java.util.concurrent TimeUnit)
           (com.github.benmanes.caffeine.cache Caffeine Cache)))


(defn cache
  ([]
   (cache nil))
  (
   [configuration]
   (let [{:keys [max-size expire-after]
          :or   {max-size 10000 expire-after 10}} configuration]
     (-> (Caffeine/newBuilder)
         (.maximumSize max-size)
         (.expireAfterWrite expire-after (TimeUnit/SECONDS))
         (.build)))))

(deftype CaffeineCache [^Cache cache]
  c/CacheProtocol
  (lookup [this item]
    (let [ret (c/lookup this item ::nope)]
      (when-not (= ::nope ret) ret)))
  (lookup [this item not-found]
    (let [v (.getIfPresent cache item)]
      (if v
        v
        not-found)))
  (has? [_ item]
    (.getIfPresent cache item))
  (hit [this item] this)
  (miss [this item result]
    (.put cache item result)
    this)
  (seed [this base] this)
  (evict [this key]
    (.invalidate cache key)
    this))

(defn caffeine-cache-factory
  [{ttl :ttl :or {ttl 2000}}]
  (->CaffeineCache (cache {:expire-after ttl})))