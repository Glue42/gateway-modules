(defproject com.tick42.gateway/core-parent "3.0.7-SNAPSHOT"
  :description "Tick42 Gateway Core Parent"

  :target-path "target/%s"

  :profiles {:uberjar  {:aot          :all
                        :dependencies [[org.clojure/clojure "_"]]}
             :provided {:dependencies [[org.clojure/clojure "_"]]}

             :dev {:plugins      [[lein-kibit "0.1.6"]
                                  [jonase/eastwood "0.3.3"]
                                  [lein-ancient "0.6.15"]
                                  [test2junit "1.4.2"]]
                   :dependencies [[org.clojure/tools.reader "_"]]}

             :limited-resources {:jvm-opts ["-Xms1g" "-Xmx1g" "-XX:+HeapDumpOnOutOfMemoryError"
                                            "-Xss512k" "-XX:MetaspaceSize=256m" "-XX:MaxMetaspaceSize=256m"
                                            "-XX:CompressedClassSpaceSize=64m" "-XX:ReservedCodeCacheSize=32m"]}}


  :plugins [[lein-modules "0.3.11"]
            [com.andrewmcveigh/lein-auto-release "0.1.10"]]

  :modules {
            :versions {org.clojure/clojure                                  "1.10.1"
                       com.taoensso/timbre                                  "4.10.0"
                       org.clojure/core.async                               "1.0.567"
                       org.clojure/tools.reader                             "1.3.2"
                       instaparse                                           "1.4.10"
                       cheshire                                             "5.8.1"
                       org.clojure/clojurescript                            "1.10.339"
                       com.github.ben-manes.caffeine/caffeine               "2.8.0"
                       funcool/promesa                                      "5.1.0"
                       com.cognitect/transit-clj                            "0.8.313"
                       gnl/ghostwheel                                       "0.3.9"}}

  :release-tasks [["auto-release" "checkout" "master"]
                  ["auto-release" "merge-no-ff" "develop"]
                  ["vcs" "assert-committed"]
                  ["change" "version" "leiningen.release/bump-version" "release"]
                  ["modules" "change" "version" "leiningen.release/bump-version" "release"]
                  ["vcs" "commit"]
                  ["vcs" "tag" "--no-sign"]
                  ["vcs" "push"]
                  ["auto-release" "checkout" "develop"]
                  ["auto-release" "merge" "master"]
                  ;; ["modules" "deploy"]
                  ["change" "version" "leiningen.release/bump-version"]
                  ["modules" "change" "version" "leiningen.release/bump-version"]
                  ["vcs" "commit"]
                  ["vcs" "push"]]

  :packaging "pom"
  :pom-addition [:modules
                 [:module "ghostwheel-stub"]
                 [:module "auth"]
                 [:module "basic-auth"]
                 [:module "common"]
                 [:module "common-test"]
                 [:module "context-domain"]
                 [:module "activity-domain"]
                 [:module "agm-domain"]
                 [:module "global-domain"]
                 [:module "metrics-domain"]
                 [:module "bus-domain"]
                 [:module "local-node"]])
