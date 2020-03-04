(defproject com.tick42.gateway/basic-auth "3.0.8-SNAPSHOT"
  :plugins [[lein-modules "0.3.11"]]
  :dependencies [[com.tick42.gateway/auth :version]
                 [com.tick42.gateway/common :version]

                 [com.taoensso/timbre "_"]
                 [org.clojure/core.async "_"]
                 [funcool/promesa "_"]]

  :modules {:subprocess nil})
