(ns gateway.common.context.spec.messages
  (:require [clojure.spec.alpha :as s]
            [gateway.common.spec.messages :refer [message-body] :as messages]
            [gateway.state.spec.common :as common]
            [gateway.state.spec.context :as context]
            [gateway.common.context.spec.requests :as requests]))

(s/def ::reason_uri string?)
(s/def ::reason string?)
(s/def ::updater_id ::common/peer_id)

(defmethod message-body :context-updated [_]
  (s/merge ::messages/broadcast
           (s/keys :req-un [::updater_id ::context_id ::requests/delta])))

(defmethod message-body :update-context [_]
  (s/merge ::messages/message-type ::requests/context-update))

(defmethod message-body :context-created [_]
  (s/merge ::messages/response
           (s/keys :req-un [::context_id])))

(s/def ::creator_id ::common/peer_id)
(s/def ::context_id ::context/id)
(defmethod message-body :context-added [_]
  (s/merge ::messages/broadcast
           (s/keys :req-un [::creator_id ::context_id ::context/name])))

(defmethod message-body :subscribed-context [_]
  (s/merge ::messages/response
           (s/keys :req-un [::context_id ::context/data])))

(defmethod message-body :context-destroyed [_]
  (s/merge ::messages/broadcast
           (s/keys :req-un [::context_id ::reason_uri ::reason])))

(defmethod message-body :create-context [_]
  (s/merge ::messages/message-type ::requests/context-create))

(defmethod message-body :destroy-context [_]
  (s/merge ::messages/message-type ::requests/context-destroy))

(defmethod message-body :subscribe-context [_]
  (s/merge ::messages/message-type ::requests/context-subscribe))

(defmethod message-body :unsubscribe-context [_]
  (s/merge ::messages/message-type ::requests/context-unsubscribe))
