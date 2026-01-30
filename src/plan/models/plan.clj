(ns plan.models.plan
  "Plan entity model with Malli schemas"
  (:refer-clojure :exclude [update])
  (:require
   [hugsql.adapter.next-jdbc :as next-adapter]
   [hugsql.core :as hugsql]
   [malli.core :as m]
   [plan.db :as db]))

(set! *warn-on-reflection* true)

;; Configure HugSQL to use next.jdbc adapter
(hugsql/set-adapter! (next-adapter/hugsql-adapter-next-jdbc))

;; Load SQL queries from external file
(hugsql/def-db-fns "sql/plans.sql")

;; Malli schema for a Plan entity
(def Plan
  [:map
   [:id [:maybe :int]]
   [:name :string]
   [:description [:maybe :string]]
   [:content [:maybe :string]]
   [:completed :boolean]
   [:created_at [:maybe :string]]
   [:updated_at [:maybe :string]]])

;; Schema for plan creation (id/timestamps are optional)
(def PlanCreate
  [:map
   [:name :string]
   [:description [:maybe :string]]
   [:content [:maybe :string]]])

;; Schema for plan updates (all fields optional)
(def PlanUpdate
  [:map
   [:name {:optional true} :string]
   [:description {:optional true} [:maybe :string]]
   [:content {:optional true} [:maybe :string]]
   [:completed {:optional true} :boolean]])

(defn- convert-boolean [plan]
  (when plan
    (clojure.core/update plan :completed #(if (number? %) (not= 0 %) %))))

(defn create
  "Create a new plan with the given name, description and content.
   Returns the created plan with generated id and timestamps."
  [conn name description content]
  (convert-boolean
   (plan-create conn {:name name
                      :description description
                      :content content
                      :completed false})))

(defn get-by-id
  "Fetch a plan by its id. Returns nil if not found."
  [conn id]
  (convert-boolean
   (plan-get-by-id conn {:id id})))

(defn get-by-name
  "Fetch a plan by its name. Returns nil if not found."
  [conn name]
  (convert-boolean
   (plan-get-by-name conn {:name name})))

(defn get-all
  "Fetch all plans, ordered by created_at descending, then id descending."
  [conn]
  (map convert-boolean
       (plan-get-all conn {})))

(defn update
  "Update a plan's fields. Returns the updated plan or nil if not found.
   Returns nil if no fields to update are provided."
  [conn id {:keys [name description content completed] :as updates}]
  (when (seq updates)
    (when-let [existing (get-by-id conn id)]
      (convert-boolean
       (plan-update conn {:id id
                          :name (or name (:name existing))
                          :description (or description (:description existing))
                          :content (or content (:content existing))
                          :completed (if (nil? completed)
                                       (:completed existing)
                                       (if completed 1 0))})))))

(defn delete
  "Delete a plan by id. Returns true if a plan was deleted."
  [conn id]
  (let [result (plan-delete conn {:id id})]
    (> result 0)))

(defn mark-completed
  "Mark a plan as completed or not completed."
  [conn id completed]
  (update conn id {:completed completed}))

(defn search
  "Search for plans matching the query using full-text search."
  [conn query]
  ;; Keep using db/search-plans until db.clj is migrated
  (db/search-plans conn query))

(defn upsert
  "Insert or update a plan by name. Returns the plan.
   If a plan with this name exists, updates it. Otherwise creates new."
  [conn {:keys [name description content completed]}]
  (plan-upsert conn {:name name
                     :description description
                     :content content
                     :completed (if completed 1 0)})
  ;; Fetch and return the plan
  (get-by-name conn name))

;; Malli function schemas - register at end to avoid reload issues
(try
  (m/=> create [:=> [:cat :any :string [:maybe :string] [:maybe :string]] Plan])
  (m/=> get-by-id [:=> [:cat :any :int] [:maybe Plan]])
  (m/=> get-by-name [:=> [:cat :any :string] [:maybe Plan]])
  (m/=> get-all [:=> [:cat :any] [:sequential Plan]])
  (m/=> update [:=> [:cat :any :int PlanUpdate] [:maybe Plan]])
  (m/=> upsert [:=> [:cat :any Plan] Plan])
  (m/=> delete [:=> [:cat :any :int] :boolean])
  (m/=> mark-completed [:=> [:cat :any :int :boolean] [:maybe Plan]])
  (m/=> search [:=> [:cat :any :string] [:sequential Plan]])
  (catch Exception _ nil))
