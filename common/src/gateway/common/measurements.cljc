(ns gateway.common.measurements)

(defprotocol Measurements
  (get-all [this])
  (record! [this type measurement-name measurement-value]))

