(ns gateway.domains.activity.messages
  (:require
    [gateway.common.messages :as m]
    [clojure.set :as set]
    #?(:cljs [gateway.reason :refer [Reason]])
    [gateway.domains.activity.constants :as constants]
    [gateway.state.peers :as peers])
  #?(:clj
     (:import (gateway.reason Reason))))

(defn peer-factories-added
  (
   [recipient peer-id owner-id factories]
   (m/outgoing recipient (peer-factories-added peer-id owner-id factories)))
  (
   [peer-id owner-id factories]
   {:domain    constants/activity-domain-uri
    :type      :peer-factories-added
    :peer_id   peer-id
    :owner_id  owner-id
    :factories factories}))

(defn peer-factories-removed
  (
   [recipient peer-id owner-id factory-ids]
   (m/outgoing recipient (peer-factories-removed peer-id owner-id factory-ids)))
  (
   [peer-id owner-id factory-ids]
   {:domain      constants/activity-domain-uri
    :type        :peer-factories-removed
    :peer_id     peer-id
    :owner_id    owner-id
    :factory_ids factory-ids}))

(defn peer-requested
  (
   [recipient request-id peer-id factory-id token configuration activity-rq]
   (m/outgoing recipient (peer-requested request-id peer-id factory-id token configuration activity-rq)))
  (
   [request-id peer-id factory-id token configuration activity-rq]
   (merge {:domain        constants/activity-domain-uri
           :type          :peer-requested
           :request_id    request-id
           :peer_id       peer-id
           :peer_factory  factory-id
           :gateway_token token
           :configuration configuration}
          activity-rq)))

(defn dispose-peer
  ([recipient peer-id requestor-id ^Reason reason]
   (m/outgoing recipient (dispose-peer peer-id requestor-id reason)))
  (
   [peer-id requestor-id ^Reason reason]
   {:domain       constants/activity-domain-uri
    :type         :dispose-peer
    :peer_id      peer-id
    :requestor_id requestor-id
    :reason_uri   (:uri reason)
    :reason       (:message reason)}))

(defn peer-created
  ([recipient request-id peer-id created-id]
   (m/outgoing recipient (peer-created request-id peer-id created-id)))
  (
   [request-id peer-id created-id]
   {:domain     constants/activity-domain-uri
    :type       :peer-created
    :request_id request-id
    :peer_id    peer-id
    :created_id created-id}))

(defn activity-types-added
  (
   [recipient peer-id types]
   (m/outgoing recipient (activity-types-added peer-id types)))
  (
   [peer-id types]
   {:domain  constants/activity-domain-uri
    :type    :types-added
    :peer_id peer-id
    :types   types}))

(defn activity-types-removed
  (
   [recipient peer-id types]
   (m/outgoing recipient (activity-types-removed peer-id types)))
  (
   [peer-id types]
   {:domain  constants/activity-domain-uri
    :type    :types-removed
    :peer_id peer-id
    :types   types}))

(defn activity-initiated
  (
   [recipient request-id peer-id activity-id]
   (m/outgoing recipient (activity-initiated request-id peer-id activity-id)))
  (
   [request-id peer-id activity-id]
   {:domain      constants/activity-domain-uri
    :type        :initiated
    :request_id  request-id
    :peer_id     peer-id
    :activity_id activity-id}))

(defn filter-context
  [children]
  (into {} (map (fn [[k c]] [k (-> c
                                   (update :context :data)
                                   (update :children filter-context))]) children)))

(defn- peer-info
  [state peer-id]
  (let [p (peers/by-id state peer-id)]
    {:peer_id peer-id
     :name    (:peer_name p)
     :type    (:peer_type p)}))

(defn joined-activity
  (
   [state recipient peer activity context-snapshot]
   (m/outgoing recipient (joined-activity state
                                          peer
                                          activity
                                          context-snapshot)))
  (
   [state peer activity context-snapshot]
   (let [children (:children activity)
         peer-name (:peer_name peer)
         peer-type (:peer_type peer)]
     (cond-> {:domain           constants/activity-domain-uri
              :type             :joined
              :peer_id          (:id peer)
              :activity_id      (:id activity)
              :activity_type    (:type activity)
              :initiator        (:initiator activity)
              :context_id       (:context-id activity)
              :owner            (peer-info state (:owner activity))
              :participants     (mapv (partial peer-info state) (:ready-members activity))
              :context_snapshot context-snapshot}
             peer-name (assoc :peer_name peer-name)
             peer-type (assoc :peer_type peer-type)
             children (assoc :children (filter-context children))))))

(defn activity-joined
  (
   [recipient peer-id joined-peer activity-id]
   (m/outgoing recipient (activity-joined peer-id joined-peer activity-id)))
  (
   [peer-id joined-peer activity-id]
   (let [peer-name (:peer_name joined-peer)]
     (cond-> {:domain      constants/activity-domain-uri
              :type        :activity-joined
              :peer_id     peer-id
              :activity_id activity-id
              :joined_id   (:id joined-peer)
              :joined_type (:peer_type joined-peer)}
             peer-name (assoc :joined_name peer-name)))))

(defn activity-created
  (
   [state recipient peer-id activity]
   (m/outgoing recipient (activity-created state peer-id activity)))
  (
   [state peer-id activity]
   (let [children (:children activity)]
     (cond-> {:domain        constants/activity-domain-uri
              :type          :created
              :peer_id       peer-id
              :activity_id   (:id activity)
              :context_id    (:context-id activity)
              :owner         (peer-info state (:owner activity))
              :participants  (mapv (partial peer-info state)
                                   (set/union (:participants activity)
                                              (:ready-members activity)))
              :activity_type (:type activity)
              :initiator     (:initiator activity)}
             children (assoc :children (filter-context children))))))

(defn activity-left
  (
   [recipient peer-id left-id activity-id ^Reason reason]
   (m/outgoing recipient (activity-left peer-id left-id activity-id reason)))
  (
   [peer-id left-id activity-id ^Reason reason]
   {:domain      constants/activity-domain-uri
    :type        :left
    :peer_id     peer-id
    :activity_id activity-id
    :left_id     left-id
    :reason_uri  (:uri reason)
    :reason      (:message reason)}))

(defn activity-destroyed
  (
   [recipient peer-id activity-id ^Reason reason]
   (m/outgoing recipient (activity-destroyed peer-id activity-id reason)))
  (
   [peer-id activity-id ^Reason reason]
   {:domain      constants/activity-domain-uri
    :type        :destroyed
    :peer_id     peer-id
    :activity_id activity-id
    :reason_uri  (:uri reason)
    :reason      (:message reason)}))

(defn owner-changed
  (
   [recipient peer-id activity-id owner]
   (m/outgoing recipient (owner-changed peer-id activity-id owner)))
  (
   [peer-id activity-id owner]
   (let [peer-name (:peer_name owner)
         peer-type (:peer_type owner)]
     (cond-> {:domain      constants/activity-domain-uri
              :type        :owner-changed
              :peer_id     peer-id
              :activity_id activity-id
              :owner_id    (:id owner)}
             peer-name (assoc :peer_name peer-name)
             peer-type (assoc :peer_type peer-type)))))

(def success (partial m/success constants/activity-domain-uri))
(def error (partial m/error constants/activity-domain-uri))
(def peer-removed (partial m/peer-removed constants/activity-domain-uri))
(def peer-added (partial m/peer-added constants/activity-domain-uri))