(ns gateway.common.context.messages
  (:require [gateway.common.messages :as m]))

(defn context-updated
  [domain-uri recipient peer-id updater-id context-id delta]
  (m/outgoing recipient {:domain     domain-uri
                         :type       :context-updated
                         :peer_id    peer-id
                         :updater_id updater-id
                         :context_id context-id
                         :delta      delta}))


(defn context-created
  [domain-uri recipient request-id peer-id context-id]
  (m/outgoing recipient {:domain     domain-uri
                         :type       :context-created
                         :request_id request-id
                         :peer_id    peer-id
                         :context_id context-id}))

(defn subscribed-context
  [domain-uri recipient request-id peer-id context-id data]
  (m/outgoing recipient {:domain     domain-uri
                         :type       :subscribed-context
                         :request_id request-id
                         :peer_id    peer-id
                         :context_id context-id
                         :data       data}))

(defn context-added
  [domain-uri recipient peer-id creator-id ctx-id ctx-name]
  (m/outgoing recipient {:domain     domain-uri
                         :type       :context-added
                         :peer_id    peer-id
                         :creator_id creator-id
                         :context_id ctx-id
                         :name       ctx-name}))

(defn context-destroyed
  [domain-uri recipient peer-id ctx-id reason]
  (m/outgoing recipient {:domain     domain-uri
                         :type       :context-destroyed
                         :peer_id    peer-id
                         :context_id ctx-id
                         :reason_uri (:uri reason)
                         :reason     (:message reason)}))