(ns metrics.t-util
  (:require [gateway.metrics.core.util :as util]

            [clojure.core.async :as a]
            [clojure.test :refer :all]))

(deftest batch-test
         (let [in-ch (a/chan)
               out-ch (util/batch in-ch 2 100000 (a/buffer 3))]

           (a/>!! in-ch {:identity {:instance "instance"
                                    :service  "service"
                                    :system   "system"}
                         :metrics  {"/system/metric-a" {:datapoints [{:timestamp 1
                                                                      :value     {:value "a-value"}}]
                                                        :definition {:type "string"}}}})
           (a/>!! in-ch {:identity {:instance "instance"
                                    :service  "service"
                                    :system   "system"}
                         :metrics  {"/system/metric-a" {:datapoints [{:timestamp 3
                                                                      :value     {:value "b-value"}}]
                                                        :definition {:type "string"}}
                                    "/system/metric-b" {:datapoints [{:timestamp 2
                                                                      :value     {:value 123.0}}]
                                                        :definition {:type "number"}}
                                    "/system/metric-c" {:datapoints [{:timestamp 4
                                                                      :value     {:value {"bar" {:value 456.0}
                                                                                          "foo" {:value "foo-value"}}}}]
                                                        :definition {:composite {"bar" {:type "number"}
                                                                                 "foo" {:type "string"}}
                                                                     :type      "object"}}}})
           (let [[msg _] (a/alts!! [out-ch (a/timeout 1000)])]
             (is (= msg [{:identity {:instance "instance"
                                     :service  "service"
                                     :system   "system"}
                          :metrics  {"/system/metric-a" {:datapoints [{:timestamp 1
                                                                       :value     {:value "a-value"}}]
                                                         :definition {:type "string"}}}}
                         {:identity {:instance "instance"
                                     :service  "service"
                                     :system   "system"}
                          :metrics  {"/system/metric-a" {:datapoints [{:timestamp 3
                                                                       :value     {:value "b-value"}}]
                                                         :definition {:type "string"}}
                                     "/system/metric-b" {:datapoints [{:timestamp 2
                                                                       :value     {:value 123.0}}]
                                                         :definition {:type "number"}}
                                     "/system/metric-c" {:datapoints [{:timestamp 4
                                                                       :value     {:value {"bar" {:value 456.0}
                                                                                           "foo" {:value "foo-value"}}}}]
                                                         :definition {:composite {"bar" {:type "number"}
                                                                                  "foo" {:type "string"}}
                                                                      :type      "object"}}}}])))
           (a/close! in-ch)))