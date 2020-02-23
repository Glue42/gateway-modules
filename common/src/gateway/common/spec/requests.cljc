(ns gateway.common.spec.requests
  (:require [gateway.state.spec.common :as common]
            [gateway.common.spec.messages :as messages]

            [clojure.spec.alpha :as s]))

(s/def ::restrictions string?)
(s/def ::join
  (s/merge ::messages/request
           (s/keys :req-un [::common/identity]
                   :opt-un [::restrictions])))
