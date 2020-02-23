(ns gateway.domains.agm.core
  (:require
    [taoensso.timbre :as timbre]
    [gateway.common.messages :refer [success error] :as m]
    [gateway.common.utilities :refer [conj-in disj-in dissoc-in state->]]
    #?(:cljs [gateway.common.utilities :refer-macros [state->]])
    [gateway.domains.agm.mthds :as methods]
    [gateway.domains.agm.messages :as msg]
    [gateway.domains.agm.calls :as calls]
    [gateway.domains.agm.subscriptions :as subs]
    [gateway.restrictions :as rst]
    [gateway.address :refer [peer->address]]

    [gateway.state.core :as state]
    [gateway.state.peers :as peers]

    [gateway.domains.agm.constants :as constants]
    [gateway.reason :refer [reason ex->Reason request->Reason]]
    [gateway.domain :refer [Domain] :as domain]
    [gateway.domains.global.messages :as gmsg]
    [gateway.id-generators :as ids]
    [gateway.common.commands :as commands]

    [ghostwheel.core :as g :refer [>defn >defn- >fdef => | <- ?]]
    [gateway.state.spec.state :as s]
    [gateway.domains.agm.spec.messages]
    [gateway.common.spec.messages :as message-spec]
    [gateway.common.spec.requests :as cr-spec]
    [gateway.state.spec.common :as common-spec]))

(defn add-peer
  "Adds a new peer to the list of peers of an existing peer. Only the local peers receive announcement messages"

  [state receiver new-peer existing-peer]
  (let [existing-identity (:identity existing-peer)
        existing-id (:id existing-peer)
        existing-methods (eduction (map val)
                                   (get-in existing-peer [:agm-domain :methods existing-id]))

        new-peer-id (:id new-peer)
        local? (peers/local-peer? new-peer)
        existing-local? (peers/local-peer? existing-peer)

        ;; generate messages announcing the link to both sides
        messages (cond-> []
                         local? (#(conj % (m/peer-added constants/agm-domain-uri
                                                        receiver
                                                        new-peer-id
                                                        existing-id
                                                        existing-identity
                                                        {:local existing-local?})))
                         existing-local? (#(conj % (m/peer-added constants/agm-domain-uri
                                                                 (:source existing-peer)
                                                                 existing-id
                                                                 new-peer-id
                                                                 (:identity new-peer)
                                                                 {:local local?}))))


        ;; and finally register all the existing methods to the consumer
        [state message] (methods/register-with-consumer state
                                                        existing-peer
                                                        existing-methods
                                                        new-peer)]

    [state (if message
             (conj messages message)
             messages)]))

(defn- announce-peer
  [state receiver peer-id]
  (let [peer (peers/by-id* state peer-id :agm-domain)]
    (reduce (fn [[state _ :as agg] el]
              (state-> agg
                       (add-peer receiver
                                 (peers/by-id state peer-id)
                                 el)))
            [state []]
            (peers/visible-peers state :agm-domain peer))))


(defn- join*
  [state source peer-id peer-identity restrictions]
  (let [parsed-restrictions (rst/parse restrictions)]
    (-> state
        (state/join-domain peer-id :agm-domain parsed-restrictions)
        (announce-peer source peer-id))))

(defn- remote-join
  "Remote peer is joining the domain.

  Updates the state and generates messages to all existing peers announcing the new peer"
  [state source request]
  (let [{peer-id :peer_id peer-identity :identity restrictions :restrictions} request]
    (when-not (state/in-domain state peer-id :agm-domain)
      (join* state source peer-id peer-identity restrictions))))

(defn- local-join
  "Local peer is joining the domain.

  Updates the state and generates messages to all existing peers announcing the new peer"
  [state source request]
  (let [{request-id :request_id peer-id :peer_id peer-identity :identity restrictions :restrictions} request]
    (if (state/in-domain state peer-id :agm-domain)
      ;; the peer is already in the domain
      [state [(success constants/agm-domain-uri
                       source
                       request-id
                       peer-id)]]

      ;; we need to join it
      (let [[state' msgs] (join* state source peer-id peer-identity restrictions)
            peer (peers/by-id state' peer-id)]
        [state' (conj msgs
                      (success constants/agm-domain-uri
                               source
                               request-id
                               peer-id)
                      (m/broadcast (peer->address (ids/node-id (:ids state)) peer-id)
                                   (cond-> (assoc request :type :join)
                                           (:options peer) (assoc :options (:options peer)))))]))))

(>defn join
  [state source request]
  [::s/state ::common-spec/source ::cr-spec/join => ::domain/operation-result]
  (if (m/remote-source? source)
    (remote-join state source request)
    (local-join state source request)))

(defn- detach-peer
  "Detaches a peer from an existing one"

  [state peer from-peer-id reason]
  (let [from-peer (peers/by-id state from-peer-id)
        recipient (:source from-peer)
        [state msgs] (state-> [state nil]
                              (calls/cancel-invocations recipient from-peer peer)
                              (subs/cancel-interest from-peer peer nil constants/reason-peer-removed)
                              (methods/remove-methods-from-consumer (:id peer) from-peer-id))]
    [state (if (m/local-source? recipient)
             (conj msgs (m/peer-removed constants/agm-domain-uri
                                        recipient
                                        from-peer-id
                                        (:id peer)
                                        reason))
             msgs)]))

(defn remove-peer
  "Peer is leaving the domain.

  Updates the state and generates messages for the peer and its registered methods being removed"
  [state peer reason]
  (let [peer-id (:id peer)

        [new-state messages] (transduce (map :id)
                                        (completing (fn [sm from-id]
                                                      (state-> sm
                                                               (detach-peer peer from-id reason))))
                                        (subs/cancel-subscriptions state peer reason)
                                        (peers/visible-peers state :agm-domain peer))]
    [(state/leave-domain new-state peer-id :agm-domain) messages]))

(defn- remote-leave
  [state source request]
  (let [{:keys [peer_id]} request]
    (when-let [peer (peers/by-id state peer_id)]
      (remove-peer state peer (request->Reason request)))))

(defn- local-leave
  [state source request]
  (let [{:keys [request_id peer_id]} request]
    (let [[new-state msgs] (remove-peer state
                                        (peers/by-id* state peer_id)
                                        (request->Reason request))]
      [new-state (conj msgs
                       (success constants/agm-domain-uri
                                source
                                request_id
                                peer_id)
                       (m/broadcast (peer->address (ids/node-id (:ids state)) peer_id)
                                    (assoc request :type :leave)))])))

(defn- leave
  [state source request]
  (if (m/remote-source? source)
    (remote-leave state source request)
    (local-leave state source request)))


(>defn source-removed
  "Source has disconnected from the system"

  [state source _]
  [::s/state ::common-spec/source any? => ::domain/operation-result]
  (timbre/debug "removing source from agm domain")
  (let [affected (sequence (peers/by-source state source :agm-domain))
        state-without-affected (transduce (map :id)
                                          (completing #(state/leave-domain %1 %2 :agm-domain))
                                          state
                                          affected)
        r (reduce (fn [sm peer]
                    (state-> sm
                             (remove-peer peer constants/reason-peer-removed)))
                  [state-without-affected nil]
                  affected)]
    (timbre/debug "removed source from agm domain")
    r))

(defn handle-error
  "Handles an error that is coming as a response to either an invocation on subscription.

  Returns [<new state> <messages>]"
  [state source request]
  (let [{:keys [request_id peer_id]} request]
    (peers/by-id* state peer_id)
    (if-let [response (calls/complete-invocation state request_id peer_id request {:failure request})]
      response
      (subs/drop-interest state source request true))))

(defn- peer-methods [state receiver {:keys [peer_id request_id] :as r}]
  (let [peer (peers/by-id* state peer_id :agm-domain)
        methods (->> peer
                     (peers/visible-peers state :agm-domain)
                     (filter peers/local-peer?)
                     (map (fn [{:keys [id] :as existing-peer}]
                            (let [new-peer (peers/by-id state peer_id)
                                  existing-methods (vals (get-in existing-peer [:agm-domain :methods id]))]
                              (methods/visible-messages existing-peer existing-methods new-peer))))
                     flatten
                     vec)]
    [state [(m/outgoing receiver {:methods    methods
                                  :request_id request_id
                                  :type       :methods
                                  :peer_id    peer_id})]]))

(defmulti handle-request (fn [state source request] (:type request)))

(defmethod handle-request ::domain/join
  [state source request]
  (join state source request))

(defmethod handle-request ::domain/leave
  [state source request]
  (leave state source request))

(defmethod handle-request :register
  [state source request]
  (methods/register state source request))

(defmethod handle-request :unregister
  [state source request]
  (methods/unregister state source request))

(defmethod handle-request :call
  [state source request]
  (calls/call state source request))

(defmethod handle-request :yield
  [state source request]
  (calls/yield state source request))

(defmethod handle-request :subscribe
  [state source request]
  (subs/subscribe state source request))

(defmethod handle-request :unsubscribe
  [state source request]
  (subs/unsubscribe state source request))

(defmethod handle-request :drop-subscription
  [state source request]
  (subs/drop-interest state source request false))

(defmethod handle-request :accepted
  [state source request]
  (subs/accepted state source request))

(defmethod handle-request :publish
  [state source request]
  (subs/publish state source request))

(defmethod handle-request :post
  [state source request]
  (subs/post state source request))

(defmethod handle-request :error
  [state source request]
  (handle-error state source request))

(defmethod handle-request ::commands/source-removed
  [state source request]
  (source-removed state source request))

(defmethod handle-request ::peer-methods
  [state source request]
  (peer-methods state source request))

(defmethod handle-request :default
  [state source body]
  (timbre/error "Unhandled message" body)
  [state [(error constants/agm-domain-uri
                 source
                 (:request_id body -1)
                 (:peer_id body)
                 (reason constants/agm-unhandled-message
                         (str "Unhandled message " body)))]])

(>defn -handle-message
  [state msg]
  [::s/state ::message-spec/incoming-message => ::domain/operation-result]

  (let [{:keys [source body]} msg]
    (try
      (handle-request state source body)
      (catch #?(:clj  Exception
                :cljs :default) e
        (when-not (ex-data e) (timbre/error e "Error handling request" msg))
        (when (m/local-source? source)
          [state [(error constants/agm-domain-uri
                         source
                         (:request_id body)
                         (:peer_id body)
                         (ex->Reason e constants/failure))]])))))

(deftype AgmDomain []
  Domain

  (info [this] {:uri         constants/agm-domain-uri
                :description ""
                :version     1})
  (init [this state] state)
  (destroy [this state] state)

  (handle-message [this state msg] (-handle-message state msg))

  (state->messages
    [this state]
    (let [node-id (ids/node-id (:ids state))
          result (->> (peers/peers state :agm-domain)
                      (filter #(m/local-source? (:source %)))
                      (mapcat #(let [peer-id (:id %)]
                                 (map (partial m/unicast (peer->address node-id peer-id) nil)
                                      [(gmsg/join nil
                                                  peer-id
                                                  (get-in % [:agm-domain :restrictions])
                                                  constants/agm-domain-uri
                                                  (:identity %))
                                       (msg/register nil
                                                     peer-id
                                                     (mapv val (get-in % [:agm-domain :methods peer-id])))])))
                      (vec))]
      (timbre/debug "Node" node-id "sending state" result)
      result)))


(defn agm-domain [] (->AgmDomain))
