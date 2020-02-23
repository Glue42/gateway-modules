(ns gateway.domains.activity.activity
  (:require [gateway.id-generators :as ids]
            [gateway.common.context.state :as contexts]))

(defrecord Activity [id type context-id initiator])

(defn activity
  [id activity-type context-id peer-id]
  (Activity. id
             activity-type
             context-id
             peer-id))


(defn request->Activity
  [state request peer]
  (let [[new-ids ctx-id] (ids/context-id (:ids state))
        context (contexts/->ctx (:identity peer)
                                nil
                                (:initial_context request)
                                :activity
                                (:read_permissions request)
                                (:write_permissions request)
                                ctx-id
                                (contexts/next-version))
        [new-ids act-id] (ids/activity-id new-ids)
        activity (assoc (activity act-id
                                  (:activity_type request)
                                  ctx-id
                                  (:peer_id request))
                   :client-request request)]
    [(assoc state :ids new-ids) activity context]))

