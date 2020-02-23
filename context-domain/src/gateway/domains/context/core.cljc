(ns gateway.domains.context.core
  (:require [gateway.domains.context.messages :as msg]
            [gateway.domains.context.constants :as constants]
            [gateway.common.context.state :as state]
            [gateway.common.messages :as m]
            [gateway.common.context.ops :as ops]
            [gateway.common.context.constants :as cc]

            [gateway.domains.global.messages :as gmsg]
            [gateway.domains.global.constants :as gc]

            [gateway.common.utilities :as util]
            [gateway.restrictions :as restrictions]
            [gateway.domain :refer [Domain] :as domain]
            [gateway.address :refer [peer->address]]

            [gateway.state.peers :as peers]
            [gateway.state.core :as core-state]

            [gateway.id-generators :as ids]
            [clojure.walk :refer [keywordize-keys]]
            [gateway.reason :refer [ex->Reason ->Reason throw-reason reason request->Reason]]
            [gateway.common.utilities :refer [state->]]
            #?(:cljs [gateway.common.utilities :refer-macros [state->]])

            [taoensso.timbre :as timbre]

            [ghostwheel.core :as g :refer [>defn >defn- >fdef => | <- ?]]
            [gateway.state.spec.state :as s]
            [gateway.state.spec.common :as common-spec]
            [gateway.common.spec.requests :as cr-spec]
            [gateway.domains.context.spec.requests :as request-spec]
            [gateway.common.spec.messages :as message-spec]
            [gateway.domains.context.spec.messages]
            [gateway.domains.global.spec.messages]
            [gateway.state.spec.state :as state-spec]))

(def update-ctx (partial ops/update-ctx constants/context-domain-uri))
(def create (partial ops/create constants/context-domain-uri))
(def destroy (partial ops/destroy constants/context-domain-uri))
(def subscribe (partial ops/subscribe constants/context-domain-uri))
(def unsubscribe (partial ops/unsubscribe constants/context-domain-uri))
(def remove-peer (partial ops/remove-peer constants/context-domain-uri))

;; conversion functions

(defn ctx->request
  [peer-id ctx]
  (msg/create-context nil
                      peer-id
                      (:name ctx)
                      (:data ctx)
                      (:lifetime ctx)
                      (:read_permissions ctx)
                      (:write_permissions ctx)
                      (:version ctx)))

(defn- announce-peer
  [state receiver peer-id was-in-compat?]
  (let [peer (peers/by-id* state peer-id :context-domain)
        announce-messages (cond->> (filter (partial ops/compatibility-mode-filter state)
                                           (peers/announce-peer constants/context-domain-uri :context-domain state receiver peer))
                                   ;; if the peer was already implicitly joined, then its already announced
                                   was-in-compat? (remove (fn [m] (= peer-id (-> m :body :new_peer_id)))))

        ]
    (->> (if (not was-in-compat?)                           ;; if the peer was implicitly joined already, it has received the contexts
           (ops/announce-contexts (ops/peer->domain-uri peer) state peer)
           ())
         (concat announce-messages)
         (vec))))

(defn- join*
  [state source peer-id peer-identity restrictions was-in-compat?]
  (let [parsed-restrictions (restrictions/parse restrictions)
        state' (cond-> (core-state/join-domain state peer-id :context-domain parsed-restrictions)
                       was-in-compat? (peers/update-peer peer-id #(util/dissoc-in % [:options :context-compatibility-mode?])))]
    [state' (announce-peer state' source peer-id was-in-compat?)]))

(defn- local-join
  [state source request]
  (let [{request-id :request_id peer-id :peer_id peer-identity :identity restrictions :restrictions} request
        context-compat-mode? (get-in request [:options :context-compatibility-mode?])

        was-in-compat? (some-> (peers/by-id state peer-id) :options :context-compatibility-mode?)
        already-here? (core-state/in-domain state peer-id :context-domain)]
    (if (and already-here? (not was-in-compat?))
      ;; the peer is already in the domain
      [state [(msg/success source
                           request-id
                           peer-id)]]

      ;; we need to join it
      (let [[state' msgs] (join* state source peer-id peer-identity restrictions (and already-here? was-in-compat?))
            peer (peers/by-id state' peer-id)]
        [state' (cond-> (conj msgs
                              (m/broadcast (peer->address (ids/node-id (:ids state)) peer-id)
                                           (cond-> (assoc request :type :join)
                                                   (:options peer) (assoc :options (:options peer)))))
                        (not context-compat-mode?) (conj (msg/success source
                                                                      request-id
                                                                      peer-id)))]))))
(defn- remote-join
  [state source request]
  (let [{peer-id :peer_id peer-identity :identity restrictions :restrictions} request
        was-in-compat? (some-> (peers/by-id state peer-id) :options :context-compatibility-mode?)]
    (when-not (and (not was-in-compat?)
                   (core-state/in-domain state peer-id :context-domain))
      (join* state source peer-id peer-identity restrictions was-in-compat?))))

(>defn join
  [state source request]
  [::s/state ::common-spec/source ::cr-spec/join => ::domain/operation-result]
  (if (m/remote-source? source)
    (remote-join state source request)
    (local-join state source request)))

(>defn source-removed
  "Source has disconnected from the system"

  [state source _]
  [::s/state ::common-spec/source any? => ::domain/operation-result]
  (timbre/debug "removing source from the context domain")
  (let [affected (sequence (peers/by-source state source :context-domain))
        state-without-affected (transduce (map :id)
                                          (completing #(core-state/leave-domain %1 %2 :context-domain))
                                          state
                                          affected)
        leave-rq {:request_id  nil
                  :domain      gc/global-domain-uri
                  :type        :leave
                  :destination constants/context-domain-uri
                  :reason_uri  (:uri constants/reason-peer-removed)
                  :reason      (:message constants/reason-peer-removed)}

        r (reduce (fn [sm peer]
                    (state-> sm
                             (remove-peer peer constants/reason-peer-removed true)
                             ((fn [s] [s (m/broadcast (peer->address (ids/node-id (:ids s)) (:id peer))
                                                      (assoc leave-rq :peer_id (:id peer)))]))))
                  [state-without-affected nil]
                  affected)]
    (timbre/debug "removed source from context domain")
    r))

(defn- remote-leave
  [state source request]
  (let [{:keys [peer_id]} request]
    (when-let [peer (peers/by-id state peer_id)]
      (remove-peer state peer (request->Reason request) false))))

(defn- local-leave
  [state source request]
  (let [{:keys [request_id peer_id]} request]
    (let [[new-state msgs] (remove-peer state
                                        (peers/by-id* state peer_id)
                                        (request->Reason request)
                                        false)]
      [new-state (conj msgs
                       (msg/success source
                                    request_id
                                    peer_id)
                       (m/broadcast (peer->address (ids/node-id (:ids state)) peer_id)
                                    (assoc request :type :leave)))])))

(defn- leave
  [state source request]
  (if (m/remote-source? source)
    (remote-leave state source request)
    (local-leave state source request)))

(defmulti handle-request (fn [state source request] (:type request)))

(defmethod handle-request ::domain/join
  [state source request]
  (join state source request))

(defmethod handle-request ::domain/leave
  [state source request]
  (leave state source request))

(defmethod handle-request :create-context
  [state source request]
  (create state source request))

(defmethod handle-request :update-context
  [state source request]
  (update-ctx state source request))

(defmethod handle-request :subscribe-context
  [state source request]
  (subscribe state source request))

(defmethod handle-request :unsubscribe-context
  [state source request]
  (unsubscribe state source request))

(defmethod handle-request :destroy-context
  [state source request]
  (destroy state source request))

(defmethod handle-request :default
  [state source body _ _ _]
  (timbre/error "Unhandled message" body)
  [state [(msg/error source
                     (:request_id body -1)
                     (:peer_id body)
                     (reason (cc/context-unhandled-message constants/context-domain-uri)
                             (str "Unhandled message " body)))]])


(defn -handle-message
  [state msg]
  ;[::s/state ::message-spec/incoming-message => ::domain/operation-result]

  (let [{:keys [source body]} msg]
    (try
      (handle-request state source body)
      (catch #?(:clj  Exception
                :cljs :default) e
        (when-not (ex-data e) (timbre/error e "Error handling request" msg))
        (when (m/local-source? source)
          [state [(msg/error source
                             (:request_id body)
                             (:peer_id body)
                             (ex->Reason e constants/failure))]])))))

(defn state->messages
  [state]
  (let [node-id (ids/node-id (:ids state))
        join-messages (->> (peers/peers state :context-domain)
                           (filter #(m/local-source? (:source %)))
                           (map #(let [peer-id (:id %)]
                                   (m/unicast (peer->address node-id peer-id) nil
                                              (assoc (gmsg/join nil
                                                                peer-id
                                                                (get-in % [:context-domain :restrictions])
                                                                constants/context-domain-uri
                                                                (:identity %))
                                                :options (:options %))))))

        create-messages (->> (state/contexts state)
                             (filter :local?)
                             (mapcat (fn [ctx]
                                       (let [members (:members ctx)
                                             owner-id (:owner ctx (first members))]
                                         (concat [(m/unicast (peer->address node-id owner-id) nil
                                                             (ctx->request owner-id ctx))]
                                                 (map (fn [member]
                                                        (m/unicast (peer->address node-id owner-id) nil
                                                                   (msg/subscribe nil member (:id ctx)))) members))))))


        messages (concat join-messages create-messages)]
    (timbre/debug "Node" node-id "sending state" messages)
    messages))

(deftype ContextDomain []
  Domain

  (info [this] {:uri         constants/context-domain-uri
                :description ""
                :version     1})
  (init [this state] state)
  (destroy [this state] state)

  (handle-message [this state msg] (-handle-message state msg))

  (state->messages [this state]
    (state->messages state)))


(defn context-domain [] (->ContextDomain))