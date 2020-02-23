(ns gateway.state.spec.domain-registry
  (:require [clojure.spec.alpha :as s]))

(s/def ::uri string?)
(s/def ::description string?)
(s/def ::version pos?)
(s/def ::domain some?)
(s/def ::info (s/keys :req-un [::uri ::description ::version]))
(s/def ::domain-uri string?)

(s/def ::registered-domains (s/map-of ::domain-uri (s/keys :req-un [::domain ::info])))