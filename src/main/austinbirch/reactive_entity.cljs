(ns austinbirch.reactive-entity
  (:refer-clojure :exclude [exists?])
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
;;   eids after that - ideally don't want to have to resolve eids on
;;   each `process-tx-report`?

(def initial-state {:subs {}
                    :reverse-subs {}
                    :db-conn nil})

(defonce state (atom initial-state))

(defn cache-reactive-pair!
  [eid attr reactive-pair]
  (let [reverse? (datascript.db/reverse-ref? attr)
        cache-bucket (if reverse?
                       :reverse-subs
                       :subs)
        cache-key (if reverse?
                    [attr eid]
                    [eid attr])]
    (ratom/add-on-dispose! (:reaction reactive-pair)
                           (fn []
                             (let [existing-reactive-pair (get-in @state [cache-bucket cache-key])
                                   existing-reaction (:reaction existing-reactive-pair)]
                               (when (identical? existing-reaction (:reaction reactive-pair))
                                 (swap! state update cache-bucket
                                        (fn [cache]
                                          (dissoc cache cache-key)))))))
    (swap! state assoc-in [cache-bucket cache-key] reactive-pair)
    nil))

(defn clear-cache!
  []
  (doseq [cache-bucket [:subs :reverse-subs]]
    (let [cache (get @state cache-bucket)]
      (doseq [[cache-key reactive-pair] cache]
        (when-let [reaction (:reaction reactive-pair)]
          (ratom/dispose! reaction))))
    (when (not-empty (get @state cache-bucket))
      (js/console.error (ex-info "cache bucket should be empty after clearing it."
                                 {:cache-bucket cache-bucket})))))

(declare ->ReactiveEntity equiv-entity reactive-entity-lookup)

(defn entity
  [eid]
  (->ReactiveEntity eid))

(defn lookup
  [e attr]
  (when-let [db-conn (:db-conn @state)]
    (let [db @db-conn
          eid (::eid e)
          v (get (d/entity db eid) attr)]

      (cond
        ;; db/id requires us to check index to actually find out if the entity really exists
        (= attr :db/id)
        (let [entid (datascript.db/entid db eid)]
          (if (seq (d/datoms db :eavt entid))
            entid
            nil))

        (and (not (nil? v))
             (or (datascript.db/ref? db attr)
                 (datascript.db/reverse-ref? attr)))
        (if (set? v)
          (->> v
               (map (fn [e]
                      (entity (:db/id e))))
               set)
          (entity (:db/id v)))

        :else
        v))))

(defn reactive-lookup
  [db e attr]
  (let [eid (::eid e)
        reverse? (datascript.db/reverse-ref? attr)
        cache-bucket (if reverse?
                       :reverse-subs
                       :subs)
        cache-key (if reverse?
                    [attr eid]
                    [eid attr])]
    (if-let [reactive-pair (get-in @state [cache-bucket cache-key])]
      @(:reaction reactive-pair)
      (let [initial-v (lookup e attr)
            ratom (ratom/atom initial-v)
            reaction (ratom/make-reaction #(deref ratom))]
        (cache-reactive-pair! eid
                              attr
                              {:ratom ratom
                               :reaction reaction})
        @reaction))))

(deftype ReactiveEntity [eid]
  IEquiv
  (-equiv [this o] (equiv-entity this o))

  ILookup
  (-lookup [this attr] (reactive-entity-lookup this attr nil))
  (-lookup [this attr not-found] (reactive-entity-lookup this attr not-found))

  IFn
  (-invoke [this attr] (reactive-entity-lookup this attr nil))
  (-invoke [this attr not-found] (reactive-entity-lookup this attr not-found))

  IHash
  (-hash [_] (hash eid))

  IAssociative
  (-contains-key?
    [this attr]
    (not= ::not-found
          (reactive-entity-lookup this attr ::not-found))))

(defn reactive-entity-lookup
  [^ReactiveEntity this attr not-found]
  (let [db @(:db-conn @state)
        eid (.-eid this)]
    (if-some [v (reactive-lookup db {::eid eid} attr)]
      v
      not-found)))

(defn exists?
  [^ReactiveEntity this]
  (some? (reactive-entity-lookup this :db/id nil)))

(defn equiv-entity
  [^ReactiveEntity this that]
  (and (instance? ReactiveEntity that)
       (= (.-eid this) (.-eid ^ReactiveEntity that))))

(defn reverse-subs->forward-attrs
  [reverse-subs]
  (->> reverse-subs
       (map (fn [[cache-key _]]
              (datascript.db/reverse-ref (first cache-key))))
       set))

(defn mentioned-eid+attr
  [{:keys [subs
           reverse-subs
           tx-data]}]
  (let [forward-refs-for-reverse-subs (reverse-subs->forward-attrs reverse-subs)]
    (->> tx-data
         (reduce (fn [acc [e a v tx added?]]
                   (let [e-a-cache-key [e a]
                         e-db-id-cache-key [e :db/id]]
                     (cond
                       (some? (get subs e-a-cache-key))
                       (assoc acc {:cache-key e-a-cache-key
                                   :reverse? false}
                                  true)

                       (some? (get subs e-db-id-cache-key))
                       (assoc acc {:cache-key e-db-id-cache-key
                                   :reverse? false}
                                  true)

                       (and (contains? forward-refs-for-reverse-subs a)
                            (get reverse-subs [(datascript.db/reverse-ref a) v]))
                       (assoc acc {:cache-key [(datascript.db/reverse-ref a) v]
                                   :reverse? true}
                                  true)

                       :else
                       acc)))
                 {})
         keys
         set)))

(defn process-tx-report
  [tx-report]
  (let [tx-data (:tx-data tx-report)
        mentioned (mentioned-eid+attr {:subs (:subs @state)
                                       :reverse-subs (:reverse-subs @state)
                                       :tx-data tx-data})]
    (doseq [{:keys [cache-key
                    reverse?]} mentioned]
      (let [cache-bucket (get @state (if reverse?
                                       :reverse-subs
                                       :subs))
            ratom (:ratom (get cache-bucket cache-key))
            [e a] cache-key
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
