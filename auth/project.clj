(defproject com.tick42.gateway/auth "3.0.7-SNAPSHOT"
  :plugins [[lein-modules "0.3.11"]]
  :dependencies [[com.tick42.gateway/common :version]
                 [com.taoensso/timbre "_"]
                 [funcool/promesa "_" :exclusions [org.clojure/clojurescript]]
                 [gnl/ghostwheel "_"]

                 [org.clojure/core.async "_"]]
  :modules {:subprocess nil})
