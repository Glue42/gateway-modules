(ns gateway.common.messages)


(defn outgoing
  [receiver message]
  {:receiver receiver :body message})

(defn broadcast
  [source message]
  (assoc (outgoing {:type :cluster} message) :source source))

(defn unicast
  [source recipient message]
  (assoc (outgoing recipient message) :source source))


(defn error?
  [{message :message}]
  (= :error (:type message)))

(defn- error-msg
  [domain-uri request-id peer-id reason context]
  (let [result {:domain     domain-uri
                :type       :error
                :request_id request-id
                :peer_id    peer-id
                :reason_uri (:uri reason)
                :reason     (:message reason)}]
    (if context
      (assoc result :context context)
      result)))

(defn error
  (
   [domain-uri recipient request-id peer-id reason context]
   (outgoing recipient (error-msg domain-uri request-id peer-id reason context)))
  (
   [domain-uri recipient request-id peer-id reason]
   (error domain-uri recipient request-id peer-id reason nil)))

(defn success
  (
   [domain-uri recipient request-id peer-id]
   (outgoing recipient (success domain-uri request-id peer-id)))
  (
   [domain-uri request-id peer-id]
   {:domain     domain-uri
    :type       :success
    :request_id request-id
    :peer_id    peer-id}))

(defn reply
  "Creates a successful reply to message."
  ([msg] (reply msg :success))
  ([{:keys [body source]} type]
   (let [{peer-id :peer_id request-id :request_id domain :domain} body]
     (outgoing source {:domain     domain
                       :type       type
                       :request_id request-id
                       :peer_id    peer-id}))))

(defn local-source?
  [source]
  (= (:type source) :local))

(defn remote-source?
  [source]
  (not (local-source? source)))

(defn token
  (
   [domain-uri recipient request-id peer-id t]
   (outgoing recipient (token domain-uri request-id peer-id t)))
  (
   [domain-uri request-id peer-id t]
   {:domain     domain-uri
    :type       :token
    :request_id request-id
    :peer_id    peer-id
    :token      t}))

(defn peer-added
  (
   [domain-uri recipient peer-id new-peer-id new-peer-identity meta]
   (outgoing recipient (peer-added domain-uri peer-id new-peer-id new-peer-identity meta)))
  (
   [domain-uri peer-id new-peer-id new-peer-identity meta]
   {:domain      domain-uri
    :type        :peer-added
    :peer_id     peer-id
    :new_peer_id new-peer-id
    :identity    new-peer-identity
    :meta        meta}))

(defn peer-removed
  (
   [domain-uri recipient peer-id removed-peer-id reason]
   (outgoing recipient (peer-removed domain-uri peer-id removed-peer-id reason)))
  (
   [domain-uri peer-id removed-peer-id reason]
   {:domain     domain-uri
    :type       :peer-removed
    :peer_id    peer-id
    :removed_id removed-peer-id
    :reason_uri (:uri reason)
    :reason     (:message reason)}))
