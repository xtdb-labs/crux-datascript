(ns crux-datascript.core
  (:require [datascript.core :as d]
            [crux.api :as c]))


;; all entities need a :crux.db/id
;; no cardinality-one attributes
;; ref attributes have to be prefixed with `r.`
;; a delete in Crux does not equate to a retractEntity

;; transaction-time (tt) is the global `now` for the system, across crux and in datascript, not valid-time (vt)
;; designed for vt=tt sync with datascript, so vt is used for datascript's tt (if tracked)
;; this is currently from one node, but what is needed is a feed from all transactions (submitted across all nodes, via hooks on tx-log successes), also combined with the future-time `now` index/polling merged in to support proactive ops (retroactive vt ops would still be out of scope)

(defn ref-keyword? [k]
  (when-let [nspc (namespace k)]
    (clojure.string/starts-with? nspc "r.")))

(defn docs->schema [docs]
  (merge (reduce #(assoc %1 %2 {:db/cardinality :db.cardinality/many}) {} (mapcat keys docs))
         (reduce #(assoc %1 %2 {:db/valueType :db.type/ref
                                :db/cardinality :db.cardinality/many}) {}
                 (remove #(not (ref-keyword? %))
                         (mapcat keys docs)))
         {:crux.db/id {:db/unique :db.unique/identity}}))

(defn doc->lookup-doc [doc]
  (into {}
        (map (fn [k]
               [k (let [v (k doc)]
                    (if (ref-keyword? k)
                      [:crux.db/id v]
                      v))])
             (keys doc))))

(defn eid->retractions [db eid]
  (let [eid (:db/id (d/entity db eid))] ;; resolve eid if it is a lookup vector
    (->> (take-while #(= (:e %) eid) (d/datoms db :eavt eid))
         (filter #(not (contains? #{:db/id :crux.db/id} (:a %))))
         (mapv #(vector :db/retract (:e %) (:a %) (:v %))))))

(defn crux-transact! [node ops]
  (let [submitted-tx (c/submit-tx node ops)]
    (c/await-tx node submitted-tx)
    (when (c/tx-committed? node submitted-tx)
      [ops submitted-tx])))

(defn submitted-tx->hybrid-report [db ops {:keys [crux.tx/tx-id crux.tx/tx-time] :as submitted-tx}]
  {:db-after submitted-tx
   :db-before (assoc submitted-tx :crux.tx/tx-id (dec tx-id)) ;; good enough?
   :tx-time tx-time
   :tx-data (reduce into
                    (mapv (fn [[op & args]]
                            (let [doc ((if (= op :crux.tx/cas) second first) args)
                                  retract-op [:db.fn/call
                                              eid->retractions
                                              [:crux.db/id (:crux.db/id doc)]]]
                              (condp contains? op
                                #{:crux.tx/put :crux.tx/cas}
                                (into (vec (map #(do {:crux.db/id (% doc)})
                                                (filter ref-keyword? (keys doc))))
                                      [retract-op
                                       (doc->lookup-doc (c/entity db (:crux.db/id doc)))])
                                #{:crux.tx/delete :crux.tx/evict}
                                (when (nil? (c/entity db (:crux.db/id doc)))
                                  [retract-op])
                                (throw (Exception. (str "crux op not recognised: " op)))
                                )))
                          ops))}) ;; maybe add :tempids and :tx-meta

(defn crux->datascript [db ds ops submitted-tx]
  (let [{:keys [tx-data]} (submitted-tx->hybrid-report db ops submitted-tx)
        ds-schema (merge (:schema ds)
                         (docs->schema (filter map? tx-data)))]
    (:db-after (d/with (if (not (= ds-schema (:schema ds))) ;; ds schema migration isn't cheap
                         (if (nil? (d/datoms ds :eavt))
                           @(d/create-conn ds-schema)
                           @(d/conn-from-datoms (d/datoms ds :eavt) ds-schema))
                         ds)
                       tx-data))))
