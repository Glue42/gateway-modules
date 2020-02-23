(ns gateway.reason)

(defrecord Reason [uri message])

(defn ex->Reason
  [ex default-uri]
  (let [r (ex-data ex)]
    (Reason. (:uri r default-uri)
             (if-let [msg (:message r)]
               msg
               (ex-message ex)))))

(defn throw-reason
  [uri message]
  (throw (ex-info message (->Reason uri message))))

(defn request->Reason
  [request]
  (->Reason (:reason_uri request) (:reason request)))

(defn reason [uri message]
  (->Reason uri message))
