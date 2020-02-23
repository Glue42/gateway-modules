(ns gateway.domains.global.spec.messages
  (:require [gateway.domains.global.spec.requests :as requests]

            [gateway.common.spec.messages :refer [message-body] :as messages]
            [gateway.state.spec.domain-registry :as dr]
            [gateway.state.spec.common :as common]
            [gateway.state.spec.context :as context]
            [gateway.auth.spec :as auth]
            [gateway.domains.global.internal :as internal]

            [clojure.spec.alpha :as s]))

(s/def ::reason_uri string?)
(s/def ::reason string?)

;; incoming
(defmethod message-body :hello [_]
  (s/merge ::messages/message-type ::requests/hello))

(defmethod message-body :join [_]
  (s/merge ::messages/message-type ::requests/join))

(defmethod message-body :leave [_]
  (s/merge ::messages/message-type ::requests/leave))


;; internal
(s/def ::remote-identity ::common/identity)
(defmethod message-body ::internal/authenticated [_]
  (s/keys :req-un [::type ::common/request_id ::remote-identity ::auth/user ::auth/login]
          :opt-un [::auth/access_token]))

;; outgoing

(s/def ::available_domains (s/coll-of ::dr/info))
(s/def ::resolved_identity ::common/identity)
(defmethod message-body :welcome [_]
  (s/merge ::messages/response
           (s/keys :req-un [::available_domains ::resolved_identity])))

;; context messages

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

(s/def ::updater_id ::common/peer_id)
(defmethod message-body :context-updated [_]
  (s/merge ::messages/broadcast
           (s/keys :req-un [::updater_id ::context_id ::requests/delta])))

(defmethod message-body :context-destroyed [_]
  (s/merge ::messages/broadcast
           (s/keys :req-un [::context_id ::reason_uri ::reason])))
