(ns gateway.domains.bus.core
  (:require [taoensso.timbre :as timbre]
            [gateway.common.messages :refer [success error] :as m]

            [gateway.state.core :as state]
            [gateway.state.peers :as peers]

            [gateway.domains.bus.constants :as constants]
            [gateway.reason :refer [reason ex->Reason throw-reason request->Reason]]
            [gateway.domain :refer [Domain] :as domain]
            [gateway.common.commands :as commands]
            [gateway.id-generators :as ids]
            [gateway.address :refer [peer->address]]
            [gateway.restrictions :as rst]
            [gateway.domains.bus.messages :as msg]
            [gateway.domains.global.messages :as gmsg]
            [gateway.common.peer-identity :as peer-identity]))

;; joining and leaving the domain

(defn- remote-leave
  [state source request]
  (let [{:keys [peer_id]} request]
    (when-let [peer (peers/by-id state peer_id)]
      [(state/leave-domain state
                           peer_id
                           :bus-domain) nil])))

(defn- local-leave
  [state source request]
  (let [{:keys [request_id peer_id]} request]
    (let [state' (state/leave-domain state
                                     peer_id
                                     :bus-domain)]
      [state' [(m/success constants/bus-domain-uri
                          source
                          request_id
                          peer_id)
               (m/broadcast (peer->address (ids/node-id (:ids state)) peer_id)
                            (assoc request :type :leave))]])))

(defn- leave
  [state source request]
  (if (m/remote-source? source)
    (remote-leave state source request)
    (local-leave state source request)))


(defn source-removed
  "Source has disconnected from the system"

  [state source _]
  (timbre/debug "removing source" source)
  (let [state (reduce #(state/leave-domain %1 (:peer-id %2) :bus-domain)
                      state
                      (peers/by-source state source :bus-domain))]

    [state nil]))


(defn- remote-join
  "Remote peer is joining the domain.

  Updates the state and generates messages to all existing peers announcing the new peer"
  [state source {peer-id :peer_id restrictions :restrictions}]
  (when-not (state/in-domain state peer-id :bus-domain)
    [(state/join-domain state peer-id :bus-domain (rst/parse restrictions)) nil]))

(defn- local-join
  "Local peer is joining the domain.

  Updates the state and generates messages to all existing peers announcing the new peer"
  [state source request]
  (let [{request-id :request_id peer-id :peer_id peer-identity :identity restrictions :restrictions} request]
    (if (state/in-domain state peer-id :bus-domain)
      ;; the peer is already in the domain
      [state [(success constants/bus-domain-uri
                       source
                       request-id
                       peer-id)]]

      ;; we need to join it
      (let [state' (state/join-domain state peer-id :bus-domain (rst/parse restrictions))
            peer (peers/by-id state' peer-id)]
        [state' [(success constants/bus-domain-uri
                          source
                          request-id
                          peer-id)
                 (m/broadcast (peer->address (ids/node-id (:ids state)) peer-id)
                              (cond-> (assoc request :type :join)
                                      (:options peer) (assoc :options (:options peer))))]]))))

(defn join
  [state source request]
  (if (m/remote-source? source)
    (remote-join state source request)
    (local-join state source request)))

;; event publishing

(defn zip-peer-subscriptions
  "given a collection of peers, returns a [[peer subscription-a] [peer subscription-b]] where
  subscription-a and subscription-b are the topic subscriptions of the peer"

  [peers]
  (->> peers
       (map #(vector % (get-in % [:bus-domain :subscriptions])))
       (mapcat (fn [[k v]] (map (fn [peer subscription] {:peer         peer
                                                         :subscription subscription})
                                (repeat k) v)))))

(defn matches-topic?
  "checks if sub-topic matches topic"
  [{sub-topic :topic sub-pattern :topic-repattern} topic]
  (if sub-pattern
    (re-matches sub-pattern topic)
    (= sub-topic topic)))

(defn matches-routing?
  [sub-key key]
  (or (not sub-key)
      (not key)
      (= sub-key key)))

(defn matches-subscription?
  "checks if given topic and routing key match an existing subscription"
  [topic routing-key [_ subscription]]
  (and (matches-topic? subscription topic)
       (matches-routing? (:routing-key subscription) routing-key)))

(defn matches-target?
  [target peer]
  (let [peer-identity (:identity peer)]
    (reduce-kv
      (fn [_ k v]
        (if-not (= (get peer-identity k) v)
          (reduced false)
          true))
      true
      target)))

(defn publish*
  "distributes a message onto all matching subscribers"

  [state request publisher]
  (when-let [data (:data request)]
    (let [{topic :topic routing-key :routing_key publisher-id :peer_id target :target_identity} request
          matcher (partial matches-subscription? topic routing-key)
          target-matcher (partial matches-target? (peer-identity/keywordize-id target))
          publisher-identity (:identity publisher)
          remotes (volatile! #{})
          messages (->> (->> (peers/visible-peers state :bus-domain publisher true)
                             (filter target-matcher)
                             (zip-peer-subscriptions)
                             (filter #(matcher (:subscription %))))
                        (reduce (fn [messages {subscriber :peer subscription :subscription}]
                                  (let [sub-source (:source subscriber)
                                        subscriber-id (:id subscriber)
                                        msg (if (m/local-source? sub-source)
                                              (msg/event sub-source
                                                         subscriber-id
                                                         (first subscription)
                                                         publisher-identity
                                                         data)

                                              ;; remote source - we need to propagate the request to an unique list of nodes
                                              ;; otherwise there will be event duplication
                                              (when-not (= publisher-id subscriber-id)
                                                (let [node (:node sub-source)]
                                                  (when-not (contains? @remotes node)
                                                    (vswap! remotes conj node)
                                                    (m/unicast (peer->address (ids/node-id (:ids state)) publisher-id)
                                                               {:type :node :node node}
                                                               request)))))]
                                    (if msg (conj messages msg) messages)))
                                []))]
      [state messages])))

(defn- local-publish
  [state request]
  (let [publisher-id (:peer_id request)]
    (when-let [peer (peers/by-id* state publisher-id)]
      (publish* state request peer))))

(defn- remote-publish
  [state request]
  (let [publisher-id (:peer_id request)]
    (when-let [peer (peers/by-id state publisher-id)]
      (publish* state request peer))))

(defn publish
  [state source request]
  (if (m/local-source? source)
    (local-publish state request)
    (remote-publish state request)))

;; subscribing

(defn wildcard?
  [topic]
  (or (clojure.string/includes? topic ">") (clojure.string/includes? topic "*")))

(defn topic->pattern
  [topic]
  (-> topic
      (clojure.string/replace "." "\\.")
      (clojure.string/replace "*" "[a-zA-Z_0-9]+")
      (clojure.string/replace ">" ".*")
      (re-pattern)))

(defn subscribe*
  [state request subscription-id peer]
  (let [{topic :topic routing-key :routing_key request-id :request_id peer-id :peer_id} request]
    (let [source (:source peer)]
      (let [peer (assoc-in peer
                           [:bus-domain :subscriptions subscription-id]
                           (cond-> {:topic topic :routing-key routing-key}
                                   (wildcard? topic) (assoc :topic-repattern (topic->pattern topic))))]
        [(peers/set-peer state peer-id peer) (when (m/local-source? source)
                                               [(msg/subscribed source
                                                                request-id
                                                                peer-id
                                                                subscription-id)
                                                (m/broadcast (peer->address (ids/node-id (:ids state)) peer-id)
                                                             (assoc request :subscription_id subscription-id))])]))))

(defn- local-subscribe
  [state source request]
  (let [[new-ids subscription-id] (ids/request-id (:ids state))
        peer-id (:peer_id request)
        peer (peers/by-id* state peer-id :bus-domain)]
    (subscribe* (assoc state :ids new-ids) request subscription-id peer)))

(defn- remote-subscribe
  [state request]
  (let [{subscription-id :subscription_id peer-id :peer_id} request
        peer (peers/by-id state peer-id :bus-domain)]
    (when peer
      (subscribe* state request subscription-id peer))))

(defn subscribe
  [state source request]
  (if (m/local-source? source)
    (local-subscribe state source request)
    (remote-subscribe state request)))

;; unsubscribing

(defn unsubscribe*
  [state request peer]
  (let [{request-id :request_id peer-id :peer_id subscription-id :subscription_id} request]
    (let [source (:source peer)]
      (let [peer (update-in peer [:bus-domain :subscriptions] dissoc subscription-id)]
        [(peers/set-peer state peer-id peer) (when (m/local-source? source)
                                               [(m/success constants/bus-domain-uri
                                                           source
                                                           request-id
                                                           peer-id)
                                                (m/broadcast (peer->address (ids/node-id (:ids state)) peer-id) request)])]))))

(defn- local-unsubscribe
  [state source request]
  (let [peer (peers/by-id* state (:peer_id request) :bus-domain)]
    (unsubscribe* state request peer)))

(defn- remote-unsubscribe
  [state request]
  (when-let [peer (peers/by-id state (:peer_id request))]
    (unsubscribe* state request peer)))

(defn unsubscribe
  [state source request]
  (if (m/local-source? source)
    (local-unsubscribe state source request)
    (remote-unsubscribe state request)))

(defmulti handle-request (fn [state source request] (:type request)))

(defmethod handle-request ::domain/join
  [state source request]
  (join state source request))

(defmethod handle-request ::domain/leave
  [state source request]
  (leave state source request))

(defmethod handle-request :publish
  [state source request]
  (publish state source request))

(defmethod handle-request :subscribe
  [state source request]
  (subscribe state source request))

(defmethod handle-request :unsubscribe
  [state source request]
  (unsubscribe state source request))

(defmethod handle-request ::commands/source-removed
  [state source request]
  (source-removed state source request))


(defmethod handle-request :default
  [state source body _ _]
  (timbre/error "Unhandled message" body)
  [state [(error constants/bus-domain-uri
                 source
                 (:request_id body -1)
                 (:peer_id body)
                 (reason constants/bus-unhandled-message
                         (str "Unhandled message " body)))]])

(defn subscriptions->messages
  "generates messages that applied on a remote node will join the peer to the bus domain and register its subscriptions"
  [peer]
  (let [peer-id (:id peer)]
    (concat [(gmsg/join nil
                        peer-id
                        (get-in peer [:bus-domain :restrictions])
                        constants/bus-domain-uri
                        (:identity peer))]
            (->> (get-in peer [:bus-domain :subscriptions])
                 (mapv (fn [[sub-id sub]] (msg/subscribe peer-id (:topic sub) (:routing-key sub) sub-id)))))))

(deftype BusDomain []
  Domain

  (info [this] {:uri         constants/bus-domain-uri
                :description ""
                :version     1})
  (init [this state] state)
  (destroy [this state] state)

  (handle-message
    [this state msg]
    (let [{:keys [source body]} msg]
      (try
        (handle-request state source body)
        (catch #?(:clj  Exception
                  :cljs :default) e
          (when-not (ex-data e) (timbre/error e "Error processing message" msg))
          [state [(error constants/bus-domain-uri
                         source
                         (:request_id body)
                         (:peer_id body)
                         (ex->Reason e constants/failure))]]))))

  (state->messages
    [this state]
    (let [node-id (ids/node-id (:ids state))
          result (->> (peers/peers state :bus-domain)
                      (filter #(m/local-source? (:source %)))
                      (mapcat #(let [peer-id (:id %)]
                                 (map (partial m/unicast (peer->address node-id peer-id) nil)
                                      (subscriptions->messages %))))
                      (vec))]
      (timbre/debug "Node" node-id "sending state" result)
      result)))

(defn bus-domain []
  (->BusDomain))