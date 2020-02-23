(ns gateway.state.spec.pending-requests
  (:require [clojure.spec.alpha :as s]
            [gateway.state.spec.common :as common]
            [gateway.state.spec.tokens :as tokens]
            [gateway.state.spec.factory :as factory]))

(s/def ::gw-request-id ::common/request_id)

(s/def ::id string?)
(s/def ::owner? boolean?)
(s/def ::activity (s/keys :req-in [::id ::owner?]))

(s/def ::gateway_token ::tokens/gateway-token)
(s/def ::type #{:create-peer :activity})
(s/def ::gateway-request (s/keys :req-un [::id ::type]
                                 :opt-un [
                                          ::client-request
                                          ::activity
                                          ::factory/peer_type]))
(s/def ::gateway-requests (s/map-of ::gw-request-id ::gateway-request))