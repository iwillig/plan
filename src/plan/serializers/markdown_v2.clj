(ns plan.serializers.markdown-v2
  "Serialize and deserialize plans to/from markdown with hierarchical YAML front matter.
   Uses clj-yaml for proper nested YAML structures with arbitrary depth support.
   Names are used as identifiers instead of IDs for portable, idempotent imports.
   Designed for GraalVM native-image compatibility.

   Format v3 adds:
   - format_version field
   - Task status (pending, in_progress, completed, failed, blocked, skipped)
   - Task priority
   - Task acceptance_criteria
   - Task dependencies (blocked_by, blocks lists)"
  (:require
   [clj-yaml.core :as yaml]
   [clojure.string :as str]
   [plan.markdown :as md]))

(def format-version 3)

(set! *warn-on-reflection* true)

;; -----------------------------------------------------------------------------
;; H1 Header Extraction

(defn- extract-h1-title
  "Extract the first H1 header (# Title) from markdown content.
   Returns the title text or nil if no H1 found."
  [content]
  (when content
    (let [lines (str/split-lines content)]
      (some (fn [line]
              (when-let [match (re-matches #"^#\s+(.+)$" (str/trim line))]
                (second match)))
            lines))))

(defn- remove-h1-header
  "Remove the first H1 header from markdown content.
   Returns content without the H1 line."
  [content]
  (when content
    (let [lines (str/split-lines content)
          h1-index (first (keep-indexed (fn [idx line]
                                          (when (re-matches #"^#\s+.+$" (str/trim line))
                                            idx))
                                        lines))]
      (if h1-index
        (str/trim (str/join "\n" (concat (take h1-index lines)
                                         (drop (inc h1-index) lines))))
        content))))

;; -----------------------------------------------------------------------------
;; Task Dependency Helpers

(defn- build-task-name-map
  "Build a map from task id to task name."
  [tasks]
  (into {} (map (juxt :id :name) tasks)))

(defn- task->yaml-map
  "Convert a task to a YAML-serializable map.
   Includes v3 fields: status, priority, acceptance_criteria.
   blocked_by and blocks are computed from dependencies."
  [task blocked-by-names blocks-names]
  (let [base (select-keys task [:name :description :content])
        ;; Include both completed (for v2 compat) and status (for v3)
        base (if (:completed task)
               (assoc base :completed true)
               base)
        base (if-let [status (:status task)]
               (assoc base :status status)
               base)
        base (if-let [priority (:priority task)]
               (when (not= priority 100) ;; Only include if non-default
                 (assoc base :priority priority))
               base)
        base (or base {})
        base (if-let [criteria (:acceptance_criteria task)]
               (assoc base :acceptance_criteria criteria)
               base)
        base (if (seq blocked-by-names)
               (assoc base :blocked_by (vec blocked-by-names))
               base)
        base (if (seq blocks-names)
               (assoc base :blocks (vec blocks-names))
               base)]
    base))

;; -----------------------------------------------------------------------------
;; YAML Generation

(defn- generate-yaml-front-matter
  "Generate YAML front matter from plan data using clj-yaml.
   Excludes database IDs and name (name comes from H1 header).
   Returns a string with --- delimiters.
   Optional dependencies map: {task-id {:blocked-by [task-ids] :blocks [task-ids]}}"
  ([plan tasks facts]
   (generate-yaml-front-matter plan tasks facts {}))
  ([plan tasks facts dependencies]
   (let [task-name-map (build-task-name-map tasks)
         tasks-yaml (when (seq tasks)
                      (mapv (fn [task]
                              (let [task-id (:id task)
                                    blocked-by-ids (get-in dependencies [task-id :blocked-by] [])
                                    blocks-ids (get-in dependencies [task-id :blocks] [])
                                    blocked-by-names (keep task-name-map blocked-by-ids)
                                    blocks-names (keep task-name-map blocks-ids)]
                                (task->yaml-map task blocked-by-names blocks-names)))
                            tasks))
         data (cond-> {:format_version format-version}
                (:description plan) (assoc :description (:description plan))
                (some? (:completed plan)) (assoc :completed (:completed plan))
                (seq tasks-yaml) (assoc :tasks tasks-yaml)
                (seq facts) (assoc :facts (mapv #(select-keys % [:name :description :content])
                                                facts)))]
     (str "---\n"
          (yaml/generate-string data)
          "---"))))

;; -----------------------------------------------------------------------------
;; Serialization: Plan -> Markdown

(defn plan->markdown
  "Serialize a plan with its tasks and facts to a markdown document.
   Returns a string with YAML front matter and markdown content.
   Uses hierarchical YAML structure with nested tasks and facts lists.
   Database IDs are not included - names serve as identifiers.

   Optional dependencies map: {task-id {:blocked-by [task-ids] :blocks [task-ids]}}

   Example:
   (plan->markdown
     {:name \"Project Launch\"
      :description \"Launching our product\"
      :content \"# Launch Plan\"
      :completed false}
     [{:name \"Design\" :completed true}
      {:name \"Develop\" :completed false}]
     [{:name \"Deadline\" :content \"March 15\"}])"
  ([plan tasks facts]
   (plan->markdown plan tasks facts {}))
  ([plan tasks facts dependencies]
   (let [yaml-front-matter (generate-yaml-front-matter plan tasks facts dependencies)
         name (:name plan)
         content (or (:content plan) "")
         ;; Ensure content starts with H1 header for plan name
         content-with-h1 (if (and name (not (str/blank? name)))
                           (if (re-find #"^#\s+" content)
                             content
                             (str "# " name "\n\n" content))
                           content)]
     (if (str/blank? content-with-h1)
       yaml-front-matter
       (str yaml-front-matter "\n\n" content-with-h1)))))

;; -----------------------------------------------------------------------------
;; Task Parsing Helpers

(defn- normalize-task
  "Normalize a task from YAML, handling v2/v3 format differences.
   - v2: has completed boolean only
   - v3: has status, priority, acceptance_criteria, blocked_by, blocks
   For backwards compatibility, if status is missing, derive from completed."
  [task]
  (let [status (or (:status task)
                   (if (:completed task) "completed" "pending"))
        priority (or (:priority task) 100)]
    (-> task
        (assoc :status status)
        (assoc :priority priority)
        ;; Ensure completed is consistent with status
        (assoc :completed (= status "completed")))))

;; -----------------------------------------------------------------------------
;; Deserialization: Markdown -> Plan

(defn markdown->plan
  "Deserialize a markdown document to plan data.
   Returns a map with :plan, :tasks, :facts, :dependencies keys.
   Dependencies is a list of {:from task-name :to task-name} where 'from' blocks 'to'.

   Supports both v2 and v3 formats:
   - v2: completed boolean on tasks
   - v3: status, priority, acceptance_criteria, blocked_by, blocks

   Example:
   (markdown->plan markdown-string)
   ; => {:plan {:name \"Project\" ...}
   ;     :tasks [...]
   ;     :facts [...]
   ;     :dependencies [{:from \"task1\" :to \"task2\"} ...]}"
  [^String markdown-text]
  (let [result (md/parse-with-front-matter markdown-text)
        front-matter (:front-matter result)
        body-content (:body result)
        ;; Extract name from H1 header, fallback to front-matter name for backwards compat
        h1-name (extract-h1-title body-content)
        name (or h1-name (:name front-matter))
        ;; Remove H1 from content since it's stored as name
        content-without-h1 (if h1-name
                             (remove-h1-header body-content)
                             body-content)
        ;; Parse tasks with normalization
        raw-tasks (or (:tasks front-matter) [])
        tasks (mapv normalize-task raw-tasks)
        ;; Extract dependencies from blocked_by and blocks fields
        dependencies (reduce
                      (fn [deps task]
                        (let [task-name (:name task)
                              blocked-by (or (:blocked_by task) [])
                              blocks (or (:blocks task) [])]
                          (concat deps
                                  ;; blocked_by: other task blocks this task
                                  (map (fn [blocker] {:from blocker :to task-name}) blocked-by)
                                  ;; blocks: this task blocks other tasks
                                  (map (fn [blocked] {:from task-name :to blocked}) blocks))))
                      []
                      tasks)
        ;; Remove dependency fields from tasks (they're stored separately)
        tasks (mapv #(dissoc % :blocked_by :blocks) tasks)]
    {:plan (assoc (select-keys front-matter [:description :completed])
                  :name name
                  :content content-without-h1)
     :tasks tasks
     :facts (vec (or (:facts front-matter) []))
     :dependencies (vec (distinct dependencies))
     :format_version (or (:format_version front-matter) 2)}))

;; -----------------------------------------------------------------------------
;; File I/O

(defn write-plan-to-file
  "Write a plan to a markdown file using the hierarchical format.
   Optional dependencies map: {task-id {:blocked-by [task-ids] :blocks [task-ids]}}

   Example:
   (write-plan-to-file \"plan.md\" plan tasks facts)
   (write-plan-to-file \"plan.md\" plan tasks facts dependencies)"
  ([filepath plan tasks facts]
   (write-plan-to-file filepath plan tasks facts {}))
  ([filepath plan tasks facts dependencies]
   (spit filepath (plan->markdown plan tasks facts dependencies))))

(defn read-plan-from-file
  "Read a plan from a markdown file.

   Example:
   (read-plan-from-file \"plan.md\")
   ; => {:plan {...} :tasks [...] :facts [...]}"
  [filepath]
  (markdown->plan (slurp filepath)))

;; -----------------------------------------------------------------------------
;; Validation

(defn valid-plan-markdown?
  "Check if a markdown string contains valid plan data.
   Returns true if the content has an H1 header or front matter has a name.

   Example:
   (valid-plan-markdown? markdown-string) ; => true or false"
  [markdown-text]
  (try
    (let [result (md/parse-with-front-matter markdown-text)
          front-matter (:front-matter result)
          body-content (:body result)
          ;; Check for H1 header in content
          h1-name (extract-h1-title body-content)]
      (and (map? front-matter)
           (or (and (string? h1-name) (seq h1-name))
               (and (string? (:name front-matter)) (seq (:name front-matter))))))
    (catch Exception _
      false)))

;; -----------------------------------------------------------------------------
;; Metadata Helpers

(defn get-plan-metadata
  "Extract just the plan metadata from markdown (without tasks/facts).

   Example:
   (get-plan-metadata markdown-string)
   ; => {:name \"Project\" :description \"...\" :content \"...\" ...}"
  [markdown-text]
  (let [result (md/parse-with-front-matter markdown-text)
        front-matter (:front-matter result)
        body-content (:body result)
        h1-name (extract-h1-title body-content)
        name (or h1-name (:name front-matter))
        content-without-h1 (if h1-name
                             (remove-h1-header body-content)
                             body-content)]
    (assoc (select-keys front-matter [:description :completed])
           :name name
           :content content-without-h1)))

(defn count-tasks
  "Count the number of tasks in the markdown.

   Example:
   (count-tasks markdown-string) ; => 3"
  [markdown-text]
  (let [result (md/parse-with-front-matter markdown-text)
        front-matter (:front-matter result)]
    (count (or (:tasks front-matter) []))))

(defn count-facts
  "Count the number of facts in the markdown.

   Example:
   (count-facts markdown-string) ; => 2"
  [markdown-text]
  (let [result (md/parse-with-front-matter markdown-text)
        front-matter (:front-matter result)]
    (count (or (:facts front-matter) []))))
