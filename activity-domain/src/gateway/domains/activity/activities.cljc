(ns gateway.domains.activity.activities
  (:require
    [gateway.reason :refer [ex->Reason reason throw-reason request->Reason]]

    [gateway.state.peers :as peers]

    [gateway.domains.activity.state :refer [activity*] :as state]
    [gateway.domains.global.state :as context-state]
    [gateway.state.core :as core-state]
    [gateway.domains.activity.gateway-request :as gateway-request]

    [gateway.domains.activity.messages :as msg]
    [gateway.domains.activity.factories :as factories]
    [gateway.domains.activity.activity :refer [request->Activity] :as activity]

    [gateway.common.tokens :as tokens]
    [gateway.common.utilities :refer [conj-in disj-in into-in dissoc-in ensure-domain state->]]
    #?(:cljs [gateway.common.utilities :refer-macros [state->]])

    [gateway.id-generators :as ids]
    [gateway.common.messages :as m]

    [taoensso.timbre :as timbre]
    [gateway.domains.activity.constants :as constants]
    [clojure.walk :refer [keywordize-keys]]
    [clojure.set :as set]))

(defn activity-member-joined
  "Handles the case where a joining peer should be part of an activity.

  - joins the peer to the context
  - associates the peer with the activity and sets as owner if needed"

  [state activity-id peer gw-request]
  (if-let [activity (state/activity state activity-id)]
    (let [peer-id (:id peer)
          owner? (get-in gw-request [:activity :owner?])

          context (context-state/context-by-id state (:context-id activity))
          activity (cond-> (conj-in activity [:participants] peer-id)
                           owner? (assoc :owner (:id peer))
                           :default (disj-in [:gateway-requests] (:id gw-request)))]
      (-> state
          (state/set-activity activity-id activity)
          (peers/set-peer peer-id (-> peer
                                      (assoc-in [:activity-domain :member] activity-id)
                                      (assoc-in [:activity-domain :reload] (:reload gw-request))))
          (context-state/add-context-member context peer-id)))
    state))

(defn- broadcast-add-types
  [state user types]
  (into [] (comp
             (filter #(= user (get-in % [:identity :user])))
             (map #(msg/activity-types-added (:source %) (:id %) types)))
        (peers/peers state :activity-domain)))

(defn- broadcast-remove-types
  [state user types]
  (into [] (comp
             (filter #(= user (get-in % [:identity :user])))
             (map #(msg/activity-types-removed (:source %) (:id %) types)))
        (peers/peers state :activity-domain)))


(defn add-types
  [state source request-id peer-id types]
  (let [peer (peers/by-id* state peer-id :activity-domain)]
    (if-let [user (get-in peer [:identity :user])]
      [(state/add-activity-types state user types) (conj (broadcast-add-types state user types)
                                                         (msg/success source
                                                                      request-id
                                                                      peer-id))]
      [state [(msg/error source
                         request-id
                         peer-id
                         (reason constants/activity-registration-failure
                                 (str "Registering peer is missing an user in its identity" (:identity peer))))]])))

(defn remove-types
  [state source request]
  (let [{:keys [request_id peer_id types]} request]
    (let [peer (peers/by-id* state peer_id :activity-domain)]
      (if-let [user (get-in peer [:identity :user])]
        (let [type-names (set types)
              actual-types (set (filter type-names (keys (state/activity-types state user))))]
          [(state/remove-activity-types state user actual-types)
           (conj (broadcast-remove-types state user actual-types)
                 (msg/success source
                              request_id
                              peer_id))])
        [state [(msg/error source
                           request_id
                           peer_id
                           (reason constants/activity-registration-failure
                                   (str "Removing peer is missing an user in its identity" (:identity peer))))]]))))


(defn announce-types
  [state peer]
  (let [user-types (-> (state/activity-types state (get-in peer [:identity :user]))
                       (vals)
                       (set))]
    (when (seq user-types)
      [(msg/activity-types-added (:source peer) (:id peer) user-types)])))

(defn- activity-type*
  [state peer type-name]
  (if-let [at (-> state
                  (state/activity-types (get-in peer [:identity :user]))
                  (get type-name))]
    at
    (throw-reason constants/activity-missing-activity-type (str "Unable to find activity type " type-name))))

(defn- merge-config
  [activity-peer configuration]
  (let [base-configuration (:configuration activity-peer)]
    (merge base-configuration
           (get configuration {:type (:type activity-peer)})
           (get configuration
                {:type (:type activity-peer)
                 :name (:name activity-peer)}))))

(defn- create-owner
  [{:keys [state activity] :as ctx} owner-type creator-id context configuration]
  (let [result (factories/create-activity-peer state
                                               creator-id
                                               owner-type
                                               activity
                                               context
                                               true
                                               (merge-config owner-type configuration))]
    (-> ctx
        (assoc :state (:state result))
        (into-in [:messages] (:messages result))
        (conj-in [:activity :gateway-requests] (:request-id result)))))

(defn- create-participants
  [{:keys [state activity] :as ctx} helper-types creator-id context configuration]
  (reduce (fn [ctx peer-type]
            (try
              (let [{:keys [state messages request-id]} (factories/create-activity-peer (:state ctx)
                                                                                        creator-id
                                                                                        peer-type
                                                                                        (:activity ctx)
                                                                                        context
                                                                                        false
                                                                                        (merge-config peer-type configuration))]
                (-> ctx
                    (assoc :state state)
                    (into-in [:messages] messages)
                    (conj-in [:activity :gateway-requests] request-id)))

              (catch #?(:clj  Exception
                        :cljs :default) e
                (do
                  (timbre/warn e "Unable to create helper type" peer-type "for activity" (:id activity))
                  ctx))))
          ctx
          helper-types))

(defn create-activity
  [state source request configuration]
  (let [{:keys [request_id peer_id]} request]
    (let [peer (peers/by-id* state peer_id :activity-domain)
          activity-type-name (:activity_type request)
          activity-type (activity-type* state peer activity-type-name)


          ;; the configuration is now keyed by the activity peer's type and name
          configuration (->> configuration
                             (map (fn [c] [(select-keys c [:type :name]) (:configuration c)]))
                             (into {}))

          types-override (:types_override request)
          _ (when (and types-override (not (empty? configuration)))
              (throw-reason constants/activity-is-child "Cannot specify types override and custom configuration at the same time"))

          [state activity ctx] (request->Activity state request peer)

          {:keys [state messages activity]} (-> {:state    state
                                                 :messages [(msg/activity-initiated source
                                                                                    request_id
                                                                                    peer_id
                                                                                    (:id activity))]
                                                 :activity activity}
                                                (create-owner (or (:owner_type types-override) (:owner_type activity-type))
                                                              peer_id
                                                              ctx
                                                              configuration)
                                                (create-participants (or (:helper_types types-override) (:helper_types activity-type))
                                                                     peer_id
                                                                     ctx
                                                                     configuration))
          state (-> (context-state/add-context state ctx)
                    (state/set-activity (:id activity) activity))]
      [state messages])))

(defn sees-activity?
  "An activity can be seen by a peer in the activity domain that matches the read permissions. If there
  are no permissions set, the user of the peer must match the user of the activity owner"

  [activity owner peer]
  (let [peer-id (:id peer)]
    (if (or (contains? (:ready-members activity) peer-id)
            (contains? (:participants activity) peer-id)
            (= (:owner activity) peer-id))
      ;; any activity members can actually see it
      true

      ;; the rest are subject to permissions
      (peers/peer-visible? (:identity owner)
                           (:read_permissions activity)
                           (get-in owner [:options :service?])
                           (:identity peer)
                           nil
                           (get-in peer [:options :service?])))))

(defn- can-destroy?
  "An activity can be destroyed by any of its members, or peers that can have write permissions for it"

  [state activity peer]
  (let [peer-id (:id peer)]
    (if (or (contains? (:ready-members activity) peer-id)
            (contains? (:participants activity) peer-id)
            (= (:owner activity) peer-id))
      ;; any activity members
      true

      ;; the rest are subject to permissions
      (let [owner (peers/by-id state (:owner activity))]
        (peers/peer-visible? (:identity owner)
                             (:write_permissions activity)
                             false
                             (:identity peer)
                             nil
                             false)))))

(defn- broadcast-joined-activity
  "Sends a message to all participants in the activity that are ready plus the owner itself"

  [state activity context-data]
  (let [owner-id (:owner activity)
        ready-members (:ready-members activity)]
    (->> (map #(peers/by-id state %)
              (conj ready-members owner-id))
         (filter some?)
         (mapv #(msg/joined-activity state
                                     (:source %)
                                     %
                                     activity
                                     context-data)))))

(defn activity-participants
  [state activity owner-id]
  (set/union #{owner-id}
             (get activity :participants #{})
             (get activity :ready-members)))

(defn broadcast-targets
  [state peer-id activity owner include-participants?]
  (->> (set/union (state/activity-subscribers state (:type activity))
                  (when include-participants?
                    (activity-participants state activity (:id owner))))
       (remove (partial = peer-id))
       (map (partial peers/by-id state))
       (filter (partial sees-activity? activity owner))))

(defn- broadcast-activity-created
  "Sends a message to all peers in the activity domain that are subscribed for that type of an activity"

  [state activity]
  (let [activity-type (:type activity)
        owner (peers/by-id state (:owner activity))]
    (->> (state/activity-subscribers state activity-type)
         (map (partial peers/by-id state))
         (filter #(sees-activity? activity owner %))
         (mapv #(msg/activity-created state
                                      (:source %)
                                      (:id %)
                                      activity)))))

(defn- broadcast-activity-destroyed
  "Sends a message to all peers in the activity domain that can see the activity and the activity participants"

  [state activity owner-id reason include-participants?]
  (when (:ready? activity)
    ;; if the activity is ready (i.e. created message have been sent), we send a destroyed message
    (let [owner (peers/by-id state owner-id)]

      ;; either participants or peers that can see the activity
      (->> (broadcast-targets state nil activity owner include-participants?)
           (mapv #(msg/activity-destroyed (:source %)
                                          (:id %)
                                          (:id activity)
                                          reason))))))

(defn- broadcast-owner-changed
  "Sends a message to all peers in the activity domain that can see the activity and the activity participants"

  [state activity owner]
  (when (:ready? activity)
    ;; if the activity is ready (i.e. created message have been sent), we send a destroyed message
    ;; either participants or peers that can see the activity
    (let [owner-id (:id owner)]
      (->> (broadcast-targets state owner-id activity owner true)
           (mapv #(msg/owner-changed (:source %)
                                     (:id %)
                                     (:id activity)
                                     owner))))))

(defn- owner-ready
  "Processes a ready message from an activity owner. The activity becomes ready itself and messages for the
  currently ready members are broadcast. The activity created message is broadcast as well"

  [state activity peer]
  (let [context-id (:context-id activity)
        context-data (:data (context-state/context-by-id state context-id))
        owner-id (:id peer)
        reloading? (get-in peer [:activity-domain :reload])
        activity (-> activity
                     (assoc :owner owner-id
                            :ready? true)
                     (dissoc :client-request))]

    ;; returning a vector of the new state, the modified activity and the resulting messages
    {
     ;; since the activity is ready, we can remove the client request
     :state    (-> state
                   (peers/set-peer owner-id
                                   (assoc-in peer [:activity-domain :member] (:id activity)))
                   (state/set-activity (:id activity) activity))

     ;; mark it as ready
     :activity activity

     :messages (if reloading?
                 (into [(msg/joined-activity state (:source peer) peer activity context-data)]
                       (broadcast-owner-changed state activity peer))
                 (into (broadcast-joined-activity state activity context-data)
                       (broadcast-activity-created state activity)))}))


(defn- broadcast-activity-joined
  "Broadcasts a message that a participant has joined an activity that has already started. The message is sent to
  all that can see the activity minus the participant itself (it should receive joined-activity)"

  [state peer activity owner]
  (let [peer-id (:id peer)]
    (->> (broadcast-targets state peer-id activity owner true)
         (mapv #(msg/activity-joined (:source %)
                                     (:id %)
                                     peer
                                     (:id activity))))))

(defn broadcast-activity-left
  "Broadcasts a message that a participant has left the activity to everyone that can see it"

  [state peer-id activity reason include-participants?]
  (let [owner (peers/by-id state (:owner activity))]
    (->> (broadcast-targets state peer-id activity owner include-participants?)
         (mapv #(msg/activity-left (:source %)
                                   (:id %)
                                   peer-id
                                   (:id activity)
                                   reason)))))

(defn- participant-ready
  "Processes a ready message from an activity participant. If the activity is ready, we need to broadcast events
  that we've joined the activity. Otherwise just add the peer to the set of ready members"

  [state activity peer]
  (let [peer-id (:id peer)
        context-id (:context-id activity)
        context (context-state/context-by-id state context-id)
        activity (update activity :ready-members (fnil conj #{}) peer-id)

        messages (when (:ready? activity)
                   (let [owner-id (:owner activity)
                         owner (peers/by-id state owner-id)]
                     (conj (broadcast-activity-joined state peer activity owner)
                           (msg/joined-activity state
                                                (:source peer)
                                                peer
                                                activity
                                                (:data context)))))]
    {:state    (context-state/add-context-member state context peer-id)
     :activity activity
     :messages messages}))

(defn clean-domain-info
  [peer]
  (-> peer
      (dissoc-in [:activity-domain :member])
      (dissoc-in [:activity-domain :owner])
      (ensure-domain :activity-domain)))

(defn peer-ready
  "An activity member has sent a ready message"

  [state source activity-id peer]
  (let [peer-id (:id peer)]
    (if-let [activity (state/activity state activity-id)]
      (let [owner? (= (:owner activity) peer-id)
            activity (cond-> activity
                             (not owner?) (update :ready-members (fnil conj #{}) peer-id)
                             :default (disj-in [:participants] peer-id))
            {:keys [state activity messages]} (if owner?
                                                (owner-ready state activity peer)
                                                (participant-ready state activity peer))]

        [(state/set-activity state activity-id activity) messages])

      ;; peer is ready for an activity that no longer exist - it should go away
      [(peers/set-peer state peer-id (clean-domain-info peer))
       [(msg/dispose-peer source peer-id nil constants/reason-activity-destroyed)]])))

(defn destroy-participants
  [state participant-ids reason]
  (reduce
    (fn [[state messages] pid]
      (if-let [participant (peers/by-id state pid)]
        [(peers/set-peer state pid (clean-domain-info participant))
         (conj messages
               (msg/dispose-peer (:source participant)
                                 pid
                                 nil
                                 reason))]
        [state messages]))
    [state []]
    participant-ids))

(defn- remove-gateway-requests
  [state activity]
  (reduce #(first (core-state/remove-gateway-request %1 %2))
          state
          (:gateway-requests activity)))

(defn- cleanup-activity
  "Cleans up state associated with an activity"

  [state activity]
  (-> state
      (remove-gateway-requests activity)
      (state/remove-activity (:id activity))
      (context-state/remove-context (:context-id activity))))

(defn- destroy-activity*
  "Dismantle the whole activity and send destroy messages to
  all participants that are already created. The outgoing and client requests are removed as well, so if a
  participant tries to join it receives an error"

  ([state activity reason]
   (let [client-request (:client-request activity)
         owner-id (:owner activity)
         activity-id (:id activity)

         [state messages] (state-> [(cleanup-activity state activity) nil]
                                   (destroy-participants (set/union #{owner-id}
                                                                    (:participants activity)
                                                                    (:ready-members activity))
                                                         reason)
                                   ((fn [state] [state (broadcast-activity-destroyed state activity owner-id reason true)])))]

     (if-let [requester (and client-request (peers/by-id state (:peer_id client-request)))]
       [state (conj messages
                    (msg/error (:source requester)
                               (:request_id client-request)
                               (:peer_id client-request)
                               reason))]
       [state messages]))))

(defn destroy-activity
  [state source request]
  (let [{:keys [request_id peer_id activity_id]} request]
    (let [peer (peers/by-id* state peer_id :activity-domain)
          activity (activity* state activity_id)]

      (if (can-destroy? state activity peer)
        (state-> (destroy-activity* state activity
                                    (request->Reason request))
                 ((fn [_] [nil (msg/success source
                                            request_id
                                            peer_id)])))
        [state (msg/error source
                          request_id
                          peer_id
                          (reason constants/activity-not-authorized
                                  "Not authorized to destroy activity"))]))))

(defn- owner-failed
  "The owner of the activity failed to create. Dismantle the whole activity and send destroy messages to
  all participants that are already created. The outgoing and client requests are removed as well, so if a
  participant tries to join it receives an error"

  [state activity-info]
  (let [activity (state/activity state (:id activity-info))]
    (destroy-activity* state
                       activity
                       (reason constants/activity-owner-creation-failed
                               "Activity owner cannot be created"))))

(defn handle-error
  [state activity-info]
  (if (:owner? activity-info)
    (owner-failed state activity-info)
    [state nil]))


(defn join-participant
  [state activity participant]
  (let [activity-id (:id activity)
        participant-id (:id participant)
        {:keys [state activity messages]} (participant-ready state activity participant)]

    {:state    (-> state
                   (state/set-activity activity-id activity)
                   (peers/set-peer participant-id
                                   (assoc-in participant [:activity-domain :member] activity-id)))
     :activity activity
     :messages messages}))

(defn join-activity
  "Processes a request to join a target peer in an activity"

  [state source request]
  (let [{:keys [request_id peer_id target_id activity_id peer_type peer_name]} request]
    (peers/by-id* state peer_id :activity-domain)
    (let [activity (activity* state activity_id)
          target-peer (-> (peers/by-id* state target_id :activity-domain)
                          (merge (into {} (filter second {:peer_name peer_name :peer_type peer_type}))))
          activity-domain (:activity-domain target-peer)
          member-id (:member activity-domain)
          owner-id (:owner activity-domain)
          existing-activity (state/activity state (or member-id owner-id))]

      (cond
        ;; the peer is already in the same activity
        (= (:id existing-activity) activity_id) [state [(msg/success source
                                                                     request_id
                                                                     peer_id)]]
        ;; in some other activity
        existing-activity (throw-reason constants/activity-is-child (str "Peer is already in activity " (:id existing-activity)))

        ;; joining a new activity
        :else (let [{:keys [state messages]} (join-participant state activity target-peer)]
                [state (conj messages (msg/success source
                                                   request_id
                                                   peer_id))])))))

(defn remove-participant
  "Removes a participant from the activity state"

  [activity peer-id]
  (cond-> activity
          (:participants activity) (update :participants disj peer-id)
          (:ready-members activity) (update :ready-members disj peer-id)))

(defn participant-left
  "A participant in an activity has left. Clean it up from the activity and its children"

  ([state activity peer reason]
   (timbre/debug "a participant" (:id peer) "of activity" (:id activity) "has left")

   (let [peer-id (:id peer)
         activity (remove-participant activity peer-id)
         activity-id (:id activity)
         messages (broadcast-activity-left state peer-id activity reason true)]

     {:state    (-> state
                    (peers/set-peer peer-id (clean-domain-info peer))
                    (state/set-activity activity-id activity)
                    (context-state/remove-context-member (context-state/context-by-id state (:context-id activity)) peer-id)
                    (first))
      :activity activity
      :messages messages})))

(defn owner-left
  "An owner of an activity has left. Dismantle the whole activity destroying all its children"

  ([state activity peer reason destroy?]
   (let [peer-id (:id peer)]
     (timbre/debug "the owner" peer-id "of activity" (:id activity) "has left")
     (if destroy?
       (destroy-activity* (peers/set-peer state peer-id (clean-domain-info peer)) activity reason)

       (let [{:keys [state messages]} (participant-left state activity peer reason)]
         [state messages])))))

(defn reload
  [state source request]
  (let [{:keys [request_id peer_id]} request
        peer (peers/by-id* state peer_id)
        activity-id (get-in peer [:activity-domain :member])
        activity (state/activity state activity-id)]
    (let [[new-ids gw-request-id] (ids/request-id (:ids state))
          gateway-request (gateway-request/reload gw-request-id peer activity)

          state' (-> state
                     (assoc :ids new-ids)
                     (core-state/set-gateway-request gw-request-id gateway-request))]

      [(cond-> state'
               activity-id (state/set-activity activity-id (update activity :reloading (fnil conj #{}) peer_id)))

       [(m/token constants/activity-domain-uri
                 source
                 request_id
                 peer_id
                 (tokens/for-request state (:identity peer) gateway-request))]])))

(defn remove-peer
  "A peer is being removed from the domain, check if it belongs to any activities and deal with that accordingly"

  [state peer reason]
  (let [activity-id (get-in peer [:activity-domain :member])
        activity (state/activity state activity-id)]
    (when activity
      (let [owner-id (:owner activity)
            peer-id (:id peer)
            reloading? (contains? (:reloading activity) peer-id)
            activity (cond-> activity
                             reloading? (update :reloading (fnil disj #{}) peer-id))]
        (if (= owner-id peer-id)
          (owner-left state activity peer reason (not reloading?))
          (let [{:keys [state messages]} (participant-left state activity peer reason)]
            [state messages]))))))

(defn leave-activity*
  "Processes a request of a peer to leave an activity"

  [state source request]
  (let [{:keys [request_id peer_id]} request]
    (let [peer (peers/by-id* state peer_id :activity-domain)
          member-id (get-in peer [:activity-domain :member])
          activity (state/activity state member-id)
          owner-id (:owner activity)]

      (when-not activity
        (throw-reason constants/activity-not-a-member "Not a member of an activity"))

      (state-> (let [reason (request->Reason request)]
                 (if (= owner-id peer_id)
                   (owner-left state activity peer reason true)
                   (let [{:keys [state messages]} (participant-left state activity peer reason)]
                     [state messages])))
               ((fn [_] [nil (msg/success source
                                          request_id
                                          peer_id)]))))))



(defn- subscribe-unsubscribe
  [state source request_id peer_id activity_types action-fn]
  (let [state (if (seq activity_types)
                (reduce #(action-fn %1 peer_id %2)
                        state
                        (remove nil? activity_types))
                (action-fn state peer_id :all))]
    [state [(msg/success source
                         request_id
                         peer_id)]]))

(defn subscribe
  [state source request]
  (let [{:keys [request_id peer_id activity_types]} request
        peer (peers/by-id* state peer_id :activity-domain)
        [state messages] (subscribe-unsubscribe state
                                                source
                                                request_id
                                                peer_id
                                                activity_types
                                                state/add-activity-subscriber)

        ;; activities that are ready and not children to other ones are announced
        ready-activities (->> (state/activities state)
                              (filter :ready?))]
    [state (into messages (->> (if (seq activity_types)
                                 (filter #(contains? activity_types (:type %)) ready-activities)
                                 ready-activities)
                               (mapv (partial msg/activity-created state (:source peer) (:id peer)))))]))

(defn unsubscribe
  [state source request]
  (let [{:keys [request_id peer_id activity_types]} request]
    (peers/by-id* state peer_id :activity-domain)
    (subscribe-unsubscribe state
                           source
                           request_id
                           peer_id
                           activity_types
                           state/remove-activity-subscriber)))

