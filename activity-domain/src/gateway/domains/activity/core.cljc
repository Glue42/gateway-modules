(ns gateway.domains.activity.core
  (:require
    [gateway.reason :refer [ex->Reason request->Reason reason]]
    [gateway.domains.activity.messages :as msg]
    [gateway.domains.activity.activities :as activities]
    [gateway.domains.activity.factories :as factories]
    [gateway.domains.activity.contexts :as contexts]
    [clojure.walk :refer [keywordize-keys]]

    [gateway.state.core :as state]
    [gateway.state.peers :as peers]

    [gateway.restrictions :as rst]
    [gateway.domains.activity.constants :as constants]
    [gateway.common.utilities :refer [?-> state->] :as util]
    #?(:cljs [gateway.common.utilities :refer-macros [?-> state->] :as util])
    [taoensso.timbre :as timbre]
    [gateway.domain :refer [Domain] :as domain]
    [gateway.common.messages :as m]
    [gateway.common.commands :as commands]))

(defn handle-error
  [state source request]
  (let [[state gateway-r] (state/remove-gateway-request state (:request_id request))]
    (when gateway-r
      (case (:type gateway-r)
        :activity (when-let [activity (:activity gateway-r)]
                    (-> state
                        (activities/handle-error activity)))
        :create-peer (factories/handle-create-peer-error state
                                                         (request->Reason request)
                                                         (:client-request gateway-r)
                                                         gateway-r)

        (timbre/error "Unable to handle error for an unknown incoming request type" (:type gateway-r))))))

;(defn cancel-request
;  [state source request]
;  (let [[state incoming-r] (state/remove-client-request state request)]
;    (when incoming-r
;      (case (keyword (:type incoming-r))
;        :create-peer (factories/cancel-create-peer state request incoming-r)
;        :create (activities/cancel-create-activity state request incoming-r)
;        [state nil]))))

(defn destroy-peer
  [state source request]
  (let [{:keys [request_id peer_id target_id]} request]
    (let [peer (peers/by-id* state peer_id :activity-domain)
          target (peers/by-id* state target_id)]
      [state [(msg/dispose-peer (:source target)
                                target_id
                                peer_id
                                (request->Reason request))
              (msg/success request_id
                           peer_id)]])))

(defn- join-gw-request
  "Handles the case where a peer has joined in a response of a gateway request"

  [state peer]
  (if-let [creation-request (:creation-request peer)]
    (let [peer-name (:peer_name creation-request)
          peer-type (:peer_type creation-request)
          peer (cond-> peer
                       peer-name (assoc :peer_name peer-name)
                       peer-type (assoc :peer_type peer-type))]
      (case (:type creation-request)
        :activity (activities/activity-member-joined state
                                                     (get-in creation-request [:activity :id])
                                                     peer
                                                     creation-request)
        :create-peer (peers/set-peer state (:id peer) peer)))

    state))

(defn join
  "Peer is joining the domain."
  [state source request]
  (let [{:keys [request_id peer_id restrictions]} request]
    (if (state/in-domain state peer_id :activity-domain)
      ;; peer already in the domain
      [state [(msg/success source
                           request_id
                           peer_id)]]

      ;; join it in
      (let [parsed-restrictions (rst/parse restrictions)
            state (state/join-domain state peer_id :activity-domain parsed-restrictions)
            peer (peers/by-id state peer_id)
            state (join-gw-request state peer)

            messages (-> (peers/announce-peer constants/activity-domain-uri :activity-domain state source peer)
                         (into (activities/announce-types state peer))
                         (into (factories/announce-existing state peer))
                         ;(into (activities/announce-existing state peer))
                         (conj (msg/success source
                                            request_id
                                            peer_id)))]
        [state messages]))))

(def leave-domain (partial peers/leave-domain constants/activity-domain-uri :activity-domain))

(defn remove-peer
  "Peer is leaving the domain - deal with activities and factories"

  [state peer reason source-removed?]
  (state-> [state nil]
           (activities/remove-peer peer reason)
           (factories/remove-peer peer)
           (leave-domain peer reason source-removed?)))

(defn leave
  [state source request]
  (let [{:keys [request_id peer_id]} request]
    (let [peer (peers/by-id* state peer_id :activity-domain)]
      (state-> (remove-peer state peer (request->Reason request) false)
               ((fn [_] [nil (msg/success source
                                          request_id
                                          peer_id)]))))))

(defn source-removed
  "Source has disconnected from the system"
  [state source _]
  (let [affected (peers/by-source state source :activity-domain)
        state-without-affected (transduce (map :id)
                                          (completing #(state/leave-domain %1 %2 :activity-domain))
                                          state
                                          affected)]
    (reduce (fn [s peer] (state-> s (remove-peer peer constants/reason-peer-removed true)))
            [state-without-affected nil]
            affected)))


(defn ready
  [state source request]
  (let [peer-id (:peer_id request)
        peer (peers/by-id state peer-id)]
    (if-let [activity-id (get-in peer [:activity-domain :member])]
      (activities/peer-ready state source activity-id peer)
      (factories/peer-ready state peer))))


(defmulti handle-request (fn [state source request] (:type request)))

(defmethod handle-request ::domain/join
  [state source request]
  (join state source request))

(defmethod handle-request ::domain/leave
  [state source request]
  (leave state source request))

(defmethod handle-request :ready
  [state source request]
  (ready state source request))

(defn- keywordize-type
  [activity-type]
  (-> activity-type
      (update :owner_type util/keywordize)
      (update :helper_types (partial mapv util/keywordize))))

(defn- keywordize-types
  [types]
  (mapv (fn [t] (-> t
                    util/keywordize
                    (select-keys [:name :owner_type :helper_types :default_context])
                    keywordize-type)) types))

(defmethod handle-request :add-types
  [state source request]
  (let [{:keys [request_id peer_id types]} request]
    (activities/add-types state
                          source
                          request_id
                          peer_id
                          (keywordize-types types))))

(defmethod handle-request :remove-types
  [state source request]
  (activities/remove-types state source request))

(defmethod handle-request :create
  [state source request]
  (activities/create-activity state
                              source
                              (cond-> (dissoc request :configuration)
                                      (:types_override request) (update :types_override (comp keywordize-type util/keywordize)))
                              (map util/keywordize (:configuration request))))

(defmethod handle-request :destroy
  [state source request]
  (activities/destroy-activity state source request))

(defmethod handle-request :subscribe
  [state source request]
  (activities/subscribe state source request))

(defmethod handle-request :unsubscribe
  [state source request]
  (activities/unsubscribe state source request))

(defmethod handle-request :join-activity
  [state source request]
  (activities/join-activity state source request))

(defmethod handle-request :leave-activity
  [state source request]
  (activities/leave-activity* state source request))

(defmethod handle-request :add-peer-factories
  [state source request]
  (factories/add state source request))

(defmethod handle-request :remove-peer-factories
  [state source request]
  (factories/remove-factories state source request))

(defmethod handle-request :create-peer
  [state source request]
  (factories/create state source request))

(defmethod handle-request :destroy-peer
  [state source request]
  (destroy-peer state source request))

(defmethod handle-request :error
  [state source request]
  (handle-error state source request))

(defmethod handle-request :reload
  [state source request]
  (activities/reload state source request))

(defmethod handle-request :update-context
  [state source request]
  (contexts/update-ctx state source request))

(defmethod handle-request ::commands/source-removed
  [state source request]
  (source-removed state source request))

(defmethod handle-request :default
  [state source body]
  (timbre/error "Unhandled message" body)
  [state [(msg/error source
                     (:request_id body -1)
                     (:peer_id body)
                     (reason constants/activity-unhandled-message
                             (str "Unhandled message " body)))]])

(deftype ActivityDomain []
  Domain

  (info [this] {:uri         constants/activity-domain-uri
                :description ""
                :version     2})
  (init [this state] state)
  (destroy [this state] state)

  (handle-message
    [this state msg]
    (let [{:keys [source body]} msg
          type (:type body)]
      (try
        (handle-request state source body)
        (catch #?(:clj  Exception
                  :cljs :default) e
          (when-not (ex-data e) (timbre/error e "Error handling request" msg))
          (when (m/local-source? source)
            [state [(msg/error source
                               (:request_id body)
                               (:peer_id body)
                               (ex->Reason e constants/failure))]])))))

  (state->messages [this state]))

(defn activity-domain [] (->ActivityDomain))