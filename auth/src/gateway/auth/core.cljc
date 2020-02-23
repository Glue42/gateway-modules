(ns gateway.auth.core)


(defprotocol Authenticator
  (authenticate [this authentication-request callback-fn])
  (stop [this]))


(defn authenticate-secret
  [authentication-request]
  (let [{:keys [login secret]} (:authentication authentication-request)]
    (if (and login secret) {:type  :success
                            :user  login
                            :login login}
                           {:type    :failure
                            :message "Missing login/secret"})))

(defn gateway-token [authentication]
  (when (= (:method authentication) "gateway-token")
    (:token authentication)))


