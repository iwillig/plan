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
   [:description :string]
   [:content [:maybe :string]]
   [:completed :boolean]
   [:created_at [:maybe :string]]
   [:updated_at [:maybe :string]]])

;; Schema for plan creation (id/timestamps are optional)
(def PlanCreate
  [:map
   [:description :string]
   [:content [:maybe :string]]])

;; Schema for plan updates (all fields optional)
(def PlanUpdate
  [:map
   [:description {:optional true} :string]
   [:content {:optional true} [:maybe :string]]
   [:completed {:optional true} :boolean]])

(defn create
  "Create a new plan with the given description and content.
   Returns the created plan with generated id and timestamps."
  [conn description content]
  (let [result (db/execute-one!
                conn
                {:insert-into :plans
                 :columns [:description :content :completed]
                 :values [[description content false]]
                 :returning [:*]})]
    ;; SQLite stores booleans as integers (0/1), convert back to boolean
    (when result
      (clojure.core/update result :completed #(if (number? %) (not= 0 %) %)))))

(defn get-by-id
  "Fetch a plan by its id. Returns nil if not found."
  [conn id]
  (db/execute-one!
   conn
   {:select [:*]
    :from [:plans]
    :where [:= :id id]}))

(defn get-all
  "Fetch all plans, ordered by created_at descending, then id descending."
  [conn]
  (db/execute!
   conn
   {:select [:*]
    :from [:plans]
    :order-by [[:created_at :desc] [:id :desc]]}))

(defn update
  "Update a plan's fields. Returns the updated plan or nil if not found."
  [conn id updates]
  (let [set-clause (cond-> {}
                     (contains? updates :description) (assoc :description (:description updates))
                     (contains? updates :content) (assoc :content (:content updates))
                     (contains? updates :completed) (assoc :completed (if (:completed updates) 1 0)))]
    (when (seq set-clause)
      (let [result (db/execute-one!
                    conn
                    {:update :plans
                     :set set-clause
                     :where [:= :id id]
                     :returning [:*]})]
        ;; SQLite stores booleans as integers (0/1), convert back to boolean
        (when result
          (clojure.core/update result :completed #(if (number? %) (not= 0 %) %)))))))

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
  (m/=> create [:=> [:cat :any :string [:maybe :string]] Plan])
  (m/=> get-by-id [:=> [:cat :any :int] [:maybe Plan]])
  (m/=> get-all [:=> [:cat :any] [:sequential Plan]])
  (m/=> update [:=> [:cat :any :int PlanUpdate] [:maybe Plan]])
  (m/=> delete [:=> [:cat :any :int] :boolean])
  (m/=> mark-completed [:=> [:cat :any :int :boolean] [:maybe Plan]])
  (m/=> search [:=> [:cat :any :string] [:sequential Plan]])
  (catch Exception _ nil))
