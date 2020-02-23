(ns gateway.state.core
  (:require [gateway.common.utilities :as util]
            [gateway.id-generators :as ids]
            [gateway.reason :refer [->Reason throw-reason]]
            ))



; state manipulation

(defn empty-state
  ([node-id]
   (empty-state node-id (ids/random-id)))
  (
   [node-id signing-key]
   {:ids           {:node-id    node-id
                    :current-id 1}
    :signature-key signing-key}))


(defn join-domain
  [state peer-id domain restrictions]
  (-> state
      (assoc-in [:peers peer-id domain] (if restrictions {:restrictions restrictions} {}))
      (update-in [:domains domain] (fnil conj #{}) peer-id)))

(defn leave-domain
  [state peer-id domain]
  (-> state
      (update-in [:peers peer-id] dissoc domain)
      (util/disj-in [:domains domain] peer-id)
      ;(update-in [:domains domain] disj peer-id)
      ))

(defn in-domain
  (
   [state peer-id domain]
   (get-in state [:domains domain peer-id]))
  ([peer domain]
   (get peer domain)))





;; requests

(defn set-gateway-request
  [state gw-request-id request]
  (if gw-request-id
    (assoc-in state [:gateway-requests gw-request-id] request)
    state))

(defn gateway-request
  [state request-id]
  (when request-id
    (get-in state [:gateway-requests request-id])))


(defn remove-gateway-request
  [state request-id]
  ((juxt util/dissoc-in get-in) state [:gateway-requests request-id]))


;(defn add-client-request
;  [state request]
;  (let [k {:request_id (:request_id request) :peer_id (:peer_id request)}]
;    (assoc-in state [:incoming-requests k] request)))
;

;(defn remove-client-request
;  [state request]
;  (if request
;    (let [k {:request_id (:request_id request) :peer_id (:peer_id request)}
;          removed (get-in state [:incoming-requests k])]
;      [(util/dissoc-in state [:incoming-requests k]) removed])
;    [state nil]))



;; domain registry

(defn domain-for-uri
  [state domain-uri]
  (get-in state [:registered-domains domain-uri :domain]))
