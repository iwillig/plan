(ns plan.operations.trace
  "Trace operations using failjure for monadic error handling.
   Traces implement the ReAct pattern for task reasoning.
   This layer is shared between the CLI and MCP server.

   All operations return either:
   - A success value (the data)
   - A failjure.core/Failure with error details

   Trace types: thought, action, observation, reflection"
  (:require
   [clojure.string :as str]
   [failjure.core :as f]
   [plan.models.plan :as plan]
   [plan.models.task :as task]
   [plan.models.trace :as trace]))

(set! *warn-on-reflection* true)

;; -----------------------------------------------------------------------------
;; Constants
;; -----------------------------------------------------------------------------

(def ^:private valid-trace-types
  "Valid trace types for the ReAct pattern."
  #{"thought" "action" "observation" "reflection"})

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

(defn- validate-trace-type
  "Validate that trace-type is valid.
   Returns trace-type if valid, or a Failure if not."
  [trace-type]
  (if (contains? valid-trace-types trace-type)
    trace-type
    (f/fail "Invalid trace type: %s. Must be one of: %s"
            trace-type
            (str/join ", " (sort valid-trace-types)))))

;; -----------------------------------------------------------------------------
;; Operations
;; -----------------------------------------------------------------------------

(defn add-trace
  "Add a trace entry for task reasoning.

   Args:
     conn   - Database connection
     params - Map with :task-id, :trace-type, :content (required)
              Optional: :metadata

   Trace types:
     - thought: reasoning about what to do
     - action: the action taken
     - observation: what was observed
     - reflection: lessons learned

   Returns:
     Created trace map on success, or Failure on validation error."
  [conn {:keys [task-id trace-type content metadata] :as params}]
  (f/attempt-all [_params (validate-required params [:task-id :trace-type :content])
                  _type (validate-trace-type trace-type)
                  the-task (validate-task-exists conn task-id)
                  plan-id (:plan_id the-task)
                  seq-num (trace/get-next-sequence conn plan-id)]
                 (trace/create conn {:plan-id plan-id
                                     :task-id task-id
                                     :trace-type trace-type
                                     :sequence-num seq-num
                                     :content content
                                     :metadata metadata})))

(defn get-task-traces
  "Get trace history for a task.

   Args:
     conn    - Database connection
     task-id - ID of the task

   Returns:
     Vector of trace maps ordered by sequence, or Failure if task not found."
  [conn task-id]
  (f/attempt-all [_task (validate-task-exists conn task-id)]
                 (trace/get-by-task conn task-id)))

(defn get-plan-traces
  "Get all traces for a plan.

   Args:
     conn    - Database connection
     plan-id - ID of the plan

   Returns:
     Vector of trace maps ordered by sequence, or Failure if plan not found."
  [conn plan-id]
  (f/attempt-all [_plan (validate-plan-exists conn plan-id)]
                 (trace/get-by-plan conn plan-id)))

(defn delete-task-traces
  "Delete all traces for a task.

   Args:
     conn    - Database connection
     task-id - ID of the task

   Returns:
     Map with :deleted true on success, or Failure if task not found."
  [conn task-id]
  (f/attempt-all [_task (validate-task-exists conn task-id)]
                 (do
                   (trace/delete-by-task conn task-id)
                   {:deleted true :task-id task-id})))

(defn delete-plan-traces
  "Delete all traces for a plan.

   Args:
     conn    - Database connection
     plan-id - ID of the plan

   Returns:
     Map with :deleted true on success, or Failure if plan not found."
  [conn plan-id]
  (f/attempt-all [_plan (validate-plan-exists conn plan-id)]
                 (do
                   (trace/delete-by-plan conn plan-id)
                   {:deleted true :plan-id plan-id})))
