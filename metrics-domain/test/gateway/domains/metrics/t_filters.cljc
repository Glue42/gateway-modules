(ns gateway.domains.metrics.t-filters
  (:require [gateway.domains.metrics.filters :as f]
            [gateway.common.filters :as cf]
            [clojure.test :refer :all]))

(deftest publisher-matching
  (testing "all matching keys succeed"
    (is (f/publisher-matches? {:system  #"Connect.*"
                               :service #"Glue Enterprise"}
                              {:system  "Connect.Browser"
                               :service "Glue Enterprise"})))
  (testing "matching can work with plain strings as well"
    (is (f/publisher-matches? {:system "Connect.Browser"}
                              {:system "Connect.Browser"})))
  (testing "mismatch on a value fails"
    (is (false? (f/publisher-matches? {:system  #"Connect\.Browser"
                                       :service #"Glue Enterprise"}
                                      {:system  "Connect.Browser"
                                       :service "Glue"}))))
  (testing "missing key fails"
    (is (false? (f/publisher-matches? {:system  #"Connect\.Browser"
                                       :service #"Glue Enterprise"}
                                      {:system "Connect.Browser"}))))
  (testing "empty filter matches everything"
    (is (f/publisher-matches? {}
                              {:system "Connect.Browser"}))))

(deftest metrics-matching
  (testing "a single matching element matches the whole list"
    (is (cf/values-match? ["bleh" #"/App/UserJourney/.*"] "/App/UserJourney/Action")))
  (testing "matching works with plain strings as well"
    (is (cf/values-match? ["/App/UserJourney/Action"] "/App/UserJourney/Action")))
  (testing "at least one must match"
    (is (false? (cf/values-match? [#"bleh"] "x")))))

(deftest allowed
  (testing "missing configuration allows by default"
    (is (f/allowed? nil
                    {:system  "Connect.Browser"
                     :service "Glue Enterprise"}
                    "/App/UserJourney/Action")))
  (testing "if there is config, then at least one whitelist is needed"
    (is (f/allowed? {:publishers [{:publisher {:system  #"Connect\.Browser"
                                               :service #"Glue Enterprise"}
                                   :metrics   {:whitelist ["/App/UserJourney/Action"]}}]}
                    {:system  "Connect.Browser"
                     :service "Glue Enterprise"}
                    "/App/UserJourney/Action")))
  (testing "the behavior for non matched publishers is configurable"
    (is (f/allowed? {:publishers  [{:publisher {:system  "a"
                                                :service "b"}
                                    :metrics   {:whitelist ["/App/UserJourney/Action"]}}]
                     :non-matched :whitelist}
                    {:system  "c"
                     :service "d"}
                    "/App/UserJourney/Action"))
    (is (not (f/allowed? {:publishers  [{:publisher {:system  "a"
                                                     :service "b"}
                                         :metrics   {:whitelist ["/App/UserJourney/Action"]}}]
                          :non-matched :blacklist}
                         {:system  "c"
                          :service "d"}
                         "/App/UserJourney/Action")))
    (testing "if there is a matching publisher, then the metric needs to be whitelisted"
      (is (not (f/allowed? {:publishers [{:publisher {:system "a"}
                                          :metrics   {:whitelist ["m"]}}]}
                           {:system "a"}
                           "m1"))))
    (testing "any matching blacklist fails"
      (is (not (f/allowed? {:publishers [{:publisher {:system "a"}
                                          :metrics   {:whitelist ["m"]}}
                                         {:publisher {:system "a"}
                                          :metrics   {:blacklist ["m"]}}]}
                           {:system "a"}
                           "m"))))))