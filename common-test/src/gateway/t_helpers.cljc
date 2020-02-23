(ns gateway.t-helpers
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go go-loop]]))
  #?(:clj
     (:use clojure.test))
  (:require
    [clojure.spec.alpha :as s]
    #?(:clj
    [clojure.core.async :as a]
       :cljs [cljs.core.async :as a])
    #?(:cljs [cljs.test :refer-macros [deftest is testing]])
    [gateway.state.core :as state]
    [gateway.common.peer-identity :as peer-identity]))

;; receives N messages and returns a vector of their bodies
(defn receive-n [n prom]
  (let [ch (a/chan)]
    (a/go-loop [i n results []]
      (if (> i 0)
        (recur (dec i) (conj results (first (a/alts! [ch (a/timeout 2000)]))))
        (deliver prom results)))
    ch))


(defn ch->src [ch] {:type :local :channel ch})

(defn local-msg [ch body] {:origin :local :source (ch->src ch) :body body})


;; ids

(defonce node-id (apply str (remove #{\-} (str #?(:clj  (java.util.UUID/randomUUID)
                                                  :cljs (random-uuid))))))

(defn new-state
  ([] (new-state node-id))
  ([node-id] (state/empty-state node-id)))

(def ^:private peer-id (atom 0))
(def ^:private request-id (atom 0))
(def ^:private activity-id (atom 0))
(def ^:private context-id (atom 0))
(def ^:private instance-id (atom 0))

(defn request-id! []
  (let [id (swap! request-id inc)]
    (str "rr-" node-id "-" id)))

(defn activity-id! []
  (let [id (swap! activity-id inc)]
    (str "aa-" node-id "-" id)))

(defn peer-id! []
  (let [id (swap! peer-id inc)]
    (str "pp-" node-id "-" id)))

(defn context-id! []
  (let [id (swap! context-id inc)]
    (str "cc-" node-id "-" id)))

(defn instance-id! []
  (let [id (swap! instance-id inc)]
    (str "ii-" node-id "-" id)))

(defn gen-identity
  ([]
   (peer-identity/keywordize-id {"application" "app" "instance" (instance-id!)}))
  ([user]
   (peer-identity/keywordize-id {"application" "app" "instance" (instance-id!) :user user})))

(defn gen-user-auth
  ([] (gen-user-auth "user"))
  ([user] {"method" "secret" "login" user "secret" "secret"}))

(defn local-peer
  []
  {:id (peer-id!) :source (ch->src "peer") :identity (gen-identity)})

(defn remote-peer
  [node-id]
  (let [peer-id (peer-id!)]
    {:id peer-id :source {:type    :peer
                          :peer-id peer-id
                          :node    node-id} :identity (gen-identity)}))


(defn msgs->ctx-version [msgs]
  (get-in (->> msgs
               (filter #(let [body-type (get-in % [:body :type])]
                          (or (= :create-context body-type)
                              (= :update-context body-type))))
               (first))
          [:body :version]))
