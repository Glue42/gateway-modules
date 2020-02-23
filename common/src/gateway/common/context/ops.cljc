(ns gateway.common.context.ops
  (:require [gateway.common.context.messages :as msg]
            [gateway.constants :as c]
            [gateway.common.messages :as m]
            [gateway.common.context.state :as state]
            [gateway.common.utilities :refer [state->]]
            #?(:cljs [gateway.common.utilities :refer-macros [state->]])
            [gateway.common.context.constants :as constants]
            [gateway.state.peers :as peers]
            [gateway.restrictions :as restrictions]
            [gateway.address :refer [peer->address]]
            [gateway.id-generators :as ids]

            [gateway.reason :refer [ex->Reason ->Reason throw-reason reason request->Reason]]

            [ghostwheel.core :as g :refer [>defn >defn- >fdef => | <- ?]]
            [gateway.domain :as domain]
            [gateway.state.spec.common :as common-spec]
            [gateway.state.spec.domain-registry :as dr-spec]
            [gateway.common.context.spec.messages]
            [gateway.common.context.spec.requests :as request-spec]
            [gateway.state.spec.state :as state-spec]

            [taoensso.timbre :as timbre]))

(defn should-update?
  "Checks if the request should be applied on an existing context"

  [request context]
  (>= (:version request) (:version context)))

;; context permissioning

(defn- can-write?
  "Checks if a peer can write to/destroy a context"

  [context peer]
  (let [lifetime (:lifetime context)
        result (if (= lifetime :activity)

                 ;; only members can write to an activity
                 (contains? (:members context) (:id peer))

                 ;; normal context - permissions apply
                 (let [owned (= (:lifetime context) :ownership)]
                   (or
                     (and owned (= (:owner context) (:id peer)))
                     (and (not owned) (restrictions/check? (:write_permissions context)
                                                           (:identity context)
                                                           (:identity peer))))))]
    result))

(defn- check-can-write*
  "Checks if a peer can write to/destroy a context"

  [domain-uri context peer]
  (let [lifetime (:lifetime context)
        result (if (= lifetime :activity)

                 ;; only members can write to an activity
                 (contains? (:members context) (:id peer))

                 ;; normal context - permissions apply
                 (let [owned (= (:lifetime context) :ownership)]
                   (or
                     (and owned (= (:owner context) (:id peer)))
                     (and (not owned) (restrictions/check? (:write_permissions context)
                                                           (:identity context)
                                                           (:identity peer))))))]
    (when-not result
      (throw-reason (constants/context-not-authorized domain-uri) "Not authorized to update context"))))

(defn- check-can-destroy*
  "Checks whether a not a given peer can destroy a context"

  [domain-uri context peer]
  (when (= (:lifetime context) :activity)
    (throw-reason (constants/context-not-authorized domain-uri)
                  "Activity contexts cannot be explicitly destroyed"))

  ;; the rest of the context can be destroyed by any peer that can write to them
  (check-can-write* domain-uri context peer))

(defn- can-read?
  "Checks if a peer matches the restrictions of a context."

  [context peer]
  (restrictions/check? (:read_permissions context)
                       (:identity context)
                       (:identity peer)))


(defn can-announce?
  "Check if a context can be announced to a peer"

  [peer context]
  (and (peers/local-peer? peer)
       (not= (:lifetime context) :activity)
       (can-read? context peer)))

(defn- check-can-read*
  "Checks if a peer matches the restrictions of a context."

  [domain-uri context peer]
  (when-not (can-read? context peer)
    (throw-reason (constants/context-not-authorized domain-uri)
                  "Not authorized to read context")))


(defn peer->domain-uri
  [peer]
  (if (get-in peer [:options :context-compatibility-mode?])
    c/global-domain-uri
    c/context-domain-uri))


(defn update*
  "Updates a context. Returns a new state and announcement messages"

  [state context updater-id delta version]
  (let [context-id (:id context)
        updated-state (state/apply-delta state context delta version)]

    [updated-state (->> (:members context)
                        (remove (partial = updater-id))
                        (map (partial peers/by-id updated-state))
                        (filter peers/local-peer?)
                        (mapv #(msg/context-updated (peer->domain-uri %) (:source %) (:id %) updater-id context-id delta)))]))


(defn- remote-update-ctx
  "Processes a context creation request coming from a remote node"

  [domain-uri state source request]
  (let [{:keys [request_id peer_id context_id delta]} request]
    (try
      (let [peer (peers/by-id* state peer_id)
            context (state/context-by-id* state context_id)]
        (if (and (can-write? context peer)
                 (should-update? request context))
          (state-> (update* state
                            context
                            peer_id
                            (reduce-kv #(assoc %1 (keyword %2) %3) {} delta)
                            (:version request)))
          [state nil]))
      (catch #?(:clj  Exception
                :cljs :default) e
        [state nil]))))

(defn- local-update-ctx
  "Processes an update request"

  [domain-uri state source request]
  (let [{:keys [request_id peer_id context_id delta]} request]
    (try
      (let [peer (peers/by-id* state peer_id)
            context (state/context-by-id* state context_id)
            version (state/next-version)]
        (when-not (can-write? context peer)
          (throw-reason domain-uri "Not authorized to update context"))
        (state-> (update* state
                          context
                          peer_id
                          (reduce-kv #(assoc %1 (keyword %2) %3) {} delta)
                          version)
                 ((fn [_] [_ [(m/success domain-uri
                                         source
                                         request_id
                                         peer_id)
                              (m/broadcast (peer->address (ids/node-id (:ids state)) peer_id)
                                           (assoc request :type :update-context
                                                          :version version))]]))))
      (catch #?(:clj  Exception
                :cljs :default) e
        [state [(m/error domain-uri
                         source
                         request_id
                         peer_id
                         (ex->Reason e (constants/failure domain-uri)))]]))))

(>defn update-ctx
  [domain-uri state source request]
  [::dr-spec/domain-uri ::state-spec/state ::common-spec/source ::request-spec/context-update => ::domain/operation-result]

  (if (m/remote-source? source)
    (remote-update-ctx domain-uri state source request)
    (local-update-ctx domain-uri state source request)))

(defn- ->lifetime
  [lifetime]
  (if (keyword? lifetime)
    lifetime
    (case lifetime
      "ownership" :ownership
      "ref-counted" :ref-counted
      "retained" :retained
      nil)))


(defn- request->ctx
  "Builds the context state map"

  [state creator local? request]
  (let [{:keys [name data lifetime read_permissions write_permissions peer_id]} request
        [new-ids ctx-id] (ids/context-id (:ids state))
        ctx (cond-> (assoc (state/->ctx (:identity creator)
                                        name
                                        data
                                        lifetime
                                        read_permissions
                                        write_permissions
                                        ctx-id
                                        (state/next-version))
                      :members #{peer_id}
                      :local? local?)
                    (= lifetime :ownership) (assoc :owner peer_id))]
    [(state/add-context (assoc state :ids new-ids) ctx) ctx]))

;; operations

(defn- subscribe*
  "Adds peer as a member of a context. Updates the state and generates messages."

  [domain-uri state sender request-id context peer-id]
  (let [updated-state (state/add-context-member state context peer-id)]
    [updated-state [(msg/subscribed-context domain-uri sender request-id peer-id (:id context) (:data context))]]))

(defn- remote-subscribe
  "Add the peer to the context members for the purpose of ref-counted context destruction"

  [domain-uri state source request]
  (let [{:keys [request_id peer_id context_id]} request]
    (try
      (let [peer (peers/by-id* state peer_id)
            context (state/context-by-id* state context_id)]

        (check-can-read* domain-uri context peer)
        [(state/add-context-member state context peer_id) nil])
      (catch #?(:clj  Exception
                :cljs :default) e
        [state nil]))))

(defn- local-subscribe
  "Processes a subscription request"

  [domain-uri state source request]
  (let [{:keys [request_id peer_id context_id]} request]
    (try
      (let [peer (peers/by-id* state peer_id)
            context (state/context-by-id* state context_id)
            sfun (partial subscribe* domain-uri)]

        (check-can-read* domain-uri context peer)
        (state-> [state nil]
                 (sfun source request_id context peer_id)
                 ((fn [_] [nil (m/broadcast (peer->address (ids/node-id (:ids state)) peer_id)
                                            (assoc request :type :subscribe-context))]))))
      (catch #?(:clj  Exception
                :cljs :default) e
        [state [(m/error domain-uri
                         source
                         request_id
                         peer_id
                         (ex->Reason e (constants/failure domain-uri)))]]))))

(>defn subscribe
  "Subscribes a peer to context data changes"

  [domain-uri state source request]
  [::dr-spec/domain-uri ::state-spec/state ::common-spec/source ::request-spec/context-destroy => ::domain/operation-result]
  (if (m/remote-source? source)
    (remote-subscribe domain-uri state source request)
    (local-subscribe domain-uri state source request)))

(defn- broadcast-added
  "Broadcasts that the context is added to the local peers. The remote peers will be told so by their own node"

  [state context creator-id]
  (let [ctx-name (:name context)
        ctx-id (:id context)]
    (into [] (comp
               (filter peers/local-peer?)
               (filter (partial can-read? context))
               (map #(msg/context-added (peer->domain-uri %) (:source %) (:id %) creator-id ctx-id ctx-name)))
          (peers/peers state :context-domain))))

(defn- broadcast-destroyed
  "Broadcasts that the context has been destroyed to the local peers. The remote peers will be told so by their own node"

  [context peers reason]
  (let [ctx-id (:id context)]
    (into [] (comp
               (filter peers/local-peer?)
               (filter (partial can-read? context))
               (map #(msg/context-destroyed (peer->domain-uri %) (:source %) (:id %) ctx-id reason)))
          peers)))

(defn- preprocess
  [domain-uri request]
  (let [rp (restrictions/parse (:read_permissions request))
        wp (restrictions/parse (:write_permissions request))
        lifetime (->lifetime (:lifetime request))]
    (when-not lifetime
      (throw-reason (constants/context-bad-lifetime domain-uri)
                    (str "Bad lifetime value " lifetime)))
    (merge request {:read_permissions rp :write_permissions wp :lifetime lifetime})))

(defn- remote-create
  "Processes a context creation request coming from a remote node"

  [domain-uri state source request]
  (let [{:keys [request_id peer_id name]} request]
    (try
      (let [peer (peers/by-id* state peer_id)]
        (if-let [existing-context (state/context-by-name state name)]

          ;; the context already exists, we should either reset its image or ignore the request
          (do
            (check-can-read* domain-uri existing-context peer_id)
            (if (should-update? request existing-context)
              (update* state
                       existing-context
                       peer_id
                       {:reset (:data request)}
                       (:version request))
              [state nil]))

          ;; new context
          (let [[new-state ctx] (->> request
                                     (preprocess domain-uri)
                                     (request->ctx state peer false))]
            [new-state (broadcast-added new-state ctx peer_id)])))
      (catch #?(:clj  Exception
                :cljs :default) e
        ;; swallow the exception since we cant send it back to the remote peer
        [state nil]))))

(defn- local-create
  "Creates a context or subscribes for an already existing one"

  [domain-uri state source request]

  (let [{:keys [request_id peer_id name]} request]
    (try
      (let [peer (peers/by-id* state peer_id)]
        (if-let [existing-context (state/context-by-name state name)]

          ;; the context already exists
          (do
            (check-can-read* domain-uri existing-context peer_id)
            (subscribe* domain-uri state source request_id existing-context peer_id))

          ;; new context
          (let [[new-state ctx] (->> request
                                     (preprocess domain-uri)
                                     (request->ctx state peer true))]
            [new-state (-> (broadcast-added new-state ctx peer_id)
                           (conj (msg/context-created domain-uri source request_id peer_id (:id ctx))
                                 (m/broadcast (peer->address (ids/node-id (:ids state)) peer_id)
                                              (assoc request :type :create-context
                                                             :version (:version ctx)))))])))
      (catch #?(:clj  Exception
                :cljs :default) e
        (timbre/error e "error")

        [state [(m/error domain-uri
                         source
                         request_id
                         peer_id
                         (ex->Reason e (constants/failure domain-uri)))]]))))

(>defn create
  [domain-uri state source request]
  [::dr-spec/domain-uri ::state-spec/state ::common-spec/source ::request-spec/context-create => ::domain/operation-result]

  (if (m/remote-source? source)
    (remote-create domain-uri state source request)
    (local-create domain-uri state source request)))

(defn- orphaned?
  "Checks if a context is orphaned and can be destroyed"

  [context]
  (case (:lifetime context)
    :ownership (nil? (:owner context))
    :ref-counted (empty? (:members context))
    false))

(defn- destroy*
  "Destroys a context. Returns an updated state and vector of messages to send"

  [domain-uri state context reason]
  (let [ctx-id (:id context)
        members (:members context)]
    [(state/remove-context state ctx-id)
     (reduce conj
             (->> members
                  (map (partial peers/by-id state))
                  (filter peers/local-peer?)
                  (mapv #(msg/context-destroyed domain-uri (:source %) (:id %) ctx-id reason)))
             (broadcast-destroyed context
                                  (eduction (remove #(get members (:id %)) (peers/peers state :context-domain)))
                                  reason))]))

(defn- remote-destroy
  [domain-uri state source request]

  (let [{:keys [peer_id context_id]} request]
    (try
      (let [peer (peers/by-id* state peer_id)
            context (state/context-by-id* state context_id)]
        (check-can-destroy* domain-uri context peer)

        ;; destroy the context and send success
        (destroy* domain-uri state context (constants/context-destroyed-explicitly domain-uri)))
      (catch #?(:clj  Exception
                :cljs :default) e
        [state nil]))))

(defn- local-destroy
  [domain-uri state source request]

  (let [{:keys [request_id peer_id context_id]} request]
    (try
      (let [peer (peers/by-id* state peer_id)
            context (state/context-by-id* state context_id)]
        (check-can-destroy* domain-uri context peer)

        ;; destroy the context and send success
        (state-> (destroy* domain-uri state context (constants/context-destroyed-explicitly domain-uri))
                 ((fn [_] [nil [(m/success domain-uri
                                           source
                                           request_id
                                           peer_id)
                                (m/broadcast (peer->address (ids/node-id (:ids state)) peer_id)
                                             (assoc request :type :destroy-context))]]))))
      (catch #?(:clj  Exception
                :cljs :default) e
        [state [(m/error domain-uri
                         source
                         request_id
                         peer_id
                         (ex->Reason e (constants/failure domain-uri)))]]))))


(>defn destroy
  "Destroys a context"

  [domain-uri state source request]
  [::dr-spec/domain-uri ::state-spec/state ::common-spec/source ::request-spec/context-destroy => ::domain/operation-result]
  (if (m/remote-source? source)
    (remote-destroy domain-uri state source request)
    (local-destroy domain-uri state source request)))

(defn remove-peer-from-context
  "Handles an implicit context destruction caused by peer removal"
  (
   [domain-uri state peer]
   (let [peer-id (:id peer)
         fun (partial remove-peer-from-context domain-uri)]
     (reduce (fn [agg context]
               (state-> agg
                        (fun peer-id context)))
             [state []]
             (state/contexts state))))
  (
   [domain-uri state peer-id context]
   (if (contains? (:members context) peer-id)
     (let [[new-state updated-ctx] (state/remove-context-member state context peer-id)]
       (if (orphaned? updated-ctx)
         (destroy* domain-uri new-state updated-ctx (constants/context-destroyed-peer-left domain-uri))
         [new-state nil]))
     [state nil])))

(defn- remote-unsubscribe

  [domain-uri state source request]
  (let [{:keys [request_id peer_id context_id]} request]
    (try
      (let [peer (peers/by-id* state peer_id)
            context (state/context-by-id* state context_id)]
        (remove-peer-from-context domain-uri state peer_id context))
      (catch #?(:clj  Exception
                :cljs :default) e
        [state nil]))))

(defn- local-unsubscribe

  [domain-uri state source request]
  (let [{:keys [request_id peer_id context_id]} request]
    (try
      (let [peer (peers/by-id* state peer_id)
            context (state/context-by-id* state context_id)]
        (state-> (remove-peer-from-context domain-uri state peer_id context)
                 ((fn [_] [nil [(m/success domain-uri
                                           source
                                           request_id
                                           peer_id)
                                (m/broadcast (peer->address (ids/node-id (:ids state)) peer_id)
                                             (assoc request :type :unsubscribe-context))]]))))
      (catch #?(:clj  Exception
                :cljs :default) e
        [state [(m/error domain-uri
                         source
                         request_id
                         peer_id
                         (ex->Reason e (constants/failure domain-uri)))]]))))

(>defn unsubscribe
  "Unsubscribes a peer from context data change notifications"

  [domain-uri state source request]
  [::dr-spec/domain-uri ::state-spec/state ::common-spec/source ::request-spec/context-unsubscribe => ::domain/operation-result]
  (if (m/remote-source? source)
    (remote-unsubscribe domain-uri state source request)
    (local-unsubscribe domain-uri state source request)))

(defn announce-contexts
  "Checks which contexts the peer can see and sends announcements message correspondingly"

  [domain-uri state peer]
  (let [source (:source peer)
        peer-id (:id peer)]
    (->> (state/contexts state)
         (filter (partial can-announce? peer))
         (map #(let [creator-id (:owner %)
                     ctx-id (:id %)
                     ctx-name (:name %)]
                 (msg/context-added domain-uri source peer-id creator-id ctx-id ctx-name))))))

(defn compatibility-mode-filter
  [state msg]
  (when-let [destination-peer (peers/by-id state (-> msg :body :peer_id))]
    (not (-> destination-peer :options :context-compatibility-mode?))))

(defn remove-peer
  "Peer is leaving the domain.

  Updates the state and generates messages for all other peers and eventual destruction of contexts"
  [domain-uri state leaving-peer reason source-removed?]
  (let [fun (partial remove-peer-from-context domain-uri)

        [s m] (peers/leave-domain domain-uri
                                  :context-domain
                                  state
                                  leaving-peer
                                  reason
                                  source-removed?)
        m (filter (partial compatibility-mode-filter s) m)]
    (state-> [s m]
             (fun leaving-peer))))
