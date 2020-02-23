(ns gateway.state.spec.context
  (:require [clojure.spec.alpha :as s]
            [gateway.state.spec.common :as common]
            [gateway.state.spec.restrictions :as rs]))

;; contexts

(s/def ::id (s/and string? seq))
(s/def ::name string?)
(s/def ::data (s/nilable map?))
(s/def ::read_permissions ::rs/restrictions)
(s/def ::write_permissions ::rs/restrictions)
(s/def ::lifetime (s/and keyword? #(contains? #{:ref-counted :ownership :retained :activity} %)))
(s/def ::members (s/coll-of ::common/peer_id :kind set?))
(s/def ::owner ::common/peer_id)
(s/def ::version number?)

(s/def ::context (s/keys :req-un [::id ::lifetime ::members]
                         :opt-un [::name ::read_permissions ::write_permissions ::data ::owner ::version]))
(s/def ::contexts (s/map-of ::id ::context))

