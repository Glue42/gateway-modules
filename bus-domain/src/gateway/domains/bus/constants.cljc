(ns gateway.domains.bus.constants)

(defonce bus-domain-uri "bus")

(defonce failure (str bus-domain-uri ".errors.failure"))
(defonce bus-unhandled-message (str bus-domain-uri ".errors.unhandled_message"))
