(ns plan.main
  (:gen-class)
  (:require
   [cli-matic.core :as cli]
   [clojure.pprint :as pprint]
   [plan.config :as config]
   [plan.db :as db]
   [plan.import :as import]
   [plan.markdown :as markdown]
   [plan.models.fact :as fact]
   [plan.models.plan :as plan]
   [plan.models.task :as task]
   [plan.serializers.markdown-v2 :as md-v2]))

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
    (db/with-connection db-path create-schema!)
    (pprint/pprint {:status :success
                    :db-path db-path
                    :message "Database initialized"})))

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

(defn markdown-cmd
  "Parse a markdown file and display its contents.
   Shows raw YAML front matter, parsed front matter, body content, and rendered HTML."
  [{:keys [file]}]
  (if (nil? file)
    ;; No file provided, run the built-in test
    (let [result (markdown/test-markdown-parsing)]
      (pprint/pprint {:mode :test
                      :input (:input result)
                      :front-matter (:front-matter result)
                      :html (:html result)
                      :success (:success result)})
      (if (:success result)
        (System/exit 0)
        (System/exit 1)))
    ;; File provided, parse and display it
    (let [^java.io.File f (java.io.File. ^String file)]
      (if-not (.exists f)
        (do (pprint/pprint {:status :error
                            :message (str "File not found: " file)})
            (System/exit 1))
        (let [content (slurp file)
              result (markdown/parse-with-front-matter content)]
          (pprint/pprint {:mode :parse
                          :file file
                          :raw-yaml (:raw-yaml result)
                          :front-matter (:front-matter result)
                          :body (:body result)
                          :html (:html result)})
          (System/exit 0))))))

(defn plan-export
  "Export a plan to a markdown file using v2 format."
  [{:keys [id file]}]
  (let [db-path (config/db-path)]
    (db/with-connection
      db-path
      (fn [conn]
        (if-let [plan-data (plan/get-by-id conn id)]
          (let [tasks (task/get-by-plan conn id)
                facts (fact/get-by-plan conn id)
                output-file (or file (str (:name plan-data) ".md"))]
            (md-v2/write-plan-to-file output-file plan-data tasks facts)
            (pprint/pprint {:status :success
                            :plan-name (:name plan-data)
                            :file output-file}))
          (do (pprint/pprint {:status :error
                              :message (str "Plan with ID " id " not found")})
              (System/exit 1)))))))

(defn plan-import
  "Import a plan from a markdown file with upsert semantics.
   If a plan with the same name exists, it will be updated.
   Tasks and facts are matched by name and updated, created, or deleted as needed.
   Uses hierarchical YAML format (v2) with nested tasks and facts lists."
  [{:keys [file preview]}]
  (let [db-path (config/db-path)]
    (if-not (.exists ^java.io.File (java.io.File. ^String file))
      (do (pprint/pprint {:status :error
                          :message (str "File not found: " file)})
          (System/exit 1))
      (let [content (slurp file)]
        (if-not (md-v2/valid-plan-markdown? content)
          (do (pprint/pprint {:status :error
                              :message "Invalid plan markdown file"})
              (System/exit 1))
          (let [data (md-v2/read-plan-from-file file)]
            (db/with-connection
              db-path
              (fn [conn]
                (if preview
                  ;; Preview mode - show what would happen
                  (let [preview-data (import/preview-import conn data)]
                    (pprint/pprint {:status :preview
                                    :plan-name (:plan-name preview-data)
                                    :plan-exists? (:plan-exists? preview-data)
                                    :tasks {:create (get-in preview-data [:tasks :create])
                                            :update (get-in preview-data [:tasks :update])
                                            :delete (get-in preview-data [:tasks :delete])}
                                    :facts {:create (get-in preview-data [:facts :create])
                                            :update (get-in preview-data [:facts :update])
                                            :delete (get-in preview-data [:facts :delete])}}))
                  ;; Import mode - perform the import
                  (let [plan-existed? (some? (plan/get-by-name conn (get-in data [:plan :name])))
                        result (import/import-plan conn data)]
                    (pprint/pprint {:status :success
                                    :plan-name (:name result)
                                    :plan-id (:id result)
                                    :updated-existing? plan-existed?
                                    :tasks-imported (:tasks-imported result)
                                    :tasks-deleted (:tasks-deleted result)
                                    :facts-imported (:facts-imported result)
                                    :facts-deleted (:facts-deleted result)})))))))))))

(defn config-show
  "Display the current configuration"
  [_]
  (pprint/pprint {:config-file config/config-file
                  :config (config/load-config)}))

(defn new-plan-file
  "Create a new plan file for LLM collaboration.
   Generates a markdown file with a template structure."
  [{:keys [name file description]}]
  (let [plan-name (or name "new-plan")
        filename (or file (str plan-name ".md"))
        ^java.io.File f (java.io.File. ^String filename)]
    (when (.exists f)
      (pprint/pprint {:status :error
                      :message (str "File already exists: " filename)})
      (System/exit 1))
    (let [template-content (str "# " plan-name "\n\n"
                                "## Overview\n\n"
                                "Describe what you want to accomplish with this plan.\n\n"
                                "## Goals\n\n"
                                "- Goal 1\n"
                                "- Goal 2\n"
                                "- Goal 3\n\n"
                                "## Context\n\n"
                                "Add any relevant context, background information, or constraints here.\n\n"
                                "## Approach\n\n"
                                "Outline your proposed approach or any specific requirements.\n\n"
                                "---\n\n"
                                "## Discussion\n\n"
                                "Use this section for back-and-forth with the LLM.\n\n"
                                "### Initial Request\n\n"
                                "Your initial request or question to the LLM goes here.\n\n"
                                "### Response\n\n"
                                "LLM responses will be added here during iteration.")
          plan-data {:name plan-name
                     :description (or description "A plan for LLM collaboration")
                     :content template-content
                     :completed false}
          markdown-content (md-v2/plan->markdown plan-data [] [])]
      (spit filename markdown-content)
      (pprint/pprint {:status :success
                      :file filename
                      :plan-name plan-name
                      :next-steps [(str "Edit " filename " to fill in your goals and context")
                                   (str "Import the plan: plan plan import -f " filename)
                                   "Start iterating with your LLM assistant"]}))))

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
       :description "Import a plan from a markdown file with upsert semantics"
       :opts [{:as "Input file" :option "file" :short "f" :type :string :required true}
              {:as "Preview changes without importing" :option "preview" :short "p" :type :with-flag :default false}]
       :runs plan-import}]},
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
    {:command "markdown"
     :description "Parse a markdown file and display its contents"
     :opts [{:as "Markdown file to parse" :option "file" :short "f" :type :string}]
     :runs markdown-cmd}
    {:command "config"
     :description "Show current configuration"
     :runs config-show}
    {:command "new"
     :description "Create a new plan file for LLM collaboration"
     :opts [{:as "Plan name" :option "name" :short "n" :type :string}
            {:as "Output file" :option "file" :short "f" :type :string}
            {:as "Description" :option "description" :short "d" :type :string}]
     :runs new-plan-file}]})

(defn -main [& args]
  (cli/run-cmd args cli-definition))
