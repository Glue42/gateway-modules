(ns gateway.node
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go go-loop]]))
  (:require [gateway.state.peers :as peers]
            [gateway.constants :as constants]
            [gateway.reason :refer [reason throw-reason ex->Reason]]
            [gateway.domain :as domain]
            [taoensso.timbre :as timbre]
            [gateway.common.messages :as msg]
            [gateway.common.measurements :as m]
            [gateway.common.commands :as commands]
    #?(:clj
            [clojure.core.async :as a]
       :cljs [cljs.core.async :as a])))

(defprotocol Node
  (close [this])
  (message [this msg])
  (add-source [this source])
  (remove-source [this source]))

(defn validate-source
  [state source body]
  (let [peer-id (:peer_id body)
        peer (peers/by-id state peer-id)]
    (when (and peer
               (not= source (:source peer)))
      (throw (ex-info
               (str "The original source of peer " peer-id " doesnt match the current source") {})))))

(defn source-removed
  "Sends the ::source-removed command to all domains while keeping the global one last"

  [msg state domains]
  (reduce
    (fn [[state msgs] d]
      (timbre/debug "about to remove source from domain" (domain/info d))
      (if-let [[new-state new-msgs] (domain/handle-message d state msg)]
        (do
          (timbre/debug "removed source from domain" (domain/info d))
          [new-state (into msgs new-msgs)])
        [state msgs]))
    [state []]
    (filter some?
            (conj (mapv (fn [[k v]] (:domain v))
                        (dissoc domains constants/global-domain-uri))
                  (get-in domains [constants/global-domain-uri :domain])))))

(defn nanotime []
  #?(:clj  (System/nanoTime)
     :cljs 0))

(defn handle-message*
  [domains state msg source body measurements]

  (let [start (nanotime)]
    (try
      (if (= ::commands/source-removed (:type body))
        (source-removed msg state domains)

        (if-let [domain (get-in domains [(:domain body constants/global-domain-uri) :domain])]
          (do
            (timbre/debug "Handling message with domain" domain "message: \n"
                          #?(:clj  (with-out-str (clojure.pprint/pprint msg))
                             :cljs (str msg)))
            (validate-source state source body)
            (domain/handle-message domain state msg))

          [state [(msg/error (:domain body)
                             source
                             (:request_id body)
                             (:peer_id body)
                             (reason constants/failure (str "Unable to find domain for message " msg)))]]))


      (finally (when-let [msg-type (:type body)]
                 (when measurements
                   (m/record! measurements :histo (str "inv/" (name msg-type)) (- (nanotime) start))))))))

(defn node-channel
  [buffer-size]
  (timbre/debug "node channel buffer size" buffer-size)
  (let [;; we're fine with volatile here, cause the transducer is applied in the channel lock
        incoming-index (volatile! 0)]
    (a/chan (a/dropping-buffer buffer-size)
            (map (fn [m] {:message        m
                          :incoming-index (vswap! incoming-index inc)})))))