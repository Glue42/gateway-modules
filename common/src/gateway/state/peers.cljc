(ns gateway.state.peers
  (:require [gateway.state.core :as state]

            [gateway.common.messages :as m]
            [gateway.id-generators :as ids]
            [gateway.reason :refer [->Reason throw-reason]]
            [gateway.restrictions :as rst]

            [ghostwheel.core :refer [>defn >defn- >fdef => | <- ?]]
            [gateway.state.spec.domain-registry :as dr-spec]
            [gateway.state.spec.state :as state-spec]
            [gateway.state.spec.common :as common-spec]
            [gateway.domain :as domain-spec]
            [gateway.common.spec.messages :as msg-spec]))


(defrecord Peer [id identity source options])

;; locating peers

(defn by-identity [state ident]
  (get-in state [:identities ident]))

(defn by-id
  (
   [state id]
   (when id
     (get-in state [:peers id])))
  (
   [state id domain]
   (let [peer (by-id state id)]
     (when (get peer domain)
       peer))))

(defn by-id*
  "Same as by-id but throws an exception instead"
  (
   [state id]
   (when (nil? id)
     (throw (ex-info "Peer id is missing" {})))
   (if-let [result (by-id state id)]
     result
     (throw (ex-info (str "Unable to find peer with id " id) {}))))
  (
   [state id domain]
   (when (nil? id)
     (throw (ex-info "Peer id is missing" {})))
   (if-let [result (by-id state id domain)]
     result
     (throw (ex-info (str "Unable to find peer with id " id " in domain " domain) {})))))

(defn local-peer?
  [peer]
  (= :local (get-in peer [:source :type])))

(defn peer-count
  [state]
  (count (:peers state)))

(defn peers
  (
   [state]
   (eduction (map val) (:peers state)))
  (
   [state domain]
   (eduction (map (partial by-id state)) (get-in state [:domains domain]))))


(defn source-subset-of?
  [source subset]
  (case (:type subset)
    :node (= (:node subset) (:node source))
    :peer (and (= (:node subset) (:node source))
               (= (:peer-id subset) (:peer-id source)))
    :local (= (:channel subset) (:channel source))
    false))

(defn by-source
  (
   [state source]
   (eduction (comp
               (map val)
               (filter #(source-subset-of? (:source %) source)))
             (:peers state)))
  (
   [state source domain]
   (eduction (filter #(source-subset-of? (:source %) source))
             (peers state domain))))

(defn by-user
  [state user]
  (get-in state [:users (or user :no-user)]))

;; adding/removing peers

(defn ensure-peer-with-id
  [state source peer-id resolved-identity creation-request options]
  (if-let [peer (by-id state peer-id)]
    [state peer]
    (let [peer (cond-> (->Peer peer-id resolved-identity source options)
                       creation-request (assoc :creation-request creation-request))
          user (:user resolved-identity :no-user)
          state (cond-> (-> state
                            (assoc-in [:identities resolved-identity] peer-id)
                            (update-in [:users user] (fnil conj #{}) peer-id)
                            (assoc-in [:peers peer-id] peer))
                        (:service? options) (update :services (fnil conj #{}) peer-id))]

      [state peer])))

(defn ensure-peer
  (
   [state source resolved-identity creation-request options]
   (let [[new-ids peer-id] (ids/peer-id (:ids state))]
     (ensure-peer-with-id (assoc state :ids new-ids)
                          source
                          peer-id
                          resolved-identity
                          creation-request
                          options)))
  (
   [state source resolved-identity creation-request]
   (ensure-peer state source resolved-identity creation-request nil)))

(defn remove-peer
  [state peer]
  (let [idnt (:identity peer)
        id (:id peer)
        user (:user idnt :no-user)]
    (-> state
        (update :identities dissoc idnt)
        (update :users (fn [users]
                         (let [v (-> (get users user)
                                     (disj id))]
                           (if (seq v)
                             (assoc users user v)
                             (dissoc users user)))))
        (update :peers dissoc id)
        (update :services (fnil disj #{}) id))))

(defn set-peer
  [state peer-id peer]
  (assoc-in state [:peers peer-id] peer))

(defn update-peer
  [state peer-id f & args]
  (apply update-in state [:peers peer-id] f args))

;; visibility

(defn peer-visible?
  "Returns whether two peers can see each other"

  ([lhs-identity lhs-restrictions lhs-service? rhs-identity rhs-restrictions rhs-service?]
   (if (and (nil? lhs-restrictions) (nil? rhs-restrictions))
     (let [rhs-user (:user rhs-identity)
           lhs-user (:user lhs-identity)]
       (or rhs-service? lhs-service?
           (= rhs-user lhs-user)))
     (and (rst/check? lhs-restrictions lhs-identity rhs-identity)
          (rst/check? rhs-restrictions rhs-identity lhs-identity))))

  ([domain lhs-peer rhs-peer]
   (let [lhs-identity (:identity lhs-peer)
         lhs-restrictions (get-in lhs-peer [domain :restrictions])
         lhs-service? (get-in lhs-peer [:options :service?])

         rhs-identity (:identity rhs-peer)
         rhs-restrictions (get-in rhs-peer [domain :restrictions])
         rhs-service? (get-in lhs-peer [:options :service?])]
     (and (not= (:id rhs-peer) (:id lhs-peer))
          (peer-visible? lhs-identity lhs-restrictions lhs-service?
                         rhs-identity rhs-restrictions rhs-service?)))))

(defn all-visible-peers
  "Matches all visible peers disregarding their user"
  [state domain peer include-self?]
  (eduction (filter #(or (peer-visible? domain peer %)
                         (and include-self? (= (:id %) (:id peer)))))
            (concat (peers state domain)
                    (map (partial by-id state) (:services state)))))

(defn visible-peers
  "Returns a sequence of ids of the consumers that are seen by a provider"

  ([state domain peer]
   (visible-peers state domain peer false))
  (
   [state domain peer include-self?]
   (if (get-in peer [:options :service?])
     (all-visible-peers state domain peer include-self?)

     (let [user (get-in peer [:identity :user])]
       (if user
         (eduction (comp (map (partial by-id state))
                         (filter #(or (and (state/in-domain % domain)
                                           (peer-visible? domain peer %))
                                      (and include-self? (= (:id %) (:id peer))))))
                   (concat (get-in state [:users user])
                           (:services state)))

         (all-visible-peers state domain peer include-self?))))))

(defn- announce-peer-messages
  "Generates the peer announcement messages for both sides"

  [domain-uri existing-messages receiver new-peer existing-peer]
  (let [existing-identity (:identity existing-peer)
        existing-id (:id existing-peer)

        new-peer-id (:id new-peer)
        local? (local-peer? new-peer)]

    (cond-> existing-messages
            local? (#(conj % (m/peer-added domain-uri
                                           receiver

                                           new-peer-id
                                           existing-id
                                           existing-identity
                                           {:local local?})))
            (local-peer? existing-peer) (#(conj % (m/peer-added domain-uri
                                                                (:source existing-peer)
                                                                existing-id
                                                                new-peer-id
                                                                (:identity new-peer)
                                                                {:local local?}))))))

(>defn announce-peer
       [domain-uri domain-selector state receiver peer]
       [::dr-spec/domain-uri keyword? ::state-spec/state ::common-spec/source ::state-spec/peer => ::msg-spec/outgoing-messages]

       (reduce (fn [messages existing-peer]
                 (announce-peer-messages domain-uri
                                         messages
                                         receiver
                                         peer
                                         existing-peer))
               []
               (visible-peers state domain-selector peer)))


(>defn leave-domain
       "Peer is leaving the domain.

       Updates the state and generates messages for all other peers"
       [domain-uri domain-selector state leaving-peer reason source-removed?]
       [::dr-spec/domain-uri keyword? ::state-spec/state ::state-spec/peer any? boolean? => ::domain-spec/operation-result]
       (let [peer-id (:id leaving-peer)]
         [(state/leave-domain state peer-id domain-selector) (reduce (fn [messages removed-from]
                                                                       (cond-> (conj messages (m/peer-removed domain-uri
                                                                                                              (:source removed-from)
                                                                                                              (:id removed-from)
                                                                                                              peer-id
                                                                                                              reason))
                                                                               (not source-removed?) (conj (m/peer-removed domain-uri
                                                                                                                           (:source leaving-peer)
                                                                                                                           peer-id
                                                                                                                           (:id removed-from)
                                                                                                                           reason))))
                                                                     []
                                                                     (visible-peers state domain-selector leaving-peer))]))