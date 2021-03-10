(ns austinbirch.reactive-entity-test
  (:require [cljs.test :refer [deftest is]]
            [austinbirch.reactive-entity :as re]
            [datascript.core :as d]
            [reagent.core]))

;; entity existence
;; ================

(deftest test-does-not-exist-before-adding
  (let [conn (d/create-conn)
        _ (re/init! conn)
        <entity (re/entity 1)]
    (is (false? (re/exists? <entity)))))

(deftest test-exists-after-adding
  (let [conn (d/create-conn)
        _ (re/init! conn)
        <entity (re/entity 1)]
    (d/transact! conn [[:db/add 1 :some/attr "hello"]])
    (is (true? (re/exists? <entity)))))

(deftest test-exists-changes-eid
  (let [conn (d/create-conn)
        _ (re/init! conn)
        _ (d/transact! conn [[:db/add 1 :some/attr "hello"]])
        <entity (re/entity 1)]
    ;; initial state is exists
    (is (true? (re/exists? <entity)))
    (d/transact! conn [[:db.fn/retractEntity 1]])
    ;; remove entity
    (is (false? (re/exists? <entity)))
    ;; add entity
    (d/transact! conn [[:db/add 1 :some/attr "hello"]])
    (is (true? (re/exists? <entity)))))

;; entities using resolved eids
;; ============================

(deftest test-eid-basic-attr
  (let [conn (d/create-conn)
        _ (re/init! conn)
        _ (d/transact! conn [[:db/add 1 :some/attr "hello"]
                             [:db/add 1 :some/attr2 "keep-entity"]])
        <entity (re/entity 1)]
    ;; initial state
    (is (= "hello" (:some/attr <entity)))
    ;; update attr
    (d/transact! conn [[:db/add 1 :some/attr "goodbye"]])
    (is (= "goodbye" (:some/attr <entity)))
    ;; remove attr
    (d/transact! conn [[:db.fn/retractAttribute 1 :some/attr]])
    (is (nil? (:some/attr <entity)))
    ;; remove entity

    (is (true? (re/exists? <entity)))
    (d/transact! conn [[:db.fn/retractEntity 1]])
    (is (false? (re/exists? <entity)))))

(deftest test-eid-ref-attr-one
  (let [conn (d/create-conn {:entity/child {:db/valueType :db.type/ref
                                            :db/cardinality :db.cardinality/one}})
        _ (re/init! conn)
        _ (d/transact! conn
                       [[:db/add 1 :entity/child 2]
                        [:db/add 2 :some/attr "hello"]])
        <entity (re/entity 1)]
    ;; initial state
    (is (= (re/entity 2)
           (:entity/child <entity)))
    ;; replace entity
    (d/transact! conn [[:db/add 1 :entity/child 3]
                       [:db/add 3 :some/attr "hello"]])
    (is (= (re/entity 3)
           (:entity/child <entity)))
    ;; remove entity
    (d/transact! conn [[:db.fn/retractEntity 3]])
    (is (nil? (:entity/child <entity)))))

(deftest test-eid-ref-attr-many
  (let [conn (d/create-conn {:entity/children {:db/valueType :db.type/ref
                                               :db/cardinality :db.cardinality/many}})
        _ (re/init! conn)
        _ (d/transact! conn
                       [[:db/add 1 :entity/children 2]
                        [:db/add 1 :entity/children 3]
                        [:db/add 2 :some/attr "hello1"]
                        [:db/add 3 :some/attr "hello2"]])
        <entity (re/entity 1)]
    ;; initial state
    (is (= #{(re/entity 2)
             (re/entity 3)}
           (:entity/children <entity)))
    ;; add an entity
    (d/transact! conn [[:db/add 1 :entity/children 4]
                       [:db/add 4 :some/attr "hello3"]])
    (is (= #{(re/entity 2)
             (re/entity 3)
             (re/entity 4)}
           (:entity/children <entity)))
    ;; remove an entity
    (d/transact! conn [[:db.fn/retractEntity 2]])
    (is (= #{(re/entity 3)
             (re/entity 4)}
           (:entity/children <entity)))))

(deftest test-eid-reverse-ref-one
  (let [conn (d/create-conn {:entity/child {:db/valueType :db.type/ref
                                            :db/cardinality :db.cardinality/one}})
        _ (re/init! conn)
        _ (d/transact! conn [[:db/add 1 :some/attr "hello"]
                             [:db/add 1 :entity/child 2]
                             [:db/add 2 :some/attr "goodbye"]])
        <entity (re/entity 2)]
    ;; initial state with parent
    (is (= #{(re/entity 1)}
           (:entity/_child <entity)))
    ;; remove parent
    (d/transact! conn [[:db.fn/retractEntity 1]])
    (is (= nil (:entity/_child <entity)))
    ;; add parent back
    (d/transact! conn [[:db/add 1 :some/attr "hello"]
                       [:db/add 1 :entity/child 2]])
    (is (= #{(re/entity 1)
             (:entity/_child <entity)}))))

(deftest test-eid-reverse-ref-many
  (let [conn (d/create-conn {:entity/children {:db/valueType :db.type/ref
                                               :db/cardinality :db.cardinality/many}})
        _ (re/init! conn)
        _ (d/transact! conn [[:db/add 1 :some/attr "hello"]
                             [:db/add 1 :entity/children 2]
                             [:db/add 1 :entity/children 3]
                             [:db/add 2 :some/attr "goodbye1"]
                             [:db/add 3 :some/attr "goodbye2"]])
        <entity1 (re/entity 2)
        <entity2 (re/entity 3)]
    ;; initial state with parent
    (is (= #{(re/entity 1)}
           (:entity/_children <entity1)))
    (is (= #{(re/entity 1)}
           (:entity/_children <entity2)))
    ;; remove parent
    (d/transact! conn [[:db.fn/retractEntity 1]])
    (is (= nil (:entity/_children <entity1)))
    (is (= nil (:entity/_children <entity2)))
    ;; add parent back
    (d/transact! conn [[:db/add 1 :some/attr "hello"]
                       [:db/add 1 :entity/child 2]])
    (is (= #{(re/entity 1)
             (:entity/_children <entity1)}))
    (is (= #{(re/entity 1)
             (:entity/_children <entity2)}))))

;; entities using lookup refs
;; ==========================

(deftest test-lookup-ref-basic-attr
  (let [conn (d/create-conn {:entity/id {:db/unique :db.unique/identity}})
        _ (re/init! conn)
        _ (d/transact! conn [[:db/add 1 :some/attr "hello"]
                             [:db/add 1 :entity/id "entity1"]])
        <entity (re/entity [:entity/id "entity1"])]
    (is (= "hello" (:some/attr <entity)))))

(deftest test-lookup-ref-basic-attr-changing
  (let [conn (d/create-conn {:entity/id {:db/unique :db.unique/identity}})
        _ (re/init! conn)
        _ (d/transact! conn [[:db/add 1 :some/attr "hello"]
                             [:db/add 1 :entity/id "entity1"]])
        <entity (re/entity [:entity/id "entity1"])]
    (is (= "hello" (:some/attr <entity)))
    (d/transact! conn [[:db/add 1 :some/attr "goodbye"]])
    (is (= "goodbye" (:some/attr <entity)))
    (d/transact! conn [[:db.fn/retractAttribute 1 :some/attr]])
    (is (nil? (:some/attr <entity)))))

(deftest test-lookup-ref-ref-attr-one
  (let [conn (d/create-conn {:entity/id {:db/unique :db.unique/identity}
                             :entity/child {:db/valueType :db.type/ref
                                            :db/cardinality :db.cardinality/one}})
        _ (re/init! conn)
        _ (d/transact! conn
                       [[:db/add 1 :entity/id "entity1"]
                        [:db/add 1 :entity/child 2]
                        [:db/add 2 :some/attr "hello"]])
        <entity (re/entity [:entity/id "entity1"])]
    ;; initial state
    (is (= (re/entity 2)
           (:entity/child <entity)))
    ;; replace entity
    (d/transact! conn [[:db/add 1 :entity/child 3]
                       [:db/add 3 :some/attr "hello"]])
    (is (= (re/entity 3)
           (:entity/child <entity)))
    ;; remove entity
    (d/transact! conn [[:db.fn/retractEntity 3]])
    (is (nil? (:entity/child <entity)))))

(deftest test-lookup-ref-ref-attr-many
  (let [conn (d/create-conn {:entity/id {:db/unique :db.unique/identity}
                             :entity/children {:db/valueType :db.type/ref
                                               :db/cardinality :db.cardinality/many}})
        _ (re/init! conn)
        _ (d/transact! conn
                       [[:db/add 1 :entity/id "entity1"]
                        [:db/add 1 :entity/children 2]
                        [:db/add 1 :entity/children 3]
                        [:db/add 2 :some/attr "hello1"]
                        [:db/add 3 :some/attr "hello2"]])
        <entity (re/entity [:entity/id "entity1"])]
    ;; initial state
    (is (= #{(re/entity 2)
             (re/entity 3)}
           (:entity/children <entity)))
    ;; add an entity
    (d/transact! conn [[:db/add 1 :entity/children 4]
                       [:db/add 4 :some/attr "hello3"]])
    (is (= #{(re/entity 2)
             (re/entity 3)
             (re/entity 4)}
           (:entity/children <entity)))
    ;; remove an entity
    (d/transact! conn [[:db.fn/retractEntity 2]])
    (is (= #{(re/entity 3)
             (re/entity 4)}
           (:entity/children <entity)))))

(deftest test-lookup-ref-reverse-ref-attr-one
  (let [conn (d/create-conn {:entity/id {:db/unique :db.unique/identity}
                             :entity/child {:db/valueType :db.type/ref
                                            :db/cardinality :db.cardinality/one}})
        _ (re/init! conn)
        _ (d/transact! conn [[:db/add 1 :some/attr "hello"]
                             [:db/add 1 :entity/child 2]
                             [:db/add 2 :entity/id "entity2"]
                             [:db/add 2 :some/attr "goodbye"]])
        <entity (re/entity [:entity/id "entity2"])]
    ;; initial state with parent
    (is (= #{(re/entity 1)}
           (:entity/_child <entity)))
    ;; remove parent
    (d/transact! conn [[:db.fn/retractEntity 1]])
    (is (= nil (:entity/_child <entity)))
    ;; add parent back
    (d/transact! conn [[:db/add 1 :some/attr "hello"]
                       [:db/add 1 :entity/child 2]])
    (is (= #{(re/entity 1)

             (:entity/_child <entity)}))))
