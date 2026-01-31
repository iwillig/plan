(ns plan.operations.task
  "Task operations using failjure for monadic error handling.
   This layer is shared between the CLI and MCP server."
  (:require
   [clojure.string :as str]
   [failjure.core :as f]
   [plan.models.plan :as plan]
   [plan.models.task :as task]))

(set! *warn-on-reflection* true)

;; -----------------------------------------------------------------------------
;; Validation Helpers
;; -----------------------------------------------------------------------------

(defn- validate-required
  "Validate that required keys are present in params.
   Returns params if valid, or a Failure if not."
  [params required-keys]
  (let [missing (remove #(contains? params %) required-keys)]
    (if (seq missing)
      (f/fail "Missing required parameters: %s" (str/join ", " (map name missing)))
      params)))

(defn- validate-plan-exists
  "Validate that a plan exists.
   Returns the plan if found, or a Failure if not."
  [conn plan-id]
  (if-let [p (plan/get-by-id conn plan-id)]
    p
    (f/fail "Plan not found: %s" plan-id)))

(defn- validate-task-exists
  "Validate that a task exists.
   Returns the task if found, or a Failure if not."
  [conn task-id]
  (if-let [t (task/get-by-id conn task-id)]
    t
    (f/fail "Task not found: %s" task-id)))

;; -----------------------------------------------------------------------------
;; Operations
;; -----------------------------------------------------------------------------

(defn list-tasks
  "List all tasks for a plan.
   
   Returns:
     Vector of task maps on success, or Failure if plan not found."
  [conn plan-id]
  (f/attempt-all [_plan (validate-plan-exists conn plan-id)]
                 (task/get-by-plan conn plan-id)))

(defn get-task
  "Get a single task by ID.
   
   Returns:
     Task map on success, or Failure if not found."
  [conn task-id]
  (validate-task-exists conn task-id))

(defn show-task
  "Get a task with its dependencies.
   
   Returns:
     Map with :task, :blocked-by, :blocks on success, or Failure if not found."
  [conn task-id]
  (f/attempt-all [t (validate-task-exists conn task-id)]
                 (let [blocking (task/get-blocking-tasks conn task-id)
                       blocked (task/get-blocked-tasks conn task-id)]
                   {:task t
                    :blocked-by (mapv #(select-keys % [:id :name :status]) blocking)
                    :blocks (mapv #(select-keys % [:id :name :status]) blocked)})))

(defn create-task
  "Create a new task.
   
   Args:
     conn   - Database connection
     params - Map with :plan_id, :name (required), :description, :content, :parent_id
   
   Returns:
     Created task map on success, or Failure on validation error."
  [conn {:keys [plan_id name description content parent_id] :as params}]
  (f/attempt-all [_params (validate-required params [:plan_id :name])
                  _plan (validate-plan-exists conn plan_id)]
                 (task/create conn plan_id name description content parent_id)))

(defn update-task
  "Update an existing task.
   
   Args:
     conn    - Database connection
     task-id - ID of the task to update
     updates - Map with optional :name, :description, :content, :completed, :plan_id, :parent_id
   
   Returns:
     Updated task map on success, or Failure if not found or no updates."
  [conn task-id updates]
  (let [valid-updates (select-keys updates [:name :description :content :completed :plan_id :parent_id])]
    (if (empty? valid-updates)
      (f/fail "No fields to update provided")
      (f/attempt-all [_task (validate-task-exists conn task-id)]
                     (if-let [result (task/update conn task-id valid-updates)]
                       result
                       (f/fail "Failed to update task: %s" task-id))))))

(defn delete-task
  "Delete a task by ID.
   
   Returns:
     Map with :deleted true on success, or Failure if not found."
  [conn task-id]
  (f/attempt-all [_task (validate-task-exists conn task-id)]
                 (do
                   (task/delete conn task-id)
                   {:deleted true :task-id task-id})))

(defn start-task
  "Start a task (transition from pending to in_progress).
   
   Returns:
     Updated task map on success, or Failure if not found or invalid transition."
  [conn task-id]
  (f/attempt-all [_task (validate-task-exists conn task-id)]
                 (if-let [result (task/start-task conn task-id)]
                   result
                   (f/fail "Failed to start task: %s" task-id))))

(defn complete-task
  "Complete a task.
   
   Returns:
     Updated task map on success, or Failure if not found or invalid transition."
  [conn task-id]
  (f/attempt-all [_task (validate-task-exists conn task-id)]
                 (if-let [result (task/complete-task conn task-id)]
                   result
                   (f/fail "Failed to complete task: %s" task-id))))

(defn fail-task
  "Mark a task as failed.
   
   Returns:
     Updated task map on success, or Failure if not found or invalid transition."
  [conn task-id]
  (f/attempt-all [_task (validate-task-exists conn task-id)]
                 (if-let [result (task/fail-task conn task-id)]
                   result
                   (f/fail "Failed to mark task as failed: %s" task-id))))

(defn add-dependency
  "Add a dependency: blocked-task-id depends on blocking-task-id.
   
   Returns:
     Success map on success, or Failure if would create cycle or tasks not found."
  [conn blocking-task-id blocked-task-id]
  (f/attempt-all [_blocking (validate-task-exists conn blocking-task-id)
                  _blocked (validate-task-exists conn blocked-task-id)]
                 (if (task/has-cycle? conn blocking-task-id blocked-task-id)
                   (f/fail "Adding this dependency would create a cycle")
                   (if (task/add-dependency conn blocking-task-id blocked-task-id)
                     {:success true
                      :blocking-task blocking-task-id
                      :blocked-task blocked-task-id}
                     (f/fail "Failed to add dependency")))))

(defn get-ready-tasks
  "List tasks that are ready to work on (pending with no blockers).
   
   Returns:
     Vector of ready task maps, or Failure if plan not found."
  [conn plan-id]
  (f/attempt-all [_plan (validate-plan-exists conn plan-id)]
                 (task/get-ready-tasks conn plan-id)))

(defn get-next-task
  "Get the next task to work on (highest priority ready task).
   
   Returns:
     Task map or nil if no tasks ready, or Failure if plan not found."
  [conn plan-id]
  (f/attempt-all [_plan (validate-plan-exists conn plan-id)]
                 (task/get-next-task conn plan-id)))

(defn search-tasks
  "Search tasks using full-text search.
   
   Returns:
     Vector of matching task maps, or Failure if query is empty."
  [conn query]
  (if (str/blank? query)
    (f/fail "Search query cannot be empty")
    (task/search conn query)))
