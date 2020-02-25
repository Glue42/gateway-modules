(defproject com.tick42.gateway/basic-auth "3.0.7-SNAPSHOT"
  :url "https://github.com/Glue42/gateway-core"
  :license {:name "Apache License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}
            
  :plugins [[lein-modules "0.3.11"]]
  :dependencies [[com.tick42.gateway/auth :version]
                 [com.tick42.gateway/common :version]

                 [com.taoensso/timbre "_"]
                 [org.clojure/core.async "_"]]

  :modules {:subprocess nil})
