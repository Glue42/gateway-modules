;(ns gateway.domains.activity.t-factories)
(ns gateway.domains.activity.t-factories
  (:require
    [clojure.test :refer :all]

    #?(:cljs [gateway.t-macros :refer-macros [spec-valid? just? error-msg?]])
    #?(:clj [gateway.t-macros :refer [spec-valid? just? error-msg?]])

    [gateway.domains.activity.core :refer [join ready handle-error]]

    [gateway.state.core :as core-state]
    [gateway.domains.activity.state :as state]
    [gateway.state.peers :as peers]

    [gateway.state.spec.state :as ss]
    [gateway.t-helpers :refer [ch->src gen-identity peer-id! request-id! new-state]]
    [gateway.domains.global.core :as global]
    [gateway.domains.activity.factories :as factories]
    [gateway.domains.activity.core :as activity]
    [gateway.domains.activity.constants :as constants]
    [gateway.common.tokens :as tokens]
    [gateway.domains.activity.messages :as msg]
    [gateway.common.messages :as m]
    [gateway.reason :as reason]
    [gateway.common.cache.cache :as c]))

(def environment {:local-ip "127.0.0.1"})

;
;(stest/instrument)
;
;(deftest test-adding-factories
;  (let [source "source"
;        empty-state (state/empty-state)
;
;        peer-id-1 (peer-id!)
;        identity-1 (gen-identity)
;
;        factory-id 1
;        peer-type "my-peer-type"
;
;        factory-2-id 2
;        peer-type-2 "peer-type2"
;
;        factory-3-id 3
;        peer-type-3 "peer-type3"
;
;        configuration {:config-key "config-value"}
;        flags {:flag-key "flag-value"}
;        factory-data {:id            factory-id
;                      :peer_type     peer-type
;                      :configuration configuration
;                      :flags         flags}
;        factory-2-data {:id            factory-2-id
;                        :peer_type     peer-type-2
;                        :configuration configuration
;                        :flags         flags}
;
;        factory-3-data {:id            factory-3-id
;                        :peer_type     peer-type-3
;                        :configuration configuration
;                        :flags         flags}
;
;        request {:request_id 1
;                 :peer_id    peer-id-1
;                 :factories  [factory-data factory-2-data]}
;
;        [state-with-factories messages] (-> empty-state
;                                            (state/create-peer source peer-id-1 identity-1 nil)
;                                            (first)
;                                            (core-state/join-domain peer-id-1 :activity-domain nil)
;                                            (factories/add source request))
;
;        [state-with-more-factories more-messages] (-> state-with-factories
;                                                      (factories/add source {:request_id 3
;                                                                             :peer_id    peer-id-1
;                                                                             :factories  [factory-3-data]}))]
;
;
;    (testing "A peer can add factories"
;      (spec-valid? ::ss/state state-with-factories)
;      (is (= state-with-factories (merge
;                                    empty-state
;                                    {:factories  {peer-type   [peer-id-1]
;                                                  peer-type-2 [peer-id-1]}
;                                     :identities {identity-1 peer-id-1}
;                                     :peers      {peer-id-1 (peers/map->Peer {:activity-domain {:factories {peer-type   factory-data
;                                                                                                            peer-type-2 factory-2-data}}
;                                                                              :id              peer-id-1
;                                                                              :identity        identity-1
;                                                                              :source          source})}})))
;      (is (= messages [
;                       {:body  {:factories [factory-data factory-2-data]
;                                   :owner_id  peer-id-1
;                                   :peer_id   peer-id-1
;                                   :type      :peer-factories-added}
;                        :receiver "source"}
;                       {:body  {:peer_id    peer-id-1
;                                   :request_id 1
;                                   :type       :success}
;                        :receiver source}])))
;    (testing "Any peers that join later on will receive the existing factories"
;      (let [peer-id-2 (peer-id!)
;            identity-2 (gen-identity)
;            join-r-id (request-id!)
;            [state messages] (-> state-with-factories
;                                 (state/create-peer source peer-id-2 identity-2 nil)
;                                 (first)
;                                 (join source {:request_id join-r-id
;                                               :peer_id    peer-id-2
;                                               :identity   identity-2}))]
;        (spec-valid? ::ss/state state)
;        (is (= messages [{:body  {:factories [factory-data factory-2-data]
;                                     :owner_id  peer-id-1
;                                     :peer_id   peer-id-2
;                                     :type      :peer-factories-added}
;                          :receiver source}
;                         {:body  {:peer_id    peer-id-2
;                                     :request_id join-r-id
;                                     :type       :success}
;                          :receiver source}]))))
;    (testing "Removing a factory is announced to all peers"
;      (let [peer-id-2 (peer-id!)
;            identity-2 (gen-identity)
;            join-r-id (request-id!)
;            remove-r-id (request-id!)
;            [state messages] (-> state-with-factories
;                                 (state/create-peer source peer-id-2 identity-2 nil)
;                                 (first)
;                                 (join source {:request_id join-r-id
;                                               :peer_id    peer-id-2
;                                               :identity   identity-2})
;                                 (first)
;                                 (factories/remove-factories source {:request_id  remove-r-id
;                                                                     :peer_id     peer-id-1
;                                                                     :factory_ids [factory-id]})
;                                 )]
;        (spec-valid? ::ss/state state)
;        (is (= messages [{:body  {:factory_ids [1]
;                                     :owner_id    peer-id-1
;                                     :peer_id     peer-id-1
;                                     :type        :peer-factories-removed}
;                          :receiver "source"}
;                         {:body  {:factory_ids [1]
;                                     :owner_id    peer-id-1
;                                     :peer_id     peer-id-2
;                                     :type        :peer-factories-removed}
;                          :receiver "source"}
;                         {:body  {:peer_id    peer-id-1
;                                     :request_id remove-r-id
;                                     :type       :success}
;                          :receiver "source"}]))))
;
;    (testing "Removing the factory owner unregisters the factory"
;      (let [peer-id-2 (peer-id!)
;            identity-2 (gen-identity)
;            join-r-id (request-id!)
;            leave-r-id (request-id!)
;            [state messages] (-> state-with-factories
;                                 (state/create-peer source peer-id-2 identity-2 nil)
;                                 (first)
;                                 (join source {:request_id join-r-id
;                                               :peer_id    peer-id-2
;                                               :identity   identity-2})
;                                 (first)
;                                 (leave source {:request_id leave-r-id
;                                                :peer_id    peer-id-1})
;
;                                 )]
;        (spec-valid? ::ss/state state)
;        (is (complement (seq (state/factories state))))
;        (is (= messages [{:body  {:factory_ids [factory-id factory-2-id]
;                                     :owner_id    peer-id-1
;                                     :peer_id     peer-id-2
;                                     :type        :peer-factories-removed}
;                          :receiver source}
;                         {:body  {:peer_id    peer-id-1
;                                     :request_id leave-r-id
;                                     :type       :success}
;                          :receiver source}]))))
;    ))
;
;
;(deftest test-factory-added-twice-failure
;  (testing "that a peer cannot add a factory for the same type twice"
;    (let [source "source"
;          empty-state (state/empty-state)
;
;          peer-id-1 (peer-id!)
;          identity-1 (gen-identity)
;          factory-id 1
;          peer-type "my-peer-type"
;          configuration {:config-key "config-value"}
;          flags {:flag-key "flag-value"}
;          request {:request_id 1
;                   :peer_id    peer-id-1
;                   :factories  [
;                                {:id            factory-id
;                                 :peer_type     peer-type
;                                 :configuration configuration
;                                 :flags         flags}]}
;
;          state-with-factories (-> empty-state
;                                   (state/create-peer source peer-id-1 identity-1 nil)
;                                   (first)
;                                   (core-state/join-domain peer-id-1 :activity-domain nil)
;                                   (factories/add source request)
;                                   (first))
;          [state messages] (-> state-with-factories
;                               (factories/add source request))]
;      (spec-valid? ::ss/state state)
;      (is (= state state-with-factories))
;      (is (= (error-msg? constants/activity-registration-failure (first messages)))))))
;
;(deftest test-peers-adding-same-factories
;  (testing "that two different peers can add factories for the same type"
;    (let [source "source"
;          empty-state (state/empty-state)
;
;          peer-id-1 (peer-id!)
;          peer-id-2 (peer-id!)
;          identity-1 (gen-identity)
;          identity-2 (gen-identity)
;          factory-id 1
;          peer-type "my-peer-type"
;          configuration {:config-key "config-value"}
;          flags {:flag-key "flag-value"}
;          factory-data {:id            factory-id
;                        :peer_type     peer-type
;                        :configuration configuration
;                        :flags         flags}
;          request {:request_id 1
;                   :peer_id    peer-id-1
;                   :factories  [factory-data]}
;
;          [state messages] (-> empty-state
;                               (state/create-peer source peer-id-1 identity-1 nil)
;                               (first)
;                               (state/create-peer source peer-id-2 identity-2 nil)
;                               (first)
;                               (core-state/join-domain peer-id-1 :activity-domain nil)
;                               (core-state/join-domain peer-id-2 :activity-domain nil)
;                               (factories/add source request)
;                               (first)
;                               (factories/add source (assoc request :peer_id peer-id-2)))]
;      (spec-valid? ::ss/state state)
;      (is (= state (merge
;                     empty-state
;                     {:factories  {peer-type [peer-id-1 peer-id-2]}
;                      :identities {identity-1 peer-id-1
;                                   identity-2 peer-id-2}
;                      :peers      {peer-id-1 (peers/map->Peer {:activity-domain {:factories {peer-type factory-data}}
;                                                               :id              peer-id-1
;                                                               :identity        identity-1
;                                                               :source          source})
;                                   peer-id-2 (peers/map->Peer {:activity-domain {:factories {peer-type factory-data}}
;                                                               :id              peer-id-2
;                                                               :identity        identity-2
;                                                               :source          source})}})))
;      (is (= messages [{:body  {:factories [factory-data]
;                                   :owner_id  peer-id-2
;                                   :peer_id   peer-id-1
;                                   :type      :peer-factories-added}
;                        :receiver "source"}
;                       {:body  {:factories [factory-data]
;                                   :owner_id  peer-id-2
;                                   :peer_id   peer-id-2
;                                   :type      :peer-factories-added}
;                        :receiver "source"}
;                       {:body  {:peer_id    peer-id-2
;                                   :request_id 1
;                                   :type       :success}
;                        :receiver source}])))))
;
(deftest test-removing-factories
  (let [source-1 (ch->src "source")
        source-2 (ch->src "source-2")
        empty-state (new-state)

        peer-id-1 (peer-id!)
        peer-id-2 (peer-id!)

        identity-1 (gen-identity)
        identity-2 (gen-identity)

        factory-id 1
        peer-type "my-peer-type"
        configuration {:config-key "config-value"}
        flags {:flag-key "flag-value"}
        factory-data {:id            factory-id
                      :peer_type     peer-type
                      :configuration configuration
                      :flags         flags}
        request {:request_id 1
                 :peer_id    peer-id-1
                 :factories  [factory-data]}

        state-without-factories (-> empty-state
                                    (peers/ensure-peer-with-id source-1 peer-id-1 identity-1 nil nil)
                                    (first)
                                    (core-state/join-domain peer-id-1 :activity-domain nil)
                                    (peers/ensure-peer-with-id source-2 peer-id-2 identity-2 nil nil)
                                    (first)
                                    (core-state/join-domain peer-id-2 :activity-domain nil))

        state-with-factories (-> state-without-factories
                                 (factories/add source-1 request)
                                 (first))]
    (testing "explicit factory removal gets broadcast"
      (let [[state-factory-removed messages] (-> state-with-factories
                                                 (factories/remove-factories source-1 {:request_id  1
                                                                                       :peer_id     peer-id-1
                                                                                       :factory_ids [factory-id]}))]
        (spec-valid? ::ss/state state-factory-removed)
        (is (= state-without-factories state-factory-removed))
        (is (= messages [
                         {:body     {:domain      constants/activity-domain-uri
                                     :factory_ids [factory-id]
                                     :owner_id    peer-id-1
                                     :peer_id     peer-id-2
                                     :type        :peer-factories-removed}
                          :receiver source-2}
                         {:body     {:domain     constants/activity-domain-uri
                                     :peer_id    peer-id-1
                                     :request_id 1
                                     :type       :success}
                          :receiver source-1}]))))
    (testing "factory owner leaving broadcasts factory removed messages"
      (let [[state-factory-removed messages] (-> state-with-factories
                                                 (activity/source-removed source-1 nil))]
        (spec-valid? ::ss/state state-factory-removed)
        (is (just? messages [{:body     {:domain      constants/activity-domain-uri
                                         :factory_ids [factory-id]
                                         :owner_id    peer-id-1
                                         :peer_id     peer-id-2
                                         :type        :peer-factories-removed}
                              :receiver source-2}
                             (msg/peer-removed source-2
                                               peer-id-2
                                               peer-id-1
                                               constants/reason-peer-removed)]))))))

(deftest peer-creation-valid-type
  (let [source (ch->src "source")

        peer-id-1 (peer-id!)
        identity-1 (gen-identity)
        factory-id 1
        peer-type "my-peer-type"
        configuration {:config-key "config-value"}
        flags {:flag-key "flag-value"}
        factory-data {:id            factory-id
                      :peer_type     peer-type
                      :configuration configuration
                      :flags         flags}
        arguments {:tick 42}
        create-r {:request_id    (request-id!)
                  :peer_id       peer-id-1
                  :peer_type     peer-type
                  :configuration arguments}

        [state messages] (-> (new-state)
                             (peers/ensure-peer-with-id source peer-id-1 identity-1 nil nil)
                             (first)
                             (core-state/join-domain peer-id-1 :activity-domain nil)
                             (factories/add source {:request_id (request-id!)
                                                    :peer_id    peer-id-1
                                                    :factories  [factory-data]})
                             (first)
                             (factories/create source create-r))
        gw-token-str (get-in (first messages) [:body :gateway_token])
        gw-r-id (get-in (first messages) [:body :request_id])

        gw-token (tokens/->token state gw-token-str)]

    (spec-valid? ::ss/state state)
    (is (= (:gw-request gw-token) (core-state/gateway-request state gw-r-id)))
    (is (= (dissoc gw-token :exp)
           {:type             :gw-request
            :impersonate-peer (->> (:peer_id create-r)
                                   (peers/by-id state)
                                   :identity)
            :gw-request       (core-state/gateway-request state gw-r-id)}))
    (is (= (dissoc state :ids :signature-key)
           {:domains          {:activity-domain #{peer-id-1}}
            :factories        {peer-type [peer-id-1]}
            :gateway-requests {gw-r-id {:client-request (select-keys create-r [:request_id :peer_id])
                                        :peer_type      peer-type
                                        :peer_name      peer-type
                                        :type           :create-peer
                                        :id             gw-r-id}}
            :identities       {identity-1 peer-id-1}
            :peers            {peer-id-1 (peers/map->Peer {:activity-domain {:factories {peer-type factory-data}}
                                                           :id              peer-id-1
                                                           :identity        identity-1
                                                           :source          source})}
            :users            {:no-user #{peer-id-1}}}))
    (is (= messages [(msg/peer-requested source
                                         gw-r-id
                                         peer-id-1
                                         factory-id
                                         gw-token-str
                                         (merge configuration arguments)
                                         {:peer_name peer-type})

                     (m/success constants/activity-domain-uri
                                source
                                (:request_id create-r)
                                peer-id-1)]))))

(deftest peer-creation-missing-type
  (let [source (ch->src "source")
        peer-id-1 (peer-id!)
        identity-1 (gen-identity)
        factory-id 1
        peer-type "my-peer-type"
        missing-peer-type "missing-peer-type"
        configuration {:config-key "config-value"}
        flags {:flag-key "flag-value"}
        factory-data {:id            factory-id
                      :peer_type     peer-type
                      :configuration configuration
                      :flags         flags}
        arguments {:tick 42}
        create-r {:request_id (request-id!)
                  :peer_id    peer-id-1
                  :peer_type  missing-peer-type
                  :arguments  arguments}]

    (is (thrown? #?(:clj  Exception
                    :cljs js/Error)
                 (-> (new-state)
                     (peers/ensure-peer-with-id source peer-id-1 identity-1 nil nil)
                     (first)
                     (core-state/join-domain peer-id-1 :activity-domain nil)
                     (factories/add source {:request_id (request-id!)
                                            :peer_id    peer-id-1
                                            :factories  [factory-data]})
                     (first)
                     (factories/create source create-r))))))

(deftest created-peer-ready
  (let [source (ch->src "source")
        peer-id-1 (peer-id!)
        identity-1 (gen-identity)
        created-identity (gen-identity)

        factory-id 1
        peer-type "my-peer-type"
        configuration {:config-key "config-value"}
        flags {:flag-key "flag-value"}
        factory-data {:id            factory-id
                      :peer_type     peer-type
                      :configuration configuration
                      :flags         flags}
        arguments {:tick 42}
        create-r {:request_id (request-id!)
                  :peer_id    peer-id-1
                  :peer_type  peer-type
                  :arguments  arguments}

        [state messages] (-> (new-state)
                             (peers/ensure-peer-with-id source peer-id-1 identity-1 nil nil)
                             (first)
                             (core-state/join-domain peer-id-1 :activity-domain nil)
                             (factories/add source {:request_id (request-id!)
                                                    :peer_id    peer-id-1
                                                    :factories  [factory-data]})
                             (first)
                             (factories/create source create-r)
                             )
        gw-token (get-in (first messages) [:body :gateway_token])
        gw-r-id (get-in (first messages) [:body :request_id])
        gw-r (core-state/gateway-request state gw-r-id)

        [created-state messages] (-> state
                                     (global/handle-hello source
                                                          {:type           :hello
                                                           :request_id     1
                                                           :identity       created-identity
                                                           :authentication {"method" "gateway-token" "token" gw-token}}
                                                          nil
                                                          nil
                                                          environment))
        created-id (get-in (first messages) [:body :peer_id])

        joined-state (-> created-state
                         (join source {:request_id (request-id!)
                                       :peer_id    created-id
                                       :identity   created-identity})
                         (first))

        [ready-state messages] (-> joined-state
                                   (ready source {:peer_id created-id}))]
    (testing "When a created peer joins, its tied to the gateway request"
      (is (= (:creation-request (peers/by-id* ready-state created-id :activity-domain))
             gw-r)))

    (testing "It also has a type set (explicit creation name defaults to type)"
      (let [p (peers/by-id* ready-state created-id :activity-domain)]
        (is (= (:peer_type p) "my-peer-type"))
        (is (= (:peer_name p) "my-peer-type"))))

    (testing "Peer is created via a factory, then it joins the activity domain and sends a ready message.
              The expectation is to remove the incoming request and send a peer-created message"
      (is (some? created-id))
      (spec-valid? ::ss/state ready-state)
      (is (= messages [(msg/peer-created source
                                         (:request_id create-r)
                                         peer-id-1
                                         created-id)])))

    ;(testing "sending a second ready throws an error"
    ;  (is (thrown? #?(:clj  Exception
    ;                  :cljs js/Error)
    ;               (-> ready-state
    ;                   (ready source {:peer_id created-id})))))

    ))

(deftest error-on-peer-creation
  (let [source (ch->src "source")
        peer-id-1 (peer-id!)
        identity-1 (gen-identity)
        created-identity (gen-identity)

        factory-id 1
        peer-type "my-peer-type"
        configuration {:config-key "config-value"}
        flags {:flag-key "flag-value"}
        factory-data {:id            factory-id
                      :peer_type     peer-type
                      :configuration configuration
                      :flags         flags}
        arguments {:tick 42}
        create-r {:request_id (request-id!)
                  :peer_id    peer-id-1
                  :peer_type  peer-type
                  :arguments  arguments}

        [state messages] (-> (new-state)
                             (peers/ensure-peer-with-id source peer-id-1 identity-1 nil nil)
                             (first)
                             (core-state/join-domain peer-id-1 :activity-domain nil)
                             (factories/add source {:request_id (request-id!)
                                                    :peer_id    peer-id-1
                                                    :factories  [factory-data]})
                             (first)
                             (factories/create source create-r))
        gw-token (get-in (first messages) [:body :gateway_token])
        gw-r (get-in (first messages) [:body :request_id])
        [failed-state messages] (-> state
                                    (handle-error source {:domain     constants/activity-domain-uri
                                                          :request_id gw-r
                                                          :reason     "poof"}))]
    (is (= messages [(m/error constants/activity-domain-uri
                              source
                              (:request_id create-r)
                              (:peer_id create-r)
                              (reason/reason nil "poof"))]))))