(defproject com.tick42.gateway/common "3.0.7-SNAPSHOT"
  :url "https://github.com/Glue42/gateway-core"
  :license {:name "Apache License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}
            
  :plugins [[lein-modules "0.3.11"]
            [lein-cljsbuild "1.1.7"]
            [lein-doo "0.1.10"]]

  :dependencies [[instaparse "_"]
                 [com.auth0/java-jwt "3.8.1"]
                 [commons-codec/commons-codec "1.13"]
                 [cheshire "_"]
                 [com.taoensso/timbre "_"]
                 [gnl/ghostwheel "_"]
                 [org.clojure/core.async "_"]
                 [com.github.ben-manes.caffeine/caffeine "_"]]

  :profiles {:cljstest [:test {:dependencies [[org.clojure/clojurescript "_"]]}]}

  :cljsbuild {:builds [{:id           "dev"
                        :source-paths ["src"]
                        :compiler     {:output-to     "out/js/main.js"
                                       :output-dir    "out/js"
                                       :optimizations :whitespace
                                       :source-map    "out/js/main.js.map"
                                       :pretty-print  true}}
                       {:id           "test"
                        :source-paths ["src" "test"]
                        :compiler     {:output-to     "js/testable.js"
                                       :main          'gateway.test-runner
                                       :target        :nodejs
                                       :optimizations :none}}]}

  :modules {:subprocess nil})
