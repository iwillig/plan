(ns plan.models.task
  "Task entity model with Malli schemas"
  (:refer-clojure :exclude [update])
  (:require
   [malli.core :as m]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]
   [plan.db :as db]))

(set! *warn-on-reflection* true)

;; Valid task statuses
(def valid-statuses #{"pending" "in_progress" "completed" "failed" "blocked" "skipped"})

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
   [:status [:maybe :string]]
   [:priority [:maybe :int]]
   [:acceptance_criteria [:maybe :string]]
   [:status_changed_at [:maybe :string]]
   [:created_at [:maybe :string]]
   [:updated_at [:maybe :string]]])

;; Schema for task creation
(def TaskCreate
  [:map
   [:plan_id :int]
   [:name :string]
   [:description [:maybe :string]]
   [:content [:maybe :string]]
   [:parent_id [:maybe :int]]
   [:status {:optional true} :string]
   [:priority {:optional true} :int]
   [:acceptance_criteria {:optional true} :string]])

;; Schema for task updates
(def TaskUpdate
  [:map
   [:description {:optional true} [:maybe :string]]
   [:content {:optional true} [:maybe :string]]
   [:completed {:optional true} :boolean]
   [:name {:optional true} :string]
   [:plan_id {:optional true} :int]
   [:parent_id {:optional true} [:maybe :int]]
   [:status {:optional true} :string]
   [:priority {:optional true} :int]
   [:acceptance_criteria {:optional true} :string]])

(defn- convert-boolean [task]
  (when task
    (-> task
        (clojure.core/update :completed #(if (number? %) (not= 0 %) %))
        ;; Ensure status defaults to 'pending' if nil
        (clojure.core/update :status #(or % "pending")))))

(defn create
  "Create a new task for a plan.
   Returns the created task with generated id and timestamps.
   Optional opts map can include :status, :priority, :acceptance_criteria."
  ([conn plan-id name description content parent-id]
   (create conn plan-id name description content parent-id {}))
  ([conn plan-id name description content parent-id opts]
   (let [status (get opts :status "pending")
         priority (get opts :priority 100)
         acceptance-criteria (get opts :acceptance_criteria)]
     (convert-boolean
      (db/execute-one!
       conn
       {:insert-into :tasks
        :columns [:plan_id :name :description :content :parent_id :completed :status :priority :acceptance_criteria]
        :values [[plan-id name description content parent-id false status priority acceptance-criteria]]
        :returning [:*]})))))

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
  "Update a task's fields. Returns the updated task or nil if not found.
   When status changes, also updates status_changed_at timestamp."
  [conn id updates]
  (let [base-clause (select-keys updates [:name :description :content :plan_id :parent_id
                                          :priority :acceptance_criteria])
        set-clause (cond-> base-clause
                     (contains? updates :completed) (assoc :completed (if (:completed updates) 1 0))
                     (contains? updates :status) (assoc :status (:status updates)
                                                        :status_changed_at [:raw "CURRENT_TIMESTAMP"]))]
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
  [conn plan-id {:keys [name description content completed parent_id status priority acceptance_criteria]}]
  (let [status-val (or status "pending")
        priority-val (or priority 100)]
    (jdbc/execute! conn
                   [(str "INSERT INTO tasks (plan_id, name, description, content, completed, parent_id, status, priority, acceptance_criteria) "
                         "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(plan_id, name) DO UPDATE SET "
                         "description = excluded.description, content = excluded.content, "
                         "completed = excluded.completed, parent_id = excluded.parent_id, "
                         "status = excluded.status, priority = excluded.priority, "
                         "acceptance_criteria = excluded.acceptance_criteria")
                    plan-id name description content (if completed 1 0) parent_id status-val priority-val acceptance_criteria])
    (convert-boolean
     (db/execute-one! conn {:select [:*] :from [:tasks]
                            :where [:and [:= :plan_id plan-id] [:= :name name]]}))))

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

;; -----------------------------------------------------------------------------
;; Status Transition Functions

(defn set-status
  "Set a task's status. Returns the updated task.
   Also updates status_changed_at timestamp."
  [conn id status]
  (when (valid-statuses status)
    (update conn id {:status status})))

(defn start-task
  "Transition task from pending to in_progress."
  [conn id]
  (set-status conn id "in_progress"))

(defn complete-task
  "Transition task to completed status."
  [conn id]
  (let [result (set-status conn id "completed")]
    (when result
      (update conn id {:completed true}))))

(defn fail-task
  "Transition task to failed status."
  [conn id]
  (set-status conn id "failed"))

(defn block-task
  "Transition task to blocked status."
  [conn id]
  (set-status conn id "blocked"))

(defn skip-task
  "Transition task to skipped status."
  [conn id]
  (set-status conn id "skipped"))

;; -----------------------------------------------------------------------------
;; Dependency Functions

(defn add-dependency
  "Add a dependency: task-id blocks blocks-task-id.
   Returns true if the dependency was added."
  [conn task-id blocks-task-id & [dependency-type]]
  (let [dep-type (or dependency-type "blocks")]
    (try
      (jdbc/execute! conn
                     [(str "INSERT INTO task_dependencies (task_id, blocks_task_id, dependency_type) "
                           "VALUES (?, ?, ?) ON CONFLICT(task_id, blocks_task_id) DO NOTHING")
                      task-id blocks-task-id dep-type])
      true
      (catch Exception _ false))))

(defn remove-dependency
  "Remove a dependency between tasks."
  [conn task-id blocks-task-id]
  (let [result (db/execute-one!
                conn
                {:delete-from :task_dependencies
                 :where [:and [:= :task_id task-id] [:= :blocks_task_id blocks-task-id]]})]
    (> (get result :next.jdbc/update-count 0) 0)))

(defn get-blocking-tasks
  "Get tasks that are blocking the given task (tasks that must complete first)."
  [conn task-id]
  (map convert-boolean
       (db/execute!
        conn
        {:select [:t.*]
         :from [[:tasks :t]]
         :join [[:task_dependencies :d] [:= :t.id :d.task_id]]
         :where [:= :d.blocks_task_id task-id]})))

(defn get-blocked-tasks
  "Get tasks that are blocked by the given task (tasks waiting for this one)."
  [conn task-id]
  (map convert-boolean
       (db/execute!
        conn
        {:select [:t.*]
         :from [[:tasks :t]]
         :join [[:task_dependencies :d] [:= :t.id :d.blocks_task_id]]
         :where [:= :d.task_id task-id]})))

(defn get-dependencies-for-plan
  "Get all dependencies for tasks in a plan.
   Returns list of {:task_id :blocks_task_id :dependency_type}."
  [conn plan-id]
  (db/execute!
   conn
   {:select [:d.*]
    :from [[:task_dependencies :d]]
    :join [[:tasks :t] [:= :t.id :d.task_id]]
    :where [:= :t.plan_id plan-id]}))

(defn delete-dependencies-for-task
  "Delete all dependencies involving a task (both directions)."
  [conn task-id]
  (db/execute! conn
               {:delete-from :task_dependencies
                :where [:or [:= :task_id task-id] [:= :blocks_task_id task-id]]}))

;; -----------------------------------------------------------------------------
;; Ready Task Functions

(defn get-ready-tasks
  "Get tasks that are ready to work on for a plan.
   Ready tasks are:
   - Status is 'pending'
   - Not blocked by any incomplete task
   Returns tasks ordered by priority (lowest first), then by id."
  [conn plan-id]
  (map convert-boolean
       (jdbc/execute!
        conn
        [(str "SELECT t.* FROM tasks t "
              "WHERE t.plan_id = ? "
              "AND t.status = 'pending' "
              "AND NOT EXISTS ("
              "  SELECT 1 FROM task_dependencies d "
              "  JOIN tasks blocker ON blocker.id = d.task_id "
              "  WHERE d.blocks_task_id = t.id "
              "  AND blocker.status NOT IN ('completed', 'skipped')"
              ") "
              "ORDER BY t.priority ASC, t.id ASC")
         plan-id]
        {:builder-fn next.jdbc.result-set/as-unqualified-maps})))

(defn get-next-task
  "Get the highest priority ready task for a plan.
   Returns nil if no tasks are ready."
  [conn plan-id]
  (first (get-ready-tasks conn plan-id)))

(defn has-cycle?
  "Check if adding a dependency from task-id to blocks-task-id would create a cycle.
   Returns true if a cycle would be created."
  [conn task-id blocks-task-id]
  ;; Check if blocks-task-id can reach task-id through existing dependencies
  (loop [visited #{}
         queue [blocks-task-id]]
    (if (empty? queue)
      false
      (let [current (first queue)
            remaining (rest queue)]
        (cond
          (= current task-id) true
          (visited current) (recur visited remaining)
          :else
          (let [blocked-by (get-blocked-tasks conn current)
                new-ids (remove visited (map :id blocked-by))]
            (recur (conj visited current)
                   (concat remaining new-ids))))))))

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
