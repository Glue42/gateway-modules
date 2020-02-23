(ns gateway.constants
  (:require [gateway.reason :refer [reason]]))

;; domain base URI
(defonce global-domain-uri "global")
(defonce context-domain-uri "context")

;; errors
(defonce failure (str global-domain-uri ".errors.failure"))
(defonce error-unhandled-message "gateway.errors.unhandled_message")
