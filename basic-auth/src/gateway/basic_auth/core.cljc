(ns gateway.basic-auth.core
  (:require
    [gateway.auth.core :as auth]
    [gateway.auth.impl :refer [AuthImpl] :as impl]

    [gateway.common.utilities :refer [now ex-msg]]
    [taoensso.timbre :as timbre]

    #?(:clj  [gateway.common.jwtj :as jwt]
       :cljs [gateway.common.jwt :as jwt])
    [gateway.id-generators :as ids]))

(defmulti authenticate (fn [authentication-request secret ttl] (get-in authentication-request [:authentication :method])))

(defmethod authenticate "secret" [authentication-request secret ttl]
  (let [response (auth/authenticate-secret authentication-request)]
    (if (= (:type response) :success)
      (let [t (jwt/sign {:user (:user response)
                         :exp  (+ (now) ttl)}
                        secret)]
        (assoc response :access_token t))
      response)))

(defmethod authenticate "access-token" [authentication-request secret ttl]
  (try
    (let [token (get-in authentication-request [:authentication :token])
          user (:user (jwt/unsign token secret {:now (now)}))]

      {:type         :success
       :login        user
       :user         user
       :access_token token})
    (catch #?(:clj  Exception
              :cljs :default) e
      {:type    :failure
       :message (str "Invalid or expired token: " (ex-msg e))})))

(defmethod authenticate :default [authentication-request _ _]
  (let [method (get-in authentication-request [:authentication :method])]
    (timbre/debug "Unknown auth method -" method)
    {:type    :failure
     :message (str "Unknown authentication method " method)}))


(deftype BasicAuthenticator [secret ttl]
  AuthImpl
  (auth [this authentication-request response-fn]
    (response-fn (authenticate authentication-request secret ttl))))


(defn authenticator
  [config]
  (timbre/info "creating basic authenticator with configuration" config)
  (impl/authenticator config (->BasicAuthenticator (ids/random-id)
                                                   (* 1000 (:ttl config 60000)))))
