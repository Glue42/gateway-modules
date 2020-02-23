(ns gateway.state.spec.environment
  (:require [clojure.spec.alpha :as s]))

(s/def ::environment (s/keys :req-un [::local-ip ::os ::process-id ::start-time]))
