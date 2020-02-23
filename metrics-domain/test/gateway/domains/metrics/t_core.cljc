(ns gateway.domains.metrics.t-core
  (:require [gateway.domains.metrics.core :as core]
            [gateway.metrics.core.def :as metrics]
            [gateway.t-helpers :refer [gen-identity ch->src peer-id! request-id! new-state]]
            [gateway.state.peers :as peers]

            [clojure.test :refer :all]))

(deftest definition-keywordize
  (testing "whether a JSON definition message is properly keywordized"
    (is (= {:name        "/State",
            :type        "object",
            :composite   {
                          "Description" {
                                         :type        "string",
                                         :description ""
                                         },
                          "Value"       {
                                         :type        "number",
                                         :description ""
                                         }
                          },
            :description "System state",
            :context     {}}
           (core/keywordize-definition {"name"        "/State",
                                        "type"        "object",
                                        "composite"   {
                                                       "Description" {
                                                                      "type"        "string",
                                                                      "description" ""
                                                                      },
                                                       "Value"       {
                                                                      "type"        "number",
                                                                      "description" ""
                                                                      }
                                                       },
                                        "description" "System state",
                                        "context"     {}
                                        })))))

(defn ->factory
  [repositories defined published]
  (reify metrics/RepositoryFactory
    (repository [this peer-identity opts]
      (let [repo (reify metrics/Repository
                   (start! [this] this)
                   (stop! [this] this)
                   (add! [this definitions]
                     (swap! defined #(reduce
                                       (fn [m d] (conj m (:name d)))
                                       %
                                       definitions))
                     this)
                   (publish! [this data-points]
                     (swap! published #(reduce
                                         (fn [m d] (conj m (:name d)))
                                         %
                                         data-points))
                     this)
                   (status! [this publisher-status]))]
        (swap! repositories #(assoc % peer-identity repo))
        repo))
    (shutdown [this])))

(deftest filtered-publishing
  (let [repositories (atom {})
        defined (atom #{})
        published (atom #{})

        factory (->factory repositories defined published)
        filters {:publishers [{:publisher {:system "a"}
                               :metrics   {:whitelist ["m"]}}]}

        peer-id-1 (peer-id!)
        source-1 (ch->src "peer-1")
        identity-1 (assoc (gen-identity)
                     :application "app1"
                     :system "a")
        state (-> (new-state)
                  (peers/ensure-peer-with-id source-1 peer-id-1 identity-1 nil nil)
                  (first)
                  (core/join-peer source-1
                                  {:request_id (request-id!)
                                   :peer_id    peer-id-1
                                   :identity   identity-1}
                                  nil)
                  (first))]

    (is (empty? @repositories))
    (-> state
        (core/define-metrics source-1
                             {:peer_id peer-id-1
                              :metrics [{"name"        "m",
                                         "type"        "string",
                                         "description" "allowed metric",
                                         "context"     {}
                                         }
                                        {"name"        "n",
                                         "type"        "string",
                                         "description" "forbidden metric",
                                         "context"     {}
                                         }]}
                             [factory]
                             filters)
        (first)
        (core/publish-metrics source-1
                              {:peer_id peer-id-1
                               :values  [{"name"  "m",
                                          "value" "1"}
                                         {"name"  "n",
                                          "value" "2"}]}
                              filters))
    (is (= 1 (count @repositories)))
    (is (= #{"m"} @defined))
    (is (= #{"m"} @published))))