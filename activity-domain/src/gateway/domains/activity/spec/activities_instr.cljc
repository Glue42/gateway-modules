(ns gateway.domains.activity.spec.activities-instr
  (:require
    [clojure.spec.alpha :as s]
            [gateway.state.spec.activity :as activity-spec]
            [gateway.state.spec.common :as common-spec]
            [gateway.state.spec.state :as state-spec]
            [gateway.domains.activity.activities :as activities]))

;;instrumentation

(s/def ::request (s/keys :req-un [::request_id ::common-spec/peer_id]))

;; registering an activity type

(s/fdef activities/add-types
        :args (s/cat :state (s/spec ::state-spec/state)
                     :source any?
                     :request-id (s/spec ::common-spec/request_id)
                     :peer-id (s/spec ::common-spec/peer_id)
                     :types (s/coll-of ::activity-spec/activity-type))
        :ret (s/cat :state (s/spec ::state-spec/state) :messages vector?))

;; creating an activity

(s/def ::activity_type ::activity-spec/type)
(s/def ::initial_context (s/map-of some? some?))
(s/def ::owner_type ::activity-spec/owner_type)
(s/def ::helper_types ::activity-spec/helper_types)
(s/def ::configuration (s/coll-of ::activity-spec/activity-peer))
(s/def ::types_override (s/keys :opt-un [::owner_type ::helper_types]))
(s/def ::read_permissions string?)
(s/def ::write_permissions string?)
(s/def ::create-activity-request (s/merge ::request (s/keys :req-un [::activity_type]
                                                            :opt-un [::initial_context
                                                                     ::configuration
                                                                     ::types_override
                                                                     ::read_permissions
                                                                     ::write_permissions])))
(s/fdef activities/create-activity
        :args (s/cat :state (s/spec ::state-spec/state)
                     :source any?
                     :request (s/spec ::create-activity-request)
                     :configuration (s/nilable (s/spec ::configuration)))
        :ret (s/cat :state (s/spec ::state-spec/state) :messages vector?))
