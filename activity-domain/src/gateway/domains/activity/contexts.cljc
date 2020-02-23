(ns gateway.domains.activity.contexts
  (:require [gateway.common.context.ops :as ops]
            [gateway.domains.activity.constants :as constants]))

(def update-ctx (partial ops/update-ctx constants/activity-domain-uri))
