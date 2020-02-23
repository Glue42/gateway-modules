(ns gateway.domains.agm.t-invocation
  (:require
    [clojure.test :refer :all]

    #?(:cljs [gateway.t-macros :refer-macros [spec-valid? just? error-msg?]])
    #?(:clj [gateway.t-macros :refer [spec-valid? just? error-msg?]])
    [gateway.domains.agm.t-helpers :refer [create-join remote-join remote-register-methods]]
    [gateway.domains.global.core :as global]
    [gateway.domains.agm.core :as core]
    [gateway.domains.agm.calls :as calls]
    [gateway.domains.agm.mthds :as methods]
    [gateway.domains.agm.messages :as msg]

    [gateway.state.peers :as peers]

    [gateway.t-helpers :refer [gen-identity ch->src request-id! peer-id! new-state local-peer remote-peer]]
    [gateway.common.messages :as m]
    [gateway.state.spec.state :as ss]
    [gateway.domains.agm.constants :as constants]
    [gateway.reason :refer [reason request->Reason]]
    [gateway.id-generators :as ids]
    [gateway.address :as address]))

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

(deftest local-method-invocation
  (let [
        empty-state (new-state)

        rid (request-id!)
        [peer-id-1 peer-id-2] (repeatedly 2 peer-id!)

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

        ctx {}]
    (letfn [(gen-state [] (-> empty-state
                              (create-join peer-1 {:request_id rid :peer_id peer-id-1 :identity identity-1})
                              (first)
                              (create-join peer-2 {:request_id rid :peer_id peer-id-2 :identity identity-2})
                              (first)
                              (methods/register peer-1 {:domain     constants/agm-domain-uri
                                                        :type       :register
                                                        :request_id rid :peer_id peer-id-1 :methods [method]})
                              (first)))]
      (testing
        "calling a method registers the invocation and generates a matching invoke message"
        (let [[state messages] (-> (gen-state)
                                   (calls/call peer-2 {:request_id rid
                                                       :peer_id    peer-id-2
                                                       :server_id  peer-id-1
                                                       :method_id  method-id
                                                       :arguments  [arg-1 arg-2]
                                                       :context    ctx}))
              inv-id (get-in (first messages) [:body :invocation_id])]
          (spec-valid? ::ss/state state)
          (is (= (dissoc state :ids :signature-key)
                 {:domains    {:agm-domain #{peer-id-1 peer-id-2}}
                  :identities {identity-1 peer-id-1
                               identity-2 peer-id-2}
                  :peers      {
                               peer-id-1 (peers/map->Peer {:id         peer-id-1
                                                           :identity   identity-1
                                                           :source     peer-1
                                                           :agm-domain {
                                                                        :invocations {inv-id {:caller peer-id-2 :method_id method-id :request_id rid}}
                                                                        :methods     {peer-id-1 {method-id method-def}}}})
                               peer-id-2 (peers/map->Peer {:id         peer-id-2
                                                           :identity   identity-2
                                                           :source     peer-2
                                                           :agm-domain {
                                                                        :calls   {rid {:callee peer-id-1 :invocation_id inv-id :method_id method-id}}
                                                                        :methods {peer-id-1 {method-id method-def}}}})}
                  :users      {:no-user #{peer-id-1 peer-id-2}}}))
          (is (= messages
                 [(msg/invoke peer-1 inv-id peer-id-1 method-id peer-id-2 [arg-1 arg-2] nil ctx)]))))
      (testing
        "yielding clears the pending calls/invocations and generates a result message"
        (let [
              results {:a 1 :t 42}
              [state messages] (-> (gen-state)
                                   (calls/call peer-2 {:request_id rid
                                                       :peer_id    peer-id-2
                                                       :server_id  peer-id-1
                                                       :method_id  method-id
                                                       :arguments  [arg-1 arg-2]
                                                       :context    ctx}))
              inv-id (get-in (first messages) [:body :invocation_id])
              [state messages] (-> state
                                   (calls/yield peer-1 {:invocation_id inv-id :peer_id peer-id-1 :result results}))]
          (spec-valid? ::ss/state state)
          (is (= (dissoc state :ids :signature-key)
                 {:domains    {:agm-domain #{peer-id-1 peer-id-2}}
                  :identities {identity-1 peer-id-1
                               identity-2 peer-id-2}
                  :peers      {
                               peer-id-1 (peers/map->Peer {:id         peer-id-1
                                                           :identity   identity-1
                                                           :source     peer-1
                                                           :agm-domain {
                                                                        :methods {peer-id-1 {method-id method-def}}}})
                               peer-id-2 (peers/map->Peer {:id         peer-id-2
                                                           :identity   identity-2
                                                           :source     peer-2
                                                           :agm-domain {
                                                                        :methods {peer-id-1 {method-id method-def}}}})}
                  :users      {:no-user #{peer-id-1 peer-id-2}}}))
          (is (= messages
                 [(msg/result peer-2 rid peer-id-2 results)]))))

      (testing
        "you can call your own method and receive a result"
        (let [results {:a 1 :t 42}
              [call-state call-messages] (-> (gen-state)
                                             (calls/call peer-1 {:request_id rid
                                                                 :peer_id    peer-id-1
                                                                 :server_id  peer-id-1
                                                                 :method_id  method-id
                                                                 :arguments  [arg-1 arg-2]
                                                                 :context    ctx}))
              inv-id (get-in (first call-messages) [:body :invocation_id])

              [yield-state yield-messages] (-> call-state
                                               (calls/yield peer-1 {:invocation_id inv-id :peer_id peer-id-1 :result results}))]
          (spec-valid? ::ss/state yield-state)
          (spec-valid? ::ss/state call-state)
          (is (= (dissoc call-state :ids :signature-key)
                 {:domains    {:agm-domain #{peer-id-1 peer-id-2}}
                  :identities {identity-1 peer-id-1
                               identity-2 peer-id-2}
                  :peers      {peer-id-1 (peers/map->Peer {:id         peer-id-1
                                                           :identity   identity-1
                                                           :source     peer-1
                                                           :agm-domain {
                                                                        :calls       {rid {:callee        peer-id-1
                                                                                           :invocation_id inv-id
                                                                                           :method_id     method-id}}
                                                                        :invocations {inv-id {:caller     peer-id-1
                                                                                              :method_id  method-id
                                                                                              :request_id rid}}
                                                                        :methods     {peer-id-1 {method-id method-def}}}})
                               peer-id-2 (peers/map->Peer {:id         peer-id-2
                                                           :identity   identity-2
                                                           :source     peer-2
                                                           :agm-domain {
                                                                        :methods {peer-id-1 {method-id method-def}}}})}
                  :users      {:no-user #{peer-id-1 peer-id-2}}}))
          (is (= call-messages
                 [(msg/invoke peer-1 inv-id peer-id-1 method-id peer-id-1 [arg-1 arg-2] nil ctx)]))
          (is (= (dissoc yield-state :ids :signature-key)
                 {:domains    {:agm-domain #{peer-id-1 peer-id-2}}
                  :identities {identity-1 peer-id-1
                               identity-2 peer-id-2}
                  :peers      {peer-id-1 (peers/map->Peer {:id         peer-id-1
                                                           :identity   identity-1
                                                           :source     peer-1
                                                           :agm-domain {
                                                                        :methods {peer-id-1 {method-id method-def}}}})
                               peer-id-2 (peers/map->Peer {:id         peer-id-2
                                                           :identity   identity-2
                                                           :source     peer-2
                                                           :agm-domain {
                                                                        :methods {peer-id-1 {method-id method-def}}}})}
                  :users      {:no-user #{peer-id-1 peer-id-2}}}))
          (is (= yield-messages
                 [(msg/result peer-1 rid peer-id-1 results)]))))

      (testing
        "the callee can respond with an error with a context to an invocation"
        (let [[state messages] (-> (gen-state)
                                   (calls/call peer-2 {:request_id rid
                                                       :peer_id    peer-id-2
                                                       :server_id  peer-id-1
                                                       :method_id  method-id
                                                       :arguments  [arg-1 arg-2]
                                                       :context    ctx}))
              inv-id (get-in (first messages) [:body :invocation_id])
              error-resp {:request_id inv-id
                          :peer_id    peer-id-1
                          :reason_uri "reason-uri"
                          :reason     "reason"
                          :context    {:a 1 :t 42}}
              [state messages] (-> state
                                   (core/handle-error peer-1 error-resp))]
          (spec-valid? ::ss/state state)
          (is (= (dissoc state :ids :signature-key)
                 {:domains    {:agm-domain #{peer-id-1 peer-id-2}}
                  :identities {identity-1 peer-id-1
                               identity-2 peer-id-2}
                  :peers      {
                               peer-id-1 (peers/map->Peer {:id         peer-id-1
                                                           :identity   identity-1
                                                           :source     peer-1
                                                           :agm-domain {
                                                                        :methods {peer-id-1 {method-id method-def}}}})
                               peer-id-2 (peers/map->Peer {:id         peer-id-2
                                                           :identity   identity-2
                                                           :source     peer-2
                                                           :agm-domain {
                                                                        :methods {peer-id-1 {method-id method-def}}}})}
                  :users      {:no-user #{peer-id-1 peer-id-2}}}))
          (is (= messages
                 [(msg/error peer-2
                             rid
                             peer-id-2
                             (request->Reason error-resp)
                             (:context error-resp))]))))

      (testing
        "responding with an error to an invocation that is missing still works"
        (let [[state messages] (-> (gen-state)
                                   (calls/call peer-2 {:request_id rid
                                                       :peer_id    peer-id-2
                                                       :server_id  peer-id-1
                                                       :method_id  method-id
                                                       :arguments  [arg-1 arg-2]
                                                       :context    ctx}))
              inv-id (get-in (first messages) [:body :invocation_id])
              error-resp {:request_id inv-id
                          :peer_id    peer-id-1
                          :reason_uri "reason-uri"
                          :reason     "reason"
                          :context    {:a 1 :t 42}}

              [state messages] (-> state
                                   (core/source-removed peer-2 nil)
                                   (first)
                                   (global/source-removed peer-2 nil)
                                   (first)
                                   (core/handle-error peer-1 error-resp))]
          (spec-valid? ::ss/state state)
          (is (= (dissoc state :ids :signature-key)
                 {:domains    {:agm-domain #{peer-id-1}}
                  :identities {identity-1 peer-id-1}
                  :peers      {
                               peer-id-1 (peers/map->Peer {:id         peer-id-1
                                                           :identity   identity-1
                                                           :source     peer-1
                                                           :agm-domain {
                                                                        :methods {peer-id-1 {method-id method-def}}}})}
                  :services   #{}
                  :users      {:no-user #{peer-id-1}}}))
          (is (= messages
                 []))))


      (testing
        "callee leaving before yielding cancels pending calls and generates an error message for the caller"
        (let [state (-> (gen-state)
                        (calls/call peer-2 {:request_id rid
                                            :peer_id    peer-id-2
                                            :server_id  peer-id-1
                                            :method_id  method-id
                                            :arguments  [arg-1 arg-2]
                                            :context    ctx})

                        (first))

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
                 [(msg/error peer-2
                             rid
                             peer-id-2
                             (reason constants/agm-invocation-failure
                                     "Peer has left while waiting for result"))
                  (msg/methods-removed peer-2 peer-id-2 peer-id-1 [method-id])
                  (msg/peer-removed peer-2 peer-id-2 peer-id-1 reason)]))))
      (testing
        "callee leaving doesnt corrupt the caller invocation list when there are more than 1 calls pending"
        (let [peer-3 (ch->src "peer-3")
              identity-3 (gen-identity)
              peer-id-3 (peer-id!)

              call2-id (request-id!)

              state (-> (gen-state)
                        (create-join peer-3 {:request_id rid :peer_id peer-id-3 :identity identity-3})
                        (first)
                        (methods/register peer-3 {:domain     constants/agm-domain-uri
                                                  :type       :register
                                                  :request_id rid :peer_id peer-id-3 :methods [method]})
                        (first)
                        (calls/call peer-2 {:request_id rid
                                            :peer_id    peer-id-2
                                            :server_id  peer-id-1
                                            :method_id  method-id
                                            :arguments  [arg-1 arg-2]
                                            :context    ctx})
                        (first)
                        (calls/call peer-2 {:request_id call2-id
                                            :peer_id    peer-id-2
                                            :server_id  peer-id-3
                                            :method_id  method-id
                                            :arguments  [arg-1 arg-2]
                                            :context    ctx})
                        (first))

              [state messages] (-> state
                                   (core/remove-peer (peers/by-id state peer-id-1) reason))]
          (spec-valid? ::ss/state state)))
      (testing
        "calling a method on a missing server returns an error"
        (let [missing-server-id ""]
          (is (thrown? #?(:clj  Exception
                          :cljs js/Error)
                       (-> (gen-state)
                           (calls/call peer-2 {:request_id rid
                                               :peer_id    peer-id-2
                                               :server_id  missing-server-id
                                               :method_id  method-id
                                               :arguments  [arg-1 arg-2]
                                               :context    ctx}))))))

      (testing
        "calling a missing method on an valid server returns an error"
        (let [missing-method-id (inc method-id)]
          (is (thrown? #?(:clj  Exception
                          :cljs js/Error)
                       (-> (gen-state)
                           (calls/call peer-2 {:request_id rid
                                               :peer_id    peer-id-2
                                               :server_id  peer-id-1
                                               :method_id  missing-method-id
                                               :arguments  [arg-1 arg-2]
                                               :context    ctx}))))))
      )))

(deftest remote-peer-invocation-1
  (testing "a peer on one node can call a peer on another node"
    (let [{peer-id-1 :id identity-1 :identity peer-1 :source} (local-peer)
          {peer-id-2 :id identity-2 :identity peer-2 :source} (local-peer)

          rid (request-id!)

          ;; create the first peer (local to node1) and join it to both nodes
          [node1-state messages] (-> (new-state)
                                     (create-join peer-1 {:request_id rid :peer_id peer-id-1 :identity identity-1}))
          node2-state (remote-join (new-state) (second messages))

          ;; create the second peer (local to node2) and join it to both nodes
          [node2-state messages] (-> node2-state
                                     (create-join peer-2 {:request_id rid :peer_id peer-id-2 :identity identity-2}))
          node1-state (remote-join node1-state (nth messages 2))

          ;; register a method on node 2
          registration-request {:domain     constants/agm-domain-uri
                                :type       :register
                                :request_id rid
                                :peer_id    peer-id-2
                                :methods    [method-def]}
          [node2-state messages] (-> node2-state
                                     (methods/register peer-2 registration-request))

          ;;apply the broadcasted message and invoke the method
          method-id (:id method-def)
          arg-1 "arg-1"
          arg-2 "arg-2"
          ctx {}
          request {:domain     constants/agm-domain-uri
                   :type       :call
                   :request_id rid
                   :peer_id    peer-id-1
                   :server_id  peer-id-2
                   :method_id  method-id
                   :arguments  [arg-1 arg-2]
                   :context    ctx}

          [node1-state call-msgs] (-> node1-state
                                      (remote-register-methods (nth messages 2))
                                      (calls/call peer-1 request))

          ;; apply the broadcasted call message to node 2
          call-msg (first call-msgs)
          invocation-id (get-in call-msg [:body :invocation_id])
          [node2-state invoke-msgs] (-> node2-state
                                        (calls/call (:source call-msg)
                                                    (:body call-msg)))


          ;; peer 2 yields a result on node 2
          results {:a 1 :t 42}
          yield {:domain constants/agm-domain-uri :type :yield :invocation_id invocation-id :peer_id peer-id-2 :result results}
          [node2-state yield-msgs] (-> node2-state
                                       (calls/yield peer-2
                                                    yield))

          ;; and the yield is broadcasted on node 1, which should translate to a result message
          yield-msg (first yield-msgs)
          [node1-state result-msgs] (-> node1-state
                                        (calls/yield (:source yield-msg)
                                                     (:body yield-msg)))
          ]

      (is (= 1 (count call-msgs)))
      (is (= (:receiver call-msg) {:type :node :node (ids/node-id (:ids node2-state))}))
      (is (= (:source call-msg) {:type :peer :peer-id peer-id-1 :node (ids/node-id (:ids node1-state))}))
      (is (= (-> call-msg
                 (:body)
                 (dissoc :invocation_id)) request))

      (is (= invoke-msgs [(msg/invoke peer-2
                                      invocation-id
                                      peer-id-2
                                      method-id
                                      peer-id-1
                                      [arg-1 arg-2]
                                      nil
                                      ctx)]))

      (is (= yield-msgs [(m/unicast (address/peer->address (ids/node-id (:ids node2-state)) peer-id-2)
                                    {:type :node :node (ids/node-id (:ids node1-state))}
                                    yield)]))
      (is (= result-msgs [(msg/result peer-1
                                      rid
                                      peer-id-1
                                      results)]))
      )))



(defn add-peers
  [state source user]
  (let [rid (request-id!)
        [peer-id-1 peer-id-2] (repeatedly 2 peer-id!)

        ; first peer
        identity-1 (gen-identity user)

        ; second peer
        identity-2 (gen-identity user)

        method method-def
        method-id (:id method)

        arg-1 "arg-1"
        arg-2 "arg-2"

        ctx {}]
    (-> state
        (create-join source {:request_id rid :peer_id peer-id-1 :identity identity-1})
        (first)
        (create-join source {:request_id rid :peer_id peer-id-2 :identity identity-2})
        (first)
        (methods/register source {:request_id rid :peer_id peer-id-1 :methods [method]})
        (first)
        )))

;(defn perf
;  []
;  (timbre/info "starting")
;  (let [source (ch->src "source")
;        user "user"
;        state (loop [i 20000 state (new-state)]
;                (if (pos? i)
;                  (recur (dec i) (add-peers state source (str user i)))
;                  state))
;        [_ msgs] (core/source-removed state source nil)]
;    msgs))
;
;(defn perf-add
;  []
;  (let [source (ch->src "source")
;        user "user"
;        state (loop [i 100 state (new-state)]
;                (if (pos? i)
;                  (recur (dec i) (add-peers state source (str user i)))
;                  state))]
;    state))