(ns gateway.state.spec.restrictions
  (:require [clojure.spec.alpha :as s]))

(s/def ::restrictions (s/or
                        ::empty-restrictions nil?
                        ::string-restrictions string?
                        ::parsed-restrictions vector?))
