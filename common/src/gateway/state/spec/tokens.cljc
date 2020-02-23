(ns gateway.state.spec.tokens
  (:require [clojure.spec.alpha :as s]
            [gateway.state.spec.common :as cs]
            [gateway.common.cache.cache :as cache]))

(s/def ::gateway-token string?)
(s/def ::lifetime int?)
(s/def ::impersonate-peer ::cs/peer_id)
(s/def ::gw-request-id ::cs/request_id)
(s/def ::token-data (s/keys :req-un [::impersonate-peer ::gw-request-id]))

;(s/def ::gateway-tokens (s/map-of ::gateway-token ::token-data))

;; this is now replaced by a cache
;(s/def ::gateway-tokens #(satisfies? cache/CacheProtocol %))