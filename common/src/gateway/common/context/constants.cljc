(ns gateway.common.context.constants
  (:require [gateway.reason :refer [reason]]))


(defn context-not-authorized
  [domain-uri]
  (str domain-uri ".errors.not_authorized"))

(defn context-bad-lifetime
  [domain-uri]
  (str domain-uri ".errors.bad_lifetime"))

(defn context-invalid-context
  [domain-uri]
  (str domain-uri ".errors.invalid_context"))

(defn context-unhandled-message
  [domain-uri]
  (str domain-uri ".errors.unhandled_message"))

(defn context-destroyed-explicitly
  [domain-uri]
  (reason (str domain-uri ".destroyed")
          "Context destroyed explicitly"))

(defn context-destroyed-peer-left
  [domain-uri]
  (reason (str domain-uri ".peer-left")
          "Context destroyed because its owner/last peer left"))

(defn failure
  [domain-uri]
  (str domain-uri ".errors.failure"))
