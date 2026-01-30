(ns plan.models.fact
  "Fact entity model with Malli schemas"
  (:refer-clojure :exclude [update])
  (:require
   [malli.core :as m]
   [next.jdbc :as jdbc]
   [plan.db :as db]))

(set! *warn-on-reflection* true)

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

;; Schema for fact creation
(def FactCreate
  [:map
   [:plan_id :int]
   [:name :string]
   [:description [:maybe :string]]
   [:content [:maybe :string]]])

;; Schema for fact updates
(def FactUpdate
  [:map
   [:name {:optional true} :string]
   [:description {:optional true} [:maybe :string]]
   [:content {:optional true} [:maybe :string]]])

(defn create
  "Create a new fact for a plan.
   Returns the created fact with generated id and timestamps."
  [conn plan-id name description content]
  (db/execute-one!
   conn
   {:insert-into :facts
    :columns [:plan_id :name :description :content]
    :values [[plan-id name description content]]
    :returning [:*]}))

(defn get-by-id
  "Fetch a fact by its id. Returns nil if not found."
  [conn id]
  (db/execute-one!
   conn
   {:select [:*]
    :from [:facts]
    :where [:= :id id]}))

(defn get-by-plan
  "Fetch all facts for a plan, ordered by created_at descending."
  [conn plan-id]
  (db/execute!
   conn
   {:select [:*]
    :from [:facts]
    :where [:= :plan_id plan-id]
    :order-by [[:created_at :desc]]}))

(defn get-all
  "Fetch all facts, ordered by created_at descending."
  [conn]
  (db/execute!
   conn
   {:select [:*]
    :from [:facts]
    :order-by [[:created_at :desc]]}))

(defn update
  "Update a fact's fields. Returns the updated fact or nil if not found."
  [conn id updates]
  (let [set-clause (select-keys updates [:name :description :content])]
    (when (seq set-clause)
      (db/execute-one!
       conn
       {:update :facts
        :set set-clause
        :where [:= :id id]
        :returning [:*]}))))

(defn delete
  "Delete a fact by id. Returns true if a fact was deleted."
  [conn id]
  (let [result (db/execute-one!
                conn
                {:delete-from :facts
                 :where [:= :id id]})]
    ;; next.jdbc returns #:next.jdbc{:update-count N} for DELETE
    (> (get result :next.jdbc/update-count 0) 0)))

(defn delete-by-plan
  "Delete all facts for a plan. Returns the number of facts deleted."
  [conn plan-id]
  (let [result (db/execute!
                conn
                {:delete-from :facts
                 :where [:= :plan_id plan-id]})]
    ;; next.jdbc returns [#:next.jdbc{:update-count N}] for DML operations
    (get (first result) :next.jdbc/update-count 0)))

(defn search
  "Search for facts matching the query using full-text search."
  [conn query]
  (db/search-facts conn query))

(defn get-by-plan-and-name
  "Fetch a fact by plan_id and name. Returns nil if not found."
  [conn plan-id name]
  (db/execute-one!
   conn
   {:select [:*]
    :from [:facts]
    :where [:and [:= :plan_id plan-id] [:= :name name]]}))

(defn upsert
  "Insert or update a fact by plan_id and name. Returns the fact.
   If a fact with this plan_id and name exists, updates it. Otherwise creates new."
  [conn plan-id {:keys [name description content]}]
  (jdbc/execute! conn
                 [(str "INSERT INTO facts (plan_id, name, description, content) VALUES (?, ?, ?, ?) "
                       "ON CONFLICT(plan_id, name) DO UPDATE SET description = excluded.description, "
                       "content = excluded.content")
                  plan-id name description content])
  (db/execute-one! conn {:select [:*] :from [:facts]
                         :where [:and [:= :plan_id plan-id] [:= :name name]]}))

(defn delete-orphans
  "Delete facts for a plan that are not in the given set of names.
   Returns the number of facts deleted."
  [conn plan-id keep-names]
  (if (seq keep-names)
    (let [result (db/execute!
                  conn
                  {:delete-from :facts
                   :where [:and [:= :plan_id plan-id] [:not-in :name keep-names]]})]
      (get (first result) :next.jdbc/update-count 0))
    (delete-by-plan conn plan-id)))

;; Malli function schemas - register at end to avoid reload issues
(try
  (m/=> create [:=> [:cat :any :int :string [:maybe :string] [:maybe :string]] Fact])
  (m/=> get-by-id [:=> [:cat :any :int] [:maybe Fact]])
  (m/=> get-by-plan [:=> [:cat :any :int] [:sequential Fact]])
  (m/=> get-by-plan-and-name [:=> [:cat :any :int :string] [:maybe Fact]])
  (m/=> get-all [:=> [:cat :any] [:sequential Fact]])
  (m/=> update [:=> [:cat :any :int FactUpdate] [:maybe Fact]])
  (m/=> upsert [:=> [:cat :any :int Fact] Fact])
  (m/=> delete [:=> [:cat :any :int] :boolean])
  (m/=> delete-by-plan [:=> [:cat :any :int] :int])
  (m/=> delete-orphans [:=> [:cat :any :int [:sequential :string]] :int])
  (m/=> search [:=> [:cat :any :string] [:sequential Fact]])
  (catch Exception _ nil))
