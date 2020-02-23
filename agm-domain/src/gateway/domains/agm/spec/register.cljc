(ns gateway.domains.agm.spec.register
  (:require [gateway.common.spec.messages :as messages]
            [gateway.state.spec.mthds :as methods]
            [clojure.spec.alpha :as s]))

(s/def ::methods (s/coll-of ::methods/method-def))
(s/def ::register
  (s/merge ::messages/request
           (s/keys :req-un [::methods])))

