(ns austinbirch.reactive-entity
  (:require [datascript.core :as d]
            [datascript.db]
            [reagent.ratom :as ratom]))

;; TODO: support for entities that don't exist yet?
;;
;; - if you use a lookup ref for `[:id "1234"]` that doesn't exist,
;;   we try to resolve eid (which results in `nil`), then later you
;;   transact an entity with `:id` = "1234", we should pick up the
;;   render from there?
;; - currently resolving at first lookup, and then never resolving
;;   eids afer that - ideally don't want to have to resolve eids on
;;   each `process-tx-report`?

(def initial-state {:subs {}
                    :db-conn nil})

(defonce state (atom initial-state))

(defn cache-reactive-pair!
  [eid attr reactive-pair]
  (let [cache-path [:subs eid attr]]
    (ratom/add-on-dispose! (:reaction reactive-pair)
                           (fn []
                             (let [existing-reactive-pair (get-in @state cache-path)
                                   existing-reaction (:reaction existing-reactive-pair)]
                               (when (identical? existing-reaction (:reaction reactive-pair))
                                 (swap! state update
                                        :subs (fn [subs]
                                                (let [attrs (get subs eid)
                                                      next-attrs (dissoc attrs attr)]
                                                  (if (empty? next-attrs)
                                                    (dissoc subs eid)
                                                    (assoc subs eid next-attrs)))))))))
    (swap! state assoc-in cache-path reactive-pair)
    nil))

(defn clear-cache!
  []
  (let [subs (:subs @state)]
    (doseq [[eid attrs->reactive-pair] subs]
      (doseq [[attr reactive-pair] attrs->reactive-pair]
        (when-let [reaction (:reaction reactive-pair)]
          (ratom/dispose! reaction)))))
  (when (not-empty (:subs @state))
    (js/console.error "subs cache should be empty after clearing it.")))

(declare ->ReactiveEntity equiv-entity)

(defn entity
  [eid]
  (->ReactiveEntity eid))

(defn lookup
  [e attr]
  (when-let [db-conn (:db-conn @state)]
    (let [db @db-conn
          eid (::eid e)
          v (get (d/entity db eid) attr)]
      (if (and (not (nil? v))
               (datascript.db/ref? db attr))
        (if (set? v)
          (->> v
               (map (fn [e]
                      (entity (:db/id e))))
               set)
          (entity (:db/id v)))
        v))))

(defn reactive-lookup
  [db e attr]
  (let [resolved-eid (datascript.db/entid db (::eid e))]
    (if (nil? resolved-eid)
      (throw (ex-info (str "Cannot perform reactive lookup for entity that does not exist")
                      {:eid (::eid e)
                       :attr attr}))
      (if-let [reaction (get-in @state [:subs resolved-eid attr :reaction])]
        @reaction
        (let [ratom (ratom/atom (lookup e attr))
              reaction (ratom/make-reaction #(deref ratom))]
          (cache-reactive-pair! resolved-eid
                                attr
                                {:ratom ratom
                                 :reaction reaction})
          @reaction)))))

(deftype ReactiveEntity [eid]
  IEquiv
  (-equiv [this o] (equiv-entity this o))

  ILookup
  (-lookup [this attr]
    (let [db-conn (:db-conn @state)
          db @db-conn]
      (reactive-lookup db {::eid eid} attr)))
  (-lookup [this attr not-found]
    (let [db-conn (:db-conn @state)
          db @db-conn]
      (if-some [v (reactive-lookup db {::eid eid} attr)]
        v
        not-found))))

(defn equiv-entity
  [^ReactiveEntity this that]
  (and (instance? ReactiveEntity that)
       (= (.-eid this) (.-eid ^ReactiveEntity that))))

(defn process-tx-report
  [tx-report]
  (let [subs (:subs @state)
        tx-data (:tx-data tx-report)
        mentioned-eid+attr (reduce (fn [acc [e a]]
                                     (if (some? (get-in subs [e a]))
                                       (assoc acc [e a] true)
                                       acc))
                                   {}
                                   tx-data)]
    (doseq [[e a] (keys mentioned-eid+attr)]
      (let [ratom (get-in subs [e a :ratom])
            v (lookup {::eid e} a)]
        (reset! ratom v)))))

(defn listen!
  [db-conn]
  (d/listen! db-conn
             ::reactive-entity-listener
             #'process-tx-report))

(defn init!
  [conn]
  (listen! conn)
  (reset! state initial-state)
  (swap! state assoc :db-conn conn))

(comment

  ;; To initialise
  ;;
  ;; - create a db, then call `init!` passing the connection
  (let [db-conn (d/create-conn {:app.todo/id {:db/unique :db.unique/identity}})]
    (d/transact! [{:app.todo/id 1
                   :app.todo/date "2021-03-01"}
                  {:app.todo/id 2
                   :app.todo/date "2021-07-01"}]))

  ;; Ensure that you clear the cache when live reloading
  ;; code (see: https://github.com/day8/re-frame/blob/master/docs/FAQs/Why-Clear-Sub-Cache.md)
  (defn ^:dev/after-load mount-root
    []
    (clear-cache!)
    (reagent.dom/render [#'app] (.getElementById js/document "app")))

  ;; your application init function
  (defn init-reagent-app
    []
    ;; setup reactive-entity
    (init! db-conn)
    ;; mount the reagent app
    (mount-root))

  ;; To use in your reagent components:
  ;;
  ;; - create an entity via `entity`
  ;; - treat entities like maps by accessing keys the
  ;;   usual way
  ;; - components only re-render when the values for
  ;;   the keys that they have accessed change
  ;;
  ;; in the example below:
  ;;
  ;; - when the `:app.todo/date` value changes for the
  ;;   todo entity, only the `todo-date` component re-renders.
  ;; - when `:app.todo/message` changes, only the `todo-view`
  ;;   component re-renders for that todo
  ;; - when any other value changes for the todo, none
  ;;   of the components re-render

  (def todo-date
    [{:keys [<todo]}]
    [:div [:span "Date: " (:app.todo/date <todo)]])

  (defn todo-view
    []
    (let [<todo (entity [:app.todo/id 1])]
      [:div
       [:div
        [:span "Todo: " (:app.todo/message <todo)]]
       [todo-date {:<todo <todo}]]))

  )
