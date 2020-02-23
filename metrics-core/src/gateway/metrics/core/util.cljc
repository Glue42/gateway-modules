(ns gateway.metrics.core.util
  #?(:cljs (:require-macros [taoensso.timbre :refer [trace]]))
  (:require
    #?(:clj [clojure.core.async :as a]
       :cljs [cljs.core.async :as a :refer-macros [go go-loop]])
    [taoensso.timbre :as timbre]))


(defn batch
  [in batch-size interval buffer-or-n]

  (timbre/info "initializing batch with" batch-size " batch size and" interval "batch interval")
  (let [out (a/chan buffer-or-n)
        lim (dec batch-size)]
    (a/go-loop [t (a/timeout interval)
                data (transient [])]
               (let [[msg ch] (a/alts! [in t])
                     cnt (count data)]

                 (cond
                   (= ch t) (do
                              (timbre/trace "scheduled flush")
                              (when (pos? cnt)
                                (a/>! out (persistent! data)))
                              (recur (a/timeout interval)
                                     (transient [])))
                   (nil? msg) (do
                                (when (pos? cnt)
                                  (a/>! out (persistent! data)))
                                (a/close! out))

                   (== cnt lim) (do
                                  (timbre/trace "hit batch size" batch-size)
                                  (a/>! out (persistent! (conj! data msg)))
                                  (recur (a/timeout interval)
                                         (transient [])))
                   :else (recur t
                                (conj! data msg)))))
    out))

