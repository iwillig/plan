(ns plan.models.lesson
  "Lesson entity model for Reflexion-style learning.
   Lessons capture patterns learned from task execution
   that can be applied to future similar tasks."
  (:require
   [malli.core :as m]
   [plan.db :as db]))

(set! *warn-on-reflection* true)

;; Valid lesson types
(def valid-lesson-types #{"success_pattern" "failure_pattern" "constraint" "technique"})

;; Malli schema for a Lesson entity
(def Lesson
  [:map
   [:id [:maybe :int]]
   [:plan_id [:maybe :int]]
   [:task_id [:maybe :int]]
   [:lesson_type :string]
   [:trigger_condition [:maybe :string]]
   [:lesson_content :string]
   [:confidence [:maybe :double]]
   [:times_validated [:maybe :int]]
   [:created_at [:maybe :string]]])

;; Schema for lesson creation
(def LessonCreate
  [:map
   [:plan_id {:optional true} [:maybe :int]]
   [:task_id {:optional true} [:maybe :int]]
   [:lesson_type :string]
   [:trigger_condition {:optional true} [:maybe :string]]
   [:lesson_content :string]
   [:confidence {:optional true} [:maybe :double]]])

;; -----------------------------------------------------------------------------
;; CRUD Operations

(defn create
  "Create a new lesson. Returns the created lesson."
  [conn lesson-type lesson-content & [{:keys [plan-id task-id trigger-condition confidence]}]]
  (when (valid-lesson-types lesson-type)
    (db/execute-one!
     conn
     {:insert-into :lessons
      :columns [:plan_id :task_id :lesson_type :trigger_condition :lesson_content :confidence :times_validated]
      :values [[plan-id task-id lesson-type trigger-condition lesson-content (or confidence 0.5) 0]]
      :returning [:*]})))

(defn get-by-id
  "Fetch a lesson by its id."
  [conn id]
  (db/execute-one!
   conn
   {:select [:*]
    :from [:lessons]
    :where [:= :id id]}))

(defn get-by-plan
  "Fetch all lessons for a plan."
  [conn plan-id]
  (db/execute!
   conn
   {:select [:*]
    :from [:lessons]
    :where [:= :plan_id plan-id]
    :order-by [[:confidence :desc] [:times_validated :desc]]}))

(defn get-by-task
  "Fetch all lessons learned from a task."
  [conn task-id]
  (db/execute!
   conn
   {:select [:*]
    :from [:lessons]
    :where [:= :task_id task-id]
    :order-by [[:created_at :desc]]}))

(defn get-by-type
  "Fetch lessons of a specific type."
  [conn lesson-type]
  (db/execute!
   conn
   {:select [:*]
    :from [:lessons]
    :where [:= :lesson_type lesson-type]
    :order-by [[:confidence :desc]]}))

(defn get-all
  "Fetch all lessons ordered by confidence."
  [conn]
  (db/execute!
   conn
   {:select [:*]
    :from [:lessons]
    :order-by [[:confidence :desc] [:times_validated :desc]]}))

(defn get-high-confidence
  "Fetch lessons with confidence above threshold."
  [conn min-confidence]
  (db/execute!
   conn
   {:select [:*]
    :from [:lessons]
    :where [:>= :confidence min-confidence]
    :order-by [[:confidence :desc]]}))

(defn update-confidence
  "Update a lesson's confidence score."
  [conn id confidence]
  (db/execute-one!
   conn
   {:update :lessons
    :set {:confidence confidence}
    :where [:= :id id]
    :returning [:*]}))

(defn validate-lesson
  "Increment the times_validated counter and optionally boost confidence."
  [conn id & [confidence-boost]]
  (let [current (get-by-id conn id)
        new-validated (inc (or (:times_validated current) 0))
        new-confidence (if confidence-boost
                         (min 1.0 (+ (or (:confidence current) 0.5) confidence-boost))
                         (:confidence current))]
    (db/execute-one!
     conn
     {:update :lessons
      :set {:times_validated new-validated
            :confidence new-confidence}
      :where [:= :id id]
      :returning [:*]})))

(defn invalidate-lesson
  "Decrease confidence when a lesson fails to apply."
  [conn id & [confidence-penalty]]
  (let [current (get-by-id conn id)
        penalty (or confidence-penalty 0.1)
        new-confidence (max 0.0 (- (or (:confidence current) 0.5) penalty))]
    (db/execute-one!
     conn
     {:update :lessons
      :set {:confidence new-confidence}
      :where [:= :id id]
      :returning [:*]})))

(defn delete
  "Delete a lesson by id."
  [conn id]
  (let [result (db/execute-one!
                conn
                {:delete-from :lessons
                 :where [:= :id id]})]
    (> (get result :next.jdbc/update-count 0) 0)))

(defn delete-by-plan
  "Delete all lessons for a plan."
  [conn plan-id]
  (let [result (db/execute!
                conn
                {:delete-from :lessons
                 :where [:= :plan_id plan-id]})]
    (get (first result) :next.jdbc/update-count 0)))

;; -----------------------------------------------------------------------------
;; Convenience Functions

(defn add-success-pattern
  "Record a pattern that led to success."
  [conn lesson-content & [opts]]
  (create conn "success_pattern" lesson-content opts))

(defn add-failure-pattern
  "Record a pattern that led to failure."
  [conn lesson-content & [opts]]
  (create conn "failure_pattern" lesson-content opts))

(defn add-constraint
  "Record a constraint discovered during execution."
  [conn lesson-content & [opts]]
  (create conn "constraint" lesson-content opts))

(defn add-technique
  "Record a useful technique discovered during execution."
  [conn lesson-content & [opts]]
  (create conn "technique" lesson-content opts))

(defn get-relevant-lessons
  "Get lessons relevant to a given context.
   Searches lesson content and trigger conditions using FTS."
  [conn query & [min-confidence]]
  (let [confidence-threshold (or min-confidence 0.3)
        fts-query (str query "*")]
    (db/execute!
     conn
     {:select [:l.*]
      :from [[:lessons :l]]
      :join [[:lessons_fts :fts] [:= :l.id :fts.rowid]]
      :where [:and
              [:raw (str "lessons_fts MATCH '" fts-query "'")]
              [:>= :l.confidence confidence-threshold]]
      :order-by [[:l.confidence :desc]]})))

;; -----------------------------------------------------------------------------
;; Malli function schemas

(try
  (m/=> create [:=> [:cat :any :string :string [:maybe :map]] [:maybe Lesson]])
  (m/=> get-by-id [:=> [:cat :any :int] [:maybe Lesson]])
  (m/=> get-by-plan [:=> [:cat :any :int] [:sequential Lesson]])
  (m/=> get-by-task [:=> [:cat :any :int] [:sequential Lesson]])
  (m/=> get-by-type [:=> [:cat :any :string] [:sequential Lesson]])
  (m/=> get-all [:=> [:cat :any] [:sequential Lesson]])
  (m/=> delete [:=> [:cat :any :int] :boolean])
  (m/=> delete-by-plan [:=> [:cat :any :int] :int])
  (catch Exception _ nil))
