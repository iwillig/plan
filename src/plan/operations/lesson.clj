(ns plan.operations.lesson
  "Lesson operations using failjure for monadic error handling.
   Lessons implement the Reflexion pattern for learning from experience.
   This layer is shared between the CLI and MCP server.

   All operations return either:
   - A success value (the data)
   - A failjure.core/Failure with error details

   Lesson types: success_pattern, failure_pattern, constraint, technique"
  (:require
   [clojure.string :as str]
   [failjure.core :as f]
   [plan.models.lesson :as lesson]
   [plan.models.plan :as plan]
   [plan.models.task :as task]))

(set! *warn-on-reflection* true)

;; -----------------------------------------------------------------------------
;; Constants
;; -----------------------------------------------------------------------------

(def ^:private valid-lesson-types
  "Valid lesson types for the Reflexion pattern."
  #{"success_pattern" "failure_pattern" "constraint" "technique"})

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

(defn- validate-lesson-exists
  "Validate that a lesson exists.
   Returns the lesson if found, or a Failure if not."
  [conn lesson-id]
  (if-let [l (lesson/get-by-id conn lesson-id)]
    l
    (f/fail "Lesson not found: %s" lesson-id)))

(defn- validate-lesson-type
  "Validate that lesson-type is valid.
   Returns lesson-type if valid, or a Failure if not."
  [lesson-type]
  (if (contains? valid-lesson-types lesson-type)
    lesson-type
    (f/fail "Invalid lesson type: %s. Must be one of: %s"
            lesson-type
            (str/join ", " (sort valid-lesson-types)))))

(defn- validate-confidence
  "Validate that confidence is between 0.0 and 1.0.
   Returns confidence if valid, or a Failure if not."
  [confidence]
  (cond
    (nil? confidence) 0.5 ;; default
    (and (number? confidence) (<= 0.0 confidence 1.0)) confidence
    :else (f/fail "Invalid confidence: %s. Must be between 0.0 and 1.0" confidence)))

;; -----------------------------------------------------------------------------
;; Operations
;; -----------------------------------------------------------------------------

(defn create-lesson
  "Create a new lesson from experience.

   Args:
     conn   - Database connection
     params - Map with :lesson-type, :lesson-content (required)
              Optional: :plan-id, :task-id, :trigger-condition, :confidence

   Lesson types:
     - success_pattern: what worked well
     - failure_pattern: what didn't work
     - constraint: limits/restrictions discovered
     - technique: methods/approaches that help

   Returns:
     Created lesson map on success, or Failure on validation error."
  [conn {:keys [plan-id task-id lesson-type trigger-condition lesson-content confidence]
         :as params}]
  (f/attempt-all [_params (validate-required params [:lesson-type :lesson-content])
                  _type (validate-lesson-type lesson-type)
                  _conf (validate-confidence confidence)
                  _plan (when plan-id (validate-plan-exists conn plan-id))
                  _task (when task-id (validate-task-exists conn task-id))]
                 (lesson/create conn {:plan-id plan-id
                                      :task-id task-id
                                      :lesson-type lesson-type
                                      :trigger-condition trigger-condition
                                      :lesson-content lesson-content
                                      :confidence (or confidence 0.5)})))

(defn get-lesson
  "Get a single lesson by ID.

   Args:
     conn      - Database connection
     lesson-id - ID of the lesson

   Returns:
     Lesson map on success, or Failure if not found."
  [conn lesson-id]
  (validate-lesson-exists conn lesson-id))

(defn list-all-lessons
  "List all lessons, optionally filtered.

   Args:
     conn    - Database connection
     filters - Optional map with:
               :min-confidence - minimum confidence (0.0-1.0)
               :max-confidence - maximum confidence (0.0-1.0)
               :lesson-type    - filter by type

   Returns:
     Vector of lesson maps ordered by confidence descending."
  [conn {:keys [min-confidence max-confidence lesson-type]}]
  (let [lessons (lesson/get-all conn)
        filtered (cond->> lessons
                   min-confidence (filter #(>= (:confidence %) min-confidence))
                   max-confidence (filter #(<= (:confidence %) max-confidence))
                   lesson-type (filter #(= (:lesson_type %) lesson-type)))]
    (vec filtered)))

(defn list-plan-lessons
  "List all lessons for a plan.

   Args:
     conn    - Database connection
     plan-id - ID of the plan
     filters - Optional map with :min-confidence, :max-confidence, :lesson-type

   Returns:
     Vector of lesson maps, or Failure if plan not found."
  [conn plan-id {:keys [min-confidence max-confidence lesson-type]}]
  (f/attempt-all [_plan (validate-plan-exists conn plan-id)]
                 (let [lessons (lesson/get-by-plan conn plan-id)
                       filtered (cond->> lessons
                                  min-confidence (filter #(>= (:confidence %) min-confidence))
                                  max-confidence (filter #(<= (:confidence %) max-confidence))
                                  lesson-type (filter #(= (:lesson_type %) lesson-type)))]
                   (vec filtered))))

(defn list-task-lessons
  "List all lessons for a task.

   Args:
     conn    - Database connection
     task-id - ID of the task

   Returns:
     Vector of lesson maps, or Failure if task not found."
  [conn task-id]
  (f/attempt-all [_task (validate-task-exists conn task-id)]
                 (lesson/get-by-task conn task-id)))

(defn search-lessons
  "Search lessons using full-text search.

   Args:
     conn  - Database connection
     query - Search query string

   Returns:
     Vector of matching lesson maps, or Failure if query is empty."
  [conn query]
  (if (str/blank? query)
    (f/fail "Search query cannot be empty")
    (lesson/search conn query)))

(defn validate-lesson
  "Increase confidence in a lesson (it was useful/correct).

   Args:
     conn      - Database connection
     lesson-id - ID of the lesson

   Returns:
     Updated lesson map, or Failure if not found."
  [conn lesson-id]
  (f/attempt-all [_lesson (validate-lesson-exists conn lesson-id)]
                 (do
                   (lesson/validate conn lesson-id)
                   (lesson/get-by-id conn lesson-id))))

(defn invalidate-lesson
  "Decrease confidence in a lesson (it was wrong/unhelpful).

   Args:
     conn      - Database connection
     lesson-id - ID of the lesson

   Returns:
     Updated lesson map, or Failure if not found."
  [conn lesson-id]
  (f/attempt-all [_lesson (validate-lesson-exists conn lesson-id)]
                 (do
                   (lesson/invalidate conn lesson-id)
                   (lesson/get-by-id conn lesson-id))))

(defn delete-lesson
  "Delete a lesson by ID.

   Args:
     conn      - Database connection
     lesson-id - ID of the lesson

   Returns:
     Map with :deleted true on success, or Failure if not found."
  [conn lesson-id]
  (f/attempt-all [_lesson (validate-lesson-exists conn lesson-id)]
                 (do
                   (lesson/delete conn lesson-id)
                   {:deleted true :lesson-id lesson-id})))

(defn delete-plan-lessons
  "Delete all lessons for a plan.

   Args:
     conn    - Database connection
     plan-id - ID of the plan

   Returns:
     Map with :deleted true on success, or Failure if plan not found."
  [conn plan-id]
  (f/attempt-all [_plan (validate-plan-exists conn plan-id)]
                 (do
                   (lesson/delete-by-plan conn plan-id)
                   {:deleted true :plan-id plan-id})))

(defn delete-task-lessons
  "Delete all lessons for a task.

   Args:
     conn    - Database connection
     task-id - ID of the task

   Returns:
     Map with :deleted true on success, or Failure if task not found."
  [conn task-id]
  (f/attempt-all [_task (validate-task-exists conn task-id)]
                 (do
                   (lesson/delete-by-task conn task-id)
                   {:deleted true :task-id task-id})))
