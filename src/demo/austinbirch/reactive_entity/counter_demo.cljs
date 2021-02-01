(ns austinbirch.reactive-entity.counter-demo
  (:require [datascript.core :as d]
            [austinbirch.reactive-entity :as re]))

(defonce db-conn
         (let [conn (d/create-conn)]
           (d/transact! conn [{:db/id 1
                               :counter 0}])
           conn))

(defn inc-counter!
  []
  (let [counter (-> (d/entity @db-conn 1)
                    :counter)]
    (d/transact! db-conn [{:db/id 1
                           :counter (inc counter)}])))

(defn counter-demo
  []
  (let [<session (re/entity 1) ;; make reactive entity
        counter (:counter <session) ;; read `:counter` from reactive entity
        ]
    [:div
     [:div
      [:span "Counter: " (str counter)]]
     [:button {:onClick inc-counter!}
      "Add one to counter"]]))

(defn ^:dev/after-load mount-root
  []
  (re/clear-cache!)
  (reagent.dom/render [#'counter-demo] (.getElementById js/document "app")))

(defn init
  []
  (re/init! db-conn)
  (mount-root))
