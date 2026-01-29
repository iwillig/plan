(ns plan.models.plan
  "Plan entity model with Malli schemas"
  (:refer-clojure :exclude [update])
  (:require
   [malli.core :as m]
   [plan.db :as db]))

(set! *warn-on-reflection* true)

;; Malli schema for a Plan entity
(def Plan
  [:map
   [:id [:maybe :int]]
   [:name :string]
   [:description [:maybe :string]]
   [:content [:maybe :string]]
   [:completed :boolean]
   [:created_at [:maybe :string]]
   [:updated_at [:maybe :string]]])

;; Schema for plan creation (id/timestamps are optional)
(def PlanCreate
  [:map
   [:name :string]
   [:description [:maybe :string]]
   [:content [:maybe :string]]])

;; Schema for plan updates (all fields optional)
(def PlanUpdate
  [:map
   [:name {:optional true} :string]
   [:description {:optional true} [:maybe :string]]
   [:content {:optional true} [:maybe :string]]
   [:completed {:optional true} :boolean]])

(defn- convert-boolean [plan]
  (when plan
    (clojure.core/update plan :completed #(if (number? %) (not= 0 %) %))))

(defn create
  "Create a new plan with the given name, description and content.
   Returns the created plan with generated id and timestamps."
  [conn name description content]
  (convert-boolean
   (db/execute-one!
    conn
    {:insert-into :plans
     :columns [:name :description :content :completed]
     :values [[name description content false]]
     :returning [:*]})))

(defn get-by-id
  "Fetch a plan by its id. Returns nil if not found."
  [conn id]
  (convert-boolean
   (db/execute-one!
    conn
    {:select [:*]
     :from [:plans]
     :where [:= :id id]})))

(defn get-all
  "Fetch all plans, ordered by created_at descending, then id descending."
  [conn]
  (map convert-boolean
       (db/execute!
        conn
        {:select [:*]
         :from [:plans]
         :order-by [[:created_at :desc] [:id :desc]]})))

(defn update
  "Update a plan's fields. Returns the updated plan or nil if not found."
  [conn id updates]
  (let [set-clause (cond-> (select-keys updates [:name :description :content])
                     (contains? updates :completed) (assoc :completed (if (:completed updates) 1 0)))]
    (when (seq set-clause)
      (convert-boolean
       (db/execute-one!
        conn
        {:update :plans
         :set set-clause
         :where [:= :id id]
         :returning [:*]})))))

(defn delete
  "Delete a plan by id. Returns true if a plan was deleted."
  [conn id]
  (let [result (db/execute-one!
                conn
                {:delete-from :plans
                 :where [:= :id id]})]
    ;; next.jdbc returns #:next.jdbc{:update-count N} for DELETE
    (> (get result :next.jdbc/update-count 0) 0)))

(defn mark-completed
  "Mark a plan as completed or not completed."
  [conn id completed]
  (update conn id {:completed completed}))

(defn search
  "Search for plans matching the query using full-text search."
  [conn query]
  (db/search-plans conn query))

;; Malli function schemas - register at end to avoid reload issues
(try
  (m/=> create [:=> [:cat :any :string [:maybe :string] [:maybe :string]] Plan])
  (m/=> get-by-id [:=> [:cat :any :int] [:maybe Plan]])
  (m/=> get-all [:=> [:cat :any] [:sequential Plan]])
  (m/=> update [:=> [:cat :any :int PlanUpdate] [:maybe Plan]])
  (m/=> delete [:=> [:cat :any :int] :boolean])
  (m/=> mark-completed [:=> [:cat :any :int :boolean] [:maybe Plan]])
  (m/=> search [:=> [:cat :any :string] [:sequential Plan]])
  (catch Exception _ nil))
