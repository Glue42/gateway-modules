(ns gateway.auth.core
  (:require [promesa.core :as p]))


(defprotocol Authenticator
  (authenticate
    ;; returns a promise that will either be resolved or rejected depending on the authentication result
    [this authentication-request])
  (stop
    [this]))


(defn authenticate-secret
  [authentication-request]
  (let [{:keys [login secret]} (:authentication authentication-request)]
    (if (and login secret) (p/resolved {:type  :success
                                        :user  login
                                        :login login})
                           (p/rejected (ex-info "Missing login/secret" {:type    :failure
                                                                        :message "Missing login/secret"})))))

(defn gateway-token [authentication]
  (when (= (:method authentication) "gateway-token")
    (:token authentication)))


