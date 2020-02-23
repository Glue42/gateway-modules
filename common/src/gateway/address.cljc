(ns gateway.address)

(defn peer->address
  [node-id peer-id]
  {:type :peer :peer-id peer-id :node node-id})
