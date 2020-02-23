(ns gateway.common.cache.cache)

;; shamelessly lifted from core.cache

(defprotocol CacheProtocol
  "This is the protocol describing the basic cache capability."
  (lookup [cache e]
    [cache e not-found]
    "Retrieve the value associated with `e` if it exists, else `nil` in
    the 2-arg case.  Retrieve the value associated with `e` if it exists,
    else `not-found` in the 3-arg case.")
  (has? [cache e]
    "Checks if the cache contains a value associated with `e`")
  (hit [cache e]
    "Is meant to be called if the cache is determined to contain a value
    associated with `e`")
  (miss [cache e ret]
    "Is meant to be called if the cache is determined to **not** contain a
    value associated with `e`")
  (evict [cache e]
    "Removes an entry from the cache")
  (seed [cache base]
    "Is used to signal that the cache should be created with a seed.
    The contract is that said cache should return an instance of its
    own type."))