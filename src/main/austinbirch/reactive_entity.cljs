(ns austinbirch.reactive-entity
  (:refer-clojure :exclude [exists?])
  (:require [datascript.core :as d]
            [datascript.db]
            [reagent.ratom :as ratom]))

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

(defn lookup-ref?
  "Returns true if this eid looks like a lookup-ref (e.g. [:entity/id 123])

  #TODO: can check a lot more about lookup-refs here"
  [eid]
  (sequential? eid))

(defn make-forward-matchers
  "Make a map of 'matchers' that we can use on the tx-data in the tx-report.

  forward-matcher = [e a]: #{cache-key, cache-key...}
    - e = entity id in datascript
    - a = attribute (no reverse attributes)
    - cache-key = cache-key that we need to re-read because we've seen tx-data
                  that might invalidate current state"
  [{:keys [subs
           db-before
           db-after]}]
  (let [lookup-ref-subs (->> subs
                             (filter (fn [[cache-key reactive-pair]]
                                       (let [[e attr] cache-key]
                                         (lookup-ref? e))))
                             (into {}))
        eid-subs (->> subs
                      (filter (fn [[cache-key reactive-pair]]
                                (let [[e attr] cache-key]
                                  (not (lookup-ref? e)))))
                      (into {}))
        lookup-ref-matchers (->> lookup-ref-subs
                                 (reduce (fn [acc [cache-key reactive-pair]]
                                           (let [[lookup-ref attr] cache-key
                                                 eid-before (datascript.db/entid db-before lookup-ref)
                                                 eid-now (datascript.db/entid db-after lookup-ref)]
                                             (cond-> acc
                                                     eid-before
                                                     (update [eid-before attr]
                                                             (fnil conj #{})
                                                             cache-key)
                                                     eid-now
                                                     (update [eid-now attr]
                                                             (fnil conj #{})
                                                             cache-key))))
                                         {}))
        matchers (reduce (fn [acc [cache-key reactive-pair]]
                           (update acc cache-key
                                   (fnil conj #{})
                                   cache-key))
                         lookup-ref-matchers
                         eid-subs)]
    matchers))

(defn make-reverse-matchers
  "Make a map of 'matchers' that we can use on the tx-data in the tx-report.

  reverse-matcher = [a e]: #{cache-key, cache-key...}
    - a = attribute (the forward version of the reverse attribute)
    - e = entity id in datascript
    - cache-key = cache-key that we need to re-read because we've seen tx-data
                  that might invalidate current state"
  [{:keys [reverse-subs
           db-before
           db-after]}]
  (let [lookup-ref-subs (->> reverse-subs
                             (filter (fn [[cache-key reactive-pair]]
                                       (let [[attr e] cache-key]
                                         (lookup-ref? e))))
                             (into {}))
        eid-subs (->> reverse-subs
                      (filter (fn [[cache-key reactive-pair]]
                                (let [[attr e] cache-key]
                                  (not (lookup-ref? e)))))
                      (into {}))
        lookup-ref-matchers (->> lookup-ref-subs
                                 (reduce (fn [acc [cache-key reactive-pair]]
                                           (let [[attr lookup-ref] cache-key
                                                 eid-before (datascript.db/entid db-before lookup-ref)
                                                 eid-now (datascript.db/entid db-after lookup-ref)]
                                             (cond-> acc
                                                     eid-before
                                                     (update [(datascript.db/reverse-ref attr)
                                                              eid-before]
                                                             (fnil conj #{})
                                                             cache-key)
                                                     eid-now
                                                     (update [(datascript.db/reverse-ref attr)
                                                              eid-now]
                                                             (fnil conj #{})
                                                             cache-key))))
                                         {}))
        matchers (reduce (fn [acc [cache-key reactive-pair]]
                           (let [[attr eid] cache-key]
                             (update acc [(datascript.db/reverse-ref attr)
                                          eid]
                                     (fnil conj #{})
                                     cache-key)))
                         lookup-ref-matchers
                         eid-subs)]
    matchers))

(defn mentioned-eid+attr
  [{:keys [subs
           reverse-subs
           tx-report]}]
  (let [db-before (:db-before tx-report)
        db-after (:db-after tx-report)
        tx-data (:tx-data tx-report)]
    (let [forward-refs-for-reverse-subs (reverse-subs->forward-attrs reverse-subs)
          forward-matchers (make-forward-matchers {:subs subs
                                                   :db-before db-before
                                                   :db-after db-after})
          reverse-matchers (make-reverse-matchers {:reverse-subs reverse-subs
                                                   :db-before db-before
                                                   :db-after db-after})]
      (->> tx-data
           (reduce (fn [acc [e a v tx added?]]
                     (let [forward-match-key [e a]
                           reverse-match-key (when (contains? forward-refs-for-reverse-subs a)
                                               [a v])
                           forward-db-id-match-key [e :db/id]]
                       (as-> acc acc
                             ;; match for attribute reads (e.g. [1 :attr/something] or [[:some/id 1] :attr/something])
                             (if (some? (get forward-matchers forward-match-key))
                               (let [matched-cache-keys (get forward-matchers forward-match-key)]
                                 (merge acc (->> matched-cache-keys
                                                 (map (fn [cache-key]
                                                        [{:cache-key cache-key
                                                          :reverse? false}
                                                         true]))
                                                 (into {}))))
                               acc)

                             ;; match for reverse-ref reads (e.g. [1 :attr/_something] or [[:some/id 1] :attr/_something])
                             (if (and reverse-match-key
                                      (some? (get reverse-matchers reverse-match-key)))
                               (let [matched-cache-keys (get reverse-matchers reverse-match-key)]
                                 (merge acc (->> matched-cache-keys
                                                 (map (fn [cache-key]
                                                        [{:cache-key cache-key
                                                          :reverse? true}
                                                         true]))
                                                 (into {}))))
                               acc)

                             ;; if we have a :db/id for this e then we need to re-read in
                             ;; case the `exists?` state just changed (added, or deleted)
                             (if (some? (get forward-matchers forward-db-id-match-key))
                               (assoc acc {:cache-key forward-db-id-match-key
                                           :reverse? false}
                                          true)
                               acc))))
                   {})
           keys
           set))))

(defn process-tx-report
  [tx-report]
  (let [mentioned (mentioned-eid+attr {:subs (:subs @state)
                                       :reverse-subs (:reverse-subs @state)
                                       :tx-report tx-report})]
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
