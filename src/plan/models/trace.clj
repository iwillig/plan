(ns plan.models.trace
  (:require
   [hugsql.adapter.next-jdbc :as next-adapter]
   [hugsql.core :as hugsql]))

;; Configure HugSQL to use next.jdbc adapter
(hugsql/set-adapter! (next-adapter/hugsql-adapter-next-jdbc))

;; Load SQL queries from external file
(hugsql/def-db-fns "sql/traces.sql")

(defn create
  "Create a new trace entry
   Options: plan-id, task-id (optional), trace-type, sequence-num, content, metadata (optional)"
  [conn {:keys [plan-id task-id trace-type sequence-num content metadata]}]
  (trace-create conn {:plan-id plan-id
                      :task-id task-id
                      :trace-type trace-type
                      :sequence-num sequence-num
                      :content content
                      :metadata (when metadata (str metadata))}))

(defn get-next-sequence
  "Get the next sequence number for a plan's traces"
  [conn plan-id]
  (let [result (trace-get-next-sequence conn {:plan-id plan-id})
        max-seq (:seq result)]
    (inc (or max-seq 0))))

(defn get-by-plan
  "Get all traces for a plan, ordered by sequence"
  [conn plan-id]
  (trace-get-by-plan conn {:plan-id plan-id}))

(defn get-by-task
  "Get all traces for a task, ordered by sequence"
  [conn task-id]
  (trace-get-by-task conn {:task-id task-id}))

(defn delete-by-plan
  "Delete all traces for a plan"
  [conn plan-id]
  (trace-delete-by-plan conn {:plan-id plan-id}))

(defn delete-by-task
  "Delete all traces for a task"
  [conn task-id]
  (trace-delete-by-task conn {:task-id task-id}))
