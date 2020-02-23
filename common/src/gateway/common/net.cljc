(ns gateway.common.net
  #?(:cljs (:require [os :as os]))
  #?(:clj
     (:import (java.net NetworkInterface Inet4Address InterfaceAddress))))


(defn local-addresses
  []
  #?(:clj
     (->> (NetworkInterface/getNetworkInterfaces)
          (enumeration-seq)
          (map bean)
          (remove :loopback)
          (mapcat :interfaceAddresses)
          (map #(.getAddress ^InterfaceAddress %)))))

(defn local-ip4 []
  #?(:clj  (->> (local-addresses)
                (filter #(instance? Inet4Address %))
                (map #(.getHostAddress ^Inet4Address %))
                (first))
     :cljs (let [addresses (->> (os/networkInterfaces)
                                (js->clj)
                                (vals)
                                (mapcat (fn [details] (filter #(= (get % "family") "IPv4") details)))
                                (filter seq)
                                (group-by #(get % "internal")))]
                (some-> (first (or (get addresses false)
                                   (get addresses true)))
                        (get "address")))))

(defonce local-ip (or (local-ip4) "127.0.0.1"))


