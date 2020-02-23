(ns gateway.state.spec.activity
  (:require [clojure.spec.alpha :as s]
            [gateway.state.spec.factory :as factory]
            [gateway.state.spec.restrictions :as restrictions]
            [gateway.state.spec.context :as context]
            [gateway.state.spec.common :as common]
            [gateway.state.spec.factory :as factory]))

(s/def ::type string?)
(s/def ::name ::type)
(s/def ::configuration (s/map-of some? some?))

(s/def ::activity-peer (s/keys :req-un [::type]
                               :opt-un [::name ::configuration]))
(s/def ::owner_type ::activity-peer)
(s/def ::helper_types (s/coll-of ::activity-peer :kind vector?))
(s/def ::default_context (s/map-of some? some?))
(s/def ::visibility ::restrictions/restrictions)
(s/def ::activity-type (s/keys :req-un [::name ::owner_type]
                               :opt-un [::helper_types ::default_context ::visibility]))
(s/def ::user string?)

;; collection of registered activity types grouped by user
(s/def ::types (s/map-of ::user
                         (s/map-of ::type ::activity-type)))

(s/def ::id string?)

(s/def ::context_id ::context/id)
(s/def ::initiator ::common/peer_id)
(s/def ::owner ::common/peer_id)

(s/def ::ready-members (s/coll-of ::common/peer_id :kind set?))
(s/def ::participants (s/coll-of ::common/peer_id :kind set?))

(s/def ::context ::context/context)
(s/def ::child (s/keys :req-un [::type ::context ::owner] :opt-un [::participants ::properties ::children]))
(s/def ::children (s/map-of ::id ::child))

(s/def ::parent ::id)
(s/def ::ready? boolean?)

;; describes a created activity
(s/def ::activity (s/keys :req-un [
                                   ::id                     ;; the activity id generated by the gateway
                                   ::type                   ;; the activity type
                                   ::context-id             ;; activity's own context
                                   ::initiator              ;; id of the peer that has created it
                                   ]
                          :opt-un [
                                   ::ready?                 ;; flag signalling if the activity is ready (i.e. owner is ready)
                                   ::owner                  ;; owner peer id
                                   ::participants           ;; collection of participant ids
                                   ::ready-members          ;; collection of ready members of the activity. once a member becomes ready, its moved from the participants collection here
                                   ::children               ;; collection of activities that are direct children
                                   ]))

(s/def ::activities (s/map-of ::id ::activity))

(s/def ::activity-domain (s/keys
                           :opt-un [::factory/factories]))

;; peers that have subscribed for activity related events
;; the subscription can happen before the activity type is added and be a wildcard one
(s/def ::activity-subscribers (s/map-of (s/or :type ::type :all (partial = :all))
                                        (s/coll-of ::common/peer_id :kind set?)))
