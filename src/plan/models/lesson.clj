(ns plan.models.lesson
  (:require
   [hugsql.adapter.next-jdbc :as next-adapter]
   [hugsql.core :as hugsql]))

;; Configure HugSQL to use next.jdbc adapter
(hugsql/set-adapter! (next-adapter/hugsql-adapter-next-jdbc))

;; Load SQL queries from external file
(hugsql/def-db-fns "sql/lessons.sql")

(def ^:private default-confidence 0.5)
(def ^:private confidence-delta 0.1)

(defn create
  "Create a new lesson
   Types: success_pattern, failure_pattern, constraint, technique"
  [conn {:keys [plan_id task_id lesson_type trigger_condition lesson_content
                plan-id task-id lesson-type trigger-condition lesson-content
                confidence]}]
  (lesson-create conn {:plan-id (or plan-id plan_id)
                       :task-id (or task-id task_id)
                       :lesson-type (or lesson-type lesson_type)
                       :trigger-condition (or trigger-condition trigger_condition)
                       :lesson-content (or lesson-content lesson_content)
                       :confidence (or confidence default-confidence)}))

(defn get-by-id
  "Get a lesson by ID"
  [conn id]
  (lesson-get-by-id conn {:id id}))

(defn get-all
  "Get all lessons ordered by confidence desc"
  [conn]
  (lesson-get-all conn {}))

(defn get-by-plan
  "Get lessons for a plan"
  [conn plan-id]
  (lesson-get-by-plan conn {:plan-id plan-id}))

(defn get-by-task
  "Get lessons for a task"
  [conn task-id]
  (lesson-get-by-task conn {:task-id task-id}))

(defn search
  "Search lessons using FTS"
  [conn query]
  (lesson-search conn {:query (str query "*")}))

(defn validate
  "Increase confidence and times_validated for a lesson"
  [conn id]
  (lesson-validate conn {:id id :delta confidence-delta}))

(defn invalidate
  "Decrease confidence for a lesson"
  [conn id]
  (lesson-invalidate conn {:id id :delta confidence-delta}))

(defn delete
  "Delete a lesson by ID"
  [conn id]
  (lesson-delete conn {:id id}))

(defn delete-by-plan
  "Delete all lessons for a plan"
  [conn plan-id]
  (lesson-delete-by-plan conn {:plan-id plan-id}))

(defn delete-by-task
  "Delete all lessons for a task"
  [conn task-id]
  (lesson-delete-by-task conn {:task-id task-id}))
