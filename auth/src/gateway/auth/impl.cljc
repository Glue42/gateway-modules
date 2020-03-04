(ns gateway.auth.impl
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go go-loop]]))
  (:require [gateway.auth.core :refer [Authenticator] :as core]
            [gateway.auth.spec :as s]
            [gateway.common.utilities :as util]

            [taoensso.timbre :as timbre]
            [promesa.core :as p]

            [clojure.walk :refer [keywordize-keys]]
            #?(:clj  [clojure.core.async :as a]
               :cljs [cljs.core.async :as a])

            [ghostwheel.core :as g :refer [>defn >defn- >fdef => | <- ?]]))

(defonce default-authentication-timeout 5000)

(defn sanitize [authentication]
  (if (:secret authentication)
    (assoc authentication :secret "XXX")
    (assoc authentication :token "XXX")))

(defn sanitize-response [response]
  (if (:access-token response)
    (assoc response :access-token "<TOKEN>")
    response))

(defprotocol AuthImpl
  (auth [this authentication-request]))

(>defn -authenticate!
  [authenticator-ch timeout authentication-request]
  [any? int? ::s/request => any?]
  (if authenticator-ch
    (-> (p/create (fn [resolve reject] (a/put! authenticator-ch [resolve reject authentication-request])))
        (p/timeout timeout {:type    :failure
                            :message "Authentication timed out"}))


    (p/rejected (ex-info "No authenticator configured" {:type    :failure
                                                        :message "No authenticator configured"}))))

(deftype DefaultAuthenticator [authenticator-ch timeout]
  Authenticator
  (stop [this]
    (when authenticator-ch
      (util/close-and-flush! authenticator-ch)))

  (authenticate [this authentication-request]
    (-authenticate! authenticator-ch timeout authentication-request)))

(defn authenticator
  [config impl]
  (let [ch (a/chan (a/dropping-buffer 20000))]
    (timbre/info "starting authenticator" impl)
    (a/go-loop []
      (let [msg (a/<! ch)]
        (when msg
          (let [[resolve reject authentication-request] msg
                authentication-request (update authentication-request :authentication keywordize-keys)]
            (timbre/debug "processing authentication" (sanitize (:authentication authentication-request)))
            (-> (auth impl authentication-request)
                (p/then (fn [v] (resolve v)))
                (p/catch (fn [v] (reject v))))
            (recur)))))
    (DefaultAuthenticator. ch (:timeout config default-authentication-timeout))))
