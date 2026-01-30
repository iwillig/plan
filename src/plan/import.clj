(ns plan.import
  "Import plans from markdown files with upsert semantics.
   Uses names as identifiers instead of IDs for idempotent imports.
   All import operations are atomic (wrapped in transactions)."
  (:require
   [next.jdbc :as jdbc]
   [plan.models.fact :as fact]
   [plan.models.plan :as plan]
   [plan.models.task :as task]
   [plan.serializers.markdown-v2 :as md]))

(set! *warn-on-reflection* true)

;; -----------------------------------------------------------------------------
;; Import Functions

(defn import-plan
  "Import a plan from parsed markdown data with upsert semantics.
   All operations are performed within a transaction for atomicity.
   - If a plan with the same name exists, updates it
   - Otherwise creates a new plan
   - Tasks and facts are matched by name within the plan
   - Tasks/facts in the database but not in the import are deleted
   Returns the imported plan with :tasks and :facts counts.

   Example:
   (import-plan conn {:plan {:name \"Project\" ...}
                      :tasks [...]
                      :facts [...]})
   ; => {:id 1 :name \"Project\" :tasks-imported 3 :facts-imported 2}"
  [conn {:keys [plan tasks facts]}]
  (jdbc/with-transaction [tx conn]
    (let [plan-name (:name plan)]
      ;; Upsert the plan
      (plan/upsert tx plan)
      ;; Get the plan (now it definitely exists)
      (let [db-plan (plan/get-by-name tx plan-name)
            plan-id (:id db-plan)
            ;; Upsert tasks
            task-names (mapv :name tasks)
            _ (doseq [task tasks]
                (task/upsert tx plan-id task))
            ;; Delete orphaned tasks
            tasks-deleted (task/delete-orphans tx plan-id task-names)
            ;; Upsert facts
            fact-names (mapv :name facts)
            _ (doseq [f facts]
                (fact/upsert tx plan-id f))
            ;; Delete orphaned facts
            facts-deleted (fact/delete-orphans tx plan-id fact-names)]
        (assoc db-plan
               :tasks-imported (count tasks)
               :tasks-deleted tasks-deleted
               :facts-imported (count facts)
               :facts-deleted facts-deleted)))))

(defn import-from-file
  "Import a plan from a markdown file within a transaction.
   See import-plan for semantics.

   Example:
   (import-from-file conn \"plan.md\")
   ; => {:id 1 :name \"Project\" :tasks-imported 3 :facts-imported 2}"
  [conn filepath]
  (let [data (md/read-plan-from-file filepath)]
    (import-plan conn data)))

(defn import-from-string
  "Import a plan from a markdown string within a transaction.
   See import-plan for semantics.

   Example:
  (import-from-string conn markdown-string)
   ; => {:id 1 :name \"Project\" :tasks-imported 3 :facts-imported 2}"
  [conn markdown-text]
  (let [data (md/markdown->plan markdown-text)]
    (import-plan conn data)))

;; -----------------------------------------------------------------------------
;; Preview Functions

(defn preview-import
  "Preview what would happen during import without making changes.
   Returns a map describing the planned operations.

   Example:
   (preview-import conn {:plan {:name \"Project\"} :tasks [...] :facts [...]})
   ; => {:plan-name \"Project\"
   ;     :plan-exists? true
   ;     :tasks {:create 2 :update 1 :delete 0}
   ;     :facts {:create 1 :update 0 :delete 1}}"
  [conn {:keys [plan tasks facts]}]
  (let [plan-name (:name plan)
        existing-plan (plan/get-by-name conn plan-name)
        plan-id (:id existing-plan)
        existing-tasks (when plan-id
                         (into {} (map (juxt :name identity)
                                       (task/get-by-plan conn plan-id))))
        existing-facts (when plan-id
                         (into {} (map (juxt :name identity)
                                       (fact/get-by-plan conn plan-id))))
        task-names (set (map :name tasks))
        fact-names (set (map :name facts))]
    {:plan-name plan-name
     :plan-exists? (some? existing-plan)
     :tasks {:create (count (remove #(get existing-tasks (:name %)) tasks))
             :update (count (filter #(get existing-tasks (:name %)) tasks))
             :delete (count (remove #(task-names (:name %)) (vals existing-tasks)))}
     :facts {:create (count (remove #(get existing-facts (:name %)) facts))
             :update (count (filter #(get existing-facts (:name %)) facts))
             :delete (count (remove #(fact-names (:name %)) (vals existing-facts)))}}))

(defn preview-import-file
  "Preview import from a file without making changes.

   Example:
   (preview-import-file conn \"plan.md\")"
  [conn filepath]
  (let [data (md/read-plan-from-file filepath)]
    (preview-import conn data)))

(defn preview-import-string
  "Preview import from a string without making changes.

   Example:
   (preview-import-string conn markdown-string)"
  [conn markdown-text]
  (let [data (md/markdown->plan markdown-text)]
    (preview-import conn data)))
