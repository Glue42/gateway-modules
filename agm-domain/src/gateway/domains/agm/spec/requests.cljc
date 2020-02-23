(ns gateway.domains.agm.spec.requests
  (:require [gateway.state.spec.common :as common]
            [gateway.state.spec.domain-registry :as dr]
            [gateway.auth.spec :as auth]
            [gateway.common.spec.messages :as messages]
            [gateway.state.spec.mthds :as methods]

            [clojure.walk :refer [keywordize-keys]]
            [clojure.spec.alpha :as s]))

(s/def ::method_id ::methods/id)
(s/def ::server_id ::common/peer_id)
(s/def ::arguments (s/nilable (s/coll-of any?)))
(s/def ::arguments_kv (s/nilable map?))
(s/def ::flags (s/nilable map?))
(s/def ::context (s/nilable map?))
(s/def ::subscribe
  (s/merge ::messages/request
           (s/keys :req-un [::server_id ::method_id]
                   :opt-un [::arguments ::arguments_kv ::flags ::context])))

(s/def ::subscription_id string?)
(s/def ::unsubscribe
  (s/merge ::messages/request
           (s/keys :req-un [::subscription_id ::messages/reason_uri ::messages/reason])))


(s/def ::caller_id ::common/peer_id)
(s/def ::add-interest
  (s/keys :req-un [::subscription_id ::common/peer_id ::method_id ::caller_id]
          :opt-un [::arguments ::arguments_kv ::flags ::context]))

(s/def ::remove-interest
  (s/keys :req-un [::subscription_id ::common/peer_id ::method_id ::caller_id ::messages/reason_uri ::messages/reason]))

(s/def ::stream_id string?)
(s/def ::accepted
  (s/keys :req-un [::subscription_id ::common/peer_id ::stream_id]))


(s/def ::drop-subscription
  (s/keys :req-un [::common/peer_id ::subscription_id ::messages/reason_uri ::messages/reason]))

(s/def ::sequence number?)
(s/def ::snapshot boolean?)
(s/def ::data map?)
(s/def ::data-message
  (s/keys :req-un [::common/peer_id ::sequence ::snapshot ::data]))

(s/def ::post (s/merge ::data-message (s/keys :req-un [::subscription_id])))
(s/def ::publish (s/merge ::data-message (s/keys :req-un [::stream_id])))

(s/def ::call
  (s/merge ::messages/request
           (s/keys :req-un [::server_id ::method_id]
                   :opt-un [::arguments ::arguments_kv ::context])))

(s/def ::invocation_id string?)
(s/def ::invoke
  (s/keys :req-un [::invocation_id ::caller_id ::common/peer_id ::method_id]
          :opt-un [::arguments ::arguments_kv ::context]))


(s/def ::result map?)
(s/def ::yield
  (s/keys :req-un [::invocation_id ::common/peer_id ::result]))

;(s/def ::leave
;  (s/merge ::messages/request
;           (s/keys :req-un [::destination])))

