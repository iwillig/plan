(ns plan.main
  (:gen-class)
  (:require
   [cli-matic.core :as cli]
   [clojure.pprint :as pprint]
   [plan.config :as config]
   [plan.db :as db]
   [plan.markdown :as markdown]
   [plan.models.fact :as fact]
   [plan.models.plan :as plan]
   [plan.models.task :as task]
   [plan.serializers.markdown :as md-serializer]))

(set! *warn-on-reflection* true)

(def plans-table
  {:create-table [:plans :if-not-exists]
   :with-columns
   [[:id :integer :primary-key :autoincrement]
    [:name :text [:not nil] :unique]
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
    [:name :text [:not nil]]
    [:completed :boolean [:not nil] [:default false]]
    [:description :text]
    [:content :text]
    [:parent_id :integer]
    [:created_at :datetime [:default :current_timestamp]]
    [:updated_at :datetime [:default :current_timestamp]]]})

(def tasks-unique-constraint
  [:raw "CREATE UNIQUE INDEX IF NOT EXISTS idx_tasks_plan_name ON tasks(plan_id, name)"])

(def facts-table
  {:create-table [:facts :if-not-exists]
   :with-columns
   [[:id :integer :primary-key :autoincrement]
    [:plan_id :integer [:not nil] [:references :plans :id]]
    [:name :text [:not nil]]
    [:description :text]
    [:content :text]
    [:created_at :datetime [:default :current_timestamp]]
    [:updated_at :datetime [:default :current_timestamp]]]})

(def facts-unique-constraint
  [:raw "CREATE UNIQUE INDEX IF NOT EXISTS idx_facts_plan_name ON facts(plan_id, name)"])

(def idx-tasks-plan-id
  {:create-index [[:idx_tasks_plan_id :if-not-exists] [:tasks :plan_id]]})

(def idx-tasks-parent-id
  {:create-index [[:idx_tasks_parent_id :if-not-exists] [:tasks :parent_id]]})

(def idx-facts-plan-id
  {:create-index [[:idx_facts_plan_id :if-not-exists] [:facts :plan_id]]})

;; FTS5 virtual tables (raw SQL - HoneySQL doesn't support CREATE VIRTUAL TABLE)
(def plans-fts-table
  [:raw "CREATE VIRTUAL TABLE IF NOT EXISTS plans_fts USING fts5(name, description, content, content='plans', content_rowid='id')"])

(def tasks-fts-table
  [:raw "CREATE VIRTUAL TABLE IF NOT EXISTS tasks_fts USING fts5(name, description, content, content='tasks', content_rowid='id')"])

(def facts-fts-table
  [:raw "CREATE VIRTUAL TABLE IF NOT EXISTS facts_fts USING fts5(name, description, content, content='facts', content_rowid='id')"])

;; FTS5 triggers for plans (raw SQL)
(def plans-fts-ai-trigger
  [:raw "CREATE TRIGGER IF NOT EXISTS plans_ai AFTER INSERT ON plans BEGIN
           INSERT INTO plans_fts(rowid, name, description, content)
           VALUES (new.id, new.name, new.description, new.content);
         END"])

(def plans-fts-ad-trigger
  [:raw "CREATE TRIGGER IF NOT EXISTS plans_ad AFTER DELETE ON plans BEGIN
           INSERT INTO plans_fts(plans_fts, rowid, name, description, content)
           VALUES('delete', old.id, old.name, old.description, old.content);
         END"])

(def plans-fts-au-trigger
  [:raw "CREATE TRIGGER IF NOT EXISTS plans_au AFTER UPDATE ON plans BEGIN
           INSERT INTO plans_fts(plans_fts, rowid, name, description, content)
           VALUES('delete', old.id, old.name, old.description, old.content);
           INSERT INTO plans_fts(rowid, name, description, content)
           VALUES (new.id, new.name, new.description, new.content);
         END"])

;; FTS5 triggers for tasks (raw SQL)
(def tasks-fts-ai-trigger
  [:raw "CREATE TRIGGER IF NOT EXISTS tasks_ai AFTER INSERT ON tasks BEGIN
           INSERT INTO tasks_fts(rowid, name, description, content)
           VALUES (new.id, new.name, new.description, new.content);
         END"])

(def tasks-fts-ad-trigger
  [:raw "CREATE TRIGGER IF NOT EXISTS tasks_ad AFTER DELETE ON tasks BEGIN
           INSERT INTO tasks_fts(tasks_fts, rowid, name, description, content)
           VALUES('delete', old.id, old.name, old.description, old.content);
         END"])

(def tasks-fts-au-trigger
  [:raw "CREATE TRIGGER IF NOT EXISTS tasks_au AFTER UPDATE ON tasks BEGIN
           INSERT INTO tasks_fts(tasks_fts, rowid, name, description, content)
           VALUES('delete', old.id, old.name, old.description, old.content);
           INSERT INTO tasks_fts(rowid, name, description, content)
           VALUES (new.id, new.name, new.description, new.content);
         END"])

;; FTS5 triggers for facts (raw SQL)
(def facts-fts-ai-trigger
  [:raw "CREATE TRIGGER IF NOT EXISTS facts_ai AFTER INSERT ON facts BEGIN
           INSERT INTO facts_fts(rowid, name, description, content)
           VALUES (new.id, new.name, new.description, new.content);
         END"])

(def facts-fts-ad-trigger
  [:raw "CREATE TRIGGER IF NOT EXISTS facts_ad AFTER DELETE ON facts BEGIN
           INSERT INTO facts_fts(facts_fts, rowid, name, description, content)
           VALUES('delete', old.id, old.name, old.description, old.content);
         END"])

(def facts-fts-au-trigger
  [:raw "CREATE TRIGGER IF NOT EXISTS facts_au AFTER UPDATE ON facts BEGIN
           INSERT INTO facts_fts(facts_fts, rowid, name, description, content)
           VALUES('delete', old.id, old.name, old.description, old.content);
           INSERT INTO facts_fts(rowid, name, description, content)
           VALUES (new.id, new.name, new.description, new.content);
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
  ;; Unique constraints (for name uniqueness within plan scope)
  (db/execute! conn tasks-unique-constraint)
  (db/execute! conn facts-unique-constraint)
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
  [{:keys [name description content]}]
  (let [db-path (config/db-path)]
    (db/with-connection
      db-path
      (fn [conn]
        (pprint/pprint (plan/create conn name description content))))))

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
  [{:keys [id completed] :as args}]
  (let [db-path (config/db-path)]
    (db/with-connection
      db-path
      (fn [conn]
        (let [updates (select-keys args [:name :description :content])
              updates (if completed
                        (assoc updates :completed (= "true" completed))
                        updates)
              result (plan/update conn id updates)]
          (pprint/pprint result))))))

(defn task-list
  "List tasks for a plan"
  [{:keys [plan-id]}]
  (let [db-path (config/db-path)]
    (db/with-connection
      db-path
      (fn [conn]
        (let [tasks (task/get-by-plan conn plan-id)]
          (pprint/pprint tasks))))))

(defn task-create
  "Create a new task"
  [{:keys [plan-id name description content parent-id]}]
  (let [db-path (config/db-path)]
    (db/with-connection
      db-path
      (fn [conn]
        (let [result (task/create conn plan-id name description content parent-id)]
          (pprint/pprint result))))))

(defn task-update
  "Update a task"
  [{:keys [id completed] :as args}]
  (let [db-path (config/db-path)]
    (db/with-connection
      db-path
      (fn [conn]
        (let [updates (select-keys args [:name :description :content :plan-id :parent-id])
              updates (if completed
                        (assoc updates :completed (= "true" completed))
                        updates)
              result (task/update conn id updates)]
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
        (let [task-count (task/delete-by-plan conn id)]
          (fact/delete-by-plan conn id)
          (plan/delete conn id)
          (pprint/pprint {:deleted {:plan id :tasks task-count}}))))))

(defn task-delete
  "Delete a task"
  [{:keys [id]}]
  (let [db-path (config/db-path)]
    (db/with-connection
      db-path
      (fn [conn]
        (task/delete conn id)
        (pprint/pprint {:deleted {:task id}})))))

(defn test-markdown
  "Test CommonMark markdown parsing with YAML front matter.
   Useful for verifying GraalVM native-image compatibility."
  [_]
  (println "Testing CommonMark markdown parsing...")
  (let [result (markdown/test-markdown-parsing)]
    (println "\n=== Test Input ===")
    (println (:input result))
    (println "\n=== Front Matter ===")
    (pprint/pprint (:front-matter result))
    (println "\n=== Rendered HTML ===")
    (println (:html result))
    (println "\n=== Test Result ===")
    (if (:success result)
      (do (println "SUCCESS: CommonMark parsing works correctly!")
          (System/exit 0))
      (do (println "FAILURE: CommonMark parsing failed!")
          (System/exit 1)))))

(defn plan-export
  "Export a plan to a markdown file."
  [{:keys [id file]}]
  (let [db-path (config/db-path)]
    (db/with-connection
      db-path
      (fn [conn]
        (if-let [plan-data (plan/get-by-id conn id)]
          (let [tasks (task/get-by-plan conn id)
                facts (fact/get-by-plan conn id)
                output-file (or file (str (:name plan-data) ".md"))]
            (md-serializer/write-plan-to-file output-file plan-data tasks facts)
            (println (str "Exported plan '" (:name plan-data) "' to " output-file)))
          (do (println (str "Error: Plan with ID " id " not found"))
              (System/exit 1)))))))

(defn plan-import
  "Import a plan from a markdown file."
  [{:keys [file]}]
  (let [db-path (config/db-path)]
    (if-not (.exists ^java.io.File (java.io.File. ^String file))
      (do (println (str "Error: File not found: " file))
          (System/exit 1))
      (let [data (md-serializer/read-plan-from-file file)]
        (if-not (md-serializer/valid-plan-markdown? (slurp file))
          (do (println "Error: Invalid plan markdown file")
              (System/exit 1))
          (db/with-connection
            db-path
            (fn [conn]
              (let [plan-data (:plan data)
                    ;; Create the plan
                    created-plan (plan/create conn
                                              (:name plan-data)
                                              (:description plan-data)
                                              (:content plan-data))
                    plan-id (:id created-plan)]
                ;; Update completed status if needed
                (when (:completed plan-data)
                  (plan/update conn plan-id {:completed true}))
                ;; Create tasks
                (doseq [task-data (:tasks data)]
                  (task/create conn
                               plan-id
                               (:name task-data)
                               (:description task-data)
                               (:content task-data)
                               (:parent_id task-data))
                  (when (:completed task-data)
                    ;; Get the task we just created (by name) and mark completed
                    (let [tasks (task/get-by-plan conn plan-id)
                          matching (first (filter #(= (:name task-data) (:name %)) tasks))]
                      (when matching
                        (task/update conn (:id matching) {:completed true})))))
                ;; Create facts
                (doseq [fact-data (:facts data)]
                  (fact/create conn
                               plan-id
                               (:name fact-data)
                               (:description fact-data)
                               (:content fact-data)))
                (println (str "Imported plan '" (:name created-plan) "' with ID " plan-id))
                (pprint/pprint {:plan created-plan
                                :task-count (count (:tasks data))
                                :fact-count (count (:facts data))})))))))))

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
       :opts [{:as "Name" :option "name" :short "n" :type :string :required true}
              {:as "Description" :option "description" :short "d" :type :string}
              {:as "Content" :option "content" :short "c" :type :string}]
       :runs plan-create}
      {:command "show"
       :description "Show a specific plan"
       :opts [{:as "Plan ID" :option "id" :required true :type :int}]
       :runs plan-show}
      {:command "update"
       :description "Update a plan"
       :opts [{:as "Plan ID" :option "id" :required true :type :int}
              {:as "Name" :option "name" :short "n" :type :string}
              {:as "Description" :option "description" :short "d" :type :string}
              {:as "Content" :option "content" :short "c" :type :string}
              {:as "Completed" :option "completed" :type :string}]
       :runs plan-update}
      {:command "delete"
       :description "Delete a plan and all its tasks"
       :opts [{:as "Plan ID" :option "id" :required true :type :int}]
       :runs plan-delete}
      {:command "export"
       :description "Export a plan to a markdown file"
       :opts [{:as "Plan ID" :option "id" :required true :type :int}
              {:as "Output file" :option "file" :short "f" :type :string}]
       :runs plan-export}
      {:command "import"
       :description "Import a plan from a markdown file"
       :opts [{:as "Input file" :option "file" :short "f" :type :string :required true}]
       :runs plan-import}]}
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
              {:as "Name" :option "name" :short "n" :type :string :required true}
              {:as "Description" :option "description" :short "d" :type :string}
              {:as "Content" :option "content" :short "c" :type :string}
              {:as "Parent Task ID" :option "parent-id" :type :int}]
       :runs task-create}
      {:command "update"
       :description "Update a task"
       :opts [{:as "Task ID" :option "id" :required true :type :int}
              {:as "Name" :option "name" :short "n" :type :string}
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
     :runs search}
    {:command "test-markdown"
     :description "Test CommonMark markdown parsing (verifies GraalVM compatibility)"
     :runs test-markdown}]})

(defn -main [& args]
  (cli/run-cmd args cli-definition))
