(ns gateway.domains.agm.spec.unregister
  (:require [gateway.common.spec.messages :as messages]
            [gateway.state.spec.mthds :as methods]
            [clojure.spec.alpha :as s]))

(s/def ::methods (s/coll-of ::methods/id))
(s/def ::unregister
  (s/merge ::messages/request
           (s/keys :req-un [::methods])))

