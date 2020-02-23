(ns gateway.auth.t-default
  (:require [gateway.auth.core :as c]
            [gateway.auth.impl :as d]
            [promesa.core :as p]

            [clojure.test :refer :all]))

(deftest authenticator-timeout
  (let [impl (reify d/AuthImpl
               (auth [this authentication-request result-fn]))
        auth (d/authenticator {:timeout 500} impl)
        rp (p/create (fn [resolve _]
                       (c/authenticate auth {:authentication {:method "secret"
                                                              :login  "test-user"
                                                              :secret "test-pass"}} resolve)))]

    (is (= {:message "Authentication timed out"
            :type    :failure} @(p/timeout rp 1000)))
    (c/stop auth)))

(deftest authenticator-success
  (let [l "test-user"
        impl (reify d/AuthImpl
               (auth [this authentication-request result-fn]
                 (let [l (get-in authentication-request [:authentication :login])]
                   (result-fn {:type  :success
                               :user  l
                               :login l}))))
        auth (d/authenticator {:timeout 10000} impl)
        rp (p/create (fn [resolve _]
                       (c/authenticate auth {:authentication {:method "secret"
                                                              :login  l
                                                              :secret l}} resolve)))]

    (is (= {:type  :success
            :login l
            :user  l} @(p/timeout rp 1000)))
    (c/stop auth)))