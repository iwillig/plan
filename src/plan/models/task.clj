(ns plan.models.task
  "Task entity model with Malli schemas"
  (:refer-clojure :exclude [update])
  (:require
   [hugsql.adapter.next-jdbc :as next-adapter]
   [hugsql.core :as hugsql]
   [malli.core :as m]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]
   [plan.db]))

(set! *warn-on-reflection* true)

;; Configure HugSQL to use next.jdbc adapter
(hugsql/set-adapter! (next-adapter/hugsql-adapter-next-jdbc))

;; Load SQL queries from external file
(hugsql/def-db-fns "sql/tasks.sql")

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

(defn- convert-booleans [tasks]
  (map convert-boolean tasks))

;; -----------------------------------------------------------------------------
;; Basic CRUD Functions

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
      (task-create conn {:plan-id plan-id
                         :name name
                         :description description
                         :content content
                         :parent-id parent-id
                         :completed false
                         :status status
                         :priority priority
                         :acceptance-criteria acceptance-criteria})))))

(defn get-by-id
  "Fetch a task by its id. Returns nil if not found."
  [conn id]
  (convert-boolean (task-get-by-id conn {:id id})))

(defn get-by-plan
  "Fetch all tasks for a plan, ordered by created_at descending, then id descending."
  [conn plan-id]
  (convert-booleans (task-get-by-plan conn {:plan-id plan-id})))

(defn get-by-plan-and-name
  "Fetch a task by plan_id and name. Returns nil if not found."
  [conn plan-id name]
  (convert-boolean
   (task-get-by-plan-and-name conn {:plan-id plan-id :name name})))

(defn get-children
  "Fetch all child tasks for a parent task, ordered by created_at descending, then id descending."
  [conn parent-id]
  (convert-booleans (task-get-children conn {:parent-id parent-id})))

(defn get-all
  "Fetch all tasks, ordered by created_at descending, then id descending."
  [conn]
  (convert-booleans (task-get-all conn {})))

(defn update
  "Update a task's fields. Returns the updated task or nil if not found.
   When status changes, also updates status_changed_at timestamp."
  [conn id {:keys [name description content plan_id parent_id completed status priority acceptance_criteria] :as updates}]
  (when (seq updates)
    (when-let [existing (get-by-id conn id)]
      (let [set-clause {:name (or name (:name existing))
                        :description (or description (:description existing))
                        :content (or content (:content existing))
                        :plan-id (or plan_id (:plan_id existing))
                        :parent-id (or parent_id (:parent_id existing))
                        :completed (if (nil? completed)
                                     (:completed existing)
                                     (if completed 1 0))
                        :status (or status (:status existing))
                        :priority (or priority (:priority existing))
                        :acceptance-criteria (or acceptance_criteria (:acceptance_criteria existing))}]
        (convert-boolean
         (task-update conn (assoc set-clause :id id)))))))

(defn delete
  "Delete a task by id. Returns true if a task was deleted."
  [conn id]
  (let [result (task-delete conn {:id id})]
    (> result 0)))

(defn delete-by-plan
  "Delete all tasks for a plan. Returns the number of tasks deleted."
  [conn plan-id]
  (task-delete-by-plan conn {:plan-id plan-id}))

(defn mark-completed
  "Mark a task as completed or not completed."
  [conn id completed]
  (update conn id {:completed completed}))

(defn search
  "Search for tasks matching the query using full-text search."
  [conn query]
  ;; Keep using db/search-tasks until db.clj is migrated
  (plan.db/search-tasks conn query))

;; -----------------------------------------------------------------------------
;; Upsert and Orphan Management

(defn upsert
  "Insert or update a task by plan_id and name. Returns the task.
   If a task with this plan_id and name exists, updates it. Otherwise creates new."
  [conn plan-id {:keys [name description content completed parent_id status priority acceptance_criteria]}]
  (let [status-val (or status "pending")
        priority-val (or priority 100)]
    (task-upsert conn {:plan-id plan-id
                       :name name
                       :description description
                       :content content
                       :completed (if completed 1 0)
                       :parent-id parent_id
                       :status status-val
                       :priority priority-val
                       :acceptance-criteria acceptance_criteria})
    ;; Fetch and return the task
    (get-by-plan-and-name conn plan-id name)))

(defn delete-orphans
  "Delete tasks for a plan that are not in the given set of names.
   Returns the number of tasks deleted."
  [conn plan-id keep-names]
  (if (seq keep-names)
    (let [keep-set (set keep-names)
          all-tasks (task-delete-orphans-query conn {:plan-id plan-id})
          orphans (remove #(keep-set (:name %)) all-tasks)
          orphan-ids (map :id orphans)]
      (doseq [id orphan-ids]
        (task-delete conn {:id id}))
      (count orphan-ids))
    (delete-by-plan conn plan-id)))

;; -----------------------------------------------------------------------------
;; Status Transition Functions

(defn set-status
  "Set a task's status. Returns the updated task.
   Also updates status_changed_at timestamp."
  [conn id status]
  (when (valid-statuses status)
    (convert-boolean (task-set-status conn {:id id :status status}))))

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
      (task-add-dependency conn {:task-id task-id
                                 :blocks-task-id blocks-task-id
                                 :dependency-type dep-type})
      true
      (catch Exception _ false))))

(defn remove-dependency
  "Remove a dependency between tasks."
  [conn task-id blocks-task-id]
  (let [result (task-remove-dependency conn {:task-id task-id
                                             :blocks-task-id blocks-task-id})]
    (> result 0)))

(defn get-blocking-tasks
  "Get tasks that are blocking the given task (tasks that must complete first)."
  [conn task-id]
  (convert-booleans (task-get-blocking conn {:task-id task-id})))

(defn get-blocked-tasks
  "Get tasks that are blocked by the given task (tasks waiting for this one)."
  [conn task-id]
  (convert-booleans (task-get-blocked conn {:task-id task-id})))

(defn get-dependencies-for-plan
  "Get all dependencies for tasks in a plan.
   Returns list of {:task_id :blocks_task_id :dependency_type}."
  [conn plan-id]
  (task-get-dependencies-for-plan conn {:plan-id plan-id}))

(defn delete-dependencies-for-task
  "Delete all dependencies involving a task (both directions)."
  [conn task-id]
  (task-delete-dependencies-for-task conn {:task-id task-id}))

;; -----------------------------------------------------------------------------
;; Ready Task Functions

(defn get-ready-tasks
  "Get tasks that are ready to work on for a plan.
   Ready tasks are:
   - Status is 'pending'
   - Not blocked by any incomplete task
   Returns tasks ordered by priority (lowest first), then by id."
  [conn plan-id]
  (convert-booleans (task-get-ready conn {:plan-id plan-id})))

(defn get-next-task
  "Get the highest priority ready task for a plan.
   Returns nil if no tasks are ready."
  [conn plan-id]
  (first (get-ready-tasks conn plan-id)))

;; -----------------------------------------------------------------------------
;; Cycle Detection
;; NOTE: This uses application-level traversal to avoid complex recursive SQL

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
          :else (let [blocked (map :blocks_task_id (jdbc/execute!
                                                    conn
                                                    [(str "SELECT blocks_task_id FROM task_dependencies "
                                                          "WHERE task_id = ?")
                                                     current]
                                                    {:builder-fn rs/as-unqualified-maps}))]
                  (recur (conj visited current)
                         (concat remaining blocked))))))))

;; Malli function schemas - register at end to avoid reload issues
(try
  (m/=> create [:=> [:cat :any :int :string [:maybe :string] [:maybe :string] [:maybe :int]] Task])
  (m/=> create [:=> [:cat :any :int :string [:maybe :string] [:maybe :string] [:maybe :int :map]] Task])
  (m/=> get-by-id [:=> [:cat :any :int] [:maybe Task]])
  (m/=> get-by-plan [:=> [:cat :any :int] [:sequential Task]])
  (m/=> get-all [:=> [:cat :any] [:sequential Task]])
  (m/=> update [:=> [:cat :any :int TaskUpdate] [:maybe Task]])
  (m/=> upsert [:=> [:cat :any :int Task] Task])
  (m/=> delete [:=> [:cat :any :int] :boolean])
  (m/=> mark-completed [:=> [:cat :any :int :boolean] [:maybe Task]])
  (catch Exception _ nil))
