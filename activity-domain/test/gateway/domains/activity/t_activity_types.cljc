(ns gateway.domains.activity.t-activity-types
  (:require
    [clojure.test :refer :all]

    #?(:cljs [gateway.t-macros :refer-macros [spec-valid? just?]])
    ;#?(:cljs [cljs.spec.test.alpha :as stest])

    #?(:clj [gateway.t-macros :refer [spec-valid? just?]])
    #?(:clj [clojure.spec.test.alpha :as stest])

    [gateway.state.core :as core-state]
    [gateway.domains.activity.state :as state]

    [gateway.state.peers :as peers]

    [gateway.domains.activity.core :refer [join]]
    [gateway.t-helpers :refer [gen-identity ch->src peer-id! request-id! new-state]]

    [gateway.domains.activity.activities :as activities]

    [gateway.domains.activity.spec.activities-instr :as activities-instr]
    [gateway.state.spec.state :as ss]

    [gateway.domains.activity.messages :as msg]
    [gateway.domains.activity.constants :as constants]))

#?(:clj
   (stest/instrument))

(deftest test-adding-types
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
        request-id (request-id!)

        [state-with-types messages] (-> (new-state)
                                        (peers/ensure-peer-with-id source peer-id-1 identity-1 nil nil)
                                        (first)
                                        (core-state/join-domain peer-id-1 :activity-domain nil)
                                        (activities/add-types source request-id peer-id-1 [activity-type-1 activity-type-2]))]
    (testing "Peer can add activity types"
      (spec-valid? ::ss/state state-with-types)
      (is (= (dissoc state-with-types :signature-key)
             (merge (dissoc (new-state) :signature-key)
                    {:domains        {:activity-domain #{peer-id-1}}
                     :activity-types {user {(:name activity-type-1) activity-type-1
                                            (:name activity-type-2) activity-type-2}}
                     :identities     {identity-1 peer-id-1}
                     :peers          {peer-id-1 (peers/map->Peer {:activity-domain {}
                                                                  :id              peer-id-1
                                                                  :identity        identity-1
                                                                  :source          source})}
                     :users          {"user 1" #{peer-id-1}}})))
      (is (= messages [(msg/activity-types-added source
                                                 peer-id-1
                                                 [activity-type-1 activity-type-2])
                       (msg/success source
                                    request-id
                                    peer-id-1)])))
    (testing "Peers that join later on will receive the existing activity types"
      (let [peer-id-2 (peer-id!)
            identity-2 (assoc (gen-identity) :user user)
            join-r-id (request-id!)
            [state messages] (-> state-with-types
                                 (peers/ensure-peer-with-id source peer-id-2 identity-2 nil nil)
                                 (first)
                                 (join source {:request_id join-r-id
                                               :peer_id    peer-id-2
                                               :identity   identity-2}))]
        (spec-valid? ::ss/state state)
        (is (just? messages [(msg/peer-added source peer-id-1 peer-id-2 identity-2 {:local true})
                             (msg/peer-added source peer-id-2 peer-id-1 identity-1 {:local true})

                             (msg/activity-types-added source
                                                       peer-id-2
                                                       #{activity-type-1 activity-type-2})
                             (msg/success source
                                          join-r-id
                                          peer-id-2)]))))

    (testing "Removing an activity type is announced "
      (let [remove-r-id (request-id!)
            [state messages] (-> state-with-types
                                 (activities/remove-types source {:request_id remove-r-id
                                                                  :peer_id    peer-id-1
                                                                  :types      [(:name activity-type-1)
                                                                               (:name activity-type-2)
                                                                               "missing-name"]})
                                 )]
        (spec-valid? ::ss/state state)
        (is (= messages [(msg/activity-types-removed source
                                                     peer-id-1
                                                     #{(:name activity-type-1) (:name activity-type-2)})
                         (msg/success source
                                      remove-r-id
                                      peer-id-1)]))))
    ))

(deftest activity-type-visibility
  (let [source (ch->src "source")
        user "my-user"

        [peer-id-1 peer-id-2] (repeatedly 2 peer-id!)
        identity-1 (merge (gen-identity) {:user user})
        identity-2 (gen-identity)

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
        request-id (request-id!)

        [state-with-types messages] (-> (new-state)
                                        (peers/ensure-peer-with-id source peer-id-1 identity-1 nil nil)
                                        (first)
                                        (peers/ensure-peer-with-id source peer-id-2 identity-2 nil nil)
                                        (first)
                                        (core-state/join-domain peer-id-1 :activity-domain nil)
                                        (core-state/join-domain peer-id-2 :activity-domain nil)
                                        (activities/add-types source request-id peer-id-1 [activity-type-1 activity-type-2]))]
    (testing "peers only receive add notification for the types that they can see"
      (spec-valid? ::ss/state state-with-types)
      (is (= (dissoc state-with-types :signature-key)
             (merge (dissoc (new-state) :signature-key)
                    {:domains        {:activity-domain #{peer-id-1 peer-id-2}}
                     :activity-types {user
                                      {(:name activity-type-1) activity-type-1
                                       (:name activity-type-2) activity-type-2}}
                     :identities     {identity-1 peer-id-1 identity-2 peer-id-2}
                     :peers          {peer-id-1 (peers/map->Peer {:activity-domain {}
                                                                  :id              peer-id-1
                                                                  :identity        identity-1
                                                                  :source          source})
                                      peer-id-2 (peers/map->Peer {:activity-domain {}
                                                                  :id              peer-id-2
                                                                  :identity        identity-2
                                                                  :source          source})}
                     :users          {"my-user" #{peer-id-1}
                                      :no-user  #{peer-id-2}}})))
      (is (= messages [(msg/activity-types-added source
                                                 peer-id-1
                                                 [activity-type-1 activity-type-2])
                       (msg/success source
                                    request-id
                                    peer-id-1)])))))