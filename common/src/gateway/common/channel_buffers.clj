(ns gateway.common.channel-buffers
  (:require [clojure.core.async.impl.protocols :as impl])
  (:import [java.util LinkedList Queue]))

(defprotocol DroppedCount
  (dropped-count [this]))

(deftype DroppingBufferWithSignal [^LinkedList buf ^long n ^:unsynchronized-mutable ^long total-dropped on-drop]
  impl/UnblockingBuffer
  impl/Buffer
  (full? [this]
    false)
  (remove! [this]
    (.removeLast buf))
  (add!* [this itm]
    (if-not (>= (.size buf) n)
      (.addFirst buf itm)
      (do
        (set! total-dropped (inc total-dropped))
        (when on-drop
          (on-drop itm total-dropped))))
    this)
  (close-buf! [this]
    (set! total-dropped 0))
  clojure.lang.Counted
  (count [this]
    (.size buf))
  DroppedCount
  (dropped-count [this]
    total-dropped))

(defn dropping-buffer-with-signal
  ([n]
   dropping-buffer-with-signal n nil)
  ([n on-drop]
   (DroppingBufferWithSignal. (LinkedList.) n 0 on-drop)))
