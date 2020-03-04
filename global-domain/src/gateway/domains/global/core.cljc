(ns gateway.domains.global.core
  (:require [taoensso.timbre :as timbre]

            [gateway.domains.global.spec.messages]
            [gateway.domains.global.internal :as internal]
            [gateway.common.messages :refer [error] :as m]
            [gateway.domains.global.messages :as msg]
            [gateway.common.context.ops :as ops]

            [gateway.common.measurements :as measurements]

            [gateway.state.core :as state]
            [gateway.state.peers :as peers]

            [gateway.auth.core :as auth]
            [clojure.core.async :as a]
            [gateway.domains.global.constants :as constants]
            [gateway.constants :as c]
            [gateway.reason :refer [reason ex->Reason throw-reason]]
            [gateway.domain :refer [Domain] :as domain]
            [gateway.id-generators :as ids]
            [gateway.common.utilities :refer [state->]]
            [gateway.common.tokens :as tokens]
            [gateway.common.peer-identity :as peer-identity]
            #?(:cljs [gateway.common.utilities :refer-macros [state->]])
            [gateway.common.commands :as commands]

            [clojure.walk :refer [keywordize-keys]]
            [promesa.core :as p]

            [ghostwheel.core :as g :refer [>defn >defn- >fdef => | <- ?]]
            [gateway.state.spec.state :as s]
            [gateway.common.spec.messages :as message-spec]

            [clojure.spec.alpha :as spec]))

(def ^:redef -measurements- nil)

(defn- limit-exceeded
  [state user limits]
  (when-let [max-connections-per-user (:max-connections-per-user limits)]
    (>= (count (peers/by-user state user)) max-connections-per-user)))

(defn- resolve-qmarks [identity-map peer-id]
  (reduce-kv #(assoc %1 %2 (if (= "?" %3) peer-id %3)) {} identity-map))

(defn- local-join
  [state source request]
  (let [{request-id :request_id peer-id :peer_id domain-uri :destination} request
        err (fn [err-uri err-msg] (error constants/global-domain-uri
                                         source request-id peer-id
                                         (reason err-uri err-msg)))]
    (if-let [peer-identity (:identity (peers/by-id state peer-id))]
      (if-let [destination-domain (state/domain-for-uri state domain-uri)]
        (domain/handle-message destination-domain
                               state
                               {:origin :local
                                :source source
                                :body   (-> request
                                            (assoc :identity peer-identity)
                                            (assoc :type ::domain/join))})
        [state [(err constants/global-invalid-domain (str "Unable to join missing domain " domain-uri))]])
      [state [(err constants/global-invalid-peer (str "Unable to find peer with id " peer-id))]])))

(defn- remote-join
  [state source request]
  (let [{peer-id :peer_id domain-uri :destination options :options} request]

    (when-let [destination-domain (state/domain-for-uri state domain-uri)]
      (let [[state _] (peers/ensure-peer-with-id state source peer-id (:identity request) nil options)]
        (domain/handle-message destination-domain
                               state
                               {:origin :cluster
                                :source source
                                :body   (assoc request :type ::domain/join)})))))

(defn- forward-join
  [state source request]
  (if (m/remote-source? source)
    (remote-join state source request)
    (local-join state source request)))

(defn context-compatibility
  [state source request]
  (local-join state source request))

(defn authenticated
  [state source authentication-response configuration environment]
  (let [{request-id      :request_id
         remote-identity :remote-identity
         gw-request      :gw-request
         user            :user
         access-token    :access_token
         options         :options} authentication-response

        limits (:limits configuration)

        resolved-identity (merge {:machine (or (:endpoint source) (:local-ip environment))}
                                 (peer-identity/keywordize-id remote-identity)
                                 (select-keys authentication-response [:user :login]))

        [resolved-identity state] (if (:instance resolved-identity)
                                    [resolved-identity state]
                                    (let [[new-ids instance-id] (ids/instance-id (:ids state))]
                                      [(assoc resolved-identity :instance instance-id)
                                       (assoc state :ids new-ids)]))

        options (assoc options :context-compatibility-mode? true)]
    (try
      (when-let [existing-peer (peers/by-identity state resolved-identity)]
        (throw-reason constants/global-already-seen "Hello already received once"))

      (peer-identity/check-identity* resolved-identity)

      (when (limit-exceeded state user limits)
        (throw-reason constants/global-limits-exceeded "Maximum number of peers per user have been reached"))

      (let [[new-ids peer-id] (ids/peer-id (:ids state))
            resolved-identity (resolve-qmarks resolved-identity peer-id)
            [state peer] (peers/ensure-peer-with-id (assoc state :ids new-ids)
                                                    source peer-id resolved-identity gw-request
                                                    options)
            welcome (msg/welcome source
                                 request-id
                                 (:id peer)
                                 (map :info (vals (:registered-domains state)))
                                 resolved-identity
                                 (-> (when-let [info (:info configuration)] {:info info})
                                     (merge (when access-token {:access_token access-token}))))

            ann-f (partial ops/announce-contexts constants/global-domain-uri)]

        (when -measurements-
          (measurements/record! -measurements- :number "global/peer-count" (peers/peer-count state)))

        (state-> [state [welcome]]
                 ;(ann-f peer)
                 (context-compatibility source {:request_id  request-id
                                                :peer_id     (:id peer)
                                                :identity    resolved-identity
                                                :options     options
                                                :destination c/context-domain-uri
                                                :domain      constants/global-domain-uri})))

      (catch #?(:clj  Exception
                :cljs :default) e
        (when (m/local-source? source)
          [state [(error constants/global-domain-uri
                         source
                         request-id
                         nil
                         (ex->Reason e constants/failure))]])))))

(defn authentication-failed
  [state source authentication-response]
  (let [{msg        :message
         request-id :request_id} authentication-response]
    [state [(error constants/global-domain-uri
                   source
                   request-id
                   nil
                   (reason constants/global-authentication-failure msg))]]))

(defn authentication-request
  [state source request]
  (let [{request-id     :request_id
         authentication :authentication} request]
    [state [(msg/authentication-request constants/global-domain-uri
                                        source
                                        request-id
                                        nil
                                        authentication)]]))



(defn- local-leave
  [state source request]
  (let [{request-id :request_id
         peer-id    :peer_id
         domain-uri :destination} request
        err (fn [reason] (error constants/global-domain-uri
                                source
                                request-id
                                peer-id
                                reason))]
    (if (peers/by-id state peer-id)
      (if-let [destination-domain (state/domain-for-uri state domain-uri)]
        (domain/handle-message destination-domain
                               state
                               {:origin :local
                                :source source
                                :body   (assoc request :type ::domain/leave)})
        [state [(err (reason constants/global-invalid-domain (str "Unable to leave missing domain " domain-uri)))]])
      [state [(err (reason constants/global-invalid-peer (str "Unable to find peer with id " peer-id)))]])))

(defn- remote-leave
  [state source request]
  (let [{peer-id    :peer_id
         domain-uri :destination} request]
    (when (peers/by-id state peer-id)
      (when-let [destination-domain (state/domain-for-uri state domain-uri)]
        (domain/handle-message destination-domain
                               state
                               {:origin :local
                                :source source
                                :body   (assoc request :type ::domain/leave)})))))

(defn- forward-leave
  [state source request]
  (if (m/remote-source? source)
    (remote-leave state source request)
    (local-leave state source request)))

(defn source-removed
  [state source request]
  (timbre/debug "removing source from global domain")
  (let [node-id (get-in state [:ids :node-id])
        [state' _ :as r] (reduce (fn [agg p]
                                   (state-> agg
                                            ((fn [s] [(peers/remove-peer s p) nil]))
                                            ;(remove-peer p constants/reason-peer-removed true)
                                            ((fn [_] [nil (when (m/local-source? source)
                                                            (m/broadcast {:type    :peer
                                                                          :peer-id (:id p)
                                                                          :node    node-id}
                                                                         request))]))))
                                 [state nil]
                                 (peers/by-source state source))]
    (timbre/debug "removed source from global domain")
    (when -measurements-
      (measurements/record! -measurements- :number "global/peer-count" (peers/peer-count state')))
    r))

(defn- impersonate
  "The gateway token authentication allows a peer to assume part (currently the user) of the
  identity of the peer that has requested its creation"

  [remote-identity impersonate-identity]
  (if-let [user (:user impersonate-identity)]
    (assoc remote-identity :user user)
    remote-identity))

(defn- handle-gw-token-authentication
  [state source request-id remote-identity token environment]

  (try
    (let [gw-token (tokens/->token state token)]

      (case (:type gw-token)
        :gw-request (let [gw-request (:gw-request gw-token)
                          [state _] (state/remove-gateway-request state (:id gw-request))]
                      (if-not gw-request
                        [state [(error constants/global-domain-uri
                                       source
                                       request-id
                                       nil
                                       (reason constants/global-authentication-failure "The token refers to a missing gateway request"))]]

                        (authenticated state
                                       source
                                       {:request_id      request-id
                                        :remote-identity (impersonate remote-identity (:impersonate-peer gw-token))
                                        :gw-request      gw-request}
                                       nil
                                       environment)))


        :authentication (if-let [user (:user gw-token)]
                          (authenticated state
                                         source
                                         {:request_id      request-id
                                          :remote-identity (assoc remote-identity :user user)}
                                         nil
                                         environment)
                          [state [(error constants/global-domain-uri
                                         source
                                         request-id
                                         nil
                                         (reason constants/global-missing-parent "Missing or invalid impersonation information"))]])

        [state [(error constants/global-domain-uri
                       source
                       request-id
                       nil
                       (reason constants/global-authentication-failure (str "Invalid gateway token type: " (:type gw-token))))]]))

    (catch #?(:clj  Exception
              :cljs js/Error) e
      [state [(error constants/global-domain-uri
                     source
                     request-id
                     nil
                     (reason constants/global-authentication-failure "Invalid gateway token"))]])))

(defn handle-hello
  "Handles a hello message.

  If the authentication is 'gateway-token' its handled immediately, otherwise the authenticator is invoked"
  [state source request authenticators ch environment]
  (let [{request-id      :request_id
         remote-identity :identity
         authentication  :authentication} request
        authentication (keywordize-keys authentication)]
    (if-let [token (auth/gateway-token authentication)]
      (handle-gw-token-authentication state source request-id remote-identity token environment)
      (let [requested-provider (keyword (:provider authentication (:default authenticators)))]

        (if-let [authenticator (get-in authenticators [:available requested-provider])]
          (do
            (-> (auth/authenticate authenticator {:request_id      request-id
                                                  :remote-identity remote-identity
                                                  :authentication  authentication})
                (p/then (fn [msg] (update msg :type (fn [t] (case t
                                                              :success ::internal/authenticated
                                                              :continue ::internal/authentication-request
                                                              t)))))
                (p/catch (fn [err] (-> (ex-data err)
                                       (assoc :type ::internal/authentication-failed))))
                (p/then (fn [msg] (a/put! ch {:origin :local
                                              :source source
                                              :body   (assoc msg :request_id request-id
                                                                 :remote-identity remote-identity)}))))
            [state nil])

          [state [(error constants/global-domain-uri
                         source
                         request-id
                         nil
                         (reason constants/global-authentication-failure
                                 (str "Requested authentication provider " requested-provider " is not available")))]])))))

(def update-ctx (partial ops/update-ctx constants/global-domain-uri))
(def create (partial ops/create constants/global-domain-uri))
(def destroy (partial ops/destroy constants/global-domain-uri))
(def subscribe (partial ops/subscribe constants/global-domain-uri))
(def unsubscribe (partial ops/unsubscribe constants/global-domain-uri))

(defmulti handle-request (fn [state source request authenticators configuration environment] (:type request)))

(defmethod handle-request :hello
  [state source request authenticators _ environment]
  (handle-hello state source request authenticators (:handler-ch state) environment))

(defmethod handle-request :join
  [state source request _ _ _]
  (forward-join state source request))

(defmethod handle-request :leave
  [state source request _ _ _]
  (forward-leave state source request))

(defmethod handle-request ::internal/authenticated
  [state source request _ configuration environment]
  (authenticated state source request configuration environment))

(defmethod handle-request ::internal/authentication-failed
  [state source request _ _ _]
  (authentication-failed state source request))

(defmethod handle-request ::internal/authentication-request
  [state source request _ _ _]
  (authentication-request state source request))

(defmethod handle-request :create-context
  [state source request _ _ _]
  (create state source request))

(defmethod handle-request :update-context
  [state source request _ _ _]
  (update-ctx state source request))

(defmethod handle-request :subscribe-context
  [state source request _ _ _]
  (subscribe state source request))

(defmethod handle-request :unsubscribe-context
  [state source request _ _ _]
  (unsubscribe state source request))

(defmethod handle-request :destroy-context
  [state source request _ _ _]
  (destroy state source request))

(defmethod handle-request :ping
  [state source request _ _ _]
  [state])

(defmethod handle-request ::commands/source-removed
  [state source request _ _ _]
  (source-removed state source request))

(defn- token-ttl
  [configuration]
  (let [ttl (* (get-in configuration [:authentication :token-ttl] 0) 1000)]
    (if (pos? ttl)
      ttl
      tokens/*ttl*)))

(defmethod handle-request :create-token
  [state source {:keys [request_id peer_id]} _ configuration _]
  (let [peer (peers/by-id* state peer_id)]
    [state [(m/token constants/global-domain-uri
                     source
                     request_id
                     peer_id
                     (with-redefs [tokens/*ttl* (token-ttl configuration)]
                       (tokens/for-authentication state
                                                  (:identity peer))))]]))

(defmethod handle-request :default
  [state source body _ _ _]
  (timbre/error "Unhandled message" body)
  [state [(error constants/global-domain-uri
                 source
                 (:request_id body -1)
                 (:peer_id body)
                 (reason constants/global-unhandled-message
                         (str "Unhandled message " body)))]])

(>defn -handle-message
  [authenticators configuration environment state msg]
  [map? (spec/nilable map?) map? ::s/state ::message-spec/incoming-message => ::domain/operation-result]
  (let [{:keys [source body]} msg]
    (try
      (handle-request state source body authenticators configuration environment)
      (catch #?(:clj  Exception
                :cljs js/Error) e
        (when-not (ex-data e) (timbre/error e "Error processing message" msg))
        [state [(error constants/global-domain-uri
                       source
                       (:request_id body -1)
                       (:peer_id body)
                       (ex->Reason e constants/failure))]]))))

(deftype GlobalDomain [environment authenticators configuration measurements]
  Domain

  (info [this] {:uri         constants/global-domain-uri
                :description ""
                :version     1})

  (init [this state]
    #?(:clj (alter-var-root #'-measurements- (constantly measurements)))
    state)
  (destroy [this state]
    #?(:clj (alter-var-root #'-measurements- (constantly nil)))
    state)

  (handle-message [this state msg]
    (-handle-message authenticators configuration environment state msg))

  (state->messages [this state]))

(defn global-domain [authenticators configuration measurements environment]
  (->GlobalDomain environment
                  authenticators
                  configuration
                  measurements))
