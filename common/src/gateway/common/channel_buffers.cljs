(ns gateway.common.channel-buffers
  (:require [cljs.core.async.impl.protocols :as impl]
            [cljs.core.async.impl.buffers :as impl-buf]))

(defprotocol DroppedCount
  (dropped-count [this]))

(deftype DroppingBufferWithSignal [buf n ^:mutable total-dropped on-drop]
  impl/UnblockingBuffer
  impl/Buffer
  (full? [this]
    false)
  (remove! [this]
    (.pop buf))
  (add!* [this itm]
    (if-not (== (.-length buf) n)
      (.unshift buf itm)
      (do
        (set! total-dropped (inc total-dropped))
        (when on-drop
          (on-drop itm total-dropped))))
    this)
  (close-buf! [this])
  cljs.core/ICounted
  (-count [this]
    (.-length buf))
  DroppedCount
  (dropped-count [this]
    total-dropped))

(defn dropping-buffer-with-signal
  ([n]
   (dropping-buffer-with-signal n nil))
  ([n on-drop]
   (DroppingBufferWithSignal. (impl-buf/ring-buffer n) n 0 on-drop)))
