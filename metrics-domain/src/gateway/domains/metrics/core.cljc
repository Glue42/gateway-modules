(ns gateway.domains.metrics.core
  (:require [taoensso.timbre :as timbre]
            [gateway.common.messages :refer [success error] :as m]

            [gateway.state.core :as state]
            [gateway.state.peers :as peers]

            [gateway.domains.metrics.constants :as constants]
            [gateway.domains.metrics.filters :as filters]

            [gateway.reason :refer [reason ex->Reason throw-reason request->Reason]]
            [gateway.domain :refer [Domain] :as domain]
            [gateway.metrics.core.def :as metrics]
            [gateway.common.utilities :as util]
            [gateway.common.commands :as commands]))

(defonce required-properties #{:system :service :instance})

(defn- check-identity*
  [repo-identity]
  (let [missing (some #(and (not (contains? repo-identity %)) %) required-properties)]
    (when missing
      (throw-reason constants/metrics-bad-identity
                    (str "Repository is missing required " missing " property")))))

(defn- default-repository-id
  [environment source peer-identity]
  {:machine    (or (:machine peer-identity) (:endpoint source) (:local-ip environment) "127.0.0.1")
   :process-id (:process-id environment)
   :start-time (util/current-time)

   :os         (:os environment)
   :system     (:system peer-identity)
   :service    (:application peer-identity)})

(defn join-peer
  "Local peer is joining the domain.

  Updates the state and generates messages to all existing peers announcing the new peer"
  [state source request environment]
  (let [{request-id    :request_id
         peer-id       :peer_id
         peer-identity :identity
         options       :options} request
        repo-id (merge (default-repository-id environment source peer-identity)
                       (util/keywordize options)
                       (util/keywordize peer-identity))]
    (if (state/in-domain state peer-id :metrics-domain)
      ;; the peer is already in the domain
      [state [(success constants/metrics-domain-uri
                       source
                       request-id
                       peer-id)]]

      ;; we need to join it
      (do
        (check-identity* repo-id)
        (let [state (-> state
                        (state/join-domain peer-id :metrics-domain nil)
                        (assoc-in [:peers peer-id :metrics-domain :repo-id] repo-id))]
          [state [(success constants/metrics-domain-uri
                           source
                           request-id
                           peer-id)]])))))

(defn- remove-peer
  [state peer reason]
  (let [peer-id (:id peer)
        repositories (get-in peer [:metrics-domain :repos])]
    (when (seq repositories)
      (timbre/info "stopping metrics publishing for peer" peer-id)
      (doseq [r repositories]
        (metrics/stop! r)))
    (state/leave-domain state
                        peer-id
                        :metrics-domain)))
(defn leave
  [state source request]
  (let [{:keys [request_id peer_id]} request
        peer (peers/by-id* state peer_id)
        new-state (remove-peer state peer (request->Reason request))]
    [new-state [(success constants/metrics-domain-uri
                         source
                         request_id
                         peer_id)]]))


(declare keywordize-definition)

(defn- keywordize-composite
  [composite]
  (reduce-kv
    #(assoc %1 %2 (keywordize-definition %3))
    {}
    composite))

(defn keywordize-definition
  [definition]
  (reduce-kv
    (fn [data k v]
      (let [kw-k (keyword k)]
        (assoc data kw-k (if (= kw-k :composite)
                           (keywordize-composite v)
                           v))))
    {}
    definition))



(defn ensure-repositories [state repository-factories peer]
  (if-let [repositories (get-in peer [:metrics-domain :repos])]
    [state repositories]

    (let [peer-id (:id peer)
          repo-id (get-in peer [:metrics-domain :repo-id])
          repositories (doall (map #(metrics/start! (metrics/repository % repo-id nil)) repository-factories))]

      [(assoc-in state [:peers peer-id :metrics-domain :repos] repositories) repositories])))

(defn define-metrics
  [state source request repository-factories filters]
  (let [{:keys [peer_id metrics]} request
        peer (peers/by-id* state peer_id)
        repo-id (get-in peer [:metrics-domain :repo-id])
        definitions (->> metrics
                         (map keywordize-definition)
                         (filter #(filters/allowed? filters repo-id (:name %))))]
    (when (seq definitions)
      (timbre/debug "publisher" repo-id "adding metrics" (map :name definitions))
      (let [[state repositories] (ensure-repositories state repository-factories peer)]
        (when (seq repositories)
          [(assoc-in state
                     [:peers peer_id :metrics-domain :repos]
                     (doall (map #(metrics/add! % definitions) repositories))) nil])))))

(defn publish-metrics
  [state source request filters]
  (let [{:keys [peer_id values]} request
        peer (peers/by-id* state peer_id)
        repo-id (get-in peer [:metrics-domain :repo-id])
        values (->> values
                    (map util/keywordize)
                    (filter #(filters/allowed? filters repo-id (:name %))))]
    (when (seq values)
      (when-let [repositories (get-in peer [:metrics-domain :repos])]
        (doseq [r repositories]
          (metrics/publish! r values))))))

(defn source-removed
  "Source has disconnected from the system"

  [state source _]
  (timbre/debug "removing source" source)
  (let [state (reduce #(remove-peer %1 %2 constants/reason-peer-removed)
                      state
                      (peers/by-source state source :metrics-domain))]

    [state nil]))

(defmulti handle-request (fn [state source request & _] (:type request)))

(defmethod handle-request ::domain/join
  [state source request & {:keys [environment]}]
  (join-peer state source request environment))

(defmethod handle-request ::domain/leave
  [state source request & _]
  (leave state source request))

(defmethod handle-request ::commands/source-removed
  [state source request & _]
  (source-removed state source request))

(defmethod handle-request :define
  [state source request & {:keys [repository-factories filters]}]
  (define-metrics state source request repository-factories filters))

(defmethod handle-request :publish
  [state source request & {:keys [filters]}]
  (publish-metrics state source request filters))

(defmethod handle-request :default
  [state source body & _]
  (timbre/error "Unhandled message" body)
  [state [(error constants/metrics-domain-uri
                 source
                 (:request_id body -1)
                 (:peer_id body)
                 (reason constants/metrics-unhandled-message
                         (str "Unhandled message " body)))]])

(deftype MetricsDomain [environment repository-factories filters]
  Domain

  (info [this] {:uri         constants/metrics-domain-uri
                :description ""
                :version     1})
  (init [this state] state)
  (destroy [this state]
    (let [state (reduce #(remove-peer %1 %2 constants/reason-peer-removed)
                        state
                        (peers/peers state :metrics-domain))]
      state))

  (handle-message
    [this state msg]
    (let [{:keys [source body]} msg]
      (try
        (handle-request state
                        source
                        body
                        :environment environment
                        :repository-factories repository-factories
                        :filters filters)
        (catch #?(:clj  Exception
                  :cljs :default) e
          (when-not (ex-data e) (timbre/error e "Error processing message" msg))
          [state [(error constants/metrics-domain-uri
                         source
                         (:request_id body)
                         (:peer_id body)
                         (ex->Reason e constants/metrics-failure))]]))))

  (state->messages [this state]))

(defn metrics-domain [environment repository-factories filters]
  (timbre/debug "initializing metrics domain with filters" filters)
  (->MetricsDomain environment repository-factories filters))