(ns gateway.domains.global.spec.requests
  (:require [gateway.state.spec.common :as common]
            [gateway.state.spec.domain-registry :as dr]
            [gateway.auth.spec :as auth]
            [gateway.state.spec.context :as context]
            [gateway.common.spec.messages :as messages]

            [clojure.walk :refer [keywordize-keys]]
            [clojure.spec.alpha :as s]))

(s/def ::authentication #(s/conform ::auth/authentication (keywordize-keys %)))

(s/def ::hello (s/keys :req-un [::common/request_id ::common/identity ::authentication]))

(s/def ::destination ::dr/domain-uri)

(s/def ::join
  (s/merge ::messages/request
           (s/keys :req-un [::destination])))
(s/def ::leave
  (s/merge ::messages/request
           (s/keys :req-un [::destination])))

;; contexts

(s/def ::lifetime #(s/conform ::context/lifetime (keyword %)))
(s/def ::read_permissions (s/nilable string?))
(s/def ::write_permissions (s/nilable string?))
(s/def ::context-create (s/merge ::messages/request
                                 (s/keys :req-un [::context/name
                                                  ::context/data
                                                  ::lifetime
                                                  ::read_permissions
                                                  ::write_permissions])))

(s/def ::context_id ::context/id)
(s/def ::removed (s/coll-of string?))
(s/def ::added map?)
(s/def ::updated map?)
(s/def ::reset map?)
(s/def ::delta
  (s/keys :opt-un [::removed ::added ::updated ::reset]))

(s/def ::context-update (s/merge ::messages/request
                                 (s/keys :req-un [::context_id ::delta])))

(s/def ::context-subscribe (s/merge ::messages/request
                                    (s/keys :req-un [::context_id])))

(s/def ::context-unsubscribe (s/merge ::messages/request
                                      (s/keys :req-un [::context_id])))

(s/def ::context-destroy (s/merge ::messages/request
                                  (s/keys :req-un [::context_id])))