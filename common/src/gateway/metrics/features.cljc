(ns gateway.metrics.features
  (:require [gateway.metrics.filters :as filters]
            [taoensso.timbre :as timbre]))


(defonce interop-feature "interop")
(defonce ^:dynamic *config* {:publisher nil})

#?(:clj
   (defn set-config! [config] (alter-var-root #'*config* (constantly config)))
   :cljs
   (defn set-config! [config] (set! *config* config)))

(defn interop-feature-enabled? []
  (let [c *config*]
    (and (some? (:publisher c))
         (get-in c [:interop :enabled]))))

(defn enabled? [config]
  (some? (:publisher config)))

(defn filter-args [method-arg-filters args]
  (filters/filter-args (:arguments method-arg-filters) args))

(defn -feature [config name action value]
  (when-let [p (:publisher config)]
    (p name action value)))

(defn feature! [name action value]
  (let [config *config*]
    (when-let [p (:publisher config)]
      (p name action value))))

(defn -invoke! [config a]
  (when (enabled? config)
    (let [{:keys [method]} @a
          method-name (:name method)
          interop-config (get-in config [:interop :invoke])]

      (if-let [method-filter-fn (:method-filter-fn interop-config)]
        (when (method-filter-fn method-name)
          (let [arg-filter-fn (:arg-filter-fn interop-config)]
            (-feature config interop-feature "invoke" (-> @a
                                                          (update :args #(if arg-filter-fn (arg-filter-fn method-name %) %))
                                                          (assoc :method method-name)))))

        (when-let [method-arg-filters (filters/method-allowed? (:filters interop-config) method-name)]
          (-feature config interop-feature "invoke" (-> @a
                                                        (update :args #(filter-args method-arg-filters %))
                                                        (assoc :method method-name))))))))
(defmacro invoke!
  [request-id caller server method args]
  (let [config 'gateway.metrics.features/*config*]
    `(-invoke! ~config (delay ~{:request_id request-id
                                :caller     caller
                                :server     server
                                :method     method
                                :args       args}))))

(defn -yield! [config a]
  (when (enabled? config)
    (let [{:keys [method]} @a
          method-name (:name method)
          interop-config (get-in config [:interop :invoke])]

      (if-let [method-filter-fn (:method-filter-fn interop-config)]
        (when (method-filter-fn method-name)
          (-feature config interop-feature "yield" (assoc @a :method method-name)))

        (when (filters/method-allowed? (:filters interop-config) method-name)
          (-feature config interop-feature "yield" (assoc @a :method method-name)))))))


(defmacro yield!
  [request-id caller server method success? result]
  (let [config 'gateway.metrics.features/*config*]
    `(-yield! ~config (delay ~{:request_id request-id
                               :caller     caller
                               :server     server
                               :method     method
                               :success    success?
                               :result     result}))))

(defn -subscribe! [config a]
  (when (enabled? config)
    (let [{:keys [method]} @a
          method-name (:name method)
          interop-config (get-in config [:interop :invoke])]

      (if-let [method-filter-fn (:method-filter-fn interop-config)]
        (when (method-filter-fn method-name)
          (let [arg-filter-fn (:arg-filter-fn interop-config)]
            (-feature config interop-feature "subscribe" (-> @a
                                                             (update :args #(if arg-filter-fn (arg-filter-fn method-name %) %))
                                                             (assoc :method method-name)))))

        (when-let [method-arg-filters (filters/method-allowed? (:filters interop-config) method-name)]
          (-feature config interop-feature "subscribe" (-> @a
                                                           (update :args #(filter-args method-arg-filters %))
                                                           (assoc :method method-name))))))))
(defmacro subscribe!
  [subscription-id subscriber server method args]
  (let [config 'gateway.metrics.features/*config*]
    `(-subscribe! ~config (delay ~{:subscription_id subscription-id
                                   :subscriber      subscriber
                                   :server          server
                                   :method          method
                                   :args            args}))))

(defn -unsubscribe! [config a]
  (when (enabled? config)
    (let [{:keys [method]} @a
          method-name (:name method)
          interop-config (get-in config [:interop :invoke])]

      (if-let [method-filter-fn (:method-filter-fn interop-config)]
        (when (method-filter-fn method-name)
          (-feature config interop-feature "unsubscribe" (assoc @a :method method-name)))

        (when (filters/method-allowed? (:filters interop-config) method-name)
          (-feature config interop-feature "unsubscribe" (assoc @a :method method-name)))))))


(defmacro unsubscribe!
  [subscription-id subscriber server method]
  (let [config 'gateway.metrics.features/*config*]
    `(-unsubscribe! ~config (delay ~{:subscription_id subscription-id
                                     :subscriber      subscriber
                                     :server          server
                                     :method          method}))))

(defn -event! [config a]
  (when (enabled? config)
    (let [{:keys [method]} @a
          method-name (:name method)
          interop-config (get-in config [:interop :invoke])]

      (if-let [method-filter-fn (:method-filter-fn interop-config)]
        (when (method-filter-fn method-name)
          (let [arg-filter-fn (:arg-filter-fn interop-config)]
            (-feature config interop-feature "event" (-> @a
                                                         (update :data #(if arg-filter-fn (arg-filter-fn method-name %) %))
                                                         (assoc :method method-name)))))

        (when-let [method-arg-filters (filters/method-allowed? (:filters interop-config) method-name)]
          (-feature config interop-feature "event" (-> @a
                                                       (update :data #(filter-args method-arg-filters %))
                                                       (assoc :method method-name))))))))

(defmacro event! [subscription-id subscriber server method oob? sqn snapshot? data]
  (let [config 'gateway.metrics.features/*config*]
    `(-event! ~config (delay ~{:subscription_id subscription-id
                               :subscriber      subscriber
                               :server          server
                               :method          method
                               :oob             oob?
                               :sqn             sqn
                               :snapshot        snapshot?
                               :data            data}))))