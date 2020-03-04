(defproject com.tick42.gateway/auth "3.0.8-SNAPSHOT"
  :plugins [[lein-modules "0.3.11"]]
  :dependencies [[com.tick42.gateway/common :version]
                 [com.taoensso/timbre "_"]
                 [funcool/promesa "_"]
                 [gnl/ghostwheel "_"]

                 [org.clojure/core.async "_"]]
  :modules {:subprocess nil})
