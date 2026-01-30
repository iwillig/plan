(ns plan.models.task
  "Task entity model with Malli schemas"
  (:refer-clojure :exclude [update])
  (:require
   [malli.core :as m]
   [next.jdbc :as jdbc]
   [plan.db :as db]))

(set! *warn-on-reflection* true)

;; Malli schema for a Task entity
(def Task
  [:map
   [:id [:maybe :int]]
   [:plan_id :int]
   [:name :string]
   [:parent_id [:maybe :int]]
   [:description [:maybe :string]]
   [:content [:maybe :string]]
   [:completed :boolean]
   [:created_at [:maybe :string]]
   [:updated_at [:maybe :string]]])

;; Schema for task creation
(def TaskCreate
  [:map
   [:plan_id :int]
   [:name :string]
   [:description [:maybe :string]]
   [:content [:maybe :string]]
   [:parent_id [:maybe :int]]])

;; Schema for task updates
(def TaskUpdate
  [:map
   [:description {:optional true} [:maybe :string]]
   [:content {:optional true} [:maybe :string]]
   [:completed {:optional true} :boolean]
   [:name {:optional true} :string]
   [:plan_id {:optional true} :int]
   [:parent_id {:optional true} [:maybe :int]]])

(defn- convert-boolean [task]
  (when task
    (clojure.core/update task :completed #(if (number? %) (not= 0 %) %))))

(defn create
  "Create a new task for a plan.
   Returns the created task with generated id and timestamps."
  [conn plan-id name description content parent-id]
  (convert-boolean
   (db/execute-one!
    conn
    {:insert-into :tasks
     :columns [:plan_id :name :description :content :parent_id :completed]
     :values [[plan-id name description content parent-id false]]
     :returning [:*]})))

(defn get-by-id
  "Fetch a task by its id. Returns nil if not found."
  [conn id]
  (convert-boolean
   (db/execute-one!
    conn
    {:select [:*]
     :from [:tasks]
     :where [:= :id id]})))

(defn get-by-plan
  "Fetch all tasks for a plan, ordered by created_at descending, then id descending."
  [conn plan-id]
  (map convert-boolean
       (db/execute!
        conn
        {:select [:*]
         :from [:tasks]
         :where [:= :plan_id plan-id]
         :order-by [[:created_at :desc] [:id :desc]]})))

(defn get-children
  "Fetch all child tasks for a parent task, ordered by created_at descending, then id descending."
  [conn parent-id]
  (map convert-boolean
       (db/execute!
        conn
        {:select [:*]
         :from [:tasks]
         :where [:= :parent_id parent-id]
         :order-by [[:created_at :desc] [:id :desc]]})))

(defn get-all
  "Fetch all tasks, ordered by created_at descending, then id descending."
  [conn]
  (map convert-boolean
       (db/execute!
        conn
        {:select [:*]
         :from [:tasks]
         :order-by [[:created_at :desc] [:id :desc]]})))

(defn update
  "Update a task's fields. Returns the updated task or nil if not found."
  [conn id updates]
  (let [set-clause (cond-> (select-keys updates [:name :description :content :plan_id :parent_id])
                     (contains? updates :completed) (assoc :completed (if (:completed updates) 1 0)))]
    (when (seq set-clause)
      (convert-boolean
       (db/execute-one!
        conn
        {:update :tasks
         :set set-clause
         :where [:= :id id]
         :returning [:*]})))))

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

(defn get-by-plan-and-name
  "Fetch a task by plan_id and name. Returns nil if not found."
  [conn plan-id name]
  (convert-boolean
   (db/execute-one!
    conn
    {:select [:*]
     :from [:tasks]
     :where [:and [:= :plan_id plan-id] [:= :name name]]})))

(defn upsert
  "Insert or update a task by plan_id and name. Returns the task.
   If a task with this plan_id and name exists, updates it. Otherwise creates new."
  [conn plan-id {:keys [name description content completed parent_id]}]
  (jdbc/execute! conn
                 [(str "INSERT INTO tasks (plan_id, name, description, content, completed, parent_id) "
                       "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(plan_id, name) DO UPDATE SET "
                       "description = excluded.description, content = excluded.content, "
                       "completed = excluded.completed, parent_id = excluded.parent_id")
                  plan-id name description content (if completed 1 0) parent_id])
  (convert-boolean
   (db/execute-one! conn {:select [:*] :from [:tasks]
                          :where [:and [:= :plan_id plan-id] [:= :name name]]})))

(defn delete-orphans
  "Delete tasks for a plan that are not in the given set of names.
   Returns the number of tasks deleted."
  [conn plan-id keep-names]
  (if (seq keep-names)
    (let [result (db/execute!
                  conn
                  {:delete-from :tasks
                   :where [:and [:= :plan_id plan-id] [:not-in :name keep-names]]})]
      (get (first result) :next.jdbc/update-count 0))
    (delete-by-plan conn plan-id)))

;; Malli function schemas - register at end to avoid reload issues
(try
  (m/=> create [:=> [:cat :any :int :string [:maybe :string] [:maybe :string] [:maybe :int]] Task])
  (m/=> get-by-id [:=> [:cat :any :int] [:maybe Task]])
  (m/=> get-by-plan [:=> [:cat :any :int] [:sequential Task]])
  (m/=> get-by-plan-and-name [:=> [:cat :any :int :string] [:maybe Task]])
  (m/=> get-children [:=> [:cat :any :int] [:sequential Task]])
  (m/=> get-all [:=> [:cat :any] [:sequential Task]])
  (m/=> update [:=> [:cat :any :int TaskUpdate] [:maybe Task]])
  (m/=> upsert [:=> [:cat :any :int Task] Task])
  (m/=> delete [:=> [:cat :any :int] :boolean])
  (m/=> delete-by-plan [:=> [:cat :any :int] :int])
  (m/=> delete-orphans [:=> [:cat :any :int [:sequential :string]] :int])
  (m/=> mark-completed [:=> [:cat :any :int :boolean] [:maybe Task]])
  (m/=> search [:=> [:cat :any :string] [:sequential Task]])
  (catch Exception _ nil))
