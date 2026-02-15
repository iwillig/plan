(ns plan.mcp-server
  "MCP server implementation for the planning tool.
   Uses a capabilities-based RPC design with two tools:
   - get_capabilities: self-documenting API reference
   - execute: RPC dispatcher for all operations

   Operations are delegated to plan.operations.* namespaces which use
   failjure for monadic error handling."
  (:require
   [clojure-mcp.core :as mcp-core]
   [clojure.data.json :as json]
   [failjure.core :as f]
   [plan.config :as config]
   [plan.db :as db]
   [plan.operations.fact :as fact-ops]
   [plan.operations.lesson :as lesson-ops]
   [plan.operations.plan :as plan-ops]
   [plan.operations.task :as task-ops]
   [plan.operations.trace :as trace-ops]))

(set! *warn-on-reflection* true)

;; -----------------------------------------------------------------------------
;; Capabilities Document
;; -----------------------------------------------------------------------------

(def ^:private capabilities
  "Self-documenting API specification that the LLM can query to discover
   available operations and their parameters."
  {:version "2.0.0"
   :description "Plan Management System - Complete API for Plans, Tasks, Facts, Lessons, and Traces"
   :note "All operations require a valid database. Plans contain tasks and facts. Tasks have status workflow (pending->in_progress->completed|failed)."
   :operations
   {:plan-list
    {:description "List all plans"
     :parameters {}
     :returns {:type "array"
               :description "Array of plan objects"
               :items {:type "object"
                       :properties {:id {:type "integer"}
                                    :name {:type "string"}
                                    :description {:type "string"}
                                    :status {:type "string"}
                                    :created_at {:type "string"}
                                    :updated_at {:type "string"}}}}}

    :plan-get
    {:description "Get a single plan by ID"
     :parameters {:plan_id {:type "integer"
                            :description "ID of the plan to retrieve"
                            :required true
                            :example 1}}
     :returns {:type "object"
               :description "Plan object or null if not found"}}

    :plan-show
    {:description "Get a plan with its tasks and facts"
     :parameters {:plan_id {:type "integer"
                            :description "ID of the plan"
                            :required true
                            :example 1}}
     :returns {:type "object"
               :description "Object with :plan, :tasks, :facts"}}

    :plan-create
    {:description "Create a new plan"
     :parameters {:name {:type "string"
                         :description "Plan name (required)"
                         :required true
                         :example "Q1 Roadmap"}
                  :description {:type "string"
                                :description "Brief description"
                                :required false}
                  :content {:type "string"
                            :description "Full content/details"
                            :required false}}
     :returns {:type "object"
               :description "Created plan with generated ID"}}

    :plan-update
    {:description "Update an existing plan"
     :parameters {:plan_id {:type "integer"
                            :description "ID of plan to update"
                            :required true
                            :example 1}
                  :name {:type "string" :required false}
                  :description {:type "string" :required false}
                  :content {:type "string" :required false}
                  :completed {:type "boolean" :required false}}
     :note "At least one field must be provided"
     :returns {:type "object" :description "Updated plan"}}

    :plan-delete
    {:description "Delete a plan and all its tasks and facts"
     :parameters {:plan_id {:type "integer"
                            :description "ID of plan to delete"
                            :required true
                            :example 1}}
     :returns {:type "object"
               :description "Deletion summary with counts"}}

    :plan-search
    {:description "Search plans using full-text search"
     :parameters {:query {:type "string"
                          :description "Search query"
                          :required true
                          :example "roadmap"}}
     :returns {:type "array" :description "Matching plans"}}

    :task-list
    {:description "List all tasks for a plan"
     :parameters {:plan_id {:type "integer"
                            :description "ID of the plan"
                            :required true
                            :example 1}}
     :returns {:type "array" :description "Array of task objects"}}

    :task-get
    {:description "Get a single task by ID"
     :parameters {:task_id {:type "integer"
                            :description "ID of the task"
                            :required true
                            :example 1}}
     :returns {:type "object" :description "Task object"}}

    :task-show
    {:description "Get a task with its dependencies"
     :parameters {:task_id {:type "integer"
                            :description "ID of the task"
                            :required true
                            :example 1}}
     :returns {:type "object"
               :description "Object with :task, :blocked-by, :blocks"}}

    :task-create
    {:description "Create a new task in a plan"
     :parameters {:plan_id {:type "integer"
                            :description "Plan ID (required)"
                            :required true
                            :example 1}
                  :name {:type "string"
                         :description "Task name (required)"
                         :required true
                         :example "Design API"}
                  :description {:type "string" :required false}
                  :content {:type "string" :required false}
                  :parent_id {:type "integer"
                              :description "Parent task ID for subtasks"
                              :required false}}
     :returns {:type "object" :description "Created task"}}

    :task-update
    {:description "Update an existing task"
     :parameters {:task_id {:type "integer"
                            :description "ID of task to update"
                            :required true
                            :example 1}
                  :name {:type "string" :required false}
                  :description {:type "string" :required false}
                  :content {:type "string" :required false}
                  :completed {:type "boolean" :required false}
                  :plan_id {:type "integer" :required false}
                  :parent_id {:type "integer" :required false}}
     :note "At least one field must be provided"
     :returns {:type "object" :description "Updated task"}}

    :task-delete
    {:description "Delete a task"
     :parameters {:task_id {:type "integer"
                            :description "ID of task to delete"
                            :required true
                            :example 1}}
     :returns {:type "object" :description "Deletion confirmation"}}

    :task-start
    {:description "Start a task (pending -> in_progress)"
     :parameters {:task_id {:type "integer"
                            :description "ID of task to start"
                            :required true
                            :example 1}}
     :returns {:type "object" :description "Updated task"}}

    :task-complete
    {:description "Complete a task"
     :parameters {:task_id {:type "integer"
                            :description "ID of task to complete"
                            :required true
                            :example 1}}
     :returns {:type "object" :description "Updated task"}}

    :task-fail
    {:description "Mark a task as failed"
     :parameters {:task_id {:type "integer"
                            :description "ID of task to mark failed"
                            :required true
                            :example 1}}
     :returns {:type "object" :description "Updated task"}}

    :task-add-dependency
    {:description "Add a dependency between tasks (blocked depends on blocking)"
     :parameters {:blocking_task_id {:type "integer"
                                     :description "Task that must complete first"
                                     :required true
                                     :example 1}
                  :blocked_task_id {:type "integer"
                                    :description "Task that is blocked"
                                    :required true
                                    :example 2}}
     :returns {:type "object" :description "Dependency confirmation"}}

    :task-ready
    {:description "List tasks ready to work on (pending, no blockers)"
     :parameters {:plan_id {:type "integer"
                            :description "Plan ID"
                            :required true
                            :example 1}}
     :returns {:type "array" :description "Ready task objects"}}

    :task-next
    {:description "Get the next highest priority ready task"
     :parameters {:plan_id {:type "integer"
                            :description "Plan ID"
                            :required true
                            :example 1}}
     :returns {:type "object" :description "Next task or null"}}

    :task-search
    {:description "Search tasks using full-text search"
     :parameters {:query {:type "string"
                          :description "Search query"
                          :required true
                          :example "design"}}
     :returns {:type "array" :description "Matching tasks"}}

    :fact-list
    {:description "List all facts for a specific plan"
     :parameters {:plan_id {:type "integer"
                            :description "ID of the plan"
                            :required true
                            :example 1}}
     :returns {:type "array" :description "Array of fact objects"}}

    :fact-get
    {:description "Get a single fact by its ID"
     :parameters {:fact_id {:type "integer"
                            :description "ID of the fact"
                            :required true
                            :example 1}}
     :returns {:type "object" :description "Fact object or null"}}

    :fact-create
    {:description "Create a new fact in a plan"
     :parameters {:plan_id {:type "integer"
                            :description "Plan ID (required)"
                            :required true
                            :example 1}
                  :name {:type "string"
                         :description "Unique name for the fact"
                         :required true
                         :example "API Endpoint"}
                  :description {:type "string" :required false}
                  :content {:type "string"
                            :description "Full content"
                            :required true
                            :example "https://api.example.com/v1"}}
     :returns {:type "object" :description "Created fact"}}

    :fact-update
    {:description "Update an existing fact"
     :parameters {:fact_id {:type "integer"
                            :description "ID of fact to update"
                            :required true
                            :example 1}
                  :name {:type "string" :required false}
                  :description {:type "string" :required false}
                  :content {:type "string" :required false}}
     :note "At least one field must be provided"
     :returns {:type "object" :description "Updated fact"}}

    :fact-delete
    {:description "Delete a fact by ID"
     :parameters {:fact_id {:type "integer"
                            :description "ID of fact to delete"
                            :required true
                            :example 1}}
     :returns {:type "object" :description "Deletion confirmation"}}

    :fact-search
    {:description "Search facts using full-text search"
     :parameters {:query {:type "string"
                          :description "Search query"
                          :required true
                          :example "database"}}
     :returns {:type "array" :description "Matching facts"}}

    :lesson-create
    {:description "Create a lesson from experience (Reflexion pattern)"
     :parameters {:lesson_type {:type "string"
                                :description "Type: success_pattern, failure_pattern, constraint, technique"
                                :required true
                                :example "success_pattern"}
                  :lesson_content {:type "string"
                                   :description "The lesson learned (required)"
                                   :required true}
                  :plan_id {:type "integer"
                            :description "Optional associated plan"
                            :required false}
                  :task_id {:type "integer"
                            :description "Optional associated task"
                            :required false}
                  :trigger_condition {:type "string"
                                      :description "When to apply this lesson"
                                      :required false}
                  :confidence {:type "number"
                               :description "Confidence 0.0-1.0 (default 0.5)"
                               :required false}}
     :returns {:type "object" :description "Created lesson"}}

    :lesson-get
    {:description "Get a lesson by ID"
     :parameters {:lesson_id {:type "integer"
                              :description "ID of the lesson"
                              :required true
                              :example 1}}
     :returns {:type "object" :description "Lesson object"}}

    :lesson-list-all
    {:description "List all lessons with optional filters"
     :parameters {:min_confidence {:type "number" :required false}
                  :max_confidence {:type "number" :required false}
                  :lesson_type {:type "string" :required false}}
     :returns {:type "array" :description "Array of lessons"}}

    :lesson-list-plan
    {:description "List lessons for a plan"
     :parameters {:plan_id {:type "integer" :required true :example 1}
                  :min_confidence {:type "number" :required false}
                  :max_confidence {:type "number" :required false}
                  :lesson_type {:type "string" :required false}}
     :returns {:type "array" :description "Array of lessons"}}

    :lesson-list-task
    {:description "List lessons for a task"
     :parameters {:task_id {:type "integer" :required true :example 1}}
     :returns {:type "array" :description "Array of lessons"}}

    :lesson-search
    {:description "Search lessons using full-text search"
     :parameters {:query {:type "string" :required true :example "api design"}}
     :returns {:type "array" :description "Matching lessons"}}

    :lesson-validate
    {:description "Increase confidence in a lesson (it was useful)"
     :parameters {:lesson_id {:type "integer" :required true :example 1}}
     :returns {:type "object" :description "Updated lesson"}}

    :lesson-invalidate
    {:description "Decrease confidence in a lesson (it was wrong)"
     :parameters {:lesson_id {:type "integer" :required true :example 1}}
     :returns {:type "object" :description "Updated lesson"}}

    :lesson-delete
    {:description "Delete a lesson by ID"
     :parameters {:lesson_id {:type "integer" :required true :example 1}}
     :returns {:type "object" :description "Deletion confirmation"}}

    :trace-add
    {:description "Add a trace entry for task reasoning (ReAct pattern)"
     :parameters {:task_id {:type "integer"
                            :description "Task ID (required)"
                            :required true
                            :example 1}
                  :trace_type {:type "string"
                               :description "Type: thought, action, observation, reflection"
                               :required true
                               :example "thought"}
                  :content {:type "string"
                            :description "Trace content (required)"
                            :required true}
                  :metadata {:type "object"
                             :description "Optional JSON metadata"
                             :required false}}
     :returns {:type "object" :description "Created trace"}}

    :trace-get-task
    {:description "Get all traces for a task"
     :parameters {:task_id {:type "integer" :required true :example 1}}
     :returns {:type "array" :description "Array of trace entries"}}

    :trace-get-plan
    {:description "Get all traces for a plan"
     :parameters {:plan_id {:type "integer" :required true :example 1}}
     :returns {:type "array" :description "Array of trace entries"}}}

   :examples
   [{:description "List all plans"
     :request {:operation "plan-list"}}
    {:description "Create a plan"
     :request {:operation "plan-create" :name "Q1 Roadmap" :description "Quarter goals"}}
    {:description "Create a task"
     :request {:operation "task-create" :plan_id 1 :name "Design API"}}
    {:description "Start a task"
     :request {:operation "task-start" :task_id 1}}
    {:description "Complete a task"
     :request {:operation "task-complete" :task_id 1}}
    {:description "Add a fact"
     :request {:operation "fact-create" :plan_id 1 :name "Database URL" :content "postgres://localhost"}}
    {:description "Create a lesson"
     :request {:operation "lesson-create" :lesson_type "success_pattern" :lesson_content "Always validate inputs"}}
    {:description "Add a trace"
     :request {:operation "trace-add" :task_id 1 :trace_type "thought" :content "Need to refactor this"}}]})

;; -----------------------------------------------------------------------------
;; Result Formatting
;; -----------------------------------------------------------------------------

(defn- format-success
  "Format a successful operation result."
  [operation data]
  {:status :success
   :operation operation
   :data data})

(defn- format-success-with-count
  "Format a successful list operation result."
  [operation data]
  {:status :success
   :operation operation
   :count (count data)
   :data (vec data)})

(defn- format-error
  "Format a failure result from failjure."
  [operation failure]
  {:status :error
   :operation operation
   :message (f/message failure)})

;; -----------------------------------------------------------------------------
;; Plan Operation Handlers
;; -----------------------------------------------------------------------------

(defn- handle-plan-list
  "List all plans"
  [conn _params]
  (let [result (plan-ops/list-plans conn)]
    (if (f/failed? result)
      (format-error :plan-list result)
      (format-success-with-count :plan-list result))))

(defn- handle-plan-get
  "Get a single plan by ID"
  [conn params]
  (let [plan-id (:plan_id params)]
    (if (nil? plan-id)
      {:status :error
       :operation :plan-get
       :message "Missing required parameter: plan_id"
       :error-type :validation-error}
      (let [result (plan-ops/get-plan conn plan-id)]
        (if (f/failed? result)
          (format-error :plan-get result)
          (format-success :plan-get result))))))

(defn- handle-plan-show
  "Get a plan with tasks and facts"
  [conn params]
  (let [plan-id (:plan_id params)]
    (if (nil? plan-id)
      {:status :error
       :operation :plan-show
       :message "Missing required parameter: plan_id"
       :error-type :validation-error}
      (let [result (plan-ops/show-plan conn plan-id)]
        (if (f/failed? result)
          (format-error :plan-show result)
          (format-success :plan-show result))))))

(defn- handle-plan-create
  "Create a new plan"
  [conn params]
  (let [result (plan-ops/create-plan conn params)]
    (if (f/failed? result)
      (format-error :plan-create result)
      (format-success :plan-create result))))

(defn- handle-plan-update
  "Update an existing plan"
  [conn params]
  (let [plan-id (:plan_id params)]
    (if (nil? plan-id)
      {:status :error
       :operation :plan-update
       :message "Missing required parameter: plan_id"
       :error-type :validation-error}
      (let [updates (select-keys params [:name :description :content :completed])
            result (plan-ops/update-plan conn plan-id updates)]
        (if (f/failed? result)
          (format-error :plan-update result)
          (format-success :plan-update result))))))

(defn- handle-plan-delete
  "Delete a plan"
  [conn params]
  (let [plan-id (:plan_id params)]
    (if (nil? plan-id)
      {:status :error
       :operation :plan-delete
       :message "Missing required parameter: plan_id"
       :error-type :validation-error}
      (let [result (plan-ops/delete-plan conn plan-id)]
        (if (f/failed? result)
          (format-error :plan-delete result)
          (format-success :plan-delete result))))))

(defn- handle-plan-search
  "Search plans"
  [conn params]
  (let [query (:query params)]
    (if (nil? query)
      {:status :error
       :operation :plan-search
       :message "Missing required parameter: query"
       :error-type :validation-error}
      (let [result (plan-ops/search-plans conn query)]
        (if (f/failed? result)
          (format-error :plan-search result)
          (format-success-with-count :plan-search result))))))

;; -----------------------------------------------------------------------------
;; Task Operation Handlers
;; -----------------------------------------------------------------------------

(defn- handle-task-list
  "List all tasks for a plan"
  [conn params]
  (let [plan-id (:plan_id params)]
    (if (nil? plan-id)
      {:status :error
       :operation :task-list
       :message "Missing required parameter: plan_id"
       :error-type :validation-error}
      (let [result (task-ops/list-tasks conn plan-id)]
        (if (f/failed? result)
          (format-error :task-list result)
          (format-success-with-count :task-list result))))))

(defn- handle-task-get
  "Get a single task by ID"
  [conn params]
  (let [task-id (:task_id params)]
    (if (nil? task-id)
      {:status :error
       :operation :task-get
       :message "Missing required parameter: task_id"
       :error-type :validation-error}
      (let [result (task-ops/get-task conn task-id)]
        (if (f/failed? result)
          (format-error :task-get result)
          (format-success :task-get result))))))

(defn- handle-task-show
  "Get a task with dependencies"
  [conn params]
  (let [task-id (:task_id params)]
    (if (nil? task-id)
      {:status :error
       :operation :task-show
       :message "Missing required parameter: task_id"
       :error-type :validation-error}
      (let [result (task-ops/show-task conn task-id)]
        (if (f/failed? result)
          (format-error :task-show result)
          (format-success :task-show result))))))

(defn- handle-task-create
  "Create a new task"
  [conn params]
  (let [result (task-ops/create-task conn params)]
    (if (f/failed? result)
      (format-error :task-create result)
      (format-success :task-create result))))

(defn- handle-task-update
  "Update an existing task"
  [conn params]
  (let [task-id (:task_id params)]
    (if (nil? task-id)
      {:status :error
       :operation :task-update
       :message "Missing required parameter: task_id"
       :error-type :validation-error}
      (let [updates (select-keys params [:name :description :content :completed :plan_id :parent_id])
            result (task-ops/update-task conn task-id updates)]
        (if (f/failed? result)
          (format-error :task-update result)
          (format-success :task-update result))))))

(defn- handle-task-delete
  "Delete a task"
  [conn params]
  (let [task-id (:task_id params)]
    (if (nil? task-id)
      {:status :error
       :operation :task-delete
       :message "Missing required parameter: task_id"
       :error-type :validation-error}
      (let [result (task-ops/delete-task conn task-id)]
        (if (f/failed? result)
          (format-error :task-delete result)
          (format-success :task-delete result))))))

(defn- handle-task-start
  "Start a task"
  [conn params]
  (let [task-id (:task_id params)]
    (if (nil? task-id)
      {:status :error
       :operation :task-start
       :message "Missing required parameter: task_id"
       :error-type :validation-error}
      (let [result (task-ops/start-task conn task-id)]
        (if (f/failed? result)
          (format-error :task-start result)
          (format-success :task-start result))))))

(defn- handle-task-complete
  "Complete a task"
  [conn params]
  (let [task-id (:task_id params)]
    (if (nil? task-id)
      {:status :error
       :operation :task-complete
       :message "Missing required parameter: task_id"
       :error-type :validation-error}
      (let [result (task-ops/complete-task conn task-id)]
        (if (f/failed? result)
          (format-error :task-complete result)
          (format-success :task-complete result))))))

(defn- handle-task-fail
  "Fail a task"
  [conn params]
  (let [task-id (:task_id params)]
    (if (nil? task-id)
      {:status :error
       :operation :task-fail
       :message "Missing required parameter: task_id"
       :error-type :validation-error}
      (let [result (task-ops/fail-task conn task-id)]
        (if (f/failed? result)
          (format-error :task-fail result)
          (format-success :task-fail result))))))

(defn- handle-task-add-dependency
  "Add dependency between tasks"
  [conn params]
  (let [blocking-id (:blocking_task_id params)
        blocked-id (:blocked_task_id params)]
    (cond
      (nil? blocking-id)
      {:status :error
       :operation :task-add-dependency
       :message "Missing required parameter: blocking_task_id"
       :error-type :validation-error}
      (nil? blocked-id)
      {:status :error
       :operation :task-add-dependency
       :message "Missing required parameter: blocked_task_id"
       :error-type :validation-error}
      :else
      (let [result (task-ops/add-dependency conn blocking-id blocked-id)]
        (if (f/failed? result)
          (format-error :task-add-dependency result)
          (format-success :task-add-dependency result))))))

(defn- handle-task-ready
  "Get ready tasks for a plan"
  [conn params]
  (let [plan-id (:plan_id params)]
    (if (nil? plan-id)
      {:status :error
       :operation :task-ready
       :message "Missing required parameter: plan_id"
       :error-type :validation-error}
      (let [result (task-ops/get-ready-tasks conn plan-id)]
        (if (f/failed? result)
          (format-error :task-ready result)
          (format-success-with-count :task-ready result))))))

(defn- handle-task-next
  "Get next ready task"
  [conn params]
  (let [plan-id (:plan_id params)]
    (if (nil? plan-id)
      {:status :error
       :operation :task-next
       :message "Missing required parameter: plan_id"
       :error-type :validation-error}
      (let [result (task-ops/get-next-task conn plan-id)]
        (if (f/failed? result)
          (format-error :task-next result)
          (format-success :task-next (or result {})))))))

(defn- handle-task-search
  "Search tasks"
  [conn params]
  (let [query (:query params)]
    (if (nil? query)
      {:status :error
       :operation :task-search
       :message "Missing required parameter: query"
       :error-type :validation-error}
      (let [result (task-ops/search-tasks conn query)]
        (if (f/failed? result)
          (format-error :task-search result)
          (format-success-with-count :task-search result))))))

;; -----------------------------------------------------------------------------
;; Fact Operation Handlers
;; -----------------------------------------------------------------------------

(defn- handle-fact-list
  "List all facts for a plan"
  [conn params]
  (let [plan-id (:plan_id params)]
    (if (nil? plan-id)
      {:status :error
       :operation :fact-list
       :message "Missing required parameter: plan_id"
       :error-type :validation-error}
      (let [result (fact-ops/list-facts conn plan-id)]
        (if (f/failed? result)
          (format-error :fact-list result)
          (format-success-with-count :fact-list result))))))

(defn- handle-fact-get
  "Get a single fact by ID"
  [conn params]
  (let [fact-id (:fact_id params)]
    (if (nil? fact-id)
      {:status :error
       :operation :fact-get
       :message "Missing required parameter: fact_id"
       :error-type :validation-error}
      (let [result (fact-ops/get-fact conn fact-id)]
        (if (f/failed? result)
          (format-error :fact-get result)
          (format-success :fact-get result))))))

(defn- handle-fact-create
  "Create a new fact"
  [conn params]
  (let [result (fact-ops/create-fact conn params)]
    (if (f/failed? result)
      (format-error :fact-create result)
      (format-success :fact-create result))))

(defn- handle-fact-update
  "Update an existing fact"
  [conn params]
  (let [fact-id (:fact_id params)]
    (if (nil? fact-id)
      {:status :error
       :operation :fact-update
       :message "Missing required parameter: fact_id"
       :error-type :validation-error}
      (let [updates (select-keys params [:name :description :content])
            result (fact-ops/update-fact conn fact-id updates)]
        (if (f/failed? result)
          (format-error :fact-update result)
          (format-success :fact-update result))))))

(defn- handle-fact-delete
  "Delete a fact"
  [conn params]
  (let [fact-id (:fact_id params)]
    (if (nil? fact-id)
      {:status :error
       :operation :fact-delete
       :message "Missing required parameter: fact_id"
       :error-type :validation-error}
      (let [result (fact-ops/delete-fact conn fact-id)]
        (if (f/failed? result)
          (format-error :fact-delete result)
          (format-success :fact-delete result))))))

(defn- handle-fact-search
  "Search facts"
  [conn params]
  (let [query (:query params)]
    (if (nil? query)
      {:status :error
       :operation :fact-search
       :message "Missing required parameter: query"
       :error-type :validation-error}
      (let [result (fact-ops/search-facts conn query)]
        (if (f/failed? result)
          (format-error :fact-search result)
          (format-success-with-count :fact-search result))))))

;; -----------------------------------------------------------------------------
;; Lesson Operation Handlers
;; -----------------------------------------------------------------------------

(defn- handle-lesson-create
  "Create a new lesson"
  [conn params]
  (let [lesson-params {:plan-id (:plan_id params)
                       :task-id (:task_id params)
                       :lesson-type (:lesson_type params)
                       :trigger-condition (:trigger_condition params)
                       :lesson-content (:lesson_content params)
                       :confidence (:confidence params)}
        result (lesson-ops/create-lesson conn lesson-params)]
    (if (f/failed? result)
      (format-error :lesson-create result)
      (format-success :lesson-create result))))

(defn- handle-lesson-get
  "Get a lesson by ID"
  [conn params]
  (let [lesson-id (:lesson_id params)]
    (if (nil? lesson-id)
      {:status :error
       :operation :lesson-get
       :message "Missing required parameter: lesson_id"
       :error-type :validation-error}
      (let [result (lesson-ops/get-lesson conn lesson-id)]
        (if (f/failed? result)
          (format-error :lesson-get result)
          (format-success :lesson-get result))))))

(defn- handle-lesson-list-all
  "List all lessons"
  [conn params]
  (let [filters {:min-confidence (:min_confidence params)
                 :max-confidence (:max_confidence params)
                 :lesson-type (:lesson_type params)}
        result (lesson-ops/list-all-lessons conn filters)]
    (if (f/failed? result)
      (format-error :lesson-list-all result)
      (format-success-with-count :lesson-list-all result))))

(defn- handle-lesson-list-plan
  "List lessons for a plan"
  [conn params]
  (let [plan-id (:plan_id params)]
    (if (nil? plan-id)
      {:status :error
       :operation :lesson-list-plan
       :message "Missing required parameter: plan_id"
       :error-type :validation-error}
      (let [filters {:min-confidence (:min_confidence params)
                     :max-confidence (:max_confidence params)
                     :lesson-type (:lesson_type params)}
            result (lesson-ops/list-plan-lessons conn plan-id filters)]
        (if (f/failed? result)
          (format-error :lesson-list-plan result)
          (format-success-with-count :lesson-list-plan result))))))

(defn- handle-lesson-list-task
  "List lessons for a task"
  [conn params]
  (let [task-id (:task_id params)]
    (if (nil? task-id)
      {:status :error
       :operation :lesson-list-task
       :message "Missing required parameter: task_id"
       :error-type :validation-error}
      (let [result (lesson-ops/list-task-lessons conn task-id)]
        (if (f/failed? result)
          (format-error :lesson-list-task result)
          (format-success-with-count :lesson-list-task result))))))

(defn- handle-lesson-search
  "Search lessons"
  [conn params]
  (let [query (:query params)]
    (if (nil? query)
      {:status :error
       :operation :lesson-search
       :message "Missing required parameter: query"
       :error-type :validation-error}
      (let [result (lesson-ops/search-lessons conn query)]
        (if (f/failed? result)
          (format-error :lesson-search result)
          (format-success-with-count :lesson-search result))))))

(defn- handle-lesson-validate
  "Validate/increase confidence in a lesson"
  [conn params]
  (let [lesson-id (:lesson_id params)]
    (if (nil? lesson-id)
      {:status :error
       :operation :lesson-validate
       :message "Missing required parameter: lesson_id"
       :error-type :validation-error}
      (let [result (lesson-ops/validate-lesson conn lesson-id)]
        (if (f/failed? result)
          (format-error :lesson-validate result)
          (format-success :lesson-validate result))))))

(defn- handle-lesson-invalidate
  "Invalidate/decrease confidence in a lesson"
  [conn params]
  (let [lesson-id (:lesson_id params)]
    (if (nil? lesson-id)
      {:status :error
       :operation :lesson-invalidate
       :message "Missing required parameter: lesson_id"
       :error-type :validation-error}
      (let [result (lesson-ops/invalidate-lesson conn lesson-id)]
        (if (f/failed? result)
          (format-error :lesson-invalidate result)
          (format-success :lesson-invalidate result))))))

(defn- handle-lesson-delete
  "Delete a lesson"
  [conn params]
  (let [lesson-id (:lesson_id params)]
    (if (nil? lesson-id)
      {:status :error
       :operation :lesson-delete
       :message "Missing required parameter: lesson_id"
       :error-type :validation-error}
      (let [result (lesson-ops/delete-lesson conn lesson-id)]
        (if (f/failed? result)
          (format-error :lesson-delete result)
          (format-success :lesson-delete result))))))

;; -----------------------------------------------------------------------------
;; Trace Operation Handlers
;; -----------------------------------------------------------------------------

(defn- handle-trace-add
  "Add a trace entry"
  [conn params]
  (let [trace-params {:task-id (:task_id params)
                      :trace-type (:trace_type params)
                      :content (:content params)
                      :metadata (:metadata params)}
        result (trace-ops/add-trace conn trace-params)]
    (if (f/failed? result)
      (format-error :trace-add result)
      (format-success :trace-add result))))

(defn- handle-trace-get-task
  "Get traces for a task"
  [conn params]
  (let [task-id (:task_id params)]
    (if (nil? task-id)
      {:status :error
       :operation :trace-get-task
       :message "Missing required parameter: task_id"
       :error-type :validation-error}
      (let [result (trace-ops/get-task-traces conn task-id)]
        (if (f/failed? result)
          (format-error :trace-get-task result)
          (format-success-with-count :trace-get-task result))))))

(defn- handle-trace-get-plan
  "Get traces for a plan"
  [conn params]
  (let [plan-id (:plan_id params)]
    (if (nil? plan-id)
      {:status :error
       :operation :trace-get-plan
       :message "Missing required parameter: plan_id"
       :error-type :validation-error}
      (let [result (trace-ops/get-plan-traces conn plan-id)]
        (if (f/failed? result)
          (format-error :trace-get-plan result)
          (format-success-with-count :trace-get-plan result))))))

;; -----------------------------------------------------------------------------
;; RPC Router
;; -----------------------------------------------------------------------------

(defn- execute-operation
  "Route to the appropriate operation handler"
  [conn operation params]
  (case operation
    ;; Plan operations
    "plan-list" (handle-plan-list conn params)
    "plan-get" (handle-plan-get conn params)
    "plan-show" (handle-plan-show conn params)
    "plan-create" (handle-plan-create conn params)
    "plan-update" (handle-plan-update conn params)
    "plan-delete" (handle-plan-delete conn params)
    "plan-search" (handle-plan-search conn params)
    ;; Task operations
    "task-list" (handle-task-list conn params)
    "task-get" (handle-task-get conn params)
    "task-show" (handle-task-show conn params)
    "task-create" (handle-task-create conn params)
    "task-update" (handle-task-update conn params)
    "task-delete" (handle-task-delete conn params)
    "task-start" (handle-task-start conn params)
    "task-complete" (handle-task-complete conn params)
    "task-fail" (handle-task-fail conn params)
    "task-add-dependency" (handle-task-add-dependency conn params)
    "task-ready" (handle-task-ready conn params)
    "task-next" (handle-task-next conn params)
    "task-search" (handle-task-search conn params)
    ;; Fact operations
    "fact-list" (handle-fact-list conn params)
    "fact-get" (handle-fact-get conn params)
    "fact-create" (handle-fact-create conn params)
    "fact-update" (handle-fact-update conn params)
    "fact-delete" (handle-fact-delete conn params)
    "fact-search" (handle-fact-search conn params)
    ;; Lesson operations
    "lesson-create" (handle-lesson-create conn params)
    "lesson-get" (handle-lesson-get conn params)
    "lesson-list-all" (handle-lesson-list-all conn params)
    "lesson-list-plan" (handle-lesson-list-plan conn params)
    "lesson-list-task" (handle-lesson-list-task conn params)
    "lesson-search" (handle-lesson-search conn params)
    "lesson-validate" (handle-lesson-validate conn params)
    "lesson-invalidate" (handle-lesson-invalidate conn params)
    "lesson-delete" (handle-lesson-delete conn params)
    ;; Trace operations
    "trace-add" (handle-trace-add conn params)
    "trace-get-task" (handle-trace-get-task conn params)
    "trace-get-plan" (handle-trace-get-plan conn params)
    ;; Unknown operation
    {:status :error
     :operation operation
     :message (str "Unknown operation: " operation)
     :error-type :unknown-operation}))

;; -----------------------------------------------------------------------------
;; MCP Tool Functions
;; -----------------------------------------------------------------------------

(defn get-capabilities-tool
  "Returns the capabilities document as JSON string"
  [_args _callback]
  (json/write-str capabilities :escape-slash false))

(defn execute-tool
  "Execute an RPC operation
   Expected args format:
   {:request '{\"operation\": \"fact-list\", \"plan_id\": 1}'}

   The request is a JSON string containing the operation and parameters."
  [args callback]
  (let [request-json (get args "request")
        db-path (config/db-path)]
    (try
      (let [request (json/read-str request-json :key-fn keyword)
            operation (:operation request)
            params (dissoc request :operation)]
        (db/with-connection db-path
          (fn [conn]
            (let [result (execute-operation conn operation params)]
              (callback [(json/write-str result :escape-slash false)] false)))))
      (catch Exception e
        (callback [(json/write-str {:status :error
                                    :message (str "JSON parse error: "
                                                  (.getMessage e))}
                                   :escape-slash false)]
                  true)))))

;; -----------------------------------------------------------------------------
;; MCP Server Setup
;; -----------------------------------------------------------------------------

(defn make-tools
  "Create the MCP tools for the planning system"
  [_nrepl-client-atom _working-dir]
  [{:name "get_capabilities"
    :description (str "Get the capabilities document for the planning system. "
                      "This tool returns a complete API specification including "
                      "all available operations, their parameters, and examples. "
                      "Call this first to discover how to use the system.")
    :schema {:type "object"
             :properties {}
             :required []}
    :tool-fn (fn [_exchange _args callback]
               (callback [(get-capabilities-tool _args callback)] false))}

   {:name "execute"
    :description (str "Execute an operation on the planning system. "
                      "Requires a JSON request string with 'operation' and parameters. "
                      "Use get_capabilities first to discover available operations. "
                      "Request format: '{\"operation\": \"plan-list\"}'")
    :schema {:type "object"
             :properties
             {:request {:type "string"
                        :description "JSON string with 'operation' and parameters"}}
             :required ["request"]}
    :tool-fn execute-tool}])

(defn start-mcp-server
  "Start the MCP server for the planning tool

   Options:
   - :port - nREPL port (optional, for project discovery)
   - :project-dir - Project directory (can be used instead of :port)"
  [opts]
  (mcp-core/build-and-start-mcp-server
   opts
   {:make-tools-fn make-tools
    :make-prompts-fn (constantly [])  ;; No prompts for now
    :make-resources-fn (constantly [])}))  ;; No resources for now
