(ns gateway.common.event-logger
  (:require [taoensso.encore :as enc]
            [taoensso.timbre :as timbre]))

(enc/defonce ^:dynamic *config* false)

(defn swap-config! [f & args]
  #?(:cljs (set! *config* (apply f *config* args)))
  #?(:clj (apply alter-var-root #'*config* f args)))

(defn set-event-logging! [m] (swap-config! (fn [_old] (true? m))))
(defn may-log? [_] *config*)

(defmacro log-event [ev]
  `(when *config*
     (timbre/info :event ~ev)))

