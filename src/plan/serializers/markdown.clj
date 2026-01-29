(ns plan.serializers.markdown
  "Serialize and deserialize plans to/from markdown with YAML front matter.
   Uses flat key-value pairs compatible with CommonMark's YAML front matter extension.
   Designed for GraalVM native-image compatibility."
  (:require
   [clojure.string :as str]
   [plan.markdown :as md])
  (:import
   (org.commonmark.parser
    Parser)))

(set! *warn-on-reflection* true)

;; -----------------------------------------------------------------------------
;; YAML Generation Helpers

(defn- yaml-escape-string
  "Escape a string for YAML. Handles quotes, newlines, and special characters."
  [s]
  (when s
    (cond
      (str/includes? s "\n") (str "|\n" (str/join "\n" (map #(str "  " %) (str/split-lines s))))
      (str/includes? s "\"") (str "'" s "'")
      (str/includes? s "'") (str "\"" s "\"")
      (re-find #"[:#{}\[\],&*!?|>-]" s) (str "\"" s "\"")
      :else s)))

(defn- yaml-value
  "Convert a Clojure value to a YAML string value."
  [v]
  (cond
    (nil? v) "null"
    (boolean? v) (str v)
    (number? v) (str v)
    (string? v) (yaml-escape-string v)
    :else (yaml-escape-string (str v))))

(defn- yaml-line
  "Generate a YAML key: value line."
  [k v]
  (let [yaml-v (yaml-value v)]
    (if (and (string? yaml-v) (str/starts-with? yaml-v "|"))
      (str (name k) ": " yaml-v)
      (str (name k) ": " yaml-v))))

;; -----------------------------------------------------------------------------
;; Flat Key Encoding/Decoding

;; Plan fields use plan_ prefix
;; Task fields use task_N_ prefix where N is the index
;; Fact fields use fact_N_ prefix where N is the index

(defn- encode-plan-fields
  "Encode plan fields with plan_ prefix."
  [plan]
  (let [fields (select-keys plan [:id :name :description :completed :created_at :updated_at])]
    (into {} (map (fn [[k v]]
                    [(keyword (str "plan_" (name k))) v])
                  fields))))

(defn- encode-task-fields
  "Encode task fields with task_N_ prefix."
  [idx task]
  (let [prefix (str "task_" idx "_")
        fields (select-keys task [:id :name :description :content :completed :parent_id :created_at :updated_at])]
    (into {} (map (fn [[k v]]
                    [(keyword (str prefix (name k))) v])
                  fields))))

(defn- encode-fact-fields
  "Encode fact fields with fact_N_ prefix."
  [idx fact]
  (let [prefix (str "fact_" idx "_")
        fields (select-keys fact [:id :name :description :content :created_at :updated_at])]
    (into {} (map (fn [[k v]]
                    [(keyword (str prefix (name k))) v])
                  fields))))

(defn- normalize-yaml-value
  "Normalize a YAML value from CommonMark parser.
   Empty vectors become nil, 'true'/'false' become booleans, 'null' becomes nil."
  [v]
  (cond
    (vector? v) nil
    (= "true" v) true
    (= "false" v) false
    (= "null" v) nil
    :else v))

(defn- decode-plan-fields
  "Decode plan fields from flat keys with plan_ prefix."
  [data]
  (into {} (keep (fn [[k v]]
                   (let [ks (name k)]
                     (when (str/starts-with? ks "plan_")
                       [(keyword (subs ks 5)) (normalize-yaml-value v)])))
                 data)))

(defn- decode-task-fields
  "Decode task fields from flat keys with task_N_ prefix."
  [data]
  (let [task-keys (filter #(str/starts-with? (name %) "task_") (keys data))
        task-indices (set (map #(-> (name %)
                                    (str/split #"_")
                                    second
                                    Integer/parseInt)
                               task-keys))]
    (mapv (fn [idx]
            (let [prefix (str "task_" idx "_")]
              (into {} (keep (fn [[k v]]
                               (let [ks (name k)]
                                 (when (str/starts-with? ks prefix)
                                   [(keyword (subs ks (count prefix))) (normalize-yaml-value v)])))
                             data))))
          (sort task-indices))))

(defn- decode-fact-fields
  "Decode fact fields from flat keys with fact_N_ prefix."
  [data]
  (let [fact-keys (filter #(str/starts-with? (name %) "fact_") (keys data))
        fact-indices (set (map #(-> (name %)
                                    (str/split #"_")
                                    second
                                    Integer/parseInt)
                               fact-keys))]
    (mapv (fn [idx]
            (let [prefix (str "fact_" idx "_")]
              (into {} (keep (fn [[k v]]
                               (let [ks (name k)]
                                 (when (str/starts-with? ks prefix)
                                   [(keyword (subs ks (count prefix))) (normalize-yaml-value v)])))
                             data))))
          (sort fact-indices))))

;; -----------------------------------------------------------------------------
;; Serialization: Plan -> Markdown

(defn plan->markdown
  "Serialize a plan with its tasks and facts to a markdown document.
   Returns a string with YAML front matter and markdown content.
   Uses flat key structure compatible with CommonMark's YAML front matter."
  [plan tasks facts]
  (let [plan-fields (encode-plan-fields plan)
        task-fields (apply merge (map-indexed encode-task-fields tasks))
        fact-fields (apply merge (map-indexed encode-fact-fields facts))
        all-fields (merge plan-fields task-fields fact-fields)
        yaml-lines (map (fn [[k v]] (yaml-line k v)) all-fields)
        yaml-front-matter (str "---\n"
                               (str/join "\n" yaml-lines)
                               "\n---")
        content (or (:content plan) "")]
    (if (str/blank? content)
      yaml-front-matter
      (str yaml-front-matter "\n\n" content))))

;; -----------------------------------------------------------------------------
;; Deserialization: Markdown -> Plan

(defn markdown->plan
  "Deserialize a markdown document to plan data.
   Returns a map with :plan, :tasks, :facts keys."
  [^String markdown-text]
  (let [parser (md/create-parser)
        document (.parse ^Parser parser markdown-text)
        front-matter (md/extract-yaml-front-matter document)
        body-content (md/extract-body-content markdown-text)]
    {:plan (assoc (decode-plan-fields front-matter)
                  :content body-content)
     :tasks (decode-task-fields front-matter)
     :facts (decode-fact-fields front-matter)}))

;; -----------------------------------------------------------------------------
;; File I/O

(defn write-plan-to-file
  "Write a plan to a markdown file."
  [filepath plan tasks facts]
  (spit filepath (plan->markdown plan tasks facts)))

(defn read-plan-from-file
  "Read a plan from a markdown file."
  [filepath]
  (markdown->plan (slurp filepath)))

;; -----------------------------------------------------------------------------
;; Validation

(defn valid-plan-markdown?
  "Check if a markdown string contains valid plan data.
   Returns true if the front matter contains required plan fields."
  [markdown-text]
  (try
    (let [parser (md/create-parser)
          document (.parse ^Parser parser markdown-text)
          front-matter (md/extract-yaml-front-matter document)]
      (and (map? front-matter)
           (string? (:plan_name front-matter))
           (seq (:plan_name front-matter))))
    (catch Exception _
      false)))

;; -----------------------------------------------------------------------------
;; Metadata Helpers

(defn get-plan-metadata
  "Extract just the plan metadata from markdown (without tasks/facts)."
  [markdown-text]
  (let [parser (md/create-parser)
        document (.parse ^Parser parser markdown-text)
        front-matter (md/extract-yaml-front-matter document)
        body-content (md/extract-body-content markdown-text)]
    (assoc (decode-plan-fields front-matter)
           :content body-content)))

(defn count-tasks
  "Count the number of tasks in the markdown."
  [markdown-text]
  (let [parser (md/create-parser)
        document (.parse ^Parser parser markdown-text)
        front-matter (md/extract-yaml-front-matter document)]
    (count (decode-task-fields front-matter))))

(defn count-facts
  "Count the number of facts in the markdown."
  [markdown-text]
  (let [parser (md/create-parser)
        document (.parse ^Parser parser markdown-text)
        front-matter (md/extract-yaml-front-matter document)]
    (count (decode-fact-fields front-matter))))
