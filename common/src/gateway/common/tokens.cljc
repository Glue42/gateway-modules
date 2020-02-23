(ns gateway.common.tokens
  (:require [gateway.common.utilities :refer [now]]
            [gateway.common.peer-identity :as peer-identity]
            [clojure.walk :as walk]
    #?(:clj [gateway.common.jwtj :as jwt]
       :cljs [gateway.common.jwt :as jwt])))

(def ^:dynamic *ttl* (* 1000 60 10))                ; 10 minutes

(defn for-request
  (
   [state impersonate-identity gw-request exp]
   (jwt/sign (cond-> {:type             :gw-request
                      :impersonate-peer impersonate-identity
                      :gw-request       gw-request}
                     exp (assoc :exp exp))
             (:signature-key state)))
  (
   [state impersonate-identity gw-request]
   (for-request state impersonate-identity gw-request (+ (now) *ttl*))))

(defn for-authentication
  (
   [state impersonate-identity exp]
   (jwt/sign (cond-> {:type :authentication
                      :user (:user impersonate-identity)}
                     exp (assoc :exp exp))
             (:signature-key state)))
  (
   [state impersonate-identity]
   (for-authentication state impersonate-identity (+ (now) *ttl*))))


(defn ->token
  (
   [state token-str exp]
   (let [t (-> (jwt/unsign token-str (:signature-key state) (when exp {:now exp}))
               (update :type keyword)
               (update :impersonate-peer peer-identity/keywordize-id))]
     (if (= (:type t) :gw-request)
       (update t :gw-request #(-> %
                                  (walk/keywordize-keys)
                                  (update :type keyword)))
       t)))
  (
   [state token-str]
   (->token state token-str (now))))