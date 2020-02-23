(ns gateway.state.spec.common
  (:require [clojure.spec.alpha :as s]))

(s/def ::peer_id string?)
(s/def ::request_id (s/nilable string?))
(s/def ::activity-id int?)
(s/def ::endpoint string?)

;; message addressing
(defmulti address-type :type)

;; if its a local peer, then it has a channel
(s/def ::channel any?)
(defmethod address-type :local [_]
  (s/keys :req-un [::type ::channel]
          ::opt-un [::endpoint]))

(s/def ::node string?)

;; if its a remote peer, then it has a peer-id
(defmethod address-type :peer [_]
  (s/keys :req-un [::type ::peer-id ::node]))

;; broadcast address
(defmethod address-type :cluster [_]
  (s/keys :req-un [::type]))

;; unicast node address
(defmethod address-type :node [_]
  (s/keys :req-un [::type ::node]))

(defmethod address-type :default [_]
  (s/keys :req-un [::type]))

(s/def ::address (s/multi-spec address-type :type))

(s/def ::source (s/multi-spec address-type :type))

(s/def ::identity (s/map-of (s/or ::string string? ::keyword keyword?) any?))
