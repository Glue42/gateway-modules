(ns gateway.domains.bus.messages
  (:require
    [gateway.common.messages :as m]
    #?(:cljs [gateway.reason :refer [Reason]])
    [gateway.domains.bus.constants :as constants])
  #?(:clj
     (:import [gateway.reason Reason])))



(defn event
  (
   [recipient subscriber-id subscription-id publisher-identity data]
   (m/outgoing recipient (event subscriber-id subscription-id publisher-identity data)))
  (
   [subscriber-id subscription-id publisher-identity data]
   {:domain             constants/bus-domain-uri
    :type               :event
    :peer_id            subscriber-id
    :subscription_id    subscription-id
    :publisher-identity publisher-identity
    :data               data}))

(defn subscribed
  (
   [recipient request-id subscriber-id subscription-id]
   (m/outgoing recipient (subscribed request-id subscriber-id subscription-id)))
  (
   [request-id subscriber-id subscription-id]
   {:domain          constants/bus-domain-uri
    :type            :subscribed
    :request_id      request-id
    :peer_id         subscriber-id
    :subscription_id subscription-id}))

(defn subscribe
  [peer-id topic routing-key subscription-id]
  (cond-> {:domain          constants/bus-domain-uri
           :type            :subscribe
           :peer_id         peer-id
           :topic           topic
           :subscription_id subscription-id}
          routing-key (assoc :routing_key routing-key)))