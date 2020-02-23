(ns gateway.common.spec.messages
  (:require [clojure.spec.alpha :as s]
            [gateway.state.spec.common :as common]))

(s/def ::domain string?)
(s/def ::type keyword?)

(defmulti message-body :type)


(s/def ::reason_uri string?)
(s/def ::reason string?)
(s/def ::peer_id (s/nilable ::common/peer_id))

(s/def ::message-type (s/keys :req-un [::domain ::type]))
(s/def ::request (s/keys :req-un [::common/request_id ::common/peer_id]))
(s/def ::peer-message (s/keys :req-un [::common/peer_id]))

(s/def ::broadcast (s/merge ::message-type ::peer-message))
(s/def ::response (s/merge ::message-type
                           ::peer-message
                           (s/keys :req-un [::common/request_id])))

(defmethod message-body :error [_]
  (s/merge ::message-type
           (s/keys :req-un [::reason_uri ::reason]
                   :opt-un [::peer_id])))

(defmethod message-body :success [_]
  (s/merge ::message-type
           (s/keys :opt-un [::peer_id])))


(s/def ::body (s/multi-spec message-body :type))

(s/def ::receiver ::common/address)

(s/def ::incoming-message (s/keys :req-un [::common/source ::body]))
(s/def ::outgoing-message (s/keys :req-un [::receiver ::body] :opt-un [::common/source]))

(s/def ::outgoing-messages (s/nilable (s/coll-of ::outgoing-message)))

(s/def ::local boolean?)
(s/def ::meta (s/keys :req-un [::local]))

(defmethod message-body :peer-added [_]
  (s/merge ::broadcast
           (s/keys :req-un [::new_peer_id ::common/identity ::meta])))

(s/def ::removed_id ::common/peer_id)
(defmethod message-body :peer-removed [_]
  (s/merge ::broadcast
           (s/keys :req-un [::removed_id ::reason_uri ::reason])))
