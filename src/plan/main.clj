(ns plan.main
  (:gen-class)
  (:require
   [cli-matic.core :as cli]
   [plan.config :as config]
   [plan.db :as db]))

(set! *warn-on-reflection* true)

(def plans-table
  {:create-table [:plans :if-not-exists]
   :with-columns
   [[:id :integer :primary-key :autoincrement]
    [:completed :boolean [:not nil] [:default false]]
    [:description :text]
    [:content :text]
    [:created_at :datetime [:default :current_timestamp]]
    [:updated_at :datetime]]})

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
    [:updated_at :datetime]]})

(def facts-table
  {:create-table [:facts :if-not-exists]
   :with-columns
   [[:id :integer :primary-key :autoincrement]
    [:plan_id :integer [:not nil] [:references :plans :id]]
    [:description :text]
    [:content :text]
    [:created_at :datetime [:default :current_timestamp]]
    [:updated_at :datetime]]})

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
  (println "Listing all plans...")
  ;; TODO: Implement plan listing
  )

(defn plan-create
  "Create a new plan"
  [{:keys [description content]}]
  (println (str "Creating plan: " description))
  (when content
    (println (str "Content: " content)))
  ;; TODO: Implement plan creation
  )

(defn plan-show
  "Show a specific plan"
  [{:keys [id]}]
  (println (str "Showing plan: " id))
  ;; TODO: Implement plan show
  )

(defn task-list
  "List tasks for a plan"
  [{:keys [plan-id]}]
  (println (str "Listing tasks for plan: " plan-id))
  ;; TODO: Implement task listing
  )

(defn task-create
  "Create a new task"
  [{:keys [plan-id description content parent-id]}]
  (println (str "Creating task for plan " plan-id ": " description))
  (when content
    (println (str "Content: " content)))
  (when parent-id
    (println (str "Parent task: " parent-id)))
  ;; TODO: Implement task creation
  )

(defn search
  "Search across plans, tasks, and facts"
  [{:keys [query]}]
  (println (str "Searching for: " query))
  ;; TODO: Implement search using FTS
  )

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
       :runs plan-show}]}
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
       :runs task-create}]}
    {:command "search"
     :description "Search across plans, tasks, and facts"
     :opts [{:as "Search query" :option "query" :short "q" :type :string :required true}]
     :runs search}]})

(defn -main [& args]
  (cli/run-cmd args cli-definition))
