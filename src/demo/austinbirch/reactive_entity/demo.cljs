(ns austinbirch.reactive-entity.demo
  (:require [reagent.core :as r]
            [reagent.dom]
            [austinbirch.reactive-entity.impl :as re]
            [datascript.core :as d]
            [re-frame.core :as rf]))

(enable-console-print!)

(defn make-uuid
  []
  (str (random-uuid)))

(defonce db-conn
         (let [conn (d/create-conn {:session/id {:db/unique :db.unique/identity}
                                    :session/todo-lists {:db/valueType :db.type/ref
                                                         :db/cardinality :db.cardinality/many}
                                    :todo-list/id {:db/unique :db.unique/identity}
                                    :todo-list/items {:db/valueType :db.type/ref
                                                      :db/cardinality :db.cardinality/many
                                                      :db/isComponent true}
                                    :todo-list.item/id {:db/unique :db.unique/identity}})]
           ;; add some initial data
           (d/transact! conn
                        [{:session/id "session-id"
                          :session/todo-lists
                          [{:todo-list/id (make-uuid)
                            :todo-list/name "List One"
                            :todo-list/items [{:todo-list.item/id (make-uuid)
                                               :todo-list.item/complete? true
                                               :todo-list.item/todo "Item 1"}
                                              {:todo-list.item/id (make-uuid)
                                               :todo-list.item/complete? false
                                               :todo-list.item/todo "Item 2"}]}
                           {:todo-list/id (make-uuid)
                            :todo-list/name "List Two"
                            :todo-list/items [{:todo-list.item/id (make-uuid)
                                               :todo-list.item/complete? false
                                               :todo-list.item/todo "Item 1"}
                                              {:todo-list.item/id (make-uuid)
                                               :todo-list.item/complete? true
                                               :todo-list.item/todo "Item 2"}]}]}])
           conn))

;; Mutations - you would probably do this differently in a real app

(defn create-new-todo-list
  []
  (let [todo-list {:todo-list/id (make-uuid)
                   :todo-list/name "Some list name"
                   :todo-list/items [{:todo-list.item/id (make-uuid)
                                      :todo-list.item/complete? false
                                      :todo-list.item/todo "Some todo"}]}]
    (d/transact! db-conn
                 [{:session/id "session-id"
                   :session/todo-lists [todo-list]}])))

(defn remove-todo-list
  [list-id]
  (d/transact! db-conn
               [[:db.fn/retractEntity [:todo-list/id list-id]]]))

(defn add-todo
  [list-id]
  (let [todo-item {:todo-list.item/id (make-uuid)
                   :todo-list.item/complete? false
                   :todo-list.item/todo "Some todo added to list"}]
    (d/transact! db-conn
                 [{:todo-list/id list-id
                   :todo-list/items todo-item}])))

(defn remove-todo
  [id]
  (d/transact! db-conn
               [[:db.fn/retractEntity [:todo-list.item/id id]]]))

(defn set-todo-completed
  [id completed?]
  (d/transact! db-conn
               [[:db/add [:todo-list.item/id id] :todo-list.item/complete? completed?]]))

;; UI components - log when they render

(def primary-button-classes "bg-transparent f6 fw6 link dim ba b--blue ph3 pv2 dib blue pointer")
(def secondary-button-classes "bg-transparent f6 fw5 link dim ba ph3 pv2 dib black pointer")

(defn ui-todo-list-item
  [{:keys [<todo-list-item]}]
  (let [id (:todo-list.item/id <todo-list-item)
        complete? (:todo-list.item/complete? <todo-list-item)]
    (js/console.log "render ui-todo-list-item" id)
    [:div {:class ["flex flex-row items-center"
                   "bg-black-05"]}
     [:label {:class ["pa3 pointer"]}
      [:input {:type "checkbox"
               :checked (:todo-list.item/complete? <todo-list-item)
               :onChange (fn [e]
                           (set-todo-completed id (not complete?)))}]]
     [:div {:class ["pr3 flex-auto"]}
      [:span (:todo-list.item/todo <todo-list-item)]]
     [:div {:class ["pr2"]}
      [:button {:class secondary-button-classes
                :onClick #(remove-todo id)}
       "remove item"]]]))

(defn ui-todo-list
  [{:keys [<todo-list]}]
  (let [<todo-list-items (:todo-list/items <todo-list)
        list-id (:todo-list/id <todo-list)]
    (js/console.log "render ui-todo-list" list-id)
    [:div {:class ["pv3"]}
     [:h2 {:class ["f5 fw6 mb3"]} (:todo-list/name <todo-list)]
     [:div {:class ["mb3"]}
      (doall
        (map (fn [<todo-list-item]
               [:div {:key (:todo-list.item/id <todo-list-item)
                      :class ["mb2"]}
                [ui-todo-list-item {:<todo-list-item <todo-list-item}]])
             <todo-list-items))]
     [:div {:class ["flex flex-row justify-between"]}
      [:button {:class primary-button-classes
                :onClick #(add-todo list-id)}
       "Add todo to list"]
      [:button {:class secondary-button-classes
                :onClick #(remove-todo-list list-id)}
       "Remove todo list"]]]))

(defn ui-todo-lists
  []
  (let [<session (re/entity [:session/id "session-id"])
        <todo-lists (:session/todo-lists <session)]
    (js/console.log "render ui-todo-lists")
    [:div {:class ["mw6"]}
     [:h1 {:class ["f4 fw7"]} "Todo lists"]
     [:div {:class ["bg-lightest-blue pa3 mb3"]}
      [:p {:class ["fw5 lh-copy"]} "A really rough demo of avoiding re-renders using reactive-entity. Open the DevTools console and watch the console.log messages when each component renders."]]
     [:div {:class ["mb4"]}
      (doall
        (map (fn [<todo-list]
               [:div {:key (:todo-list/id <todo-list)
                      :class ["mb4 bb bw1 b--black"]}
                [ui-todo-list {:<todo-list <todo-list}]])
             <todo-lists))]
     [:div
      [:button {:class primary-button-classes
                :onClick #(create-new-todo-list)}
       "Create new todo list"]]]))

(defn app
  []
  (js/console.log "render app")
  [:div {:class ["pa3"]}
   [ui-todo-lists]])

;; shadow-cljs calls this after code has been reloaded during development
(defn ^:dev/after-load mount-root
  []
  ;; need to ensure we start with a fresh state each time we
  ;; mount the app root.
  (re/clear-cache!)
  (reagent.dom/render [#'app] (.getElementById js/document "app")))

;; called from index.html
(defn init
  []
  ;; setup reactive-entity to watch our DataScript connection
  (re/init! db-conn)
  ;; perform the initial mount of our Reagent app
  (mount-root))
