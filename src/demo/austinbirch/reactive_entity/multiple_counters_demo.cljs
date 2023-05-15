(ns austinbirch.reactive-entity.multiple-counters-demo
  (:require [datascript.core :as d]
            [austinbirch.reactive-entity.impl :as re]))

(defonce db-conn
         (let [conn (d/create-conn {:session/counters {:db/valueType :db.type/ref
                                                       :db/cardinality :db.cardinality/many}
                                    :counter/id {:db/unique :db.unique/identity}})]
           (d/transact! conn [{:db/id 1
                               :session/counters [{:counter/id 1
                                                   :counter/count 0}
                                                  {:counter/id 2
                                                   :counter/count 10}]}])
           conn))

(defn inc-counter!
  [counter-id]
  (let [counter (-> (d/entity @db-conn [:counter/id counter-id])
                    :counter/count)]
    (d/transact! db-conn [{:counter/id counter-id
                           :counter/count (inc counter)}])))

(defn counter-view
  [{:keys [<counter]}]
  (let [id (:counter/id <counter) ;; subscribe to changes for `:counter/count` for this counter only
        count (:counter/count <counter)]
    [:div
     [:div
      [:span "Counter ID:" id]]
     [:div
      [:span "Count: " (str count)]]
     [:button {:onClick (partial inc-counter! id)}
      "Add one to counter"]]))

(defn multiple-counters-demo
  []
  (let [<session (re/entity 1) ;; make reactive entity
        counters (:session/counters <session) ;; read all counters from session as reactive entities
        ]
    [:div
     (doall
       (map (fn [<counter]
              [:div {:key (:counter/id <counter)}
               [counter-view {:<counter <counter} ;; pass reactive entity as props
                ]])
            counters))]))

(defn ^:dev/after-load mount-root
  []
  (re/clear-cache!)
  (reagent.dom/render [#'multiple-counters-demo] (.getElementById js/document "app")))

(defn init
  []
  (re/init! db-conn)
  (mount-root))
