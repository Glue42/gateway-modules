(ns gateway.state.spec.mthds
  (:require [clojure.spec.alpha :as s]
            [gateway.state.spec.common :as common]
            [gateway.state.spec.restrictions :as rs]))

(s/def ::id int?)

(s/def ::name string?)
(s/def ::version int?)
(s/def ::input_signature string?)
(s/def ::result_signature string?)
(s/def ::display_name string?)
(s/def ::description string?)

(s/def ::method-def (s/keys :req-un [::id ::name ::version ::flags ::input_signature ::result_signature]
                            :opt-un [::display_name ::description ::rs/restrictions]))
(s/def ::peer-methods (s/or ::no-methods empty? ::method (s/map-of ::id ::method-def)))
(s/def ::methods (s/map-of ::common/peer_id ::peer-methods))
