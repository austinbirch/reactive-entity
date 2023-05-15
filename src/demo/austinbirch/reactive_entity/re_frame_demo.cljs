(ns austinbirch.reactive-entity.re-frame-demo
  (:require [datascript.core :as d]
            [re-frame.core :as rf]
            [austinbirch.reactive-entity.impl :as re]))

(defonce db-conn
  (let [conn (d/create-conn {:session/todos {:db/valueType :db.type/ref
                                             :db/cardinality :db.cardinality/many}
                             :todo/id {:db/unique :db.unique/identity}})]
    (d/transact! conn [{:db/id 1
                        :session/todos [{:todo/id 1
                                         :todo/complete? false
                                         :todo/text "Todo 1"}
                                        {:todo/id 2
                                         :todo/complete? true
                                         :todo/text "Todo 2"}
                                        {:todo/id 3
                                         :todo/complete? false
                                         :todo/text "Todo 3"}]}])
    conn))

(defn update-todo-status!
  [todo-id complete?]
  (d/transact! db-conn [[:db/add [:todo/id todo-id] :todo/complete? complete?]]))

(rf/reg-sub
  :todos
  (fn []
    (let [<session (re/entity 1)]
      (:session/todos <session))))

(rf/reg-sub
  :complete-todos
  (fn []
    (rf/subscribe [:todos]))
  (fn [todos]
    (filter (fn [<todo]
              (:todo/complete? <todo))
            todos)))

(rf/reg-sub
  :incomplete-todos
  (fn []
    (rf/subscribe [:todos]))
  (fn [todos]
    (filter (fn [<todo]
              (not (:todo/complete? <todo)))
            todos)))

(defn todo-view
  [{:keys [<todo]}]
  [:div
   [:input {:type "checkbox"
            :checked (:todo/complete? <todo)
            :onChange (fn []
                        (update-todo-status! (:todo/id <todo)
                                             (not (:todo/complete? <todo))))}]
   [:span (:todo/text <todo)]])

(defn todos-demo
  []
  (let [complete-todos @(rf/subscribe [:complete-todos])
        incomplete-todos @(rf/subscribe [:incomplete-todos])]
    [:div
     [:div
      [:div [:span "Complete todos"]]
      (doall
        (map (fn [<todo]
               [todo-view {:key (:todo/id <todo)
                           :<todo <todo}])
             complete-todos))]
     [:div
      [:div [:span "Incomplete todos"]]
      (doall
        (map (fn [<todo]
               [todo-view {:key (:todo/id <todo)
                           :<todo <todo}])
             incomplete-todos))]]))

(defn ^:dev/after-load mount-root
  []
  (re/clear-cache!)
  (reagent.dom/render [#'todos-demo] (.getElementById js/document "app")))

(defn init
  []
  (re/init! db-conn)
  (mount-root))

