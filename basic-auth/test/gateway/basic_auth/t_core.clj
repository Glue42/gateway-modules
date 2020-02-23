(ns gateway.basic-auth.t-core
  (:use clojure.test)
  (:require
    [gateway.basic-auth.core :as core]
    [gateway.auth.core :as auth]
    [clojure.core.async :as a]))

(deftest token-generation
  (let [ba (core/authenticator nil)
        out (a/chan 10)]
    (auth/authenticate ba {:authentication {:method "secret"
                                            :login  "test-user"
                                            :secret "test-pass"}} (partial a/put! out))
    (let [[response _] (a/alts!! [out (a/timeout 2000)])
          token (:access_token response)]
      (is (= :success (:type response)))
      (is (= "test-user" (:user response)))
      (is (some? token))

      (auth/authenticate ba {:authentication {:method "access-token"
                                              :token token}} (partial a/put! out))
      (let [[token-resp _] (a/alts!! [out (a/timeout 2000)])]
        (is (= :success(:type token-resp)))
        (is (= "test-user" (:user token-resp)))))

    (a/close! out)
    (auth/stop ba)))

(deftest token-expiration
  (let [ba (core/authenticator {:ttl 1})
        out (a/chan 10)]
    (auth/authenticate ba {:authentication {:method "secret"
                                            :login  "test-user"
                                            :secret "test-pass"}} (partial a/put! out))
    (let [[response _] (a/alts!! [out (a/timeout 2000)])
          token (:access_token response)]
      (Thread/sleep 2000)
      (auth/authenticate ba {:authentication {:method "access-token"
                                              :token token}} (partial a/put! out))
      (let [[token-resp _] (a/alts!! [out (a/timeout 2000)])]
        (is (= :failure(:type token-resp)))
        (is (nil? (:user token-resp)))))

    (a/close! out)
    (auth/stop ba)))
