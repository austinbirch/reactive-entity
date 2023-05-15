(ns austinbirch.reactive-entity
  (:refer-clojure :exclude [exists?])
  (:require [austinbirch.reactive-entity.impl :as impl]))

(defn entity
  [eid]
  (impl/entity eid))

(defn entities
  [attr]
  (impl/entities attr))

(defn init!
  [conn]
  (impl/init! conn))

(defn exists?
  [entity]
  (impl/exists? entity))

(defn is-reactive-entity?
  [entity]
  (instance? impl/ReactiveEntity entity))

(defn snapshot-entity-as-map
  [entity]
  (impl/snapshot-entity-as-map entity))