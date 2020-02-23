(ns gateway.common.t-core
  #?(:clj
     (:use clojure.test))
  (:require
    #?(:cljs [cljs.test :refer-macros [deftest is testing]])

    [taoensso.timbre :as timbre]
    [gateway.common.channel-buffers :as buf]

    #?(:clj
             [clojure.core.async :as a]
       :cljs [cljs.core.async :as a])
    [gateway.state.core :as state]))

(deftest drop-message-on-full-buffer
  (testing "On put in a channel over dropping buffer catch on-drop event and get dropped counter incremented"
    (let [buf-size (rand-int 100)
          _ (timbre/debug "buf-size:" buf-size)
          dropped (atom {})
          ch (a/chan (buf/dropping-buffer-with-signal
                       buf-size
                       (fn [itm total-dropped]
                         (timbre/info "dropped message" itm)
                         (swap! dropped assoc :dropped-msg itm :total-dropped total-dropped))))]

      (dotimes [n buf-size]
        (a/put! ch (str "msg no." n)))

      (timbre/debug "buffer count:" (.-n (.-buf ch)))

      (is (empty? @dropped))

      (a/put! ch (str "msg no." (inc buf-size)))

      (is (= 1 (:total-dropped @dropped)))
      (is (= 1 (buf/dropped-count (.-buf ch))))
      (is (= (str "msg no." (inc buf-size)) (:dropped-msg @dropped))))))

