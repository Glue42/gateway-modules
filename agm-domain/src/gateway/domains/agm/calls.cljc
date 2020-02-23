(ns gateway.domains.agm.calls
  (:require [gateway.address :refer [peer->address]]
            [gateway.common.utilities :refer [dissoc-in state->]]
            #?(:cljs [gateway.common.utilities :refer-macros [state->]])
            [gateway.domain :as domain]
            [gateway.domains.agm.constants :as constants]
            [gateway.domains.agm.messages :as msg]
            [gateway.domains.agm.spec.requests :as request-spec]
            [gateway.domains.agm.utilities :refer [validate*]]
            [gateway.id-generators :as ids]
            [gateway.common.messages :as m :refer [error success]]
            [gateway.reason :refer [reason request->Reason]]
            [gateway.state.peers :as peers]
            [gateway.state.spec.common :as common-spec]
            [gateway.state.spec.state :as s]

            #?(:clj  [gateway.metrics.features :as features]
               :cljs [gateway.metrics.features :refer-macros [invoke! yield!] :as features])

            [ghostwheel.core :as g :refer [=> >defn]]))

;;

(defn- invoke
  "Records and generates a method invocation.

  If the callee is local - direct invocation message. Otherwise, we'll broadcast the call request and it
  will be processed by the node that owns the callee peer"

  [state request callee]
  (let [{:keys [request_id peer_id server_id invocation_id method_id arguments arguments_kv context]} request]

    (let [callee (assoc-in callee
                           [:agm-domain :invocations invocation_id]
                           {:caller peer_id :method_id method_id :request_id request_id})
          remote-callee? (not (peers/local-peer? callee))]

      [(peers/set-peer state server_id callee) (if remote-callee?
                                                 [(m/unicast (peer->address (ids/node-id (:ids state)) peer_id)
                                                             {:type :node :node (get-in callee [:source :node])}
                                                             request)]
                                                 [(msg/invoke (:source callee)
                                                              invocation_id
                                                              server_id
                                                              method_id
                                                              peer_id
                                                              arguments
                                                              arguments_kv
                                                              context)])])))


(defn- call*
  [state request invocation-id callee caller]
  (let [{:keys [request_id peer_id server_id method_id]} request]
    (let [caller (assoc-in caller
                           [:agm-domain :calls request_id]
                           {:callee server_id :method_id method_id :invocation_id invocation-id})]

      (features/invoke! request_id
                        (:identity caller)
                        (:identity callee)
                        (get-in callee [:agm-domain :methods server_id method_id])
                        (:arguments_kv request))

      (invoke (peers/set-peer state peer_id caller)
              (assoc request :invocation_id invocation-id)
              (if (= peer_id server_id) caller
                                        callee)))))

(defn- remote-call
  "Processes a remote call request. Ignored if the callee is not owned by this node"

  [state source request]
  (let [{:keys [request_id peer_id server_id invocation_id]} request
        callee (peers/by-id state server_id :agm-domain)]
    (when (peers/local-peer? callee)
      (call* state
             request
             invocation_id
             callee
             (peers/by-id* state peer_id :agm-domain)))))

(defn- local-call
  "Processes a local call request"

  [state source request]
  (let [{:keys [request_id peer_id server_id]} request]
    (let [callee (peers/by-id* state server_id :agm-domain)
          caller (peers/by-id* state peer_id :agm-domain)
          [new-ids invocation-id] (ids/request-id (:ids state))]
      (validate* caller callee request)
      (call* (assoc state :ids new-ids) request invocation-id callee caller))))


(>defn call
  [state source request]
  [::s/state ::common-spec/source ::request-spec/call => ::domain/operation-result]

  (if (m/remote-source? source)
    (remote-call state source request)
    (local-call state source request)))


(defn cancel-invocations
  "Cancels any pending invocations made by caller-id to callee-id.

  Returns a [state [message]]"
  [state receiver caller callee]
  (let [callee-id (:id callee)
        {removed true remaining false} (group-by #(= (:callee (val %)) callee-id)
                                                 (get-in caller [:agm-domain :calls]))
        err-fn #(error constants/agm-domain-uri
                       receiver
                       (key %)
                       (:id caller)
                       (reason constants/agm-invocation-failure "Peer has left while waiting for result"))]
    (if (seq removed)
      [(peers/update-peer state
                          (:id caller)
                          (fn [c]
                            (if (seq remaining)
                              (assoc-in c [:agm-domain :calls] (into {} remaining))
                              (update c :agm-domain dissoc :calls))))
       (when (peers/local-peer? caller) (mapv err-fn removed))]

      [state nil])))

(defn- complete-call
  [state callee-id invocation request success-failure]
  (let [request-id (:request_id invocation)
        caller-id (:caller invocation)
        method-id (:method_id invocation)]
    (when-let [caller (some-> (peers/by-id state caller-id)
                              (dissoc-in [:agm-domain :calls request-id]))]
      (let [state (peers/set-peer state caller-id caller)
            callee (peers/by-id state callee-id)
            method (get-in callee [:agm-domain :methods callee-id method-id])
            success (:success success-failure)
            failure (:failure success-failure)]
        (if (peers/local-peer? caller)
          (if success
            (do
              (features/yield! (:request_id invocation)
                               (:identity caller)
                               (:identity callee)
                               method
                               true
                               (:result success))
              [state [(msg/result (:source caller)
                                  request-id
                                  caller-id
                                  (:result success))]])
            (do
              (features/yield! (:request_id invocation)
                               (:identity caller)
                               (:identity callee)
                               method
                               false
                               (select-keys failure [:reason :reason_uri]))

              [state [(error constants/agm-domain-uri
                             (:source caller)
                             request-id
                             caller-id
                             (request->Reason failure)
                             (:context failure))]]))
          [state [(m/unicast (peer->address (ids/node-id (:ids state)) callee-id)
                             {:type :node :node (get-in caller [:source :node])}
                             request)]])))))

(defn complete-invocation
  "Removes the call/invocation information from the caller and the callee."

  [state invocation-id peer-id request success-failure]
  (let [callee (peers/by-id state peer-id)
        invocation (get-in callee [:agm-domain :invocations invocation-id])]
    (when invocation
      (state-> [(peers/set-peer state
                                peer-id
                                (dissoc-in callee [:agm-domain :invocations invocation-id])) []]
               (complete-call peer-id invocation request success-failure)))))

(defn- yield*
  [state source request]
  (let [{:keys [invocation_id peer_id]} request]
    (peers/by-id* state peer_id :agm-domain)
    (complete-invocation state invocation_id peer_id request {:success request})))

(defn- remote-yield
  [state source request]
  (yield* state source request))

(defn- local-yield
  [state source request]
  (yield* state source request))

(>defn yield
  [state source request]
  [::s/state ::common-spec/source ::request-spec/yield => ::domain/operation-result]

  (if (m/local-source? source)
    (local-yield state source request)
    (remote-yield state source request)))
