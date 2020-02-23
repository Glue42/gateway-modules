(ns gateway.domains.context.t-core
  #?(:clj
     (:use clojure.test))
  (:require
    #?(:cljs [cljs.test :refer-macros [deftest is testing]])
    #?(:cljs [gateway.t-macros :refer-macros [just? error-msg?]])
    #?(:clj [gateway.t-macros :refer [just? error-msg?]])
    [gateway.reason :refer [reason]]
    [gateway.domains.global.constants :as c]
    [gateway.domains.context.core :as ctx]
    [gateway.domains.context.helpers :refer [context-id]]
    [gateway.domains.global.core :as gc]

    [gateway.domains.context.messages :as msg]
    [gateway.t-helpers :refer [gen-identity ch->src request-id! new-state node-id peer-id! local-peer remote-peer msgs->ctx-version]]
    [gateway.common.context.state :as state]
    [gateway.domains.context.constants :as constants]
    [gateway.common.context.constants :as cc]
    [gateway.common.commands :as commands]
    [gateway.state.peers :as peers]
    [gateway.common.messages :as m]
    [gateway.local-node.core :as local-node]))


(def environment {:local-ip "127.0.0.1"})
(defn authenticated
  [state source request]
  (gc/authenticated state source request nil environment))

(defn create-join
  [state source request]
  (let [{:keys [peer_id identity]} request]
    (-> state
        (peers/ensure-peer-with-id source peer_id identity nil (:options request))
        (first)
        (ctx/join source (assoc request :domain c/global-domain-uri
                                        :destination constants/context-domain-uri)))))

(deftest context-creation
  (testing "contexts can be created if missing"
    (let [
          id1 (gen-identity)
          source (ch->src "source")
          request-id (request-id!)
          context-name "my context"
          peer-id-1 (peer-id!)

          create-rq {:domain            constants/context-domain-uri
                     :request_id        request-id
                     :peer_id           peer-id-1
                     :name              context-name
                     :data              {}
                     :lifetime          "ownership"
                     :read_permissions  nil
                     :write_permissions nil}
          msgs (-> (new-state)
                   (create-join source {:request_id request-id :peer_id peer-id-1 :identity id1})
                   (first)
                   (ctx/create source create-rq)
                   (second))

          context-id (context-id :context-created msgs)]
      (just? msgs
             [(msg/context-created source request-id peer-id-1 context-id)
              (msg/context-added source peer-id-1 peer-id-1 context-id context-name)
              (m/broadcast {:type    :peer
                            :peer-id peer-id-1
                            :node    node-id}
                           (assoc create-rq
                             :type :create-context
                             :version (msgs->ctx-version msgs)))])))

  (testing "trying to create a context with an existing name subscribes for it"
    (let [
          [id1 id2] (repeatedly 2 gen-identity)
          [peer-id-1 peer-id-2] (repeatedly 2 peer-id!)

          source (ch->src "source")
          source-2 (ch->src "source-2")
          request-id (request-id!)
          context-name "my context"

          create-rq {:domain            constants/context-domain-uri
                     :request_id        request-id
                     :peer_id           peer-id-1
                     :name              context-name
                     :data              {:t 41}
                     :lifetime          "retained"
                     :read_permissions  nil
                     :write_permissions nil}
          [state msgs] (-> (new-state)
                           (create-join source {:request_id request-id :peer_id peer-id-1 :identity id1})
                           (first)
                           (create-join source-2 {:request_id request-id :peer_id peer-id-2 :identity id2})
                           (first)
                           (ctx/create source create-rq))
          context-id (context-id :context-created msgs)

          create-rq-2 {:domain            constants/context-domain-uri
                       :request_id        request-id
                       :peer_id           peer-id-2
                       :name              context-name
                       :data              {:t 42}
                       :lifetime          "retained"
                       :read_permissions  nil
                       :write_permissions nil}
          msgs (-> state
                   (ctx/create source create-rq-2)
                   (second))]
      (is (= msgs [(msg/subscribed-context source request-id peer-id-2 context-id {:t 41})]))))

  (testing "creating a context without a peer returns an error"
    ;; ghostwheel will throw an exception
    (is (thrown? #?(:clj  Exception
                    :cljs :default)
                 (let [request-id (request-id!)
                       source (ch->src "source")
                       msgs (-> (new-state)
                                (ctx/create source {:request_id        request-id
                                                    :name              "context-name"
                                                    :data              {:t 41}
                                                    :lifetime          "retained"
                                                    :read_permissions  nil
                                                    :write_permissions nil})
                                (second))]
                   (is (error-msg? constants/failure (first msgs))))))))


(deftest context-announcement
  (testing "when a context is created, its announced to all peers that can see it"
    (let [
          [id1 id2] (repeatedly 2 gen-identity)
          [peer-id-1 peer-id-2] (repeatedly 2 peer-id!)

          source (ch->src "source")
          source-2 (ch->src "source-2")
          request-id (request-id!)
          context-name "my context"

          create-rq {:domain            constants/context-domain-uri
                     :request_id        request-id
                     :peer_id           peer-id-1
                     :name              context-name
                     :data              {}
                     :lifetime          "ownership"
                     :read_permissions  nil
                     :write_permissions nil}
          msgs (-> (new-state)
                   (create-join source {:request_id request-id :peer_id peer-id-1 :identity id1})
                   (first)
                   (create-join source-2 {:request_id request-id :peer_id peer-id-2 :identity id2})
                   (first)
                   (ctx/create source create-rq)
                   (second))
          context-id (context-id :context-created msgs)]

      (just? [(msg/context-added source peer-id-1 peer-id-1 context-id context-name)
              (msg/context-added source-2 peer-id-2 peer-id-1 context-id context-name)
              (msg/context-created source request-id peer-id-1 context-id)
              (m/broadcast {:type    :peer
                            :peer-id peer-id-1
                            :node    node-id}
                           (assoc create-rq
                             :type :create-context
                             :version (msgs->ctx-version msgs)))] msgs)))

  (testing "when a peer join, it receives an announcement for all contexts that it can see"
    (let [
          [id1 id2] (repeatedly 2 gen-identity)
          [peer-id-1 peer-id-2] (repeatedly 2 peer-id!)

          source (ch->src "source")
          source-2 (ch->src "source-2")
          request-id (request-id!)
          context-name "my context"

          request {:request_id  request-id :peer_id peer-id-2 :identity id2
                   :domain      c/global-domain-uri
                   :destination constants/context-domain-uri}
          create-rq {:domain            constants/context-domain-uri
                     :request_id        request-id
                     :peer_id           peer-id-1
                     :name              context-name
                     :data              {}
                     :lifetime          "ownership"
                     :read_permissions  nil
                     :write_permissions nil}
          [state msgs] (-> (new-state)
                           (create-join source {:request_id request-id :peer_id peer-id-1 :identity id1})
                           (first)
                           (ctx/create source create-rq)
                           (first)
                           (create-join source-2 request))

          context (state/context-by-name state context-name)
          context-id (:id context)]

      (just? msgs
             [(m/peer-added constants/context-domain-uri source-2 peer-id-2 peer-id-1 id1 {:local true})
              (m/peer-added constants/context-domain-uri source peer-id-1 peer-id-2 id2 {:local true})
              (msg/context-added source-2 peer-id-2 peer-id-1 context-id context-name)
              (msg/success source-2 request-id peer-id-2)
              (m/broadcast {:type    :peer
                            :peer-id peer-id-2
                            :node    node-id}
                           (assoc request :type :join))])))

  (testing "peer joined thru the compatibility mode global domain doesnt receive announcements, but is announced"
    (let [
          [id1 id2] (repeatedly 2 gen-identity)
          [peer-id-1 peer-id-2] (repeatedly 2 peer-id!)

          source (ch->src "source")
          source-2 (ch->src "source-2")
          request-id (request-id!)

          [state msgs] (-> (new-state)
                           (assoc :registered-domains (local-node/build-domains [(ctx/context-domain)]))
                           (authenticated source {:request_id request-id :remote-identity id1}))

          peer-id-1 (get-in (first msgs) [:body :peer_id])

          request {:request_id  request-id :peer_id peer-id-2 :identity id2
                   :domain      c/global-domain-uri
                   :destination constants/context-domain-uri}
          [state msgs] (-> state
                           (create-join source-2 request))]

      (just? msgs
             [(m/peer-added constants/context-domain-uri source-2 peer-id-2 peer-id-1 (assoc id1 :machine "127.0.0.1") {:local true})
              (msg/success source-2 request-id peer-id-2)
              (m/broadcast {:type    :peer
                            :peer-id peer-id-2
                            :node    node-id}
                           (assoc request :type :join))])
      (testing "then if it joins the context domain, it gets announcements of the rest of the peers"
        (let [
              request {:request_id  request-id
                       :peer_id     peer-id-1
                       :identity    id1
                       :domain      c/global-domain-uri
                       :destination constants/context-domain-uri}
              [state msgs] (-> state
                               (ctx/join source request))]
          (just? msgs [(m/peer-added constants/context-domain-uri source peer-id-1 peer-id-2 id2 {:local true})
                       (msg/success source request-id peer-id-1)
                       (m/broadcast {:type    :peer
                                     :peer-id peer-id-1
                                     :node    node-id}
                                    (assoc request :type :join))]))))))

(deftest context-restricted-announcement
  (let [
        id1 (assoc (gen-identity) :user "user")
        id2 (assoc (gen-identity) :user "user")
        id3 (assoc (gen-identity) :user "other-user")
        [peer-id-1 peer-id-2 peer-id-3] (repeatedly 3 peer-id!)

        source (ch->src "source")
        source-2 (ch->src "source-2")
        source-3 (ch->src "source-3")
        request-id (request-id!)
        context-name "my context"

        create-rq {:domain            constants/context-domain-uri
                   :request_id        request-id
                   :peer_id           peer-id-1
                   :name              context-name
                   :data              {}
                   :lifetime          "ownership"
                   :read_permissions  "$user==#user"
                   :write_permissions nil}
        msgs (-> (new-state)
                 (create-join source {:request_id request-id :peer_id peer-id-1 :identity id1})
                 (first)
                 (create-join source-2 {:request_id request-id :peer_id peer-id-2 :identity id2})
                 (first)
                 (create-join source-3 {:request_id request-id :peer_id peer-id-3 :identity id3})
                 (first)
                 (ctx/create source create-rq)
                 (second))
        context-id (context-id :context-created msgs)]

    (just? [(msg/context-added source peer-id-1 peer-id-1 context-id context-name)
            (msg/context-added source-2 peer-id-2 peer-id-1 context-id context-name)
            (msg/context-created source request-id peer-id-1 context-id)
            (m/broadcast {:type    :peer
                          :peer-id peer-id-1
                          :node    node-id}
                         (assoc create-rq
                           :type :create-context
                           :version (msgs->ctx-version msgs)))]
           msgs)))

(deftest context-destroyed-when-owner-leaves
  (let [
        [id1 id2] (repeatedly 2 gen-identity)
        [peer-id-1 peer-id-2] (repeatedly 2 peer-id!)

        source (ch->src "source")
        source-2 (ch->src "source-2")
        request-id (request-id!)
        context-name "my context"

        create-rq {:domain            constants/context-domain-uri
                   :request_id        request-id
                   :peer_id           peer-id-1
                   :name              context-name
                   :data              {}
                   :lifetime          "ownership"
                   :read_permissions  nil
                   :write_permissions nil}
        state-with-context (-> (new-state)
                               (create-join source {:request_id request-id :peer_id peer-id-1 :identity id1})
                               (first)
                               (create-join source-2 {:request_id request-id :peer_id peer-id-2 :identity id2})
                               (first)
                               (ctx/create source create-rq)
                               (first))

        context (state/context-by-name state-with-context context-name)
        remove-rq {:type ::commands/source-removed}
        [state msgs] (ctx/source-removed state-with-context source remove-rq)]
    (is (nil? (state/context-by-name state context-name)))
    (is (= msgs
           [(msg/context-destroyed source-2 peer-id-2 (:id context) (cc/context-destroyed-peer-left constants/context-domain-uri))
            (m/peer-removed constants/context-domain-uri source-2 peer-id-2 peer-id-1 constants/reason-peer-removed)
            (m/broadcast {:type    :peer
                          :peer-id peer-id-1
                          :node    node-id}
                         {:request_id  nil
                          :domain      c/global-domain-uri
                          :peer_id     peer-id-1
                          :type        :leave
                          :destination constants/context-domain-uri
                          :reason_uri  (:uri constants/reason-peer-removed)
                          :reason      (:message constants/reason-peer-removed)})]))))


(deftest owner-destroys-context
  (let [
        [id1 id2] (repeatedly 2 gen-identity)
        [peer-id-1 peer-id-2] (repeatedly 2 peer-id!)

        source (ch->src "source")
        source-2 (ch->src "source-2")
        request-id (request-id!)
        context-name "my context"

        create-rq {:domain            constants/context-domain-uri
                   :request_id        request-id
                   :peer_id           peer-id-1
                   :name              context-name
                   :data              {}
                   :lifetime          "ownership"
                   :read_permissions  nil
                   :write_permissions nil}
        state-with-context (-> (new-state)
                               (create-join source {:request_id request-id :peer_id peer-id-1 :identity id1})
                               (first)
                               (create-join source-2 {:request_id request-id :peer_id peer-id-2 :identity id2})
                               (first)
                               (ctx/create source create-rq)
                               (first))
        context (state/context-by-name state-with-context context-name)
        ctx-id (:id context)
        destroy-rq {:domain     constants/context-domain-uri
                    :request_id request-id :peer_id peer-id-1 :context_id ctx-id}
        [state msgs] (ctx/destroy state-with-context source
                                  destroy-rq)]

    (is (nil? (state/context-by-name state context-name)))
    (just? msgs
           [(msg/context-destroyed source peer-id-1 ctx-id (cc/context-destroyed-explicitly constants/context-domain-uri))
            (msg/context-destroyed source-2 peer-id-2 ctx-id (cc/context-destroyed-explicitly constants/context-domain-uri))
            (msg/success source request-id peer-id-1)
            (m/broadcast {:type    :peer
                          :peer-id peer-id-1
                          :node    node-id}
                         (assoc destroy-rq
                           :type :destroy-context))])))

(deftest non-owner-cant-destroy
  (let [
        [id1 id2] (repeatedly 2 gen-identity)
        [peer-id-1 peer-id-2] (repeatedly 2 peer-id!)

        source (ch->src "source")
        source-2 (ch->src "source-2")
        request-id (request-id!)
        context-name "my context"

        create-rq {:domain            constants/context-domain-uri
                   :request_id        request-id
                   :peer_id           peer-id-1
                   :name              context-name
                   :data              {}
                   :lifetime          "ownership"
                   :read_permissions  nil
                   :write_permissions nil}
        state-with-context (-> (new-state)
                               (create-join source {:request_id request-id :peer_id peer-id-1 :identity id1})
                               (first)
                               (create-join source-2 {:request_id request-id :peer_id peer-id-2 :identity id2})
                               (first)
                               (ctx/create source create-rq)
                               (first))

        context (state/context-by-name state-with-context context-name)
        ctx-id (:id context)
        [state msgs] (ctx/destroy state-with-context source-2 {:request_id request-id :peer_id peer-id-2 :context_id ctx-id})]

    (is (some? (state/context-by-name state context-name)))
    (is (= msgs
           [(msg/error source-2
                       request-id
                       peer-id-2
                       (reason (cc/context-not-authorized constants/context-domain-uri)
                               "Not authorized to update context"))]))))


(deftest destroy-restricted-fail
  (let [
        id1 (assoc (gen-identity) :user "user")
        id2 (assoc (gen-identity) :user "other-user")
        [peer-id-1 peer-id-2] (repeatedly 2 peer-id!)

        source (ch->src nil)
        source-2 (ch->src nil)
        request-id (request-id!)
        context-name "my context"

        create-rq {:domain            constants/context-domain-uri
                   :request_id        request-id
                   :peer_id           peer-id-1
                   :name              context-name
                   :data              {}
                   :lifetime          "ref-counted"
                   :read_permissions  nil
                   :write_permissions "$user==#user"}
        state-with-context (-> (new-state)
                               (create-join source {:request_id request-id :peer_id peer-id-1 :identity id1})
                               (first)
                               (create-join source-2 {:request_id request-id :peer_id peer-id-2 :identity id2})
                               (first)
                               (ctx/create source create-rq)
                               (first))

        context (state/context-by-name state-with-context context-name)
        ctx-id (:id context)
        [state msgs] (ctx/destroy state-with-context source-2 {:request_id request-id :peer_id peer-id-2 :context_id ctx-id})]

    (is (some? (state/context-by-name state context-name)))
    (is (= msgs
           [(msg/error source-2
                       request-id
                       peer-id-2
                       (reason (cc/context-not-authorized constants/context-domain-uri)
                               "Not authorized to update context"))]))))

(deftest destroy-restricted-success
  (let [
        id1 (assoc (gen-identity) :user "user")
        id2 (assoc (gen-identity) :user "user")
        [peer-id-1 peer-id-2] (repeatedly 2 peer-id!)

        source (ch->src "source")
        source-2 (ch->src "source-2")
        request-id (request-id!)
        context-name "my context"

        create-rq {:domain            constants/context-domain-uri
                   :request_id        request-id
                   :peer_id           peer-id-1
                   :name              context-name
                   :data              {}
                   :lifetime          "ref-counted"
                   :read_permissions  nil
                   :write_permissions "$user==#user"}
        state-with-context (-> (new-state)
                               (create-join source {:request_id request-id :peer_id peer-id-1 :identity id1})
                               (first)
                               (create-join source-2 {:request_id request-id :peer_id peer-id-2 :identity id2})
                               (first)
                               (ctx/create source create-rq)
                               (first))
        context (state/context-by-name state-with-context context-name)
        ctx-id (:id context)
        destroy-rq {:domain     constants/context-domain-uri
                    :request_id request-id :peer_id peer-id-2 :context_id ctx-id}
        [state msgs] (ctx/destroy state-with-context source-2 destroy-rq)]

    (is (nil? (state/context-by-name state context-name)))
    (just? msgs
           [(msg/context-destroyed source peer-id-1 ctx-id (cc/context-destroyed-explicitly constants/context-domain-uri))
            (msg/context-destroyed source-2 peer-id-2 ctx-id (cc/context-destroyed-explicitly constants/context-domain-uri))
            (msg/success source-2 request-id peer-id-2)
            (m/broadcast {:type    :peer
                          :peer-id peer-id-2
                          :node    node-id}
                         (assoc destroy-rq
                           :type :destroy-context))])))

(deftest context-subscription
  (testing "contexts can be subscribed to"
    (let [
          [id1 id2] (repeatedly 2 gen-identity)
          [peer-id-1 peer-id-2] (repeatedly 2 peer-id!)

          source (ch->src "source")
          source-2 (ch->src "source-2")
          request-id (request-id!)
          context-name "my context"

          create-rq {:domain            constants/context-domain-uri
                     :request_id        request-id
                     :peer_id           peer-id-1
                     :name              context-name
                     :data              {}
                     :lifetime          "ownership"
                     :read_permissions  nil
                     :write_permissions nil}
          [state msgs] (-> (new-state)
                           (create-join source {:request_id request-id :peer_id peer-id-1 :identity id1})
                           (first)
                           (create-join source-2 {:request_id request-id :peer_id peer-id-2 :identity id2})
                           (first)
                           (ctx/create source create-rq))
          context-id (context-id :context-created msgs)
          subscribe-rq {:domain     constants/context-domain-uri
                        :request_id request-id :peer_id peer-id-2 :context_id context-id}
          msgs (-> state
                   (ctx/subscribe source-2 subscribe-rq)
                   (second))]
      (just? msgs [(msg/subscribed-context source-2 request-id peer-id-2 context-id {})
                   (m/broadcast {:type    :peer
                                 :peer-id peer-id-2
                                 :node    node-id}
                                (assoc subscribe-rq
                                  :type :subscribe-context))])))

  (testing "unsubscribing from a context stops all evens from it"
    (let [
          [id1 id2] (repeatedly 2 gen-identity)
          [peer-id-1 peer-id-2] (repeatedly 2 peer-id!)

          source (ch->src "source")
          source-2 (ch->src "source-2")
          request-id (request-id!)
          context-name "my context"

          create-rq {:domain            constants/context-domain-uri
                     :request_id        request-id
                     :peer_id           peer-id-1
                     :name              context-name
                     :data              {}
                     :lifetime          "ownership"
                     :read_permissions  nil
                     :write_permissions nil}
          [state msgs] (-> (new-state)
                           (create-join source {:request_id request-id :peer_id peer-id-1 :identity id1})
                           (first)
                           (create-join source-2 {:request_id request-id :peer_id peer-id-2 :identity id2})
                           (first)
                           (ctx/create source create-rq))
          context-id (context-id :context-created msgs)

          ;; unsubscribe from the context
          unsub-rq {:domain     constants/context-domain-uri
                    :request_id request-id :peer_id peer-id-2 :context_id context-id}
          [state unsub-msgs] (-> state
                                 (ctx/subscribe source-2 {:domain     constants/context-domain-uri
                                                          :request_id request-id :peer_id peer-id-2 :context_id context-id})
                                 (first)
                                 (ctx/unsubscribe source-2 unsub-rq))

          ;; send an update
          delta {:added {"k" 5}}
          update-rq {:domain     constants/context-domain-uri
                     :request_id request-id :peer_id peer-id-1 :context_id context-id
                     :delta      delta}
          msgs (-> state
                   (ctx/update-ctx source update-rq)
                   (second))]

      (is (= unsub-msgs [(msg/success source-2
                                      request-id
                                      peer-id-2)
                         (m/broadcast {:type    :peer
                                       :peer-id peer-id-2
                                       :node    node-id}
                                      (assoc unsub-rq
                                        :type :unsubscribe-context))]))
      (just? msgs [(msg/success source
                                request-id
                                peer-id-1)
                   (m/broadcast {:type    :peer
                                 :peer-id peer-id-1
                                 :node    node-id}
                                (assoc update-rq
                                  :type :update-context
                                  :version (msgs->ctx-version msgs)))]))))

(deftest context-updates
  (testing "when a context is updated, the update is broadcasted to all members"
    (let [
          [id1 id2] (repeatedly 2 gen-identity)
          [peer-id-1 peer-id-2] (repeatedly 2 peer-id!)

          source (ch->src "source")
          source-2 (ch->src "source-2")
          request-id (request-id!)
          context-name "my context"

          create-rq {:domain            constants/context-domain-uri
                     :request_id        request-id
                     :peer_id           peer-id-1
                     :name              context-name
                     :data              {}
                     :lifetime          "ownership"
                     :read_permissions  nil
                     :write_permissions nil}
          [state msgs] (-> (new-state)
                           (create-join source {:request_id request-id :peer_id peer-id-1 :identity id1})
                           (first)
                           (create-join source-2 {:request_id request-id :peer_id peer-id-2 :identity id2})
                           (first)
                           (ctx/create source create-rq))

          context-id (context-id :context-created msgs)
          delta {:added {"k" 5}}
          update-rq {:domain     constants/context-domain-uri
                     :request_id request-id :peer_id peer-id-1 :context_id context-id
                     :delta      delta}
          msgs (-> state
                   (ctx/subscribe source-2 {:domain     constants/context-domain-uri
                                            :request_id request-id :peer_id peer-id-2 :context_id context-id})
                   (first)
                   (ctx/update-ctx source
                                   update-rq)
                   (second))]
      (just? msgs [(msg/context-updated source-2
                                        peer-id-2
                                        peer-id-1
                                        context-id
                                        delta)
                   (msg/success source
                                request-id
                                peer-id-1)
                   (m/broadcast {:type    :peer
                                 :peer-id peer-id-1
                                 :node    node-id}
                                (assoc update-rq
                                  :type :update-context
                                  :version (msgs->ctx-version msgs)))])))
  (testing "data can be deleted"
    (let [
          [id1 id2] (repeatedly 2 gen-identity)
          [peer-id-1 peer-id-2] (repeatedly 2 peer-id!)

          source (ch->src "source")
          source-2 (ch->src "source-2")
          request-id (request-id!)
          context-name "my context"

          create-rq {:domain            constants/context-domain-uri
                     :request_id        request-id
                     :peer_id           peer-id-1
                     :name              context-name
                     :data              {}
                     :lifetime          "ownership"
                     :read_permissions  nil
                     :write_permissions nil}
          [state msgs] (-> (new-state)
                           (create-join source {:request_id request-id :peer_id peer-id-1 :identity id1})
                           (first)
                           (create-join source-2 {:request_id request-id :peer_id peer-id-2 :identity id2})
                           (first)
                           (ctx/create source create-rq))

          context-id (context-id :context-created msgs)
          delta {:removed ["remove-me"]}
          update-rq {:domain     constants/context-domain-uri
                     :request_id request-id :peer_id peer-id-1 :context_id context-id
                     :delta      delta}
          msgs (-> state
                   (ctx/subscribe source-2 {:domain     constants/context-domain-uri
                                            :request_id request-id :peer_id peer-id-2 :context_id context-id})
                   (first)
                   (ctx/update-ctx source update-rq)
                   (second))]
      (just? msgs [(msg/context-updated source-2
                                        peer-id-2
                                        peer-id-1
                                        context-id
                                        delta)
                   (msg/success source
                                request-id
                                peer-id-1)
                   (m/broadcast {:type    :peer
                                 :peer-id peer-id-1
                                 :node    node-id}
                                (assoc update-rq
                                  :type :update-context
                                  :version (msgs->ctx-version msgs)))]))))

(deftest context-resets
  (testing "when a context is reset, the update is broadcasted to all members"
    (let [
          [id1 id2] (repeatedly 2 gen-identity)
          [peer-id-1 peer-id-2] (repeatedly 2 peer-id!)

          source (ch->src "source")
          source-2 (ch->src "source-2")
          request-id (request-id!)
          context-name "my context"
          old-ctx {"a" 1 "b" 2}
          new-ctx {"a" 2 "c" 3 "d" 4}
          delta {:reset new-ctx}

          create-rq {:domain            constants/context-domain-uri
                     :request_id        request-id
                     :peer_id           peer-id-1
                     :name              context-name
                     :data              old-ctx
                     :lifetime          "ownership"
                     :read_permissions  nil
                     :write_permissions nil}
          [state msgs] (-> (new-state)
                           (create-join source {:request_id request-id :peer_id peer-id-1 :identity id1})
                           (first)
                           (create-join source-2 {:request_id request-id :peer_id peer-id-2 :identity id2})
                           (first)
                           (ctx/create source create-rq))

          context-id (context-id :context-created msgs)
          update-rq {:domain     constants/context-domain-uri
                     :request_id request-id :peer_id peer-id-1 :context_id context-id
                     :delta      delta}
          msgs (-> state
                   (ctx/subscribe source-2 {:domain     constants/context-domain-uri
                                            :request_id request-id :peer_id peer-id-2 :context_id context-id})
                   (first)
                   (ctx/update-ctx source update-rq)
                   (second))]
      (just? msgs [(msg/context-updated source-2
                                        peer-id-2
                                        peer-id-1
                                        context-id
                                        delta)
                   (msg/success source
                                request-id
                                peer-id-1)
                   (m/broadcast {:type    :peer
                                 :peer-id peer-id-1
                                 :node    node-id}
                                (assoc update-rq
                                  :type :update-context
                                  :version (msgs->ctx-version msgs)))]))))

(deftest context-update-handling
  (testing "updated merges the data key by key"
    (let [state {:contexts {1 {:id   1
                               :data {:meta {:channel "red"}
                                      :data {:color "red"
                                             :fruit "apple"}}}}}
          ctx (state/context-by-id state 1)]
      (is (= {:data {:color "green"
                     :fruit "apple"}
              :meta {:channel "red"}}
             (-> state
                 (state/apply-delta ctx {:updated {:data {:color "green"}}} 1)
                 (state/context-by-id 1)
                 :data)))))
  (testing "updated replaces existing values with non-associative updates"
    (let [state {:contexts {1 {:id   1
                               :data {:meta {:channel "red"}
                                      :data {:color "red"
                                             :fruit "apple"}}}}}
          ctx (state/context-by-id state 1)]
      (is (= {:data "string"
              :meta {:channel "red"}}
             (-> state
                 (state/apply-delta ctx {:updated {:data "string"}} 1)
                 (state/context-by-id 1)
                 :data)))))
  (testing "updated replaces existing non-associative values with new ones"
    (let [state {:contexts {1 {:id   1
                               :data {:meta {:channel "red"}
                                      :data "string"}}}}
          ctx (state/context-by-id state 1)]
      (is (= {:data {:color "green"}
              :meta {:channel "red"}}
             (-> state
                 (state/apply-delta ctx {:updated {:data {:color "green"}}} 1)
                 (state/context-by-id 1)
                 :data)))))
  (testing "added replaces the corresponding keys"
    (let [state {:contexts {1 {:id   1
                               :data {:meta {:channel "red"}
                                      :data {:color "red"
                                             :fruit "apple"}}}}}
          ctx (state/context-by-id state 1)]
      (is (= {:data {:color "green"}
              :meta {:channel "red"}}
             (-> state
                 (state/apply-delta ctx {:added {:data {:color "green"}}} 1)
                 (state/context-by-id 1)
                 :data)))))
  (testing "removed, well, removes a list of keys"
    (let [state {:contexts {1 {:id   1
                               :data {:meta {:channel "red"}
                                      :data {:color "red"
                                             :fruit "apple"}
                                      :bleh {:a 1}}}}}
          ctx (state/context-by-id state 1)]
      (is (= {:meta {:channel "red"}}
             (-> state
                 (state/apply-delta ctx {:removed [:data :bleh]} 1)
                 (state/context-by-id 1)
                 :data)))))
  (testing "reset with nil data does nothing"
    (let [state {:contexts {1 {:id   1
                               :data {:bleh {:a 1}
                                      :data {:color "red"
                                             :fruit "apple"}
                                      :meta {:channel "red"}}}}}
          ctx (state/context-by-id state 1)]
      (is (= {:bleh {:a 1}
              :data {:color "red"
                     :fruit "apple"}
              :meta {:channel "red"}}
             (-> state
                 (state/apply-delta ctx {:reset nil} 1)
                 (state/context-by-id 1)
                 :data)))))
  (testing "sending invalid commands are ignored"
    (let [data {:bleh {:a 1}
                :data {:color "red"
                       :fruit "apple"}
                :meta {:channel "red"}}
          state {:contexts {1 {:id   1
                               :data data}}}
          ctx (state/context-by-id state 1)]
      (is (= data
             (-> state
                 (state/apply-delta ctx {"bleh" 123} 1)
                 (state/context-by-id 1)
                 :data))))))


(defn remote-join
  [state message]
  (let [body (:body message)]
    (-> state
        (peers/ensure-peer-with-id (:source message)
                                   (:peer_id body)
                                   (:identity body)
                                   nil
                                   nil)
        (first)
        (ctx/join (:source message) body)
        (first))))

(defn filter-join [messages]
  (->> messages
       (filter #(= :join (get-in % [:body :type])))
       (first)))

(deftest context-create-propagated
  (testing "context created on one node creates it on a remote node as well"
    (let [{peer-id-1 :id identity-1 :identity peer-1 :source} (local-peer)
          {peer-id-2 :id identity-2 :identity peer-2 :source} (local-peer)

          rid (request-id!)

          ;; create the first peer (local to node1) and join it to both nodes
          [node1-state messages] (-> (new-state)
                                     (create-join peer-1 {:request_id rid :peer_id peer-id-1 :identity identity-1}))
          node2-state (remote-join (new-state) (filter-join messages))

          ;; create the second peer (local to node2) and join it to both nodes
          [node2-state messages] (-> node2-state
                                     (create-join peer-2 {:request_id rid :peer_id peer-id-2 :identity identity-2}))
          node1-state (remote-join node1-state (filter-join messages))

          ;; create a context on node 1
          ctx-data {:value 42}
          creation-request {:domain     constants/context-domain-uri
                            :type       :create-context
                            :request_id rid
                            :peer_id    peer-id-1
                            :name       "context"
                            :data       ctx-data
                            :lifetime   "retained"}
          [node1-state messages] (-> node1-state
                                     (ctx/create peer-1 creation-request))
          ctx-id (context-id :context-created messages)
          b (last messages)

          [node2-state messages] (-> node2-state
                                     (ctx/create (:source b) (:body b)))

          replicated-ctx (state/context-by-id node2-state ctx-id)]

      (is (= messages [(msg/context-added peer-2 peer-id-2 peer-id-1 ctx-id "context")]))
      (is (= (:data replicated-ctx) ctx-data)))))

(def context-version (atom 0))

(deftest context-create-replaces-older-remote-context
  (with-redefs [state/next-version (fn [] (swap! context-version inc))]
    (testing "context creation overrides an existing older context"
      (let [{peer-id-1 :id identity-1 :identity peer-1 :source} (local-peer)
            {peer-id-2 :id identity-2 :identity peer-2 :source} (local-peer)

            rid (request-id!)

            ;; create the first peer (local to node1) and join it to both nodes
            [node1-state messages] (-> (new-state)
                                       (create-join peer-1 {:request_id rid :peer_id peer-id-1 :identity identity-1}))
            node2-state (remote-join (new-state) (filter-join messages))

            ;; create the second peer (local to node2) and join it to both nodes
            [node2-state messages] (-> node2-state
                                       (create-join peer-2 {:request_id rid :peer_id peer-id-2 :identity identity-2}))
            node1-state (remote-join node1-state (filter-join messages))

            ;; create a context on node 2
            [node2-state messages] (-> node2-state
                                       (ctx/create peer-1 {:domain     constants/context-domain-uri
                                                           :type       :create-context
                                                           :request_id rid
                                                           :peer_id    peer-id-2
                                                           :name       "context"
                                                           :data       {:value 41}
                                                           :lifetime   "retained"}))

            ;; create a context on node 1
            ctx-data {:value 42}
            [node1-state messages] (-> node1-state
                                       (ctx/create peer-1 {:domain     constants/context-domain-uri
                                                           :type       :create-context
                                                           :request_id rid
                                                           :peer_id    peer-id-1
                                                           :name       "context"
                                                           :data       ctx-data
                                                           :lifetime   "retained"}))
            ctx-id (context-id :context-created messages)
            b (last messages)

            [node2-state messages'] (-> node2-state
                                        (ctx/create (:source b) (:body b)))

            replicated-ctx (state/context-by-id node2-state ctx-id)]

        (is (= messages' [(msg/context-updated peer-2 peer-id-2 peer-id-1 ctx-id {:reset ctx-data})]))
        (is (= (:data replicated-ctx) ctx-data))))))

(deftest context-create-fizzles-new-remote-context
  (testing "context creation cant override a newer context"
    (let [{peer-id-1 :id identity-1 :identity peer-1 :source} (local-peer)
          {peer-id-2 :id identity-2 :identity peer-2 :source} (local-peer)

          rid (request-id!)

          ;; create the first peer (local to node1) and join it to both nodes
          [node1-state messages] (-> (new-state)
                                     (create-join peer-1 {:request_id rid :peer_id peer-id-1 :identity identity-1}))
          node2-state (remote-join (new-state) (filter-join messages))

          ;; create the second peer (local to node2) and join it to both nodes
          [node2-state messages] (-> node2-state
                                     (create-join peer-2 {:request_id rid :peer_id peer-id-2 :identity identity-2}))
          node1-state (remote-join node1-state (filter-join messages))

          ;; create a context on node 2
          [node2-state messages] (with-redefs [state/next-version (fn [] 2)]
                                   (-> node2-state
                                       (ctx/create peer-1 {:domain     constants/context-domain-uri
                                                           :type       :create-context
                                                           :request_id rid
                                                           :peer_id    peer-id-2
                                                           :name       "context"
                                                           :data       {:value 41}
                                                           :lifetime   "retained"})))
          ctx-id (context-id :context-created messages)

          ;; create a context on node 1
          [node1-state messages] (with-redefs [state/next-version (fn [] 1)]
                                   (-> node1-state
                                       (ctx/create peer-1 {:domain     constants/context-domain-uri
                                                           :type       :create-context
                                                           :request_id rid
                                                           :peer_id    peer-id-1
                                                           :name       "context"
                                                           :data       {:value 42}
                                                           :lifetime   "retained"})))

          b (last messages)

          [node2-state messages'] (-> node2-state
                                      (ctx/create (:source b) (:body b)))

          replicated-ctx (state/context-by-id node2-state ctx-id)]

      (is (nil? messages'))
      (is (= (:data replicated-ctx) {:value 41})))))


(deftest context-update-propagated
  (with-redefs [state/next-version (fn [] (swap! context-version inc))]
    (testing "context updated on one node, gets updated on the other nodes"
      (let [{peer-id-1 :id identity-1 :identity peer-1 :source} (local-peer)
            {peer-id-2 :id identity-2 :identity peer-2 :source} (local-peer)

            rid (request-id!)

            ;; create the first peer (local to node1) and join it to both nodes
            [node1-state messages] (-> (new-state)
                                       (create-join peer-1 {:request_id rid :peer_id peer-id-1 :identity identity-1}))
            node2-state (remote-join (new-state) (filter-join messages))

            ;; create the second peer (local to node2) and join it to both nodes
            [node2-state messages] (-> node2-state
                                       (create-join peer-2 {:request_id rid :peer_id peer-id-2 :identity identity-2}))
            node1-state (remote-join node1-state (filter-join messages))

            ;; create a context on node 1
            ctx-data {:value 42}
            creation-request {:domain     constants/context-domain-uri
                              :type       :create-context
                              :request_id rid
                              :peer_id    peer-id-1
                              :name       "context"
                              :data       ctx-data
                              :lifetime   "retained"}
            [node1-state messages] (-> node1-state
                                       (ctx/create peer-1 creation-request))
            ctx-id (context-id :context-created messages)
            b (last messages)

            [node2-state messages] (-> node2-state
                                       (ctx/create (:source b) (:body b)))

            ;; first node gets updated
            delta {:added {"k" 5}}
            update-rq {:domain     constants/context-domain-uri
                       :request_id rid :peer_id peer-id-1 :context_id ctx-id
                       :delta      delta}
            [node1-state messages] (-> node1-state
                                       (ctx/update-ctx peer-1 update-rq))

            ;; apply to second node
            b (last messages)
            [node2-state messages'] (-> node2-state
                                        (ctx/update-ctx (:source b) (:body b)))


            replicated-ctx (state/context-by-id node2-state ctx-id)]

        (is (= (:data replicated-ctx) (:data (state/context-by-id node1-state ctx-id))))))))

(deftest subscribe-unsubscribe-ref-counting
  (with-redefs [state/next-version (fn [] (swap! context-version inc))]
    (testing "subscriptions are tracked on local and remote nodes"
      (let [{peer-id-1 :id identity-1 :identity peer-1 :source} (local-peer)
            {peer-id-2 :id identity-2 :identity peer-2 :source} (local-peer)

            rid (request-id!)

            ;; create the first peer (local to node1) and join it to both nodes
            [node1-state messages] (-> (new-state)
                                       (create-join peer-1 {:request_id rid :peer_id peer-id-1 :identity identity-1}))
            node2-state (remote-join (new-state) (filter-join messages))

            ;; create the second peer (local to node2) and join it to both nodes
            [node2-state messages] (-> node2-state
                                       (create-join peer-2 {:request_id rid :peer_id peer-id-2 :identity identity-2}))
            node1-state (remote-join node1-state (filter-join messages))

            ;; create a context on node 1
            ctx-data {:value 42}
            creation-request {:domain     constants/context-domain-uri
                              :type       :create-context
                              :request_id rid
                              :peer_id    peer-id-1
                              :name       "context"
                              :data       ctx-data
                              :lifetime   "ref-counted"}
            [node1-state messages] (-> node1-state
                                       (ctx/create peer-1 creation-request))
            ctx-id (context-id :context-created messages)
            b (last messages)

            [node2-state messages] (-> node2-state
                                       (ctx/create (:source b) (:body b)))

            ;; subscribe on node 1
            [node1-state messages] (-> node1-state
                                       (ctx/subscribe peer-1 {:domain     constants/context-domain-uri
                                                              :request_id rid :peer_id peer-id-1 :context_id ctx-id}))
            ;; propagate on node 2
            b (last messages)
            [node2-state messages] (-> node2-state
                                       (ctx/subscribe (:source b) (:body b)))


            ;; subscribe on node 2
            [node2-state messages] (-> node2-state
                                       (ctx/subscribe peer-1 {:domain     constants/context-domain-uri
                                                              :request_id rid :peer_id peer-id-2 :context_id ctx-id}))

            ;; propagate on node 1
            b (last messages)
            [node1-state messages] (-> node1-state
                                       (ctx/subscribe (:source b) (:body b)))]

        (is (= 2
               (-> (state/context-by-id node1-state ctx-id)
                   :members
                   count)
               (-> (state/context-by-id node2-state ctx-id)
                   :members
                   count)))
        (testing "ref-counting tracks local and remote peers"
          (let [
                ;; unsubscribe on node 1
                [node1-state messages] (-> node1-state
                                           (ctx/unsubscribe peer-1 {:domain     constants/context-domain-uri
                                                                    :request_id rid :peer_id peer-id-1 :context_id ctx-id}))

                ;; propagate on node 2
                b (last messages)
                [node2-state messages] (-> node2-state
                                           (ctx/unsubscribe (:source b) (:body b)))


                ;; unsubscribe on node 2
                [node2-state' messages2] (-> node2-state
                                             (ctx/unsubscribe peer-1 {:domain     constants/context-domain-uri
                                                                      :request_id rid :peer_id peer-id-2 :context_id ctx-id}))

                ;; propagate on node 1
                b (last messages2)
                [node1-state' messages1] (-> node1-state
                                             (ctx/unsubscribe (:source b) (:body b)))

                ]
            ;; after removing the corresponding local peers, the contexts are still alive
            (is (= 1
                   (-> (state/context-by-id node1-state ctx-id)
                       :members
                       count)
                   (-> (state/context-by-id node2-state ctx-id)
                       :members
                       count)))

            ;; and then after the remote peers disappear, the contexts are destroyed
            (is (= :context-destroyed (-> messages1 last :body :type)))
            (is (= :context-destroyed (-> messages1 first :body :type)))
            (is (nil? (state/context-by-id node1-state' ctx-id)))
            (is (nil? (state/context-by-id node2-state' ctx-id)))))))))


(deftest state-propagation
  (with-redefs [state/next-version (fn [] (swap! context-version inc))]
    (testing "testing that the state is propagated via messages"
      (let [{peer-id-1 :id identity-1 :identity peer-1 :source} (local-peer)

            rid (request-id!)

            ;; create a peer on node 1
            [node1-state messages] (-> (new-state)
                                       (create-join peer-1 {:request_id rid :peer_id peer-id-1 :identity identity-1}))

            ;; create a context on node 1
            ctx-data {:value 42}
            [node1-state messages] (-> node1-state
                                       (ctx/create peer-1 {:domain     constants/context-domain-uri
                                                           :type       :create-context
                                                           :request_id rid
                                                           :peer_id    peer-id-1
                                                           :name       "context"
                                                           :data       ctx-data
                                                           :lifetime   "retained"}))
            ctx-id (context-id :context-created messages)
            b (last messages)

            state-messages (ctx/state->messages node1-state)

            node2-state (-> (new-state)
                            (assoc :registered-domains (local-node/build-domains [(ctx/context-domain)])))

            node2-state (reduce (fn [state m] (-> (-> (if (= :join (get-in m [:body :type]))
                                                        (gc/-handle-message {} {} {} state m)
                                                        (ctx/-handle-message state m))
                                                      (first))))
                                node2-state
                                state-messages)
            ;
            replicated-ctx (state/context-by-id node2-state ctx-id)]

        (is (= (:data replicated-ctx) ctx-data))))))
