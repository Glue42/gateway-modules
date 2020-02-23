(ns gateway.domains.context.constants
  (:require [gateway.constants :as c]
            [gateway.common.context.constants :as cc]
            [gateway.reason :refer [reason]]))

(defonce context-domain-uri c/context-domain-uri)
(defonce context-destroyed-peer-left (cc/context-destroyed-peer-left context-domain-uri))

(defonce failure (str context-domain-uri ".errors.failure"))

(defonce reason-peer-removed (reason (str context-domain-uri ".peer-removed") "Peer has been removed"))