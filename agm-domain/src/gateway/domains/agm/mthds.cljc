(ns gateway.domains.agm.mthds
  (:require
    [gateway.common.messages :refer [success error] :as m]
    [gateway.common.utilities :refer [conj-in disj-in dissoc-in merge-in ensure-domain conj-if! state->]]
    #?(:cljs [gateway.common.utilities :refer-macros [state->]])

    [gateway.domains.agm.subscriptions :as subscriptions]
    [gateway.domains.agm.messages :as msg]
    [gateway.domains.agm.constants :as constants]
    [gateway.restrictions :as rst]

    [gateway.state.peers :as peers]

    [clojure.walk :refer [keywordize-keys]]
    [gateway.reason :refer [ex->Reason ->Reason throw-reason]]
    [gateway.address :refer [peer->address]]
    [gateway.id-generators :as ids]
    [gateway.common.utilities :as util]

    [ghostwheel.core :as g :refer [>defn >defn- >fdef => | <- ?]]
    [gateway.state.spec.state :as s]
    [gateway.state.spec.common :as common-spec]
    [gateway.domain :as domain]
    [gateway.domains.agm.spec.register :as register-spec]
    [gateway.domains.agm.spec.unregister :as unregister-spec]))


(defn- method->map
  [method]
  (let [method (util/keywordize method)]
    (if-let [parsed-restrictions (rst/parse (:restrictions method))]
      (assoc method :parsed-restrictions parsed-restrictions)
      method)))

(defn- map->method [method] (dissoc method :parsed-restrictions))


(defn- visible?
  [method provider-identity consumer-identity]
  (if-let [restrictions (:parsed-restrictions method)]
    (rst/check? restrictions provider-identity consumer-identity)
    true))

(defn remove-methods-from-consumer
  "Removes all methods of a given provider from a consumer. Generates announcement messages for the
  actually removed methods (those that were visible to the consumer)"

  ([state provider-id consumer-id]
   (let [consumer (peers/by-id state consumer-id)
         locator [:agm-domain :methods provider-id]
         method-ids (into #{} (map first (get-in consumer locator)))]
     (if (seq method-ids)
       (remove-methods-from-consumer state
                                     provider-id
                                     consumer
                                     locator
                                     method-ids)
       [(peers/update-peer state
                           consumer-id
                           (fn [c] (ensure-domain (dissoc-in c locator) :agm-domain)))
        nil])))
  ([state provider-id consumer locator method-ids]
   ;; build the list of method ids that are actually present on the consumer
   (let [provider (peers/by-id state provider-id)
         [consumer removed-ids] (reduce
                                  (fn [[consumer removed-ids] mid]
                                    (let [method-locator (conj locator mid)]
                                      (if (get-in consumer method-locator)
                                        [(dissoc-in consumer method-locator) (conj removed-ids mid)]
                                        [consumer removed-ids])))
                                  [consumer #{}]
                                  method-ids)

         state (peers/set-peer state
                               (:id consumer)
                               (ensure-domain consumer :agm-domain))]
     (if-not (seq removed-ids)
       ;; nothing has been removed - state is unchanged
       [state nil]

       ;; new state and removal messages
       (state-> (subscriptions/cancel-interest state
                                               consumer
                                               provider
                                               removed-ids
                                               constants/reason-method-removed)
                ((fn [state]
                   [state
                    (when (peers/local-peer? consumer)
                      (msg/methods-removed (:source consumer) (:id consumer) provider-id (vec removed-ids)))])))))))


(defn- unregister*
  [state request]
  (let [{:keys [peer_id methods]} request]
    (let [methods-locator [:agm-domain :methods peer_id]

          updated-peer (-> (reduce (fn [peer mid]
                                     (let [method-locator (conj methods-locator mid)]
                                       (if (get-in peer method-locator)
                                         (dissoc-in peer method-locator)
                                         (throw-reason constants/agm-unregistration-failure
                                                       (str "Unable to unregister missing method " mid)))))
                                   (peers/by-id* state peer_id)
                                   methods)
                           (ensure-domain :agm-domain))
          new-state (peers/set-peer state peer_id updated-peer)

          [new-state messages] (transduce (remove #(= peer_id (:id %)))
                                          (completing (fn [agg consumer]
                                                        (state-> agg
                                                                 (remove-methods-from-consumer peer_id
                                                                                               consumer
                                                                                               methods-locator
                                                                                               methods))))
                                          [new-state []]
                                          (peers/visible-peers state :agm-domain updated-peer))]
      [new-state messages])))

(defn- remote-unregister
  [state request]
  (unregister* state request))

(defn- local-unregister
  [state source request]
  (let [{:keys [request_id peer_id methods]} request]
    (let [[new-state messages] (unregister* state request)]
      [new-state (conj messages
                       (msg/methods-removed source peer_id peer_id methods)
                       (success constants/agm-domain-uri
                                source
                                request_id
                                peer_id)
                       (m/broadcast (peer->address (ids/node-id (:ids state)) peer_id)
                                    request))])))

(>defn unregister
  [state source request]
  [::s/state ::common-spec/source ::unregister-spec/unregister => ::domain/operation-result]

  (if (m/remote-source? source)
    (remote-unregister state request)
    (local-unregister state source request)))

(defn register-with-consumer
  "Registers methods from a given provider to a given consumer.

  It is assumed that the consumer and the provider can see each other"
  [state provider methods consumer]

  (let [provider-id (:id provider)
        provider-identity (:identity provider)

        consumer-id (:id consumer)
        consumer-identity (:identity consumer)
        existing-methods (get-in consumer [:agm-domain :methods provider-id])

        [remote mthds] (transduce (filter #(visible? % provider-identity consumer-identity))
                                  (completing (fn [[remote mtds] v]
                                                [(assoc remote (:id v) v)
                                                 (conj mtds (map->method v))]))
                                  [existing-methods []]
                                  methods)]
    (if (seq mthds)
      [(peers/set-peer state
                       consumer-id
                       (assoc-in consumer [:agm-domain :methods provider-id] remote))

       ;; messages are sent only to the local consumers
       (when (peers/local-peer? consumer)

         (msg/methods-added (:source consumer)
                            consumer-id
                            provider-id
                            mthds
                            (-> provider :source :type)))]

      [state nil])))


(defn- register*
  [state request]
  (let [{provider-id :peer_id methods :methods} request
        translated-methods (map method->map methods)

        ;; add the methods to the provider
        provider (reduce (fn [provider method]
                           (assoc-in provider
                                     [:agm-domain :methods provider-id (:id method)]
                                     method))
                         (peers/by-id* state provider-id)
                         translated-methods)
        updated-state (peers/set-peer state
                                      provider-id
                                      provider)
        ]

    ;; and register them with all eligible consumers
    (reduce (fn [agg consumer]
              (state-> agg
                       (register-with-consumer provider
                                               translated-methods
                                               consumer)))
            [updated-state []]
            (peers/visible-peers state :agm-domain provider))))

(defn- remote-register
  "Adds a sequence of methods belonging to a remote peer to all local peers

   Returns a [state (messages)]"
  [state request]
  (register* state request))

(defn- local-register
  "Adds a sequence of methods belonging to a local peer to all local peers

   Returns a [state (messages)]"
  [state source request]
  (let [{request_id :request_id provider-id :peer_id methods :methods} request]
    (let [[state messages] (register* state request)]

      [state
       (conj messages
             (msg/methods-added source provider-id provider-id methods :local)
             (success constants/agm-domain-uri
                      source
                      request_id
                      provider-id)
             (m/broadcast (peer->address (ids/node-id (:ids state)) provider-id)
                          request)
             )])))

(>defn register
  [state source request]
  [::s/state ::common-spec/source ::register-spec/register => ::domain/operation-result]

  (if (m/remote-source? source)
    (remote-register state request)
    (local-register state source request)))

(defn visible-messages
  [provider methods consumer]
  (let [provider-identity (:identity provider)
        consumer-identity (:identity consumer)]
    (transduce
      (filter #(visible? % provider-identity consumer-identity))
      (completing (fn [mtds v]
                    (conj mtds (assoc (map->method v) :server_id (:id provider)))))
      []
      methods)))

;(defn xx [st]
;  [(assoc st :a 1) ["bahor" "mahor"]])
;
;(defn test []
;  (state-> [{} ["x"]]
;           xx))
