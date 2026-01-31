(ns plan.operations.plan
  "Plan operations using failjure for monadic error handling.
   This layer is shared between the CLI and MCP server."
  (:require
   [clojure.string :as str]
   [failjure.core :as f]
   [plan.models.fact :as fact]
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

;; -----------------------------------------------------------------------------
;; Operations
;; -----------------------------------------------------------------------------

(defn list-plans
  "List all plans.
   
   Returns:
     Vector of plan maps."
  [conn]
  (plan/get-all conn))

(defn get-plan
  "Get a single plan by ID.
   
   Returns:
     Plan map on success, or Failure if not found."
  [conn plan-id]
  (validate-plan-exists conn plan-id))

(defn show-plan
  "Get a plan with its tasks and facts.
   
   Returns:
     Map with :plan, :tasks, :facts on success, or Failure if not found."
  [conn plan-id]
  (f/attempt-all [p (validate-plan-exists conn plan-id)]
                 {:plan p
                  :tasks (task/get-by-plan conn plan-id)
                  :facts (fact/get-by-plan conn plan-id)}))

(defn create-plan
  "Create a new plan.
   
   Args:
     conn   - Database connection
     params - Map with :name (required), :description, :content
   
   Returns:
     Created plan map on success, or Failure on validation error."
  [conn {:keys [name description content] :as params}]
  (f/attempt-all [_params (validate-required params [:name])]
                 (plan/create conn name description content)))

(defn update-plan
  "Update an existing plan.
   
   Args:
     conn    - Database connection
     plan-id - ID of the plan to update
     updates - Map with optional :name, :description, :content, :completed
   
   Returns:
     Updated plan map on success, or Failure if not found or no updates."
  [conn plan-id updates]
  (let [valid-updates (select-keys updates [:name :description :content :completed])]
    (if (empty? valid-updates)
      (f/fail "No fields to update provided")
      (f/attempt-all [_plan (validate-plan-exists conn plan-id)]
                     (if-let [result (plan/update conn plan-id valid-updates)]
                       result
                       (f/fail "Failed to update plan: %s" plan-id))))))

(defn delete-plan
  "Delete a plan and all its tasks and facts.
   
   Returns:
     Map with deletion counts on success, or Failure if not found."
  [conn plan-id]
  (f/attempt-all [_plan (validate-plan-exists conn plan-id)]
                 (let [task-count (task/delete-by-plan conn plan-id)
                       fact-count (fact/delete-by-plan conn plan-id)]
                   (plan/delete conn plan-id)
                   {:deleted true
                    :plan-id plan-id
                    :tasks-deleted task-count
                    :facts-deleted fact-count})))

(defn search-plans
  "Search plans using full-text search.
   
   Returns:
     Vector of matching plan maps, or Failure if query is empty."
  [conn query]
  (if (str/blank? query)
    (f/fail "Search query cannot be empty")
    (plan/search conn query)))
