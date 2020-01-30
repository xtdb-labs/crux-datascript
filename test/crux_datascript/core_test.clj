(ns crux-datascript.core-test
  (:require [clojure.test :refer :all]
            [crux-datascript.core :as x]
            [datascript.core :as d]
            [crux.api :as c]))

(deftest a-test
  (testing "Basic end-to-end"
    (let [my-ops [[:crux.tx/put {:crux.db/id :test :a :vall} #inst "2020-01-22" #inst "2020-04-27"]
                  ;;[:crux.tx/cas {:crux.db/id :test :a :vall} {:crux.db/id :test :a :val2}]
                  [:crux.tx/put {:crux.db/id :test3 :a :val :what :is-happening :r.blah/bloo :asdf}]
                  [:crux.tx/delete :test2]]
          crux-options
          {:crux.node/topology :crux.standalone/topology
           :crux.node/kv-store "crux.kv.memdb/kv" ; in-memory, see docs for LMDB/RocksDB storage
           :crux.standalone/event-log-kv-store "crux.kv.memdb/kv" ; same as above
           :crux.standalone/event-log-dir "data/event-log-dir-1" ; :event-log-dir is ignored when using MemKv
           :crux.kv/db-dir "data/db-dir-1"}] ; :db-dir is ignored when using MemKv

      (with-open [node (c/start-node crux-options)]
        (when-let [transact-result (x/crux-transact! node my-ops)]
          (let [ds-node (d/create-conn {})]
            (reset! ds-node
                    (apply x/crux->datascript (c/db node) @ds-node transact-result))
            (is (= (d/q '[:find  ?n ?a
                          :where
                          [?n :a :val]
                          [?n :r.blah/bloo ?b]
                          [?b :crux.db/id ?a]]
                        @ds-node)
                   #{[3 :asdf]}))))))))
