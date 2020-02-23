(ns gateway.state.spec.factory
  (:require [clojure.spec.alpha :as s]))

(s/def ::id int?)
(s/def ::peer_type string?)
(s/def ::configuration (s/map-of some? any?))
(s/def ::flags (s/map-of some? any?))

(s/def ::factory (s/keys
                   :req-un [::id ::peer_type]
                   :opt-un [::configuration ::flags]))

(s/def ::factories (s/map-of ::peer_type ::factory))

