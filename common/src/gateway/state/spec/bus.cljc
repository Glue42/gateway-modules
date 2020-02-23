(ns gateway.state.spec.bus
  (:require [clojure.spec.alpha :as s]

            [gateway.state.spec.common :as common]))

(s/def ::subscription-id ::common/request_id)

(s/def ::topic string?)
(s/def ::topic-repattern any?)
(s/def ::subscription (s/keys :req-un [::topic]
                              :opt-un [::topic-repattern ::routing-key]))
(s/def ::subscriptions (s/map-of ::subscription-id ::subscription))

(s/def ::bus-domain (s/keys :opt-un [::subscriptions]))
