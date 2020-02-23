(ns gateway.domain
  (:require [gateway.state.spec.state :as state]
            [gateway.common.spec.messages :as msg]

            [clojure.spec.alpha :as s]))


;; specs

(s/def ::operation-result (s/nilable (s/tuple (s/nilable ::state/state)
                                              (s/nilable (s/coll-of ::msg/outgoing-message)))))

(defprotocol Domain
  (info [this])
  (init [this state])
  (destroy [this state])

  (handle-message [this state request])

  (state->messages [this state]
    "Generates the messages needed to replicate the state onto a remote node. Can be a noop"))

