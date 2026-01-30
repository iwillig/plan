(ns plan.models.trace
  "Trace entity model for ReAct-style reasoning traces.
   Traces capture the thought/action/observation/reflection cycle
   of LLM agent reasoning."
  (:require
   [malli.core :as m]
   [plan.db :as db]))

(set! *warn-on-reflection* true)

;; Valid trace types for ReAct pattern
(def valid-trace-types #{"thought" "action" "observation" "reflection"})

;; Malli schema for a Trace entity
(def Trace
  [:map
   [:id [:maybe :int]]
   [:plan_id :int]
   [:task_id [:maybe :int]]
   [:trace_type :string]
   [:sequence_num :int]
   [:content :string]
   [:metadata [:maybe :string]]
   [:created_at [:maybe :string]]])

;; Schema for trace creation
(def TraceCreate
  [:map
   [:plan_id :int]
   [:task_id {:optional true} [:maybe :int]]
   [:trace_type :string]
   [:content :string]
   [:metadata {:optional true} [:maybe :string]]])

;; -----------------------------------------------------------------------------
;; CRUD Operations

(defn- get-next-sequence-num
  "Get the next sequence number for traces in a plan (or task)."
  [conn plan-id task-id]
  (let [result (if task-id
                 (db/execute-one!
                  conn
                  {:select [[[:max :sequence_num] :max_seq]]
                   :from [:traces]
                   :where [:and [:= :plan_id plan-id] [:= :task_id task-id]]})
                 (db/execute-one!
                  conn
                  {:select [[[:max :sequence_num] :max_seq]]
                   :from [:traces]
                   :where [:= :plan_id plan-id]}))]
    (inc (or (:max_seq result) 0))))

(defn create
  "Create a new trace. Automatically assigns sequence number.
   Returns the created trace."
  [conn plan-id task-id trace-type content & [metadata]]
  (when (valid-trace-types trace-type)
    (let [seq-num (get-next-sequence-num conn plan-id task-id)]
      (db/execute-one!
       conn
       {:insert-into :traces
        :columns [:plan_id :task_id :trace_type :sequence_num :content :metadata]
        :values [[plan-id task-id trace-type seq-num content metadata]]
        :returning [:*]}))))

(defn get-by-id
  "Fetch a trace by its id."
  [conn id]
  (db/execute-one!
   conn
   {:select [:*]
    :from [:traces]
    :where [:= :id id]}))

(defn get-by-plan
  "Fetch all traces for a plan, ordered by sequence."
  [conn plan-id]
  (db/execute!
   conn
   {:select [:*]
    :from [:traces]
    :where [:= :plan_id plan-id]
    :order-by [[:sequence_num :asc]]}))

(defn get-by-task
  "Fetch all traces for a task, ordered by sequence."
  [conn task-id]
  (db/execute!
   conn
   {:select [:*]
    :from [:traces]
    :where [:= :task_id task-id]
    :order-by [[:sequence_num :asc]]}))

(defn get-by-type
  "Fetch traces of a specific type for a plan."
  [conn plan-id trace-type]
  (db/execute!
   conn
   {:select [:*]
    :from [:traces]
    :where [:and [:= :plan_id plan-id] [:= :trace_type trace-type]]
    :order-by [[:sequence_num :asc]]}))

(defn delete
  "Delete a trace by id."
  [conn id]
  (let [result (db/execute-one!
                conn
                {:delete-from :traces
                 :where [:= :id id]})]
    (> (get result :next.jdbc/update-count 0) 0)))

(defn delete-by-plan
  "Delete all traces for a plan."
  [conn plan-id]
  (let [result (db/execute!
                conn
                {:delete-from :traces
                 :where [:= :plan_id plan-id]})]
    (get (first result) :next.jdbc/update-count 0)))

(defn delete-by-task
  "Delete all traces for a task."
  [conn task-id]
  (let [result (db/execute!
                conn
                {:delete-from :traces
                 :where [:= :task_id task-id]})]
    (get (first result) :next.jdbc/update-count 0)))

;; -----------------------------------------------------------------------------
;; Convenience Functions for ReAct Pattern

(defn add-thought
  "Add a thought trace."
  [conn plan-id task-id content & [metadata]]
  (create conn plan-id task-id "thought" content metadata))

(defn add-action
  "Add an action trace."
  [conn plan-id task-id content & [metadata]]
  (create conn plan-id task-id "action" content metadata))

(defn add-observation
  "Add an observation trace."
  [conn plan-id task-id content & [metadata]]
  (create conn plan-id task-id "observation" content metadata))

(defn add-reflection
  "Add a reflection trace."
  [conn plan-id task-id content & [metadata]]
  (create conn plan-id task-id "reflection" content metadata))

(defn get-recent-traces
  "Get the N most recent traces for a plan or task."
  [conn plan-id task-id limit]
  (let [base-query {:select [:*]
                    :from [:traces]
                    :order-by [[:sequence_num :desc]]
                    :limit limit}
        query (if task-id
                (assoc base-query :where [:and [:= :plan_id plan-id] [:= :task_id task-id]])
                (assoc base-query :where [:= :plan_id plan-id]))]
    (reverse (db/execute! conn query))))

;; -----------------------------------------------------------------------------
;; Malli function schemas

(try
  (m/=> create [:=> [:cat :any :int [:maybe :int] :string :string [:maybe :string]] [:maybe Trace]])
  (m/=> get-by-id [:=> [:cat :any :int] [:maybe Trace]])
  (m/=> get-by-plan [:=> [:cat :any :int] [:sequential Trace]])
  (m/=> get-by-task [:=> [:cat :any :int] [:sequential Trace]])
  (m/=> delete [:=> [:cat :any :int] :boolean])
  (m/=> delete-by-plan [:=> [:cat :any :int] :int])
  (m/=> delete-by-task [:=> [:cat :any :int] :int])
  (catch Exception _ nil))
