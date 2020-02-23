(ns gateway.common.jwt
  (:require [clojure.string :as str]
            [goog.json :as json]
            [goog.crypt.base64 :as b64])
  (:import goog.crypt
           goog.crypt.Sha256
           goog.crypt.Sha384
           goog.crypt.Sha512
           goog.crypt.Hmac))


(def signing-algorithm-map {"HS256" "sha256"
                            "HS384" "sha384"
                            "HS512" "sha512"
                            "RS256" "RSA-SHA256"})

(def signing-type-map {"HS256" "hmac"
                       "HS384" "hmac"
                       "HS512" "hmac"
                       "RS256" "sign"})

(defn- base64-url-escape
  [b64string]
  (-> b64string
      (str/replace "+" "-")
      (str/replace "/" "_")
      (str/replace "=" "")))

(defn- base64-url-encode
  [string]
  (base64-url-escape (b64/encodeString string)))

(defn- create-hmac
  [algo key]
  (let [hasher (case algo
                 "sha256" (Sha256.)
                 "sha384" (Sha384.)
                 "sha512" (Sha512.)
                 (throw (js/Error. (str algo " hashing not supported"))))]
    (Hmac. hasher key)))

(defn- create-signature
  [input key algo type]
  (case type
    "hmac" (-> (create-hmac algo (goog.crypt.stringToByteArray key))
               (.getHmac input)
               (b64/encodeByteArray)
               (base64-url-escape))
    (throw (js/Error. "algorithm not supported"))))

(defn- verify-sig
  [input sig key method type]
  (case type
    "hmac" (= sig (create-signature input key method type))
    (throw (js/Error. "algorithm not supported"))))


(defn- validate-claims

  "Checks the issuer in the `:iss` claim against one of the allowed issuers in the passed `:iss`. Passed `:iss` may be a string or a vector of strings.
  If no `:iss` is passed, this check is not performed.

  Checks one or more audiences in the `:aud` claim against the single valid audience in the passed `:aud`.
  If no `:aud` is passed, this check is not performed.

  Checks the `:exp` claim is not less than the passed `:now`, with a leeway of the passed `:leeway`.
  If no `:exp` claim exists, this check is not performed.

  Checks the `:nbf` claim is less than the passed `:now`, with a leeway of the passed `:leeway`.
  If no `:nbf` claim exists, this check is not performed.

  Checks the passed `:now` is greater than the `:iat` claim plus the passed `:max-age`. If no `:iat` claim exists, this check is not performed.

  A check that fails raises an exception with `:type` of `:validation` and `:cause` indicating which check failed.

  `:now` is an integer POSIX time and defaults to the current time.
  `:leeway` is an integer number of seconds and defaults to zero."
  [claims {:keys [max-age iss aud now leeway]
           :or   {now (.getTime (js/Date.)) leeway 0}}]

  ;; Check the `:iss` claim.
  (when (and iss (let [iss-claim (:iss claims)]
                   (if (coll? iss)
                     (not-any? #{iss-claim} iss)
                     (not= iss-claim iss))))
    (throw (ex-info (str "Issuer does not match " iss)
                    {:type :validation :cause :iss})))

  ;; Check the `:aud` claim.
  (when (and aud (let [aud-claim (:aud claims)]
                   (if (coll? aud-claim)
                     (not-any? #{aud} aud-claim)
                     (not= aud aud-claim))))
    (throw (ex-info (str "Audience does not match " aud)
                    {:type :validation :cause :aud})))

  ;; Check the `:exp` claim.
  (when (and (:exp claims) (<= (:exp claims) (- now leeway)))
    (throw (ex-info (str "Token is expired " (:exp claims))
                    {:type :validation :cause :exp})))

  ;; Check the `:nbf` claim.
  (when (and (:nbf claims) (> (:nbf claims) (+ now leeway)))
    (throw (ex-info (str "Token is not yet valid " (:nbf claims))
                    {:type :validation :cause :nbf})))

  ;; Check the `:max-age` option.
  (when (and (:iat claims) (number? max-age) (> (- now (:iat claims)) max-age))
    (throw (ex-info (str "Token is older than max-age " max-age)
                    {:type :validation :cause :max-age})))
  claims)

(defn ^:export unsign
  "decode from JWT"
  ([message pkey]
   (unsign message pkey {}))

  ([message pkey {:keys [skip-validation] :or {skip-validation false} :as opts}]
   (let [segments (str/split message ".")
         h (get segments 0)
         p (get segments 1)
         s (get segments 2)]
     (when (some nil? [h p s])
       (throw (js/Error. "invalid token")))

     (let [alg (-> h
                   (b64/decodeString)
                   (json/parse)
                   (.. -alg))

           claims (->> (json/parse (b64/decodeString p))
                       (js->clj)
                       (reduce-kv #(assoc %1 (keyword %2) %3) {}))

           signing-method (get signing-algorithm-map alg)
           signing-type (get signing-type-map alg)]

       (cond
         skip-validation claims

         (not (and signing-method signing-type))
         (throw (js/Error. "algorithm not supported"))

         (not (verify-sig (str h "." p)
                          s
                          pkey
                          signing-method
                          signing-type))
         (throw (js/Error. "signature verification failed"))

         :default (validate-claims claims opts))))))

(defn ^:export sign
  "encode to JWT"
  [payload key & [algo extra-headers]]
  (let [algo (or algo "HS256")
        extra-headers (or extra-headers {})
        signing-method (get signing-algorithm-map algo)
        signing-type (get signing-type-map algo)]

    (when-not (map? payload)
      (throw (js/Error. "payload should be in JSON format")))

    (when-not (map? extra-headers)
      (throw (js/Error. "extra-headers should be a map")))

    (when-not (and signing-method signing-type)
      (throw (js/Error. "algorithm not supported")))

    (let [header (-> (apply conj extra-headers {:alg algo :typ "JWT"})
                     (clj->js)
                     (json/serialize)
                     (base64-url-encode))
          payload (-> payload
                      (clj->js)
                      (json/serialize)
                      (base64-url-encode))
          signature (create-signature (str header "." payload) key signing-method signing-type)]
      (str header "." payload "." signature))))