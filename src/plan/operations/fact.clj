(ns plan.operations.fact
  "Fact operations using failjure for monadic error handling.
   This layer is shared between the CLI and MCP server.

   All operations return either:
   - A success value (the data)
   - A failjure.core/Failure with error details

   Operations take a database connection as the first argument."
  (:require
   [clojure.string :as str]
   [failjure.core :as f]
   [plan.models.fact :as fact]
   [plan.models.plan :as plan]
   [plan.schemas :as schemas]))

(set! *warn-on-reflection* true)

;; -----------------------------------------------------------------------------
;; Validation Helpers
;; -----------------------------------------------------------------------------

(defn- validate-schema
  "Validate params against a Malli schema.
   Returns params if valid, or a Failure with humanized errors."
  [schema params]
  (let [result (schemas/validate schema params)]
    (if (schemas/validation-failed? result)
      (f/fail "Validation failed: %s"
              (str/join "; "
                        (for [[k v] (:errors result)]
                          (str (name k) " " (first v)))))
      params)))

(defn- validate-plan-exists
  "Validate that a plan exists.
   Returns the plan if found, or a Failure if not."
  [conn plan-id]
  (if-let [p (plan/get-by-id conn plan-id)]
    p
    (f/fail "Plan not found: %s" plan-id)))

(defn- validate-fact-exists
  "Validate that a fact exists.
   Returns the fact if found, or a Failure if not."
  [conn fact-id]
  (if-let [the-fact (fact/get-by-id conn fact-id)]
    the-fact
    (f/fail "Fact not found: %s" fact-id)))

;; -----------------------------------------------------------------------------
;; Operations
;; -----------------------------------------------------------------------------

(defn list-facts
  "List all facts for a plan.
   
   Args:
     conn    - Database connection
     plan-id - ID of the plan
   
   Returns:
     Vector of fact maps on success, or Failure if plan not found."
  [conn plan-id]
  (f/attempt-all [_plan (validate-plan-exists conn plan-id)]
                 (fact/get-by-plan conn plan-id)))

(defn get-fact
  "Get a single fact by ID.
   
   Args:
     conn    - Database connection
     fact-id - ID of the fact
   
   Returns:
     Fact map on success, or Failure if not found."
  [conn fact-id]
  (validate-fact-exists conn fact-id))

(defn create-fact
  "Create a new fact.

   Args:
     conn    - Database connection
     params  - Map with :plan_id, :name, :content, and optional :description

   Returns:
     Created fact map on success, or Failure on validation error."
  [conn {:keys [plan_id name description content] :as params}]
  (f/attempt-all [_params (validate-schema schemas/FactCreate params)
                  _plan (validate-plan-exists conn plan_id)]
                 (fact/create conn plan_id name description content)))

(defn update-fact
  "Update an existing fact.
   
   Args:
     conn    - Database connection
     fact-id - ID of the fact to update
     updates - Map with optional :name, :description, :content
   
   Returns:
     Updated fact map on success, or Failure if not found or no updates."
  [conn fact-id updates]
  (let [valid-updates (select-keys updates [:name :description :content])]
    (if (empty? valid-updates)
      (f/fail "No fields to update provided")
      (f/attempt-all [_fact (validate-fact-exists conn fact-id)
                      result (or (fact/update conn fact-id valid-updates)
                                 (f/fail "Failed to update fact: %s" fact-id))]
                     result))))

(defn delete-fact
  "Delete a fact by ID.
   
   Args:
     conn    - Database connection
     fact-id - ID of the fact to delete
   
   Returns:
     Map with :deleted true and :fact_id on success, or Failure if not found."
  [conn fact-id]
  (f/attempt-all [_fact (validate-fact-exists conn fact-id)]
                 (do
                   (fact/delete conn fact-id)
                   {:deleted true :fact_id fact-id})))

(defn search-facts
  "Search facts using full-text search.
   
   Args:
     conn  - Database connection
     query - Search query string
   
   Returns:
     Vector of matching fact maps."
  [conn query]
  (if (str/blank? query)
    (f/fail "Search query cannot be empty")
    (fact/search conn query)))
