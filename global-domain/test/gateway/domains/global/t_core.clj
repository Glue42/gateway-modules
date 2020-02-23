(ns gateway.domains.global.t-core
  (:use clojure.test)
  (:require
    [gateway.t-macros :refer [spec-valid? just? error-msg?]]
    [gateway.node :as node]
    [gateway.local-node.core :as local-node]
    [gateway.domain :refer [Domain] :as domain]
    [gateway.state.spec.state :as ss]
    [gateway.domains.global.core :as global]
    [gateway.domains.global.constants :as constants]

    [gateway.t-helpers :refer [local-msg ch->src request-id! new-state node-id] :as hlp]
    [taoensso.timbre :as timbre]
    [clojure.core.async :as a]

    [gateway.state.peers :as peers]

    [clojure.spec.test.alpha :as stest]
    [gateway.common.tokens :as tokens]
    [gateway.basic-auth.core :as basic]
    [gateway.auth.core :as auth]
    [gateway.common.peer-identity :as peer-identity]
    [gateway.domains.global.messages :as msg]
    [gateway.common.net :as net]))

(timbre/set-level! :info)

(stest/instrument)

(defn ->auth []
  {:default   :basic
   :available {:basic (basic/authenticator {})}})

(defn stop-auth [auths]
  (doseq [a (vals (:available auths))]
    (auth/stop a)))

(deftest sends-welcome
  (let [auth (->auth)]
    (try
      (let [global-domain (global/global-domain auth nil nil {:local-ip net/local-ip})
            node (local-node/local-node [global-domain] nil)
            results (promise)
            rcv (hlp/receive-n 1 results)

            rid (request-id!)
            id (hlp/gen-identity)
            auth (hlp/gen-user-auth)
            hello {:type :hello :domain constants/global-domain-uri :request_id rid :identity id :authentication auth}]
        (node/message node (local-msg rcv hello))
        (let [response (first (deref results 1000 nil))
              user (get auth "login")]
          (is (= (dissoc response :peer_id :options)
                 {:domain            constants/global-domain-uri
                  :type              :welcome
                  :request_id        rid
                  :resolved_identity (-> (peer-identity/keywordize-id id)
                                         (assoc :user user
                                                :login user
                                                :machine net/local-ip))
                  :available_domains [(domain/info global-domain)]})))
        (node/close node))
      (finally
        (stop-auth auth)))))

(deftest error-on-second-hello
  (with-redefs [global/context-compatibility (fn [state _ _] [state nil])]
    (let [auth (->auth)]
      (try
        (let [node (local-node/local-node [(global/global-domain auth nil nil {:local-ip net/local-ip})] nil)
              rid (request-id!)
              hello {:domain         constants/global-domain-uri
                     :type           :hello
                     :request_id     rid
                     :identity       (hlp/gen-identity)
                     :authentication (hlp/gen-user-auth)}
              results (promise)
              rcv (hlp/receive-n 2 results)]
          (node/message node (local-msg rcv hello))         ;; first hello
          (node/message node (local-msg rcv hello))         ;; second hello
          (is (error-msg? constants/global-already-seen {:body (second (deref results 1000 nil))}))
          (node/close node))
        (finally
          (stop-auth auth))))))


(defn- test-domain
  [domain-uri results]
  (reify Domain
    (init [this state] state)
    (destroy [this state] state)
    (info [this] {:uri domain-uri :description "" :version 1})
    (handle-message
      [this state request]
      (deliver results request)
      [state nil])))

(deftest join-forwarding
  (let [auth (->auth)]
    (try
      (let [
            test-domain-uri "com.tick42.domains.test"
            dom-results (promise)

            node (local-node/local-node [(global/global-domain auth nil nil {:local-ip net/local-ip})
                                         (test-domain test-domain-uri dom-results)] nil)
            identity (hlp/gen-identity)
            rid (request-id!)
            hello {:domain constants/global-domain-uri :type :hello :request_id rid :identity identity :authentication (hlp/gen-user-auth)}
            join-request {:domain       constants/global-domain-uri
                          :type         :join
                          :request_id   rid
                          :restrictions nil
                          :destination  test-domain-uri}
            forwarded-join (-> join-request
                               (assoc :identity (-> (peer-identity/keywordize-id identity)
                                                    (assoc :user "user"
                                                           :login "user"
                                                           :machine net/local-ip)))
                               (assoc :type ::domain/join))
            results (promise)
            rcv (hlp/receive-n 1 results)]
        (node/message node (local-msg rcv hello))
        (let [welcome (first (deref results 1000 nil))
              peer-id (:peer_id welcome)]
          (is (= (:type welcome) :welcome))
          (node/message node (local-msg rcv (assoc join-request :peer_id peer-id)))
          (is (= (deref dom-results 1000 nil)
                 (local-msg rcv (assoc forwarded-join :peer_id peer-id))))
          (node/close node)))
      (finally
        (stop-auth auth)))))




(deftest leave-forwarding
  (let [auth (->auth)]
    (try

      (let [
            test-domain-uri "com.tick42.domains.test"
            dom-results (promise)

            node (local-node/local-node [(global/global-domain auth nil nil {:local-ip net/local-ip})
                                         (test-domain test-domain-uri dom-results)] nil)

            hello {:domain constants/global-domain-uri :type :hello :request_id (request-id!) :identity (hlp/gen-identity) :authentication (hlp/gen-user-auth)}
            leave-rq {:domain constants/global-domain-uri :type :leave :request_id (request-id!) :destination test-domain-uri}
            results (promise)
            rcv (hlp/receive-n 1 results)]
        (node/message node (local-msg rcv hello))
        (let [welcome (first (deref results 1000 nil))
              peer-id (:peer_id welcome)]
          (is (= (:type welcome) :welcome))
          (node/message node (local-msg rcv (assoc leave-rq :peer_id peer-id)))
          (is (= (deref dom-results 1000 nil)
                 (local-msg rcv (-> leave-rq
                                    (assoc :peer_id peer-id)
                                    (assoc :type ::domain/leave)))))
          (node/close node)))
      (finally
        (stop-auth auth)))))

(def environment {:local-ip net/local-ip})

(defn authenticated
  [state source request]
  (global/authenticated state source request nil environment))

(deftest global-domain-state
  (let [id1 {"application" "test" "instance" "id1"}
        id2 {"application" "test" "instance" "id2"}
        id1-user (assoc id1 :user "user")
        id2-user (assoc id2 :user "user")

        source (ch->src nil)]
    (testing "there can be multiple peers with the same source"
      (let [[state msgs] (-> (new-state)
                             (authenticated source {:request_id (request-id!) :remote-identity id1 :user "user"}))
            peer-id-1 (get-in (first msgs) [:body :peer_id])
            [state msgs] (-> state
                             (authenticated source {:request_id (request-id!) :remote-identity id2 :user "user"}))
            peer-id-2 (get-in (first msgs) [:body :peer_id])

            r-id1 (-> (peer-identity/keywordize-id id1-user)
                      (assoc :machine net/local-ip))
            r-id2 (-> (peer-identity/keywordize-id id2-user)
                      (assoc :machine net/local-ip))

            expected {:identities {r-id1 peer-id-1
                                   r-id2 peer-id-2}
                      :peers      {peer-id-1 (peers/map->Peer {:id       peer-id-1
                                                               :identity r-id1
                                                               :source   source
                                                               :options  {:context-compatibility-mode? true}})
                                   peer-id-2 (peers/map->Peer {:id       peer-id-2
                                                               :identity r-id2
                                                               :source   source
                                                               :options  {:context-compatibility-mode? true}})}
                      :users      {"user" #{peer-id-1 peer-id-2}}}]
        (spec-valid? ::ss/state state)
        (is (= (dissoc state :ids :signature-key) expected))))
    (testing "when a source is removed all its peers are removed as well"
      (let [state (-> (new-state)
                      (authenticated source {:request_id (request-id!) :remote-identity id1})
                      (first)
                      (authenticated source {:request_id (request-id!) :remote-identity id2})
                      (first)
                      (global/source-removed source nil)
                      (first))]
        (spec-valid? ::ss/state state)
        (is (= (dissoc state :ids :signature-key)
               {:identities {}, :users {}, :peers {} :services #{}}))))))

(deftest gw-token-authentication
  (let [user {:user "user"}
        id1 (merge {"application" "test" "instance" "id1"} user)
        id2 {"application" "application-2" "instance" "spawned"}

        source (ch->src nil)

        [state msgs] (-> (new-state)
                         (authenticated source {:request_id (request-id!) :remote-identity id1 :user "user"}))
        peer-id-1 (get-in (first msgs) [:body :peer_id])

        token (tokens/for-authentication state (:identity (peers/by-id state peer-id-1)))

        [state msgs] (-> state
                         (global/handle-hello source
                                              {:type           :hello
                                               :request_id     1
                                               :identity       id2
                                               :authentication {"method" "gateway-token" "token" token}}
                                              nil
                                              nil
                                              environment))
        peer-id-2 (get-in (first msgs) [:body :peer_id])

        r-id1 (-> (peer-identity/keywordize-id id1)
                  (assoc :machine net/local-ip))
        r-id2 (-> (peer-identity/keywordize-id (merge id2 user))
                  (assoc :machine net/local-ip))]

    (spec-valid? ::ss/state state)
    (is (= (dissoc state :ids :signature-key)
           {:identities {r-id1 peer-id-1
                         r-id2 peer-id-2}
            :peers      {peer-id-1 (peers/map->Peer {:id       peer-id-1
                                                     :identity r-id1
                                                     :source   source
                                                     :options  {:context-compatibility-mode? true}})
                         peer-id-2 (peers/map->Peer {:id       peer-id-2
                                                     :identity r-id2
                                                     :source   source
                                                     :options  {:context-compatibility-mode? true}})}
            :users      {"user" #{peer-id-1 peer-id-2}}}))
    (is (= (first msgs) (msg/welcome source
                                     1
                                     peer-id-2
                                     []
                                     (-> (peer-identity/keywordize-id id2)
                                         (assoc :user "user"
                                                :machine net/local-ip))
                                     nil)))))

(deftest create-token
  (let [user {:user "user"}
        id1 (merge {"application" "test" "instance" "id1"} user)
        id2 {"application" "application-2" "instance" "spawned"}

        source (ch->src nil)

        [state msgs] (-> (new-state)
                         (authenticated source {:request_id (request-id!) :remote-identity id1 :user "user"}))
        peer-id-1 (get-in (first msgs) [:body :peer_id])

        [state-with-token msgs] (-> state
                                    (global/handle-request source {:request_id (request-id!) :peer_id peer-id-1 :type :create-token} nil nil nil))

        token (-> msgs
                  (first)
                  (get-in [:body :token]))]
    (is (some? token))
    (let [[state msgs] (-> state-with-token
                           (global/handle-hello source
                                                {:type           :hello
                                                 :request_id     1
                                                 :identity       id2
                                                 :authentication {"method" "gateway-token" "token" token}}
                                                nil
                                                nil
                                                environment))
          peer-id-2 (get-in (first msgs) [:body :peer_id])

          r-id1 (-> (peer-identity/keywordize-id id1)
                    (assoc :machine net/local-ip))
          r-id2 (-> (peer-identity/keywordize-id (merge id2 user))
                    (assoc :machine net/local-ip))]

      (spec-valid? ::ss/state state)
      (is (= (dissoc state :ids :signature-key)
             {:identities {r-id1 peer-id-1
                           r-id2 peer-id-2}
              :peers      {peer-id-1 (peers/map->Peer {:id       peer-id-1
                                                       :identity r-id1
                                                       :source   source
                                                       :options  {:context-compatibility-mode? true}})
                           peer-id-2 (peers/map->Peer {:id       peer-id-2
                                                       :identity r-id2
                                                       :source   source
                                                       :options  {:context-compatibility-mode? true}})}
              :users      {"user" #{peer-id-1 peer-id-2}}}))
      (is (= (first msgs) (msg/welcome source
                                       1
                                       peer-id-2
                                       []
                                       (-> (peer-identity/keywordize-id id2)
                                           (assoc :user "user"
                                                  :machine net/local-ip))
                                       nil))))))


(defn token
  [peer-id peer-m peer-c node]
  (let [tap-c (a/tap peer-m (a/chan 1000))]
    (node/message node (hlp/local-msg peer-c
                                      {:type       :create-token
                                       :request_id (hlp/request-id!)
                                       :peer_id    peer-id}))
    (let [[m c] (a/alts!! [tap-c (a/timeout 1000)])]
      (cond
        (nil? m) (when-not m (throw (ex-info "Timed out while waiting for a message" {})))
        (= (:type m) :token) (:token m)
        :else (do
                (timbre/error "Expected a token message, got something else" {:message m})
                (throw (ex-info "Expected a token message, got something else" {:message m})))))))


; TODO: move in the correct module
;(deftest test-hello-remote-token
;  (testing "a gateway authentication token created on one node is valid on another node"
;    (let [auth (->auth)]
;      (with-hazel-node [h1 [(global/global-domain auth nil nil {:local-ip net/local-ip}) (agm/agm-domain)]
;                        h2 [(global/global-domain auth nil nil {:local-ip net/local-ip}) (agm/agm-domain)]]
;
;                       (let [ch1 (a/chan 1000)
;                             m1 (a/mult ch1)
;
;                             ch2 (a/chan 1000)
;                             m2 (a/mult ch2)
;
;                             p1 (hello m1 ch1 h1)
;                             auth-token (token p1 m1 ch1 h1)
;                             _ (is (some? auth-token))
;
;                             p2 (hello m2 ch2 h2 {"method" "gateway-token" "token" auth-token})]
;
;                         (is (some? p2)))))))

(deftest global-domain-identity-with-qmarks
  (let [id1 {"application" "test" "instance" "id"}
        id2 {"application" "test" "instance" "?" "machine" "?"}
        id3 {"application" "test" "instance" "?" "machine" "?"}

        source (ch->src nil)]

    (testing "when one of the identities has qmarks"
      (let [[state msgs] (-> (new-state)
                             (authenticated source {:request_id (request-id!) :remote-identity id1 :user "user"}))
            msg-id-1 (:body (first msgs))
            peer-id-1 (:peer_id msg-id-1)
            resolved-id-1 (:resolved_identity msg-id-1)
            instance-id-1 (:instance resolved-id-1)

            [state msgs] (-> state
                             (authenticated source {:request_id (request-id!) :remote-identity id2 :user "user"}))
            msg-id-2 (:body (first msgs))
            peer-id-2 (:peer_id msg-id-2)
            resolved-id-2 (:resolved_identity msg-id-2)
            instance-id-2 (:instance resolved-id-2)
            machine-id-2 (:machine resolved-id-2)

            [state msgs] (-> state
                             (authenticated source {:request_id (request-id!) :remote-identity id3 :user "user"}))
            msg-id-3 (:body (first msgs))
            peer-id-3 (:peer_id msg-id-3)
            resolved-id-3 (:resolved_identity msg-id-3)
            instance-id-3 (:instance resolved-id-3)
            machine-id-3 (:machine resolved-id-3)]
        (spec-valid? ::ss/state state)

        (is (not= peer-id-1 peer-id-2 peer-id-3))
        (is (= instance-id-1 "id"))
        (is (= peer-id-2 instance-id-2 machine-id-2))
        (is (= peer-id-3 instance-id-3 machine-id-3))))))