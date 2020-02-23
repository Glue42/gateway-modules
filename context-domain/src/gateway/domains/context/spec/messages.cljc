(ns gateway.domains.context.spec.messages
  (:require [gateway.domains.context.spec.requests :as requests]

            [gateway.common.spec.messages :refer [message-body] :as messages]
            [gateway.state.spec.common :as common]
            [gateway.state.spec.context :as context]

            [clojure.spec.alpha :as s]))


;; context messages

