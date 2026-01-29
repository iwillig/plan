(ns plan.models.task
  "Task entity model with Malli schemas"
  (:refer-clojure :exclude [update])
  (:require
   [malli.core :as m]
   [plan.db :as db]))

(set! *warn-on-reflection* true)

;; Malli schema for a Task entity
(def Task
  [:map
   [:id [:maybe :int]]
   [:plan_id :int]
   [:parent_id [:maybe :int]]
   [:description :string]
   [:content [:maybe :string]]
   [:completed :boolean]
   [:created_at [:maybe :string]]
   [:updated_at [:maybe :string]]])

;; Schema for task creation
(def TaskCreate
  [:map
   [:plan_id :int]
   [:description :string]
   [:content [:maybe :string]]
   [:parent_id [:maybe :int]]])

;; Schema for task updates
(def TaskUpdate
  [:map
   [:description {:optional true} :string]
   [:content {:optional true} [:maybe :string]]
   [:completed {:optional true} :boolean]
   [:plan_id {:optional true} :int]
   [:parent_id {:optional true} [:maybe :int]]])

(defn create
  "Create a new task for a plan.
   Returns the created task with generated id and timestamps."
  [conn plan-id description content parent-id]
  (let [result (db/execute-one!
                conn
                {:insert-into :tasks
                 :columns [:plan_id :description :content :parent_id :completed]
                 :values [[plan-id description content parent-id false]]
                 :returning [:*]})]
    ;; SQLite stores booleans as integers (0/1), convert back to boolean
    (when result
      (clojure.core/update result :completed #(if (number? %) (not= 0 %) %)))))

(defn get-by-id
  "Fetch a task by its id. Returns nil if not found."
  [conn id]
  (db/execute-one!
   conn
   {:select [:*]
    :from [:tasks]
    :where [:= :id id]}))

(defn get-by-plan
  "Fetch all tasks for a plan, ordered by created_at descending, then id descending."
  [conn plan-id]
  (db/execute!
   conn
   {:select [:*]
    :from [:tasks]
    :where [:= :plan_id plan-id]
    :order-by [[:created_at :desc] [:id :desc]]}))

(defn get-children
  "Fetch all child tasks for a parent task, ordered by created_at descending, then id descending."
  [conn parent-id]
  (db/execute!
   conn
   {:select [:*]
    :from [:tasks]
    :where [:= :parent_id parent-id]
    :order-by [[:created_at :desc] [:id :desc]]}))

(defn get-all
  "Fetch all tasks, ordered by created_at descending, then id descending."
  [conn]
  (db/execute!
   conn
   {:select [:*]
    :from [:tasks]
    :order-by [[:created_at :desc] [:id :desc]]}))

(defn update
  "Update a task's fields. Returns the updated task or nil if not found."
  [conn id updates]
  (let [set-clause (cond-> {}
                     (contains? updates :description) (assoc :description (:description updates))
                     (contains? updates :content) (assoc :content (:content updates))
                     (contains? updates :completed) (assoc :completed (if (:completed updates) 1 0))
                     (contains? updates :plan_id) (assoc :plan_id (:plan_id updates))
                     (contains? updates :parent_id) (assoc :parent_id (:parent_id updates)))]
    (when (seq set-clause)
      (let [result (db/execute-one!
                    conn
                    {:update :tasks
                     :set set-clause
                     :where [:= :id id]
                     :returning [:*]})]
        ;; SQLite stores booleans as integers (0/1), convert back to boolean
        (when result
          (clojure.core/update result :completed #(if (number? %) (not= 0 %) %)))))))

(defn delete
  "Delete a task by id. Returns true if a task was deleted."
  [conn id]
  (let [result (db/execute-one!
                conn
                {:delete-from :tasks
                 :where [:= :id id]})]
    ;; next.jdbc returns #:next.jdbc{:update-count N} for DELETE
    (> (get result :next.jdbc/update-count 0) 0)))

(defn delete-by-plan
  "Delete all tasks for a plan. Returns the number of tasks deleted."
  [conn plan-id]
  (let [result (db/execute!
                conn
                {:delete-from :tasks
                 :where [:= :plan_id plan-id]})]
    ;; next.jdbc returns [#:next.jdbc{:update-count N}] for DML operations
    (get (first result) :next.jdbc/update-count 0)))

(defn mark-completed
  "Mark a task as completed or not completed."
  [conn id completed]
  (update conn id {:completed completed}))

(defn search
  "Search for tasks matching the query using full-text search."
  [conn query]
  (db/search-tasks conn query))

;; Malli function schemas - register at end to avoid reload issues
(try
  (m/=> create [:=> [:cat :any :int :string [:maybe :string] [:maybe :int]] Task])
  (m/=> get-by-id [:=> [:cat :any :int] [:maybe Task]])
  (m/=> get-by-plan [:=> [:cat :any :int] [:sequential Task]])
  (m/=> get-children [:=> [:cat :any :int] [:sequential Task]])
  (m/=> get-all [:=> [:cat :any] [:sequential Task]])
  (m/=> update [:=> [:cat :any :int TaskUpdate] [:maybe Task]])
  (m/=> delete [:=> [:cat :any :int] :boolean])
  (m/=> delete-by-plan [:=> [:cat :any :int] :int])
  (m/=> mark-completed [:=> [:cat :any :int :boolean] [:maybe Task]])
  (m/=> search [:=> [:cat :any :string] [:sequential Task]])
  (catch Exception _ nil))
