(ns gateway.domains.agm.utilities
  (:require
    [gateway.common.utilities :refer [conj-in disj-in dissoc-in merge-in]]
    [gateway.domains.agm.constants :as constants]
    [gateway.reason :refer [throw-reason]]
    [gateway.state.peers :as peers]))




(defn validate*
  [caller callee request]
  (let [{:keys [server_id method_id arguments arguments_kv]} request]
    (when (and (not= (:id caller) (:id callee))
               (not (peers/peer-visible? :agm-domain caller callee)))
      (throw-reason constants/agm-invocation-failure "Unable to invoke methods across different users"))

    (when-not (get-in caller [:agm-domain :methods server_id method_id])
      (throw-reason constants/agm-invocation-failure
                    (str "Unable to find method with id " method_id " on server id " server_id " registered with this peer")))
    (when (and (seq arguments) (seq arguments_kv))
      (throw-reason constants/agm-invocation-failure
                    "Cant use positional and by-name arguments at the same time"))))