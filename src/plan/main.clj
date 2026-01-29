(ns plan.main
  (:gen-class)
  (:require
   [cli-matic.core :as cli]
   [clojure.pprint :as pprint]
   [plan.config :as config]
   [plan.db :as db]
   [plan.models.fact :as fact]
   [plan.models.plan :as plan]
   [plan.models.task :as task]))

(set! *warn-on-reflection* true)

(def plans-table
  {:create-table [:plans :if-not-exists]
   :with-columns
   [[:id :integer :primary-key :autoincrement]
    [:completed :boolean [:not nil] [:default false]]
    [:description :text]
    [:content :text]
    [:created_at :datetime [:default :current_timestamp]]
    [:updated_at :datetime [:default :current_timestamp]]]})

(def tasks-table
  {:create-table [:tasks :if-not-exists]
   :with-columns
   [[:id :integer :primary-key :autoincrement]
    [:plan_id :integer [:not nil] [:references :plans :id]]
    [:completed :boolean [:not nil] [:default false]]
    [:description :text]
    [:content :text]
    [:parent_id :integer]
    [:created_at :datetime [:default :current_timestamp]]
    [:updated_at :datetime [:default :current_timestamp]]]})

(def facts-table
  {:create-table [:facts :if-not-exists]
   :with-columns
   [[:id :integer :primary-key :autoincrement]
    [:plan_id :integer [:not nil] [:references :plans :id]]
    [:description :text]
    [:content :text]
    [:created_at :datetime [:default :current_timestamp]]
    [:updated_at :datetime [:default :current_timestamp]]]})

(def idx-tasks-plan-id
  {:create-index [[:idx_tasks_plan_id :if-not-exists] [:tasks :plan_id]]})

(def idx-tasks-parent-id
  {:create-index [[:idx_tasks_parent_id :if-not-exists] [:tasks :parent_id]]})

(def idx-facts-plan-id
  {:create-index [[:idx_facts_plan_id :if-not-exists] [:facts :plan_id]]})

;; FTS5 virtual tables (raw SQL - HoneySQL doesn't support CREATE VIRTUAL TABLE)
(def plans-fts-table
  [:raw "CREATE VIRTUAL TABLE IF NOT EXISTS plans_fts USING fts5(description, content, content='plans', content_rowid='id')"])

(def tasks-fts-table
  [:raw "CREATE VIRTUAL TABLE IF NOT EXISTS tasks_fts USING fts5(description, content, content='tasks', content_rowid='id')"])

(def facts-fts-table
  [:raw "CREATE VIRTUAL TABLE IF NOT EXISTS facts_fts USING fts5(description, content, content='facts', content_rowid='id')"])

;; FTS5 triggers for plans (raw SQL)
(def plans-fts-ai-trigger
  [:raw "CREATE TRIGGER IF NOT EXISTS plans_ai AFTER INSERT ON plans BEGIN
           INSERT INTO plans_fts(rowid, description, content)
           VALUES (new.id, new.description, new.content);
         END"])

(def plans-fts-ad-trigger
  [:raw "CREATE TRIGGER IF NOT EXISTS plans_ad AFTER DELETE ON plans BEGIN
           INSERT INTO plans_fts(plans_fts, rowid, description, content)
           VALUES('delete', old.id, old.description, old.content);
         END"])

(def plans-fts-au-trigger
  [:raw "CREATE TRIGGER IF NOT EXISTS plans_au AFTER UPDATE ON plans BEGIN
           INSERT INTO plans_fts(plans_fts, rowid, description, content)
           VALUES('delete', old.id, old.description, old.content);
           INSERT INTO plans_fts(rowid, description, content)
           VALUES (new.id, new.description, new.content);
         END"])

;; FTS5 triggers for tasks (raw SQL)
(def tasks-fts-ai-trigger
  [:raw "CREATE TRIGGER IF NOT EXISTS tasks_ai AFTER INSERT ON tasks BEGIN
           INSERT INTO tasks_fts(rowid, description, content)
           VALUES (new.id, new.description, new.content);
         END"])

(def tasks-fts-ad-trigger
  [:raw "CREATE TRIGGER IF NOT EXISTS tasks_ad AFTER DELETE ON tasks BEGIN
           INSERT INTO tasks_fts(tasks_fts, rowid, description, content)
           VALUES('delete', old.id, old.description, old.content);
         END"])

(def tasks-fts-au-trigger
  [:raw "CREATE TRIGGER IF NOT EXISTS tasks_au AFTER UPDATE ON tasks BEGIN
           INSERT INTO tasks_fts(tasks_fts, rowid, description, content)
           VALUES('delete', old.id, old.description, old.content);
           INSERT INTO tasks_fts(rowid, description, content)
           VALUES (new.id, new.description, new.content);
         END"])

;; FTS5 triggers for facts (raw SQL)
(def facts-fts-ai-trigger
  [:raw "CREATE TRIGGER IF NOT EXISTS facts_ai AFTER INSERT ON facts BEGIN
           INSERT INTO facts_fts(rowid, description, content)
           VALUES (new.id, new.description, new.content);
         END"])

(def facts-fts-ad-trigger
  [:raw "CREATE TRIGGER IF NOT EXISTS facts_ad AFTER DELETE ON facts BEGIN
           INSERT INTO facts_fts(facts_fts, rowid, description, content)
           VALUES('delete', old.id, old.description, old.content);
         END"])

(def facts-fts-au-trigger
  [:raw "CREATE TRIGGER IF NOT EXISTS facts_au AFTER UPDATE ON facts BEGIN
           INSERT INTO facts_fts(facts_fts, rowid, description, content)
           VALUES('delete', old.id, old.description, old.content);
           INSERT INTO facts_fts(rowid, description, content)
           VALUES (new.id, new.description, new.content);
         END"])

(defn create-schema!
  "Creates all tables, indexes, FTS tables, and triggers.
   Takes a JDBC connection (not datasource) to ensure all
   operations happen in the same SQLite session."
  [conn]
  ;; Regular tables
  (db/execute! conn plans-table)
  (db/execute! conn tasks-table)
  (db/execute! conn facts-table)
  ;; Indexes
  (db/execute! conn idx-tasks-plan-id)
  (db/execute! conn idx-tasks-parent-id)
  (db/execute! conn idx-facts-plan-id)
  ;; FTS virtual tables
  (db/execute! conn plans-fts-table)
  (db/execute! conn tasks-fts-table)
  (db/execute! conn facts-fts-table)
  ;; FTS triggers
  (db/execute! conn plans-fts-ai-trigger)
  (db/execute! conn plans-fts-ad-trigger)
  (db/execute! conn plans-fts-au-trigger)
  (db/execute! conn tasks-fts-ai-trigger)
  (db/execute! conn tasks-fts-ad-trigger)
  (db/execute! conn tasks-fts-au-trigger)
  (db/execute! conn facts-fts-ai-trigger)
  (db/execute! conn facts-fts-ad-trigger)
  (db/execute! conn facts-fts-au-trigger)
  nil)

;; Command handlers

(defn init-db
  "Initialize the database with schema"
  [_]
  (let [db-path (config/db-path)]
    (println (str "Initializing database at: " db-path))
    (db/with-connection db-path create-schema!)
    (println "Database initialized successfully.")))

(defn plan-list
  "List all plans"
  [_]
  (let [db-path (config/db-path)]
    (db/with-connection
      db-path
      (fn [conn]
        (pprint/pprint (plan/get-all conn))))))

(defn plan-create
  "Create a new plan"
  [{:keys [description content]}]
  (let [db-path (config/db-path)]
    (db/with-connection
      db-path
      (fn [conn]
        (pprint/pprint (plan/create conn description content))))))

(defn plan-show
  "Show a specific plan"
  [{:keys [id]}]
  (let [db-path (config/db-path)]
    (db/with-connection
      db-path
      (fn [conn]
        (pprint/pprint {:plan (plan/get-by-id conn id)
                        :tasks (task/get-by-plan conn id)
                        :facts (fact/get-by-plan conn id)})))))

(defn plan-update
  "Update a plan"
  [{:keys [id description content completed]}]
  (let [db-path (config/db-path)]
    (db/with-connection
      db-path
      (fn [conn]
        (let [set-map (cond-> {:updated_at [:datetime "now"]}
                        description (assoc :description description)
                        content (assoc :content content)
                        completed (assoc :completed (= "true" completed)))
              result (db/execute-one!
                      conn
                      {:update [:plans]
                       :set set-map
                       :where [:= :id id]
                       :returning [:*]})]
          (pprint/pprint result))))))

(defn task-list
  "List tasks for a plan"
  [{:keys [plan-id]}]
  (let [db-path (config/db-path)]
    (db/with-connection
      db-path
      (fn [conn]
        (let [tasks (db/execute! conn {:select [:*] :from [:tasks] :where [:= :plan_id plan-id] :order-by [[:created_at :desc]]})]
          (pprint/pprint tasks))))))

(defn task-create*
  "Internal: Create a new task given a connection. Returns the created task."
  [conn plan-id description content parent-id]
  (db/execute-one!
   conn
   {:insert-into [:tasks]
    :columns [:plan_id :description :content :parent_id]
    :values [[plan-id description content parent-id]]
    :returning [:*]}))

(defn task-create
  "Create a new task"
  [{:keys [plan-id description content parent-id]}]
  (let [db-path (config/db-path)]
    (db/with-connection
      db-path
      (fn [conn]
        (let [result (task-create* conn plan-id description content parent-id)]
          (pprint/pprint result))))))

(defn task-update
  "Update a task"
  [{:keys [id description content completed plan-id parent-id]}]
  (let [db-path (config/db-path)]
    (db/with-connection
      db-path
      (fn [conn]
        (let [set-map (cond-> {:updated_at [:datetime "now"]}
                        description (assoc :description description)
                        content (assoc :content content)
                        completed (assoc :completed (= "true" completed))
                        plan-id (assoc :plan_id plan-id)
                        parent-id (assoc :parent_id parent-id))
              result (db/execute-one!
                      conn
                      {:update [:tasks]
                       :set set-map
                       :where [:= :id id]
                       :returning [:*]})]
          (pprint/pprint result))))))

(defn search
  "Search across plans, tasks, and facts"
  [{:keys [query]}]
  (let [db-path (config/db-path)]
    (db/with-connection
      db-path
      (fn [conn]
        (let [plans (db/search-plans conn query)
              tasks (db/search-tasks conn query)
              facts (db/search-facts conn query)]
          (pprint/pprint {:plans plans :tasks tasks :facts facts}))))))

(defn plan-delete
  "Delete a plan and all its tasks"
  [{:keys [id]}]
  (let [db-path (config/db-path)]
    (db/with-connection
      db-path
      (fn [conn]
        (let [tasks (db/execute! conn {:select [:id] :from [:tasks] :where [:= :plan_id id]})
              task-count (count tasks)]
          (db/execute! conn {:delete-from [:tasks] :where [:= :plan_id id]})
          (db/execute! conn {:delete-from [:facts] :where [:= :plan_id id]})
          (db/execute! conn {:delete-from [:plans] :where [:= :id id]})
          (pprint/pprint {:deleted {:plan id :tasks task-count}}))))))

(defn task-delete
  "Delete a task"
  [{:keys [id]}]
  (let [db-path (config/db-path)]
    (db/with-connection
      db-path
      (fn [conn]
        (db/execute! conn {:delete-from [:tasks] :where [:= :id id]})
        (pprint/pprint {:deleted {:task id}})))))

;; CLI definition

(def cli-definition
  {:app-name "plan"
   :version "0.1.0"
   :description "A planning tool for LLM Agents"
   :subcommands
   [{:command "init"
     :description "Initialize the database"
     :runs init-db}
    {:command "plan"
     :description "Plan operations"
     :subcommands
     [{:command "list"
       :description "List all plans"
       :runs plan-list}
      {:command "create"
       :description "Create a new plan"
       :opts [{:as "Description" :option "description" :short "d" :type :string :required true}
              {:as "Content" :option "content" :short "c" :type :string}]
       :runs plan-create}
      {:command "show"
       :description "Show a specific plan"
       :opts [{:as "Plan ID" :option "id" :required true :type :int}]
       :runs plan-show}
      {:command "update"
       :description "Update a plan"
       :opts [{:as "Plan ID" :option "id" :required true :type :int}
              {:as "Description" :option "description" :short "d" :type :string}
              {:as "Content" :option "content" :short "c" :type :string}
              {:as "Completed" :option "completed" :type :string}]
       :runs plan-update}
      {:command "delete"
       :description "Delete a plan and all its tasks"
       :opts [{:as "Plan ID" :option "id" :required true :type :int}]
       :runs plan-delete}]}
    {:command "task"
     :description "Task operations"
     :subcommands
     [{:command "list"
       :description "List tasks for a plan"
       :opts [{:as "Plan ID" :option "plan-id" :required true :type :int}]
       :runs task-list}
      {:command "create"
       :description "Create a new task"
       :opts [{:as "Plan ID" :option "plan-id" :required true :type :int}
              {:as "Description" :option "description" :short "d" :type :string :required true}
              {:as "Content" :option "content" :short "c" :type :string}
              {:as "Parent Task ID" :option "parent-id" :type :int}]
       :runs task-create}
      {:command "update"
       :description "Update a task"
       :opts [{:as "Task ID" :option "id" :required true :type :int}
              {:as "Description" :option "description" :short "d" :type :string}
              {:as "Content" :option "content" :short "c" :type :string}
              {:as "Completed" :option "completed" :type :string}
              {:as "Plan ID" :option "plan-id" :type :int}
              {:as "Parent ID" :option "parent-id" :type :int}]
       :runs task-update}
      {:command "delete"
       :description "Delete a task"
       :opts [{:as "Task ID" :option "id" :required true :type :int}]
       :runs task-delete}]}
    {:command "search"
     :description "Search across plans, tasks, and facts"
     :opts [{:as "Search query" :option "query" :short "q" :type :string :required true}]
     :runs search}]})

(defn -main [& args]
  (cli/run-cmd args cli-definition))
