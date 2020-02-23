(ns gateway.common.filters)

(defn value-matches? [lhs rhs]
  (if (string? lhs)
    (= lhs rhs)
    (and (some? rhs) (re-matches lhs rhs))))

(defn values-match? [regexes v]
  (reduce
    (fn [_ b]
      (if (value-matches? b v)
        (reduced true)
        false))
    false
    regexes))

