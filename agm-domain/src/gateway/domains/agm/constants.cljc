(ns gateway.domains.agm.constants
  (:require [gateway.constants :as c]
            [gateway.reason :refer [reason]]))

(defonce agm-domain-uri "agm")
(defonce failure (str agm-domain-uri ".errors.failure"))

(defonce agm-unhandled-message (str agm-domain-uri ".errors.unhandled_message"))
(defonce agm-registration-failure (str agm-domain-uri ".errors.registration.failure"))
(defonce agm-unregistration-failure (str agm-domain-uri ".errors.unregistration.failure"))
(defonce agm-invocation-failure (str agm-domain-uri ".errors.invocation.failure"))
(defonce agm-subscription-failure (str agm-domain-uri ".errors.subscription.failure"))
(defonce agm-invalid-subscription (str agm-domain-uri ".errors.subscription.invalid_subscription"))

(defonce reason-peer-removed (reason (str agm-domain-uri ".peer-removed") "Peer has been removed"))
(defonce reason-method-removed (reason (str agm-domain-uri ".method-removed") "Method has been removed"))

(defonce reason-bad-drop (reason agm-invalid-subscription
                                 "Trying to drop a subscription that wasnt established. Did you mean to return an error instead?"))
