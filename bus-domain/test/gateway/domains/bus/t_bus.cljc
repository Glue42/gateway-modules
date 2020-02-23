(ns gateway.domains.bus.t_bus
  (:require
    #?(:cljs [cljs.test :refer-macros [deftest is testing]])
    #?(:cljs [gateway.t-macros :refer-macros [spec-valid? just?]])
    #?(:clj [gateway.t-macros :refer [spec-valid? just?]])
    #?(:clj [clojure.test :refer [deftest is testing]])


    [gateway.t-helpers :refer [gen-identity ch->src peer-id! request-id! new-state]]
    [gateway.state.peers :as peers]
    [gateway.state.core :as state]
    [gateway.state.spec.state :as ss]

    [gateway.domains.bus.core :as bus]
    [gateway.id-generators :as ids]
    [gateway.domains.bus.constants :as constants]))

(deftest bus
  (let [rid (request-id!)
        [peer-id-1 peer-id-2 peer-id-3] (repeatedly 3 peer-id!)

        ; first peer
        source-1 (ch->src "peer-1")
        identity-1 (assoc (gen-identity) :application "app1")

        ; second peer
        source-2 (ch->src "peer-2")
        identity-2 (assoc (gen-identity) :application "app2")

        ; third peer
        source-3 (ch->src "peer-3")
        identity-3 (gen-identity)]

    (letfn [(gen-state [] (-> (new-state)
                              (peers/ensure-peer-with-id source-1 peer-id-1 identity-1 nil nil)
                              (first)
                              (state/join-domain peer-id-1 :bus-domain nil)
                              (peers/ensure-peer-with-id source-2 peer-id-2 identity-2 nil nil)
                              (first)
                              (state/join-domain peer-id-2 :bus-domain nil)
                              (peers/ensure-peer-with-id source-3 peer-id-3 identity-3 nil nil)
                              (first)
                              (state/join-domain peer-id-3 :bus-domain nil)))]
      (testing
        "subscribing for topic updates the state and generates a subscribed message"
        (let [[state messages] (-> (gen-state)
                                   (bus/subscribe source-1 {:request_id  rid
                                                            :peer_id     peer-id-1
                                                            :topic       "test-topic"
                                                            :routing_key "test-routing-key"}))
              sub-id (get-in (first messages) [:body :subscription_id])]
          (spec-valid? ::ss/state state)
          (is (= (dissoc state :ids :signature-key)
                 {:domains    {:bus-domain #{peer-id-1 peer-id-2 peer-id-3}}
                  :identities {identity-1 peer-id-1
                               identity-2 peer-id-2
                               identity-3 peer-id-3}
                  :peers      {peer-id-1 (peers/map->Peer {:id         peer-id-1
                                                           :identity   identity-1
                                                           :source     source-1
                                                           :bus-domain {
                                                                        :subscriptions {sub-id {:routing-key "test-routing-key"
                                                                                                :topic       "test-topic"}}}})
                               peer-id-2 (peers/map->Peer {:id         peer-id-2
                                                           :identity   identity-2
                                                           :source     source-2
                                                           :bus-domain {}})
                               peer-id-3 (peers/map->Peer {:id         peer-id-3
                                                           :identity   identity-3
                                                           :source     source-3
                                                           :bus-domain {}})}
                  :users      {:no-user #{peer-id-1 peer-id-2 peer-id-3}}}))
          (is (= messages [{:body     {:domain          constants/bus-domain-uri
                                       :peer_id         peer-id-1
                                       :request_id      rid
                                       :subscription_id sub-id
                                       :type            :subscribed}
                            :receiver source-1}
                           {:body     {:peer_id         peer-id-1
                                       :request_id      rid
                                       :routing_key     "test-routing-key"
                                       :topic           "test-topic"
                                       :subscription_id sub-id}
                            :receiver {:type :cluster}
                            :source   {:node    (ids/node-id (:ids state))
                                       :peer-id peer-id-1
                                       :type    :peer}}]))))
      (testing "publishing on a matching topic with an empty routing key generates event messages for all subscribers on that topic"
        (let [[state messages] (-> (gen-state)
                                   (bus/subscribe source-1 {:request_id  rid
                                                            :peer_id     peer-id-1
                                                            :topic       "test-topic"
                                                            :routing_key "test-routing-key"}))
              sub-id (get-in (first messages) [:body :subscription_id])

              [state messages] (-> state
                                   (bus/subscribe source-3 {:request_id  rid
                                                            :peer_id     peer-id-3
                                                            :topic       "test-topic"
                                                            :routing_key "test-routing-key-2"}))
              sub-id-3 (get-in (first messages) [:body :subscription_id])

              [state' messages'] (-> state
                                     (bus/publish source-2 {:request_id rid
                                                            :peer_id    peer-id-2
                                                            :topic      "test-topic"
                                                            :data       "data"}))]
          (just? messages' [{:body     {:data               "data"
                                        :domain             constants/bus-domain-uri
                                        :peer_id            peer-id-1
                                        :publisher-identity identity-2
                                        :subscription_id    sub-id
                                        :type               :event}
                             :receiver source-1}
                            {:body     {:data               "data"
                                        :domain             constants/bus-domain-uri
                                        :peer_id            peer-id-3
                                        :publisher-identity identity-2
                                        :subscription_id    sub-id-3
                                        :type               :event}
                             :receiver source-3}])))
      (testing "wildcard subscription with > matches everything after the sign"
        (let [[state messages] (-> (gen-state)
                                   (bus/subscribe source-1 {:request_id  rid
                                                            :peer_id     peer-id-1
                                                            :topic       "test>"
                                                            :routing_key "test-routing-key"}))
              sub-id (get-in (first messages) [:body :subscription_id])

              [state' messages'] (-> state
                                     (bus/publish source-2 {:request_id rid
                                                            :peer_id    peer-id-2
                                                            :topic      "test123"
                                                            :data       "data"}))]
          (just? messages' [{:body     {:data               "data"
                                        :domain             constants/bus-domain-uri
                                        :peer_id            peer-id-1
                                        :publisher-identity identity-2
                                        :subscription_id    sub-id
                                        :type               :event}
                             :receiver source-1}])))
      (testing "wildcard subscription with * matches only a fragment"
        (let [
              ;; this one should match
              [state messages] (-> (gen-state)
                                   (bus/subscribe source-1 {:request_id  rid
                                                            :peer_id     peer-id-1
                                                            :topic       "test.*.test"
                                                            :routing_key "test-routing-key"}))
              sub-id (get-in (first messages) [:body :subscription_id])

              ;; this one shouldnt match
              [state messages] (-> state
                                   (bus/subscribe source-3 {:request_id  rid
                                                            :peer_id     peer-id-3
                                                            :topic       "test.*"
                                                            :routing_key "test-routing-key-2"}))
              sub-id-3 (get-in (first messages) [:body :subscription_id])

              [state' messages'] (-> state
                                     (bus/publish source-2 {:request_id rid
                                                            :peer_id    peer-id-2
                                                            :topic      "test.1.test"
                                                            :data       "data"}))]
          (just? messages' [{:body     {:data               "data"
                                        :domain             constants/bus-domain-uri
                                        :peer_id            peer-id-1
                                        :publisher-identity identity-2
                                        :subscription_id    sub-id
                                        :type               :event}
                             :receiver source-1}])))
      (testing "publishing on a matching topic with an concrete routing key generates event messages for matching subscribers"
        (let [[state messages] (-> (gen-state)
                                   (bus/subscribe source-1 {:request_id  rid
                                                            :peer_id     peer-id-1
                                                            :topic       "test-topic"
                                                            :routing_key "test-routing-key"}))
              sub-id (get-in (first messages) [:body :subscription_id])

              [state messages] (-> state
                                   (bus/subscribe source-3 {:request_id  rid
                                                            :peer_id     peer-id-3
                                                            :topic       "test-topic"
                                                            :routing_key "test-routing-key-2"}))
              sub-id-3 (get-in (first messages) [:body :subscription_id])

              [state' messages'] (-> state
                                     (bus/publish source-2 {:request_id  rid
                                                            :peer_id     peer-id-2
                                                            :routing_key "test-routing-key"
                                                            :topic       "test-topic"
                                                            :data        "data"}))]
          (just? messages' [{:body     {:data               "data"
                                        :domain             constants/bus-domain-uri
                                        :peer_id            peer-id-1
                                        :publisher-identity identity-2
                                        :subscription_id    sub-id
                                        :type               :event}
                             :receiver source-1}])))
      (testing "publishing with specific target properly matches the identities"
        (let [[state messages] (-> (gen-state)
                                   (bus/subscribe source-1 {:request_id rid
                                                            :peer_id    peer-id-1
                                                            :topic      "test-topic"}))
              sub-id (get-in (first messages) [:body :subscription_id])

              [state messages] (-> state
                                   (bus/subscribe source-3 {:request_id rid
                                                            :peer_id    peer-id-3
                                                            :topic      "test-topic"}))
              sub-id-3 (get-in (first messages) [:body :subscription_id])

              [state' messages'] (-> state
                                     (bus/publish source-2 {:request_id      rid
                                                            :peer_id         peer-id-2
                                                            :topic           "test-topic"
                                                            :data            "data"
                                                            :target_identity {:application "app1"}}))]
          (just? messages' [{:body     {:data               "data"
                                        :domain             constants/bus-domain-uri
                                        :peer_id            peer-id-1
                                        :publisher-identity identity-2
                                        :subscription_id    sub-id
                                        :type               :event}
                             :receiver source-1}])))
      (testing "publishing on a non-matching topic produces no events"
        (let [[state messages] (-> (gen-state)
                                   (bus/subscribe source-1 {:request_id  rid
                                                            :peer_id     peer-id-1
                                                            :topic       "test-topic"
                                                            :routing_key "test-routing-key"}))
              sub-id (get-in (first messages) [:body :subscription_id])
              [state' messages'] (-> state
                                     (bus/publish source-2 {:request_id rid
                                                            :peer_id    peer-id-2
                                                            :topic      "test-topic-2"
                                                            :data       "data"}))]

          (is (empty? messages'))))
      (testing "publishing on a matching topic with non-matching routing key produces no events"
        (let [[state messages] (-> (gen-state)
                                   (bus/subscribe source-1 {:request_id  rid
                                                            :peer_id     peer-id-1
                                                            :topic       "test-topic"
                                                            :routing_key "test-routing-key"}))
              sub-id (get-in (first messages) [:body :subscription_id])
              [state' messages'] (-> state
                                     (bus/publish source-2 {:request_id  rid
                                                            :peer_id     peer-id-2
                                                            :topic       "test-topic"
                                                            :routing_key "bad-routing-key"
                                                            :data        "data"}))]

          (is (empty? messages'))))
      (testing "publishing an empty message produces no events"
        (let [[state messages] (-> (gen-state)
                                   (bus/subscribe source-1 {:request_id  rid
                                                            :peer_id     peer-id-1
                                                            :topic       "test-topic"
                                                            :routing_key "test-routing-key"}))
              sub-id (get-in (first messages) [:body :subscription_id])
              [state' messages'] (-> state
                                     (bus/publish source-2 {:request_id rid
                                                            :peer_id    peer-id-2
                                                            :topic      "test-topic"}))]

          (is (empty? messages'))))
      (testing "a publisher can also be a subscriber and receive its own events"
        (let [[state messages] (-> (gen-state)
                                   (bus/subscribe source-1 {:request_id rid
                                                            :peer_id    peer-id-1
                                                            :topic      "test-topic"}))
              sub-id (get-in (first messages) [:body :subscription_id])
              [state' messages'] (-> state
                                     (bus/publish source-1 {:request_id rid
                                                            :peer_id    peer-id-1
                                                            :topic      "test-topic"
                                                            :data       "data"}))]

          (just? messages' [{:body     {:data               "data"
                                        :domain             constants/bus-domain-uri
                                        :peer_id            peer-id-1
                                        :publisher-identity identity-1
                                        :subscription_id    sub-id
                                        :type               :event}
                             :receiver source-1}])))
      (testing "subscribe unsubscribe subscribe"
        (let [[state messages] (-> (gen-state)
                                   (bus/subscribe source-1 {:request_id rid
                                                            :peer_id    peer-id-1
                                                            :topic      "test-topic"}))
              sub-id (get-in (first messages) [:body :subscription_id])
              [state' messages'] (-> state
                                     (bus/unsubscribe source-1 {:request_id      rid
                                                                :peer_id         peer-id-1
                                                                :subscription_id sub-id})
                                     (first)
                                     (bus/subscribe source-1 {:request_id rid
                                                              :peer_id    peer-id-1
                                                              :topic      "test-topic"}))
              sub-id' (get-in (first messages') [:body :subscription_id])]

          (is (= [{:body     {:domain          constants/bus-domain-uri
                              :peer_id         peer-id-1
                              :request_id      rid
                              :subscription_id sub-id'
                              :type            :subscribed}
                   :receiver source-1}
                  {:body     {:peer_id         peer-id-1
                              :request_id      rid
                              :topic           "test-topic"
                              :subscription_id sub-id'}
                   :receiver {:type :cluster}
                   :source   {:node    (ids/node-id (:ids state))
                              :peer-id peer-id-1
                              :type    :peer}}]
                 messages')))))))