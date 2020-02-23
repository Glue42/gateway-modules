(ns gateway.id-generators)

(defn random-id [] (apply str (remove #{\-} (str #?(:clj  (java.util.UUID/randomUUID)
                                                    :cljs (random-uuid))))))

(defn node-id
  ([] (random-id))
  ([ids] (:node-id ids)))


;(defn local-generator []
;  (->LocalGenerator (node-id) 0 0 0 0 0))

(defn request-id [ids]
  (let [next-id (:current-id ids 1)
        req-id (str "r-" (:node-id ids) "-" next-id)]
    [(assoc ids :current-id (inc next-id)) req-id]))

(defn instance-id [ids]
  (let [next-id (:current-id ids 1)
        req-id (str "i-" (:node-id ids) "-" next-id)]
    [(assoc ids :current-id (inc next-id)) req-id]))

(defn context-id [ids]
  (let [next-id (:current-id ids 1)
        req-id (str "c-" (:node-id ids) "-" next-id)]
    [(assoc ids :current-id (inc next-id)) req-id]))

(defn activity-id [ids]
  (let [next-id (:current-id ids 1)
        req-id (str "a-" (:node-id ids) "-" next-id)]
    [(assoc ids :current-id (inc next-id)) req-id]))

(defn peer-id [ids]
  (let [next-id (:current-id ids 1)
        req-id (str "p-" (:node-id ids) "-" next-id)]
    [(assoc ids :current-id (inc next-id)) req-id]))