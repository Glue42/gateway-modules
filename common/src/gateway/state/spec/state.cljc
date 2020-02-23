(ns gateway.state.spec.state
  (:require [clojure.spec.alpha :as s]

            [gateway.state.spec.common :as common]
            [gateway.state.spec.context :as ctxs]
            [gateway.state.spec.activity :as activity]
            [gateway.state.spec.agm :as agm]
            [gateway.state.spec.bus :as bus]
            [gateway.state.spec.pending-requests :as requests]
            [gateway.state.spec.factory :as factory]
            [gateway.state.spec.tokens :as tokens]
            [gateway.state.spec.domain-registry :as dr]))

(s/def ::id int?)

(s/def ::creation-request ::requests/gateway-request)
(s/def ::peer (s/keys :req-un [::common/source ::common/identity]
                      :opt-un [::options
                               ::agm/agm-domain
                               ::activity/activity-domain
                               ::bus/bus-domain
                               ::creation-request           ;; holds the originating request, if this peer has been created as part of an activity or factory request
                               ::factory/peer_type]))

(s/def ::peers (s/map-of ::common/peer_id ::peer))

(s/def ::users (s/map-of (s/or ::user-name string? ::no-user #{:no-user})
                         (s/coll-of ::common/peer_id)))
(s/def ::services (s/coll-of ::common/peer_id))

(s/def ::contexts ::ctxs/contexts)
(s/def ::activity-types ::activity/types)
(s/def ::activity-subscribers ::activity/activity-subscribers)

(s/def ::identities (s/map-of ::common/identity ::common/peer_id))

(s/def ::handler-ch any?)


;; ids
(s/def ::node-id string?)
(s/def ::current-id pos?)
(s/def ::ids (s/keys :req-un [::node-id ::current-id]))

(s/def ::signature-key string?)

(s/def ::state (s/keys :req-un [::ids ::signature-key]
                       :opt-un [::handler-ch
                                ::dr/registered-domains
                                ::peers
                                ::users
                                ::services
                                ::identities
                                ::contexts
                                ::requests/gateway-requests
                                ::activity/activities
                                ::activity-types
                                ::activity-subscribers]))

