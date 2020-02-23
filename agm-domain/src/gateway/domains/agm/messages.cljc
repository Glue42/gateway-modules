(ns gateway.domains.agm.messages
  (:require
    [gateway.common.messages :as m]
    #?(:cljs [gateway.reason :refer [Reason]])
    [gateway.domains.agm.constants :as constants])
  #?(:clj
     (:import [gateway.reason Reason])))

(defn methods-added
  ([recipient to-peer-id server-id methods source-type]
   (m/outgoing recipient (methods-added to-peer-id server-id methods source-type)))
  ([to-peer-id server-id methods source-type]
   {:domain      constants/agm-domain-uri
    :type        :methods-added
    :peer_id     to-peer-id
    :source_type source-type
    :server_id   server-id
    :methods     methods}))

(defn register
  [request-id server-id methods]
  {:domain     constants/agm-domain-uri
   :type       :register
   :request_id request-id
   :peer_id    server-id
   :methods    methods})

(defn methods-removed
  (
   [recipient from-peer-id server-id method-ids]
   (m/outgoing recipient (methods-removed from-peer-id server-id method-ids)))
  (
   [from-peer-id server-id method-ids]
   {:domain    constants/agm-domain-uri
    :type      :methods-removed
    :peer_id   from-peer-id
    :server_id server-id
    :methods   method-ids}))

(defn invoke
  (
   [recipient invocation-id peer-id method-id caller-id arguments arguments-kv ctx]
   (m/outgoing recipient (invoke invocation-id peer-id method-id caller-id arguments arguments-kv ctx)))
  (
   [invocation-id peer-id method-id caller-id arguments arguments-kv ctx]
   {:domain        constants/agm-domain-uri
    :type          :invoke
    :invocation_id invocation-id
    :peer_id       peer-id
    :method_id     method-id
    :caller_id     caller-id
    :arguments     arguments
    :arguments_kv  arguments-kv
    :context       ctx}))

(defn result
  (
   [recipient request-id caller-id results]
   (m/outgoing recipient (result request-id caller-id results)))
  (
   [request-id caller-id results]
   {:domain     constants/agm-domain-uri
    :type       :result
    :request_id request-id
    :peer_id    caller-id
    :result     results}))

(defn add-interest
  (
   [recipient subscription-id server-id method-id subscriber-id arguments argumentsKV flags ctx]
   (m/outgoing recipient (add-interest subscription-id server-id method-id subscriber-id arguments argumentsKV flags ctx)))
  (
   [subscription-id server-id method-id subscriber-id arguments arguments-kv flags ctx]
   {:domain          constants/agm-domain-uri
    :type            :add-interest
    :subscription_id subscription-id
    :peer_id         server-id
    :method_id       method-id
    :caller_id       subscriber-id
    :arguments       arguments
    :arguments_kv    arguments-kv
    :flags           flags
    :context         ctx}))

(defn remove-interest
  (
   [recipient subscription-id server-id method-id subscriber-id ^Reason reason]
   (m/outgoing recipient (remove-interest subscription-id server-id method-id subscriber-id reason)))
  (
   [subscription-id server-id method-id subscriber-id ^Reason reason]
   {:domain          constants/agm-domain-uri
    :type            :remove-interest
    :subscription_id subscription-id
    :peer_id         server-id
    :method_id       method-id
    :caller_id       subscriber-id
    :reason_uri      (:uri reason)
    :reason          (:message reason)}))

(defn subscribed
  (
   [recipient request-id subscriber-id subscription-id]
   (m/outgoing recipient (subscribed request-id subscriber-id subscription-id)))
  (
   [request-id subscriber-id subscription-id]
   {:domain          constants/agm-domain-uri
    :type            :subscribed
    :request_id      request-id
    :peer_id         subscriber-id
    :subscription_id subscription-id}))

(defn event
  (
   [recipient subscriber-id subscription-id oob? sqn snapshot? data]
   (m/outgoing recipient (event subscriber-id subscription-id oob? sqn snapshot? data)))
  (
   [subscriber-id subscription-id oob? sqn snapshot? data]
   {:domain          constants/agm-domain-uri
    :type            :event
    :peer_id         subscriber-id
    :subscription_id subscription-id
    :oob             oob?
    :sequence        sqn
    :snapshot        snapshot?
    :data            data}))

(defn subscription-cancelled
  (
   [recipient subscription-id subscriber-id ^Reason reason]
   (m/outgoing recipient (subscription-cancelled subscription-id subscriber-id reason)))
  (
   [subscription-id subscriber-id ^Reason reason]
   {:domain          constants/agm-domain-uri
    :type            :subscription-cancelled
    :subscription_id subscription-id
    :peer_id         subscriber-id
    :reason_uri      (:uri reason)
    :reason          (:message reason)}))

(def peer-removed (partial m/peer-removed constants/agm-domain-uri))
(def peer-added (partial m/peer-added constants/agm-domain-uri))
(def success (partial m/success constants/agm-domain-uri))
(def error (partial m/error constants/agm-domain-uri))