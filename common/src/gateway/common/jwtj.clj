(ns gateway.common.jwtj
  (:require [cheshire.core :as cheshire])
  (:import (com.auth0.jwt.algorithms Algorithm)
           (com.auth0.jwt JWT)
           (org.apache.commons.codec.binary Base64 StringUtils)
           (java.nio.charset StandardCharsets)))


(defn- to-string ^String [v]
  (if (keyword? v)
    (name v)
    (str v)))


(defn sign [payload ^String key]
  (let [algo (Algorithm/HMAC256 key)
        header (-> {:alg (.getName algo) :typ "JWT"}
                   (cheshire/generate-string)
                   (.getBytes StandardCharsets/UTF_8)
                   (Base64/encodeBase64URLSafeString))
        p (-> (cond-> payload
                      (:exp payload) (update :exp / 1000))
              (cheshire/generate-string)
              (.getBytes StandardCharsets/UTF_8)
              (Base64/encodeBase64URLSafeString))

        signature (-> algo
                      (.sign (.getBytes header StandardCharsets/UTF_8)
                             (.getBytes p StandardCharsets/UTF_8))
                      (Base64/encodeBase64URLSafeString))]
    (str header "." p "." signature)))

(defn unsign [^String message ^String key claims]
  (let [algo (Algorithm/HMAC256 key)]
    (-> (JWT/require algo)
        (.build)
        (.verify message)
        (.getPayload)
        (.getBytes StandardCharsets/UTF_8)
        (Base64/decodeBase64)
        (StringUtils/newStringUtf8)
        (cheshire/parse-string keyword))))
