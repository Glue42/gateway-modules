(ns gateway.domains.context.messages
  (:require [gateway.common.messages :as m]
            [gateway.common.context.messages :as ctx-msg]

            [gateway.domains.context.constants :as constants]))


(def context-created (partial ctx-msg/context-created constants/context-domain-uri))
(def subscribed-context (partial ctx-msg/subscribed-context constants/context-domain-uri))
(def context-added (partial ctx-msg/context-added constants/context-domain-uri))
(def context-destroyed (partial ctx-msg/context-destroyed constants/context-domain-uri))
(def context-updated (partial ctx-msg/context-updated constants/context-domain-uri))
(def success (partial m/success constants/context-domain-uri))
(def error (partial m/error constants/context-domain-uri))

(defn create-context
  [request-id peer-id name data lifetime read-permissions write-permissions version]
  {:domain            constants/context-domain-uri
   :type              :create-context
   :request_id        request-id
   :peer_id           peer-id
   :name              name
   :data              data
   :lifetime          lifetime
   :read_permissions  read-permissions
   :write_permissions write-permissions
   :version           version})

(defn subscribe
  [request-id peer-id context-id]
  {:domain     constants/context-domain-uri
   :type       :subscribe-context
   :peer_id    peer-id
   :request_id request-id
   :context_id context-id})