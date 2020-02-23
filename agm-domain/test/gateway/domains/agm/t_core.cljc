(ns gateway.domains.agm.t-core
  (:require
    [clojure.test :refer :all]

    #?(:cljs [gateway.t-macros :refer-macros [spec-valid? just?]])
    #?(:clj [gateway.t-macros :refer [spec-valid? just?]])


    [gateway.domains.agm.t-helpers :refer [create-join
                                           remote-join
                                           remote-register-methods
                                           source-removed]]
    [gateway.domains.agm.core :as core]
    [gateway.domains.agm.subscriptions :as subs]
    [gateway.domains.agm.mthds :as methods]
    [gateway.domains.agm.messages :as msgs]

    [gateway.state.peers :as peers]

    [gateway.t-helpers :refer [local-peer remote-peer gen-identity ch->src peer-id! request-id! new-state node-id]]
    [gateway.state.spec.state :as ss]

    [gateway.domains.agm.constants :as constants]
    [gateway.domains.global.constants :as global]

    [gateway.reason :refer [->Reason request->Reason]]
    [gateway.id-generators :as id]
    [gateway.common.commands :as commands]
    [gateway.common.messages :as m]))

;(stest/instrument)

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

;(deftest message-processing
;  (testing "sending bad messages results in an error message"
;    (let [[state msgs] ((agm-handler nil) nil nil {} [nil [:join]])]
;      (is (error-msg? (first msgs))))))


(deftest handle-remote-source-dropped-test
  (testing "removing a peer from one node removes it from the remote node as well"
    (let [{peer-id-1 :id identity-1 :identity peer-1 :source} (local-peer)
          {peer-id-2 :id identity-2 :identity peer-2 :source} (local-peer)

          rid (request-id!)

          ;; create the first peer (local to node1) and join it to both nodes
          [node1-state messages] (-> (new-state)
                                     (create-join peer-1 {:request_id rid
                                                          :peer_id    peer-id-1
                                                          :identity   identity-1}))
          node2-state (remote-join (new-state) (second messages))

          ;; create the second peer (local to node2) and join it to both nodes
          [node2-state messages] (-> node2-state
                                     (create-join peer-2 {:domain      global/global-domain-uri
                                                          :destination constants/agm-domain-uri
                                                          :request_id  rid
                                                          :peer_id     peer-id-2
                                                          :identity    identity-2}))
          node1-state (remote-join node1-state (last messages))

          remove-rq {:type ::commands/source-removed}
          [node1-state removed-msgs] (source-removed node1-state peer-1 remove-rq)

          broadcast (last removed-msgs)
          [node2-state removed-msgs-2] (source-removed node2-state
                                                       (:source broadcast)
                                                       (:body broadcast))]

      ;; node 1 broadcasts a message to node 2
      (is (= removed-msgs [{:source   {:type    :peer
                                       :peer-id peer-id-1
                                       :node    (id/node-id (:ids node1-state))}
                            :receiver {:type :cluster}
                            :body     remove-rq}]))

      ;; the peer is removed from the state of node 1
      (is (nil? (get-in node1-state [:peers peer-id-1])))
      (is (not (contains? (get-in node1-state [:users :no-user])
                          peer-id-1)))

      ;; node 2 announces the removal to its peers
      (is (= removed-msgs-2 [(msgs/peer-removed peer-2
                                                peer-id-2
                                                peer-id-1
                                                constants/reason-peer-removed)]))

      ;; the peer is removed from the state of node 2 as well
      (is (nil? (get-in node2-state [:peers peer-id-1])))
      (is (not (contains? (get-in node2-state [:users :no-user])
                          peer-id-1))))))


(deftest message-subscription
  (let [rid (request-id!)
        [peer-id-1 peer-id-2 peer-id-3] (repeatedly 3 peer-id!)

        ; first peer
        peer-1 (ch->src "peer-1")
        identity-1 (gen-identity)

        ; second peer
        peer-2 (ch->src "peer-2")
        identity-2 (gen-identity)

        method method-def
        method-id (:id method)

        arg-1 "arg-1"
        arg-2 "arg-2"
        flags {:flag 1}

        ctx {}

        reason {:reason_uri "reason.uri"
                :reason     "reason"}]
    (letfn [(gen-state [] (-> (new-state)
                              (create-join peer-1 {:request_id rid :peer_id peer-id-1 :identity identity-1})
                              (first)
                              (create-join peer-2 {:request_id rid :peer_id peer-id-2 :identity identity-2})
                              (first)
                              (methods/register peer-1 {:domain     constants/agm-domain-uri
                                                        :type       :register
                                                        :request_id rid :peer_id peer-id-1 :methods [method]})
                              (first)))]
      (testing
        "subscribing for a method updates the state and generates an add-interest message"
        (let [[state messages] (-> (gen-state)
                                   (subs/subscribe peer-2 {:request_id rid
                                                           :peer_id    peer-id-2
                                                           :server_id  peer-id-1
                                                           :method_id  method-id
                                                           :arguments  [arg-1 arg-2]
                                                           :flags      flags
                                                           :context    ctx}))
              sub-id (get-in (first messages) [:body :subscription_id])]
          (spec-valid? ::ss/state state)
          (is (= (dissoc state :ids :signature-key)
                 {:domains    {:agm-domain #{peer-id-1 peer-id-2}}
                  :identities {identity-1 peer-id-1
                               identity-2 peer-id-2}
                  :peers      {peer-id-1 (peers/map->Peer {:id         peer-id-1
                                                           :identity   identity-1
                                                           :source     peer-1
                                                           :agm-domain {
                                                                        :interests {sub-id {:method_id  method-id
                                                                                            :subscriber peer-id-2}}
                                                                        :methods   {peer-id-1 {method-id method-def}}}
                                                           })
                               peer-id-2 (peers/map->Peer {:id         peer-id-2
                                                           :identity   identity-2
                                                           :source     peer-2
                                                           :agm-domain {
                                                                        :subscriptions {sub-id {:method_id  method-id
                                                                                                :request_id rid
                                                                                                :server     peer-id-1}}
                                                                        :methods       {peer-id-1 {method-id method-def}}}})}
                  :users      {:no-user #{peer-id-1 peer-id-2}}}))
          (is (= messages
                 [(msgs/add-interest peer-1 sub-id peer-id-1 method-id peer-id-2 [arg-1 arg-2] nil flags ctx)]))))

      (testing
        "accepting the subscription adds the stream and generates a subscribed message"
        (let [
              stream-1 "1"
              [state messages] (-> (gen-state)
                                   (subs/subscribe peer-2 {:request_id rid
                                                           :peer_id    peer-id-2
                                                           :server_id  peer-id-1
                                                           :method_id  method-id
                                                           :arguments  [arg-1 arg-2]
                                                           :flags      flags
                                                           :context    ctx}))
              sub-id (get-in (first messages) [:body :subscription_id])
              [state messages] (-> state
                                   (subs/accepted peer-1 {:subscription_id sub-id :peer_id peer-id-1 :stream_id stream-1}))]
          (spec-valid? ::ss/state state)
          (is (= (dissoc state :ids :signature-key)
                 {:domains    {:agm-domain #{peer-id-1 peer-id-2}}
                  :identities {identity-1 peer-id-1
                               identity-2 peer-id-2}
                  :peers      {peer-id-1 (peers/map->Peer {:id         peer-id-1
                                                           :identity   identity-1
                                                           :source     peer-1
                                                           :agm-domain {
                                                                        :interests {sub-id {:method_id  method-id
                                                                                            :subscriber peer-id-2
                                                                                            :stream     stream-1}}
                                                                        :methods   {peer-id-1 {method-id method-def}}
                                                                        :streams   {stream-1 {peer-id-2 #{sub-id}}}}
                                                           })
                               peer-id-2 (peers/map->Peer {:id         peer-id-2
                                                           :identity   identity-2
                                                           :source     peer-2
                                                           :agm-domain {
                                                                        :subscriptions {sub-id {:method_id  method-id
                                                                                                :request_id rid
                                                                                                :server     peer-id-1
                                                                                                :stream     stream-1}}
                                                                        :methods       {peer-id-1 {method-id method-def}}}})}
                  :users      {:no-user #{peer-id-1 peer-id-2}}}))
          (is (= messages
                 [(msgs/subscribed peer-2 rid peer-id-2 sub-id)]))))

      (testing
        "rejecting the subscription cleans up the state and generates an error message"
        (let [
              [state messages] (-> (gen-state)
                                   (subs/subscribe peer-2 {:request_id rid
                                                           :peer_id    peer-id-2
                                                           :server_id  peer-id-1
                                                           :method_id  method-id
                                                           :arguments  [arg-1 arg-2]
                                                           :flags      flags
                                                           :context    ctx}))
              sub-id (get-in (first messages) [:body :subscription_id])
              [state messages] (-> state
                                   (core/handle-error peer-1 (merge {:domain     constants/agm-domain-uri
                                                                     :type       :error
                                                                     :request_id sub-id :peer_id peer-id-1} reason)))]
          (spec-valid? ::ss/state state)
          (is (= (dissoc state :ids :signature-key)
                 {:domains    {:agm-domain #{peer-id-1 peer-id-2}}
                  :identities {identity-1 peer-id-1
                               identity-2 peer-id-2}
                  :peers      {peer-id-1 (peers/map->Peer {:id         peer-id-1
                                                           :identity   identity-1
                                                           :source     peer-1
                                                           :agm-domain {
                                                                        :methods {peer-id-1 {method-id method-def}}}
                                                           })
                               peer-id-2 (peers/map->Peer {:id         peer-id-2
                                                           :identity   identity-2
                                                           :source     peer-2
                                                           :agm-domain {
                                                                        :methods {peer-id-1 {method-id method-def}}}})}
                  :users      {:no-user #{peer-id-1 peer-id-2}}}))
          (is (= messages
                 [(msgs/error peer-2
                              rid
                              peer-id-2
                              (request->Reason reason))]))))

      (testing
        "removing a subscriber clears the state and generates a remove-interest message"
        (let [
              [state messages] (-> (gen-state)
                                   (subs/subscribe peer-2 {:request_id rid
                                                           :peer_id    peer-id-2
                                                           :server_id  peer-id-1
                                                           :method_id  method-id
                                                           :arguments  [arg-1 arg-2]
                                                           :flags      flags
                                                           :context    ctx}))
              sub-id (get-in (first messages) [:body :subscription_id])
              [state messages] (-> state
                                   (core/remove-peer (peers/by-id state peer-id-2)
                                                     reason))]
          (spec-valid? ::ss/state state)
          (is (= (dissoc state :ids :signature-key)
                 {:domains    {:agm-domain #{peer-id-1}}
                  :identities {identity-1 peer-id-1
                               identity-2 peer-id-2}
                  :peers      {peer-id-1 (peers/map->Peer {:id         peer-id-1
                                                           :identity   identity-1
                                                           :agm-domain {
                                                                        :methods {peer-id-1 {method-id method-def}}}
                                                           :source     peer-1})
                               peer-id-2 (peers/map->Peer {:id       peer-id-2
                                                           :identity identity-2
                                                           :source   peer-2})}
                  :users      {:no-user #{peer-id-1 peer-id-2}}}))
          (is (= messages
                 [(msgs/remove-interest peer-1 sub-id peer-id-1 method-id peer-id-2 reason)
                  (msgs/peer-removed peer-1 peer-id-1 peer-id-2 reason)]))))

      (testing
        "unsubscribe clears the state and generates a remove-interest message"
        (let [
              [state messages] (-> (gen-state)
                                   (subs/subscribe peer-2 {:request_id rid
                                                           :peer_id    peer-id-2
                                                           :server_id  peer-id-1
                                                           :method_id  method-id
                                                           :arguments  [arg-1 arg-2]
                                                           :flags      flags
                                                           :context    ctx}))
              sub-id (get-in (first messages) [:body :subscription_id])
              [_ messages] (-> state
                               (subs/unsubscribe peer-2
                                                 (merge {:request_id rid :peer_id peer-id-2 :subscription_id sub-id}
                                                        reason)))]
          (is (= messages
                 [(msgs/remove-interest peer-1
                                        sub-id
                                        peer-id-1
                                        method-id
                                        peer-id-2
                                        (request->Reason reason))
                  (msgs/success peer-2
                                rid
                                peer-id-2)]))))

      (testing
        "publishing a message to a stream generates an event for all subscribers"
        (let [
              ; third peer
              peer-3 (ch->src "peer-3")
              identity-3 {}
              stream-1 "1"
              snapshot? true
              data {:data "payload"}
              seq 1

              [state messages] (-> (gen-state)
                                   (create-join peer-3 {:domain      global/global-domain-uri
                                                        :destination constants/agm-domain-uri
                                                        :request_id  rid :peer_id peer-id-3 :identity identity-3})
                                   (first)
                                   (subs/subscribe peer-2 {:request_id rid
                                                           :peer_id    peer-id-2
                                                           :server_id  peer-id-1
                                                           :method_id  method-id
                                                           :arguments  [arg-1 arg-2]
                                                           :flags      flags
                                                           :context    ctx}))
              sub-id-1 (get-in (first messages) [:body :subscription_id])

              [state messages] (-> state
                                   (subs/accepted peer-1 {:subscription_id sub-id-1 :peer_id peer-id-1 :stream_id stream-1})
                                   (first)
                                   (subs/subscribe peer-3 {:request_id rid
                                                           :peer_id    peer-id-3
                                                           :server_id  peer-id-1
                                                           :method_id  method-id
                                                           :arguments  [arg-1 arg-2]
                                                           :flags      flags
                                                           :context    ctx}))
              sub-id-2 (get-in (first messages) [:body :subscription_id])

              messages (-> state
                           (subs/accepted peer-1 {:subscription_id sub-id-2 :peer_id peer-id-1 :stream_id stream-1})
                           (first)
                           (subs/publish peer-1 {:peer_id peer-id-1 :stream_id stream-1 :sequence seq :snapshot snapshot? :data data})
                           (second)
                           )]
          (just? messages
                 [
                  (msgs/event peer-2 peer-id-2 sub-id-1 false seq snapshot? data)
                  (msgs/event peer-3 peer-id-3 sub-id-2 false seq snapshot? data)])))

      (testing
        "posting a message to a specific subscriber generates an OOB event only for that subscriber"
        (let [
              ; third peer
              peer-3 (ch->src "peer-3")
              identity-3 {}
              stream-1 "1"
              snapshot? true
              data {:data "payload"}
              seq 1

              [state messages] (-> (gen-state)
                                   (create-join peer-3 {:domain      global/global-domain-uri
                                                        :destination constants/agm-domain-uri
                                                        :request_id  rid :peer_id peer-id-3 :identity identity-3})
                                   (first)
                                   (subs/subscribe peer-2 {:request_id rid
                                                           :peer_id    peer-id-2
                                                           :server_id  peer-id-1
                                                           :method_id  method-id
                                                           :arguments  [arg-1 arg-2]
                                                           :flags      flags
                                                           :context    ctx}))
              sub-id-1 (get-in (first messages) [:body :subscription_id])

              [state messages] (-> state
                                   (subs/accepted peer-1 {:subscription_id sub-id-1 :peer_id peer-id-1 :stream_id stream-1})
                                   (first)
                                   (subs/subscribe peer-3 {:request_id rid
                                                           :peer_id    peer-id-3
                                                           :server_id  peer-id-1
                                                           :method_id  method-id
                                                           :arguments  [arg-1 arg-2]
                                                           :flags      flags
                                                           :context    ctx}))
              sub-id-2 (get-in (first messages) [:body :subscription_id])

              messages (-> state
                           (subs/accepted peer-1 {:subscription_id sub-id-2 :peer_id peer-id-1 :stream_id stream-1})
                           (first)
                           (subs/post peer-1 {:peer_id peer-id-1 :subscription_id sub-id-2 :sequence seq :snapshot snapshot? :data data})
                           (second)
                           )]
          (is (= messages
                 [(msgs/event peer-3 peer-id-3 sub-id-2 true seq snapshot? data)]))))

      (testing "removing a server cancels any subscriptions to it"
        (let [
              reason {:reason_uri "reason.uri"
                      :reason     "reason"}
              [state messages] (-> (gen-state)
                                   (subs/subscribe peer-2 {:request_id rid
                                                           :peer_id    peer-id-2
                                                           :server_id  peer-id-1
                                                           :method_id  method-id
                                                           :arguments  [arg-1 arg-2]
                                                           :flags      flags
                                                           :context    ctx}))
              sub-id-1 (get-in (first messages) [:body :subscription_id])
              [state messages] (-> state
                                   (core/remove-peer (peers/by-id state peer-id-1)
                                                     reason))]
          (spec-valid? ::ss/state state)
          (is (= (dissoc state :ids :signature-key)
                 {:domains    {:agm-domain #{peer-id-2}}
                  :identities {identity-1 peer-id-1
                               identity-2 peer-id-2}
                  :peers      {peer-id-1 (peers/map->Peer {:id       peer-id-1
                                                           :identity identity-1
                                                           :source   peer-1})
                               peer-id-2 (peers/map->Peer {:id         peer-id-2
                                                           :identity   identity-2
                                                           :source     peer-2
                                                           :agm-domain {}})}
                  :users      {:no-user #{peer-id-1 peer-id-2}}}))
          (is (= messages
                 [(msgs/subscription-cancelled peer-2 sub-id-1 peer-id-2 constants/reason-peer-removed)
                  (msgs/methods-removed peer-2 peer-id-2 peer-id-1 [method-id])
                  (msgs/peer-removed peer-2 peer-id-2 peer-id-1 reason)]))))

      (testing "removing a method cancels subscriptions to it"
        (let [[state messages] (-> (gen-state)
                                   (subs/subscribe peer-2 {:request_id rid
                                                           :peer_id    peer-id-2
                                                           :server_id  peer-id-1
                                                           :method_id  method-id
                                                           :arguments  [arg-1 arg-2]
                                                           :flags      flags
                                                           :context    ctx}))
              sub-id-1 (get-in (first messages) [:body :subscription_id])
              unregistration-request {:type       :unregister
                                      :domain     "agm"
                                      :request_id rid
                                      :peer_id    peer-id-1
                                      :methods    [method-id]}
              [state messages] (-> state
                                   (methods/unregister peer-1 unregistration-request))

              p1 (peers/by-id state peer-id-1)
              p2 (peers/by-id state peer-id-2)]
          (spec-valid? ::ss/state state)
          (is (empty? (get-in p1 [:agm-domain :interests])))
          (is (empty? (get-in p2 [:agm-domain :subscriptions])))
          (is (= messages
                 [(msgs/subscription-cancelled peer-2 sub-id-1 peer-id-2 constants/reason-method-removed)
                  (msgs/methods-removed peer-2 peer-id-2 peer-id-1 [method-id])
                  (msgs/methods-removed peer-1 peer-id-1 peer-id-1 [method-id])
                  (m/success constants/agm-domain-uri peer-1 rid peer-id-1)
                  (m/broadcast {:type    :peer
                                :peer-id peer-id-1
                                :node    node-id}
                               unregistration-request)]))))

      (testing "the server can forcefully close a subscription"
        (let [
              reason {:reason_uri "reason.uri"
                      :reason     "reason"}
              stream-1 "1"

              [state messages] (-> (gen-state)
                                   (subs/subscribe peer-2 {:request_id rid
                                                           :peer_id    peer-id-2
                                                           :server_id  peer-id-1
                                                           :method_id  method-id
                                                           :arguments  [arg-1 arg-2]
                                                           :flags      flags
                                                           :context    ctx}))
              sub-id-1 (get-in (first messages) [:body :subscription_id])
              [state messages] (-> state
                                   (subs/accepted peer-1 {:subscription_id sub-id-1 :peer_id peer-id-1 :stream_id stream-1})
                                   (first)
                                   (subs/drop-interest peer-1 (merge {:type    :drop-subscription
                                                                      :peer_id peer-id-1 :subscription_id sub-id-1} reason) false)
                                   )]
          (spec-valid? ::ss/state state)
          (is (= (dissoc state :ids :signature-key)
                 {:domains    {:agm-domain #{peer-id-1 peer-id-2}}
                  :identities {identity-1 peer-id-1
                               identity-2 peer-id-2}
                  :peers      {peer-id-1 (peers/map->Peer {:id         peer-id-1
                                                           :identity   identity-1
                                                           :agm-domain {
                                                                        :methods {peer-id-1 {method-id method-def}}}
                                                           :source     peer-1})
                               peer-id-2 (peers/map->Peer {:id         peer-id-2
                                                           :identity   identity-2
                                                           :agm-domain {
                                                                        :methods {peer-id-1 {method-id method-def}}}
                                                           :source     peer-2})}
                  :users      {:no-user #{peer-id-1 peer-id-2}}}))
          (is (= messages
                 [(msgs/subscription-cancelled peer-2 sub-id-1 peer-id-2 (request->Reason reason))])))))))


;(deftest broken-join
;  (let [d (core/agm-domain)
;        [state msgs] (domain/handle-message d {} {:source (ch->src "src")
;                                                  :body   {:type         ::domain/join
;                                                           :request_id   1
;                                                           :restrictions nil
;                                                           :destination  constants/agm-domain-uri}})]
;    (is (= [{:body     {:domain     constants/agm-domain-uri
;                        :peer_id    nil
;                        :reason     "Peer id is missing"
;                        :reason_uri constants/failure
;                        :request_id 1
;                        :type       :error}
;             :receiver {:channel "src"
;                        :type    :local}}] msgs))))