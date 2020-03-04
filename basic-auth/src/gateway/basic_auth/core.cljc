(ns gateway.basic-auth.core
  (:require
    [gateway.auth.core :as auth]
    [gateway.auth.impl :refer [AuthImpl] :as impl]

    [gateway.common.utilities :refer [now ex-msg]]
    [taoensso.timbre :as timbre]

    #?(:clj  [gateway.common.jwtj :as jwt]
       :cljs [gateway.common.jwt :as jwt])
    [gateway.id-generators :as ids]

    [promesa.core :as p]))

(defmulti authenticate (fn [authentication-request secret ttl] (get-in authentication-request [:authentication :method])))

(defmethod authenticate "secret"
  [authentication-request secret ttl]

  (-> (auth/authenticate-secret authentication-request)
      (p/then (fn [response] (let [t (jwt/sign {:user (:user response)
                                                :exp  (+ (now) ttl)}
                                               secret)]
                               (assoc response :access_token t))))))

(defmethod authenticate "access-token" [authentication-request secret ttl]
  (try
    (let [token (get-in authentication-request [:authentication :token])
          user (:user (jwt/unsign token secret {:now (now)}))]

      (p/resolved {:type         :success
                   :login        user
                   :user         user
                   :access_token token}))
    (catch #?(:clj  Exception
              :cljs :default) e
      (timbre/debug e "Error processing access token authentication request")
      (p/rejected (ex-info (ex-msg e) {:type    :failure
                                       :message (str "Invalid or expired token: " (ex-msg e))})))))

(defmethod authenticate :default [authentication-request _ _]
  (let [method (get-in authentication-request [:authentication :method])
        msg (str "Unknown authentication method " method)]
    (timbre/debug msg)
    (p/rejected (ex-info msg {:type    :failure
                             :message msg}))))


(deftype BasicAuthenticator [secret ttl]
  AuthImpl
  (auth [this authentication-request]
    (authenticate authentication-request secret ttl)))


(defn authenticator
  [config]
  (timbre/info "creating basic authenticator with configuration" config)
  (impl/authenticator config (->BasicAuthenticator (ids/random-id)
                                                   (* 1000 (:ttl config 60000)))))
