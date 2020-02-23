(ns gateway.state.spec.agm
  (:require [clojure.spec.alpha :as s]

            [gateway.state.spec.restrictions :as rs]
            [gateway.state.spec.mthds :as methods]
            [gateway.state.spec.common :as common]))

(s/def ::method_id ::methods/id)
(s/def ::invocation_id ::common/request_id)
(s/def ::subscription-id ::common/request_id)
(s/def ::request_id ::common/request_id)

(s/def ::callee ::common/peer_id)
(s/def ::call (s/keys :req-un [::callee ::method_id ::invocation_id]))
(s/def ::calls (s/map-of ::request_id ::call))

(s/def ::caller ::common/peer_id)
(s/def ::invocation (s/keys :req-un [::caller ::method_id ::request_id]))
(s/def ::invocations (s/map-of ::invocation_id ::invocation))

(s/def ::server ::common/peer_id)
(s/def ::subscription (s/keys :req-un [::server ::method_id ::request_id]))
(s/def ::subscriptions (s/map-of ::subscription-id ::subscription))

(s/def ::subscriber ::common/peer_id)
(s/def ::interest (s/keys :req-in [::subscriber ::methods/id]))
(s/def ::interests (s/map-of ::subscription-id ::interest))

(s/def ::stream-id string?)
(s/def ::stream-peer-info (s/map-of ::common/peer_id (s/coll-of ::subscription-id :kind set?)))
(s/def ::streams (s/map-of ::stream-id ::stream-peer-info))

(s/def ::agm-domain (s/keys :opt-un [::rs/restrictions
                                     ::methods/methods
                                     ::calls
                                     ::invocations
                                     ::subscriptions
                                     ::interests
                                     ::streams]))