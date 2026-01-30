(ns plan.main
  (:gen-class)
  (:require
   [cli-matic.core :as cli]
   [clojure-mcp.core :as mcp-core]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [plan.config :as config]
   [plan.db :as db]
   [plan.import :as import]
   [plan.markdown :as markdown]
   [plan.mcp-server :as mcp]
   [plan.models.fact :as fact]
   [plan.models.lesson :as lesson]
   [plan.models.plan :as plan]
   [plan.models.task :as task]
   [plan.models.trace :as trace]
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
    [:status :text [:default "pending"]]
    [:priority :integer [:default 100]]
    [:acceptance_criteria :text]
    [:description :text]
    [:content :text]
    [:parent_id :integer]
    [:status_changed_at :datetime]
    [:created_at :datetime [:default :current_timestamp]]
    [:updated_at :datetime [:default :current_timestamp]]]})

(def tasks-unique-constraint
  [:raw "CREATE UNIQUE INDEX IF NOT EXISTS idx_tasks_plan_name ON tasks(plan_id, name)"])

(def tasks-status-check
  [:raw "CREATE TRIGGER IF NOT EXISTS tasks_status_check
         BEFORE INSERT ON tasks
         WHEN NEW.status NOT IN ('pending', 'in_progress', 'completed', 'failed', 'blocked', 'skipped')
         BEGIN
           SELECT RAISE(ABORT, 'Invalid task status');
         END"])

(def tasks-status-update-check
  [:raw "CREATE TRIGGER IF NOT EXISTS tasks_status_update_check
         BEFORE UPDATE OF status ON tasks
         WHEN NEW.status NOT IN ('pending', 'in_progress', 'completed', 'failed', 'blocked', 'skipped')
         BEGIN
           SELECT RAISE(ABORT, 'Invalid task status');
         END"])

(def task-dependencies-table
  {:create-table [:task_dependencies :if-not-exists]
   :with-columns
   [[:id :integer :primary-key :autoincrement]
    [:task_id :integer [:not nil]]
    [:blocks_task_id :integer [:not nil]]
    [:dependency_type :text [:default "blocks"]]]})

(def task-dependencies-unique-constraint
  [:raw "CREATE UNIQUE INDEX IF NOT EXISTS idx_task_deps_unique ON task_dependencies(task_id, blocks_task_id)"])

(def idx-task-deps-task-id
  {:create-index [[:idx_task_deps_task_id :if-not-exists] [:task_dependencies :task_id]]})

(def idx-task-deps-blocks-task-id
  {:create-index [[:idx_task_deps_blocks_task_id :if-not-exists] [:task_dependencies :blocks_task_id]]})

;; Traces table for ReAct-style reasoning
(def traces-table
  {:create-table [:traces :if-not-exists]
   :with-columns
   [[:id :integer :primary-key :autoincrement]
    [:plan_id :integer [:not nil]]
    [:task_id :integer]
    [:trace_type :text [:not nil]]
    [:sequence_num :integer [:not nil]]
    [:content :text [:not nil]]
    [:metadata :text]
    [:created_at :datetime [:default :current_timestamp]]]})

(def traces-type-check
  [:raw "CREATE TRIGGER IF NOT EXISTS traces_type_check
         BEFORE INSERT ON traces
         WHEN NEW.trace_type NOT IN ('thought', 'action', 'observation', 'reflection')
         BEGIN
           SELECT RAISE(ABORT, 'Invalid trace type');
         END"])

(def idx-traces-plan-id
  {:create-index [[:idx_traces_plan_id :if-not-exists] [:traces :plan_id]]})

(def idx-traces-task-id
  {:create-index [[:idx_traces_task_id :if-not-exists] [:traces :task_id]]})

;; FTS for traces
(def traces-fts-table
  [:raw "CREATE VIRTUAL TABLE IF NOT EXISTS traces_fts USING fts5(content, content='traces', content_rowid='id')"])

(def traces-fts-ai-trigger
  [:raw "CREATE TRIGGER IF NOT EXISTS traces_ai AFTER INSERT ON traces BEGIN
           INSERT INTO traces_fts(rowid, content) VALUES (new.id, new.content);
         END"])

(def traces-fts-ad-trigger
  [:raw "CREATE TRIGGER IF NOT EXISTS traces_ad AFTER DELETE ON traces BEGIN
           INSERT INTO traces_fts(traces_fts, rowid, content) VALUES('delete', old.id, old.content);
         END"])

(def traces-fts-au-trigger
  [:raw "CREATE TRIGGER IF NOT EXISTS traces_au AFTER UPDATE ON traces BEGIN
           INSERT INTO traces_fts(traces_fts, rowid, content) VALUES('delete', old.id, old.content);
           INSERT INTO traces_fts(rowid, content) VALUES (new.id, new.content);
         END"])

;; Lessons table for Reflexion-style learning
(def lessons-table
  {:create-table [:lessons :if-not-exists]
   :with-columns
   [[:id :integer :primary-key :autoincrement]
    [:plan_id :integer]
    [:task_id :integer]
    [:lesson_type :text [:not nil]]
    [:trigger_condition :text]
    [:lesson_content :text [:not nil]]
    [:confidence :real [:default 0.5]]
    [:times_validated :integer [:default 0]]
    [:created_at :datetime [:default :current_timestamp]]]})

(def lessons-type-check
  [:raw "CREATE TRIGGER IF NOT EXISTS lessons_type_check
         BEFORE INSERT ON lessons
         WHEN NEW.lesson_type NOT IN ('success_pattern', 'failure_pattern', 'constraint', 'technique')
         BEGIN
           SELECT RAISE(ABORT, 'Invalid lesson type');
         END"])

(def idx-lessons-plan-id
  {:create-index [[:idx_lessons_plan_id :if-not-exists] [:lessons :plan_id]]})

(def idx-lessons-task-id
  {:create-index [[:idx_lessons_task_id :if-not-exists] [:lessons :task_id]]})

(def idx-lessons-type
  {:create-index [[:idx_lessons_type :if-not-exists] [:lessons :lesson_type]]})

;; FTS for lessons
(def lessons-fts-table
  [:raw "CREATE VIRTUAL TABLE IF NOT EXISTS lessons_fts USING fts5(trigger_condition, lesson_content, content='lessons', content_rowid='id')"])

(def lessons-fts-ai-trigger
  [:raw "CREATE TRIGGER IF NOT EXISTS lessons_ai AFTER INSERT ON lessons BEGIN
           INSERT INTO lessons_fts(rowid, trigger_condition, lesson_content) VALUES (new.id, new.trigger_condition, new.lesson_content);
         END"])

(def lessons-fts-ad-trigger
  [:raw "CREATE TRIGGER IF NOT EXISTS lessons_ad AFTER DELETE ON lessons BEGIN
           INSERT INTO lessons_fts(lessons_fts, rowid, trigger_condition, lesson_content) VALUES('delete', old.id, old.trigger_condition, old.lesson_content);
         END"])

(def lessons-fts-au-trigger
  [:raw "CREATE TRIGGER IF NOT EXISTS lessons_au AFTER UPDATE ON lessons BEGIN
           INSERT INTO lessons_fts(lessons_fts, rowid, trigger_condition, lesson_content) VALUES('delete', old.id, old.trigger_condition, old.lesson_content);
           INSERT INTO lessons_fts(rowid, trigger_condition, lesson_content) VALUES (new.id, new.trigger_condition, new.lesson_content);
         END"])

;; Fact-Task Links table
(def fact-task-links-table
  {:create-table [:fact_task_links :if-not-exists]
   :with-columns
   [[:id :integer :primary-key :autoincrement]
    [:fact_id :integer [:not nil]]
    [:task_id :integer [:not nil]]
    [:link_type :text [:not nil]]]})

(def fact-task-links-unique-constraint
  [:raw "CREATE UNIQUE INDEX IF NOT EXISTS idx_fact_task_links_unique ON fact_task_links(fact_id, task_id, link_type)"])

(def fact-task-links-type-check
  [:raw "CREATE TRIGGER IF NOT EXISTS fact_task_links_type_check
         BEFORE INSERT ON fact_task_links
         WHEN NEW.link_type NOT IN ('informs', 'discovered_during', 'blocks', 'required_context')
         BEGIN
           SELECT RAISE(ABORT, 'Invalid link type');
         END"])

(def idx-fact-task-links-fact-id
  {:create-index [[:idx_fact_task_links_fact_id :if-not-exists] [:fact_task_links :fact_id]]})

(def idx-fact-task-links-task-id
  {:create-index [[:idx_fact_task_links_task_id :if-not-exists] [:fact_task_links :task_id]]})

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
  (db/execute! conn task-dependencies-table)
  (db/execute! conn traces-table)
  (db/execute! conn lessons-table)
  (db/execute! conn fact-task-links-table)
  ;; Indexes
  (db/execute! conn idx-tasks-plan-id)
  (db/execute! conn idx-tasks-parent-id)
  (db/execute! conn idx-facts-plan-id)
  (db/execute! conn idx-task-deps-task-id)
  (db/execute! conn idx-task-deps-blocks-task-id)
  (db/execute! conn idx-traces-plan-id)
  (db/execute! conn idx-traces-task-id)
  (db/execute! conn idx-lessons-plan-id)
  (db/execute! conn idx-lessons-task-id)
  (db/execute! conn idx-lessons-type)
  (db/execute! conn idx-fact-task-links-fact-id)
  (db/execute! conn idx-fact-task-links-task-id)
  ;; Unique constraints (for name uniqueness within plan scope)
  (db/execute! conn tasks-unique-constraint)
  (db/execute! conn facts-unique-constraint)
  (db/execute! conn task-dependencies-unique-constraint)
  (db/execute! conn fact-task-links-unique-constraint)
  ;; Status and type check triggers
  (db/execute! conn tasks-status-check)
  (db/execute! conn tasks-status-update-check)
  (db/execute! conn traces-type-check)
  (db/execute! conn lessons-type-check)
  (db/execute! conn fact-task-links-type-check)
  ;; FTS virtual tables
  (db/execute! conn plans-fts-table)
  (db/execute! conn tasks-fts-table)
  (db/execute! conn facts-fts-table)
  (db/execute! conn traces-fts-table)
  (db/execute! conn lessons-fts-table)
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
  (db/execute! conn traces-fts-ai-trigger)
  (db/execute! conn traces-fts-ad-trigger)
  (db/execute! conn traces-fts-au-trigger)
  (db/execute! conn lessons-fts-ai-trigger)
  (db/execute! conn lessons-fts-ad-trigger)
  (db/execute! conn lessons-fts-au-trigger)
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

(defn task-start
  "Start a task (transition from pending to in_progress)"
  [{:keys [id]}]
  (let [db-path (config/db-path)]
    (db/with-connection
      db-path
      (fn [conn]
        (if-let [result (task/start-task conn id)]
          (pprint/pprint {:status :success :task result})
          (pprint/pprint {:status :error :message "Failed to start task"}))))))

(defn task-complete
  "Complete a task"
  [{:keys [id]}]
  (let [db-path (config/db-path)]
    (db/with-connection
      db-path
      (fn [conn]
        (if-let [result (task/complete-task conn id)]
          (pprint/pprint {:status :success :task result})
          (pprint/pprint {:status :error :message "Failed to complete task"}))))))

(defn task-fail
  "Mark a task as failed"
  [{:keys [id]}]
  (let [db-path (config/db-path)]
    (db/with-connection
      db-path
      (fn [conn]
        (if-let [result (task/fail-task conn id)]
          (pprint/pprint {:status :success :task result})
          (pprint/pprint {:status :error :message "Failed to mark task as failed"}))))))

(defn task-depends
  "Add a dependency: task with --id depends on task with --on"
  [{:keys [id on]}]
  (let [db-path (config/db-path)]
    (db/with-connection
      db-path
      (fn [conn]
        (if (task/has-cycle? conn on id)
          (pprint/pprint {:status :error :message "Adding this dependency would create a cycle"})
          (if (task/add-dependency conn on id)
            (pprint/pprint {:status :success
                            :message (str "Task " id " now depends on task " on)
                            :blocking-task on
                            :blocked-task id})
            (pprint/pprint {:status :error :message "Failed to add dependency"})))))))

(defn task-ready
  "List tasks that are ready to work on (pending with no blockers)"
  [{:keys [plan-id]}]
  (let [db-path (config/db-path)]
    (db/with-connection
      db-path
      (fn [conn]
        (let [ready-tasks (task/get-ready-tasks conn plan-id)]
          (pprint/pprint {:ready-tasks ready-tasks
                          :count (count ready-tasks)}))))))

(defn task-next
  "Get the next task to work on (highest priority ready task)"
  [{:keys [plan-id]}]
  (let [db-path (config/db-path)]
    (db/with-connection
      db-path
      (fn [conn]
        (if-let [next-task (task/get-next-task conn plan-id)]
          (pprint/pprint {:next-task next-task})
          (pprint/pprint {:message "No tasks ready"}))))))

(defn task-show
  "Show a task with its dependencies"
  [{:keys [id]}]
  (let [db-path (config/db-path)]
    (db/with-connection
      db-path
      (fn [conn]
        (if-let [task-data (task/get-by-id conn id)]
          (let [blocking (task/get-blocking-tasks conn id)
                blocked (task/get-blocked-tasks conn id)]
            (pprint/pprint {:task task-data
                            :blocked-by (mapv #(select-keys % [:id :name :status]) blocking)
                            :blocks (mapv #(select-keys % [:id :name :status]) blocked)}))
          (pprint/pprint {:status :error :message (str "Task " id " not found")}))))))

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
  "Export a plan to a markdown file using v3 format."
  [{:keys [id file]}]
  (let [db-path (config/db-path)]
    (db/with-connection
      db-path
      (fn [conn]
        (if-let [plan-data (plan/get-by-id conn id)]
          (let [tasks (task/get-by-plan conn id)
                facts (fact/get-by-plan conn id)
                ;; Build dependencies map {task-id {:blocked-by [...] :blocks [...]}}
                all-deps (task/get-dependencies-for-plan conn id)
                dependencies (reduce
                              (fn [deps {:keys [task_id blocks_task_id]}]
                                (-> deps
                                    (update-in [task_id :blocks] (fnil conj []) blocks_task_id)
                                    (update-in [blocks_task_id :blocked-by] (fnil conj []) task_id)))
                              {}
                              all-deps)
                output-file (or file (str (:name plan-data) ".md"))]
            (md-v2/write-plan-to-file output-file plan-data tasks facts dependencies)
            (pprint/pprint {:status :success
                            :plan-name (:name plan-data)
                            :file output-file
                            :format-version 3}))
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

(defn mcp-server-info
  "Display information about the MCP server capabilities.
   This demonstrates clojure-mcp library is properly loaded."
  [_]
  ;; Access the nrepl-client-atom from clojure-mcp.core to verify library is loaded
  (pprint/pprint {:status "clojure-mcp library loaded"
                  :version "0.1.11"
                  :capabilities [:tools :prompts :resources]
                  :nrepl-atom-exists? (some? mcp-core/nrepl-client-atom)
                  :note "MCP server functionality can be added using clojure-mcp.core/build-and-start-mcp-server"}))

(defn mcp-server-start
  "Start the MCP server for the planning tool.
   This provides a Model Context Protocol interface for LLM agents
   to interact with plans, tasks, and facts."
  [{:keys [port]}]
  (pprint/pprint {:status "Starting MCP server"
                  :port port
                  :note "Use Ctrl+C to stop"})
  ;; Start the MCP server - this blocks until interrupted
  (mcp/start-mcp-server {:port port}))

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

;; Trace commands (ReAct pattern)

(defn trace-add
  "Add a trace entry for task reasoning
   Types: thought, action, observation, reflection"
  [{:keys [task-id type content]}]
  (let [db-path (config/db-path)]
    (db/with-connection
      db-path
      (fn [conn]
        (let [task (task/get-by-id conn task-id)]
          (if-not task
            (do (pprint/pprint {:status :error
                                :message (str "Task " task-id " not found")})
                (System/exit 1))
            (let [plan-id (:plan_id task)
                  seq-num (trace/get-next-sequence conn plan-id)
                  result (trace/create conn {:plan-id plan-id
                                             :task-id task-id
                                             :trace-type type
                                             :sequence-num seq-num
                                             :content content})]
              (pprint/pprint {:status :success
                              :trace-id (:id result)
                              :task-id task-id
                              :type type
                              :sequence seq-num}))))))))

(defn trace-history
  "Show trace history for a task"
  [{:keys [task-id]}]
  (let [db-path (config/db-path)]
    (db/with-connection
      db-path
      (fn [conn]
        (let [task (task/get-by-id conn task-id)
              traces (trace/get-by-task conn task-id)]
          (if-not task
            (do (pprint/pprint {:status :error
                                :message (str "Task " task-id " not found")})
                (System/exit 1))
            (pprint/pprint {:task (select-keys task [:id :name :status])
                            :traces (mapv #(select-keys % [:id :trace_type :sequence_num :content :created_at]) traces)})))))))

;; Lesson commands (Reflexion pattern)

(defn lesson-add
  "Add a lesson from experience
   Types: success_pattern, failure_pattern, constraint, technique"
  [{:keys [plan-id task-id type trigger content]}]
  (let [db-path (config/db-path)]
    (db/with-connection
      db-path
      (fn [conn]
        (let [result (lesson/create conn {:plan-id plan-id
                                          :task-id task-id
                                          :lesson-type type
                                          :trigger-condition trigger
                                          :lesson-content content})]
          (pprint/pprint {:status :success
                          :lesson-id (:id result)
                          :type type
                          :plan-id plan-id
                          :task-id task-id}))))))

(defn lesson-list
  "List lessons with optional filters"
  [{:keys [plan-id min-confidence max-confidence type]}]
  (let [db-path (config/db-path)]
    (db/with-connection
      db-path
      (fn [conn]
        (let [min-conf (when min-confidence (Float/parseFloat min-confidence))
              max-conf (when max-confidence (Float/parseFloat max-confidence))
              ;; Get all lessons or filter by plan
              lessons (if plan-id
                        (lesson/get-by-plan conn plan-id)
                        (lesson/get-all conn))
              ;; Apply filters in Clojure
              filtered (cond->> lessons
                         min-conf (filter #(>= (:confidence %) min-conf))
                         max-conf (filter #(<= (:confidence %) max-conf))
                         type (filter #(= (:lesson_type %) type)))]
          (pprint/pprint {:lessons (mapv #(select-keys % [:id :lesson_type :trigger_condition
                                                          :lesson_content :confidence
                                                          :times_validated :created_at])
                                         filtered)
                          :count (count filtered)}))))))

(defn lesson-search
  "Search lessons by content"
  [{:keys [query]}]
  (let [db-path (config/db-path)]
    (db/with-connection
      db-path
      (fn [conn]
        (let [results (lesson/search conn query)]
          (pprint/pprint {:query query
                          :lessons (mapv #(select-keys % [:id :lesson_type :trigger_condition
                                                          :lesson_content :confidence])
                                         results)
                          :count (count results)}))))))

(defn lesson-validate
  "Increase confidence in a lesson"
  [{:keys [id]}]
  (let [db-path (config/db-path)]
    (db/with-connection
      db-path
      (fn [conn]
        (lesson/validate conn id)
        (if-let [lesson (lesson/get-by-id conn id)]
          (pprint/pprint {:status :validated
                          :lesson-id id
                          :confidence (:confidence lesson)
                          :times-validated (:times_validated lesson)})
          (pprint/pprint {:status :error
                          :message (str "Lesson " id " not found")}))))))

(defn lesson-invalidate
  "Decrease confidence in a lesson"
  [{:keys [id]}]
  (let [db-path (config/db-path)]
    (db/with-connection
      db-path
      (fn [conn]
        (lesson/invalidate conn id)
        (if-let [lesson (lesson/get-by-id conn id)]
          (pprint/pprint {:status :invalidated
                          :lesson-id id
                          :confidence (:confidence lesson)})
          (pprint/pprint {:status :error
                          :message (str "Lesson " id " not found")}))))))

(defn lesson-delete
  "Delete a lesson"
  [{:keys [id]}]
  (let [db-path (config/db-path)]
    (db/with-connection
      db-path
      (fn [conn]
        (lesson/delete conn id)
        (pprint/pprint {:deleted {:lesson id}})))))

;; Fact commands

(defn fact-create
  "Create a new fact for a plan"
  [{:keys [plan-id name description content]}]
  (let [db-path (config/db-path)]
    (db/with-connection
      db-path
      (fn [conn]
        (let [result (fact/create conn plan-id name description content)]
          (pprint/pprint {:status :success
                          :fact-id (:id result)
                          :plan-id plan-id
                          :name name}))))))

(defn fact-list
  "List facts for a plan"
  [{:keys [plan-id]}]
  (let [db-path (config/db-path)]
    (db/with-connection
      db-path
      (fn [conn]
        (let [facts (fact/get-by-plan conn plan-id)]
          (pprint/pprint {:facts (mapv #(select-keys % [:id :name :description]) facts)
                          :count (count facts)}))))))

(defn fact-show
  "Show a fact with full content"
  [{:keys [id]}]
  (let [db-path (config/db-path)]
    (db/with-connection
      db-path
      (fn [conn]
        (if-let [fact-data (fact/get-by-id conn id)]
          (pprint/pprint fact-data)
          (pprint/pprint {:status :error
                          :message (str "Fact " id " not found")}))))))

(defn fact-update
  "Update a fact"
  [{:keys [id name description content]}]
  (let [db-path (config/db-path)]
    (db/with-connection
      db-path
      (fn [conn]
        (let [result (fact/update conn id {:name name
                                           :description description
                                           :content content})]
          (if result
            (pprint/pprint {:status :success
                            :fact-id id
                            :updated (keys (select-keys {:name name :description description :content content}
                                                        [:name :description :content]))})
            (pprint/pprint {:status :error
                            :message (str "Fact " id " not found")})))))))

(defn fact-delete
  "Delete a fact"
  [{:keys [id]}]
  (let [db-path (config/db-path)]
    (db/with-connection
      db-path
      (fn [conn]
        (fact/delete conn id)
        (pprint/pprint {:deleted {:fact id}})))))

;; CLI definition

(defn load-description
  "Load the CLI description from a resource file."
  []
  (if-let [resource (io/resource "description.txt")]
    (slurp resource)
    "A planning tool for LLM Agents"))

(def cli-definition
  {:app-name "plan"
   :version "0.1.0"
   :description (load-description)
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
       :runs task-delete}
      {:command "show"
       :description "Show a task with its dependencies"
       :opts [{:as "Task ID" :option "id" :required true :type :int}]
       :runs task-show}
      {:command "start"
       :description "Start a task (pending -> in_progress)"
       :opts [{:as "Task ID" :option "id" :required true :type :int}]
       :runs task-start}
      {:command "complete"
       :description "Complete a task"
       :opts [{:as "Task ID" :option "id" :required true :type :int}]
       :runs task-complete}
      {:command "fail"
       :description "Mark a task as failed"
       :opts [{:as "Task ID" :option "id" :required true :type :int}]
       :runs task-fail}
      {:command "depends"
       :description "Add a dependency: task --id depends on task --on"
       :opts [{:as "Task ID (depends)" :option "id" :required true :type :int}
              {:as "Blocking Task ID" :option "on" :required true :type :int}]
       :runs task-depends}
      {:command "ready"
       :description "List tasks ready to work on (pending with no blockers)"
       :opts [{:as "Plan ID" :option "plan-id" :required true :type :int}]
       :runs task-ready}
      {:command "next"
       :description "Get the next task to work on (highest priority ready task)"
       :opts [{:as "Plan ID" :option "plan-id" :required true :type :int}]
       :runs task-next}]}
    {:command "trace"
     :description "ReAct reasoning traces"
     :subcommands
     [{:command "add"
       :description "Add a trace entry (thought, action, observation, reflection)"
       :opts [{:as "Task ID" :option "task-id" :required true :type :int}
              {:as "Trace type" :option "type" :required true :type :string}
              {:as "Content" :option "content" :short "c" :type :string :required true}]
       :runs trace-add}
      {:command "history"
       :description "Show trace history for a task"
       :opts [{:as "Task ID" :option "task-id" :required true :type :int}]
       :runs trace-history}]}
    {:command "lesson"
     :description "Reflexion learning from experience"
     :subcommands
     [{:command "add"
       :description "Add a lesson (success_pattern, failure_pattern, constraint, technique)"
       :opts [{:as "Lesson type" :option "type" :required true :type :string}
              {:as "Plan ID (optional)" :option "plan-id" :type :int}
              {:as "Task ID (optional)" :option "task-id" :type :int}
              {:as "Trigger condition" :option "trigger" :type :string}
              {:as "Lesson content" :option "content" :short "c" :type :string :required true}]
       :runs lesson-add}
      {:command "list"
       :description "List lessons with optional filters"
       :opts [{:as "Plan ID" :option "plan-id" :type :int}
              {:as "Minimum confidence (0.0-1.0)" :option "min-confidence" :type :string}
              {:as "Maximum confidence (0.0-1.0)" :option "max-confidence" :type :string}
              {:as "Lesson type filter" :option "type" :type :string}]
       :runs lesson-list}
      {:command "search"
       :description "Search lessons by content"
       :opts [{:as "Search query" :option "query" :short "q" :type :string :required true}]
       :runs lesson-search}
      {:command "validate"
       :description "Increase confidence in a lesson"
       :opts [{:as "Lesson ID" :option "id" :required true :type :int}]
       :runs lesson-validate}
      {:command "invalidate"
       :description "Decrease confidence in a lesson"
       :opts [{:as "Lesson ID" :option "id" :required true :type :int}]
       :runs lesson-invalidate}
      {:command "delete"
       :description "Delete a lesson"
       :opts [{:as "Lesson ID" :option "id" :required true :type :int}]
       :runs lesson-delete}]}
    {:command "fact"
     :description "Plan-specific facts and knowledge"
     :subcommands
     [{:command "create"
       :description "Create a new fact"
       :opts [{:as "Plan ID" :option "plan-id" :required true :type :int}
              {:as "Fact name" :option "name" :short "n" :type :string :required true}
              {:as "Description" :option "description" :short "d" :type :string}
              {:as "Content" :option "content" :short "c" :type :string :required true}]
       :runs fact-create}
      {:command "list"
       :description "List facts for a plan"
       :opts [{:as "Plan ID" :option "plan-id" :required true :type :int}]
       :runs fact-list}
      {:command "show"
       :description "Show a fact with full content"
       :opts [{:as "Fact ID" :option "id" :required true :type :int}]
       :runs fact-show}
      {:command "update"
       :description "Update a fact"
       :opts [{:as "Fact ID" :option "id" :required true :type :int}
              {:as "Name" :option "name" :short "n" :type :string}
              {:as "Description" :option "description" :short "d" :type :string}
              {:as "Content" :option "content" :short "c" :type :string}]
       :runs fact-update}
      {:command "delete"
       :description "Delete a fact"
       :opts [{:as "Fact ID" :option "id" :required true :type :int}]
       :runs fact-delete}]}
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
     :runs new-plan-file}
    {:command "mcp"
     :description "MCP server operations"
     :subcommands
     [{:command "info"
       :description "Display MCP server capabilities and status"
       :runs mcp-server-info}
      {:command "start"
       :description "Start the MCP server (blocks until interrupted)"
       :opts [{:as "nREPL port (optional)" :option "port" :short "p" :type :int}]
       :runs mcp-server-start}]}]})

(defn -main [& args]
  (cli/run-cmd args cli-definition))
