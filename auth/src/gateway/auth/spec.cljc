(ns gateway.auth.spec
  (:require [ghostwheel.core :as g :refer [>defn >defn- >fdef => | <- ?]]

            [clojure.spec.alpha :as s]
            [gateway.state.spec.common :as common]))

(s/def ::authentication any?)
(s/def ::remote_identity ::common/identity)
(s/def ::request (s/keys :req-un [::authentication]
                         :opt-un [::common/request_id ::remote_identity]))

(s/def ::type keyword?)

(defmulti response-type :type)

(s/def ::message string?)
(defmethod response-type :failure [_]
  (s/keys :req-un [::type ::message]))

(s/def ::login string?)
(s/def ::user string?)
(s/def ::access_token string?)

(defmethod response-type :success [_]
  (s/keys :req-un [::type ::login ::user]
          :opt-un [::access_token]))

(defmethod response-type :continue [_]
  (s/keys :req-un [::type ::authentication]))

(s/def ::response (s/multi-spec response-type :type))
