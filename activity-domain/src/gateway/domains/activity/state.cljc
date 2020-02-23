(ns gateway.domains.activity.state
  (:require [gateway.common.utilities :as util]
            [gateway.domains.activity.constants :as constants]
            [gateway.reason :refer [throw-reason]]
            [clojure.set :as set]))

;; factories

(defn add-factories
  [state factory-types peer-id]
  (reduce
    (fn [state ft]
      (let [loc [:factories ft]]
        (assoc-in state loc
                  (conj (get-in state loc []) peer-id))))
    state
    factory-types))

(defn remove-factories
  [state factory-types peer-id]
  (if (and (seq factory-types) peer-id)
    (reduce
      (fn [state ft]
        (let [loc [:factories ft]]
          (let [updated (util/removev peer-id (get-in state loc nil))]
            (if (seq updated)
              (assoc-in state loc updated)
              (util/dissoc-in state loc)))))
      state
      factory-types)
    state))

(defn factories
  ([state]
   (get state :factories))
  (
   [state peer-type]
   (get-in state [:factories peer-type])))

;; activities

(defn activity [state activity-id]
  (when activity-id
    (get-in state [:activities activity-id])))

(defn set-activity [state activity-id activity]
  (if activity-id
    (assoc-in state [:activities activity-id] activity)
    state))

(defn update-activity [state activity-id fn & args]
  (apply update-in state [:activities activity-id] fn args))

(defn remove-activity [state activity-id]
  (util/dissoc-in state [:activities activity-id]))

(defn activities [state]
  (vals (get state :activities)))


;; activity types

(defn activity-types
  [state user]
  (get-in state [:activity-types user]))

(defn add-activity-types
  [state user types]
  (reduce
    (fn [state type]
      (assoc-in state [:activity-types user (:name type)] type))
    state
    types))

(defn remove-activity-types
  [state user type-names]
  (reduce
    (fn [state type-name] (util/dissoc-in state [:activity-types user type-name]))
    state
    type-names))


(defn activity-subscribers
  "Returns a list of ids for the peers that have subscribed for a given activity type"

  [state activity-type]
  (set/union (get-in state [:activity-subscribers activity-type])
             (get-in state [:activity-subscribers :all])))

(defn add-activity-subscriber
  [state peer-id activity-type]
  (update-in state [:activity-subscribers activity-type] (fnil conj #{}) peer-id))

(defn remove-activity-subscriber
  [state peer-id activity-type]
  (update-in state [:activity-subscribers activity-type] disj peer-id))


(defn activity*
  "Retrieves an activity with a given id, throwing an exception if missing"

  [state activity-id]
  (when activity-id
    (if-let [activity (activity state activity-id)]
      activity
      (throw-reason constants/activity-invalid (str "Unable to find activity with id " activity-id)))))
