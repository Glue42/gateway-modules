(ns gateway.domains.agm.t-helpers
  (:require
    [gateway.domains.agm.core :as core]
    [gateway.domains.global.core :as global]
    [gateway.domains.global.constants :as c]
    [gateway.domains.agm.constants :as constants]
    [gateway.domains.agm.mthds :as methods]

    [gateway.state.peers :as peers]

    [gateway.t-helpers :refer [gen-identity ch->src peer-id!]]))

(defn create-join
  [state source request]
  (let [{:keys [peer_id identity]} request]
    (-> state
        (peers/ensure-peer-with-id source peer_id identity nil nil)
        (first)
        (core/join source (assoc request :domain c/global-domain-uri
                                         :destination constants/agm-domain-uri)))))

(defn create-join-service
  [state source request]
  (let [{:keys [peer_id identity]} request]
    (-> state
        (peers/ensure-peer-with-id source peer_id identity nil {:service? true})
        (first)
        (core/join source request))))


(defn remote-join
  [state message]
  (let [body (:body message)]
    (-> state
        (peers/ensure-peer-with-id (:source message)
                                   (:peer_id body)
                                   (:identity body)
                                   nil
                                   nil)
        (first)
        (core/join (:source message) body)
        (first))))

(defn remote-register-methods
  [state message]
  (let [body (:body message)]
    (-> state
        (methods/register (:source message) body)
        (first))))


(defn source-removed
  [state source request]
  (reduce (fn [[state msgs] fun]
            (let [[s m] (fun state)]
              [s (into msgs m)]))
          [state []]
          [#(core/source-removed % source request)
           #(global/source-removed % source request)]))