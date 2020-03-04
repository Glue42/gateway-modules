(ns gateway.auth.t-default
  (:require [gateway.auth.core :as c]
            [gateway.auth.impl :as d]
            [promesa.core :as p]

            [clojure.test :refer [deftest is]]))

(deftest authenticator-timeout
  (let [impl (reify d/AuthImpl
               (auth [this _]
                 (p/delay 2000)))
        auth (d/authenticator {:timeout 500} impl)
        rp (c/authenticate auth {:authentication {:method "secret"
                                                  :login  "test-user"
                                                  :secret "test-pass"}})]

    (is (= {:message "Authentication timed out"
            :type    :failure} @(p/timeout rp 1000)))
    (c/stop auth)))

(deftest authenticator-success
  (let [l "test-user"
        impl (reify d/AuthImpl
               (auth [this authentication-request]
                 (let [l (get-in authentication-request [:authentication :login])]
                   (p/resolved {:type  :success
                                :user  l
                                :login l}))))
        auth (d/authenticator {:timeout 10000} impl)
        rp (c/authenticate auth {:authentication {:method "secret"
                                                  :login  l
                                                  :secret l}})]

    (is (= {:type  :success
            :login l
            :user  l} @(p/timeout rp 1000)))
    (c/stop auth)))