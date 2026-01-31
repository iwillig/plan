(ns plan.schemas
  "Centralized Malli schemas for the plan application.

   This namespace defines:
   - Entity schemas (Plan, Task, Fact, etc.)
   - Input/output schemas for operations
   - Compiled validators for performance
   - Human-readable error formatting

   Usage:
     (require '[plan.schemas :as schemas])

     ;; Validate data
     (schemas/valid-plan? {:name \"test\" ...})

     ;; Get errors
     (schemas/explain-plan {:name \"\" ...})
     ;; => {:name [\"should be at least 1 character\"]}"
  (:require
   [malli.core :as m]
   [malli.error :as me]))

(set! *warn-on-reflection* true)

;; -----------------------------------------------------------------------------
;; Custom Schema Types
;; -----------------------------------------------------------------------------

(def NonBlankString
  "A non-empty string with at least 1 character."
  [:string {:min 1}])

(def TaskStatus
  "Valid task status values."
  [:enum "pending" "in_progress" "completed" "failed" "blocked" "skipped"])

(def TraceType
  "Valid trace types for the ReAct pattern."
  [:enum "thought" "action" "observation" "reflection"])

(def LessonType
  "Valid lesson types for the Reflexion pattern."
  [:enum "success_pattern" "failure_pattern" "constraint" "technique"])

(def Confidence
  "Confidence value between 0.0 and 1.0."
  [:double {:min 0.0 :max 1.0}])

;; -----------------------------------------------------------------------------
;; Plan Schemas
;; -----------------------------------------------------------------------------

(def Plan
  "Schema for a Plan entity as returned from the database."
  [:map
   [:id :int]
   [:name NonBlankString]
   [:description {:optional true} [:maybe :string]]
   [:content {:optional true} [:maybe :string]]
   [:completed :boolean]
   [:created_at :string]
   [:updated_at :string]])

(def PlanCreate
  "Schema for creating a new plan."
  [:map
   [:name NonBlankString]
   [:description {:optional true} [:maybe :string]]
   [:content {:optional true} [:maybe :string]]])

(def PlanUpdate
  "Schema for updating a plan. At least one field must be provided."
  [:map
   [:name {:optional true} NonBlankString]
   [:description {:optional true} [:maybe :string]]
   [:content {:optional true} [:maybe :string]]
   [:completed {:optional true} :boolean]])

;; -----------------------------------------------------------------------------
;; Task Schemas
;; -----------------------------------------------------------------------------

(def Task
  "Schema for a Task entity as returned from the database."
  [:map
   [:id :int]
   [:plan_id :int]
   [:name NonBlankString]
   [:parent_id {:optional true} [:maybe :int]]
   [:description {:optional true} [:maybe :string]]
   [:content {:optional true} [:maybe :string]]
   [:completed :boolean]
   [:status TaskStatus]
   [:priority :int]
   [:acceptance_criteria {:optional true} [:maybe :string]]
   [:status_changed_at {:optional true} [:maybe :string]]
   [:created_at :string]
   [:updated_at :string]])

(def TaskCreate
  "Schema for creating a new task."
  [:map
   [:plan_id :int]
   [:name NonBlankString]
   [:description {:optional true} [:maybe :string]]
   [:content {:optional true} [:maybe :string]]
   [:parent_id {:optional true} [:maybe :int]]
   [:status {:optional true} TaskStatus]
   [:priority {:optional true} :int]
   [:acceptance_criteria {:optional true} [:maybe :string]]])

(def TaskUpdate
  "Schema for updating a task. At least one field must be provided."
  [:map
   [:name {:optional true} NonBlankString]
   [:description {:optional true} [:maybe :string]]
   [:content {:optional true} [:maybe :string]]
   [:plan_id {:optional true} :int]
   [:parent_id {:optional true} [:maybe :int]]
   [:completed {:optional true} :boolean]
   [:status {:optional true} TaskStatus]
   [:priority {:optional true} :int]
   [:acceptance_criteria {:optional true} [:maybe :string]]])

;; -----------------------------------------------------------------------------
;; Fact Schemas
;; -----------------------------------------------------------------------------

(def Fact
  "Schema for a Fact entity as returned from the database."
  [:map
   [:id :int]
   [:plan_id :int]
   [:name NonBlankString]
   [:description {:optional true} [:maybe :string]]
   [:content {:optional true} [:maybe :string]]
   [:created_at :string]
   [:updated_at :string]])

(def FactCreate
  "Schema for creating a new fact."
  [:map
   [:plan_id :int]
   [:name NonBlankString]
   [:description {:optional true} [:maybe :string]]
   [:content NonBlankString]])

(def FactUpdate
  "Schema for updating a fact. At least one field must be provided."
  [:map
   [:name {:optional true} NonBlankString]
   [:description {:optional true} [:maybe :string]]
   [:content {:optional true} [:maybe :string]]])

;; -----------------------------------------------------------------------------
;; Trace Schemas
;; -----------------------------------------------------------------------------

(def Trace
  "Schema for a Trace entity as returned from the database."
  [:map
   [:id :int]
   [:plan_id :int]
   [:task_id {:optional true} [:maybe :int]]
   [:trace_type TraceType]
   [:sequence_num :int]
   [:content NonBlankString]
   [:metadata {:optional true} [:maybe :string]]
   [:created_at :string]])

(def TraceCreate
  "Schema for creating a new trace."
  [:map
   [:task-id :int]
   [:trace-type TraceType]
   [:content NonBlankString]
   [:metadata {:optional true} [:maybe :string]]])

;; -----------------------------------------------------------------------------
;; Lesson Schemas
;; -----------------------------------------------------------------------------

(def Lesson
  "Schema for a Lesson entity as returned from the database."
  [:map
   [:id :int]
   [:plan_id {:optional true} [:maybe :int]]
   [:task_id {:optional true} [:maybe :int]]
   [:lesson_type LessonType]
   [:trigger_condition {:optional true} [:maybe :string]]
   [:lesson_content NonBlankString]
   [:confidence Confidence]
   [:times_validated :int]
   [:created_at :string]])

(def LessonCreate
  "Schema for creating a new lesson."
  [:map
   [:plan-id {:optional true} [:maybe :int]]
   [:task-id {:optional true} [:maybe :int]]
   [:lesson-type LessonType]
   [:trigger-condition {:optional true} [:maybe :string]]
   [:lesson-content NonBlankString]
   [:confidence {:optional true} Confidence]])

;; -----------------------------------------------------------------------------
;; Compiled Validators (for performance)
;; -----------------------------------------------------------------------------

(def valid-plan? (m/validator Plan))
(def valid-plan-create? (m/validator PlanCreate))
(def valid-plan-update? (m/validator PlanUpdate))

(def valid-task? (m/validator Task))
(def valid-task-create? (m/validator TaskCreate))
(def valid-task-update? (m/validator TaskUpdate))

(def valid-fact? (m/validator Fact))
(def valid-fact-create? (m/validator FactCreate))
(def valid-fact-update? (m/validator FactUpdate))

(def valid-trace? (m/validator Trace))
(def valid-trace-create? (m/validator TraceCreate))

(def valid-lesson? (m/validator Lesson))
(def valid-lesson-create? (m/validator LessonCreate))

;; -----------------------------------------------------------------------------
;; Human-Readable Error Messages
;; -----------------------------------------------------------------------------

(defn explain-errors
  "Get human-readable error messages for validation failures.

   Args:
     schema - Malli schema to validate against
     data   - Data to validate

   Returns:
     Map of field paths to error messages, or nil if valid.

   Example:
     (explain-errors PlanCreate {:name \"\"})
     ;; => {:name [\"should be at least 1 character\"]}"
  [schema data]
  (some-> (m/explain schema data)
          (me/humanize)))

(defn explain-plan [data] (explain-errors Plan data))
(defn explain-plan-create [data] (explain-errors PlanCreate data))
(defn explain-plan-update [data] (explain-errors PlanUpdate data))

(defn explain-task [data] (explain-errors Task data))
(defn explain-task-create [data] (explain-errors TaskCreate data))
(defn explain-task-update [data] (explain-errors TaskUpdate data))

(defn explain-fact [data] (explain-errors Fact data))
(defn explain-fact-create [data] (explain-errors FactCreate data))
(defn explain-fact-update [data] (explain-errors FactUpdate data))

(defn explain-trace [data] (explain-errors Trace data))
(defn explain-trace-create [data] (explain-errors TraceCreate data))

(defn explain-lesson [data] (explain-errors Lesson data))
(defn explain-lesson-create [data] (explain-errors LessonCreate data))

;; -----------------------------------------------------------------------------
;; Validation with Failjure Integration
;; -----------------------------------------------------------------------------

(defn validate
  "Validate data against a schema, returning data if valid or failjure Failure if not.

   Args:
     schema - Malli schema to validate against
     data   - Data to validate

   Returns:
     data if valid, or a map with :errors key containing humanized errors.

   Example:
     (validate PlanCreate {:name \"test\"})
     ;; => {:name \"test\"}

     (validate PlanCreate {:name \"\"})
     ;; => {:errors {:name [\"should be at least 1 character\"]}}"
  [schema data]
  (if (m/validate schema data)
    data
    {:errors (explain-errors schema data)}))

(defn validation-failed?
  "Check if a validation result is a failure."
  [result]
  (and (map? result) (contains? result :errors)))

(defn validation-errors
  "Get the errors from a validation result, or nil if valid."
  [result]
  (when (validation-failed? result)
    (:errors result)))
