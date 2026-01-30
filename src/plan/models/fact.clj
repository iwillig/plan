(ns plan.models.fact
  "Fact entity model using HugSQL"
  (:refer-clojure :exclude [update])
  (:require
   [hugsql.adapter.next-jdbc :as next-adapter]
   [hugsql.core :as hugsql]
   [malli.core :as m]))

(set! *warn-on-reflection* true)

;; Configure HugSQL to use next.jdbc adapter
(hugsql/set-adapter! (next-adapter/hugsql-adapter-next-jdbc))

;; Define HugSQL functions from the SQL file
(hugsql/def-db-fns "sql/facts.sql")

;; Malli schema for a Fact entity
(def Fact
  [:map
   [:id [:maybe :int]]
   [:plan_id :int]
   [:name :string]
   [:description [:maybe :string]]
   [:content [:maybe :string]]
   [:created_at [:maybe :string]]
   [:updated_at [:maybe :string]]])

(defn create
  "Create a new fact for a plan"
  [conn plan-id name description content]
  (fact-create conn {:plan-id plan-id
                     :name name
                     :description description
                     :content content}))

(defn get-by-id
  "Get a fact by ID"
  [conn id]
  (fact-get-by-id conn {:id id}))

(defn get-by-plan
  "Get all facts for a plan"
  [conn plan-id]
  (fact-get-by-plan conn {:plan-id plan-id}))

(defn get-by-name
  "Get a fact by plan ID and name"
  [conn plan-id name]
  (first
   (fact-get-by-name conn {:plan-id plan-id
                           :name name})))

(defn update
  "Update a fact by ID. Only updates fields that are provided (not nil).
   Returns nil if no fields to update or fact not found."
  [conn id {:keys [name description content] :as updates}]
  (let [fact (get-by-id conn id)]
    (when (and fact (seq updates))
      (fact-update conn {:id id
                         :name (or name (:name fact))
                         :description (or description (:description fact))
                         :content (or content (:content fact))}))))

(defn delete
  "Delete a fact by ID"
  [conn id]
  (let [result (fact-delete conn {:id id})]
    (> result 0)))

(defn delete-by-plan
  "Delete all facts for a plan"
  [conn plan-id]
  (fact-delete-by-plan conn {:plan-id plan-id}))

(defn upsert
  "Insert or update a fact by plan_id and name. Returns the fact.
   If a fact with this plan_id and name exists, updates it. Otherwise creates new."
  [conn plan-id {:keys [name description content]}]
  (let [existing (get-by-name conn plan-id name)]
    (if existing
      (update conn (:id existing) {:description description
                                   :content content})
      (create conn plan-id name description content))))

(defn delete-orphans
  "Delete facts for a plan that are not in the given set of names.
   Returns the number of facts deleted."
  [conn plan-id keep-names]
  (if (seq keep-names)
    (let [keep-set (set keep-names)
          all-facts (fact-delete-orphans-query conn {:plan-id plan-id})
          orphans (remove #(keep-set (:name %)) all-facts)
          orphan-ids (map :id orphans)]
      (doseq [id orphan-ids]
        (fact-delete conn {:id id}))
      (count orphan-ids))
    (delete-by-plan conn plan-id)))

(defn get-all
  "Get all facts"
  [conn]
  (fact-get-all conn {}))

(defn search
  "Search facts using FTS"
  [conn query]
  (fact-search conn {:query (str query "*")}))

;; Malli function schemas
(m/=> create [:=> [:cat :any :int :string [:maybe :string] [:maybe :string]] Fact])
(m/=> get-by-id [:=> [:cat :any :int] [:maybe Fact]])
(m/=> get-by-plan [:=> [:cat :any :int] [:sequential Fact]])
(m/=> get-by-name [:=> [:cat :any :int :string] [:maybe Fact]])
(m/=> get-all [:=> [:cat :any] [:sequential Fact]])
(m/=> update [:=> [:cat :any :int [:map]] [:maybe Fact]])
