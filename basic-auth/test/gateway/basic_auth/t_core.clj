(ns gateway.basic-auth.t-core
  (:use clojure.test)
  (:require [gateway.basic-auth.core :as core]
            [gateway.auth.core :as auth]
            [clojure.core.async :as a]
            [promesa.core :as p]))

(deftest token-generation
  (let [ba (core/authenticator nil)]
    (let [response @(-> (auth/authenticate ba {:authentication {:method "secret"
                                                                :login  "test-user"
                                                                :secret "test-pass"}})
                        (p/timeout 2000))
          token (:access_token response)]
      (is (= :success (:type response)))
      (is (= "test-user" (:user response)))
      (is (some? token))

      (let [token-resp @(-> (auth/authenticate ba {:authentication {:method "access-token"
                                                                    :token  token}})
                            (p/timeout 2000))]
        (is (= :success (:type token-resp)))
        (is (= "test-user" (:user token-resp)))))

    (auth/stop ba)))

(deftest token-expiration
  (let [ba (core/authenticator {:ttl 1})]

    (let [response @(-> (auth/authenticate ba {:authentication {:method "secret"
                                                                :login  "test-user"
                                                                :secret "test-pass"}})
                        (p/timeout 2000))
          token (:access_token response)]
      (Thread/sleep 2000)

      (let [token-resp @(-> (auth/authenticate ba {:authentication {:method "access-token"
                                                                    :token  token}})
                            (p/timeout 2000)
                            (p/catch (fn [v] (ex-data v))))]
        (is (= :failure (:type token-resp)))
        (is (nil? (:user token-resp)))))
    (auth/stop ba)))
