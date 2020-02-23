(ns gateway.domains.context.helpers)

(defn context-id
  [msg-type msgs]
  (reduce
    (fn [_ m] (when (= msg-type (:type m)) (reduced (:context_id m))))
    nil
    (map :body msgs)))

(defn ->version [msgs]
  (get-in (->> msgs
               (filter #(let [body-type (get-in % [:body :type])]
                          (or (= :create-context body-type)
                              (= :update-context body-type))))
               (first))
          [:body :version]))

