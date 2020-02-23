(ns gateway.domains.activity.t-activity
  (:require
    [clojure.test :refer :all]

    #?(:cljs [gateway.t-macros :refer-macros [spec-valid? just? message-type? error-msg?]])

    #?(:clj [gateway.t-macros :refer [spec-valid? just? message-type? error-msg?]])
    #?(:clj
       [clojure.spec.test.alpha :as stest])

    [gateway.state.core :as core-state]
    [gateway.domains.activity.state :as state]
    [gateway.state.peers :as peers]

    [gateway.domains.activity.factories :as factories]
    [gateway.domains.activity.activities :as activities]
    [gateway.domains.activity.core :as core]
    [gateway.domains.activity.core :refer [join ready handle-error leave remove-peer]]
    [gateway.t-helpers :refer [gen-identity ch->src peer-id! request-id! peer-id! new-state msgs->ctx-version node-id]]

    [gateway.state.spec.state :as ss]
    [gateway.domains.activity.spec.activities-instr :as activities-instr]

    [gateway.domains.global.core :as global]
    [gateway.domains.global.messages :as gmsg]
    [gateway.common.context.ops :as contexts]
    [gateway.domains.global.state :as gs]
    [gateway.domains.global.constants :as global-constants]
    [gateway.domains.activity.constants :as constants]
    [gateway.domains.activity.messages :as msg]
    [gateway.common.messages :as m]
    [gateway.reason :refer [request->Reason reason]]))

#?(:clj
   (stest/instrument))

(def environment {:local-ip "127.0.0.1"})

(deftest test-create-activity
  (let [source (ch->src "source")
        empty-state (new-state)

        peer-id-1 (peer-id!)
        user "user 1"
        identity-1 (assoc (gen-identity) :user user)

        snoop-id (peer-id!)
        snoop-identity (assoc (gen-identity) :user user)
        default-activity-config {"activity-config" "activity-config"
                                 "will-override"   "will-override"}

        activity-type-1 {:name            "activity-type-1"
                         :owner_type      {"type"          "peer-type-1"
                                           "name"          "owner-name"
                                           "configuration" default-activity-config}
                         :helper_types    [{"type"          "peer-type-2"
                                            "name"          "named-helper"
                                            "configuration" default-activity-config}
                                           {"type"          "peer-type-3"
                                            "configuration" default-activity-config}]
                         :default_context {"activity-default-context" "activity-default-context"}}

        activity-type-2 {:name            "activity-type-2"
                         :owner_type      {"type"          "peer-type-4"
                                           "configuration" default-activity-config}
                         :helper_types    [{"type"          "peer-type-5"
                                            "configuration" default-activity-config}
                                           {"type"          "peer-type-6"
                                            "configuration" default-activity-config}]
                         :default_context {:context-key "aa"}}
        activity-reg-r {:request_id (request-id!)
                        :type       :add-types
                        :peer_id    peer-id-1
                        :types      [activity-type-1 activity-type-2]}

        factory-reg-r {:request_id 1
                       :peer_id    peer-id-1
                       :factories  [{:id            1
                                     :peer_type     "peer-type-1"
                                     :configuration {:factory-config-key "factory-config-value"}}
                                    {:id            2
                                     :peer_type     "peer-type-2"
                                     :configuration {:factory-config-key "factory-config-value"}}
                                    {:id            3
                                     :peer_type     "peer-type-3"
                                     :configuration {:factory-config-key "factory-config-value"}}]}

        state-with-types (-> empty-state
                             (peers/ensure-peer-with-id source peer-id-1 identity-1 nil nil)
                             (first)
                             (core-state/join-domain peer-id-1 :activity-domain nil)
                             (core/handle-request source activity-reg-r)
                             (first)
                             (factories/add source factory-reg-r)
                             (first)
                             (peers/ensure-peer-with-id source snoop-id snoop-identity nil nil)
                             (first)
                             (core-state/join-domain snoop-id :activity-domain nil)
                             (activities/subscribe source {:request_id     (request-id!)
                                                           :peer_id        snoop-id
                                                           :activity_types [(:name activity-type-1)]})
                             (first))

        create-override-config {"will-override" "overriden"}
        create-r {:request_id      (request-id!)
                  :peer_id         peer-id-1
                  :activity_type   "activity-type-1"
                  :initial_context {:context-override "context-override"}
                  :configuration   [{:type "peer-type-1" :configuration (merge create-override-config
                                                                               {:configuration-override "config-override-1"})}
                                    {:type "peer-type-1" :name "owner-name" :configuration {:named-override "named-override"}}
                                    {:type "peer-type-2" :configuration (merge create-override-config
                                                                               {:configuration-override "config-override-2"})}
                                    {:type "peer-type-3" :configuration (merge create-override-config
                                                                               {:configuration-override "config-override-3"})}]}

        [state-activity-requested peer-request-m] (-> state-with-types
                                                      (activities/create-activity source create-r (:configuration create-r)))]
    (spec-valid? ::ss/state state-activity-requested)
    (is (= 4 (count peer-request-m)))
    (let [[initiated-m owner-m helper-1-m helper-2-m] peer-request-m
          activity-id (get-in initiated-m [:body :activity_id])]
      (is (some? activity-id))
      (message-type? initiated-m :initiated)
      (message-type? owner-m :peer-requested)
      (message-type? helper-1-m :peer-requested)
      (message-type? helper-2-m :peer-requested)

      (testing "the configuration overrides have been applied in the peer request message"
        (let [merged-config (merge default-activity-config
                                   {:factory-config-key "factory-config-value"}
                                   create-override-config)]
          (is (= (get-in owner-m [:body :configuration]) (assoc merged-config :configuration-override "config-override-1"
                                                                              :named-override "named-override")))
          (is (= (get-in helper-1-m [:body :configuration]) (assoc merged-config :configuration-override "config-override-2")))
          (is (= (get-in helper-2-m [:body :configuration]) (assoc merged-config :configuration-override "config-override-3")))))


      (testing "owner responds with failure first"
        (let [owner-r-id (get-in owner-m [:body :request_id])
              owner-pid (get-in owner-m [:body :peer_id])
              [state messages] (-> state-activity-requested
                                   (handle-error source {:request_id owner-r-id
                                                         :peer_id    owner-pid
                                                         :reason_uri "failed-uri"
                                                         :reason     "failed"}))]

          (spec-valid? ::ss/state state)
          (is (empty? (:gateway-requests state)))           ;; all gateway requests are removed
          (is (= 1 (count messages)))
          (is (error-msg? constants/activity-owner-creation-failed (first messages))) ;; error message was sent to the requestor
          ))
      (testing "helper joins and then the owner responds with failure"
        (let [owner-r-id (get-in owner-m [:body :request_id])
              owner-pid (get-in owner-m [:body :peer_id])

              gw-token-1 (get-in helper-1-m [:body :gateway_token])

              created-identity (gen-identity)

              [created-state messages] (-> state-activity-requested
                                           (global/handle-hello source
                                                                {:type           :hello
                                                                 :request_id     1
                                                                 :identity       created-identity
                                                                 :authentication {"method" "gateway-token" "token" gw-token-1}}
                                                                nil
                                                                nil
                                                                environment))
              welcome-msg (first messages)
              _ (is (= :welcome (get-in welcome-msg [:body :type])))
              created-id (get-in welcome-msg [:body :peer_id])
              joined-state (-> created-state
                               (join source
                                     {:request_id (request-id!)
                                      :peer_id    created-id
                                      :identity   created-identity})
                               (first))

              [state messages] (-> joined-state
                                   (handle-error source
                                                 {:request_id owner-r-id
                                                  :peer_id    owner-pid
                                                  :reason_uri "failed-uri"
                                                  :reason     "failed"}))
              ]
          (spec-valid? ::ss/state state)
          (is (empty? (:gateway-requests state)))           ;; all gateway requests are removed
          (is (= 2 (count messages)))
          (message-type? (first messages) :dispose-peer)
          (is (error-msg? constants/activity-owner-creation-failed (second messages))) ;; error message was sent to the requestor
          ))
      (testing "helper joins and then the owner ready"
        (let [gw-token-1 (get-in helper-1-m [:body :gateway_token])

              created-identity (gen-identity)

              [created-state messages] (-> state-activity-requested
                                           (global/handle-hello source
                                                                {:type           :hello
                                                                 :request_id     1
                                                                 :identity       created-identity
                                                                 :authentication {"method" "gateway-token" "token" gw-token-1}}
                                                                nil
                                                                nil
                                                                environment))
              welcome-msg (first messages)
              _ (is (= :welcome (get-in welcome-msg [:body :type])))
              helper-id (get-in welcome-msg [:body :peer_id])
              joined-state (-> created-state
                               (join source
                                     {:request_id (request-id!)
                                      :peer_id    helper-id
                                      :identity   created-identity})
                               (first))

              ;; joining the owner
              gw-owner-token (get-in owner-m [:body :gateway_token])
              owner-identity (gen-identity)
              [owner-created-state messages] (-> joined-state
                                                 (global/handle-hello source
                                                                      {:type           :hello
                                                                       :request_id     (request-id!)
                                                                       :identity       owner-identity
                                                                       :authentication {"method" "gateway-token" "token" gw-owner-token}}
                                                                      nil
                                                                      nil
                                                                      environment))
              owner-id (get-in (first messages) [:body :peer_id])
              [owner-ready-state messages] (-> owner-created-state
                                               (join source {:request_id (request-id!)
                                                             :peer_id    owner-id
                                                             :identity   owner-identity})
                                               (first)
                                               (ready source {:peer_id owner-id}))
              activity (state/activity owner-ready-state activity-id)

              joined-msg (msg/joined-activity owner-ready-state
                                              source
                                              (peers/by-id owner-ready-state owner-id)
                                              activity
                                              {:context-override "context-override"})
              created-msg (msg/activity-created owner-ready-state
                                                source
                                                snoop-id
                                                activity)]

          (spec-valid? ::ss/state owner-ready-state)
          (just? messages [joined-msg created-msg])

          ;; the joined message holds the peer name and type
          (is (= (get-in joined-msg [:body :peer_name]) "owner-name"))
          (is (= (get-in joined-msg [:body :peer_type]) "peer-type-1"))

          ;; the created message holds the participant names and types
          (is (= (get-in created-msg [:body :participants])
                 [{:peer_id helper-id
                   :type    "peer-type-2"
                   :name    "named-helper"}]))
          (is (= (get-in created-msg [:body :owner])
                 {:peer_id owner-id
                  :type    "peer-type-1"
                  :name    "owner-name"}))))

      (testing "owner ready should send joined-activity and activity-created"
        (let [gw-owner-token (get-in owner-m [:body :gateway_token])
              context-id (get-in owner-m [:body :activity :context_id])
              owner-identity (gen-identity)

              [created-state messages] (-> state-activity-requested
                                           (global/handle-hello source
                                                                {:type           :hello
                                                                 :request_id     (request-id!)
                                                                 :identity       owner-identity
                                                                 :authentication {"method" "gateway-token" "token" gw-owner-token}}
                                                                nil
                                                                nil
                                                                environment))
              owner-id (get-in (first messages) [:body :peer_id])
              [ready-state messages] (-> created-state
                                         (join source {:request_id (request-id!)
                                                       :peer_id    owner-id
                                                       :identity   owner-identity})
                                         (first)
                                         (ready source {:peer_id owner-id}))

              activity {:id           activity-id
                        :type         (:name activity-type-1)
                        :initiator    peer-id-1
                        :context-id   context-id
                        :participants nil
                        :owner        owner-id}]
          ;(is (= ready-state {}))
          (just? messages [(msg/joined-activity ready-state
                                                source
                                                (peers/by-id ready-state owner-id :activity-domain)
                                                activity
                                                {:context-override "context-override"})
                           (msg/activity-created ready-state
                                                 source
                                                 snoop-id
                                                 activity)])
          (testing "participant joins after the activity has started"
            (let [helper-1-token (get-in helper-1-m [:body :gateway_token])
                  helper-1-identity (gen-identity)

                  [helper-created-s messages] (-> ready-state
                                                  (global/handle-hello source
                                                                       {:type           :hello
                                                                        :request_id     1
                                                                        :identity       helper-1-identity
                                                                        :authentication {"method" "gateway-token" "token" helper-1-token}}
                                                                       nil
                                                                       nil
                                                                       environment))
                  helper-1-id (get-in (first messages) [:body :peer_id])
                  [helper-ready-s messages] (-> helper-created-s
                                                (join source {:request_id (request-id!)
                                                              :peer_id    helper-1-id
                                                              :identity   helper-1-identity})
                                                (first)
                                                (ready source {:peer_id helper-1-id}))

                  activity (state/activity helper-ready-s activity-id)
                  helper-1 (peers/by-id helper-ready-s helper-1-id :activity-domain)]
              (just? messages [(msg/joined-activity helper-ready-s
                                                    source
                                                    helper-1
                                                    activity
                                                    {:context-override "context-override"})
                               (msg/activity-joined source
                                                    owner-id
                                                    helper-1
                                                    activity-id)
                               (msg/activity-joined source
                                                    snoop-id
                                                    helper-1
                                                    activity-id)]))))))))

(deftest test-create-activity-type-override
  (let [source (ch->src "source")
        empty-state (new-state)

        peer-id-1 (peer-id!)
        user "user 1"
        identity-1 (assoc (gen-identity) :user user)

        snoop-id (peer-id!)
        snoop-identity (assoc (gen-identity) :user user)
        default-activity-config {"activity-config" "activity-config"
                                 "will-override"   "will-override"}

        activity-type-1 {:name            "activity-type-1"
                         :owner_type      {"type"          "peer-type-1"
                                           "name"          "owner-name"
                                           "configuration" default-activity-config}
                         :helper_types    [{"type"          "peer-type-2"
                                            "name"          "named-helper"
                                            "configuration" default-activity-config}
                                           {"type"          "peer-type-3"
                                            "configuration" default-activity-config}]
                         :default_context {"activity-default-context" "activity-default-context"}}

        activity-type-2 {:name            "activity-type-2"
                         :owner_type      {"type"          "peer-type-4"
                                           "configuration" default-activity-config}
                         :helper_types    [{"type"          "peer-type-5"
                                            "configuration" default-activity-config}
                                           {"type"          "peer-type-6"
                                            "configuration" default-activity-config}]
                         :default_context {:context-key "aa"}}
        activity-reg-r {:request_id (request-id!)
                        :type       :add-types
                        :peer_id    peer-id-1
                        :types      [activity-type-1 activity-type-2]}

        factory-reg-r {:request_id 1
                       :peer_id    peer-id-1
                       :factories  [{:id            1
                                     :peer_type     "peer-type-1"
                                     :configuration {:factory-config-key "factory-config-value"}}
                                    {:id            2
                                     :peer_type     "peer-type-2"
                                     :configuration {:factory-config-key "factory-config-value"}}
                                    {:id            3
                                     :peer_type     "peer-type-3"
                                     :configuration {:factory-config-key "factory-config-value"}}
                                    {:id            4
                                     :peer_type     "peer-type-4"
                                     :configuration {:factory-config-key "factory-config-value"}}
                                    {:id            5
                                     :peer_type     "peer-type-5"
                                     :configuration {:factory-config-key "factory-config-value"}}
                                    {:id            6
                                     :peer_type     "peer-type-6"
                                     :configuration {:factory-config-key "factory-config-value"}}]}

        state-with-types (-> empty-state
                             (peers/ensure-peer-with-id source peer-id-1 identity-1 nil nil)
                             (first)
                             (core-state/join-domain peer-id-1 :activity-domain nil)
                             (core/handle-request source activity-reg-r)
                             (first)
                             (factories/add source factory-reg-r)
                             (first)
                             (peers/ensure-peer-with-id source snoop-id snoop-identity nil nil)
                             (first)
                             (core-state/join-domain snoop-id :activity-domain nil)
                             (activities/subscribe source {:request_id     (request-id!)
                                                           :peer_id        snoop-id
                                                           :activity_types [(:name activity-type-1)]})
                             (first))

        create-override-config {"will-override" "overriden"}
        create-r {:request_id    (request-id!)
                  :peer_id       peer-id-1
                  :activity_type "activity-type-1"
                  :type          :create}]
    (testing "error is thrown if there are type and configuration overrides"
      (let [create-r (assoc create-r
                       :configuration [{:type "peer-type-1" :configuration (merge create-override-config
                                                                                  {:configuration-override "config-override-1"})}
                                       {:type "peer-type-1" :name "owner-name" :configuration {:named-override "named-override"}}
                                       {:type "peer-type-2" :configuration (merge create-override-config
                                                                                  {:configuration-override "config-override-2"})}
                                       {:type "peer-type-3" :configuration (merge create-override-config
                                                                                  {:configuration-override "config-override-3"})}]
                       :types_override {:owner_type   {:type          "peer-type-4"
                                                       :configuration default-activity-config}
                                        :helper_types [{:type          "peer-type-5"
                                                        :configuration default-activity-config}]})]
        (is (thrown? #?(:clj  Exception
                        :cljs :default) (-> state-with-types
                                            (activities/create-activity source
                                                                        create-r
                                                                        (:configuration create-r)))))))

    (testing "the type override is used to create the peers"
      (let [create-r (assoc create-r
                       :types_override {:owner_type   {:type          "peer-type-4"
                                                       :configuration default-activity-config}
                                        :helper_types [{:type          "peer-type-5"
                                                        :configuration default-activity-config}]})
            [state-activity-requested peer-request-m] (-> state-with-types
                                                          (activities/create-activity source create-r (:configuration create-r)))]
        (spec-valid? ::ss/state state-activity-requested)
        (is (= 3 (count peer-request-m)))
        (let [[initiated-m owner-m helper-1-m] peer-request-m
              activity-id (get-in initiated-m [:body :activity_id])]
          (is (some? activity-id))
          (message-type? initiated-m :initiated)
          (message-type? owner-m :peer-requested)
          (message-type? helper-1-m :peer-requested)
          (is (= (get-in owner-m [:body :peer_factory]) 4))
          (is (= (get-in helper-1-m [:body :peer_factory]) 5)))))

    (testing "handle-message keywordizes the creation arguments"
      (let [create-r (assoc create-r
                       :types_override {"owner_type"   {"type"          "peer-type-4"
                                                        "configuration" default-activity-config}
                                        "helper_types" [{"type"          "peer-type-5"
                                                         "configuration" default-activity-config}]})
            [state-activity-requested peer-request-m] (core/handle-request state-with-types source create-r)]
        (spec-valid? ::ss/state state-activity-requested)
        (is (= 3 (count peer-request-m)))
        (let [[initiated-m owner-m helper-1-m] peer-request-m
              activity-id (get-in initiated-m [:body :activity_id])]
          (is (some? activity-id))
          (message-type? initiated-m :initiated)
          (message-type? owner-m :peer-requested)
          (message-type? helper-1-m :peer-requested)
          (is (= (get-in owner-m [:body :peer_factory]) 4))
          (is (= (get-in helper-1-m [:body :peer_factory]) 5)))))))

(defn prepare-test-activity
  []
  (let [source (ch->src "source")
        peer-id-1 (peer-id!)
        user "user 1"
        identity-1 (assoc (gen-identity) :user user)

        activity-type-1 {:name            "activity-type-1"
                         :owner_type      {:type "peer-type-1"}
                         :helper_types    [{:type "peer-type-2"}
                                           {:type "peer-type-3"}]
                         :default_context {:context-key "a"}}
        activity-type-2 {:name            "activity-type-2"
                         :owner_type      {:type "peer-type-4"}
                         :helper_types    [{:type "peer-type-5"}
                                           {:type "peer-type-6"}]
                         :default_context {:context-key "aa"}}
        activity-reg-r {:request_id (request-id!)
                        :type       :add-types
                        :peer_id    peer-id-1
                        :types      [activity-type-1 activity-type-2]}

        factories [{:id            1
                    :peer_type     "peer-type-1"
                    :configuration {:config-key "config-value"}}
                   {:id            2
                    :peer_type     "peer-type-2"
                    :configuration {:config-key "config-value"}}
                   {:id            3
                    :peer_type     "peer-type-3"
                    :configuration {:config-key "config-value"}}]
        factory-reg-r {:request_id 1
                       :peer_id    peer-id-1
                       :factories  factories}

        state-with-types (-> (new-state)
                             (peers/ensure-peer-with-id source peer-id-1 identity-1 nil nil)
                             (first)
                             (core-state/join-domain peer-id-1 :activity-domain nil)
                             (core/handle-request source activity-reg-r)
                             (first)
                             (factories/add source factory-reg-r)
                             (first))

        create-r {:request_id      (request-id!)
                  :peer_id         peer-id-1
                  :activity_type   "activity-type-1"
                  :initial_context {:context-override "context-override"}
                  :configuration   [{:type "peer-type-1" :configuration {:configuration-override "config-override-1"}}
                                    {:type "peer-type-2" :configuration {:configuration-override "config-override-2"}}
                                    {:type "peer-type-3" :configuration {:configuration-override "config-override-3"}}]}

        [state-activity-requested peer-request-m] (-> state-with-types
                                                      (activities/create-activity source create-r (:configuration create-r)))
        [initiated-m owner-m helper-1-m helper-2-m] peer-request-m
        activity-id (get-in initiated-m [:body :activity_id])
        context-id (get-in owner-m [:body :activity :context_id])

        owner-token (get-in owner-m [:body :gateway_token])
        owner-identity (gen-identity)
        helper-1-token (get-in helper-1-m [:body :gateway_token])
        helper-1-identity (gen-identity)

        [created-state messages] (-> state-activity-requested
                                     (global/handle-hello source
                                                          {:type           :hello
                                                           :request_id     1
                                                           :identity       owner-identity
                                                           :authentication {"method" "gateway-token" "token" owner-token}}
                                                          nil
                                                          nil
                                                          environment))
        owner-id (get-in (first messages) [:body :peer_id])

        [created-state messages] (-> created-state
                                     (global/handle-hello source
                                                          {:type           :hello
                                                           :request_id     1
                                                           :identity       helper-1-identity
                                                           :authentication {"method" "gateway-token" "token" helper-1-token}}
                                                          nil
                                                          nil
                                                          environment))
        helper-1-id (get-in (first messages) [:body :peer_id])

        snoop-id (peer-id!)
        snoop-identity (assoc (gen-identity) :user user)

        ;_ (clojure.pprint/pprint created-state)
        activity-state (-> created-state
                           (peers/ensure-peer-with-id source snoop-id snoop-identity nil nil)
                           (first)
                           (core-state/join-domain snoop-id :activity-domain nil)
                           (activities/subscribe source {:request_id     (request-id!)
                                                         :peer_id        snoop-id
                                                         :activity_types [(:name activity-type-1)]})
                           (first)
                           (join source {:request_id (request-id!)
                                         :peer_id    owner-id
                                         :identity   owner-identity})
                           (first)
                           (ready source {:peer_id owner-id})
                           (first)
                           (join source {:request_id (request-id!)
                                         :peer_id    helper-1-id
                                         :identity   helper-1-identity})
                           (first)
                           (ready source {:peer_id helper-1-id})
                           (first))]

    {:activity-state  activity-state
     :source          source
     :owner-id        owner-id
     :context-id      context-id
     :activity-id     activity-id
     :helper-1-id     helper-1-id
     :peer-id-1       peer-id-1
     :activity-type-1 activity-type-1
     :activity-types  #{activity-type-1 activity-type-2}
     :user            user
     :factories       factories
     :created-state   created-state
     :snoop-id        snoop-id}))

(deftest peer-leaving-activity
  (let [{:keys [activity-state source helper-1-id owner-id peer-id-1 activity-id snoop-id]} (prepare-test-activity)]
    (testing "peer leaving the activity broadcasts messages"
      (let [request-id (request-id!)
            leave-rq {:request_id request-id :peer_id helper-1-id :reason "reason" :reason_uri "reason-uri"}
            [state msgs] (-> activity-state
                             (leave source leave-rq))
            reason (request->Reason leave-rq)]
        (just? msgs [(msg/activity-left source owner-id helper-1-id activity-id reason)
                     (msg/activity-left source snoop-id helper-1-id activity-id reason)

                     (msg/peer-removed source owner-id helper-1-id reason)
                     (msg/peer-removed source helper-1-id owner-id reason)

                     (msg/peer-removed source peer-id-1 helper-1-id reason)
                     (msg/peer-removed source helper-1-id peer-id-1 reason)

                     (msg/peer-removed source snoop-id helper-1-id reason)
                     (msg/peer-removed source helper-1-id snoop-id reason)

                     (msg/success source request-id helper-1-id)])))

    (testing "owner leaving destroys the activity"
      (let [request-id (request-id!)
            leave-rq {:request_id request-id :peer_id owner-id :reason "reason" :reason_uri "reason-uri"}
            [state msgs] (-> activity-state
                             (leave source leave-rq))
            reason (request->Reason leave-rq)]
        (just? msgs [(msg/dispose-peer source helper-1-id nil reason)
                     (msg/dispose-peer source owner-id nil reason)
                     (msg/activity-destroyed source owner-id activity-id reason)
                     (msg/activity-destroyed source helper-1-id activity-id reason)
                     (msg/activity-destroyed source snoop-id activity-id reason)

                     (msg/peer-removed source owner-id helper-1-id reason)
                     (msg/peer-removed source helper-1-id owner-id reason)

                     (msg/peer-removed source peer-id-1 owner-id reason)
                     (msg/peer-removed source owner-id peer-id-1 reason)

                     (msg/peer-removed source snoop-id owner-id reason)
                     (msg/peer-removed source owner-id snoop-id reason)

                     (msg/success source request-id owner-id)])))))

(deftest owner-reloading
  (let [{:keys [activity-state source helper-1-id owner-id peer-id-1 activity-id snoop-id]} (prepare-test-activity)]
    (testing "owner sending a reload message receives a token"
      (let [request-id (request-id!)
            [state-with-reload msgs] (-> activity-state
                                         (activities/reload source {:request_id request-id :peer_id owner-id}))
            token (get-in (first msgs) [:body :token])]
        (just? msgs [(m/token constants/activity-domain-uri source request-id owner-id token)])
        (testing "then when the owner leaves the activity wont be destroyed"
          (let [request-id (request-id!)
                leave-rq {:request_id request-id :peer_id owner-id :reason "reason" :reason_uri "reason-uri"}
                [state-without-owner msgs] (-> state-with-reload
                                               (leave source leave-rq))
                reason (request->Reason leave-rq)]
            (just? msgs [(msg/activity-left source helper-1-id owner-id activity-id reason)
                         (msg/activity-left source snoop-id owner-id activity-id reason)

                         (msg/peer-removed source owner-id helper-1-id reason)
                         (msg/peer-removed source helper-1-id owner-id reason)

                         (msg/peer-removed source peer-id-1 owner-id reason)
                         (msg/peer-removed source owner-id peer-id-1 reason)

                         (msg/peer-removed source snoop-id owner-id reason)
                         (msg/peer-removed source owner-id snoop-id reason)

                         (msg/success source request-id owner-id)])
            (testing "then the owner can rejoin back with the token and send a ready message"
              (let [new-owner-identity (gen-identity)
                    [state-with-owner messages] (-> state-without-owner
                                                    (global/handle-hello source
                                                                         {:type           :hello
                                                                          :request_id     1
                                                                          :identity       new-owner-identity
                                                                          :authentication {"method" "gateway-token" "token" token}}
                                                                         nil
                                                                         nil
                                                                         environment))
                    new-owner-id (get-in (first messages) [:body :peer_id])

                    [final-state ready-msgs] (-> state-with-owner
                                                 (join source {:request_id (request-id!)
                                                               :peer_id    new-owner-id
                                                               :identity   new-owner-identity})
                                                 (first)
                                                 (ready source {:peer_id new-owner-id}))
                    activity (state/activity final-state activity-id)
                    new-owner (peers/by-id final-state new-owner-id)]
                (just? ready-msgs [(msg/joined-activity final-state
                                                        source
                                                        (peers/by-id final-state new-owner-id)
                                                        activity
                                                        {:context-override "context-override"})
                                   (msg/owner-changed source helper-1-id activity-id new-owner)
                                   (msg/owner-changed source snoop-id activity-id new-owner)])))))))))


(deftest helper-reloading
  (let [{:keys [activity-state source helper-1-id owner-id peer-id-1 activity-id snoop-id]} (prepare-test-activity)]
    (testing "helper sending a reload message receives a token"
      (let [request-id (request-id!)
            [state-with-reload msgs] (-> activity-state
                                         (activities/reload source {:request_id request-id :peer_id helper-1-id}))
            token (get-in (first msgs) [:body :token])]
        (just? msgs [(m/token constants/activity-domain-uri source request-id helper-1-id token)])
        (testing "then when the helper leaves the activity wont be destroyed"
          (let [request-id (request-id!)
                leave-rq {:request_id request-id :peer_id helper-1-id :reason "reason" :reason_uri "reason-uri"}
                [state-without-helper msgs] (-> state-with-reload
                                                (leave source leave-rq))
                reason (request->Reason leave-rq)]
            (just? msgs [(msg/activity-left source owner-id helper-1-id activity-id reason)
                         (msg/activity-left source snoop-id helper-1-id activity-id reason)

                         (msg/peer-removed source owner-id helper-1-id reason)
                         (msg/peer-removed source helper-1-id owner-id reason)

                         (msg/peer-removed source peer-id-1 helper-1-id reason)
                         (msg/peer-removed source helper-1-id peer-id-1 reason)

                         (msg/peer-removed source snoop-id helper-1-id reason)
                         (msg/peer-removed source helper-1-id snoop-id reason)

                         (msg/success source request-id helper-1-id)])
            (testing "then the helper can rejoin back with the token and send a ready message"
              (let [new-helper-identity (gen-identity)
                    [state-with-helper messages] (-> state-without-helper
                                                     (global/handle-hello source
                                                                          {:type           :hello
                                                                           :request_id     1
                                                                           :identity       new-helper-identity
                                                                           :authentication {"method" "gateway-token" "token" token}}
                                                                          nil
                                                                          nil
                                                                          environment))
                    new-helper-id (get-in (first messages) [:body :peer_id])

                    [final-state ready-msgs] (-> state-with-helper
                                                 (join source {:request_id (request-id!)
                                                               :peer_id    new-helper-id
                                                               :identity   new-helper-identity})
                                                 (first)
                                                 (ready source {:peer_id new-helper-id}))
                    activity (state/activity final-state activity-id)
                    new-helper (peers/by-id final-state new-helper-id)]
                (just? ready-msgs [(msg/joined-activity final-state
                                                        source
                                                        new-helper
                                                        activity
                                                        {:context-override "context-override"})
                                   (msg/activity-joined source owner-id new-helper activity-id)
                                   (msg/activity-joined source snoop-id new-helper activity-id)])))))))))


(deftest non-activity-member-reloading
  (let [{:keys [activity-state source helper-1-id owner-id peer-id-1 activity-id snoop-id]} (prepare-test-activity)]
    (testing "snoop sending a reload message receives a token"
      (let [request-id (request-id!)
            [state-with-reload msgs] (-> activity-state
                                         (activities/reload source {:request_id request-id :peer_id snoop-id}))
            token (get-in (first msgs) [:body :token])]
        (just? msgs [(m/token constants/activity-domain-uri source request-id snoop-id token)])
        (testing "then when the snoop leaves the activity wont be destroyed and the contexts wont be mangled"
          (let [request-id (request-id!)
                leave-rq {:request_id request-id :peer_id snoop-id :reason "reason" :reason_uri "reason-uri"}
                reason (request->Reason leave-rq)
                [state-without-snoop msgs] (-> state-with-reload
                                               (leave source leave-rq))]
            (just? msgs [(msg/peer-removed source owner-id snoop-id reason)
                         (msg/peer-removed source snoop-id owner-id reason)

                         (msg/peer-removed source peer-id-1 snoop-id reason)
                         (msg/peer-removed source snoop-id peer-id-1 reason)

                         (msg/peer-removed source snoop-id helper-1-id reason)
                         (msg/peer-removed source helper-1-id snoop-id reason)

                         (msg/success source request-id snoop-id)])
            (is (empty? (->> (gs/contexts state-without-snoop)
                             (filter #(nil? (:id %))))))


            (testing "the snoop can rejoin, but cannot send a ready message"
              (let [new-snoop-identity (gen-identity)
                    [state-with-snoop messages] (-> state-without-snoop
                                                    (global/handle-hello source
                                                                         {:type           :hello
                                                                          :request_id     1
                                                                          :identity       new-snoop-identity
                                                                          :authentication {"method" "gateway-token" "token" token}}
                                                                         nil
                                                                         nil
                                                                         environment))
                    new-snoop-id (get-in (first messages) [:body :peer_id])]
                (is (thrown? Exception (-> state-with-snoop
                                           (join source {:request_id (request-id!)
                                                         :peer_id    new-snoop-id
                                                         :identity   new-snoop-identity})
                                           (first)
                                           (ready source {:peer_id new-snoop-id}))))))))))))


(deftest peer-leaving-activity-joining-back
  (let [{:keys [activity-state source helper-1-id owner-id peer-id-1 activity-id snoop-id]} (prepare-test-activity)]
    (testing "peer that leaves and activity can join back"
      (let [request-id (request-id!)
            activity (state/activity activity-state activity-id)
            [state msgs] (-> activity-state
                             (activities/leave-activity* source {:request_id request-id :peer_id helper-1-id})
                             (first)
                             (activities/join-activity source {:request_id  request-id
                                                               :peer_id     helper-1-id
                                                               :target_id   helper-1-id
                                                               :activity_id activity-id}))
            helper-1 (peers/by-id state helper-1-id)]
        (just? msgs [(msg/activity-joined source owner-id helper-1 activity-id)
                     (msg/activity-joined source snoop-id helper-1 activity-id)
                     (msg/joined-activity state
                                          source
                                          (peers/by-id state helper-1-id)
                                          activity
                                          {:context-override "context-override"})
                     (msg/success source
                                  request-id
                                  helper-1-id)])))))

(deftest destroying-activity
  (let [{:keys [activity-state source helper-1-id owner-id peer-id-1 activity-id snoop-id]} (prepare-test-activity)]
    (testing "an activity can be destroyed by a peer that can see it"
      (let [request-id (request-id!)
            request {:request_id  request-id
                     :peer_id     peer-id-1
                     :activity_id activity-id
                     :reason_uri  "explicit"
                     :reason      "cause i want to"}
            reason (request->Reason request)

            [state messages] (-> activity-state
                                 (activities/destroy-activity source request))]
        (just? messages
               [(msg/dispose-peer source helper-1-id nil reason)
                (msg/dispose-peer source owner-id nil reason)
                (msg/activity-destroyed source owner-id activity-id reason)
                (msg/activity-destroyed source helper-1-id activity-id reason)
                (msg/activity-destroyed source snoop-id activity-id reason)
                (msg/success source
                             request-id
                             peer-id-1)])
        (is (nil? (gs/contexts state)))))
    (testing "the owner leaving should destroy the activity"
      (let [request-id (request-id!)
            leave-rq {:request_id request-id :peer_id owner-id}
            reason (request->Reason leave-rq)
            [state messages] (-> activity-state
                                 (activities/leave-activity* source leave-rq))]

        (spec-valid? ::ss/state state)
        (is (nil? (state/activities state)) "the activity has been removed")
        (is (nil? (gs/contexts state)) "the activity context has been removed")
        (just? messages [(msg/dispose-peer source helper-1-id nil reason)
                         (msg/dispose-peer source owner-id nil reason)
                         (msg/activity-destroyed source owner-id activity-id reason)
                         (msg/activity-destroyed source helper-1-id activity-id reason)
                         (msg/activity-destroyed source snoop-id activity-id reason)
                         (msg/success source
                                      request-id
                                      owner-id)])))))

(deftest activity-contexts
  (let [{:keys [activity-state source context-id owner-id helper-1-id]} (prepare-test-activity)]
    (testing "When one participant modifies the context, the other participants receive events"
      (let [delta {:updated {:testing "value"}}
            update-r (request-id!)
            upfn (partial contexts/update-ctx global-constants/global-domain-uri)
            update-rq {:request_id update-r
                   :peer_id    owner-id
                   :context_id context-id
                   :delta      delta}
            [state messages] (-> activity-state
                                 (upfn source update-rq))]
        (is (= messages [(gmsg/context-updated source
                                               helper-1-id
                                               owner-id
                                               context-id
                                               delta)
                         (m/success global-constants/global-domain-uri
                                    source
                                    update-r
                                    owner-id)
                         (m/broadcast {:type    :peer
                                       :peer-id owner-id
                                       :node    node-id}
                                      (assoc update-rq
                                        :type :update-context
                                        :version (msgs->ctx-version messages)))]))))
    (testing "The activity context is not announced to a peer that joins the domain"
      (let [messages (-> activity-state
                         (global/authenticated source
                                               {:request-id      (request-id!)
                                                :remote-identity {"application" "test" "instance" "id1"}
                                                :user            "user 1"}
                                               nil
                                               environment)
                         (second))]
        (is (->> messages
                 (map :body)
                 (map :type)
                 (filter #{:context-added})
                 (empty?)))))))

(deftest activity-visibility
  (testing "joining peer with proper visibility can see the existing activities"
    (let [{:keys [activity-state
                  source
                  peer-id-1
                  activity-id
                  activity-types
                  user
                  factories]} (prepare-test-activity)
          new-peer-identity (assoc (gen-identity) :user user)
          new-peer-id (peer-id!)

          request-id (request-id!)
          [state messages] (-> activity-state
                               (peers/ensure-peer-with-id source new-peer-id new-peer-identity nil nil)
                               (first)
                               (join source {:request_id request-id
                                             :peer_id    new-peer-id
                                             :identity   new-peer-identity}))

          [state sub-messages] (-> state
                                   (activities/subscribe source {:request_id     request-id
                                                                 :peer_id        new-peer-id
                                                                 :activity-types []}))]
      (just? (remove #(= :peer-added (get-in % [:body :type])) messages)
             [(msg/activity-types-added source
                                        new-peer-id
                                        activity-types)
              (msg/peer-factories-added source
                                        new-peer-id
                                        peer-id-1
                                        factories)
              (msg/success source
                           request-id
                           new-peer-id)])
      (just? sub-messages [(msg/activity-created state
                                                 source
                                                 new-peer-id
                                                 (state/activity state activity-id))
                           (msg/success source
                                        request-id
                                        new-peer-id)])))
  (testing "joining peer with bad visibility cant see the existing activities"
    (let [{:keys [activity-state source user]} (prepare-test-activity)
          new-peer-identity (assoc (gen-identity) :user (str user "1"))
          new-peer-id (peer-id!)

          request-id (request-id!)
          messages (-> activity-state
                       (peers/ensure-peer-with-id source new-peer-id new-peer-identity nil nil)
                       (first)
                       (join source {:request_id request-id
                                     :peer_id    new-peer-id
                                     :identity   new-peer-identity})
                       (second))]
      (is (= messages [(msg/success source
                                    request-id
                                    new-peer-id)])))))

(deftest external-peer-join
  (testing "a peer can request to join an activity on its own"
    (let [{:keys [activity-state
                  source
                  owner-id
                  helper-1-id
                  peer-id-1
                  activity-id
                  snoop-id]} (prepare-test-activity)

          target-id (peer-id!)
          target-identity (gen-identity)
          join-r-id (request-id!)

          [state messages] (-> activity-state
                               (peers/ensure-peer-with-id source target-id target-identity nil nil)
                               (first)
                               (core-state/join-domain target-id :activity-domain nil)
                               (activities/join-activity source {:request_id  join-r-id
                                                                 :peer_id     peer-id-1
                                                                 :target_id   target-id
                                                                 :activity_id activity-id}))
          activity (state/activity state activity-id)
          target (peers/by-id state target-id)]
      (just? messages [(msg/joined-activity state
                                            source
                                            target
                                            activity
                                            {:context-override "context-override"})
                       (msg/activity-joined source
                                            snoop-id
                                            target
                                            activity-id)
                       (msg/activity-joined source
                                            owner-id
                                            target
                                            activity-id)
                       (msg/activity-joined source
                                            helper-1-id
                                            target
                                            activity-id)
                       (msg/success source
                                    join-r-id
                                    peer-id-1)])
      ;(is (= state {}))
      ))
  (testing "peer that is already in an activity cannot be joined to another one"
    (let [{:keys [activity-state source peer-id-1 activity-id]} (prepare-test-activity)

          target-id (peer-id!)
          target-identity (gen-identity)
          join-r-id (request-id!)

          create-r {:request_id      (request-id!)
                    :peer_id         peer-id-1
                    :activity_type   "activity-type-1"
                    :initial_context {:context-override "context-override"}}

          [state-activity-requested peer-request-m] (-> activity-state
                                                        (peers/ensure-peer-with-id source target-id target-identity nil nil)
                                                        (first)
                                                        (core-state/join-domain target-id :activity-domain nil)
                                                        (activities/join-activity source {:request_id  join-r-id
                                                                                          :peer_id     peer-id-1
                                                                                          :target_id   target-id
                                                                                          :activity_id activity-id})
                                                        (first)
                                                        (activities/create-activity source create-r nil))
          initiated-m (first peer-request-m)
          second-activity-id (get-in initiated-m [:body :activity_id])]
      (is (thrown? #?(:clj  Exception
                      :cljs js/Error)
                   (-> state-activity-requested
                       (activities/join-activity source {:request_id  join-r-id
                                                         :peer_id     peer-id-1
                                                         :target_id   target-id
                                                         :activity_id second-activity-id})
                       (second))))))
  (testing "external peer can join before there are any participants"
    (let [{:keys [created-state
                  source
                  peer-id-1
                  activity-id]} (prepare-test-activity)

          target-id (peer-id!)
          target-identity (gen-identity)
          join-r-id (request-id!)

          state (-> created-state
                    (peers/ensure-peer-with-id source target-id target-identity nil nil)
                    (first)
                    (core-state/join-domain target-id :activity-domain nil)
                    (activities/join-activity source {:request_id  join-r-id
                                                      :peer_id     peer-id-1
                                                      :target_id   target-id
                                                      :activity_id activity-id})
                    (first))
          activity (state/activity state activity-id)]
      (is (set? (get activity :ready-members))))))

(deftest creating-peer-for-activity
  (testing "peer creation receives activity information and peer type and name"
    (let [{:keys [created-state
                  activity-state
                  source
                  context-id
                  peer-id-1
                  activity-id]} (prepare-test-activity)

          factories [
                     {:id            4
                      :peer_type     "peer-type-4"
                      :configuration {:config-key "config-value"}}]
          factory-reg-r {:request_id (request-id!)
                         :peer_id    peer-id-1
                         :factories  factories}
          create-request-id (request-id!)
          [state messages] (-> activity-state
                               (factories/add source factory-reg-r)
                               (first)
                               (core/handle-request source {:domain      constants/activity-domain-uri
                                                            :type        :create-peer
                                                            :peer_id     peer-id-1
                                                            :peer_type   "peer-type-4"
                                                            :peer_name   "peer-name-4"
                                                            :activity_id activity-id
                                                            :request_id  create-request-id}))
          activity (state/activity state activity-id)]
      (is (= (count messages) 2))
      (let [[peer-request-m success-m] messages]
        (message-type? peer-request-m :peer-requested)
        (is (= (get-in peer-request-m [:body :peer_name])
               "peer-name-4"))
        (is (= (get-in peer-request-m [:body :activity])
               {:context_id      context-id
                :id              activity-id
                :initial-context {:context-override "context-override"}
                :type            (:type activity)}))
        (message-type? success-m :success)
        (let [created-token (get-in peer-request-m [:body :gateway_token])
              created-identity (gen-identity)
              [state messages] (-> state
                                   (global/handle-hello source
                                                        {:type           :hello
                                                         :request_id     (request-id!)
                                                         :identity       created-identity
                                                         :authentication {"method" "gateway-token" "token" created-token}}
                                                        nil
                                                        nil
                                                        environment))

              created-id (get-in (first messages) [:body :peer_id])
              [state messages] (-> state
                                   (join source {:request_id (request-id!)
                                                 :peer_id    created-id
                                                 :identity   created-identity})
                                   (first)
                                   (ready source {:peer_id created-id}))
              [state messages] (-> state
                                   (activities/join-activity source {:request_id  (request-id!)
                                                                     :peer_id     created-id
                                                                     :target_id   created-id
                                                                     :peer_type   "peer-type-4"
                                                                     :peer_name   "peer-name-4"
                                                                     :activity_id activity-id}))

              joined-msg (last (butlast messages))]
          (message-type? joined-msg :joined)
          (is (= (select-keys (joined-msg :body) [:peer_name :peer_type])
                 {:peer_name "peer-name-4", :peer_type "peer-type-4"}))
          )))))

(deftest test-activity-overrides
  (let [source (ch->src "source")
        empty-state (new-state)

        peer-id-1 (peer-id!)
        user "user 1"
        identity-1 (assoc (gen-identity) :user user)

        activity-type-1 {"name"         "JsonEditorActivity"
                         "owner_type"   {"type"          "JsonEditor"
                                         "name"          "JsonEditor1"
                                         "configuration" {"type"                                                            "JsonEditor"
                                                          "name"                                                            "JsonEditor1"
                                                          "testConfigFromRegisterActivityForJsonEditor1"                    "testConfigFromRegisterActivityValueForJsonEditor1",
                                                          "testConfigFromRegisterActivityForJsonEditor1-0.3950336861932413" "testConfigFromRegisterActivityValueForJsonEditor1-0.3950336861932413"}}
                         "helper_types" [{
                                          "type"          "JsonEditor"
                                          "name"          "JsonEditor2"
                                          "configuration" {"type"                                                             "JsonEditor"
                                                           "name"                                                             "JsonEditor2"
                                                           "testConfigFromRegisterActivityForJsonEditor2"                     "testConfigFromRegisterActivityValueForJsonEditor2",
                                                           "testConfigFromRegisterActivityForJsonEditor2-0.49041988856445484" "testConfigFromRegisterActivityValueForJsonEditor2-0.49041988856445484"}}]}

        activity-reg-r {:request_id (request-id!)
                        :type       :add-types
                        :peer_id    peer-id-1
                        :types      [activity-type-1]}

        factory-reg-r {:request_id 1
                       :peer_id    peer-id-1
                       :factories  [{:id        1
                                     :peer_type "JsonEditor"}]}

        state-with-types (-> empty-state
                             (peers/ensure-peer-with-id source peer-id-1 identity-1 nil nil)
                             (first)
                             (core-state/join-domain peer-id-1 :activity-domain nil)
                             (core/handle-request source activity-reg-r)
                             (first)
                             (factories/add source factory-reg-r)
                             (first))

        create-r {:type            :create
                  :request_id      (request-id!)
                  :peer_id         peer-id-1
                  :activity_type   "JsonEditorActivity"
                  :initial_context {:context-override "context-override"}
                  :configuration   [{"type"          "JsonEditor"
                                     "configuration" {"type"                                        "JsonEditor"
                                                      "testConfigFromInitiateActivityForJsonEditor" "testConfigFromInitiateActivityValueForJsonEditor"}}
                                    {"type"          "JsonEditor"
                                     "name"          "JsonEditor2"
                                     "configuration" {"type"                                         "JsonEditor"
                                                      "name"                                         "JsonEditor2"
                                                      "testConfigFromInitiateActivityForJsonEditor2" "testConfigFromInitiateActivityValueForJsonEditor20.6206626309670655"
                                                      "testConfigFromRegisterActivityForJsonEditor2" "testConfigFromRegisterActivityJsonEditor2ValueOverride"}}]}

        [state-activity-requested peer-request-m] (-> state-with-types
                                                      (core/handle-request source create-r))]

    (spec-valid? ::ss/state state-activity-requested)
    (is (= 3 (count peer-request-m)))
    (let [[initiated-m owner-m helper-1-m] peer-request-m
          activity-id (get-in initiated-m [:body :activity_id])]
      (is (some? activity-id))
      (message-type? initiated-m :initiated)
      (message-type? owner-m :peer-requested)
      (message-type? helper-1-m :peer-requested)

      (testing "the configuration overrides have been applied in the peer request message"
        (is (= (get-in owner-m [:body :configuration]) {"name"                                                            "JsonEditor1"
                                                        "testConfigFromInitiateActivityForJsonEditor"                     "testConfigFromInitiateActivityValueForJsonEditor"
                                                        "testConfigFromRegisterActivityForJsonEditor1"                    "testConfigFromRegisterActivityValueForJsonEditor1"
                                                        "testConfigFromRegisterActivityForJsonEditor1-0.3950336861932413" "testConfigFromRegisterActivityValueForJsonEditor1-0.3950336861932413"
                                                        "type"                                                            "JsonEditor"}))
        (is (= (get-in helper-1-m [:body :configuration]) {"name"                                                             "JsonEditor2"
                                                           "testConfigFromInitiateActivityForJsonEditor"                      "testConfigFromInitiateActivityValueForJsonEditor"
                                                           "testConfigFromInitiateActivityForJsonEditor2"                     "testConfigFromInitiateActivityValueForJsonEditor20.6206626309670655"
                                                           "testConfigFromRegisterActivityForJsonEditor2"                     "testConfigFromRegisterActivityJsonEditor2ValueOverride"
                                                           "testConfigFromRegisterActivityForJsonEditor2-0.49041988856445484" "testConfigFromRegisterActivityValueForJsonEditor2-0.49041988856445484"
                                                           "type"                                                             "JsonEditor"}))))))