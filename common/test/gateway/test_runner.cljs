(ns gateway.test-runner
  (:require [doo.runner :refer-macros [doo-tests]]
            [gateway.common.t-core]
            ))

(doo-tests
  'gateway.common.t-core)