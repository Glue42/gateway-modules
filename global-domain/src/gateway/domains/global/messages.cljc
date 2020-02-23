(ns gateway.domains.global.messages
  (:require
    [gateway.common.messages :as m]
    #?(:cljs [gateway.reason :refer [Reason]])
    [gateway.domains.global.constants :as constants])
  #?(:clj
     (:import [gateway.reason Reason])))

(defn join
  [request-id peer-id restrictions domain-uri peer-identity]
  (cond-> {:domain      constants/global-domain-uri
           :type        :join
           :request_id  request-id
           :peer_id     peer-id
           :destination domain-uri
           :identity    peer-identity}
          restrictions (assoc :restrictions restrictions)))

(defn leave
  (
   [recipient request-id peer-id domain-uri]
   (m/outgoing recipient (leave request-id peer-id domain-uri)))
  (
   [request-id peer-id domain-uri]
   {:domain      constants/global-domain-uri
    :type        :leave
    :request_id  request-id
    :peer_id     peer-id
    :destination domain-uri}))

(defn context-created
  (
   [recipient request-id peer-id context-id]
   (m/outgoing recipient (context-created request-id peer-id context-id)))
  (
   [request-id peer-id context-id]
   {:domain     constants/global-domain-uri
    :type       :context-created
    :request_id request-id
    :peer_id    peer-id
    :context_id context-id}))

(defn subscribed-context
  (
   [recipient request-id peer-id context-id data]
   (m/outgoing recipient (subscribed-context request-id peer-id context-id data)))
  (
   [request-id peer-id context-id data]
   {:domain     constants/global-domain-uri
    :type       :subscribed-context
    :request_id request-id
    :peer_id    peer-id
    :context_id context-id
    :data       data}))

(defn context-added
  (
   [recipient peer-id creator-id ctx-id ctx-name]
   (m/outgoing recipient (context-added peer-id creator-id ctx-id ctx-name)))
  (
   [peer-id creator-id ctx-id ctx-name]
   {:domain     constants/global-domain-uri
    :type       :context-added
    :peer_id    peer-id
    :creator_id creator-id
    :context_id ctx-id
    :name       ctx-name}))

(defn context-destroyed
  (
   [recipient peer-id ctx-id ^Reason reason]
   (m/outgoing recipient (context-destroyed peer-id ctx-id reason)))
  (
   [peer-id ctx-id ^Reason reason]
   {:domain     constants/global-domain-uri
    :type       :context-destroyed
    :peer_id    peer-id
    :context_id ctx-id
    :reason_uri (:uri reason)
    :reason     (:message reason)}))

(defn context-updated
  (
   [recipient peer-id updater-id context-id delta]
   (m/outgoing recipient (context-updated peer-id updater-id context-id delta)))
  (
   [peer-id updater-id context-id delta]
   {:domain     constants/global-domain-uri
    :type       :context-updated
    :peer_id    peer-id
    :updater_id updater-id
    :context_id context-id
    :delta      delta}))

(defn welcome
  ([recipient request-id peer-id available-domains resolved-identity options]
   (m/outgoing recipient (welcome request-id peer-id available-domains resolved-identity options)))
  (
   [request-id peer-id available-domains resolved-identity options]
   (let [result {:domain            constants/global-domain-uri
                 :type              :welcome
                 :request_id        request-id
                 :peer_id           peer-id
                 :available_domains available-domains
                 :resolved_identity resolved-identity}]
     (if options
       (assoc result :options options)
       result))))

(defn authentication-request
  (
   [domain-uri recipient request-id peer-id details]
   (m/outgoing recipient (authentication-request domain-uri request-id peer-id details)))
  (
   [domain-uri request-id peer-id details]
   (cond-> {:domain         domain-uri
            :type           :authentication-request
            :request_id     request-id
            :authentication details}
           (some? peer-id) (assoc :peer_id peer-id))))
