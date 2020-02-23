(ns gateway.local-node.core
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go go-loop]]))
  (:require
    [taoensso.timbre :as timbre]
    [gateway.common.messages :as msg]
    [gateway.common.utilities :as util]
    [gateway.common.measurements :as m]
    [gateway.reason :refer [reason throw-reason ex->Reason]]
    [gateway.state.core :as state]
    [gateway.domain :as domain]
    [gateway.constants :as constants]
    [gateway.common.commands :as commands]
    [gateway.node :as node]
    [gateway.state.peers :as peers]
    [gateway.common.event-logger :as event-logger]
    #?(:cljs [gateway.common.event-logger :refer-macros [log-event] :as event-logger])
    [gateway.common.channel-buffers :as buf]

    #?(:clj
             [clojure.core.async :as a]
       :cljs [cljs.core.async :as a])
    #?(:clj
             [clojure.pprint :refer [pprint]]
       :cljs [cljs.pprint :refer [pprint]])

    [gateway.id-generators :as id-gen]))

(def ^:redef -measurements- nil)


;; response processing

(defmulti process-response (fn [response]
                             (let [{:keys [receiver body]} response] (:type receiver))))

(defmethod process-response :cluster [response])
(defmethod process-response :node [response])
(defmethod process-response :peer [response])

(defmethod process-response :local [response]
  (let [{:keys [receiver body]} response]
    (timbre/debug "Sending message" body "to local peer")
    (a/put! (:channel receiver) body)))

(defmethod process-response :default [response]
  (timbre/error "Unable to process response" response))

(deftype LocalNode [ch stop-p]
  node/Node
  (close [this]
    (a/close! ch)
    #?(:clj (deref stop-p 1000 false)))

  (message [this msg]
    (a/put! ch msg))

  (add-source [this source])
  (remove-source [this source]
    (a/put! ch {:origin :local
                :source source
                :body   {:type ::commands/source-removed}})))

(defn- handle-message
  [state domains msg]
  (let [{:keys [source body origin receiver]} msg
        node-id (get-in state [:ids :node-id])]
    (try
      (cond
        (= body {:dump 1}) (timbre/info (with-out-str (pprint state)))
        (= origin :cluster) nil
        :else (node/handle-message* domains state msg source body -measurements-))
      (catch #?(:clj  Exception
                :cljs js/Error) e
        (timbre/error e "Error handling message" msg)
        [state [(msg/error nil
                           source
                           (:request_id body)
                           (:peer_id body)
                           (ex->Reason e constants/failure))]]))))

(defn- request-identity [state request]
  (if-let [peer-id (:peer_id request)]
    (when-let [peer (peers/by-id state peer-id)]
      (:identity peer))

    (when-let [id (:identity request)]
      (assoc id :user (:user request)))))

(defn- process-message
  [state domains msg]
  (let [id (volatile! nil)]
    (try
      (timbre/trace "domain handler processing message" msg)
      (when (event-logger/may-log? :info)
        (let [request (:body msg)
              type (:type request)]
          (vreset! id (request-identity state request))
          (case type
            ::commands/source-removed nil
            :hello (event-logger/log-event {:identity @id :event (util/dissoc-in request [:authentication "secret"])})
            (event-logger/log-event {:identity @id :event request}))))

      (let [[new-state responses] (handle-message state domains msg)
            new-state (or new-state state)]
        (doseq [m responses] (do (process-response m)
                                 (when (event-logger/may-log? :info)
                                   (event-logger/log-event {:identity @id :event (:body m)}))))
        new-state)
      (catch #?(:clj  Exception
                :cljs js/Error) e
        (timbre/error e "error handling message" msg)
        state))))

(defn build-domains
  [domains]
  (into {} (map (fn [d]
                  (timbre/debug "Handling domain" d)
                  (let [info (domain/info d)]
                    [(:uri info) {:domain d
                                  :info   info}])) domains)))

(defn local-node
  [domains options]
  (let [buf-size (:buffer-size options 20000)
        _ (timbre/info "node buffer size:" buf-size)
        ch (a/chan
             (buf/dropping-buffer-with-signal
               buf-size
               (fn [m total-dropped]
                 (timbre/warn "the message" m "is dropped. total dropped so far" total-dropped))))

        domains (build-domains domains)
        _ (timbre/debug (str domains))
        measurements (:measurements options)
        node-id (id-gen/node-id)
        stop-p #?(:clj (promise)) #?(:cljs nil)]

    #?(:clj (alter-var-root #'-measurements- (constantly measurements)))
    (a/go-loop [state (reduce #(domain/init %2 %1)
                              (-> (state/empty-state node-id)
                                  (assoc :registered-domains domains)
                                  (assoc :handler-ch ch))
                              (map :domain (vals domains)))]
      (if-some [msg (a/<! ch)]
        (recur (process-message state domains msg))
        (do
          (reduce #(domain/destroy %2 %1) state (map :domain (vals domains)))
          #?(:clj (deliver stop-p true)))))

    (->LocalNode ch stop-p)))
