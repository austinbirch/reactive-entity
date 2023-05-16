(ns austinbirch.reactive-entity
  (:refer-clojure :exclude [exists?])
  (:require [austinbirch.reactive-entity.impl :as impl]))

(defn entity
  "Returns a ReactiveEntity (similar to a DataScript Entity) that
  can be used in Reagent components. All attribute accesses during
  render will be tracked, and then if those attributes are changed
  later the component will be re-rendered (ignoring changes to
  attributes that were not accessed by that component).

  `eid` is either a DataScript entity id or a lookup ref."
  [eid]
  (impl/entity eid))

(defn entities
  "Returns a `ReactiveEntitySet` (sort of like a read-only Clojure
  set) that can be used in Reagent components, containing
  `ReactiveEntity`s for the passed attribute (`attr`).

  The idea is that you would use in order to get access to
  `ReactiveEntity`s for all of the entities of a specific type,
  e.g. contacts via `:contact/id`, or todos via `:todo/id`.

  If new entity ids are added to (or removed from) the DataScript
  database with the passed `attr`, then the component will be
  re-rendered and the `ReactiveEntitySet` will contain an
  up-to-date set of all the entities in the DataScript database
  for that `attr`.


  `attr` must be marked `:db.unique/identity` in the DataScript
  schema."
  [attr]
  (impl/entities attr))

(defn init!
  "Sets up tracking state on the given DataScript connection so
  that the reactive entity/entities can be used."
  [conn]
  (impl/init! conn))

(defn exists?
  "Returns true if the entity exists in the DataScript database.

  Useful in order to to support creating reactive entities _before_
  the entities actually exist in the DataScript database - once
  they get added the component will re-render with the data."
  [entity]
  (impl/exists? entity))

(defn is-reactive-entity?
  "Returns true if `entity` is a `ReactiveEntity`"
  [entity]
  (instance? impl/ReactiveEntity entity))

(defn snapshot-entity-as-map
  "Used for debugging only, non-reactive. Returns a map of the
  existing state of the reactive entity - kind of like what you
  would get if you called d/touch on a DataScript entity.

  Useful for logging out the state of a reactive entity during
  a render pass, without having to subscribe to all changes on
  the entity."
  [entity]
  (impl/snapshot-entity-as-map entity))

(defn clear-cache!
  "Empty the tracking state so that all `ReactiveEntity` attribute
  reads and `ReactiveEntitySet`s must be re-read from the backing
  DataScript database.

  Very useful in a live-reload workflow - set up your 'after JS
  load' hook to clear the cache, forcing entirely new reads and
  preventing any stale data from appearing during development."
  []
  (impl/clear-cache!))