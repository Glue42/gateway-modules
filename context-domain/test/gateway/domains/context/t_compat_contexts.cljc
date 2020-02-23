(ns gateway.domains.context.t-compat-contexts
  #?(:clj
     (:use clojure.test))
  (:require
    #?(:cljs [cljs.test :refer-macros [deftest is testing]])
    #?(:cljs [gateway.t-macros :refer-macros [just? error-msg?]])
    #?(:clj [gateway.t-macros :refer [just? error-msg?]])
    [gateway.reason :refer [reason]]
    [gateway.domains.global.core :as g]
    [gateway.domains.global.messages :as msgs]
    [gateway.t-helpers :refer [gen-identity ch->src request-id! new-state node-id msgs->ctx-version]]
    [gateway.common.messages :refer [error success]]
    [gateway.domains.global.state :as state]
    [gateway.domains.global.constants :as c]
    [gateway.domains.context.constants :as cc]
    [gateway.domains.global.messages :as msg]
    [gateway.common.peer-identity :as peer-identity]
    [gateway.common.commands :as commands]
    [gateway.common.messages :as m]

    [gateway.local-node.core :as local-node]
    [gateway.domains.context.core :as ctx]
    [gateway.domains.context.helpers :refer [context-id]]))

(def environment {:local-ip "127.0.0.1"})
(defn authenticated
  [state source request]
  (g/authenticated state source request nil environment))

(defn empty-state []
  (-> (new-state)
      (assoc :registered-domains (local-node/build-domains [(ctx/context-domain)]))))

(deftest context-creation
  (testing "contexts can be created if missing"
    (let [
          id1 (gen-identity)
          source (ch->src "source")
          request-id (request-id!)
          context-name "my context"

          [state msgs] (-> (empty-state)
                           (authenticated source {:request_id request-id :remote-identity id1}))

          peer-id-1 (get-in (first msgs) [:body :peer_id])

          create-rq {:domain            c/global-domain-uri
                     :request_id        request-id
                     :peer_id           peer-id-1
                     :name              context-name
                     :data              {}
                     :lifetime          "ownership"
                     :read_permissions  nil
                     :write_permissions nil}
          [state msgs] (-> state
                           (g/create source create-rq))
          context-id (context-id :context-created msgs)
          context (get-in state [:contexts context-id])]

      (just? msgs
             [(msgs/context-created source request-id peer-id-1 context-id)
              (msgs/context-added source peer-id-1 peer-id-1 context-id context-name)
              (m/broadcast {:type    :peer
                            :peer-id peer-id-1
                            :node    node-id}
                           (assoc create-rq
                             :type :create-context
                             :version (msgs->ctx-version msgs)))])))

  (testing "trying to create a context with an existing name subscribes for it"
    (let [
          id1 (gen-identity)
          id2 (gen-identity)

          source (ch->src "source")
          source-2 (ch->src "source-2")
          request-id (request-id!)
          context-name "my context"

          [state msgs] (-> (empty-state)
                           (authenticated source {:request_id request-id :remote-identity id1}))
          peer-id-1 (get-in (first msgs) [:body :peer_id])

          [state msgs] (-> state
                           (authenticated source-2 {:request_id request-id :remote-identity id2}))
          peer-id-2 (get-in (first msgs) [:body :peer_id])

          [state msgs] (-> state
                           (g/create source {:domain            c/global-domain-uri
                                             :request_id        request-id
                                             :peer_id           peer-id-1
                                             :name              context-name
                                             :data              {:t 41}
                                             :lifetime          "retained"
                                             :read_permissions  nil
                                             :write_permissions nil}))
          context-id (context-id :context-created msgs)
          [state msgs] (-> state
                           (g/create source {:domain            c/global-domain-uri
                                             :request_id        request-id
                                             :peer_id           peer-id-2
                                             :name              context-name
                                             :data              {:t 42}
                                             :lifetime          "retained"
                                             :read_permissions  nil
                                             :write_permissions nil})
                           )]
      (is (= msgs [(msg/subscribed-context source request-id peer-id-2 context-id {:t 41})]))))
  (testing "creating a context without a peer returns an error"
    ;; ghostwheel will throw an exception
    (is (thrown? #?(:clj  Exception
                    :cljs js/Error)
                 (let [request-id (request-id!)
                       source (ch->src "source")
                       [state msgs] (-> (new-state)
                                        (g/create source {:domain            c/global-domain-uri
                                                          :request_id        request-id
                                                          :name              "context-name"
                                                          :data              {:t 41}
                                                          :lifetime          "retained"
                                                          :read_permissions  nil
                                                          :write_permissions nil})
                                        )]
                   (is (error-msg? c/failure (first msgs))))))))


(deftest context-announcement
  (testing "when a context is created, its announced to all peers that can see it"
    (let [
          id1 (gen-identity)
          id2 (gen-identity)

          source (ch->src "source")
          source-2 (ch->src "source-2")
          request-id (request-id!)
          context-name "my context"

          [state msgs] (-> (empty-state)
                           (authenticated source {:request_id request-id :remote-identity id1}))
          peer-id-1 (get-in (first msgs) [:body :peer_id])

          [state msgs] (-> state
                           (authenticated source-2 {:request_id request-id :remote-identity id2}))
          peer-id-2 (get-in (first msgs) [:body :peer_id])

          create-rq {:domain            c/global-domain-uri
                     :request_id        request-id
                     :peer_id           peer-id-1
                     :name              context-name
                     :data              {}
                     :lifetime          "ownership"
                     :read_permissions  nil
                     :write_permissions nil}
          [state msgs] (-> state
                           (g/create source create-rq))
          context-id (context-id :context-created msgs)
          context (get-in state [:contexts context-id])]
      (just? [(msgs/context-added source peer-id-1 peer-id-1 context-id context-name)
              (msgs/context-added source-2 peer-id-2 peer-id-1 context-id context-name)
              (msgs/context-created source request-id peer-id-1 context-id)
              (m/broadcast {:type    :peer
                            :peer-id peer-id-1
                            :node    node-id}
                           (assoc create-rq
                             :type :create-context
                             :version (msgs->ctx-version msgs)))] msgs)))
  (testing "when a peer join, it receives an announcement for all contexts that it can see"
    (let [
          id1 (gen-identity)
          id2 (gen-identity)

          source (ch->src "source")
          source-2 (ch->src "source-2")
          request-id (request-id!)
          context-name "my context"

          [state msgs] (-> (empty-state)
                           (authenticated source {:request_id request-id :remote-identity id1}))
          peer-id-1 (get-in (first msgs) [:body :peer_id])

          request {:request_id request-id :remote-identity id2}
          [state msgs] (-> state
                           (g/create source {:domain            c/global-domain-uri
                                             :request_id        request-id
                                             :peer_id           peer-id-1
                                             :name              context-name
                                             :data              {}
                                             :lifetime          "ownership"
                                             :read_permissions  nil
                                             :write_permissions nil})
                           (first)
                           (authenticated source-2 request))
          peer-id-2 (get-in (first msgs) [:body :peer_id])

          context (state/context-by-name state context-name)
          context-id (:id context)
          resolved-identity (assoc (peer-identity/keywordize-id id2) :machine (:local-ip environment))]
      (is (= msgs
             [(msgs/welcome source-2
                            request-id
                            peer-id-2
                            (list {:description ""
                                   :uri         "context"
                                   :version     1})
                            resolved-identity
                            nil)
              (msgs/context-added source-2 peer-id-2 peer-id-1 context-id context-name)
              (m/broadcast {:type    :peer
                            :peer-id peer-id-2
                            :node    node-id}
                           (-> request
                               (dissoc :remote-identity)
                               (assoc :type :join
                                      :identity resolved-identity
                                      :domain c/global-domain-uri
                                      :destination cc/context-domain-uri
                                      :peer_id peer-id-2
                                      :options {:context-compatibility-mode? true})))])))))

(deftest context-restricted-announcement
  (let [
        id1 (assoc (gen-identity) :user "user")
        id2 (assoc (gen-identity) :user "user")
        id3 (assoc (gen-identity) :user "other-user")

        source (ch->src "source")
        source-2 (ch->src "source-2")
        source-3 (ch->src "source-3")
        request-id (request-id!)
        context-name "my context"

        [state msgs] (-> (empty-state)
                         (authenticated source {:request_id request-id :remote-identity id1}))
        peer-id-1 (get-in (first msgs) [:body :peer_id])

        [state msgs] (-> state
                         (authenticated source-2 {:request_id request-id :remote-identity id2}))
        peer-id-2 (get-in (first msgs) [:body :peer_id])

        create-rq {:domain            c/global-domain-uri
                   :request_id        request-id
                   :peer_id           peer-id-1
                   :name              context-name
                   :data              {}
                   :lifetime          "ownership"
                   :read_permissions  "$user==#user"
                   :write_permissions nil}
        [state msgs] (-> state
                         (authenticated source-3 {:request_id request-id :remote-identity id2})
                         (first)
                         (g/create source create-rq))
        context-id (context-id :context-created msgs)
        context (get-in state [:contexts context-id])]
    (just? [(msgs/context-added source peer-id-1 peer-id-1 context-id context-name)
            (msgs/context-added source-2 peer-id-2 peer-id-1 context-id context-name)
            (msgs/context-created source request-id peer-id-1 context-id)
            (m/broadcast {:type    :peer
                          :peer-id peer-id-1
                          :node    node-id}
                         (assoc create-rq
                           :type :create-context
                           :version (msgs->ctx-version msgs)))]
           msgs)))

(deftest context-destroyed-when-owner-leaves
  (let [
        id1 (gen-identity)
        id2 (gen-identity)

        source (ch->src "source")
        source-2 (ch->src "source-2")
        request-id (request-id!)
        context-name "my context"

        [state msgs] (-> (empty-state)
                         (authenticated source {:request_id request-id :remote-identity id1}))
        peer-id-1 (get-in (first msgs) [:body :peer_id])

        [state msgs] (-> state
                         (authenticated source-2 {:request_id request-id :remote-identity id2}))
        peer-id-2 (get-in (first msgs) [:body :peer_id])

        state-with-context (-> state
                               (g/create source {:domain            c/global-domain-uri
                                                 :request_id        request-id
                                                 :peer_id           peer-id-1
                                                 :name              context-name
                                                 :data              {}
                                                 :lifetime          "ownership"
                                                 :read_permissions  nil
                                                 :write_permissions nil})
                               (first))
        context (state/context-by-name state-with-context context-name)
        remove-rq {:type ::commands/source-removed}
        [state msgs] (ctx/source-removed state-with-context source remove-rq)]
    (is (nil? (state/context-by-name state context-name)))
    (just? msgs
           [(msgs/context-destroyed source-2 peer-id-2 (:id context) cc/context-destroyed-peer-left)
            (m/broadcast {:type    :peer
                          :peer-id peer-id-1
                          :node    node-id}
                         {:request_id  nil
                          :domain      c/global-domain-uri
                          :peer_id     peer-id-1
                          :type        :leave
                          :destination cc/context-domain-uri
                          :reason_uri  (:uri cc/reason-peer-removed)
                          :reason      (:message cc/reason-peer-removed)})])))


(deftest owner-destroys-context
  (let [
        id1 (gen-identity)
        id2 (gen-identity)

        source (ch->src "source")
        source-2 (ch->src "source-2")
        request-id (request-id!)
        context-name "my context"

        [state msgs] (-> (empty-state)
                         (authenticated source {:request_id request-id :remote-identity id1}))
        peer-id-1 (get-in (first msgs) [:body :peer_id])

        [state msgs] (-> state
                         (authenticated source-2 {:request_id request-id :remote-identity id2}))
        peer-id-2 (get-in (first msgs) [:body :peer_id])

        state-with-context (-> state
                               (authenticated source {:request_id request-id :remote-identity id1})
                               (first)
                               (authenticated source-2 {:request_id request-id :remote-identity id2})
                               (first)
                               (g/create source {:domain            c/global-domain-uri
                                                 :request_id        request-id
                                                 :peer_id           peer-id-1
                                                 :name              context-name
                                                 :data              {}
                                                 :lifetime          "ownership"
                                                 :read_permissions  nil
                                                 :write_permissions nil})
                               (first))
        context (state/context-by-name state-with-context context-name)
        ctx-id (:id context)
        destroy-rq {:domain     c/global-domain-uri
                    :request_id request-id
                    :peer_id    peer-id-1
                    :context_id ctx-id}
        [state msgs] (g/destroy state-with-context source destroy-rq)]
    (is (nil? (state/context-by-name state context-name)))
    (just? msgs
           [(msgs/context-destroyed source peer-id-1 ctx-id c/context-destroyed-explicitly)
            (msgs/context-destroyed source-2 peer-id-2 ctx-id c/context-destroyed-explicitly)
            (success c/global-domain-uri source request-id peer-id-1)
            (m/broadcast {:type    :peer
                          :peer-id peer-id-1
                          :node    node-id}
                         (assoc destroy-rq
                           :type :destroy-context))])))

(deftest non-owner-cant-destroy
  (let [
        id1 (gen-identity)
        id2 (gen-identity)

        source (ch->src "source")
        source-2 (ch->src "source-2")
        request-id (request-id!)
        context-name "my context"

        [state msgs] (-> (empty-state)
                         (authenticated source {:request_id request-id :remote-identity id1}))
        peer-id-1 (get-in (first msgs) [:body :peer_id])

        [state msgs] (-> state
                         (authenticated source-2 {:request_id request-id :remote-identity id2}))
        peer-id-2 (get-in (first msgs) [:body :peer_id])

        state-with-context (-> state
                               (g/create source {:domain            c/global-domain-uri
                                                 :request_id        request-id
                                                 :peer_id           peer-id-1
                                                 :name              context-name
                                                 :data              {}
                                                 :lifetime          "ownership"
                                                 :read_permissions  nil
                                                 :write_permissions nil})
                               (first)
                               )
        context (state/context-by-name state-with-context context-name)
        ctx-id (:id context)
        [state msgs] (g/destroy state-with-context source-2 {:request_id request-id :peer_id peer-id-2 :context_id ctx-id})]
    (is (some? (state/context-by-name state context-name)))
    (is (= msgs
           [(error c/global-domain-uri
                   source-2
                   request-id
                   peer-id-2
                   (reason c/global-not-authorized "Not authorized to update context"))]))))


(deftest destroy-restricted-fail
  (let [
        id1 (assoc (gen-identity) :user "user")
        id2 (assoc (gen-identity) :user "other-user")

        source (ch->src nil)
        source-2 (ch->src nil)
        request-id (request-id!)
        context-name "my context"

        [state msgs] (-> (empty-state)
                         (authenticated source {:request_id request-id :remote-identity id1}))
        peer-id-1 (get-in (first msgs) [:body :peer_id])

        [state msgs] (-> state
                         (authenticated source-2 {:request_id request-id :remote-identity id2}))
        peer-id-2 (get-in (first msgs) [:body :peer_id])

        state-with-context (-> state
                               (g/create source {:domain            c/global-domain-uri
                                                 :request_id        request-id
                                                 :peer_id           peer-id-1
                                                 :name              context-name
                                                 :data              {}
                                                 :lifetime          "ref-counted"
                                                 :read_permissions  nil
                                                 :write_permissions "$user==#user"})
                               (first))
        context (state/context-by-name state-with-context context-name)
        ctx-id (:id context)
        [state msgs] (g/destroy state-with-context source-2 {:request_id request-id :peer_id peer-id-2 :context_id ctx-id})]
    (is (some? (state/context-by-name state context-name)))
    (is (= msgs
           [(error c/global-domain-uri
                   source-2
                   request-id
                   peer-id-2
                   (reason c/global-not-authorized "Not authorized to update context"))]))))

(deftest destroy-restricted-success
  (let [
        id1 (assoc (gen-identity) :user "user")
        id2 (assoc (gen-identity) :user "user")

        source (ch->src "source")
        source-2 (ch->src "source-2")
        request-id (request-id!)
        context-name "my context"

        [state msgs] (-> (empty-state)
                         (authenticated source {:request_id request-id :remote-identity id1}))
        peer-id-1 (get-in (first msgs) [:body :peer_id])

        [state msgs] (-> state
                         (authenticated source-2 {:request_id request-id :remote-identity id2}))
        peer-id-2 (get-in (first msgs) [:body :peer_id])

        state-with-context (-> state
                               (g/create source {:domain            c/global-domain-uri
                                                 :request_id        request-id
                                                 :peer_id           peer-id-1
                                                 :name              context-name
                                                 :data              {}
                                                 :lifetime          "ref-counted"
                                                 :read_permissions  nil
                                                 :write_permissions "$user==#user"})
                               (first)
                               )
        context (state/context-by-name state-with-context context-name)
        ctx-id (:id context)
        destroy-rq {:domain     c/global-domain-uri
                    :request_id request-id :peer_id peer-id-2 :context_id ctx-id}
        [state msgs] (g/destroy state-with-context source-2 destroy-rq)]
    (is (nil? (state/context-by-name state context-name)))
    (just? msgs
           [(msgs/context-destroyed source peer-id-1 ctx-id c/context-destroyed-explicitly)
            (msgs/context-destroyed source-2 peer-id-2 ctx-id c/context-destroyed-explicitly)
            (success c/global-domain-uri source-2 request-id peer-id-2)
            (m/broadcast {:type    :peer
                          :peer-id peer-id-2
                          :node    node-id}
                         (assoc destroy-rq
                           :type :destroy-context))])))

(deftest context-subscription
  (testing "contexts can be subscribed to"
    (let [
          id1 (gen-identity)
          id2 (gen-identity)

          source (ch->src "source")
          source-2 (ch->src "source-2")
          request-id (request-id!)
          context-name "my context"

          [state msgs] (-> (empty-state)
                           (authenticated source {:request_id request-id :remote-identity id1}))
          peer-id-1 (get-in (first msgs) [:body :peer_id])

          [state msgs] (-> state
                           (authenticated source-2 {:request_id request-id :remote-identity id2}))
          peer-id-2 (get-in (first msgs) [:body :peer_id])

          [state msgs] (-> state
                           (g/create source {:domain            c/global-domain-uri
                                             :request_id        request-id
                                             :peer_id           peer-id-1
                                             :name              context-name
                                             :data              {}
                                             :lifetime          "ownership"
                                             :read_permissions  nil
                                             :write_permissions nil})
                           )
          context-id (context-id :context-created msgs)
          subscribe-rq {:domain     c/global-domain-uri
                        :request_id request-id
                        :peer_id    peer-id-2
                        :context_id context-id}
          [state msgs] (-> state
                           (g/subscribe source-2 subscribe-rq))]
      (just? msgs [(msg/subscribed-context source-2 request-id peer-id-2 context-id {})
                   (m/broadcast {:type    :peer
                                 :peer-id peer-id-2
                                 :node    node-id}
                                (assoc subscribe-rq
                                  :type :subscribe-context))])))
  (testing "unsubscribing from a context stops all evens from it"
    (let [
          id1 (gen-identity)
          id2 (gen-identity)

          source (ch->src "source")
          source-2 (ch->src "source-2")
          request-id (request-id!)
          context-name "my context"

          [state msgs] (-> (empty-state)
                           (authenticated source {:request_id request-id :remote-identity id1}))
          peer-id-1 (get-in (first msgs) [:body :peer_id])

          [state msgs] (-> state
                           (authenticated source-2 {:request_id request-id :remote-identity id2}))
          peer-id-2 (get-in (first msgs) [:body :peer_id])

          [state msgs] (-> state
                           (g/create source {:domain            c/global-domain-uri
                                             :request_id        request-id
                                             :peer_id           peer-id-1
                                             :name              context-name
                                             :data              {}
                                             :lifetime          "ownership"
                                             :read_permissions  nil
                                             :write_permissions nil})
                           )
          context-id (context-id :context-created msgs)

          ;; unsubscribe from the context
          unsub-rq {:domain     c/global-domain-uri
                    :request_id request-id :peer_id peer-id-2 :context_id context-id}
          [state unsub-msgs] (-> state
                                 (g/subscribe source-2 {:domain     c/global-domain-uri
                                                        :request_id request-id :peer_id peer-id-2 :context_id context-id})
                                 (first)
                                 (g/unsubscribe source-2 unsub-rq))

          ;; send an update
          delta {:added {"k" 5}}
          update-rq {:domain     c/global-domain-uri
                     :request_id request-id :peer_id peer-id-1 :context_id context-id
                     :delta      delta}
          [state msgs] (-> state
                           (g/update-ctx source update-rq))]

      (just? unsub-msgs [(success c/global-domain-uri
                                  source-2
                                  request-id
                                  peer-id-2)
                         (m/broadcast {:type    :peer
                                       :peer-id peer-id-2
                                       :node    node-id}
                                      (assoc unsub-rq
                                        :type :unsubscribe-context))])
      (just? msgs [(success c/global-domain-uri
                            source
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
          id1 (gen-identity)
          id2 (gen-identity)

          source (ch->src "source")
          source-2 (ch->src "source-2")
          request-id (request-id!)
          context-name "my context"

          [state msgs] (-> (empty-state)
                           (authenticated source {:request_id request-id :remote-identity id1}))
          peer-id-1 (get-in (first msgs) [:body :peer_id])

          [state msgs] (-> state
                           (authenticated source-2 {:request_id request-id :remote-identity id2}))
          peer-id-2 (get-in (first msgs) [:body :peer_id])

          [state msgs] (-> state
                           (g/create source {:domain            c/global-domain-uri
                                             :request_id        request-id
                                             :peer_id           peer-id-1
                                             :name              context-name
                                             :data              {}
                                             :lifetime          "ownership"
                                             :read_permissions  nil
                                             :write_permissions nil})
                           )
          context-id (context-id :context-created msgs)
          delta {:added {"k" 5}}
          update-rq {:domain     c/global-domain-uri
                     :request_id request-id :peer_id peer-id-1 :context_id context-id
                     :delta      delta}
          [state msgs] (-> state
                           (g/subscribe source-2 {:domain     c/global-domain-uri
                                                  :request_id request-id :peer_id peer-id-2 :context_id context-id})
                           (first)
                           (g/update-ctx source update-rq))]
      (just? msgs [(msg/context-updated source-2
                                        peer-id-2
                                        peer-id-1
                                        context-id
                                        delta)
                   (success c/global-domain-uri
                            source
                            request-id
                            peer-id-1)
                   (m/broadcast {:type    :peer
                                 :peer-id peer-id-1
                                 :node    node-id}
                                (assoc update-rq
                                  :type :update-context
                                  :version (msgs->ctx-version msgs)))])
      ))
  (testing "data can be deleted"
    (let [
          id1 (gen-identity)
          id2 (gen-identity)

          source (ch->src "source")
          source-2 (ch->src "source-2")
          request-id (request-id!)
          context-name "my context"

          [state msgs] (-> (empty-state)
                           (authenticated source {:request_id request-id :remote-identity id1}))
          peer-id-1 (get-in (first msgs) [:body :peer_id])

          [state msgs] (-> state
                           (authenticated source-2 {:request_id request-id :remote-identity id2}))
          peer-id-2 (get-in (first msgs) [:body :peer_id])

          [state msgs] (-> state
                           (g/create source {:domain            c/global-domain-uri
                                             :request_id        request-id
                                             :peer_id           peer-id-1
                                             :name              context-name
                                             :data              {"remove-me" 42}
                                             :lifetime          "ownership"
                                             :read_permissions  nil
                                             :write_permissions nil})
                           )
          context-id (context-id :context-created msgs)
          delta {:removed ["remove-me"]}
          update-rq {:domain     c/global-domain-uri
                     :request_id request-id :peer_id peer-id-1 :context_id context-id
                     :delta      delta}
          [state msgs] (-> state
                           (g/subscribe source-2 {:domain     c/global-domain-uri
                                                  :request_id request-id :peer_id peer-id-2 :context_id context-id})
                           first
                           (g/update-ctx source update-rq))
          context (state/context-by-id state context-id)]
      (is (empty? (:data context)))
      (just? msgs [(msg/context-updated source-2
                                        peer-id-2
                                        peer-id-1
                                        context-id
                                        delta)
                   (success c/global-domain-uri
                            source
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
          id1 (gen-identity)
          id2 (gen-identity)

          source (ch->src "source")
          source-2 (ch->src "source-2")
          request-id (request-id!)
          context-name "my context"

          [state msgs] (-> (empty-state)
                           (authenticated source {:request_id request-id :remote-identity id1}))
          peer-id-1 (get-in (first msgs) [:body :peer_id])

          [state msgs] (-> state
                           (authenticated source-2 {:request_id request-id :remote-identity id2}))
          peer-id-2 (get-in (first msgs) [:body :peer_id])

          old-ctx {"a" 1 "b" 2}
          new-ctx {"a" 2 "c" 3 "d" 4}
          delta {:reset new-ctx}

          [state msgs] (-> state
                           (g/create source {:domain            c/global-domain-uri
                                             :request_id        request-id
                                             :peer_id           peer-id-1
                                             :name              context-name
                                             :data              old-ctx
                                             :lifetime          "ownership"
                                             :read_permissions  nil
                                             :write_permissions nil})
                           )
          context-id (context-id :context-created msgs)
          update-rq {:domain     c/global-domain-uri
                     :request_id request-id :peer_id peer-id-1 :context_id context-id
                     :delta      delta}
          [state msgs] (-> state
                           (g/subscribe source-2 {:domain     c/global-domain-uri
                                                  :request_id request-id :peer_id peer-id-2 :context_id context-id})
                           (first)
                           (g/update-ctx source update-rq))]
      (just? msgs [(msg/context-updated source-2
                                        peer-id-2
                                        peer-id-1
                                        context-id
                                        delta)
                   (success c/global-domain-uri
                            source
                            request-id
                            peer-id-1)
                   (m/broadcast {:type    :peer
                                 :peer-id peer-id-1
                                 :node    node-id}
                                (assoc update-rq
                                  :type :update-context
                                  :version (msgs->ctx-version msgs)))]))))


