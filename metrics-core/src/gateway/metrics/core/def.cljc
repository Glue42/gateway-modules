(ns gateway.metrics.core.def)


(defprotocol Repository
  (start! [this])
  (stop! [this])
  (add! [this definitions])
  (publish! [this data-points])
  (status! [this publisher-status]))

(defprotocol RepositoryFactory
  (repository [this peer-identity opts])
  (shutdown [this]))


