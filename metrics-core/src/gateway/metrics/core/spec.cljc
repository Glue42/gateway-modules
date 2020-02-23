(ns gateway.metrics.core.spec
  (:require
    #?(:clj [clojure.spec.alpha :as s]
       :cljs [cljs.spec.alpha :as s])))


(s/def ::name string?)
(s/def ::type #{"string" "number" "timestamp" "object"})
(s/def ::description string?)
(s/def ::context (s/map-of string? string?))
(s/def ::timestamp int?)
(s/def ::expires-at ::timestamp)
(s/def ::value (s/or ::string-value string?
                     ::numeric-value number?
                     ::timestamp-value int?
                     ::object-value map?))

;; definitions
(s/def ::composite (s/map-of ::name (s/keys :req-un [::type]
                                            :opt-un [::description ::context ::composite])))

(defmulti definition-type :type)

(defmethod definition-type "string" [_]
  (s/keys :req-un [::name ::type]
          :opt-un [::description ::context]))
(defmethod definition-type "number" [_]
  (s/keys :req-un [::name ::type]
          :opt-un [::description ::context]))
(defmethod definition-type "timestamp" [_]
  (s/keys :req-un [::name ::type]
          :opt-un [::description ::context]))
(defmethod definition-type "object" [_]
  (s/keys :req-un [::name ::type ::composite]
          :opt-un [::description ::context]))

(s/def ::definition (s/multi-spec definition-type :type))
(s/def ::definitions (s/coll-of ::definition))

(s/def ::datapoint (s/keys :req-un [::name ::value]
                           :opt-un [::timestamp ::context]))
(s/def ::data-points (s/coll-of ::datapoint))

(s/def ::state int?)
(s/def ::publisher-status (s/keys :req-un [::state ::timestamp ::description ::expires-at]))
