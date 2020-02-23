(ns gateway.domains.activity.spec.factory-instr
  (:require
    [clojure.spec.alpha :as s]
            [gateway.state.spec.factory :as factory]
            [gateway.state.spec.common :as common]
            [gateway.state.spec.state :as ss]
            [gateway.domains.activity.factories :as factories]
            ))

;;instrumentation

(s/def ::request (s/keys :req-un [::request_id ::common/peer_id]))

(s/def ::factories (s/coll-of ::factory/factory :kind vector?))
(s/def ::factory_ids (s/coll-of ::factory/id ::kind vector?))
(s/def ::add-peer-factories-request (s/merge ::request (s/keys :req-un [::factories])))
(s/def ::remove-peer-factories-request (s/merge ::request (s/keys :req-un [::factory_ids])))

(s/def ::create-peer-request (s/merge ::request (s/keys :req-un [::factory/peer_type])))

(s/fdef factories/add
        :args (s/cat :state (s/spec ::ss/state)
                     :source any?
                     :request (s/spec ::add-peer-factories-request))
        :ret (s/cat :state (s/spec ::ss/state) :messages vector?))

(s/fdef factories/remove-factories
        :args (s/cat :state (s/spec ::ss/state)
                     :source any?
                     :request (s/spec ::remove-peer-factories-request))
        :ret (s/cat :state (s/spec ::ss/state) :messages vector?))

(s/fdef factories/create
        :args (s/cat :state (s/spec ::ss/state)
                     :source any?
                     :request (s/spec ::create-peer-request))
        :ret (s/cat :state (s/spec ::ss/state) :messages vector?))
