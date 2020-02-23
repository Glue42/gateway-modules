(ns gateway.domains.agm.t-registration
  (:require
    [clojure.test :refer :all]

    #?(:cljs [gateway.t-macros :refer-macros [spec-valid? just?]])
    #?(:clj [gateway.t-macros :refer [spec-valid? just?]])

    [gateway.domains.agm.t-helpers :refer [create-join create-join-service]]
    [gateway.domains.agm.core :as core]
    [gateway.domains.agm.mthds :as methods]
    [gateway.domains.agm.messages :as msgs]

    [gateway.state.peers :as peers]

    [gateway.t-helpers :refer [local-peer remote-peer gen-identity ch->src peer-id! request-id! new-state node-id]]
    [gateway.common.messages :as m]
    [gateway.state.spec.state :as ss]
    [gateway.domains.agm.constants :as constants]
    [gateway.domains.global.constants :as c]
    [gateway.reason :refer [->Reason]]))


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


(deftest adding-updates-state
  (testing "adding local peers updates the state"
    (let [{peer-id-1 :id identity-1 :identity peer-1 :source} (local-peer)
          {peer-id-2 :id identity-2 :identity peer-2 :source} (local-peer)
          empty-state (new-state)
          rid (request-id!)

          [state messages] (-> empty-state
                               (create-join peer-1 {:request_id rid :peer_id peer-id-1 :identity identity-1}))]
      (spec-valid? ::ss/state state)
      (is (= state
             (merge empty-state
                    {:domains    {:agm-domain #{peer-id-1}}
                     :identities {identity-1 peer-id-1}
                     :peers      {
                                  peer-id-1 (peers/map->Peer {:id         peer-id-1
                                                              :source     peer-1
                                                              :identity   identity-1
                                                              :agm-domain {}})}
                     :users      {:no-user #{peer-id-1}}})))
      (is (= (-> state
                 (create-join peer-2 {:request_id rid :peer_id peer-id-2 :identity identity-2})
                 (first))
             (merge empty-state
                    {:domains    {:agm-domain #{peer-id-1 peer-id-2}}
                     :identities {identity-1 peer-id-1
                                  identity-2 peer-id-2}
                     :peers      {
                                  peer-id-1 (peers/map->Peer {:id         peer-id-1
                                                              :identity   identity-1
                                                              :source     peer-1
                                                              :agm-domain {}})
                                  peer-id-2 (peers/map->Peer {:id         peer-id-2
                                                              :identity   identity-2
                                                              :source     peer-2
                                                              :agm-domain {}})}
                     :users      {:no-user #{peer-id-1 peer-id-2}}}))))))

(deftest join-announcement-1
  (testing "joining local peers generate announcement messages from and to the other local peers"
    (let [
          {peer-id-1 :id identity-1 :identity peer-1 :source} (local-peer)
          {peer-id-2 :id identity-2 :identity peer-2 :source} (local-peer)
          {peer-id-3 :id identity-3 :identity peer-3 :source} (local-peer)
          empty-state (new-state)
          rid (request-id!)

          identity-1 (assoc identity-1 :user "user")
          identity-2 (assoc identity-2 :user "user")
          identity-3 (assoc identity-3 :user "user")


          peer-3-join-request {:domain      c/global-domain-uri
                               :destination constants/agm-domain-uri
                               :type        :join
                               :request_id  rid
                               :peer_id     peer-id-3
                               :identity    identity-3}

          [state messages] (-> empty-state

                               ;; the first peer
                               (create-join peer-1 {:domain      c/global-domain-uri
                                                    :destination constants/agm-domain-uri
                                                    :type        :join
                                                    :request_id  rid
                                                    :peer_id     peer-id-1
                                                    :identity    identity-1})
                               (first)

                               ;; the second peer
                               (create-join peer-2 {:domain      c/global-domain-uri
                                                    :destination constants/agm-domain-uri
                                                    :type        :join
                                                    :request_id  rid
                                                    :peer_id     peer-id-2
                                                    :identity    identity-2})
                               (first)

                               ;; the third peer
                               (create-join peer-3 peer-3-join-request))]
      (spec-valid? ::ss/state state)
      (just? messages
             [(msgs/peer-added peer-3 peer-id-3 peer-id-1 identity-1 {:local true})
              (msgs/peer-added peer-1 peer-id-1 peer-id-3 identity-3 {:local true})
              (msgs/peer-added peer-3 peer-id-3 peer-id-2 identity-2 {:local true})
              (msgs/peer-added peer-2 peer-id-2 peer-id-3 identity-3 {:local true})
              (msgs/success peer-3 rid peer-id-3)
              (m/broadcast {:type    :peer
                            :peer-id peer-id-3
                            :node    node-id}
                           peer-3-join-request)]))))


(deftest different-users-test
  (testing "peers from different users are not announced to each other"
    (let [
          {peer-id-1 :id identity-1 :identity peer-1 :source} (local-peer)
          {peer-id-2 :id identity-2 :identity peer-2 :source} (local-peer)

          empty-state (new-state)
          rid (request-id!)

          identity-1 (assoc identity-1 :user "user1")
          identity-2 (assoc identity-2 :user "user2")

          peer-2-join-request {:domain      c/global-domain-uri
                               :destination constants/agm-domain-uri
                               :type        :join
                               :request_id  rid
                               :peer_id     peer-id-2
                               :identity    identity-2}

          [state messages] (-> empty-state

                               ;; the first peer
                               (create-join peer-1 {:type       :join
                                                    :request_id rid
                                                    :peer_id    peer-id-1
                                                    :identity   identity-1})
                               (first)

                               ;; register a method
                               (methods/register peer-1 {:domain     constants/agm-domain-uri
                                                         :type       :register
                                                         :request_id rid :peer_id peer-id-1 :methods [method-def]})
                               (first)

                               ;; the second peer
                               (create-join peer-2 {:type       :join
                                                    :request_id rid
                                                    :peer_id    peer-id-2
                                                    :identity   identity-2}))]
      (spec-valid? ::ss/state state)
      (just? messages
             [(msgs/success peer-2 rid peer-id-2)
              (m/broadcast {:type    :peer
                            :peer-id peer-id-2
                            :node    node-id}
                           peer-2-join-request)]))))

(deftest join-announcement-2
  (testing "local peers that are not in the agm domain are not announced"
    (let [
          {peer-id-1 :id identity-1 :identity peer-1 :source} (local-peer)
          {peer-id-2 :id identity-2 :identity peer-2 :source} (local-peer)
          {peer-id-3 :id identity-3 :identity peer-3 :source} (local-peer)
          empty-state (new-state)
          rid (request-id!)

          identity-1 (assoc identity-1 :user "user")
          identity-2 (assoc identity-2 :user "user")
          identity-3 (assoc identity-3 :user "user")


          peer-3-join-request {:domain      c/global-domain-uri
                               :destination constants/agm-domain-uri
                               :type        :join
                               :request_id  rid
                               :peer_id     peer-id-3
                               :identity    identity-3}

          messages (-> empty-state

                       ;; the first peer
                       (create-join peer-1 {:domain      c/global-domain-uri
                                            :destination constants/agm-domain-uri
                                            :type        :join
                                            :request_id  rid
                                            :peer_id     peer-id-1
                                            :identity    identity-1})
                       (first)

                       ;; the second peer is there but not in the AGM domain
                       (peers/ensure-peer-with-id peer-2 peer-id-2 identity-2 nil nil)
                       (first)

                       ;; the third peer joins
                       (create-join peer-3 peer-3-join-request)
                       (second))]
      (just? messages
             [(msgs/peer-added peer-3 peer-id-3 peer-id-1 identity-1 {:local true})
              (msgs/peer-added peer-1 peer-id-1 peer-id-3 identity-3 {:local true})
              (msgs/success peer-3 rid peer-id-3)
              (m/broadcast {:type    :peer
                            :peer-id peer-id-3
                            :node    node-id}
                           peer-3-join-request)]))))

(deftest join-announcement-3
  (testing "present remote peers are not announced"
    (let [
          {peer-id-1 :id identity-1 :identity peer-1 :source} (local-peer)
          {peer-id-2 :id identity-2 :identity peer-2 :source} (remote-peer node-id)
          {peer-id-3 :id identity-3 :identity peer-3 :source} (local-peer)
          empty-state (new-state)
          rid (request-id!)

          identity-1 (assoc identity-1 :user "user")
          identity-2 (assoc identity-2 :user "user")
          identity-3 (assoc identity-3 :user "user")


          peer-3-join-request {:domain      c/global-domain-uri
                               :destination constants/agm-domain-uri
                               :type        :join
                               :request_id  rid
                               :peer_id     peer-id-3
                               :identity    identity-3}

          messages (-> empty-state

                       ;; the first peer
                       (create-join peer-1 {:domain      c/global-domain-uri
                                            :destination constants/agm-domain-uri
                                            :type        :join
                                            :request_id  rid
                                            :peer_id     peer-id-1
                                            :identity    identity-1})
                       (first)

                       (create-join peer-2 {:domain      c/global-domain-uri
                                            :destination constants/agm-domain-uri
                                            :type        :join
                                            :request_id  rid
                                            :peer_id     peer-id-2
                                            :identity    identity-2})
                       (first)

                       ;; the third peer joins
                       (create-join peer-3 peer-3-join-request)
                       (second))]
      (just? messages
             [(msgs/peer-added peer-3 peer-id-3 peer-id-1 identity-1 {:local true})
              (msgs/peer-added peer-1 peer-id-1 peer-id-3 identity-3 {:local true})
              (msgs/peer-added peer-3 peer-id-3 peer-id-2 identity-2 {:local false})
              (msgs/success peer-3 rid peer-id-3)
              (m/broadcast {:type    :peer
                            :peer-id peer-id-3
                            :node    node-id}
                           peer-3-join-request)]))))

(deftest join-announcement-4
  (testing "joining remote peers generates announcement for the local peers and
            doesnt generate a success and broadcast message"
    (let [
          {peer-id-1 :id identity-1 :identity peer-1 :source} (local-peer)
          {peer-id-2 :id identity-2 :identity peer-2 :source} (local-peer)
          {peer-id-3 :id identity-3 :identity peer-3 :source} (remote-peer node-id)
          empty-state (new-state)
          rid (request-id!)

          identity-1 (assoc identity-1 :user "user")
          identity-2 (assoc identity-2 :user "user")
          identity-3 (assoc identity-3 :user "user")


          peer-3-join-request {:type       :join
                               :request_id rid
                               :peer_id    peer-id-3
                               :identity   identity-3}

          messages (-> empty-state

                       ;; the first peer
                       (create-join peer-1 {:type       :join
                                            :request_id rid
                                            :peer_id    peer-id-1
                                            :identity   identity-1})
                       (first)

                       (create-join peer-2 {:type       :join
                                            :request_id rid
                                            :peer_id    peer-id-2
                                            :identity   identity-2})
                       (first)

                       ;; the third peer joins
                       (create-join peer-3 peer-3-join-request)
                       (second))]
      (just? messages
             [(msgs/peer-added peer-1 peer-id-1 peer-id-3 identity-3 {:local false})
              (msgs/peer-added peer-2 peer-id-2 peer-id-3 identity-3 {:local false})]))))

(deftest service-peer-visibility
  (testing "service peers are announced to other peers regardless of their user"
    (let [
          {peer-id-1 :id identity-1 :identity peer-1 :source} (local-peer)
          {peer-id-2 :id identity-2 :identity peer-2 :source} (local-peer)
          {peer-id-3 :id identity-3 :identity peer-3 :source} (local-peer)

          empty-state (new-state)
          rid (request-id!)

          identity-1 (assoc identity-1 :user "user-1")
          identity-2 (assoc identity-2 :user "user-2")
          identity-3 (assoc identity-3 :user "user-3")


          peer-3-join-request {:domain      c/global-domain-uri
                               :destination constants/agm-domain-uri
                               :type        :join
                               :request_id  rid
                               :peer_id     peer-id-3
                               :identity    identity-3}

          [state messages] (-> empty-state

                               ;; the first peer
                               (create-join peer-1 {:type       :join
                                                    :request_id rid
                                                    :peer_id    peer-id-1
                                                    :identity   identity-1})
                               (first)

                               ;; the second peer
                               (create-join peer-2 {:type       :join
                                                    :request_id rid
                                                    :peer_id    peer-id-2
                                                    :identity   identity-2})
                               (first)

                               ;; the third peer
                               (create-join-service peer-3 peer-3-join-request))]
      (spec-valid? ::ss/state state)
      (just? messages
             [(msgs/peer-added peer-3 peer-id-3 peer-id-1 identity-1 {:local true})
              (msgs/peer-added peer-1 peer-id-1 peer-id-3 identity-3 {:local true})
              (msgs/peer-added peer-3 peer-id-3 peer-id-2 identity-2 {:local true})
              (msgs/peer-added peer-2 peer-id-2 peer-id-3 identity-3 {:local true})
              (msgs/success peer-3 rid peer-id-3)
              (m/broadcast {:type    :peer
                            :peer-id peer-id-3
                            :node    node-id}
                           (assoc peer-3-join-request :options {:service? true}))]))))

(deftest methods-1
  (testing "methods added from a local peer are announced to all local peers that can see them and then broadcasted"
    (let [{peer-id-1 :id identity-1 :identity peer-1 :source} (local-peer)
          {peer-id-2 :id identity-2 :identity peer-2 :source} (local-peer)
          {peer-id-3 :id identity-3 :identity peer-3 :source} (local-peer)
          empty-state (new-state)
          rid (request-id!)

          identity-1 (assoc identity-1 :user "user")
          identity-2 (assoc identity-2 :user "user")
          identity-3 (assoc identity-3 :user "other")

          registration-request {:domain     constants/agm-domain-uri
                                :type       :register
                                :request_id rid :peer_id peer-id-1 :methods [method-def]}

          [new-state messages] (-> empty-state
                                   (create-join peer-1 {:request_id rid :peer_id peer-id-1 :identity identity-1})
                                   (first)
                                   (create-join peer-2 {:request_id rid :peer_id peer-id-2 :identity identity-2})
                                   (first)
                                   (create-join peer-3 {:request_id rid :peer_id peer-id-3 :identity identity-3})
                                   (first)
                                   (methods/register peer-1 registration-request)
                                   )]
      (spec-valid? ::ss/state new-state)
      (is (= new-state
             (merge empty-state
                    {:domains    {:agm-domain #{peer-id-1 peer-id-2 peer-id-3}}
                     :identities {identity-1 peer-id-1
                                  identity-2 peer-id-2
                                  identity-3 peer-id-3}
                     :peers      {peer-id-1 (peers/map->Peer {:id         peer-id-1
                                                              :identity   identity-1
                                                              :source     peer-1
                                                              :agm-domain {:methods {peer-id-1 {(:id method-def) method-def}}}})
                                  peer-id-2 (peers/map->Peer {:id         peer-id-2
                                                              :identity   identity-2
                                                              :source     peer-2
                                                              :agm-domain {
                                                                           :methods {peer-id-1 {(:id method-def) method-def}}}})
                                  peer-id-3 (peers/map->Peer {:id         peer-id-3
                                                              :identity   identity-3
                                                              :source     peer-3
                                                              :agm-domain {}})}
                     :users      {"user"  #{peer-id-1 peer-id-2}
                                  "other" #{peer-id-3}}})))
      (just? messages
             [(msgs/methods-added peer-2 peer-id-2 peer-id-1 [method-def] :local)
              (msgs/methods-added peer-1 peer-id-1 peer-id-1 [method-def] :local)
              (msgs/success peer-1 rid peer-id-1)
              (m/broadcast {:type    :peer
                            :peer-id peer-id-1
                            :node    node-id}
                           registration-request)]))))

(deftest methods-2
  (testing "methods added from local peer are not announced to remote peers"
    (let [
          {peer-id-1 :id identity-1 :identity peer-1 :source} (local-peer)
          {peer-id-2 :id identity-2 :identity peer-2 :source} (local-peer)
          {peer-id-3 :id identity-3 :identity peer-3 :source} (remote-peer node-id)
          empty-state (new-state)
          rid (request-id!)

          identity-1 (assoc identity-1 :user "user")
          identity-2 (assoc identity-2 :user "user")
          identity-3 (assoc identity-3 :user "user")

          registration-request {:domain     constants/agm-domain-uri
                                :type       :register
                                :request_id rid :peer_id peer-id-1 :methods [method-def]}

          [new-state messages] (-> empty-state
                                   (create-join peer-1 {:request_id rid :peer_id peer-id-1 :identity identity-1})
                                   (first)
                                   (create-join peer-2 {:request_id rid :peer_id peer-id-2 :identity identity-2})
                                   (first)
                                   (create-join peer-3 {:request_id rid :peer_id peer-id-3 :identity identity-3})
                                   (first)
                                   (methods/register peer-1 registration-request)
                                   )]
      (spec-valid? ::ss/state new-state)
      (just? messages
             [(msgs/methods-added peer-2 peer-id-2 peer-id-1 [method-def] :local)
              (msgs/methods-added peer-1 peer-id-1 peer-id-1 [method-def] :local)
              (msgs/success peer-1 rid peer-id-1)
              (m/broadcast {:type    :peer
                            :peer-id peer-id-1
                            :node    node-id} registration-request)]))))

(deftest methods-3
  (testing "methods added from remote peers are announced to local peers and not broadcasted"
    (let [{peer-id-1 :id identity-1 :identity peer-1 :source} (remote-peer node-id)
          {peer-id-2 :id identity-2 :identity peer-2 :source} (local-peer)
          {peer-id-3 :id identity-3 :identity peer-3 :source} (local-peer)
          empty-state (new-state)
          rid (request-id!)

          identity-1 (assoc identity-1 :user "user")
          identity-2 (assoc identity-2 :user "user")
          identity-3 (assoc identity-3 :user "user")

          registration-request {:request_id rid :peer_id peer-id-1 :methods [method-def]}


          [newstate messages] (-> empty-state
                                  (create-join peer-1 {:request_id rid :peer_id peer-id-1 :identity identity-1})
                                  (first)
                                  (create-join peer-2 {:request_id rid :peer_id peer-id-2 :identity identity-2})
                                  (first)
                                  (create-join peer-3 {:request_id rid :peer_id peer-id-3 :identity identity-3})
                                  (first)
                                  (methods/register peer-1 registration-request))]
      (spec-valid? ::ss/state newstate)
      (just? messages
             [(msgs/methods-added peer-2 peer-id-2 peer-id-1 [method-def] :peer)
              (msgs/methods-added peer-3 peer-id-3 peer-id-1 [method-def] :peer)]))))

(deftest method-unregistration
  (testing "local peers unregistering methods generates announcements for the other local peers and broadcasts"
    (let [
          {peer-id-1 :id identity-1 :identity peer-1 :source} (local-peer)
          {peer-id-2 :id identity-2 :identity peer-2 :source} (local-peer)
          empty-state (new-state)
          rid (request-id!)

          identity-1 (assoc identity-1 :user "user")
          identity-2 (assoc identity-2 :user "user")

          method method-def
          method-id (:id method)
          unregistration-request {:domain     constants/agm-domain-uri
                                  :type       :unregister
                                  :request_id rid :peer_id peer-id-1 :methods [method-id]}
          [new-state messages] (-> empty-state
                                   (create-join peer-1 {:request_id rid :peer_id peer-id-1 :identity identity-1})
                                   (first)
                                   (create-join peer-2 {:request_id rid :peer_id peer-id-2 :identity identity-2})
                                   (first)
                                   (methods/register peer-1 {:domain     constants/agm-domain-uri
                                                             :type       :register
                                                             :request_id rid :peer_id peer-id-1 :methods [method]})
                                   (first)
                                   (methods/unregister peer-1 unregistration-request))]
      (spec-valid? ::ss/state new-state)
      (is (= new-state
             (merge empty-state
                    {:domains    {:agm-domain #{peer-id-1 peer-id-2}}
                     :identities {identity-1 peer-id-1
                                  identity-2 peer-id-2}
                     :peers      {peer-id-1 (peers/map->Peer {:id         peer-id-1
                                                              :identity   identity-1
                                                              :agm-domain {}
                                                              :source     peer-1})
                                  peer-id-2 (peers/map->Peer {:id         peer-id-2
                                                              :identity   identity-2
                                                              :source     peer-2
                                                              :agm-domain {}})}
                     :users      {"user" #{peer-id-1 peer-id-2}}})))
      (is (= messages
             [(msgs/methods-removed peer-2 peer-id-2 peer-id-1 [method-id])
              (msgs/methods-removed peer-1 peer-id-1 peer-id-1 [method-id])
              (msgs/success peer-1 rid peer-id-1)
              (m/broadcast {:type    :peer
                            :peer-id peer-id-1
                            :node    node-id}
                           unregistration-request)])))))

(deftest announcement-only-same-user-test
  (testing "announcement messages are sent only for peers that have the same user"
    (let [
          {peer-id-1 :id identity-1 :identity peer-1 :source} (local-peer)
          {peer-id-2 :id identity-2 :identity peer-2 :source} (local-peer)
          empty-state (new-state)
          rid (request-id!)

          identity-1 (assoc identity-1 :user "user1")
          identity-2 (assoc identity-2 :user "user2")

          method method-def
          method-id (:id method)
          unregistration-request {:domain     constants/agm-domain-uri
                                  :type       :unregister
                                  :request_id rid :peer_id peer-id-1 :methods [method-id]}
          [new-state register-msgs] (-> empty-state
                                        (create-join peer-1 {:request_id rid :peer_id peer-id-1 :identity identity-1})
                                        (first)
                                        (create-join peer-2 {:request_id rid :peer_id peer-id-2 :identity identity-2})
                                        (first)
                                        (methods/register peer-1 {:domain     constants/agm-domain-uri
                                                                  :type       :register
                                                                  :request_id rid :peer_id peer-id-1 :methods [method]}))

          [new-state unregister-msgs] (-> new-state
                                          (methods/unregister peer-1 unregistration-request))]

      (spec-valid? ::ss/state new-state)
      (is (= unregister-msgs
             [(msgs/methods-removed peer-1 peer-id-1 peer-id-1 [method-id])
              (msgs/success peer-1 rid peer-id-1)
              (m/broadcast {:type    :peer
                            :peer-id peer-id-1
                            :node    node-id}
                           unregistration-request)])))))

(deftest methods-5
  (testing "local peers unregistering methods doesnt generate announcements for remote peers"
    (let [
          {peer-id-1 :id identity-1 :identity peer-1 :source} (local-peer)
          {peer-id-2 :id identity-2 :identity peer-2 :source} (remote-peer node-id)
          empty-state (new-state)
          rid (request-id!)

          identity-1 (assoc identity-1 :user "user")
          identity-2 (assoc identity-2 :user "user")

          method method-def
          method-id (:id method)
          unregistration-request {:domain     constants/agm-domain-uri
                                  :type       :unregister
                                  :request_id rid :peer_id peer-id-1 :methods [method-id]}
          [new-state messages] (-> empty-state
                                   (create-join peer-1 {:request_id rid :peer_id peer-id-1 :identity identity-1})
                                   (first)
                                   (create-join peer-2 {:request_id rid :peer_id peer-id-2 :identity identity-2})
                                   (first)
                                   (methods/register peer-1 {:domain     constants/agm-domain-uri
                                                             :type       :register
                                                             :request_id rid :peer_id peer-id-1 :methods [method]})
                                   (first)
                                   (methods/unregister peer-1 unregistration-request)
                                   )]
      (spec-valid? ::ss/state new-state)
      (is (= new-state
             (merge empty-state
                    {:domains    {:agm-domain #{peer-id-1 peer-id-2}}
                     :identities {identity-1 peer-id-1
                                  identity-2 peer-id-2}
                     :peers      {peer-id-1 (peers/map->Peer {:id         peer-id-1
                                                              :identity   identity-1
                                                              :agm-domain {}
                                                              :source     peer-1})
                                  peer-id-2 (peers/map->Peer {:id         peer-id-2
                                                              :identity   identity-2
                                                              :source     peer-2
                                                              :agm-domain {}})}
                     :users      {"user" #{peer-id-1 peer-id-2}}})))
      (is (= messages
             [(msgs/methods-removed peer-1 peer-id-1 peer-id-1 [method-id])
              (msgs/success peer-1 rid peer-id-1)
              (m/broadcast {:type    :peer
                            :peer-id peer-id-1
                            :node    node-id} unregistration-request)])))))

(deftest methods-6
  (testing "remote peers unregistering methods generate announcements for local peers and no broadcast"
    (let [
          {peer-id-1 :id identity-1 :identity peer-1 :source} (remote-peer node-id)
          {peer-id-2 :id identity-2 :identity peer-2 :source} (local-peer)
          empty-state (new-state)
          rid (request-id!)

          identity-1 (assoc identity-1 :user "user")
          identity-2 (assoc identity-2 :user "user")

          method method-def
          method-id (:id method)
          unregistration-request {:request_id rid :peer_id peer-id-1 :methods [method-id]}
          [new-state messages] (-> empty-state
                                   (create-join peer-1 {:request_id rid :peer_id peer-id-1 :identity identity-1})
                                   (first)
                                   (create-join peer-2 {:request_id rid :peer_id peer-id-2 :identity identity-2})
                                   (first)
                                   (methods/register peer-1 {:request_id rid :peer_id peer-id-1 :methods [method]})
                                   (first)
                                   (methods/unregister peer-1 unregistration-request)
                                   )]
      (spec-valid? ::ss/state new-state)
      (is (= new-state
             (merge empty-state
                    {:domains    {:agm-domain #{peer-id-1 peer-id-2}}
                     :identities {identity-1 peer-id-1
                                  identity-2 peer-id-2}
                     :peers      {peer-id-1 (peers/map->Peer {:id         peer-id-1
                                                              :identity   identity-1
                                                              :agm-domain {}
                                                              :source     peer-1})
                                  peer-id-2 (peers/map->Peer {:id         peer-id-2
                                                              :identity   identity-2
                                                              :source     peer-2
                                                              :agm-domain {}})}
                     :users      {"user" #{peer-id-1 peer-id-2}}})))
      (is (= messages
             [(msgs/methods-removed peer-2 peer-id-2 peer-id-1 [method-id])])))))

(deftest methods-7
  (testing "adding local peers after a local method has been registered adds the method to the new peer"
    (let [
          {peer-id-1 :id identity-1 :identity peer-1 :source} (local-peer)
          {peer-id-2 :id identity-2 :identity peer-2 :source} (local-peer)
          empty-state (new-state)
          rid (request-id!)

          identity-1 (assoc identity-1 :user "user")
          identity-2 (assoc identity-2 :user "user")
          method method-def
          peer-2-join-request {:domain      c/global-domain-uri
                               :destination constants/agm-domain-uri
                               :type        :join
                               :request_id  rid :peer_id peer-id-2 :identity identity-2}]
      (just? (-> empty-state
                 (create-join peer-1 {:request_id rid :peer_id peer-id-1 :identity identity-1})
                 (first)
                 (methods/register peer-1 {:domain     constants/agm-domain-uri
                                           :type       :register
                                           :request_id rid :peer_id peer-id-1 :methods [method]})
                 (first)
                 (create-join peer-2 peer-2-join-request)
                 (second))
             [(msgs/peer-added peer-2 peer-id-2 peer-id-1 identity-1 {:local true})
              (msgs/methods-added peer-2 peer-id-2 peer-id-1 [method] :local)
              (msgs/peer-added peer-1 peer-id-1 peer-id-2 identity-2 {:local true})
              (msgs/success peer-2 rid peer-id-2)
              (m/broadcast {:type    :peer
                            :peer-id peer-id-2
                            :node    node-id} peer-2-join-request)]))))

(deftest methods-8
  (testing "adding remote peers after a local method has been registered doesnt announce the local methods"
    (let [
          {peer-id-1 :id identity-1 :identity peer-1 :source} (local-peer)
          {peer-id-2 :id identity-2 :identity peer-2 :source} (remote-peer node-id)
          empty-state (new-state)
          rid (request-id!)

          identity-1 (assoc identity-1 :user "user")
          identity-2 (assoc identity-2 :user "user")
          method method-def
          peer-2-join-request {:request_id rid :peer_id peer-id-2 :identity identity-2}]
      (just? (-> empty-state
                 (create-join peer-1 {:request_id rid :peer_id peer-id-1 :identity identity-1})
                 (first)
                 (methods/register peer-1 {:domain     constants/agm-domain-uri
                                           :type       :register
                                           :request_id rid :peer_id peer-id-1 :methods [method]})
                 (first)
                 (create-join peer-2 peer-2-join-request)
                 (second))
             [(msgs/peer-added peer-1 peer-id-1 peer-id-2 identity-2 {:local false})]))))

(deftest join-with-existing-methods
  (testing "a newly joined peer gets all the visible existing methods announced"
    (let [
          {peer-id-1 :id identity-1 :identity peer-1 :source} (local-peer)
          {peer-id-2 :id identity-2 :identity peer-2 :source} (local-peer)
          {peer-id-3 :id identity-3 :identity peer-3 :source} (local-peer)
          empty-state (new-state)
          rid (request-id!)

          identity-1 (assoc identity-1 :user "user")
          identity-2 (assoc identity-2 :user "user")
          identity-3 (assoc identity-3 :user "user")

          method-def-2 {:id               2
                        :name             "method-name"
                        :display_name     "display-name"
                        :flags            {:flag 1}
                        :version          2
                        :input_signature  "input-signature"
                        :result_signature "result-signature"
                        :description      "description"
                        :object_types     {:type 2}}

          registration-request {:domain     constants/agm-domain-uri
                                :type       :register
                                :request_id rid :peer_id peer-id-1 :methods [method-def]}
          registration-request-2 {:domain     constants/agm-domain-uri
                                  :type       :register
                                  :request_id rid :peer_id peer-id-2 :methods [method-def-2]}
          join-request-3 {:domain      c/global-domain-uri
                          :destination constants/agm-domain-uri
                          :type        :join
                          :request_id  rid :peer_id peer-id-3 :identity identity-3}


          [new-state messages] (-> empty-state
                                   (create-join peer-1 {:request_id rid :peer_id peer-id-1 :identity identity-1})
                                   (first)
                                   (create-join peer-2 {:request_id rid :peer_id peer-id-2 :identity identity-2})
                                   (first)
                                   (methods/register peer-1 registration-request)
                                   (first)
                                   (methods/register peer-2 registration-request-2)
                                   (first)
                                   (create-join peer-3 join-request-3)
                                   )]
      (spec-valid? ::ss/state new-state)
      (is (= new-state
             (merge empty-state
                    {:domains    {:agm-domain #{peer-id-1 peer-id-2 peer-id-3}}
                     :identities {identity-1 peer-id-1
                                  identity-2 peer-id-2
                                  identity-3 peer-id-3}
                     :peers      {peer-id-1 (peers/map->Peer {:id         peer-id-1
                                                              :identity   identity-1
                                                              :source     peer-1
                                                              :agm-domain {:methods {peer-id-1 {(:id method-def) method-def}
                                                                                     peer-id-2 {(:id method-def-2) method-def-2}}}})
                                  peer-id-2 (peers/map->Peer {:id         peer-id-2
                                                              :identity   identity-2
                                                              :source     peer-2
                                                              :agm-domain {:methods {peer-id-1 {(:id method-def) method-def}
                                                                                     peer-id-2 {(:id method-def-2) method-def-2}}}})
                                  peer-id-3 (peers/map->Peer {:id         peer-id-3
                                                              :identity   identity-3
                                                              :source     peer-3
                                                              :agm-domain {:methods {peer-id-1 {(:id method-def) method-def}
                                                                                     peer-id-2 {(:id method-def-2) method-def-2}}}})}
                     :users      {"user" #{peer-id-1 peer-id-2 peer-id-3}}})))
      (just? messages
             [(msgs/peer-added peer-3 peer-id-3 peer-id-1 identity-1 {:local true})
              (msgs/peer-added peer-3 peer-id-3 peer-id-2 identity-2 {:local true})
              (msgs/peer-added peer-1 peer-id-1 peer-id-3 identity-3 {:local true})
              (msgs/peer-added peer-2 peer-id-2 peer-id-3 identity-3 {:local true})
              (msgs/methods-added peer-3 peer-id-3 peer-id-1 [method-def] :local)
              (msgs/methods-added peer-3 peer-id-3 peer-id-2 [method-def-2] :local)
              (msgs/success peer-3 rid peer-id-3)
              (m/broadcast {:type    :peer
                            :peer-id peer-id-3
                            :node    node-id}
                           join-request-3)]))))

(deftest peers
  (let [
        rid (request-id!)
        empty-state (new-state)
        [peer-id-1 peer-id-2 peer-id-3] (repeatedly 3 peer-id!)

        ; first peer
        peer-1 (ch->src "peer-1")
        identity-1 (gen-identity)

        ; second peer
        peer-2 (ch->src "peer-2")
        identity-2 (gen-identity)

        ; third peer
        peer-3 (ch->src "peer-3")
        identity-3 (gen-identity)]


    (testing "method registration/unregistration works when there is only one peer"
      (let [
            method method-def
            method-id (:id method)

            [reg-state reg-messages] (-> empty-state
                                         (create-join peer-1 {:request_id rid :peer_id peer-id-1 :identity identity-1})
                                         (first)
                                         (methods/register peer-1 {:domain     constants/agm-domain-uri
                                                                   :type       :register
                                                                   :request_id rid :peer_id peer-id-1 :methods [method]}))
            unregistration-request {:domain     constants/agm-domain-uri
                                    :type       :unregister
                                    :request_id rid :peer_id peer-id-1 :methods [method-id]}
            [unreg-state messages] (-> reg-state
                                       (methods/unregister peer-1 unregistration-request))]
        (spec-valid? ::ss/state reg-state)
        (spec-valid? ::ss/state unreg-state)
        (is (= unreg-state
               (merge empty-state
                      {:domains    {:agm-domain #{peer-id-1}}
                       :identities {identity-1 peer-id-1}
                       :peers      {peer-id-1 (peers/map->Peer {:id         peer-id-1
                                                                :identity   identity-1
                                                                :source     peer-1
                                                                :agm-domain {}})}
                       :users      {:no-user #{peer-id-1}}})))
        (is (= messages [(msgs/methods-removed peer-1 peer-id-1 peer-id-1 [method-id])
                         (msgs/success peer-1 rid peer-id-1)
                         (m/broadcast {:type    :peer
                                       :peer-id peer-id-1
                                       :node    node-id}
                                      unregistration-request)]))))

    (testing "unregistering a missing method generates an error"
      (let [
            method method-def
            method-id (:id method)
            missing-method-id (inc method-id)]
        (is (thrown? #?(:clj  Exception
                        :cljs js/Error)
                     (-> empty-state
                         (create-join peer-1
                                      {:request_id rid :peer_id peer-id-1 :identity identity-1})
                         (first)
                         (create-join peer-2
                                      {:request_id rid :peer_id peer-id-2 :identity identity-2})
                         (first)
                         (methods/register peer-1
                                           {:request_id rid :peer_id peer-id-1 :methods [method]})
                         (first)
                         (methods/unregister peer-1
                                             {:request_id rid :peer_id peer-id-1 :methods [missing-method-id]}))))))

    (testing
      "adding a peer with an existing id keeps the state intact and generates a success"
      (let [state (-> empty-state
                      (create-join peer-1 {:request_id rid :peer_id peer-id-1 :identity identity-1})
                      (first)
                      (create-join peer-2 {:request_id rid :peer_id peer-id-2 :identity identity-2})
                      (first))]
        (is (= (core/join state peer-2 {:request_id rid :peer_id peer-id-2 :identity identity-2})
               [state [(msgs/success peer-2 rid peer-id-2)]]))))

    (testing
      "removing a peer removes the agm data from the state and generates messages for the remaining peers"
      (let [
            reason {:reason_uri "reason.uri"
                    :reason     "reason message"}
            state (-> empty-state
                      (create-join peer-1 {:request_id rid :peer_id peer-id-1 :identity identity-1})
                      (first)
                      (create-join peer-2 {:request_id rid :peer_id peer-id-2 :identity identity-2})
                      (first)
                      (create-join peer-3 {:request_id rid :peer_id peer-id-3 :identity identity-3})
                      (first))
            [state messages] (-> state
                                 (core/remove-peer (peers/by-id state peer-id-1)
                                                   reason))]
        (spec-valid? ::ss/state state)
        (is (= state
               (merge empty-state
                      {:domains    {:agm-domain #{peer-id-2 peer-id-3}}
                       :identities {identity-1 peer-id-1
                                    identity-2 peer-id-2
                                    identity-3 peer-id-3}
                       :peers      {peer-id-1 (peers/map->Peer {:id       peer-id-1
                                                                :identity identity-1
                                                                :source   peer-1})
                                    peer-id-3 (peers/map->Peer {:id         peer-id-3
                                                                :identity   identity-3
                                                                :source     peer-3
                                                                :agm-domain {}})
                                    peer-id-2 (peers/map->Peer {:id         peer-id-2
                                                                :identity   identity-2
                                                                :source     peer-2
                                                                :agm-domain {}})}
                       :users      {:no-user #{peer-id-1 peer-id-2 peer-id-3}}})))
        (just? messages
               [(msgs/peer-removed peer-2 peer-id-2 peer-id-1 reason)
                (msgs/peer-removed peer-3 peer-id-3 peer-id-1 reason)])))

    (testing
      "removing a peer that has methods removes those as well"
      (let [
            method method-def
            method-id (:id method)
            reason {:reason_uri "reason.uri"
                    :reason     "reason message"}
            state (-> empty-state
                      (create-join peer-1 {:request_id rid :peer_id peer-id-1 :identity identity-1})
                      (first)
                      (create-join peer-2 {:request_id rid :peer_id peer-id-2 :identity identity-2})
                      (first)
                      (methods/register peer-1 {:domain     constants/agm-domain-uri
                                                :type       :register
                                                :request_id rid :peer_id peer-id-1 :methods [method]})
                      (first))
            [state messages] (-> state
                                 (core/remove-peer (peers/by-id state peer-id-1)
                                                   reason)
                                 )]
        (spec-valid? ::ss/state state)
        (is (= state
               (merge empty-state
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
                       :users      {:no-user #{peer-id-1 peer-id-2}}})))
        (is (= messages
               [
                (msgs/methods-removed peer-2 peer-id-2 peer-id-1 [method-id])
                (msgs/peer-removed peer-2 peer-id-2 peer-id-1 reason)]))))

    (testing
      "peer leaving cleans up the state and generates messages for the remaining peers"
      (let [[state messages] (-> empty-state
                                 (create-join peer-1 {:request_id rid :peer_id peer-id-1 :identity identity-1})
                                 (first)
                                 (create-join peer-2 {:request_id rid :peer_id peer-id-2 :identity identity-2})
                                 (first)
                                 (create-join peer-2 {:request_id rid :peer_id peer-id-3 :identity identity-3})
                                 (first)
                                 (core/source-removed peer-2 nil)
                                 )]
        (spec-valid? ::ss/state state)
        (is (= state
               (merge empty-state
                      {:domains    {:agm-domain #{peer-id-1}}
                       :identities {identity-1 peer-id-1
                                    identity-2 peer-id-2
                                    identity-3 peer-id-3}
                       :peers      {peer-id-1 (peers/map->Peer {:id         peer-id-1
                                                                :identity   identity-1
                                                                :source     peer-1
                                                                :agm-domain {}})
                                    peer-id-2 (peers/map->Peer {:id       peer-id-2
                                                                :identity identity-2
                                                                :source   peer-2})
                                    peer-id-3 (peers/map->Peer {:id       peer-id-3
                                                                :identity identity-3
                                                                :source   peer-2})}
                       :users      {:no-user #{peer-id-1 peer-id-2 peer-id-3}}})))
        (just? messages
               [(msgs/peer-removed peer-1 peer-id-1 peer-id-2 constants/reason-peer-removed)
                (msgs/peer-removed peer-1 peer-id-1 peer-id-3 constants/reason-peer-removed)])))
    ))
