(ns gateway.domains.agm.t-subscriptions
  (:require
    [clojure.test :refer :all]

    #?(:cljs [gateway.t-macros :refer-macros [spec-valid? just? error-msg?]])
    #?(:clj [gateway.t-macros :refer [spec-valid? just? error-msg?]])
    [gateway.domains.agm.t-helpers :refer [create-join
                                           remote-join
                                           remote-register-methods
                                           source-removed]]

    [gateway.domains.agm.core :as core]
    [gateway.domains.agm.subscriptions :as subs]
    [gateway.domains.agm.mthds :as methods]
    [gateway.t-helpers :refer [local-peer remote-peer gen-identity ch->src peer-id! request-id! new-state node-id]]
    [gateway.common.messages :as m]
    [gateway.reason :refer [->Reason request->Reason]]
    [gateway.domains.agm.messages :as msg]
    [gateway.common.utilities :refer [dissoc-in]]
    [gateway.domains.agm.constants :as constants]
    [gateway.state.spec.state :as ss]
    [gateway.id-generators :as ids]
    [gateway.common.commands :as commands]))

(def ^:private method-def
  {
   :id               1
   :name             "method-name"
   :display_name     "display-name"
   :flags            {:flag 1}
   :version          2
   :input_signature  "input-signature"
   :result_signature "result-signature"
   :description      "description"
   :object_types     {:type 2}})

(defn accept
  [accepted-rq server-source subscriber-node-state server-node-state]

  (let [[server-node-state accepted-msgs] (subs/accepted server-node-state
                                                         server-source
                                                         accepted-rq)

        accepted-msg (first accepted-msgs)
        [subscriber-node-state subscribed-msgs] (subs/accepted subscriber-node-state
                                                               (:source accepted-msg)
                                                               (:body accepted-msg))]
    {:subscriber-node-state subscriber-node-state
     :server-node-state     server-node-state
     :accepted-msgs         accepted-msgs
     :subscribed-msgs       subscribed-msgs}))


(deftest remote-subscription-test
  (let [{peer-id-1 :id identity-1 :identity peer-1 :source} (local-peer)
        {peer-id-2 :id identity-2 :identity peer-2 :source} (local-peer)

        rid (request-id!)

        sub-node-id (ids/node-id)
        server-node-id (ids/node-id)
        ;; create the first peer (local to node1) and join it to both nodes
        [subscriber-node-state messages] (-> (new-state sub-node-id)
                                             (create-join peer-1 {:request_id rid :peer_id peer-id-1 :identity identity-1}))
        server-node-state (remote-join (new-state server-node-id) (second messages))

        ;; create the second peer (local to node2) and join it to both nodes
        [server-node-state messages] (-> server-node-state
                                         (create-join peer-2 {:request_id rid :peer_id peer-id-2 :identity identity-2}))
        subscriber-node-state (remote-join subscriber-node-state (last messages))

        ;; register a method on node 2
        registration-request {:domain     constants/agm-domain-uri
                              :type       :register
                              :request_id rid :peer_id peer-id-2 :methods [method-def]}
        [server-node-state messages] (-> server-node-state
                                         (methods/register peer-2 registration-request))

        ;; subscribe for the method
        method-id (:id method-def)
        arg-1 "arg-1"
        arg-2 "arg-2"
        ctx {}
        sub-rq {:domain     constants/agm-domain-uri
                :type       :subscribe
                :request_id rid
                :peer_id    peer-id-1
                :server_id  peer-id-2
                :method_id  method-id
                :arguments  [arg-1 arg-2]
                :flags      {:flag 42}
                :context    ctx}

        [subscriber-node-state sub-msgs] (-> subscriber-node-state
                                             (remote-register-methods (last messages))
                                             (subs/subscribe peer-1 sub-rq))

        ;; apply the broadcasted call message to node 2
        sub-msg (first sub-msgs)
        [server-node-state interest-msgs] (-> server-node-state
                                              (subs/subscribe (:source sub-msg)
                                                              (:body sub-msg)))

        sub-id-1 (get-in (first interest-msgs) [:body :subscription_id])]

    (testing "the subscription sends the request to the remote node"
      (is (= 1 (count sub-msgs)))
      ;; with a non-null subscription id injected in it
      (is (some? sub-id-1))
      (is (= sub-msg
             {:body     (assoc sub-rq :subscription_id sub-id-1)
              :source   {:type    :peer
                         :peer-id peer-id-1
                         :node    sub-node-id}
              :receiver {:type :node
                         :node server-node-id}})))

    (testing "a remote node can accept a remote subscription"
      (let [stream-1 "1"
            accepted-rq {:domain          constants/agm-domain-uri
                         :type            :accepted
                         :subscription_id sub-id-1
                         :peer_id         peer-id-2
                         :stream_id       stream-1}

            {:keys [subscriber-node-state
                    server-node-state
                    accepted-msgs
                    subscribed-msgs]} (accept accepted-rq peer-2 subscriber-node-state server-node-state)]

        ;; when the subscription is accepted a message is sent to the requesting node
        (is (= accepted-msgs [{:body     accepted-rq
                               :source   {:type    :peer
                                          :peer-id peer-id-2
                                          :node    server-node-id}
                               :receiver {:type :node
                                          :node sub-node-id}}]))

        ;; the subscription and interest are in the node 1 state
        (is (= (get-in subscriber-node-state [:peers peer-id-1 :agm-domain :subscriptions sub-id-1])
               {:method_id  method-id
                :request_id rid
                :server     peer-id-2
                :stream     stream-1}))
        (is (= (get-in subscriber-node-state [:peers peer-id-2 :agm-domain :interests sub-id-1])
               {:method_id  method-id
                :stream     stream-1
                :subscriber peer-id-1}))

        ;; and node 2 state as well
        (is (= (get-in server-node-state [:peers peer-id-1 :agm-domain :subscriptions sub-id-1])
               {:method_id  method-id
                :request_id rid
                :server     peer-id-2
                :stream     stream-1}))
        (is (= (get-in server-node-state [:peers peer-id-2 :agm-domain :interests sub-id-1])
               {:method_id  method-id
                :stream     stream-1
                :subscriber peer-id-1}))

        ;; and subscribed message is sent to the caller
        (is (= subscribed-msgs
               [(msg/subscribed peer-1 rid peer-id-1 sub-id-1)]))))

    (testing "rejecting the subscription on the remote side sends an error to the caller side"
      (let [reason-uri "because-uri"
            reason "because"
            reject-rq {:domain     constants/agm-domain-uri
                       :type       :error
                       :request_id sub-id-1
                       :peer_id    peer-id-2
                       :reason_uri reason-uri
                       :reason     reason}
            [server-node-state error-msgs] (-> server-node-state
                                               (core/handle-error peer-2
                                                                  reject-rq))

            error-msg (first error-msgs)
            [subscriber-node-state rejected-msgs] (-> subscriber-node-state
                                                      (core/handle-error (:source error-msg)
                                                                         (:body error-msg)))]

        ;; when the subscription is accepted a message is sent to the requesting node
        (is (= error-msgs [{:body     reject-rq
                            :source   {:type :peer :peer-id peer-id-2 :node server-node-id}
                            :receiver {:type :node :node sub-node-id}}]))


        ;; the subscription and interest are removed from node 1
        (is (nil? (get-in subscriber-node-state [:peers peer-id-1 :agm-domain :subscriptions sub-id-1])))
        (is (nil? (get-in subscriber-node-state [:peers peer-id-2 :agm-domain :interests sub-id-1])))

        ;; and node 2
        (is (nil? (get-in server-node-state [:peers peer-id-1 :agm-domain :subscriptions sub-id-1])))
        (is (nil? (get-in server-node-state [:peers peer-id-2 :agm-domain :interests sub-id-1])))

        ;; and an error message is sent to the caller
        (is (= rejected-msgs
               [(m/error constants/agm-domain-uri
                         peer-1
                         rid
                         peer-id-1
                         (request->Reason reject-rq))]))))

    (testing "attempt to drop a subscription before its established results in an error"
      (let [drop-rq {:type            :drop-subscription
                     :peer_id         peer-id-2
                     :subscription_id sub-id-1
                     :reason_uri      "because-uri"
                     :reason          "because"}

            [server-node-state drop-msgs] (subs/drop-interest server-node-state peer-2 drop-rq false)]

        (spec-valid? ::ss/state subscriber-node-state)
        (spec-valid? ::ss/state server-node-state)

        (is (= drop-msgs [(msg/error peer-2
                                     sub-id-1
                                     peer-id-2
                                     constants/reason-bad-drop
                                     nil)]))))

    (testing "dropping the subscription on the remote server node results in a cancellation on the caller node"
      (let [{:keys [subscriber-node-state server-node-state]} (accept {:domain          constants/agm-domain-uri
                                                                       :type            :accepted
                                                                       :subscription_id sub-id-1
                                                                       :peer_id         peer-id-2
                                                                       :stream_id       "1"}
                                                                      peer-2
                                                                      subscriber-node-state
                                                                      server-node-state)

            drop-rq {:domain          constants/agm-domain-uri
                     :type            :drop-subscription
                     :peer_id         peer-id-2
                     :subscription_id sub-id-1
                     :reason_uri      "because-uri"
                     :reason          "because"}

            [server-node-state drop-msgs] (subs/drop-interest server-node-state peer-2 drop-rq false)

            drop-msg (first drop-msgs)
            [subscriber-node-state event-msgs] (subs/drop-interest subscriber-node-state (:source drop-msg)
                                                                   (:body drop-msg)
                                                                   false)]

        (spec-valid? ::ss/state subscriber-node-state)
        (spec-valid? ::ss/state server-node-state)

        (is (= drop-msgs [{:body     drop-rq
                           :receiver {:type :node :node sub-node-id}
                           :source   {:type :peer :peer-id peer-id-2 :node server-node-id}}]))

        ;; the subscription and interest are removed from node 1
        (is (nil? (get-in subscriber-node-state [:peers peer-id-1 :agm-domain :subscriptions sub-id-1])))
        (is (nil? (get-in subscriber-node-state [:peers peer-id-2 :agm-domain :interests sub-id-1])))

        ;; and node 2
        (is (nil? (get-in server-node-state [:peers peer-id-1 :agm-domain :subscriptions sub-id-1])))
        (is (nil? (get-in server-node-state [:peers peer-id-2 :agm-domain :interests sub-id-1])))

        ;; subscriber on node 1 receives a cancellation message
        (is (= event-msgs [(msg/subscription-cancelled peer-1
                                                       sub-id-1
                                                       peer-id-1
                                                       (request->Reason drop-rq))]))))

    (testing "dropping subscriber results in a cancellation message on the remote server side"
      (let [{:keys [subscriber-node-state server-node-state]} (accept {:domain          constants/agm-domain-uri
                                                                       :type            :accepted
                                                                       :subscription_id sub-id-1
                                                                       :peer_id         peer-id-2
                                                                       :stream_id       "1"}
                                                                      peer-2
                                                                      subscriber-node-state
                                                                      server-node-state)

            remove-rq {:type ::commands/source-removed}
            [subscriber-node-state removed-msgs] (source-removed subscriber-node-state peer-1 remove-rq)

            broadcast (last removed-msgs)
            [server-node-state removed-msgs-2] (source-removed server-node-state
                                                               (:source broadcast)
                                                               (:body broadcast))]
        ;; the subscription and interest are removed from node 1
        (is (nil? (get-in subscriber-node-state [:peers peer-id-1 :agm-domain :subscriptions sub-id-1])))
        (is (nil? (get-in subscriber-node-state [:peers peer-id-2 :agm-domain :interests sub-id-1])))

        ;; and node 2
        (is (nil? (get-in server-node-state [:peers peer-id-1 :agm-domain :subscriptions sub-id-1])))
        (is (nil? (get-in server-node-state [:peers peer-id-2 :agm-domain :interests sub-id-1])))

        ;; server on node 2 receives a remove-interest message
        (is (= removed-msgs-2 [(msg/remove-interest peer-2
                                                    sub-id-1
                                                    peer-id-2
                                                    method-id
                                                    peer-id-1
                                                    constants/reason-peer-removed)
                               (msg/peer-removed peer-2
                                                 peer-id-2
                                                 peer-id-1
                                                 constants/reason-peer-removed)]))))


    (testing "dropping the remote server results in a cancellation message on the caller side"
      (let [{:keys [subscriber-node-state server-node-state]} (accept {:domain          constants/agm-domain-uri
                                                                       :type            :accepted
                                                                       :subscription_id sub-id-1
                                                                       :peer_id         peer-id-2
                                                                       :stream_id       "1"}
                                                                      peer-2
                                                                      subscriber-node-state
                                                                      server-node-state)

            remove-rq {:type ::commands/source-removed}
            [server-node-state removed-msgs] (source-removed server-node-state peer-2 remove-rq)

            broadcast (last removed-msgs)
            [subscriber-node-state removed-msgs-1] (source-removed subscriber-node-state
                                                                   (:source broadcast)
                                                                   (:body broadcast))]
        ;; sanity check
        (spec-valid? ::ss/state subscriber-node-state)
        (spec-valid? ::ss/state server-node-state)

        ;; the subscription and interest are removed from node 1
        (is (nil? (get-in subscriber-node-state [:peers peer-id-1 :agm-domain :subscriptions sub-id-1])))
        (is (nil? (get-in subscriber-node-state [:peers peer-id-2 :agm-domain :interests sub-id-1])))

        ;; and node 2
        (is (nil? (get-in server-node-state [:peers peer-id-1 :agm-domain :subscriptions sub-id-1])))
        (is (nil? (get-in server-node-state [:peers peer-id-2 :agm-domain :interests sub-id-1])))

        ;; subscriber on node 1 receives a cancellation message
        (is (= removed-msgs-1 [(msg/subscription-cancelled peer-1
                                                           sub-id-1
                                                           peer-id-1
                                                           constants/reason-peer-removed)
                               (msg/methods-removed peer-1
                                                    peer-id-1
                                                    peer-id-2
                                                    [method-id])
                               (msg/peer-removed peer-1
                                                 peer-id-1
                                                 peer-id-2
                                                 constants/reason-peer-removed)]))))

    (testing "dropping the server doesnt break the subscription list if there are multiple subscriptions"
      (let [;; accept the first subscription
            {:keys [subscriber-node-state server-node-state]} (accept {:domain          constants/agm-domain-uri
                                                                       :type            :accepted
                                                                       :subscription_id sub-id-1
                                                                       :peer_id         peer-id-2
                                                                       :stream_id       "1"}
                                                                      peer-2
                                                                      subscriber-node-state
                                                                      server-node-state)

            ;; create a third peer and subscribe to it as well

            {peer-id-3 :id identity-3 :identity peer-3 :source} (local-peer)
            [server-node-state messages] (-> server-node-state
                                             (create-join peer-3 {:request_id rid :peer_id peer-id-3 :identity identity-3}))
            subscriber-node-state (remote-join subscriber-node-state (last messages))

            ;; register a method on node 2
            registration-request {:domain     constants/agm-domain-uri
                                  :type       :register
                                  :request_id rid :peer_id peer-id-3 :methods [method-def]}
            [server-node-state messages] (-> server-node-state
                                             (methods/register peer-3 registration-request))

            ;; subscribe for the method
            sub-rq {:domain     constants/agm-domain-uri
                    :type       :subscribe
                    :request_id (request-id!)
                    :peer_id    peer-id-1
                    :server_id  peer-id-3
                    :method_id  method-id
                    :arguments  [arg-1 arg-2]
                    :flags      {:flag 42}
                    :context    ctx}

            [subscriber-node-state sub-2-msgs] (-> subscriber-node-state
                                                   (remote-register-methods (last messages))
                                                   (subs/subscribe peer-1 sub-rq))

            ;; apply the broadcasted call message to node 2
            sub-msg (first sub-2-msgs)
            [server-node-state interest-msgs] (-> server-node-state
                                                  (subs/subscribe (:source sub-msg)
                                                                  (:body sub-msg)))
            sub-id-2 (get-in (first interest-msgs) [:body :subscription_id])

            ;; accept this as well
            {:keys [subscriber-node-state server-node-state]} (accept {:domain          constants/agm-domain-uri
                                                                       :type            :accepted
                                                                       :subscription_id sub-id-2
                                                                       :peer_id         peer-id-3
                                                                       :stream_id       "1"}
                                                                      peer-2
                                                                      subscriber-node-state
                                                                      server-node-state)


            remove-rq {:type ::commands/source-removed}
            [server-node-state removed-msgs] (source-removed server-node-state peer-2 remove-rq)

            broadcast (last removed-msgs)
            [subscriber-node-state removed-msgs-1] (source-removed subscriber-node-state
                                                                   (:source broadcast)
                                                                   (:body broadcast))]
        ;; sanity check
        (spec-valid? ::ss/state subscriber-node-state)
        (spec-valid? ::ss/state server-node-state)))

    (testing "publishing from the remote server node raises an event on the caller node"
      (let [{:keys [subscriber-node-state server-node-state]} (accept {:domain          constants/agm-domain-uri
                                                                       :type            :accepted
                                                                       :subscription_id sub-id-1
                                                                       :peer_id         peer-id-2
                                                                       :stream_id       "1"}
                                                                      peer-2
                                                                      subscriber-node-state
                                                                      server-node-state)

            publish-rq {:domain    constants/agm-domain-uri
                        :type      :publish
                        :peer_id   peer-id-2
                        :stream_id "1"
                        :sequence  1
                        :snapshot  true
                        :data      {:t 42}}

            publish-msgs (-> server-node-state
                             (subs/publish peer-2 publish-rq)
                             (second))

            publish-msg (first publish-msgs)
            event-msgs (-> subscriber-node-state
                           (subs/publish (:source publish-msg)
                                         (:body publish-msg))
                           (second))]

        (is (= publish-msgs [{:body     publish-rq
                              :receiver {:type :node :node sub-node-id}
                              :source   {:type :peer :peer-id peer-id-2 :node server-node-id}}]))

        (is (= event-msgs [(msg/event peer-1
                                      peer-id-1
                                      sub-id-1
                                      false
                                      (:sequence publish-rq)
                                      (:snapshot publish-rq)
                                      (:data publish-rq))]))))

    (testing "publishing from the remote node raises a single event on the caller node
              even if there are multiple subscribers from it"
      (let [{:keys [subscriber-node-state server-node-state]} (accept {:domain          constants/agm-domain-uri
                                                                       :type            :accepted
                                                                       :subscription_id sub-id-1
                                                                       :peer_id         peer-id-2
                                                                       :stream_id       "1"}
                                                                      peer-2
                                                                      subscriber-node-state
                                                                      server-node-state)

            ;; adding the second subscriber
            {peer-id-3 :id identity-3 :identity peer-3 :source} (local-peer)
            [subscriber-node-state messages] (-> subscriber-node-state
                                                 (create-join peer-3 {:request_id rid :peer_id peer-id-3 :identity identity-3}))
            server-node-state (remote-join server-node-state (last messages))

            ;; subscribe for the method
            sub-rq {:domain     constants/agm-domain-uri
                    :type       :subscribe
                    :request_id rid
                    :peer_id    peer-id-3
                    :server_id  peer-id-2
                    :method_id  method-id
                    :arguments  [arg-1 arg-2]
                    :flags      {:flag 42}
                    :context    ctx}

            [subscriber-node-state sub-msgs] (-> subscriber-node-state
                                                 (subs/subscribe peer-1 sub-rq))

            ;; apply the broadcasted call message to node 2
            sub-msg (first sub-msgs)
            [server-node-state interest-msgs] (-> server-node-state
                                                  (subs/subscribe (:source sub-msg)
                                                                  (:body sub-msg)))
            sub-id-2 (get-in (first interest-msgs) [:body :subscription_id])

            {:keys [subscriber-node-state server-node-state]} (accept {:domain          constants/agm-domain-uri
                                                                       :type            :accepted
                                                                       :subscription_id sub-id-2
                                                                       :peer_id         peer-id-2
                                                                       :stream_id       "1"}
                                                                      peer-2
                                                                      subscriber-node-state
                                                                      server-node-state)

            ;; publish an event
            publish-rq {:domain    constants/agm-domain-uri
                        :type      :publish
                        :peer_id   peer-id-2
                        :stream_id "1"
                        :sequence  1
                        :snapshot  true
                        :data      {:t 42}}

            publish-msgs (-> server-node-state
                             (subs/publish peer-2 publish-rq)
                             (second))

            publish-msg (first publish-msgs)
            event-msgs (-> subscriber-node-state
                           (subs/publish (:source publish-msg)
                                         (:body publish-msg))
                           (second))]

        ;; single message is sent to the subscriber node
        (is (= publish-msgs [{:body     publish-rq
                              :receiver {:type :node :node sub-node-id}
                              :source   {:type :peer :peer-id peer-id-2 :node server-node-id}}]))

        ;; which results in two events
        (just? event-msgs [(msg/event peer-1
                                      peer-id-1
                                      sub-id-1
                                      false
                                      (:sequence publish-rq)
                                      (:snapshot publish-rq)
                                      (:data publish-rq))
                           (msg/event peer-3
                                      peer-id-3
                                      sub-id-2
                                      false
                                      (:sequence publish-rq)
                                      (:snapshot publish-rq)
                                      (:data publish-rq))])))

    (testing "posting from the remote node raises an event on the caller node"
      (let [{:keys [subscriber-node-state server-node-state]} (accept {:domain          constants/agm-domain-uri
                                                                       :type            :accepted
                                                                       :subscription_id sub-id-1
                                                                       :peer_id         peer-id-2
                                                                       :stream_id       "1"}
                                                                      peer-2
                                                                      subscriber-node-state
                                                                      server-node-state)
            publish-rq {:domain          constants/agm-domain-uri
                        :type            :post
                        :peer_id         peer-id-2
                        :subscription_id sub-id-1
                        :sequence        1
                        :snapshot        true
                        :data            {:t 42}}

            publish-msgs (-> server-node-state
                             (subs/post peer-2 publish-rq)
                             (second))

            publish-msg (first publish-msgs)
            event-msgs (-> subscriber-node-state
                           (subs/post (:source publish-msg)
                                      (:body publish-msg))
                           (second))]

        (is (= publish-msgs [{:body     publish-rq
                              :receiver {:type :node :node sub-node-id}
                              :source   {:type :peer :peer-id peer-id-2 :node server-node-id}}]))

        (is (= event-msgs [(msg/event peer-1
                                      peer-id-1
                                      sub-id-1
                                      true
                                      (:sequence publish-rq)
                                      (:snapshot publish-rq)
                                      (:data publish-rq))]))))

    ))
