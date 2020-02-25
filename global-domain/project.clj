(defproject com.tick42.gateway/global-domain "3.0.7-SNAPSHOT"
  :plugins [[lein-modules "0.3.11"]
            [lein-cljsbuild "1.1.7"]
            [lein-doo "0.1.10"]]

  :dependencies [[com.tick42.gateway/auth :version]
                 [com.tick42.gateway/common :version]

                 [com.taoensso/timbre "_"]
                 [gnl/ghostwheel "_"]

                 [org.clojure/core.async "_"]]

  :profiles {:dev {:dependencies   [[com.tick42.gateway/basic-auth :version]
                                    [com.tick42.gateway/local-node :version]
                                    [com.tick42.gateway/common-test :version]]
                   :resource-paths ["test/resources"]
                   :jvm-opts       ["-Dghostwheel.enabled=true"]}}

  :test2junit-run-ant false
  :test2junit-output-dir "test-results"

  :modules {:subprocess nil})
