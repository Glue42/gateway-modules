(ns gateway.domains.agm.spec.messages
  (:require [gateway.domains.agm.spec.requests :as requests]
            [gateway.domains.agm.spec.register :as register]
            [gateway.domains.agm.spec.unregister :as unregister]

            [gateway.common.spec.messages :refer [message-body] :as messages]
            [gateway.state.spec.common :as common]

            [clojure.spec.alpha :as s]))

(s/def ::reason_uri string?)
(s/def ::reason string?)

;; outgoing
(s/def ::local boolean?)
(s/def ::meta (s/keys :req-un [::local]))

(defmethod message-body :peer-added [_]
  (s/merge ::messages/broadcast
           (s/keys :req-un [::new_peer_id ::common/identity ::meta])))

(s/def ::removed_id ::common/peer_id)
(defmethod message-body :peer-removed [_]
  (s/merge ::messages/broadcast
           (s/keys :req-un [::removed_id ::reason_uri ::reason])))

(defmethod message-body :methods-removed [_]
  (s/merge ::messages/broadcast
           (s/keys :req-un [::unregister/methods]
                   :opt-un [::reason_uri ::reason])))

(s/def ::source_type #{:local :peer :cluster :node})
(defmethod message-body :methods-added [_]
  (s/merge ::messages/broadcast
           (s/keys :req-un [::requests/server_id ::source_type ::requests/methods])))

(defmethod message-body :add-interest [_]
  (s/merge ::messages/broadcast
           ::requests/add-interest))

(defmethod message-body :remove-interest [_]
  (s/merge ::messages/broadcast
           ::requests/remove-interest))

(defmethod message-body :subscribe [_]
  (s/merge ::messages/message-type
           ::requests/subscribe))

(defmethod message-body :accepted [_]
  (s/merge ::messages/message-type
           ::requests/accepted))

(defmethod message-body :subscribed [_]
  (s/merge ::messages/response
           (s/keys :req-un [::requests/subscription_id])))

(defmethod message-body :drop-subscription [_]
  (s/merge ::messages/message-type
           ::requests/drop-subscription))


(defmethod message-body :subscription-cancelled [_]
  (s/merge ::messages/broadcast
           (s/keys :req-un [::requests/subscription_id ::reason_uri ::reason])))

(defmethod message-body :register [_]
  (s/merge ::messages/message-type
           ::register/register))

(defmethod message-body :unregister [_]
  (s/merge ::messages/message-type
           ::unregister/unregister))

(defmethod message-body :post [_]
  (s/merge ::messages/message-type
           ::requests/post))

(defmethod message-body :publish [_]
  (s/merge ::messages/message-type
           ::requests/publish))

(s/def ::oob boolean?)
(defmethod message-body :event [_]
  (s/merge ::messages/broadcast
           (s/keys :req-un [::requests/subscription_id ::oob ::requests/sequence ::requests/snapshot ::requests/data])))

(defmethod message-body :call [_]
  (s/merge ::messages/message-type
           ::requests/call))

(defmethod message-body :invoke [_]
  (s/merge ::messages/message-type
           ::requests/invoke))

(defmethod message-body :yield [_]
  (s/merge ::messages/message-type
           ::requests/yield))

(defmethod message-body :result [_]
  (s/merge ::messages/response
           (s/keys :req-un [::requests/result])))
