(ns gateway.domains.agm.subscriptions
  (:require [gateway.common.messages :refer [success error] :as m]
            [gateway.id-generators :as ids]
            [gateway.common.utilities :refer [conj-in disj-in dissoc-in conj-if! state->]]
            #?(:cljs [gateway.common.utilities :refer-macros [state->]])
            [gateway.domains.agm.messages :as msg]
            [gateway.domains.agm.utilities :refer [validate*]]

            #?(:clj  [gateway.metrics.features :as features]
               :cljs [gateway.metrics.features :refer-macros [subscribe! event! unsubscribe!] :as features])

            [gateway.state.peers :as peers]

            [gateway.domains.agm.constants :as constants]
            [gateway.reason :refer [ex->Reason reason throw-reason request->Reason]]
            [gateway.address :refer [peer->address]]

            [ghostwheel.core :as g :refer [>defn >defn- >fdef => | <- ?]]
            [gateway.state.spec.common :as common-spec]
            [gateway.domain :as domain]
            [gateway.common.spec.messages :as message-spec]
            [gateway.domains.agm.spec.requests :as request-spec]
            [gateway.state.spec.state :as s]
            [clojure.spec.alpha :as spec]

            [taoensso.timbre :as timbre]))

;; state manipulation helpers

(defn- conj-stream
  [server stream-id subscriber-id subscription-id]
  (update-in server [:agm-domain :streams stream-id subscriber-id] (fnil conj #{}) subscription-id))

(defn- disj-stream
  [server stream-id subscriber-id subscription-id]
  (if (nil? stream-id)
    server
    (disj-in server [:agm-domain :streams stream-id subscriber-id] subscription-id)))


(defn- cancel-subscriptions-from
  [state subscription-id subscription subscriber-id reason]
  (let [{server-id :server stream-id :stream_id method-id :method_id} subscription
        peer (-> (peers/by-id state server-id)
                 (dissoc-in [:agm-domain :interests subscription-id])
                 (disj-stream stream-id subscriber-id subscription-id))
        state (peers/set-peer state server-id peer)]

    (if (peers/local-peer? peer)
      ;; local servers receive messages
      [state (msg/remove-interest
               (:source peer)
               subscription-id
               server-id
               method-id
               subscriber-id
               reason)]

      ;; the remote servers will receive their own when the peer is removed on their node
      [state nil])))

(defn cancel-subscriptions
  "Cancels all subscriptions made by peer with subscriber-id and generates messages for the servers

  Returns [state [message]]"
  [state subscriber reason]
  (let [subscriptions (get-in subscriber [:agm-domain :subscriptions])
        subscriber-id (:id subscriber)
        [new-state messages] (reduce (fn [agg [subscription-id subscription]]
                                       (state-> agg
                                                (cancel-subscriptions-from subscription-id
                                                                           subscription
                                                                           subscriber-id
                                                                           reason)))
                                     [state []]
                                     subscriptions)]
    [new-state messages]))

(defn trace-unsubscriptions!
  [subscriber server removed]
  (when (features/interop-feature-enabled?)
    (let [server-id (:id server)]
      (doseq [r removed
              :let [subscription-id (first r)
                    subscription (second r)
                    method (get-in server [:agm-domain :methods server-id (:method_id subscription)])]]
        (features/unsubscribe! subscription-id (:identity subscriber) (:identity server) method)))))


(defn collect-subscriptions
  [subscriptions server-id method-ids]
  (reduce-kv
    (fn [result sub-id sub]
      (if (and (= server-id (:server sub))
               (or (empty? method-ids) (contains? method-ids (:method_id sub))))
        (update result :removed #(assoc % sub-id sub))
        (update result :remaining #(assoc % sub-id sub))))
    {}
    subscriptions))

(defn cancel-interest
  "Cancels all interest that subscriber-id might have on server-id methods

  Returns [state [message]]"
  [state subscriber server method-ids reason]
  (let [server-id (:id server)
        {:keys [removed remaining]} (collect-subscriptions (get-in subscriber [:agm-domain :subscriptions])
                                                           server-id
                                                           method-ids)

        state-without-interests (reduce-kv
                                  (fn [state sub-id sub]
                                    (-> state
                                        (cancel-subscriptions-from sub-id sub (:id subscriber) reason)
                                        (first)))
                                  state
                                  removed)]
    (if (seq removed)
      (do
        (trace-unsubscriptions! subscriber server removed)

        [(peers/update-peer state-without-interests (:id subscriber)
                            (fn [s]
                              (if (seq remaining)
                                (assoc-in s [:agm-domain :subscriptions] remaining)
                                (update s :agm-domain dissoc :subscriptions))))
         (when (m/local-source? (:source subscriber))
           (mapv #(msg/subscription-cancelled (:source subscriber)
                                              (key %)
                                              (:id subscriber)
                                              reason)
                 removed))])
      [state nil])))

(defn- add-interest
  [caller state request]
  (let [{:keys [peer_id server_id subscription_id method_id arguments arguments_kv context flags]} request]
    (if-let [callee (peers/by-id state server_id)]
      (let [callee (assoc-in callee
                             [:agm-domain :interests subscription_id]
                             {:subscriber peer_id :method_id method_id})
            callee-src (:source callee)
            method (get-in caller [:agm-domain :methods server_id method_id])]
        (features/subscribe! subscription_id (:identity caller) (:identity callee) method arguments_kv)
        [(peers/set-peer state server_id callee) (if (m/local-source? callee-src)
                                                   [(msg/add-interest callee-src
                                                                      subscription_id
                                                                      server_id
                                                                      method_id
                                                                      peer_id
                                                                      arguments
                                                                      arguments_kv
                                                                      flags
                                                                      context)]
                                                   [(m/unicast (peer->address (ids/node-id (:ids state)) peer_id)
                                                               {:type :node :node (:node callee-src)}
                                                               request)])])
      (throw (ex-info (str "Unable to find server with id " server_id) {})))))

(defn subscribe*
  [state request subscription-id]
  (let [{:keys [request_id
                peer_id
                server_id
                method_id]} request]
    (let [caller (-> (peers/by-id* state peer_id :agm-domain)
                     (assoc-in [:agm-domain :subscriptions subscription-id]
                               {:server server_id :method_id method_id :request_id request_id}))
          callee (peers/by-id* state server_id :agm-domain)]
      (validate* caller callee request)
      (add-interest caller
                    (peers/set-peer state peer_id caller)
                    (assoc request :subscription_id subscription-id)))))

(defn- local-subscribe
  [state source request]
  (let [[new-ids subscription-id] (ids/request-id (:ids state))]
    (subscribe* (assoc state :ids new-ids) request subscription-id)))

(defn- remote-subscribe
  [state request]
  (let [{:keys [subscription_id server_id]} request]
    (when (peers/by-id state server_id)
      (subscribe* state request subscription_id))))

(>defn subscribe
  [state source request]
  [::s/state ::common-spec/source ::request-spec/subscribe => ::domain/operation-result]

  (if (m/local-source? source)
    (local-subscribe state source request)
    (remote-subscribe state request)))

(defn- drop-subscription
  [state subscriber subscriber-id subscription-id reason ctx error?]
  (if subscriber
    (let [locator [:agm-domain :subscriptions subscription-id]
          subscription (get-in subscriber locator)
          request-id (:request_id subscription)
          sub-src (:source subscriber)]

      [(peers/set-peer state
                       subscriber-id
                       (dissoc-in subscriber locator))
       (when (m/local-source? sub-src)
         (if error?
           [(error constants/agm-domain-uri sub-src request-id subscriber-id reason ctx)]
           [(msg/subscription-cancelled sub-src subscription-id subscriber-id reason)]))])
    [state nil]))

(defn- subscribed
  "Marks a request from a given subscriber as successful as a result of an accepted message.
  If the subscriber is a local one - it receives a direct message. Otherwise a message for the
  specific peer is sent to the cluster"

  [state subscriber-id subscription-id stream-id accept-rq]
  (let [subscriber (peers/by-id state subscriber-id)
        request-id (get-in subscriber [:agm-domain :subscriptions subscription-id :request_id])
        state (peers/set-peer state subscriber-id
                              (assoc-in subscriber [:agm-domain :subscriptions subscription-id :stream] stream-id))
        sub-source (:source subscriber)]

    (if (peers/local-peer? subscriber)
      [state [(msg/subscribed sub-source request-id subscriber-id subscription-id)]]
      [state [(m/unicast (peer->address (ids/node-id (:ids state)) (:peer_id accept-rq))
                         {:type :node :node (:node sub-source)}
                         accept-rq)]])))

(defn- accepted*
  "Handles an 'accepted' message.

  The returned stream is associated with the subscriber and the interest"
  [state peer request]
  (let [{:keys [subscription_id
                peer_id
                stream_id]} request]
    (when (nil? stream_id)
      (throw-reason constants/agm-invalid-subscription "Invalid or missing stream id"))

    (if-let [interest (get-in peer [:agm-domain :interests subscription_id])]
      (let [subscriber-id (:subscriber interest)]
        (-> state
            (peers/set-peer peer_id (-> peer
                                        (assoc-in [:agm-domain :interests subscription_id :stream] stream_id)
                                        (conj-stream stream_id subscriber-id subscription_id)))
            (subscribed subscriber-id subscription_id stream_id request)))
      (timbre/debug "Subscription accept response" request "for missing interest"))))

(defn- remote-accepted
  "Handles a subscription accept response coming from a remote peer"

  [state request]
  (let [{:keys [subscription_id peer_id subscriber_id]} request]
    (if-let [peer (peers/by-id state peer_id :agm-domain)]
      (accepted* state peer request)
      (do
        (timbre/warn "Subscription accept response" request "from missing peer")
        (drop-subscription state
                           (peers/by-id state subscriber_id)
                           subscriber_id
                           subscription_id
                           (reason constants/agm-subscription-failure
                                   "Received a response from a missing server")
                           nil
                           true)))))

(defn- local-accepted
  "Handles a subscription accept response coming from a local peer"

  [state source request]
  (let [{:keys [subscription_id peer_id]} request]
    (let [peer (peers/by-id* state peer_id :agm-domain)]
      (accepted* state peer request))))

(>defn accepted
  [state source request]
  [::s/state ::common-spec/source ::request-spec/accepted => ::domain/operation-result]

  (if (m/local-source? source)
    (local-accepted state source request)
    (remote-accepted state request)))

(defmulti drop-interest-rq :type)
(defmethod drop-interest-rq :error [_] ::message-spec/body)
(defmethod drop-interest-rq :drop-subscription [_] ::request-spec/drop-subscription)

(spec/def ::drop-interest (spec/multi-spec drop-interest-rq :type))

(>defn drop-interest
  [state source request error?]
  [::s/state ::common-spec/source ::drop-interest boolean? => ::domain/operation-result]

  (let [peer-id (:peer_id request)
        subscription-id (get request (if error? :request_id :subscription_id))
        peer (peers/by-id* state peer-id :agm-domain)]
    (if-let [interest (get-in peer [:agm-domain :interests subscription-id])]
      (let [subscriber-id (:subscriber interest)
            stream-id (get interest :stream)
            subscriber (peers/by-id state subscriber-id)
            subscriber-src (:source subscriber)]

        (if (or stream-id error?)
          (state-> [(peers/set-peer state peer-id (-> peer
                                                      (dissoc-in [:agm-domain :interests subscription-id])
                                                      (disj-stream stream-id subscriber-id subscription-id))) []]
                   (drop-subscription subscriber
                                      subscriber-id
                                      subscription-id
                                      (request->Reason request)
                                      (:context request)
                                      error?)
                   ((fn [_] (when (and subscriber (not (m/local-source? subscriber-src)))
                              [nil (m/unicast (peer->address (ids/node-id (:ids state)) peer-id)
                                              {:type :node :node (:node subscriber-src)}
                                              request)]))))

          [state [(error constants/agm-domain-uri
                         source
                         subscription-id
                         peer-id
                         constants/reason-bad-drop
                         nil)]]))


      [state [(error constants/agm-domain-uri
                     source
                     subscription-id
                     peer-id
                     (reason constants/agm-invalid-subscription "Trying to drop a non-existing subscription")
                     nil)]])))

(defn- remove-interest
  [state subscriber server-id subscription-id request]
  (when-let [server (peers/by-id state server-id)]
    (when-let [interest (get-in server [:agm-domain :interests subscription-id])]
      (features/unsubscribe! subscription-id
                             (:identity subscriber)
                             (:identity server)
                             (get-in server [:agm-domain :methods server-id (:method_id interest)]))
      (let [subscriber-id (:id subscriber)
            server-src (:source server)
            state (peers/set-peer state
                                  server-id
                                  (-> server
                                      (dissoc-in [:agm-domain :interests subscription-id])
                                      (disj-stream (:stream interest) subscriber-id subscription-id)))]
        [state [(if (m/local-source? server-src)
                  (msg/remove-interest server-src
                                       subscription-id
                                       server-id
                                       (:method_id interest)
                                       subscriber-id
                                       (request->Reason request))
                  (m/unicast (peer->address (ids/node-id (:ids state)) subscriber-id)
                             {:type :node :node (:node server-src)}
                             request))]]))))

(defn- unsubscribe*
  [state request subscriber-id subscription-id request-id]
  (let [subscriber (peers/by-id* state subscriber-id :agm-domain)
        sub-loc [:agm-domain :subscriptions subscription-id]
        {server-id :server} (get-in subscriber sub-loc)]
    (if server-id
      (let [state (peers/set-peer state
                                  subscriber-id
                                  (dissoc-in subscriber sub-loc))
            result (remove-interest state
                                    subscriber
                                    server-id
                                    subscription-id
                                    request)]
        (or result
            [state []]))
      (throw-reason constants/agm-invalid-subscription
                    (str "Unable to find subscription with id " subscription-id " on subscriber id " subscriber-id)))))

(defn- remote-unsubscribe
  "Processes a remote unsubscription request.

  Returns [state [messages]]"
  [state request]
  (let [{:keys [request_id peer_id subscription_id]} request]
    (unsubscribe* state
                  request
                  peer_id
                  subscription_id
                  request_id)))

(defn- local-unsubscribe
  "Processes a local unsubscription request.

  Returns [state [messages]]"
  [state source request]
  (let [{:keys [request_id peer_id subscription_id]} request]
    (let [[state messages] (unsubscribe* state
                                         request
                                         peer_id
                                         subscription_id
                                         request_id)]
      [state (conj messages
                   (success constants/agm-domain-uri
                            source
                            request_id
                            peer_id))])))

(>defn unsubscribe
  [state source request]
  [::s/state ::common-spec/source ::request-spec/unsubscribe => ::domain/operation-result]

  (if (m/local-source? source)
    (local-unsubscribe state source request)
    (remote-unsubscribe state request)))

(defn trace-event!
  [subscription-ids subscriber server oob? sqn snapshot? data]

  (when (features/interop-feature-enabled?)
    (let [server-id (:id server)
          interests (get-in server [:agm-domain :interests])
          methods (get-in server [:agm-domain :methods server-id])]
      (doseq [sub-id subscription-ids
              :let [interest (get interests sub-id)
                    method (get methods (:method_id interest))]]
        (features/event! sub-id
                         (:identity subscriber)
                         (:identity server)
                         method
                         oob?
                         sqn
                         snapshot?
                         data)))))

(>defn publish
  [state source request]
  [::s/state ::common-spec/source ::request-spec/publish => ::domain/operation-result]

  (let [{server-id :peer_id
         stream-id :stream_id
         sqn       :sequence
         snapshot? :snapshot
         data      :data} request]
    (let [server (peers/by-id state server-id :agm-domain)
          destinations (get-in server [:agm-domain :streams stream-id])
          remotes (volatile! #{})]

      [state (doall (->> destinations
                         (mapcat (fn [[subscriber-id subscriptions]]
                                   (let [subscriber (peers/by-id state subscriber-id)
                                         sub-source (:source subscriber)]
                                     (if (m/local-source? sub-source)
                                       (do
                                         (trace-event! subscriptions subscriber server false sqn snapshot? data)
                                         (map #(msg/event sub-source
                                                          subscriber-id
                                                          %
                                                          false
                                                          sqn
                                                          snapshot?
                                                          data)
                                              subscriptions))

                                       ;; remote source - we need to propagate the request to an unique list of nodes
                                       ;; otherwise there will be event duplication
                                       (let [node (:node sub-source)]
                                         (when-not (contains? @remotes node)
                                           (vswap! remotes conj node)
                                           [(m/unicast (peer->address (ids/node-id (:ids state)) server-id)
                                                       {:type :node :node node}
                                                       request)]))))))
                         (filter some?)))])))

(>defn post
  [state source request]
  [::s/state ::common-spec/source ::request-spec/post => ::domain/operation-result]

  (let [{server-id       :peer_id
         subscription-id :subscription_id
         sqn             :sequence
         snapshot?       :snapshot
         data            :data} request]
    (let [server (peers/by-id state server-id :agm-domain)
          subscriber-id (get-in server [:agm-domain :interests subscription-id :subscriber])
          subscriber (peers/by-id state subscriber-id)]
      (when-let [sub-src (:source subscriber)]
        [state [(if (peers/local-peer? subscriber)
                  (do
                    (trace-event! [subscription-id] subscriber server true sqn snapshot? data)
                    (msg/event sub-src
                               (:id subscriber)
                               subscription-id
                               true
                               sqn
                               snapshot?
                               data))
                  (m/unicast (peer->address (ids/node-id (:ids state)) server-id)
                             {:type :node :node (:node sub-src)}
                             request))]]))))
