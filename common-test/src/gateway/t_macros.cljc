(ns gateway.t-macros)

(defn- cljs-env?
  "Take the &env from a macro, and tell whether we are expanding into cljs.
   Source: http://v.gd/rmKNdf"
  [env]
  (boolean (:ns env)))


(defmacro spec-valid?
  [spec x]
  (let [[do-report valid? explain] (if (cljs-env? &env)
                                     ['cljs.test/do-report 'cljs.spec.alpha/valid? 'cljs.spec.alpha/explain]
                                     ['clojure.test/do-report 'clojure.spec.alpha/valid? 'clojure.spec.alpha/explain])]
    `(let [spec# ~spec
           x# ~x
           valid# (~valid? spec# x#)]
       (when-not valid#
         (~do-report {:type     :fail,
                      :message  (str x# " doesnt match specification"),
                      :expected spec#, :actual nil})
         (~explain spec# x#))
       true
       )))

(defmacro just?
  "Checks if coll1 matches coll2"

  [coll1 coll2]
  (let [is (if (cljs-env? &env) 'cljs.test/is 'clojure.test/is)]
    `(let [m1# ~coll1
           m2# ~coll2]

       (if-not (= (count m1#) (count m2#))
         (~is (= m1# m2#) "element count mismatch")

         (let [{m# :message e# :expected a# :actual} (reduce
                                                       (fn [r# el#]
                                                         (if-not (some (partial = el#) m1#)
                                                           (reduced {:message  (str m1# " doesnt contain element " el#),
                                                                     :expected m2#, :actual m1#})
                                                           nil))
                                                       nil
                                                       m2#)]
           (~is (= e# a#) m#))))))

(defmacro message-type?
  [message type]
  (let [is (if (cljs-env? &env) 'cljs.test/is 'clojure.test/is)]
    `(~is (= ~type (get-in ~message [:body :type])))))

(defmacro error-msg?
  (
   [msg]
   (let [is (if (cljs-env? &env) 'cljs.test/is 'clojure.test/is)]
     `(let [{type# :type} msg]
        (~is (= :error type#)))))
  (
   [expected-error-uri msg]
   (let [is (if (cljs-env? &env) 'cljs.test/is 'clojure.test/is)]
     `(let [{type# :type error-uri# :reason_uri} (:body ~msg)]
        (~is (= :error type#))
        (~is (= ~expected-error-uri error-uri#))))))

