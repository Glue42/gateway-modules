(ns gateway.domains.activity.gateway-request)

(defn activity
  [request-id activity-peer activity activity-owner?]
  (cond-> {:type      :activity
           :id        request-id
           :peer_type (:type activity-peer)
           :activity  {:id     (:id activity)
                       :owner? activity-owner?}}
          (:name activity-peer) (assoc :peer_name (:name activity-peer))))

(defn reload
  [request-id activity-peer activity]
  (cond-> {:type      :activity
           :id        request-id
           :peer_type (:peer_type activity-peer)
           :activity  {:id     (:id activity)
                       :owner? (= (:id activity-peer) (:owner activity))}
           :reload    true}
          (:peer_name activity-peer) (assoc :peer_name (:peer_name activity-peer))))


(defn factory
  [request-id peer-type peer-name client-request]
  (cond-> {:type           :create-peer
           :id             request-id
           :peer_type      peer-type
           :client-request client-request}
          peer-name (assoc :peer_name peer-name)))