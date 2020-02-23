(ns gateway.domains.activity.factories
  (:require
    [gateway.reason :refer [ex->Reason reason throw-reason request->Reason]]
    [gateway.common.utilities :refer [find-first dissoc-in ensure-domain]]
    [gateway.common.tokens :as tokens]
    [gateway.domains.activity.messages :as msg]
    [gateway.domains.activity.gateway-request :as gateway-request]
    [clojure.walk :refer [keywordize-keys]]

    [gateway.domains.activity.state :refer [activity*] :as state]
    [gateway.domains.global.state :as context-state]
    [gateway.state.core :as core-state]

    [gateway.state.peers :as peers]

    [gateway.id-generators :as ids]
    [gateway.domains.activity.constants :as constants]))

(defn- add-factories
  [state peer factories]

  (let [factories-by-type (group-by :peer_type factories)]
    (-> state
        (peers/set-peer (:id peer) (update-in peer
                                              [:activity-domain :factories]
                                              (fnil (fn [f] (reduce-kv (fn [m k v] (assoc m k (first v)))
                                                                       f
                                                                       factories-by-type)) {})))
        (state/add-factories (keys factories-by-type) (:id peer)))))

(defn- broadcast-factories-added
  [state owner factories]
  (mapv #(msg/peer-factories-added (:source %)
                                   (:id %)
                                   (:id owner)
                                   factories)
        (peers/visible-peers state :activity-domain owner)))

(defn broadcast-removed
  [state owner factory-ids include-owner]

  (let [owner-id (:id owner)]
    (->> (peers/visible-peers state :activity-domain owner)
         (map (fn [peer]
                (when (or include-owner (not= owner-id (:id peer)))
                  (msg/peer-factories-removed (:source peer)
                                              (:id peer)
                                              owner-id
                                              (vec factory-ids)))))
         (filterv some?))))

(defn announce-existing
  ([state peer]
   (->> (set (flatten (vals (state/factories state))))
        (map (partial peers/by-id state))
        (filter (partial peers/peer-visible? :activity-domain peer))
        (map (partial announce-existing state peer))
        (filterv some?)))
  (
   [state peer factory-peer]
   (when-let [factories (vals (get-in factory-peer [:activity-domain :factories]))]
     (msg/peer-factories-added (:source peer) (:id peer) (:id factory-peer) (vec factories)))))

(defn- validate-factory
  [peer factory]
  (let [peer-type (:peer_type factory)]
    (when (get-in peer [:activity-domain :factories peer-type])
      (throw-reason constants/activity-factory-already-registered
                    (str "Factory for type " peer-type " is already registered by this peer")))))

(defn add
  [state source request]
  (let [{:keys [request_id peer_id factories]} request]
    (let [peer (peers/by-id* state peer_id :activity-domain)
          factories (map keywordize-keys factories)]
      (doseq [f factories] (validate-factory peer f))

      (let [updated-state (add-factories state peer factories)]
        [updated-state (conj (broadcast-factories-added updated-state peer factories)
                             (msg/success source
                                          request_id
                                          peer_id))]))))

(defn factory-by-id
  [factories id]
  (find-first (fn [f] (= (:id f) id)) (vals factories)))

(defn- remove-factories-from-peer
  "Removes a set of factory ids from a peer. Returns the updated peer and a vector of removed factories"

  [peer factory-ids]
  (let [factories (get-in peer [:activity-domain :factories])
        [updated-peer removed] (reduce
                                 (fn [[peer removed] factory-id]
                                   (if-let [factory (factory-by-id factories factory-id)]
                                     [(dissoc-in peer [:activity-domain :factories (:peer_type factory)]) (conj removed factory)]
                                     [peer removed]))
                                 [peer #{}]
                                 factory-ids)]
    [(ensure-domain updated-peer :activity-domain) removed]))

(defn remove-factories
  [state source request]
  (let [{:keys [request_id peer_id factory_ids]} request]
    (let [peer (peers/by-id* state peer_id :activity-domain)
          [updated-peer removed] (remove-factories-from-peer peer factory_ids)
          updated-state (-> state
                            (peers/set-peer peer_id updated-peer)
                            (state/remove-factories (map :peer_type removed) peer_id))
          messages (conj (broadcast-removed updated-state peer (mapv :id removed) true)
                         (msg/success source
                                      request_id
                                      peer_id))]
      [updated-state messages])))


(defn- request-peer
  [state owner factory configuration token request-id activity context peer_name]
  (let [activity-rq (cond-> {}
                            activity (assoc :activity
                                            {:id              (:id activity)
                                             :type            (:type activity)
                                             :context_id      (:id context)
                                             :initial-context (:data context)})
                            peer_name (assoc :peer_name peer_name))]

    ;; for those wondering why there is no peer type here - the factory id : peer type is 1 : 1
    (msg/peer-requested (:source owner)
                        request-id
                        (:id owner)
                        (:id factory)
                        token
                        (merge (:configuration factory) configuration)
                        activity-rq)))

(defn- create-peer-failed
  [state request failure]
  (if-let [creator (peers/by-id state (:peer_id request))]
    [state [(msg/error (:source creator)
                       (:request_id request)
                       (:id creator)
                       failure)]]
    [state nil]))


(defn handle-create-peer-error
  [state failure incoming-r gateway-r]
  (-> state
      (create-peer-failed incoming-r failure)))

(defn- destroy-peer
  [state requestor-id peer-id reason]
  (if-let [peer (peers/by-id state peer-id)]
    [(peers/remove-peer state peer) (msg/dispose-peer (:source peer) peer-id requestor-id reason)]

    [state nil]))

;(defn cancel-create-peer
;  "Cancels a request to create a peer"
;  [state request original-r]
;  (let [[state r-gateway] (state/remove-gateway-request state (:gw-request original-r))]
;    (-> state
;        (tokens/remove-gateway-token (:gateway_token r-gateway))
;        (destroy-peer (:peer_id request) (:associated-peer r-gateway)
;                      (reason constants/activity-request-cancelled "Request cancelled")))))

(defn- factory-owner
  [state owner-id peer-type]
  (let [owner (peers/by-id state (or owner-id (first (state/factories state peer-type))))
        factory (get-in owner [:activity-domain :factories peer-type])]
    (if factory
      [owner factory]
      (throw-reason constants/activity-missing-factory
                    (str "Unable to find factory owner for type " peer-type)))))

;(defn- gateway-request
;  "Creates a new gateway request"
;
;  [request-id client-request token activity-id activity-owner? peer-type peer-name]
;  (cond-> {:gateway_token token
;           :id            request-id}
;          peer-name (assoc :peer_name peer-name)
;          client-request (assoc :type :create-peer
;                                :peer_type peer-type
;                                :client-request client-request)
;          activity-id (assoc :type :activity
;                             :peer_type peer-type
;                             :activity {:id     (:id activity-id)
;                                        :owner? activity-owner?})))

(defn create-activity-peer
  [state creator-id activity-peer activity context activity-owner? configuration]
  (let [[owner factory] (factory-owner state nil (:type activity-peer))
        creator (peers/by-id* state creator-id)

        [new-ids gw-request-id] (ids/request-id (:ids state))

        gateway-request (gateway-request/activity gw-request-id
                                                  activity-peer
                                                  activity
                                                  activity-owner?)

        token (tokens/for-request state (:identity creator) gateway-request)

        state' (-> state
                   (assoc :ids new-ids)
                   (core-state/set-gateway-request gw-request-id gateway-request))]
    {:state      state'
     :messages   [(request-peer state'
                                owner
                                factory
                                configuration
                                token
                                gw-request-id
                                activity
                                context
                                nil)]                       ; peer_name not necessary if creating as part of activity creation
     ; since we'll join  the peer in ourselves, rather than delegate it
     ; to the factory
     :request-id gw-request-id}))


(defn- create-peer
  [state creator-id factory-owner-id peer-type peer-name activity configuration client-request]
  (let [[owner factory] (factory-owner state factory-owner-id peer-type)
        creator (peers/by-id* state creator-id)

        [new-ids gw-request-id] (ids/request-id (:ids state))
        gw-request (gateway-request/factory gw-request-id
                                            peer-type
                                            peer-name
                                            (select-keys client-request [:request_id :peer_id]))
        token (tokens/for-request state
                                  (:identity creator)
                                  gw-request)

        state' (-> state
                   (assoc :ids new-ids)
                   (core-state/set-gateway-request gw-request-id gw-request))]

    {:state      state'
     :messages   [(request-peer state'
                                owner
                                factory
                                configuration
                                token
                                gw-request-id
                                activity
                                (context-state/context-by-id state (:context-id activity))
                                peer-name)]
     :request-id gw-request-id}))

(defn create
  (
   [state source request]
   (let [{:keys [request_id
                 peer_id
                 owner_id
                 peer_type
                 peer_name
                 activity_id]} request]
     (peers/by-id* state peer_id :activity-domain)
     (let [activity (when activity_id (activity* state activity_id))
           result (create-peer state
                               peer_id
                               owner_id
                               peer_type
                               (or peer_name peer_type)
                               activity
                               (:configuration request)
                               request)]
       [(:state result)
        (conj (:messages result)
              (msg/success source
                           request_id
                           peer_id))]))))

(defn peer-ready
  [state peer]
  (if-let [client-request (get-in peer [:creation-request :client-request])]
    (let [caller-id (:peer_id client-request)
          caller (peers/by-id state caller-id)]
      [state [(msg/peer-created (:source caller)
                                (:request_id client-request)
                                caller-id
                                (:id peer))]])

    (throw-reason constants/activity-invalid-peer
                  (str "Unable to find originating request for a ready message from peer " (:id peer)))))


(defn remove-peer
  "Peer is leaving the domain.

  Updates the state and generates messages for the peer and its registered methods being removed"
  [state peer]
  (when-let [factories (vals (get-in peer [:activity-domain :factories]))]

    ;; remove the factories that this peer had
    [(state/remove-factories state
                             (map :peer_type factories)
                             (:id peer))
     (broadcast-removed state
                        peer
                        (map :id factories)
                        false)]))