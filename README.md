# reactive-entity

[![Clojars Project](https://img.shields.io/clojars/v/austinbirch/reactive-entity.svg)](https://clojars.org/austinbirch/reactive-entity) [![ci](https://github.com/austinbirch/reactive-entity/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/austinbirch/reactive-entity/actions/workflows/ci.yml)

A reactive version of [DataScript](https://github.com/tonsky/datascript)’s `d/entity` API for
your [Reagent](http://reagent-project.github.io/) components.

This library allows you to use a reactive version of DataScript entities directly in your Reagent components, re-rendering those components only when the exact attributes they depend on are updated.

This is true for simple attributes and reference attributes (i.e. references to other entities), which means that you can essentially 'walk' your DataScript data graph within your components, without triggering unnecessary re-renders. 

---

_Sorry about this stream-of-consciousness README, I might tidy it up later if this is at all interesting to people._

## Simple Example

```clojure
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
  (let [;; make reactive entity
        <session (re/entity 1)
        ;; read `:counter` from reactive entity
        counter (:counter <session)]
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

```

## Installation

Add to your project dependencies:

[![Clojars Project](https://img.shields.io/clojars/v/austinbirch/reactive-entity.svg)](https://clojars.org/austinbirch/reactive-entity)


## More complex example

See the [austinbirch.reactive-entity.demo](/src/demo/austinbirch/reactive_entity/demo.cljs) namespace.

Assuming you have yarn/npm and [shadow-cljs]() installed, you should be able to clone this repo and run:

```
yarn install && shadow-cljs watch demo
```

You’ll be able to view the demo on http://localhost:8080, watching the DevTools console for log output as components
re-render.

I’ll put together a better demo if there’s interest.

## How does it work?

This library listens to `tx-report`s emitted from your DataScript database, and then only re-calculates data for changed
attributes where we also have a component using that data.

### In a little more detail

1. Reagent components create (or are passed via props) a reactive entity.
2. Components read attributes of those reactive entities, returning Reagent reactions containing either plain data or
   more reactive entities (if the attribute is a DataScript reference type).
3. When components read attributes already used in other components, they share the same reactions.
4. When the DataScript database is updated, we check each changed entity+attribute in the `tx-report`, and update any
   reactions that are currently being used in components.
5. Components re-render if the reactions returned in 2. change.
6. When all the components that depend on a a reaction from 2. are unmounted, we dispose of the reaction and no longer
   need to check that entity+attribute pair on further `tx-report`s.

There’s [hardly any code](/src/main/austinbirch/reactive_entity.cljs), so it might actually be easier to just read that.

## Things to note

- For me, passing around entities can remove a bit of the “what data does this map have?” feeling I usually get when
  having a parent component perform a query (for example, using [posh](https://github.com/denistakeda/posh)), and then
  passing down a plain map through component props. `ReactiveEntity` instances have access to whatever you store in your DataScript
  database, regardless of which data parent components need/have accessed.

- `ReactiveEntity` acts like a normal Clojure map, so in theory you should be able to test the components/functions by
  passing plain maps instead.

    ```clojure
    (defn todo-message
      [{:keys [<todo]}]
      [:div [:span (:todo-list.item/todo <todo)]])
    
    (= (todo-message {:<todo {:todo-list.item/todo "Something"}})
       [:div [:span "Something"]])
    ```

- I’m currently using a prefix when naming `ReactiveEntity` instances so that I can see the difference quickly. I’m
  using `<` for now. It’s important to know the difference between data & Reagent atoms/reactions to avoid falling into
  the "[Reactive deref not supported in seq](https://github.com/reagent-project/reagent/issues/18)" problem that affects
  all Reagent atoms/reactions.

    ```clojure
    (defn ui-todo-lists
      []
      (let [<session (re/entity [:session/id "session-id"])
            <todo-lists (:session/todo-lists <session)]
        ;;...
        ))
    ```

## Where will this work best?

- Probably for more complex single-page applications, especially those that render a “graph” of data, rather than a
  strict tree: UIs where the same entity data can appear in many different places within the UI.
- For applications that have more complex relations between data, and especially if that data is maintained in a longer
  session for the user. DataScript works really well for this type of thing (normalisation built in, transacting &
  upserting new information is really simple), and this library works well for navigating those entities from UI
  components.
- In situations where you are happy to expose the structure of your DataScript database to your UI components. Sometimes
  it can be quite nice to avoid creating another conceptual layer that requiring more ‘names’ (e.g. re-frame
  subscriptions -
  see [Perils of accidental complexity in re-frame](https://clojureverse.org/t/perils-of-accidental-complexity-in-re-frame/6254))
  when you have already put a lot of structure around your DataScript database. Though to be clear, you can still use
  re-frame subs, and you definitely should for anything that’s computed/transformed/filtered versions of state.

## What state is this in?

A work in progress for now. Not in production yet, but very close to being used in production in a couple of complex single-page applications.

Has some foundational tests for entities using `:db/id`, lookup-refs, and reverse-ref lookups. Probably could do with some more tests to check that the reactions are being used where possible.

There’s probably a bunch of low-hanging fruit from a performance perspective; I’ve yet to push it too far. I’m happier
about the developer affordance of being able to use DataScript entities directly in Reagent components at the moment.

## Related reading

- [re-frame subscriptions](https://day8.github.io/re-frame/subscriptions/) (you should probably read through all
  the [re-frame](https://day8.github.io/re-frame/re-frame/) docs, they are great).
- [posh](https://github.com/denistakeda/posh) - reactive versions of the `d/q` and `d/pull` APIs
- [Recoil](https://recoiljs.org/) - similar motivations when it comes to rendering graph-like application state. If you
  watch [the introduction video](https://youtu.be/_ISAA_Jt9kI)  it’ll help understand the sort of problems that you get
  when there isn't a strict tree-like hierarchy to your UI.
- [Perils of accidental complexity in re-frame](https://clojureverse.org/t/perils-of-accidental-complexity-in-re-frame/6254)
  - a well thoughtful and well-reasoned piece on complexity within re-frame apps, (especially, in my opinion, "the
  proliferation of names" section)

## Things you can do with this

- Read attributes on entities and only re-render components when attributes they depend on change.

    ```clojure
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
      (let [;; make reactive entity
            <session (re/entity 1)
            ;; read `:counter` from reactive entity
            counter (:counter <session)]
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
    ```

- Navigate your graph data (via reference attributes) just as you would with the d/entity API, only re-rendering if there
  are added/removed references.

    ```clojure
    (ns austinbirch.reactive-entity.multiple-counters-demo
      (:require [datascript.core :as d]
                [austinbirch.reactive-entity :as re]))
    
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
      (let [;; read all counters as reactive entities
            <counters (re/entities :counter/id)]
        [:div
         (doall
           (map (fn [<counter]
                  [:div {:key (:counter/id <counter)}
                   ;; pass reactive entity as props
                   [counter-view {:<counter <counter}]])
                 <counters))]))
    
    (defn ^:dev/after-load mount-root
      []
      (re/clear-cache!)
      (reagent.dom/render [#'multiple-counters-demo] (.getElementById js/document "app")))
    
    (defn init
      []
      (re/init! db-conn)
      (mount-root))
    ```

- Use reactive entities within your re-frame subscriptions, only re-running the subscriptions if the data you access
  changes

    ```clojure
    (ns austinbirch.reactive-entity.re-frame-demo
      (:require [datascript.core :as d]
                [re-frame.core :as rf]
                [austinbirch.reactive-entity :as re]))
    
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
        (re/entities :todo/id)))
    
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
    ```
