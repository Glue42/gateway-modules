(ns gateway.domains.metrics.constants
  (:require [gateway.reason :refer [reason]]))

(defonce metrics-domain-uri "metrics")

(defonce metrics-failure (str metrics-domain-uri ".errors.failure"))
(defonce metrics-unhandled-message (str metrics-domain-uri ".errors.unhandled_message"))
(defonce metrics-bad-identity (str metrics-domain-uri ".errors.bad_identity"))

(defonce reason-peer-removed (reason (str metrics-domain-uri ".peer-removed") "Peer has been removed"))

