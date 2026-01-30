(ns plan.serializers.markdown-v2
  "Serialize and deserialize plans to/from markdown with hierarchical YAML front matter.
   Uses clj-yaml for proper nested YAML structures with arbitrary depth support.
   Names are used as identifiers instead of IDs for portable, idempotent imports.
   Designed for GraalVM native-image compatibility."
  (:require
   [clj-yaml.core :as yaml]
   [clojure.string :as str]
   [plan.markdown :as md]))

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
;; YAML Generation

(defn- generate-yaml-front-matter
  "Generate YAML front matter from plan data using clj-yaml.
   Excludes database IDs and name (name comes from H1 header).
   Returns a string with --- delimiters."
  [plan tasks facts]
  (let [data (cond-> (select-keys plan [:description :completed])
               (seq tasks) (assoc :tasks (mapv #(select-keys % [:name :description
                                                                :content :completed])
                                               tasks))
               (seq facts) (assoc :facts (mapv #(select-keys % [:name :description
                                                                :content])
                                               facts)))]
    (str "---\n"
         (yaml/generate-string data)
         "---")))

;; -----------------------------------------------------------------------------
;; Serialization: Plan -> Markdown

(defn plan->markdown
  "Serialize a plan with its tasks and facts to a markdown document.
   Returns a string with YAML front matter and markdown content.
   Uses hierarchical YAML structure with nested tasks and facts lists.
   Database IDs are not included - names serve as identifiers.

   Example:
   (plan->markdown
     {:name \"Project Launch\"
      :description \"Launching our product\"
      :content \"# Launch Plan\"
      :completed false}
     [{:name \"Design\" :completed true}
      {:name \"Develop\" :completed false}]
     [{:name \"Deadline\" :content \"March 15\"}])"
  [plan tasks facts]
  (let [yaml-front-matter (generate-yaml-front-matter plan tasks facts)
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
      (str yaml-front-matter "\n\n" content-with-h1))))

;; -----------------------------------------------------------------------------
;; Deserialization: Markdown -> Plan

(defn markdown->plan
  "Deserialize a markdown document to plan data.
   Returns a map with :plan, :tasks, :facts keys.

   Example:
   (markdown->plan markdown-string)
   ; => {:plan {:name \"Project\" ...}
   ;     :tasks [...]
   ;     :facts [...]}"
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
                             body-content)]
    {:plan (assoc (select-keys front-matter [:description :completed])
                  :name name
                  :content content-without-h1)
     :tasks (vec (or (:tasks front-matter) []))
     :facts (vec (or (:facts front-matter) []))}))

;; -----------------------------------------------------------------------------
;; File I/O

(defn write-plan-to-file
  "Write a plan to a markdown file using the hierarchical format.

   Example:
   (write-plan-to-file \"plan.md\" plan tasks facts)"
  [filepath plan tasks facts]
  (spit filepath (plan->markdown plan tasks facts)))

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
