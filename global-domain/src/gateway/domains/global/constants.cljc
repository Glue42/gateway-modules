(ns gateway.domains.global.constants
  (:require [gateway.constants :as c]
            [gateway.reason :refer [reason]]))

(defonce global-domain-uri c/global-domain-uri)

(defonce failure c/failure)
(defonce global-unhandled-message (str global-domain-uri ".errors.unhandled_message"))
(defonce global-already-seen (str global-domain-uri ".errors.already_seen"))
(defonce global-invalid-domain (str global-domain-uri ".errors.invalid_domain"))
(defonce global-invalid-context (str global-domain-uri ".errors.invalid_context"))
(defonce global-bad-identity (str global-domain-uri ".errors.bad_identity"))
(defonce global-authentication-failure (str global-domain-uri ".errors.authentication.failure"))
(defonce global-missing-parent (str global-domain-uri ".errors.parent_missing"))
(defonce global-not-authorized (str global-domain-uri ".errors.not_authorized"))
(defonce global-bad-restriction (str global-domain-uri ".errors.bad_restriction"))
(defonce global-bad-lifetime (str global-domain-uri ".errors.bad_lifetime"))
(defonce global-registration-failure (str global-domain-uri ".errors.registration.failure"))
(defonce global-invalid-peer (str global-domain-uri ".errors.invalid_peer"))
(defonce global-bad-source (str global-domain-uri ".errors.bad_source"))
(defonce global-limits-exceeded (str global-domain-uri ".errors.limits_exceeded"))

;; reasons

(defonce context-destroyed-peer-left (reason (str global-domain-uri ".peer-left")
                                             "Context destroyed because its owner/last peer left"))

(defonce context-destroyed-explicitly (reason (str global-domain-uri ".destroyed")
                                              "Context destroyed explicitly"))
(defonce reason-peer-removed (reason (str global-domain-uri ".peer-removed") "Peer has been removed"))

